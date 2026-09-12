package io.github.tobsef.svgo.plugins

import io.github.tobsef.svgo.Callbacks
import io.github.tobsef.svgo.Cdata
import io.github.tobsef.svgo.Element
import io.github.tobsef.svgo.PathItem
import io.github.tobsef.svgo.PluginDefinition
import io.github.tobsef.svgo.Root
import io.github.tobsef.svgo.RootCallbacks
import io.github.tobsef.svgo.Text
import io.github.tobsef.svgo.VISIT_SKIP
import io.github.tobsef.svgo.Visitor
import io.github.tobsef.svgo.XastChild
import io.github.tobsef.svgo.XastNode
import io.github.tobsef.svgo.XastParent
import io.github.tobsef.svgo.CssUsage
import io.github.tobsef.svgo.collectStylesheet
import io.github.tobsef.svgo.computeStyle
import io.github.tobsef.svgo.detachNodeFromParent
import io.github.tobsef.svgo.elemsGroups
import io.github.tobsef.svgo.findReferences
import io.github.tobsef.svgo.hasScripts
import io.github.tobsef.svgo.includesUrlReference
import io.github.tobsef.svgo.indexOfIdentity
import io.github.tobsef.svgo.inheritableAttrs
import io.github.tobsef.svgo.intersects
import io.github.tobsef.svgo.js2path
import io.github.tobsef.svgo.minifyBlock
import io.github.tobsef.svgo.minifyCss
import io.github.tobsef.svgo.path2js
import io.github.tobsef.svgo.pathElems
import io.github.tobsef.svgo.referencesProps
import io.github.tobsef.svgo.visit

private val WHITESPACE_RUN = Regex("""\s+""")

// ---------------------------------------------------------------------------
// mergeStyles
// ---------------------------------------------------------------------------

/** merge multiple style elements into one */
public val mergeStyles: PluginDefinition = PluginDefinition(
    "mergeStyles",
    "merge multiple style elements into one",
) { _, _, _ ->
    var firstStyleElement: Element? = null
    var collectedStyles = ""
    var contentType = "text"

    Visitor(
        element = Callbacks(
            enter = { node, parentNode ->
                var result: Any? = null
                if (node.name == "foreignObject") {
                    result = VISIT_SKIP
                } else if (node.name == "style") {
                    val type = node.attributes["type"]
                    if (type == null || type == "" || type == "text/css") {
                        var css = ""
                        for (child in node.children) {
                            when (child) {
                                is Text -> css += child.value
                                is Cdata -> {
                                    contentType = "cdata"; css += child.value
                                }
                                else -> {}
                            }
                        }

                        if (css.trim().isEmpty()) {
                            detachNodeFromParent(node, parentNode)
                        } else {
                            val media = node.attributes["media"]
                            if (media == null) {
                                collectedStyles += css
                            } else {
                                collectedStyles += "@media $media{$css}"
                                node.attributes.remove("media")
                            }

                            if (firstStyleElement == null) {
                                firstStyleElement = node
                            } else {
                                detachNodeFromParent(node, parentNode)
                                val child: XastChild = if (contentType == "cdata") {
                                    Cdata(collectedStyles)
                                } else {
                                    Text(collectedStyles)
                                }
                                firstStyleElement!!.children = mutableListOf(child)
                            }
                        }
                    }
                }
                result
            },
        ),
    )
}

// ---------------------------------------------------------------------------
// minifyStyles
// ---------------------------------------------------------------------------

/**
 * minifies styles and removes unused styles
 *
 * NOTE: SVGO uses the JS `csso` library, which performs structural CSS optimizations (shorthand
 * merging, rule restructuring, usage-based dead-code elimination). This port provides
 * value/whitespace-level minification only.
 */
