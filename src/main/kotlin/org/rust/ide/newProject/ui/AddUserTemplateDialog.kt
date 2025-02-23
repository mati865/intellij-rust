/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.newProject.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import org.rust.RsBundle
import org.rust.ide.newProject.state.RsUserTemplate
import org.rust.ide.newProject.state.RsUserTemplatesState
import org.rust.openapiext.addTextChangeListener
import org.rust.openapiext.fullWidthCell
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

class AddUserTemplateDialog : DialogWrapper(null) {
    private val repoUrlField: JBTextField = JBTextField().apply {
        addTextChangeListener(::suggestName)
    }

    private val nameField: JBTextField = JBTextField()

    init {
        title = RsBundle.message("dialog.create.project.custom.add.template.title")
        setOKButtonText(RsBundle.message("dialog.create.project.custom.add.template.action.add"))
        init()
    }

    override fun getPreferredFocusedComponent(): JComponent = repoUrlField

    override fun createCenterPanel(): JComponent = panel {
        row(RsBundle.message("dialog.create.project.custom.add.template.url")) {
            fullWidthCell(repoUrlField)
                .comment(RsBundle.message("dialog.create.project.custom.add.template.url.description"))
        }
        row(RsBundle.message("dialog.create.project.custom.add.template.name")) {
            fullWidthCell(nameField)
        }
    }

    override fun doOKAction() {
        // TODO: Find a better way to handle dialog form validation
        if (nameField.text.isBlank()) return
        if (RsUserTemplatesState.getInstance().templates.any { it.name == nameField.text }) return

        RsUserTemplatesState.getInstance().templates.add(
            RsUserTemplate(nameField.text, repoUrlField.text)
        )

        super.doOKAction()
    }

    private fun suggestName(event: DocumentEvent) {
        if (nameField.text.isNotBlank()) return
        if (event.length != repoUrlField.text.length) return
        if (KNOWN_URL_PREFIXES.none { repoUrlField.text.startsWith(it) }) return

        nameField.text = repoUrlField.text
            .removeSuffix("/")
            .removeSuffix(".git")
            .substringAfterLast("/")
    }

    companion object {
        private val KNOWN_URL_PREFIXES = listOf("http://", "https://")
    }
}
