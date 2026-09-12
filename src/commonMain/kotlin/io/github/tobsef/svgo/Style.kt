package io.github.tobsef.svgo

import io.github.tobsef.svgo.css.AttribSelector
import io.github.tobsef.svgo.css.ClassSelector
import io.github.tobsef.svgo.css.CombinedSelector
import io.github.tobsef.svgo.css.FunctionSelector
import io.github.tobsef.svgo.css.HashSelector
import io.github.tobsef.svgo.css.NegationSelector
import io.github.tobsef.svgo.css.PseudoSelector
import io.github.tobsef.svgo.css.SelectorNode
import io.github.tobsef.svgo.css.TypeSelector
import io.github.tobsef.svgo.css.parseSelectorGroupOrNull

/**
 * Computed-style helpers. Port of `lib/style.js`.
 *
 * A "stylesheet" snapshot bundles the CSS rules collected from `<style>` elements, a node->parent
 * map, and a per-style-string declaration cache.
 */
public class ComputedStyle(
    public val type: String,
    public val inherited: Boolean,
    public val value: String? = null,
) {
    public val isStatic: Boolean get() = type == "static"
    public val isDynamic: Boolean get() = type == "dynamic"
}

public class Stylesheet(
    public val rules: List<Rule>,
    public val parents: Map<XastNode, XastParent?>,
    internal val declarationCache: MutableMap<String, List<Declaration>> = HashMap(),
)

private val presentationAttrs: Set<String> = attrsGroups.getValue("presentation")

public fun collectStylesheet(root: Root): Stylesheet {
    val rules = ArrayList<Rule>()
    val parents = LinkedHashMap<XastNode, XastParent?>()

    visit(
        root,
        Visitor(
            element = Callbacks(
                enter = { node, parentNode ->
                    parents[node] = parentNode
                    if (node.name == "style") {
                        val type = node.attributes["type"]
                        if (type == null || type == "" || type == "text/css") {
                            val media = node.attributes["media"]
                            val dynamic = media != null && media != "all"
                            for (child in node.children) {
                                when (child) {
                                    is Text -> rules.addAll(parseStylesheetRules(child.value, dynamic))
                                    is Cdata -> rules.addAll(parseStylesheetRules(child.value, dynamic))
                                    else -> {}
                                }
                            }
                        }
                    }
                },
            ),
        ),
    )

    // stable sort by specificity (ascending) -- later + more specific wins
    val sorted = rules.sortedWith { a, b -> compareSpecificity(a.specificity, b.specificity) }
    return Stylesheet(sorted, parents)
}

private fun computeOwnStyle(stylesheet: Stylesheet, node: Element): MutableMap<String, ComputedStyle> {
    val computedStyle = LinkedHashMap<String, ComputedStyle>()
    val importantStyles = HashMap<String, Boolean>()

    // collect presentation attributes
    for ((name, value) in node.attributes) {
        if (name in presentationAttrs) {
            computedStyle[name] = ComputedStyle("static", false, value)
            importantStyles[name] = false
        }
    }

    // collect matching rules
    for (rule in stylesheet.rules) {
        if (!matches(node, rule.selector, stylesheet.parents)) continue
        for (declaration in rule.declarations) {
            val name = declaration.name
            val computed = computedStyle[name]
            if (computed != null && computed.isDynamic) continue
            if (rule.dynamic) {
                computedStyle[name] = ComputedStyle("dynamic", false)
                continue
            }
            if (computed == null || declaration.important || importantStyles[name] == false) {
                computedStyle[name] = ComputedStyle("static", false, declaration.value)
                importantStyles[name] = declaration.important
            }
        }
    }

    // collect inline styles
    val styleAttr = node.attributes["style"]
    val styleDeclarations: List<Declaration> = if (styleAttr != null) {
        stylesheet.declarationCache.getOrPut(styleAttr) { parseStyleDeclarations(styleAttr) }
    } else {
        emptyList()
    }
    for (declaration in styleDeclarations) {
        val name = declaration.name
        val computed = computedStyle[name]
        if (computed != null && computed.isDynamic) continue
        if (computed == null || declaration.important || importantStyles[name] == false) {
            computedStyle[name] = ComputedStyle("static", false, declaration.value)
            importantStyles[name] = declaration.important
        }
    }

    return computedStyle
}

public fun computeStyle(stylesheet: Stylesheet, node: Element): Map<String, ComputedStyle> {
    val parents = stylesheet.parents
    val computedStyles = computeOwnStyle(stylesheet, node)
    var parent = parents[node]
    while (parent != null && parent !is Root) {
        val inheritedStyles = computeOwnStyle(stylesheet, parent as Element)
        for ((name, computed) in inheritedStyles) {
            if (computedStyles[name] == null &&
                name in inheritableAttrs &&
                name !in presentationNonInheritableGroupAttrs
            ) {
                computedStyles[name] = ComputedStyle(computed.type, true, computed.value)
            }
        }
        parent = parents[parent]
    }
    return computedStyles
}

// ---------------------------------------------------------------------------
// selector introspection
// ---------------------------------------------------------------------------

internal class SelectorSegment(val type: String, val name: String? = null, val value: String? = null)

/**
 * Flatten a complex-selector tree into an ordered segment list with explicit `combinator` markers
 * between compounds (source order).
 */
internal fun flattenSelector(tree: SelectorNode): List<SelectorSegment> {
    val segments = ArrayList<SelectorSegment>()

    fun walk(node: SelectorNode) {
        if (node is CombinedSelector) {
            walk(node.selector)
            segments.add(SelectorSegment("combinator"))
            walk(node.subselector)
            return
        }
        when (node) {
            is HashSelector -> {
                walk(node.selector)
                segments.add(SelectorSegment("attribute", "id", node.id))
            }
            is ClassSelector -> {
                walk(node.selector)
                segments.add(SelectorSegment("attribute", "class", node.className))
            }
            is AttribSelector -> {
                walk(node.selector)
                segments.add(SelectorSegment("attribute", node.attrib, node.value))
            }
            is PseudoSelector -> walk(node.selector)
            is FunctionSelector -> walk(node.selector)
            is NegationSelector -> walk(node.selector)
            is TypeSelector -> {}
            else -> {}
        }
    }

    walk(tree)
    return segments
}

/**
 * Whether a CSS selector includes/traverses the given attribute. Classes and ids are represented as
 * attribute selectors `class` / `id`.
 */
public fun includesAttrSelector(
    selector: String,
    name: String,
    value: String? = null,
    traversed: Boolean = false,
): Boolean {
    val parsed = parseSelectorGroupOrNull(selector) ?: return false
    for (sel in parsed) {
        val segments = flattenSelector(sel.parsedTree)
        val size = segments.size
        for (index in segments.indices) {
            val segment = segments[index]
            if (traversed) {
                if (index == size - 1) continue
                if (segments[index + 1].type != "combinator") continue
            }
            if (segment.type != "attribute" || segment.name != name) continue
            if (value == null || segment.value == value) return true
        }
    }
    return false
}

/** Every attribute name referenced (as attribute, class or id selector) by a CSS selector. */
public fun selectorAttributeNames(selector: String): Set<String> {
    val parsed = parseSelectorGroupOrNull(selector) ?: return emptySet()
    val result = LinkedHashSet<String>()
    for (sel in parsed) {
        for (segment in flattenSelector(sel.parsedTree)) {
            if (segment.type == "attribute" && segment.name != null) result.add(segment.name)
        }
    }
    return result
}