public val minifyStyles: PluginDefinition = PluginDefinition(
    "minifyStyles",
    "minifies styles and removes unused styles",
) { _, params, _ ->
    val usageParam = if (params.has("usage")) params.raw("usage") else null
    var enableTagsUsage = true
    var enableIdsUsage = true
    var enableClassesUsage = true
    var forceUsageDeoptimized = false
    if (usageParam is Boolean) {
        enableTagsUsage = usageParam
        enableIdsUsage = usageParam
        enableClassesUsage = usageParam
    } else if (usageParam is Map<*, *>) {
        enableTagsUsage = usageParam["tags"] as? Boolean ?: true
        enableIdsUsage = usageParam["ids"] as? Boolean ?: true
        enableClassesUsage = usageParam["classes"] as? Boolean ?: true
        forceUsageDeoptimized = usageParam["force"] as? Boolean ?: false
    }

    val styleElements = ArrayList<Pair<Element, XastParent>>()
    val elementsWithStyleAttr = ArrayList<Element>()
    val tagsUsage = LinkedHashSet<String>()
    val idsUsage = LinkedHashSet<String>()
    val classesUsage = LinkedHashSet<String>()
    var deoptimized = false

    Visitor(
        element = Callbacks(
            enter = { node, parentNode ->
                // detect deoptimizations
                if (hasScripts(node)) deoptimized = true

                // collect tags, ids and classes usage
                tagsUsage.add(node.name.lowercase())
                node.attributes["id"]?.let { idsUsage.add(it) }
                node.attributes["class"]?.let { classes ->
                    for (className in classes.split(WHITESPACE_RUN)) {
                        if (className.isNotEmpty()) classesUsage.add(className)
                    }
                }

                if (node.name == "style" && node.children.isNotEmpty()) {
                    styleElements.add(node to parentNode)
                } else if (node.attributes["style"] != null) {
                    elementsWithStyleAttr.add(node)
                }
            },
        ),
        root = RootCallbacks(
            exit = {
                val usage = if (!deoptimized || forceUsageDeoptimized) {
                    CssUsage(
                        tags = if (enableTagsUsage) tagsUsage else null,
                        ids = if (enableIdsUsage) idsUsage else null,
                        classes = if (enableClassesUsage) classesUsage else null,
                    )
                } else {
                    null
                }

                for ((styleNode, styleParent) in styleElements) {
                    val first = styleNode.children[0]
                    val cssText = when (first) {
                        is Text -> first.value
                        is Cdata -> first.value
                        else -> null
                    } ?: continue
                    val minified = minifyCss(cssText, usage = usage)
                    if (minified.isEmpty()) {
                        detachNodeFromParent(styleNode, styleParent)
                        continue
                    }
                    // preserve cdata if the content contains markup characters
                    styleNode.children[0] = if ('>' in cssText || '<' in cssText) {
                        Cdata(minified)
                    } else {
                        Text(minified)
                    }
                }

                for (node in elementsWithStyleAttr) {
                    node.attributes["style"] = minifyBlock(node.attributes["style"]!!)
                }
            },
        ),
    )
}

// ---------------------------------------------------------------------------
// collapseGroups
// ---------------------------------------------------------------------------

private val animationElems: Set<String> = elemsGroups.getValue("animation")

private fun hasAnimatedAttr(node: XastNode, attrName: String): Boolean {
    if (node is Element) {
        if (node.name in animationElems && node.attributes["attributeName"] == attrName) return true
        for (child in node.children) if (hasAnimatedAttr(child, attrName)) return true
    }
    return false
}

