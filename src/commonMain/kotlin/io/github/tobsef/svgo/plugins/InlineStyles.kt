package io.github.tobsef.svgo.plugins

import io.github.tobsef.svgo.Callbacks
import io.github.tobsef.svgo.Cdata
import io.github.tobsef.svgo.Declaration
import io.github.tobsef.svgo.Element
import io.github.tobsef.svgo.PluginDefinition
import io.github.tobsef.svgo.RootCallbacks
import io.github.tobsef.svgo.Text
import io.github.tobsef.svgo.VISIT_SKIP
import io.github.tobsef.svgo.Visitor
import io.github.tobsef.svgo.XastNode
import io.github.tobsef.svgo.XastParent
import io.github.tobsef.svgo.attrsGroups
import io.github.tobsef.svgo.buildParents
import io.github.tobsef.svgo.compareSpecificity
import io.github.tobsef.svgo.css.AtRule
import io.github.tobsef.svgo.css.AttribSelector
import io.github.tobsef.svgo.css.ClassSelector
import io.github.tobsef.svgo.css.CombinedSelector
import io.github.tobsef.svgo.css.FunctionSelector
import io.github.tobsef.svgo.css.HashSelector
import io.github.tobsef.svgo.css.NegationSelector
import io.github.tobsef.svgo.css.PseudoSelector
import io.github.tobsef.svgo.css.QualifiedRule
import io.github.tobsef.svgo.css.SelectorNode
import io.github.tobsef.svgo.css.TypeSelector
import io.github.tobsef.svgo.css.parseRuleList
import io.github.tobsef.svgo.css.parseSelectorGroupOrNull
import io.github.tobsef.svgo.css.parseStylesheet
import io.github.tobsef.svgo.css.parseComponentValueList
import io.github.tobsef.svgo.css.serialize
import io.github.tobsef.svgo.minifySelectorTokens
import io.github.tobsef.svgo.declarationsOf
import io.github.tobsef.svgo.detachNodeFromParent
import io.github.tobsef.svgo.generateAtRulePrelude
import io.github.tobsef.svgo.includesAttrSelector
import io.github.tobsef.svgo.parseStyleDeclarations
import io.github.tobsef.svgo.pseudoClasses
import io.github.tobsef.svgo.querySelectorAll
import io.github.tobsef.svgo.splitSelectorList

/**
 * inline styles (additional options)
 *
 * Like upstream, the stylesheet is kept as a tree: matched selectors are removed from their rule's
 * selector list, rules left without a selector are dropped, and the whole tree is regenerated.
 */

private val presentationProps: Set<String> = attrsGroups.getValue("presentation")
private val preservedPseudos: Set<String> =
    pseudoClasses["functional"].orEmpty() + pseudoClasses["treeStructural"].orEmpty()

private val REG_WS_RUN_INLINE = Regex("""\s+""")
private val REG_COMBINATOR_INLINE = Regex("""\s*([>+~])\s*""")
private val REG_PSEUDO_FRAGMENT = Regex("""::?[A-Za-z-]+(\([^)]*\))?""")

private fun minifySelectorText(text: String): String = try {
    minifySelectorTokens(parseComponentValueList(text))
} catch (_: Exception) {
    REG_COMBINATOR_INLINE.replace(REG_WS_RUN_INLINE.replace(text.trim(), " ")) { it.groupValues[1] }
}

/** One selector of a rule's selector list. */
private class InlineSelector(
    val text: String,
    /** `null` when the selector could not be parsed; such selectors never match and are kept. */
    val tree: SelectorNode?,
    val specificity: IntArray,
    val rule: InlineRule,
) {
    val canonical: String = minifySelectorText(text)
    var matched: List<Element>? = null
}

private class InlineRule(node: QualifiedRule) {
    val declarations: List<Declaration> = declarationsOf(node.content ?: emptyList())

    /** Mutable: matched selectors are removed, and an empty list drops the whole rule. */
    val selectors = ArrayList<InlineSelector>()
}

/** The regenerable shape of a parsed stylesheet. */
private sealed class StyleItem

private class RuleItem(val rule: InlineRule) : StyleItem()

