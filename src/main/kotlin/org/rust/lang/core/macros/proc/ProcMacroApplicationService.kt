/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros.proc

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.util.Disposer
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.project.settings.RustProjectSettingsService
import org.rust.cargo.project.settings.rustSettings
import org.rust.cargo.toolchain.RsToolchainBase
import org.rust.cargo.toolchain.wsl.RsWslToolchain
import org.rust.ide.experiments.RsExperiments
import org.rust.openapiext.isFeatureEnabled
import java.nio.file.Path

@Service
class ProcMacroApplicationService : Disposable {
    private val servers: MutableMap<DistributionIdAndExpanderPath, ProcMacroServerPool?> = hashMapOf()

    init {
        val connect = ApplicationManager.getApplication().messageBus.connect(this)

        connect.subscribe(RustProjectSettingsService.RUST_SETTINGS_TOPIC, object : RustProjectSettingsService.RustSettingsListener {
            override fun rustSettingsChanged(e: RustProjectSettingsService.RustSettingsChangedEvent) {
                if (e.oldState.toolchain?.distributionId != e.newState.toolchain?.distributionId) {
                    removeUnusableSevers()
                }
            }
        })

        connect.subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
            override fun projectClosed(project: Project) {
                removeUnusableSevers()
            }
        })
    }

    @Synchronized
    fun getServer(toolchain: RsToolchainBase, procMacroExpanderPath: Path): ProcMacroServerPool? {
        if (!isEnabled()) return null

        val id = toolchain.distributionId
        val key = DistributionIdAndExpanderPath(id, procMacroExpanderPath)
        var server = servers[key]
        if (server == null) {
            server = ProcMacroServerPool.new(toolchain, procMacroExpanderPath, this)
            servers[key] = server
        }
        return server
    }

    @Synchronized
    private fun removeUnusableSevers() {
        val distributionIds = mutableSetOf<String>()
        val procMacroExpanderPaths = mutableSetOf<Path>()
        for (project in ProjectManager.getInstance().openProjects) {
            project.rustSettings.toolchain?.distributionId?.let { distributionIds.add(it) }
            for (cargoProject in project.cargoProjects.allProjects) {
                cargoProject.procMacroExpanderPath?.let { procMacroExpanderPaths.add(it) }
            }
        }
        servers.keys
            .filter { it.distributionId !in distributionIds || it.procMacroExpanderPath !in procMacroExpanderPaths }
            .mapNotNull { servers.remove(it) }
            .forEach { Disposer.dispose(it) }
    }

    override fun dispose() {}

    private data class DistributionIdAndExpanderPath(
        val distributionId: String,
        val procMacroExpanderPath: Path,
    )

    companion object {
        fun getInstance(): ProcMacroApplicationService = service()
        fun isEnabled(): Boolean = isFeatureEnabled(RsExperiments.PROC_MACROS)
            && isFeatureEnabled(RsExperiments.EVALUATE_BUILD_SCRIPTS)

        private val RsToolchainBase.distributionId: String
            get() = if (this is RsWslToolchain) wslPath.distributionId else "Local"
    }
}