/** collapses useless groups */
public val collapseGroups: PluginDefinition = PluginDefinition(
    "collapseGroups",
    "collapses useless groups",
) { root, _, _ ->
    val stylesheet = collectStylesheet(root)

    Visitor(
        element = Callbacks(
            exit = { node, parentNode ->
                if (parentNode is Root || (parentNode is Element && parentNode.name == "switch")) {
                    return@Callbacks Unit
                }
                if (node.name != "g" || node.children.isEmpty()) return@Callbacks Unit

                // move group attributes to the single child element
                if (node.attributes.isNotEmpty() && node.children.size == 1) {
                    val firstChild = node.children[0]
                    val nodeHasFilter = node.attributes["filter"] != null ||
                        computeStyle(stylesheet, node)["filter"] != null
                    if (firstChild is Element &&
                        firstChild.attributes["id"] == null &&
                        !nodeHasFilter &&
                        (node.attributes["class"] == null || firstChild.attributes["class"] == null) &&
                        (
                            (node.attributes["clip-path"] == null && node.attributes["mask"] == null) ||
                                (
                                    firstChild.name == "g" &&
                                        node.attributes["transform"] == null &&
                                        firstChild.attributes["transform"] == null
                                    )
                            )
                    ) {
                        val newAttrs = LinkedHashMap(firstChild.attributes)
                        for ((attrName, value) in node.attributes) {
                            if (hasAnimatedAttr(firstChild, attrName)) return@Callbacks Unit
                            val existing = newAttrs[attrName]
                            when {
                                existing == null -> newAttrs[attrName] = value
                                attrName == "transform" -> newAttrs[attrName] = "$value $existing"
                                existing == "inherit" -> newAttrs[attrName] = value
                                attrName !in inheritableAttrs && existing != value -> return@Callbacks Unit
                            }
                        }
                        node.attributes = LinkedHashMap()
                        firstChild.attributes = newAttrs
                    }
                }

                // collapse groups without attributes
                if (node.attributes.isEmpty()) {
                    for (child in node.children) {
                        if (child is Element && child.name in animationElems) return@Callbacks Unit
                    }
                    val index = parentNode.children.indexOfIdentity(node)
                    if (index >= 0) {
                        val newChildren = ArrayList<XastChild>(parentNode.children.size + node.children.size)
                        newChildren.addAll(parentNode.children.subList(0, index))
                        newChildren.addAll(node.children)
                        newChildren.addAll(parentNode.children.subList(index + 1, parentNode.children.size))
                        parentNode.children = newChildren
                    }
                }
                Unit
            },
        ),
    )
}

// ---------------------------------------------------------------------------
// moveElemsAttrsToGroup
// ---------------------------------------------------------------------------

/** Move common attributes of group children to the group */
public val moveElemsAttrsToGroup: PluginDefinition = PluginDefinition(
    "moveElemsAttrsToGroup",
    "Move common attributes of group children to the group",
) { root, _, _ ->
    var deoptimized = false
    visit(
        root,
        Visitor(element = Callbacks(enter = { node, _ -> if (node.name == "style") deoptimized = true })),
    )

    Visitor(
        element = Callbacks(
            exit = { node, _ ->
                if (node.name == "g" && node.children.size > 1 && !deoptimized) {
                    val common = LinkedHashMap<String, String?>()
                    var initial = true
                    var everyChildIsPath = true
                    for (child in node.children) {
                        if (child is Element) {
                            if (child.name !in pathElems) everyChildIsPath = false
                            if (initial) {
                                initial = false
                                for ((attrName, value) in child.attributes) {
                                    if (attrName in inheritableAttrs) common[attrName] = value
                                }
                            } else {
                                for (attrName in common.keys.toList()) {
                                    if (child.attributes[attrName] != common[attrName]) common.remove(attrName)
                                }
                            }
                        }
                    }

                    if (node.attributes["filter"] != null ||
                        node.attributes["clip-path"] != null ||
                        node.attributes["mask"] != null
                    ) {
                        common.remove("transform")
                    }
                    if (everyChildIsPath) common.remove("transform")

                    for ((attrName, value) in common) {
                        if (attrName == "transform") {
                            val existing = node.attributes["transform"]
                            node.attributes["transform"] = if (existing != null) "$existing $value" else value
                        } else {
                            node.attributes[attrName] = value
                        }
                    }

                    for (child in node.children) {
                        if (child is Element) {
                            for (attrName in common.keys) child.attributes.remove(attrName)
                        }
                    }
                }
            },
        ),
    )
}