private class AtRuleItem(
    val header: String,
    /** Nested rules, or `null` when the at-rule has no block or a declaration-only block. */
    val children: List<StyleItem>?,
    /** Rendered body of a declaration-only block, or `null` when the at-rule has no block. */
    val declarations: String?,
) : StyleItem()

private class StyleEntry(
    val node: Element,
    val parent: XastParent,
    val items: List<StyleItem>,
)

private fun serializeDeclarationList(declarations: List<Declaration>): String =
    declarations.joinToString(";") { "${it.name}:${it.value}${if (it.important) "!important" else ""}" }

/**
 * The first *written* simple selector of the leftmost compound, mirroring csstree's
 * `selector.children.first` (ignoring the implicit universal `*`).
 */
private fun firstWrittenSimple(tree: SelectorNode): SelectorNode {
    var node = tree
    while (node is CombinedSelector) node = node.selector
    var previous: SelectorNode? = null
    var current: SelectorNode? = node
    while (current != null) {
        if (current is TypeSelector) {
            return if (current.element != null && current.element != "*") current else previous ?: current
        }
        previous = current
        current = when (current) {
            is HashSelector -> current.selector
            is ClassSelector -> current.selector
            is AttribSelector -> current.selector
            is PseudoSelector -> current.selector
            is FunctionSelector -> current.selector
            is NegationSelector -> current.selector
            else -> null
        }
    }
    return node
}

private fun selectorClasses(tree: SelectorNode): List<String> {
    val classes = ArrayList<String>()

    fun walk(node: SelectorNode) {
        if (node is CombinedSelector) {
            walk(node.selector)
            walk(node.subselector)
            return
        }
        when (node) {
            is HashSelector -> walk(node.selector)
            is ClassSelector -> {
                walk(node.selector)
                classes.add(node.className)
            }
            is AttribSelector -> walk(node.selector)
            is PseudoSelector -> walk(node.selector)
            is FunctionSelector -> walk(node.selector)
            is NegationSelector -> walk(node.selector)
            else -> {}
        }
    }

    walk(tree)
    return classes
}

private fun collectNonPreservedPseudos(tree: SelectorNode): List<String> {
    val found = ArrayList<String>()

    fun walk(node: SelectorNode) {
        when (node) {
            is HashSelector -> walk(node.selector)
            is ClassSelector -> walk(node.selector)
            is AttribSelector -> walk(node.selector)
            is NegationSelector -> walk(node.selector)
            is PseudoSelector -> {
                walk(node.selector)
                if (node.ident !in preservedPseudos) found.add(":" + node.ident)
            }
            is FunctionSelector -> {
                walk(node.selector)
                if (node.name !in preservedPseudos) found.add(":" + node.name + "()")
            }
            is CombinedSelector -> {
                walk(node.selector)
                walk(node.subselector)
            }
            is TypeSelector -> {}
        }
    }

    walk(tree)
    return found
}

/**
 * Return the selector text with non-preserved pseudos removed when their combined text is listed in
 * [usePseudos]; otherwise return the original text.
 */
private fun processPseudos(selectorText: String, usePseudos: List<String>): String {
    val parsed = parseSelectorGroupOrNull(selectorText) ?: return selectorText
    val pseudos = if (parsed.isNotEmpty()) collectNonPreservedPseudos(parsed[0].parsedTree) else emptyList()
    val combined = pseudos.joinToString("")
    if (pseudos.isNotEmpty() && combined in usePseudos) {
        return REG_PSEUDO_FRAGMENT.replace(selectorText, "").trim()
    }
    return selectorText
}

