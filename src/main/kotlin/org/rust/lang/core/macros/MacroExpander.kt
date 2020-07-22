/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiBuilderUtil
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapiext.Testmark
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.util.SmartList
import org.rust.lang.core.parser.RustParserUtil.collapsedTokenType
import org.rust.lang.core.parser.createAdaptedRustPsiBuilder
import org.rust.lang.core.parser.rawLookupText
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.RsElementTypes.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.doc.psi.RsDocKind
import org.rust.openapiext.forEachChild
import org.rust.stdext.mapNotNullToSet
import org.rust.stdext.removeLast
import java.util.*
import java.util.Collections.singletonMap

/**
 * The actual algorithm for expansion is not too hard, but is pretty tricky.
 * `MacroSubstitution` structure is the key to understanding what we are doing here.
 *
 * On the high level, it stores mapping from meta variables to the bits of
 * syntax it should be substituted with. For example, if `$e:expr` is matched
 * with `1 + 1` by macro_rules, the `MacroSubstitution` will store `$e -> 1 + 1`.
 *
 * The tricky bit is dealing with repetitions (`$()*`). Consider this example:
 *
 * ```
 * macro_rules! foo {
 *     ($($ i:ident $($ e:expr),*);*) => {
 *         $(fn $ i() { $($ e);*; })*
 *     }
 * }
 * foo! { foo 1,2,3; bar 4,5,6 }
 * ```
 *
 * Here, the `$i` meta variable is matched first with `foo` and then with
 * `bar`, and `$e` is matched in turn with `1`, `2`, `3`, `4`, `5`, `6`.
 *
 * To represent such "multi-mappings", we use a recursive structures: we map
 * variables not to values, but to *lists* of values or other lists (that is,
 * to the trees).
 *
 * For the above example, the MacroSubstitution would store
 *
 * ```
 * i -> [foo, bar]
 * e -> [[1, 2, 3], [4, 5, 6]]
 * ```
 *
 * The comment copied from [rust-analyzer](https://github.com/rust-analyzer/rust-analyzer/blob/2dd8ba2b21/crates/ra_mbe/src/mbe_expander.rs#L58)
 */
data class MacroSubstitution(
    val variables: Map<String, MetaVarValue>
)

private fun MacroSubstitution.getVar(name: String, nesting: List<NestingState>): VarLookupResult {
    var value = variables[name] ?: return VarLookupResult.None
    loop@ for (nestingState in nesting) {
        nestingState.hit = true
        value = when (value) {
            is MetaVarValue.Fragment -> break@loop
            is MetaVarValue.Group -> {
                value.nested.getOrNull(nestingState.idx) ?: run {
                    nestingState.atTheEnd = true
                    return VarLookupResult.Error
                }
            }
            MetaVarValue.EmptyGroup -> {
                nestingState.atTheEnd = true
                return VarLookupResult.Error
            }
        }
    }
    return when (value) {
        is MetaVarValue.Fragment -> VarLookupResult.Ok(value)
        is MetaVarValue.Group -> VarLookupResult.Error
        MetaVarValue.EmptyGroup -> VarLookupResult.Error
    }
}

private sealed class VarLookupResult {
    class Ok(val value: MetaVarValue.Fragment) : VarLookupResult()
    object None : VarLookupResult()
    object Error : VarLookupResult()
}

sealed class MetaVarValue {
    data class Fragment(
        val value: String,
        val kind: FragmentKind?,
        val elementType: IElementType?,
        val offsetInCallBody: Int
    ) : MetaVarValue()

    data class Group(val nested: MutableList<MetaVarValue> = mutableListOf()) : MetaVarValue()
    object EmptyGroup : MetaVarValue()
}

private class NestingState(
    var idx: Int = 0,
    var hit: Boolean = false,
    var atTheEnd: Boolean = false
)