// ---------------------------------------------------------------------------
// moveGroupAttrsToElems
// ---------------------------------------------------------------------------

private val pathElemsWithGroupsAndText: Set<String> = pathElems + setOf("g", "text")

/** moves some group attributes to the content elements */
public val moveGroupAttrsToElems: PluginDefinition = PluginDefinition(
    "moveGroupAttrsToElems",
    "moves some group attributes to the content elements",
) { _, _, _ ->
    Visitor(
        element = Callbacks(
            enter = { node, _ ->
                val transform = node.attributes["transform"]
                if (node.name == "g" &&
                    node.children.isNotEmpty() &&
                    transform != null &&
                    node.attributes.none { (name, value) ->
                        name in referencesProps && value != null && includesUrlReference(value)
                    } &&
                    node.children.all {
                        it is Element && it.name in pathElemsWithGroupsAndText && it.attributes["id"] == null
                    }
                ) {
                    for (child in node.children) {
                        if (child is Element) {
                            val existing = child.attributes["transform"]
                            child.attributes["transform"] =
                                if (existing != null) "$transform $existing" else transform
                        }
                    }
                    node.attributes.remove("transform")
                }
            },
        ),
    )
}

// ---------------------------------------------------------------------------
// removeEmptyContainers
// ---------------------------------------------------------------------------

private val containerElems: Set<String> = elemsGroups.getValue("container")

/** removes empty container elements */
public val removeEmptyContainers: PluginDefinition = PluginDefinition(
    "removeEmptyContainers",
    "removes empty container elements",
) { root, _, _ ->
    val stylesheet = collectStylesheet(root)
    val removedIds = LinkedHashSet<String>()
    val usesById = LinkedHashMap<String, MutableList<Pair<Element, XastParent>>>()

    Visitor(
        element = Callbacks(
            enter = { node, parentNode ->
                if (node.name == "use") {
                    for ((attrName, value) in node.attributes) {
                        if (value == null) continue
                        for (id in findReferences(attrName, value)) {
                            usesById.getOrPut(id) { ArrayList() }.add(node to parentNode)
                        }
                    }
                }
            },
            exit = { node, parentNode ->
                if (node.name != "svg" && node.name in containerElems && node.children.isEmpty() &&
                    !(node.name == "pattern" && node.attributes.isNotEmpty()) &&
                    !(node.name == "mask" && node.attributes["id"] != null) &&
                    !(parentNode is Element && parentNode.name == "switch") &&
                    !(
                        node.name == "g" && (
                            node.attributes["filter"] != null ||
                                computeStyle(stylesheet, node)["filter"] != null
                            )
                        )
                ) {
                    detachNodeFromParent(node, parentNode)
                    val id = node.attributes["id"]
                    if (!id.isNullOrEmpty()) removedIds.add(id)
                }
            },
        ),
        root = RootCallbacks(
            exit = {
                for (id in removedIds) {
                    usesById[id]?.forEach { (useNode, useParent) -> detachNodeFromParent(useNode, useParent) }
                }
            },
        ),
    )
}

// ---------------------------------------------------------------------------
// mergePaths
// ---------------------------------------------------------------------------

private fun elementHasUrl(computedStyle: Map<String, io.github.tobsef.svgo.ComputedStyle>, attName: String): Boolean {
    val style = computedStyle[attName]
    if (style != null && style.isStatic) return includesUrlReference(style.value ?: "")
    return false
}