public val inlineStyles: PluginDefinition = PluginDefinition(
    "inlineStyles",
    "inline styles (additional options)",
) { root, params, _ ->
    val onlyMatchedOnce = params.bool("onlyMatchedOnce", true)
    val removeMatchedSelectors = params.bool("removeMatchedSelectors", true)
    val useMqs = params.stringList("useMqs") ?: listOf("", "screen")
    val usePseudos = params.stringList("usePseudos") ?: listOf("")

    val styles = ArrayList<StyleEntry>()
    val selectors = ArrayList<InlineSelector>()

    /**
     * Build the regenerable item list of a rule list. [mediaQuery] is the enclosing at-rule's
     * signature; only rules whose enclosing media query is listed in `useMqs` take part in matching.
     */
    fun buildItems(nodes: List<io.github.tobsef.svgo.css.CssNode>, mediaQuery: String): List<StyleItem> {
        val items = ArrayList<StyleItem>()
        for (cssNode in nodes) {
            when (cssNode) {
                is QualifiedRule -> {
                    val rule = InlineRule(cssNode)
                    val collect = mediaQuery in useMqs
                    for (rawSelector in splitSelectorList(serialize(cssNode.prelude).trim())) {
                        val selectorText = rawSelector.trim()
                        if (selectorText.isEmpty()) continue
                        val processed = if (collect) processPseudos(selectorText, usePseudos) else selectorText
                        val parsed = parseSelectorGroupOrNull(processed)
                        if (parsed == null) {
                            // keep selectors we cannot parse verbatim; they never match
                            rule.selectors.add(
                                InlineSelector(processed, null, intArrayOf(0, 0, 0), rule),
                            )
                            continue
                        }
                        for (parsedSelector in parsed) {
                            val entry = InlineSelector(
                                processed,
                                parsedSelector.parsedTree,
                                parsedSelector.specificity(),
                                rule,
                            )
                            rule.selectors.add(entry)
                            if (collect) selectors.add(entry)
                        }
                    }
                    items.add(RuleItem(rule))
                }

                is AtRule -> {
                    val preludeText = generateAtRulePrelude(cssNode.prelude)
                    val header = "@" + cssNode.atKeyword + (if (preludeText.isNotEmpty()) " $preludeText" else "")
                    val content = cssNode.content
                    if (content == null) {
                        items.add(AtRuleItem(header, null, null))
                    } else {
                        val inner = parseRuleList(content)
                        if (inner.any { it is QualifiedRule || it is AtRule }) {
                            val nestedQuery =
                                cssNode.atKeyword + (if (preludeText.isNotEmpty()) " $preludeText" else "")
                            items.add(AtRuleItem(header, buildItems(inner, nestedQuery), null))
                        } else {
                            items.add(AtRuleItem(header, null, serializeDeclarationList(declarationsOf(content))))
                        }
                    }
                }

                else -> {}
            }
        }
        return items
    }

    Visitor(
        element = Callbacks(
            enter = { node, parentNode ->
                var result: Any? = null
                if (node.name == "foreignObject") {
                    result = VISIT_SKIP
                } else if (node.name == "style" && node.children.isNotEmpty()) {
                    val type = node.attributes["type"]
                    if (type == null || type == "" || type == "text/css") {
                        val cssText = node.children.joinToString("") { child ->
                            when (child) {
                                is Text -> child.value
                                is Cdata -> child.value
                                else -> ""
                            }
                        }

                        val nodes = try {
                            parseStylesheet(cssText)
                        } catch (_: Exception) {
                            null
                        }

                        if (nodes != null) {
                            styles.add(StyleEntry(node, parentNode, buildItems(nodes, "")))
                        }
                    }
                }
                result
            },
        ),
        root = RootCallbacks(
            exit = {
                if (styles.isNotEmpty()) {
                    val sortedSelectors = selectors
                        .sortedWith { a, b -> compareSpecificity(a.specificity, b.specificity) }
                        .reversed()

                    val parents = LinkedHashMap<XastNode, XastParent?>()
                    buildParents(root, parents)

                    for (selector in sortedSelectors) {
                        if (selector.tree == null) continue
                        val matchedElements = try {
                            querySelectorAll(root, selector.text, parents, strict = true)
                        } catch (_: Exception) {
                            continue
                        }
                        if (matchedElements.isEmpty()) continue
                        if (onlyMatchedOnce && matchedElements.size > 1) continue

                        for (selectedElement in matchedElements) {
                            applyRuleToElement(selectedElement, selector.rule, selectors)
                        }

                        if (removeMatchedSelectors) {
                            selector.rule.selectors.removeAll { it === selector }
                        }
                        selector.matched = matchedElements
                    }

                    if (removeMatchedSelectors) {
                        for (selector in sortedSelectors) {
                            val matched = selector.matched ?: continue
                            val tree = selector.tree ?: continue
                            if (onlyMatchedOnce && matched.size > 1) continue
                            for (selectedElement in matched) {
                                val classAttr = selectedElement.attributes["class"]
                                val classSet = if (classAttr != null) {
                                    classAttr.split(" ").distinct().toMutableList()
                                } else {
                                    mutableListOf()
                                }

                                for (className in selectorClasses(tree)) {
                                    if (selectors.none {
                                            includesAttrSelector(it.text, "class", className, true)
                                        }
                                    ) {
                                        classSet.remove(className)
                                    }
                                }

                                if (classSet.isEmpty()) {
                                    selectedElement.attributes.remove("class")
                                } else {
                                    selectedElement.attributes["class"] = classSet.joinToString(" ")
                                }

                                val leading = firstWrittenSimple(tree)
                                if (leading is HashSelector &&
                                    selectedElement.attributes["id"] == leading.id &&
                                    selectors.none { includesAttrSelector(it.text, "id", leading.id, true) }
                                ) {
                                    selectedElement.attributes.remove("id")
                                }
                            }
                        }
                    }

                    regenerate(styles)
                }
            },
        ),
    )
}