class MacroExpander(val project: Project) {
    fun expandMacroAsText(def: RsMacro, call: RsMacroCall): Pair<CharSequence, RangeMap>? {
        val (case, subst, loweringRanges) = findMatchingPattern(def, call) ?: return null
        val macroExpansion = case.macroExpansion?.macroExpansionContents ?: return null

        val substWithGlobalVars = MacroSubstitution(
            subst.variables + singletonMap("crate", MetaVarValue.Fragment(expandDollarCrateVar(call, def), null, null, -1))
        )

        return substituteMacro(macroExpansion, substWithGlobalVars)?.let { (text, ranges) ->
            text to loweringRanges.mapAll(ranges)
        }
    }

    private fun findMatchingPattern(
        def: RsMacro,
        call: RsMacroCall
    ): Triple<RsMacroCase, MacroSubstitution, RangeMap>? {
        val (macroCallBody, ranges) = project.createAdaptedRustPsiBuilder(call.macroBody ?: return null).lowerDocComments()
        macroCallBody.eof() // skip whitespace
        var start = macroCallBody.mark()
        val macroCaseList = def.macroBodyStubbed?.macroCaseList ?: return null

        for (case in macroCaseList) {
            val subst = case.pattern.match(macroCallBody)
            if (subst != null) {
                return Triple(case, subst, ranges)
            } else {
                start.rollbackTo()
                start = macroCallBody.mark()
            }
        }

        return null
    }

    /** Rustc replaces doc comments like `/// foo` to attributes `#[doc = "foo"]` before macro expansion */
    private fun PsiBuilder.lowerDocComments(): Pair<PsiBuilder, RangeMap> {
        if (!hasDocComments()) {
            return this to if (originalText.isNotEmpty()) {
                RangeMap.from(SmartList(MappedTextRange(0, 0, originalText.length)))
            } else {
                RangeMap.EMPTY
            }
        }

        MacroExpansionMarks.docsLowering.hit()

        val sb = StringBuilder((originalText.length * 1.1).toInt())
        val ranges = SmartList<MappedTextRange>()

        var i = 0
        while (true) {
            val token = rawLookup(i) ?: break
            val text = rawLookupText(i)
            val start = rawTokenTypeStart(i)
            i++

            if (token in RS_DOC_COMMENTS) {
                // TODO calculate how many `#` we should insert
                sb.append("#[doc=r###\"")
                RsDocKind.of(token).removeDecoration(text.splitToSequence("\n")).joinTo(sb, separator = "\n")
                sb.append("\"###]")
            } else {
                ranges.mergeAdd(MappedTextRange(start, sb.length, text.length))
                sb.append(text)
            }
        }

        return this@MacroExpander.project.createAdaptedRustPsiBuilder(sb) to RangeMap.from(ranges)
    }

    private fun PsiBuilder.hasDocComments(): Boolean {
        var i = 0
        while (true) {
            val token = rawLookup(i++) ?: break
            if (token in RS_DOC_COMMENTS) return true
        }
        return false
    }

    private fun substituteMacro(root: PsiElement, subst: MacroSubstitution): Pair<CharSequence, RangeMap>? {
        val sb = StringBuilder()
        val ranges = SmartList<MappedTextRange>()
        if (!substituteMacro(sb, ranges, root.node, subst, mutableListOf())) return null
        return sb to RangeMap.from(ranges)
    }