/** merges multiple paths in one if possible */
public val mergePaths: PluginDefinition = PluginDefinition(
    "mergePaths",
    "merges multiple paths in one if possible",
) { root, params, _ ->
    val force = params.bool("force", false)
    val floatPrecision = params.int("floatPrecision", 3)
    val noSpaceAfterFlags = params.bool("noSpaceAfterFlags", false)
    val stylesheet = collectStylesheet(root)

    Visitor(
        element = Callbacks(
            enter = { node, _ ->
                if (node.children.size > 1) {
                    val elementsToRemove = ArrayList<Element>()
                    var prevChild = node.children[0]
                    var prevPathData: MutableList<PathItem>? = null

                    fun updatePreviousPath(child: Element, pathData: MutableList<PathItem>) {
                        js2path(child, pathData, floatPrecision, noSpaceAfterFlags)
                        prevPathData = null
                    }

                    var i = 1
                    while (i < node.children.size) {
                        val child = node.children[i]
                        val previous = prevChild

                        val previousPath = if (previous is Element && previous.name == "path" &&
                            previous.children.isEmpty() && previous.attributes["d"] != null
                        ) {
                            previous
                        } else {
                            null
                        }
                        if (previousPath == null) {
                            val data = prevPathData
                            if (data != null && previous is Element) updatePreviousPath(previous, data)
                            prevChild = child
                            i++
                            continue
                        }

                        val childPath = if (child is Element && child.name == "path" &&
                            child.children.isEmpty() && child.attributes["d"] != null
                        ) {
                            child
                        } else {
                            null
                        }
                        if (childPath == null) {
                            prevPathData?.let { updatePreviousPath(previousPath, it) }
                            prevChild = child
                            i++
                            continue
                        }

                        val computed = computeStyle(stylesheet, childPath)
                        if (computed["marker-start"] != null ||
                            computed["marker-mid"] != null ||
                            computed["marker-end"] != null ||
                            computed["clip-path"] != null ||
                            computed["mask"] != null ||
                            computed["mask-image"] != null ||
                            listOf("fill", "filter", "stroke").any { elementHasUrl(computed, it) }
                        ) {
                            prevPathData?.let { updatePreviousPath(previousPath, it) }
                            prevChild = child
                            i++
                            continue
                        }

                        val childAttrs = childPath.attributes.keys.toList()
                        if (childAttrs.size != previousPath.attributes.size) {
                            prevPathData?.let { updatePreviousPath(previousPath, it) }
                            prevChild = child
                            i++
                            continue
                        }

                        val attrsDiffer = childAttrs.any { attr ->
                            attr != "d" && previousPath.attributes[attr] != childPath.attributes[attr]
                        }
                        if (attrsDiffer) {
                            prevPathData?.let { updatePreviousPath(previousPath, it) }
                            prevChild = child
                            i++
                            continue
                        }

                        val hasPrevPath = prevPathData != null
                        val currentPathData = path2js(childPath)
                        val merged = prevPathData ?: path2js(previousPath).also { prevPathData = it }

                        if (force || !intersects(merged, currentPathData)) {
                            merged.addAll(currentPathData)
                            elementsToRemove.add(childPath)
                            i++
                            continue
                        }

                        if (hasPrevPath) updatePreviousPath(previousPath, merged)

                        prevChild = child
                        prevPathData = null
                        i++
                    }

                    val data = prevPathData
                    val last = prevChild
                    if (data != null && last is Element) updatePreviousPath(last, data)

                    if (elementsToRemove.isNotEmpty()) {
                        node.children = node.children.filterTo(ArrayList()) { candidate ->
                            elementsToRemove.none { it === candidate }
                        }
                    }
                }
            },
        ),
    )
}

// ---------------------------------------------------------------------------
// reusePaths
// ---------------------------------------------------------------------------

/**
 * Finds `<path>` elements with the same d, fill, and stroke, and converts them to `<use>` elements
 * referencing a single `<path>` def.
 */