private fun applyRuleToElement(
    selectedElement: Element,
    rule: InlineRule,
    allSelectors: List<InlineSelector>,
) {
    val styleAttr = selectedElement.attributes["style"] ?: ""
    val declarations = parseStyleDeclarations(styleAttr).toMutableList()
    // first original declaration (new rule declarations are inserted before it, mirroring
    // csstree's insert-before-firstListItem)
    val firstOriginal = declarations.firstOrNull()
    // Keyed by identity, and -- like upstream -- only ever updated when an existing declaration is
    // replaced, never when one is inserted. A rule that declares the same property twice therefore
    // contributes both, in source order, and the later one wins once the block is minified.
    val matched = HashMap<String, Declaration>()
    for (declaration in declarations) matched[declaration.name.lowercase()] = declaration

    for (ruleDeclaration in rule.declarations) {
        val property = ruleDeclaration.name
        if (property in presentationProps &&
            allSelectors.none { includesAttrSelector(it.text, property) }
        ) {
            selectedElement.attributes.remove(property)
        }

        val key = property.lowercase()
        val existing = matched[key]
        val newDeclaration = Declaration(property, ruleDeclaration.value, ruleDeclaration.important)
        if (existing == null) {
            if (firstOriginal == null) {
                declarations.add(newDeclaration)
            } else {
                val at = declarations.indexOfFirst { it === firstOriginal }
                declarations.add(if (at >= 0) at else declarations.size, newDeclaration)
            }
        } else if (!existing.important && ruleDeclaration.important) {
            val at = declarations.indexOfFirst { it === existing }
            if (at >= 0) declarations[at] = newDeclaration
            matched[key] = newDeclaration
        }
    }

    val newStyles = serializeDeclarationList(declarations)
    if (newStyles.isNotEmpty()) selectedElement.attributes["style"] = newStyles
}

private fun renderItems(items: List<StyleItem>): String {
    val rendered = StringBuilder()
    for (item in items) {
        when (item) {
            is RuleItem -> {
                // rules left without a selector are dropped
                if (item.rule.selectors.isEmpty()) continue
                rendered.append(item.rule.selectors.joinToString(",") { it.canonical })
                    .append("{").append(serializeDeclarationList(item.rule.declarations)).append("}")
            }

            is AtRuleItem -> {
                rendered.append(item.header)
                when {
                    item.children != null -> rendered.append("{").append(renderItems(item.children)).append("}")
                    item.declarations != null -> rendered.append("{").append(item.declarations).append("}")
                    else -> rendered.append(";")
                }
            }
        }
    }
    return rendered.toString()
}

private fun regenerate(styles: List<StyleEntry>) {
    for (style in styles) {
        val text = renderItems(style.items)
        if (text.isEmpty()) {
            detachNodeFromParent(style.node, style.parent)
        } else {
            when (val firstChild = style.node.children.firstOrNull()) {
                is Text -> firstChild.value = text
                is Cdata -> firstChild.value = text
                else -> {}
            }
        }
    }
}