    private fun substituteMacro(
        sb: StringBuilder,
        ranges: MutableList<MappedTextRange>,
        root: ASTNode,
        subst: MacroSubstitution,
        nesting: MutableList<NestingState>
    ): Boolean {
        root.forEachChild { child ->
            when (child.elementType) {
                in RS_COMMENTS -> Unit
                MACRO_EXPANSION, MACRO_EXPANSION_CONTENTS ->
                    if (!substituteMacro(sb, ranges, child, subst, nesting)) return false
                MACRO_REFERENCE -> {
                    when (val result = subst.getVar((child.psi as RsMacroReference).referenceName, nesting)) {
                        is VarLookupResult.Ok -> {
                            val value = result.value
                            val parensNeeded = value.kind == FragmentKind.Expr
                                && value.elementType !in USELESS_PARENS_EXPRS
                            if (parensNeeded) {
                                sb.append("(")
                                sb.append(value.value)
                                sb.append(")")
                            } else {
                                sb.safeAppend(value.value)
                            }
                            if (value.offsetInCallBody != -1 && value.value.isNotEmpty()) {
                                ranges.mergeAdd(MappedTextRange(
                                    value.offsetInCallBody,
                                    sb.length - value.value.length - if (parensNeeded) 1 else 0,
                                    value.value.length
                                ))
                            }
                        }
                        VarLookupResult.None -> {
                            MacroExpansionMarks.substMetaVarNotFound.hit()
                            sb.safeAppend(child.text)
                            return@forEachChild
                        }
                        VarLookupResult.Error -> {
                            return true
                        }
                    }
                }
                MACRO_EXPANSION_REFERENCE_GROUP -> {
                    val childPsi = child.psi as RsMacroExpansionReferenceGroup
                    childPsi.macroExpansionContents?.let { contents ->
                        nesting += NestingState()
                        val separator = childPsi.macroExpansionGroupSeparator?.text ?: ""

                        for (i in 0 until 65536) {
                            val lastPosition = sb.length
                            if (!substituteMacro(sb, ranges, contents.node, subst, nesting)) return false
                            val nestingState = nesting.last()
                            if (nestingState.atTheEnd || !nestingState.hit) {
                                sb.delete(lastPosition, sb.length)
                                if (i != 0) {
                                    sb.delete(sb.length - separator.length, sb.length)
                                }
                                break
                            }
                            nestingState.idx += 1
                            nestingState.hit = false
                            sb.append(separator)
                        }

                        nesting.removeLast()
                    }
                }
                else -> {
//                    ranges += MappedTextRange(child.offsetInDefBody, sb.length, child.textLength, SourceType.MACRO_DEFINITION)
                    sb.safeAppend(child.chars)
                }
            }
        }
        return true
    }

    /** Ensures that the buffer ends (or [str] starts) with a whitespace and appends [str] to the buffer */
    private fun StringBuilder.safeAppend(str: CharSequence) {
        if (!isEmpty() && !last().isWhitespace() && !str.isEmpty() && !str.first().isWhitespace()) {
            append(" ")
        }
        append(str)
    }

    private fun MutableList<MappedTextRange>.mergeAdd(range: MappedTextRange) {
        val last = lastOrNull() ?: run {
            add(range)
            return
        }

        if (last.srcEndOffset == range.srcOffset && last.dstEndOffset == range.dstOffset) {
            set(size - 1, MappedTextRange(
                last.srcOffset,
                last.dstOffset,
                last.length + range.length
            ))
        } else {
            add(range)
        }
    }

    companion object {
        const val EXPANDER_VERSION = 6
        private val USELESS_PARENS_EXPRS = tokenSetOf(
            LIT_EXPR, MACRO_EXPR, PATH_EXPR, PAREN_EXPR, TUPLE_EXPR, ARRAY_EXPR, UNIT_EXPR
        )
    }
}

/**
 * Returns (synthetic) path from [call] to [def]'s crate
 * We can't just expand `$crate` to something like `::crate_name` because
 * we can pass a result of `$crate` expansion to another macro as a single identifier.
 *
 * Let's look at the example:
 * ```
 * // foo_crate
 * macro_rules! foo {
 *     () => { bar!($crate); } // $crate consumed as a single identifier by `bar!`
 * }
 * // bar_crate
 * macro_rules! bar {
 *     ($i:ident) => { fn f() { $i::some_item(); } }
 * }
 * // baz_crate
 * mod baz {
 *     foo!();
 * }
 * ```
 * Imagine that macros `foo`, `bar` and the foo's call are located in different
 * crates. In this case, when expanding `foo!()`, we should expand $crate to
 * `::foo_crate`. This `::` is needed to make the path to the crate guaranteed
 * absolutely (and also to make it work on rust 2015 edition).
 * Ok, let's look at the result of single step of `foo` macro expansion:
 * `foo!()` => `bar!(::foo_crate)`. Syntactic construction `::foo_crate` consists
 * of 2 tokens: `::` and `foo_crate` (identifier). BUT `bar` expects a single
 * identifier as an input! And this is successfully complied by the rust compiler!!
 *
 * The secret is that we should not really expand `$crate` to `::foo_crate`.
 * We should expand it to "something" that can be passed to another macro
 * as a single identifier.
 *
 * Rustc figures it out by synthetic token (without text representation).
 * Rustc can do it this way because its macro substitution is based on AST.
 * But our expansion is text-based, so we must provide something textual
 * that can be parsed as an identifier.
 *
 * It's a very awful hack and we know it.
 * DON'T TRY THIS AT HOME
 */