public val reusePaths: PluginDefinition = PluginDefinition(
    "reusePaths",
    "Finds <path> elements with the same d, fill, and stroke, and converts them to <use> elements " +
        "referencing a single <path> def.",
) { root, _, _ ->
    val stylesheet = collectStylesheet(root)
    val paths = LinkedHashMap<String, MutableList<Element>>()
    var svgDefs: Element? = null
    val hrefs = LinkedHashSet<String>()

    Visitor(
        element = Callbacks(
            enter = { node, parentNode ->
                if (node.name == "path" && node.attributes["d"] != null) {
                    val d = node.attributes["d"]!!
                    val fill = node.attributes["fill"] ?: ""
                    val stroke = node.attributes["stroke"] ?: ""
                    paths.getOrPut("$d;s:$stroke;f:$fill") { ArrayList() }.add(node)
                }

                if (svgDefs == null && node.name == "defs" &&
                    parentNode is Element && parentNode.name == "svg"
                ) {
                    svgDefs = node
                }

                if (node.name == "use") {
                    for (name in listOf("href", "xlink:href")) {
                        val href = node.attributes[name]
                        if (href != null && href.startsWith("#") && href.length > 1) hrefs.add(href.substring(1))
                    }
                }
            },
            exit = { node, parentNode ->
                if (node.name == "svg" && parentNode is Root) {
                    val defsTag = svgDefs ?: Element("defs", LinkedHashMap(), mutableListOf())

                    var index = 0
                    for (list in paths.values) {
                        if (list.size <= 1) continue
                        val reusable = Element("path", LinkedHashMap(), mutableListOf())
                        for (attr in listOf("fill", "stroke", "d")) {
                            list[0].attributes[attr]?.let { reusable.attributes[attr] = it }
                        }

                        val originalId = list[0].attributes["id"]
                        if (originalId == null ||
                            originalId in hrefs ||
                            stylesheet.rules.any { it.selector == "#$originalId" }
                        ) {
                            reusable.attributes["id"] = "reuse-$index"
                            index++
                        } else {
                            reusable.attributes["id"] = originalId
                            list[0].attributes.remove("id")
                        }
                        defsTag.children.add(reusable)

                        for (pathNode in list) {
                            pathNode.attributes.remove("d")
                            pathNode.attributes.remove("stroke")
                            pathNode.attributes.remove("fill")

                            if (defsTag.children.any { it === pathNode } && pathNode.children.isEmpty()) {
                                if (pathNode.attributes.isEmpty()) {
                                    detachNodeFromParent(pathNode, defsTag)
                                    continue
                                }
                                if (pathNode.attributes.size == 1 && pathNode.attributes["id"] != null) {
                                    detachNodeFromParent(pathNode, defsTag)
                                    val pid = pathNode.attributes["id"]!!
                                    for (child in findHrefRefs(node, pid)) {
                                        for (name in listOf("href", "xlink:href")) {
                                            if (child.attributes[name] != null) {
                                                child.attributes[name] = "#" + reusable.attributes["id"]
                                            }
                                        }
                                    }
                                    continue
                                }
                            }

                            pathNode.name = "use"
                            pathNode.attributes["xlink:href"] = "#" + reusable.attributes["id"]
                        }
                    }

                    if (defsTag.children.isNotEmpty()) {
                        if (node.attributes["xmlns:xlink"] == null) {
                            node.attributes["xmlns:xlink"] = "http://www.w3.org/1999/xlink"
                        }
                        if (svgDefs == null) node.children.add(0, defsTag)
                    }
                }
            },
        ),
    )
}

/** Equivalent of `querySelectorAll` for `[href=#id], [xlink:href=#id]`. */
private fun findHrefRefs(root: XastParent, targetId: String): List<Element> {
    val reference = "#$targetId"
    val results = ArrayList<Element>()

    fun walk(node: XastParent) {
        for (child in node.children) {
            if (child is Element) {
                if (child.attributes["href"] == reference || child.attributes["xlink:href"] == reference) {
                    results.add(child)
                }
                walk(child)
            }
        }
    }

    walk(root)
    return results
}
