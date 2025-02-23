/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.ty

import com.intellij.codeInsight.completion.CompletionUtil
import org.rust.lang.core.psi.RsTraitItem
import org.rust.lang.core.psi.RsTypeParameter
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.types.BoundElement
import org.rust.lang.core.types.HAS_TY_TYPE_PARAMETER_MASK
import org.rust.lang.core.types.infer.resolve
import org.rust.lang.core.types.regions.Region

class TyTypeParameter private constructor(
    val parameter: TypeParameter,
    traitBoundsSupplier: () -> Collection<BoundElement<RsTraitItem>>,
    regionBoundsSupplier: () -> Collection<Region>
) : Ty(HAS_TY_TYPE_PARAMETER_MASK) {

    private val traitBounds: Collection<BoundElement<RsTraitItem>>
        by lazy(LazyThreadSafetyMode.PUBLICATION, traitBoundsSupplier)

    val regionBounds: Collection<Region>
        by lazy(LazyThreadSafetyMode.PUBLICATION, regionBoundsSupplier)

    override fun equals(other: Any?): Boolean = other is TyTypeParameter && other.parameter == parameter
    override fun hashCode(): Int = parameter.hashCode()

    @Deprecated("Use ImplLookup.getEnvBoundTransitivelyFor")
    fun getTraitBoundsTransitively(): Collection<BoundElement<RsTraitItem>> =
        traitBounds.flatMap { it.flattenHierarchy }

    val name: String? get() = parameter.name

    sealed class TypeParameter {
        abstract val name: String?
    }

    object Self : TypeParameter() {
        override val name: String get() = "Self"
    }

    data class Named(val parameter: RsTypeParameter) : TypeParameter() {
        override val name: String? get() = parameter.name
    }

    companion object {
        private val self = TyTypeParameter(Self, { emptyList() }, { emptyList() })

        fun self(): TyTypeParameter = self

        fun self(item: RsTraitOrImpl): TyTypeParameter {
            return TyTypeParameter(
                Self,
                { listOfNotNull(item.implementedTrait) },
                { emptyList() }
            )
        }

        fun named(parameter: RsTypeParameter): TyTypeParameter {
            // Treat the same parameters from original/copy files as equals
            val originalParameter = CompletionUtil.getOriginalOrSelf(parameter)
            return TyTypeParameter(
                Named(originalParameter),
                { traitBounds(originalParameter) },
                { regionBounds(originalParameter) }
            )
        }
    }
}

private fun traitBounds(parameter: RsTypeParameter): List<BoundElement<RsTraitItem>> =
    parameter.bounds.mapNotNull {
        if (it.hasQ) return@mapNotNull null // Ignore `T: ?Sized`
        it.bound.traitRef?.resolveToBoundTrait()
    }

private fun regionBounds(parameter: RsTypeParameter): List<Region> =
    parameter.bounds.mapNotNull { it.bound.lifetime?.resolve() }