private fun expandDollarCrateVar(call: RsMacroCall, def: RsMacro): String {
    val defTarget = def.containingCargoTarget
    val callTarget = call.containingCargoTarget
    val crateName = if (defTarget == callTarget) "self" else defTarget?.normName ?: ""
    return MACRO_CRATE_IDENTIFIER_PREFIX + crateName
}

/** Prefix for synthetic identifier produced from `$crate` metavar. See [expandDollarCrateVar] */
const val MACRO_CRATE_IDENTIFIER_PREFIX: String = "IntellijRustDollarCrate_"

class MacroPattern private constructor(
    val pattern: Sequence<ASTNode>
) {
    fun match(macroCallBody: PsiBuilder): MacroSubstitution? {
        return matchPartial(macroCallBody)?.let { result ->
            if (!macroCallBody.eof()) {
                MacroExpansionMarks.failMatchPatternByExtraInput.hit()
                null
            } else {
                result
            }
        }
    }

    private fun isEmpty() = pattern.firstOrNull() == null

    private fun matchPartial(macroCallBody: PsiBuilder): MacroSubstitution? {
        ProgressManager.checkCanceled()
        val map = HashMap<String, MetaVarValue>()

        for (node in pattern) {
            when (node.elementType) {
                MACRO_BINDING -> {
                    val psi = node.psi as RsMacroBinding
                    val name = psi.metaVarIdentifier.text ?: return null
                    val fragmentSpecifier = psi.fragmentSpecifier ?: return null
                    val kind = FragmentKind.fromString(fragmentSpecifier) ?: return null

                    val lastOffset = macroCallBody.currentOffset
                    val parsed = kind.parse(macroCallBody)
                    if (!parsed || (lastOffset == macroCallBody.currentOffset && kind != FragmentKind.Vis)) {
                        MacroExpansionMarks.failMatchPatternByBindingType.hit()
                        return null
                    }
                    val text = macroCallBody.originalText.substring(lastOffset, macroCallBody.currentOffset)
                    val elementType = macroCallBody.latestDoneMarker?.tokenType
                    map[name] = MetaVarValue.Fragment(text, kind, elementType, lastOffset)
                }
                MACRO_BINDING_GROUP -> {
                    val psi = node.psi as RsMacroBindingGroup
                    val nesteds = matchGroup(psi, macroCallBody) ?: return null
                    nesteds.forEachIndexed { i, nested ->
                        for ((key, value) in nested.variables) {
                            val it = map.getOrPut(key) { MetaVarValue.Group() } as? MetaVarValue.Group
                                ?: return null
                            while (it.nested.size < i) {
                                it.nested += MetaVarValue.Group()
                            }
                            it.nested += value
                        }
                    }
                    if (nesteds.isEmpty()) {
                        val vars = psi.descendantsOfType<RsMacroBinding>().mapNotNullToSet { it.name }
                        for (v in vars) {
                            map[v] = MetaVarValue.EmptyGroup
                        }
                    }
                }
                else -> {
                    if (!macroCallBody.isSameToken(node)) {
                        MacroExpansionMarks.failMatchPatternByToken.hit()
                        return null
                    }
                }
            }
        }
        return MacroSubstitution(map)
    }


    private fun matchGroup(group: RsMacroBindingGroup, macroCallBody: PsiBuilder): List<MacroSubstitution>? {
        val groups = mutableListOf<MacroSubstitution>()
        val pattern = MacroPattern.valueOf(group.macroPatternContents ?: return null)
        if (pattern.isEmpty()) return null
        val separator = group.macroBindingGroupSeparator?.node?.firstChildNode
        var mark: PsiBuilder.Marker? = null

        while (true) {
            if (macroCallBody.eof()) {
                MacroExpansionMarks.groupInputEnd1.hit()
                mark?.rollbackTo()
                break
            }

            val lastOffset = macroCallBody.currentOffset
            val result = pattern.matchPartial(macroCallBody)
            if (result != null) {
                if (macroCallBody.currentOffset == lastOffset) {
                    MacroExpansionMarks.groupMatchedEmptyTT.hit()
                    return null
                }
                groups += result
            } else {
                MacroExpansionMarks.groupInputEnd2.hit()
                mark?.rollbackTo()
                break
            }

            if (macroCallBody.eof()) {
                MacroExpansionMarks.groupInputEnd3.hit()
                // Don't need to roll the marker back: we just successfully parsed a group
                break
            }

            if (group.q != null) {
                // `$(...)?` means "0 or 1 occurrences"
                MacroExpansionMarks.questionMarkGroupEnd.hit()
                break
            }

            mark?.drop()
            mark = macroCallBody.mark()

            if (separator != null) {
                if (!macroCallBody.isSameToken(separator)) {
                    MacroExpansionMarks.failMatchGroupBySeparator.hit()
                    break
                }
            }
        }

        val isExpectedAtLeastOne = group.plus != null
        if (isExpectedAtLeastOne && groups.isEmpty()) {
            MacroExpansionMarks.failMatchGroupTooFewElements.hit()
            return null
        }

        return groups
    }

    companion object {
        fun valueOf(psi: RsMacroPatternContents?): MacroPattern =
            MacroPattern(psi?.node?.childrenSkipWhitespaceAndComments()?.flatten() ?: emptySequence())

        private fun Sequence<ASTNode>.flatten(): Sequence<ASTNode> = flatMap {
            when (it.elementType) {
                MACRO_PATTERN, MACRO_PATTERN_CONTENTS ->
                    it.childrenSkipWhitespaceAndComments().flatten()
                else -> sequenceOf(it)
            }
        }

        private fun ASTNode.childrenSkipWhitespaceAndComments(): Sequence<ASTNode> =
            generateSequence(firstChildNode) { it.treeNext }
                .filter { it.elementType != TokenType.WHITE_SPACE && it.elementType !in RS_COMMENTS }
    }
}

