package io.github.tobsef.svgo.plugins

import io.github.tobsef.svgo.Callbacks
import io.github.tobsef.svgo.Element
import io.github.tobsef.svgo.PluginDefinition
import io.github.tobsef.svgo.Root
import io.github.tobsef.svgo.RootCallbacks
import io.github.tobsef.svgo.Text
import io.github.tobsef.svgo.VISIT_SKIP
import io.github.tobsef.svgo.Visitor
import io.github.tobsef.svgo.XastChild
import io.github.tobsef.svgo.XastParent
import io.github.tobsef.svgo.attrsGroups
import io.github.tobsef.svgo.attrsGroupsDefaults
import io.github.tobsef.svgo.attrsGroupsDeprecated
import io.github.tobsef.svgo.collectStylesheet
import io.github.tobsef.svgo.computeStyle
import io.github.tobsef.svgo.detachNodeFromParent
import io.github.tobsef.svgo.elems
import io.github.tobsef.svgo.elemsGroups
import io.github.tobsef.svgo.entryList
import io.github.tobsef.svgo.findReferences
import io.github.tobsef.svgo.hasScripts
import io.github.tobsef.svgo.includesAttrSelector
import io.github.tobsef.svgo.indexOfIdentity
import io.github.tobsef.svgo.intersects
import io.github.tobsef.svgo.isExecutableUrl
import io.github.tobsef.svgo.parsePathData
import io.github.tobsef.svgo.presentationNonInheritableGroupAttrs
import io.github.tobsef.svgo.querySelector
import io.github.tobsef.svgo.DeprecatedAttrs
import io.github.tobsef.svgo.PathItem
import io.github.tobsef.svgo.selectorAttributeNames
import io.github.tobsef.svgo.visit

// ---------------------------------------------------------------------------
// removeDeprecatedAttrs
// ---------------------------------------------------------------------------

private fun processDeprecatedAttributes(
    node: Element,
    deprecated: DeprecatedAttrs?,
    removeUnsafe: Boolean,
    attrsInStylesheet: Set<String>,
) {
    if (deprecated == null) return
    for (name in deprecated.safe) {
        if (name in attrsInStylesheet) continue
        node.attributes.remove(name)
    }
    if (removeUnsafe) {
        for (name in deprecated.unsafe) {
            if (name in attrsInStylesheet) continue
            node.attributes.remove(name)
        }
    }
}

/** removes deprecated attributes */
public val removeDeprecatedAttrs: PluginDefinition = PluginDefinition(
    "removeDeprecatedAttrs",
    "removes deprecated attributes",
) { root, params, _ ->
    val removeUnsafe = params.bool("removeUnsafe", false)
    val stylesheet = collectStylesheet(root)
    val attrsInStylesheet = LinkedHashSet<String>()
    for (rule in stylesheet.rules) {
        attrsInStylesheet.addAll(selectorAttributeNames(rule.selector))
    }

    Visitor(
        element = Callbacks(
            enter = { node, _ ->
                val elemConfig = elems[node.name]
                if (elemConfig != null) {
                    if ("core" in elemConfig.attrsGroups &&
                        node.attributes["xml:lang"] != null &&
                        "xml:lang" !in attrsInStylesheet &&
                        node.attributes["lang"] != null
                    ) {
                        node.attributes.remove("xml:lang")
                    }

                    for (attrsGroup in elemConfig.attrsGroups) {
                        processDeprecatedAttributes(
                            node,
                            attrsGroupsDeprecated[attrsGroup],
                            removeUnsafe,
                            attrsInStylesheet,
                        )
                    }

                    processDeprecatedAttributes(node, elemConfig.deprecated, removeUnsafe, attrsInStylesheet)
                }
            },
        ),
    )
}

// ---------------------------------------------------------------------------
// removeUnknownsAndDefaults
// ---------------------------------------------------------------------------

private val allowedChildren: Map<String, Set<String>> = buildMap {
    for ((name, config) in elems) {
        val children = LinkedHashSet<String>()
        config.content?.let { children.addAll(it) }
        config.contentGroups?.forEach { groupName -> elemsGroups[groupName]?.let { children.addAll(it) } }
        put(name, children)
    }
}