private val RsMacroCase.pattern: MacroPattern
    get() = MacroPattern.valueOf(macroPattern.macroPatternContents)

private val COMPARE_BY_TEXT_TOKES = TokenSet.orSet(tokenSetOf(IDENTIFIER, QUOTE_IDENTIFIER), RS_LITERALS)

fun PsiBuilder.isSameToken(node: ASTNode): Boolean {
    val (elementType, size) = collapsedTokenType(this) ?: (tokenType to 1)
    val result = node.elementType == elementType && (elementType !in COMPARE_BY_TEXT_TOKES || node.chars == tokenText)
    if (result) {
        PsiBuilderUtil.advance(this, size)
    }
    return result
}

object MacroExpansionMarks {
    val failMatchPatternByToken = Testmark("failMatchPatternByToken")
    val failMatchPatternByExtraInput = Testmark("failMatchPatternByExtraInput")
    val failMatchPatternByBindingType = Testmark("failMatchPatternByBindingType")
    val failMatchGroupBySeparator = Testmark("failMatchGroupBySeparator")
    val failMatchGroupTooFewElements = Testmark("failMatchGroupTooFewElements")
    val questionMarkGroupEnd = Testmark("questionMarkGroupEnd")
    val groupInputEnd1 = Testmark("groupInputEnd1")
    val groupInputEnd2 = Testmark("groupInputEnd2")
    val groupInputEnd3 = Testmark("groupInputEnd3")
    val groupMatchedEmptyTT = Testmark("groupMatchedEmptyTT")
    val substMetaVarNotFound = Testmark("substMetaVarNotFound")
    val docsLowering = Testmark("docsLowering")
}