private val allowedAttributes: Map<String, Set<String>> = buildMap {
    for ((name, config) in elems) {
        val attrs = LinkedHashSet<String>()
        config.attrs?.let { attrs.addAll(it) }
        for (groupName in config.attrsGroups) attrsGroups[groupName]?.let { attrs.addAll(it) }
        put(name, attrs)
    }
}

private val attributesDefaults: Map<String, Map<String, String>> = buildMap {
    for ((name, config) in elems) {
        val defaults = LinkedHashMap<String, String>()
        config.defaults?.let { defaults.putAll(it) }
        for (groupName in config.attrsGroups) attrsGroupsDefaults[groupName]?.let { defaults.putAll(it) }
        put(name, defaults)
    }
}

private val REG_STANDALONE = Regex("""\s*standalone\s*=\s*(["'])no\1""")

/** removes unknown elements content and attributes, removes attrs with default values */
public val removeUnknownsAndDefaults: PluginDefinition = PluginDefinition(
    "removeUnknownsAndDefaults",
    "removes unknown elements content and attributes, removes attrs with default values",
) { root, params, _ ->
    val unknownContent = params.bool("unknownContent", true)
    val unknownAttrs = params.bool("unknownAttrs", true)
    val defaultAttrs = params.bool("defaultAttrs", true)
    val defaultMarkupDeclarations = params.bool("defaultMarkupDeclarations", true)
    val uselessOverrides = params.bool("uselessOverrides", true)
    val keepDataAttrs = params.bool("keepDataAttrs", true)
    val keepAriaAttrs = params.bool("keepAriaAttrs", true)
    val keepRoleAttr = params.bool("keepRoleAttr", false)

    val stylesheet = collectStylesheet(root)

    Visitor(
        instruction = Callbacks(
            enter = { node, _ ->
                if (defaultMarkupDeclarations) node.value = REG_STANDALONE.replace(node.value, "")
            },
        ),
        element = Callbacks(
            enter = { node, parentNode ->
                var result: Any? = null
                if (":" in node.name) {
                    result = null
                } else if (node.name == "foreignObject") {
                    result = VISIT_SKIP
                } else {
                    var detached = false
                    if (unknownContent && parentNode is Element) {
                        val allowed = allowedChildren[parentNode.name]
                        if (allowed == null || allowed.isEmpty()) {
                            if (allowedChildren[node.name] == null) {
                                detachNodeFromParent(node, parentNode)
                                detached = true
                            }
                        } else if (node.name !in allowed) {
                            detachNodeFromParent(node, parentNode)
                            detached = true
                        }
                    }

                    if (!detached) {
                        val allowedAttrs = allowedAttributes[node.name]
                        val defaults = attributesDefaults[node.name]
                        val computedParentStyle =
                            if (parentNode is Element) computeStyle(stylesheet, parentNode) else null

                        for ((attrName, value) in node.attributes.entryList()) {
                            if (keepDataAttrs && attrName.startsWith("data-")) continue
                            if (keepAriaAttrs && attrName.startsWith("aria-")) continue
                            if (keepRoleAttr && attrName == "role") continue
                            if (attrName == "xmlns") continue
                            if (":" in attrName) {
                                val prefix = attrName.substringBefore(":")
                                if (prefix != "xml" && prefix != "xlink") continue
                            }

                            if (unknownAttrs && allowedAttrs != null && attrName !in allowedAttrs) {
                                node.attributes.remove(attrName)
                                continue
                            }

                            if (defaultAttrs && node.attributes["id"] == null &&
                                defaults != null && defaults[attrName] == value
                            ) {
                                if ((computedParentStyle == null || computedParentStyle[attrName] == null) &&
                                    stylesheet.rules.none { includesAttrSelector(it.selector, attrName) }
                                ) {
                                    node.attributes.remove(attrName)
                                    continue
                                }
                            }

                            if (uselessOverrides && node.attributes["id"] == null) {
                                val style = computedParentStyle?.get(attrName)
                                if (attrName !in presentationNonInheritableGroupAttrs &&
                                    style != null && style.isStatic && style.value == value
                                ) {
                                    node.attributes.remove(attrName)
                                }
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
// removeUselessStrokeAndFill
// ---------------------------------------------------------------------------

private val shapeElems: Set<String> = elemsGroups.getValue("shape")

/** removes useless stroke and fill attributes */
public val removeUselessStrokeAndFill: PluginDefinition = PluginDefinition(
    "removeUselessStrokeAndFill",
    "removes useless stroke and fill attributes",
) { root, params, _ ->
    val removeStroke = params.bool("stroke", true)
    val removeFill = params.bool("fill", true)
    val removeNone = params.bool("removeNone", false)

    var hasStyleOrScript = false
    visit(
        root,
        Visitor(
            element = Callbacks(
                enter = { node, _ -> if (node.name == "style" || hasScripts(node)) hasStyleOrScript = true },
            ),
        ),
    )

    if (hasStyleOrScript) {
        null
    } else {
        val stylesheet = collectStylesheet(root)

        Visitor(
            element = Callbacks(
                enter = { node, parentNode ->
                    var result: Any? = null
                    if (node.attributes["id"] != null) {
                        result = VISIT_SKIP
                    } else if (node.name in shapeElems) {
                        val computed = computeStyle(stylesheet, node)
                        val stroke = computed["stroke"]
                        val strokeOpacity = computed["stroke-opacity"]
                        val strokeWidth = computed["stroke-width"]
                        val markerEnd = computed["marker-end"]
                        val fill = computed["fill"]
                        val fillOpacity = computed["fill-opacity"]
                        val computedParent =
                            if (parentNode is Element) computeStyle(stylesheet, parentNode) else null
                        val parentStroke = computedParent?.get("stroke")

                        if (removeStroke) {
                            if (stroke == null ||
                                (stroke.isStatic && stroke.value == "none") ||
                                (strokeOpacity != null && strokeOpacity.isStatic && strokeOpacity.value == "0") ||
                                (strokeWidth != null && strokeWidth.isStatic && strokeWidth.value == "0")
                            ) {
                                if ((strokeWidth != null && strokeWidth.isStatic && strokeWidth.value == "0") ||
                                    markerEnd == null
                                ) {
                                    for (attrName in node.attributes.keys.toList()) {
                                        if (attrName.startsWith("stroke")) node.attributes.remove(attrName)
                                    }
                                    if (parentStroke != null && parentStroke.isStatic && parentStroke.value != "none") {
                                        node.attributes["stroke"] = "none"
                                    }
                                }
                            }
                        }

                        if (removeFill) {
                            if ((fill != null && fill.isStatic && fill.value == "none") ||
                                (fillOpacity != null && fillOpacity.isStatic && fillOpacity.value == "0")
                            ) {
                                for (attrName in node.attributes.keys.toList()) {
                                    if (attrName.startsWith("fill-")) node.attributes.remove(attrName)
                                }
                                if (fill == null || (fill.isStatic && fill.value != "none")) {
                                    node.attributes["fill"] = "none"
                                }
                            }
                        }

                        if (removeNone) {
                            if ((stroke == null || node.attributes["stroke"] == "none") &&
                                (
                                    (fill != null && fill.isStatic && fill.value == "none") ||
                                        node.attributes["fill"] == "none"
                                    )
                            ) {
                                detachNodeFromParent(node, parentNode)
                            }
                        }
                    }
                    result
                },
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// removeHiddenElems
// ---------------------------------------------------------------------------

private val nonRenderingGroup: Set<String> = elemsGroups.getValue("nonRendering")

/** removes hidden elements (zero sized, with absent attributes) */
public val removeHiddenElems: PluginDefinition = PluginDefinition(
    "removeHiddenElems",
    "removes hidden elements (zero sized, with absent attributes)",
) { root, params, _ ->
    val isHidden = params.bool("isHidden", true)
    val displayNone = params.bool("displayNone", true)
    val opacity0 = params.bool("opacity0", true)
    val circleR0 = params.bool("circleR0", true)
    val ellipseRX0 = params.bool("ellipseRX0", true)
    val ellipseRY0 = params.bool("ellipseRY0", true)
    val rectWidth0 = params.bool("rectWidth0", true)
    val rectHeight0 = params.bool("rectHeight0", true)
    val patternWidth0 = params.bool("patternWidth0", true)
    val patternHeight0 = params.bool("patternHeight0", true)
    val imageWidth0 = params.bool("imageWidth0", true)
    val imageHeight0 = params.bool("imageHeight0", true)
    val pathEmptyD = params.bool("pathEmptyD", true)
    val polylineEmptyPoints = params.bool("polylineEmptyPoints", true)
    val polygonEmptyPoints = params.bool("polygonEmptyPoints", true)

    val stylesheet = collectStylesheet(root)

    val nonRenderedNodes = LinkedHashMap<Element, XastParent>()
    val removedDefIds = LinkedHashSet<String>()
    val allDefs = LinkedHashMap<Element, XastParent>()
    val allReferences = LinkedHashSet<String>()
    val referencesById = LinkedHashMap<String, MutableList<Pair<Element, XastParent>>>()
    var deoptimized = false

    fun canRemoveNonRenderingNode(node: Element): Boolean {
        if (node.attributes["id"] in allReferences) return false
        for (child in node.children) {
            if (child is Element && !canRemoveNonRenderingNode(child)) return false
        }
        return true
    }

    fun removeElement(node: Element, parentNode: XastParent) {
        val id = node.attributes["id"]
        if (id != null && parentNode is Element && parentNode.name == "defs") removedDefIds.add(id)
        detachNodeFromParent(node, parentNode)
    }

    // pre-pass: opacity 0
    visit(
        root,
        Visitor(
            element = Callbacks(
                enter = { node, parentNode ->
                    var result: Any? = null
                    if (node.name in nonRenderingGroup) {
                        nonRenderedNodes[node] = parentNode
                        result = VISIT_SKIP
                    } else {
                        val opacity = computeStyle(stylesheet, node)["opacity"]
                        if (opacity0 && opacity != null && opacity.isStatic && opacity.value == "0") {
                            if (node.name == "path") {
                                nonRenderedNodes[node] = parentNode
                                result = VISIT_SKIP
                            } else {
                                removeElement(node, parentNode)
                            }
                        }
                    }
                    result
                },
            ),
        ),
    )

    Visitor(
        element = Callbacks(
            enter = { node, parentNode ->
                if ((node.name == "style" && node.children.isNotEmpty()) || hasScripts(node)) {
                    deoptimized = true
                    return@Callbacks Unit
                }

                if (node.name == "defs") allDefs[node] = parentNode

                if (node.name == "use") {
                    for (attr in node.attributes.keys) {
                        if (attr != "href" && !attr.endsWith(":href")) continue
                        val value = node.attributes[attr] ?: continue
                        referencesById.getOrPut(value.substring(1)) { ArrayList() }.add(node to parentNode)
                    }
                }

                val attrs = node.attributes

                val zeroSized = when {
                    circleR0 && node.name == "circle" && node.children.isEmpty() && attrs["r"] == "0" -> true
                    ellipseRX0 && node.name == "ellipse" && node.children.isEmpty() && attrs["rx"] == "0" -> true
                    ellipseRY0 && node.name == "ellipse" && node.children.isEmpty() && attrs["ry"] == "0" -> true
                    rectWidth0 && node.name == "rect" && node.children.isEmpty() && attrs["width"] == "0" -> true
                    rectHeight0 && rectWidth0 && node.name == "rect" && node.children.isEmpty() &&
                        attrs["height"] == "0" -> true
                    patternWidth0 && node.name == "pattern" && attrs["width"] == "0" -> true
                    patternHeight0 && node.name == "pattern" && attrs["height"] == "0" -> true
                    imageWidth0 && node.name == "image" && attrs["width"] == "0" -> true
                    imageHeight0 && node.name == "image" && attrs["height"] == "0" -> true
                    polylineEmptyPoints && node.name == "polyline" && attrs["points"] == null -> true
                    polygonEmptyPoints && node.name == "polygon" && attrs["points"] == null -> true
                    else -> false
                }
                if (zeroSized) {
                    removeElement(node, parentNode)
                    return@Callbacks Unit
                }

                val computed = computeStyle(stylesheet, node)
                val visibility = computed["visibility"]
                if (isHidden && visibility != null && visibility.isStatic && visibility.value == "hidden" &&
                    querySelector(node, "[visibility=visible]") == null
                ) {
                    removeElement(node, parentNode)
                    return@Callbacks Unit
                }

                val display = computed["display"]
                if (displayNone && display != null && display.isStatic && display.value == "none" &&
                    node.name != "marker"
                ) {
                    removeElement(node, parentNode)
                    return@Callbacks Unit
                }

                if (pathEmptyD && node.name == "path") {
                    val d = attrs["d"]
                    if (d == null) {
                        removeElement(node, parentNode)
                        return@Callbacks Unit
                    }
                    val pathData = parsePathData(d)
                    if (pathData.isEmpty()) {
                        removeElement(node, parentNode)
                        return@Callbacks Unit
                    }
                    if (pathData.size == 1 && computed["marker-start"] == null && computed["marker-end"] == null) {
                        removeElement(node, parentNode)
                        return@Callbacks Unit
                    }
                }

                for ((attrName, value) in node.attributes) {
                    if (value == null) continue
                    allReferences.addAll(findReferences(attrName, value))
                }
                Unit
            },
        ),
        root = RootCallbacks(
            exit = {
                for (id in removedDefIds) {
                    referencesById[id]?.forEach { (useNode, useParent) ->
                        detachNodeFromParent(useNode, useParent)
                    }
                }

                if (!deoptimized) {
                    for ((node, parent) in nonRenderedNodes.entryList()) {
                        if (canRemoveNonRenderingNode(node)) detachNodeFromParent(node, parent)
                    }
                }

                for ((defNode, defParent) in allDefs.entryList()) {
                    if (defNode.children.isEmpty()) detachNodeFromParent(defNode, defParent)
                }
            },
        ),
    )
}

// ---------------------------------------------------------------------------
// removeOffCanvasPaths
// ---------------------------------------------------------------------------

private val REG_VIEWBOX_CLEAN = Regex("""[,+]|px""")
private val REG_WS = Regex("""\s+""")
private val REG_VIEWBOX_MATCH = Regex("""^(-?\d*\.?\d+) (-?\d*\.?\d+) (\d*\.?\d+) (\d*\.?\d+)$""")

private class ViewBox(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val width: Double,
    val height: Double,
)

/** removes elements that are drawn outside of the viewBox */
public val removeOffCanvasPaths: PluginDefinition = PluginDefinition(
    "removeOffCanvasPaths",
    "removes elements that are drawn outside of the viewBox",
) { _, _, _ ->
    var viewBox: ViewBox? = null

    Visitor(
        element = Callbacks(
            enter = { node, parentNode ->
                var result: Any? = null
                var stop = false

                if (node.name == "svg" && parentNode is Root) {
                    var raw = when {
                        node.attributes["viewBox"] != null -> node.attributes["viewBox"]!!
                        node.attributes["height"] != null && node.attributes["width"] != null ->
                            "0 0 ${node.attributes["width"]} ${node.attributes["height"]}"
                        else -> ""
                    }
                    raw = REG_VIEWBOX_CLEAN.replace(raw, " ")
                    raw = REG_WS.replace(raw, " ").trim()
                    val match = REG_VIEWBOX_MATCH.matchEntire(raw)
                    if (match == null) {
                        stop = true
                    } else {
                        val left = match.groupValues[1].toDouble()
                        val top = match.groupValues[2].toDouble()
                        val width = match.groupValues[3].toDouble()
                        val height = match.groupValues[4].toDouble()
                        viewBox = ViewBox(left, top, left + width, top + height, width, height)
                    }
                }

                if (!stop) {
                    if (node.attributes["transform"] != null) {
                        result = VISIT_SKIP
                    } else {
                        val box = viewBox
                        val d = node.attributes["d"]
                        if (node.name == "path" && d != null && box != null) {
                            val pathData = parsePathData(d)
                            var visible = false
                            for (item in pathData) {
                                if (item.command == "M") {
                                    val x = item.args[0]
                                    val y = item.args[1]
                                    if (x in box.left..box.right && y in box.top..box.bottom) visible = true
                                }
                            }
                            if (!visible) {
                                if (pathData.size == 2) pathData.add(PathItem("z", mutableListOf()))
                                val viewBoxPath = listOf(
                                    PathItem("M", mutableListOf(box.left, box.top)),
                                    PathItem("h", mutableListOf(box.width)),
                                    PathItem("v", mutableListOf(box.height)),
                                    PathItem("H", mutableListOf(box.left)),
                                    PathItem("z", mutableListOf()),
                                )
                                if (!intersects(viewBoxPath, pathData)) detachNodeFromParent(node, parentNode)
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
// removeScripts
// ---------------------------------------------------------------------------

private val eventAttrs: List<String> = buildList {
    addAll(attrsGroups["animationEvent"].orEmpty())
    addAll(attrsGroups["documentEvent"].orEmpty())
    addAll(attrsGroups["documentElementEvent"].orEmpty())
    addAll(attrsGroups["globalEvent"].orEmpty())
    addAll(attrsGroups["graphicalEvent"].orEmpty())
}

private const val SVG_NS = "http://www.w3.org/2000/svg"
private val FOREIGN_OBJECT_NS = listOf(SVG_NS)
private val ANCHOR_NS = listOf(SVG_NS)
private val SCRIPT_NS = listOf(SVG_NS, "http://www.w3.org/1999/xhtml")
private val HTML_URL_ATTRS = setOf("action", "data", "formaction", "href", "src")

private fun isNamespaceAwareElem(
    elem: String,
    targetElem: String,
    prefixes: Map<String, MutableList<String?>>,
    targetNamespaces: List<String>,
): Boolean {
    if (elem == targetElem) return true
    if (":" in elem) {
        val prefix = elem.substringBefore(":")
        val effectiveTag = elem.substringAfter(":")
        if (targetElem == effectiveTag) {
            val namespaces = prefixes[prefix]
            if (namespaces.isNullOrEmpty()) return false
            return namespaces[namespaces.size - 1] in targetNamespaces
        }
    }
    return false
}

/** removes scripts */
public val removeScripts: PluginDefinition = PluginDefinition(
    "removeScripts",
    "removes scripts",
) { _, _, _ ->
    val prefixes = LinkedHashMap<String, MutableList<String?>>()
    var foreignDepth = 0

    Visitor(
        element = Callbacks(
            enter = { node, parentNode ->
                for ((key, value) in node.attributes) {
                    if (!key.startsWith("xmlns:")) continue
                    prefixes.getOrPut(key.substring(6)) { ArrayList() }.add(value)
                }

                if (isNamespaceAwareElem(node.name, "foreignObject", prefixes, FOREIGN_OBJECT_NS)) foreignDepth++

                if (isNamespaceAwareElem(node.name, "script", prefixes, SCRIPT_NS)) {
                    detachNodeFromParent(node, parentNode)
                } else {
                    for ((attr, value) in node.attributes.entryList()) {
                        val localAttr = attr.substring(attr.lastIndexOf(':') + 1).lowercase()
                        val isEventAttr = attr in eventAttrs || (foreignDepth > 0 && localAttr.startsWith("on"))
                        val isEmbeddedDocAttr = foreignDepth > 0 && localAttr == "srcdoc"
                        val isExecutableHtmlUrl = foreignDepth > 0 &&
                            localAttr in HTML_URL_ATTRS &&
                            value != null &&
                            isExecutableUrl(value)
                        if (isEventAttr || isEmbeddedDocAttr || isExecutableHtmlUrl) node.attributes.remove(attr)
                    }
                }
            },
            exit = { node, parentNode ->
                val isForeignObject = isNamespaceAwareElem(node.name, "foreignObject", prefixes, FOREIGN_OBJECT_NS)
                val isAnchor = isNamespaceAwareElem(node.name, "a", prefixes, ANCHOR_NS)

                for (key in node.attributes.keys.toList()) {
                    if (!key.startsWith("xmlns:")) continue
                    prefixes[key.substring(6)]?.let { if (it.isNotEmpty()) it.removeAt(it.size - 1) }
                }

                if (isAnchor) {
                    for (attr in node.attributes.keys.toList()) {
                        if (attr != "href" && !attr.endsWith(":href")) continue
                        val value = node.attributes[attr]
                        if (value == null || !isExecutableUrl(value)) continue
                        val index = parentNode.children.indexOfIdentity(node)
                        if (index < 0) continue
                        val usefulChildren = node.children.filter { it !is Text }
                        val newChildren = ArrayList<XastChild>()
                        newChildren.addAll(parentNode.children.subList(0, index))
                        newChildren.addAll(usefulChildren)
                        newChildren.addAll(parentNode.children.subList(index + 1, parentNode.children.size))
                        parentNode.children = newChildren
                    }
                }

                if (isForeignObject) foreignDepth--
            },
        ),
    )
}

// ---------------------------------------------------------------------------
// removeXlink
// ---------------------------------------------------------------------------

private const val XLINK_NS = "http://www.w3.org/1999/xlink"
private val SHOW_TO_TARGET = mapOf("new" to "_blank", "replace" to "_self")
private val LEGACY_ELEMENTS = setOf("cursor", "filter", "font-face-uri", "glyphRef", "tref")

private fun findPrefixedAttrs(node: Element, prefixes: List<String>, attr: String): List<String> =
    prefixes.map { "$it:$attr" }.filter { node.attributes[it] != null }

/** remove xlink namespace and replaces attributes with the SVG 2 equivalent where applicable */
public val removeXlink: PluginDefinition = PluginDefinition(
    "removeXlink",
    "remove xlink namespace and replaces attributes with the SVG 2 equivalent where applicable",
) { _, params, _ ->
    val includeLegacy = params.bool("includeLegacy", false)
    val xlinkPrefixes = ArrayList<String>()
    val overriddenPrefixes = ArrayList<String>()
    val usedInLegacy = ArrayList<String>()

    Visitor(
        element = Callbacks(
            enter = { node, _ ->
                for ((key, value) in node.attributes.entryList()) {
                    if (key.startsWith("xmlns:")) {
                        val prefix = key.split(":")[1]
                        if (value == XLINK_NS) {
                            xlinkPrefixes.add(prefix)
                            continue
                        }
                        if (prefix in xlinkPrefixes) overriddenPrefixes.add(prefix)
                    }
                }

                if (overriddenPrefixes.none { it in xlinkPrefixes }) {
                    val showAttrs = findPrefixedAttrs(node, xlinkPrefixes, "show")
                    var showHandled = node.attributes["target"] != null
                    for (i in showAttrs.indices.reversed()) {
                        val attr = showAttrs[i]
                        val value = node.attributes[attr]
                        val mapping = SHOW_TO_TARGET[value]
                        if (showHandled || mapping == null) {
                            node.attributes.remove(attr)
                            continue
                        }
                        val defaultTarget = elems[node.name]?.defaults?.get("target")
                        if (mapping != defaultTarget) node.attributes["target"] = mapping
                        node.attributes.remove(attr)
                        showHandled = true
                    }

                    val titleAttrs = findPrefixedAttrs(node, xlinkPrefixes, "title")
                    for (i in titleAttrs.indices.reversed()) {
                        val attr = titleAttrs[i]
                        val value = node.attributes[attr]
                        val hasTitle = node.children.any { it is Element && it.name == "title" }
                        if (hasTitle) {
                            node.attributes.remove(attr)
                            continue
                        }
                        val titleTag = Element("title", LinkedHashMap(), mutableListOf(Text(value ?: "")))
                        node.children.add(0, titleTag)
                        node.attributes.remove(attr)
                    }

                    val hrefAttrs = findPrefixedAttrs(node, xlinkPrefixes, "href")
                    if (hrefAttrs.isNotEmpty() && node.name in LEGACY_ELEMENTS && !includeLegacy) {
                        for (attr in hrefAttrs) usedInLegacy.add(attr.substringBefore(":"))
                    } else {
                        for (i in hrefAttrs.indices.reversed()) {
                            val attr = hrefAttrs[i]
                            val value = node.attributes[attr]
                            if (node.attributes["href"] != null) {
                                node.attributes.remove(attr)
                                continue
                            }
                            node.attributes["href"] = value
                            node.attributes.remove(attr)
                        }
                    }
                }
            },
            exit = { node, _ ->
                for ((key, value) in node.attributes.entryList()) {
                    val parts = key.split(":")
                    val prefix = parts[0]
                    val attr = if (parts.size > 1) parts[1] else null

                    if (prefix in xlinkPrefixes && prefix !in overriddenPrefixes &&
                        prefix !in usedInLegacy && !includeLegacy
                    ) {
                        node.attributes.remove(key)
                        continue
                    }

                    if (key.startsWith("xmlns:") && attr !in usedInLegacy) {
                        if (value == XLINK_NS) {
                            if (attr != null) xlinkPrefixes.remove(attr)
                            node.attributes.remove(key)
                            continue
                        }
                        if (prefix in overriddenPrefixes && attr != null) overriddenPrefixes.remove(attr)
                    }
                }
            },
        ),
    )
}
