package io.github.tobsef.svgo.plugins

import io.github.tobsef.svgo.Callbacks
import io.github.tobsef.svgo.Element
import io.github.tobsef.svgo.Params
import io.github.tobsef.svgo.PluginDefinition
import io.github.tobsef.svgo.Text
import io.github.tobsef.svgo.Visitor
import io.github.tobsef.svgo.attrsGroups
import io.github.tobsef.svgo.detachNodeFromParent
import io.github.tobsef.svgo.editorNamespaces
import io.github.tobsef.svgo.elemsGroups
import io.github.tobsef.svgo.entryList
import io.github.tobsef.svgo.jsNumber
import io.github.tobsef.svgo.jsNumberToString
import io.github.tobsef.svgo.querySelectorAll
import io.github.tobsef.svgo.warn

/** removes doctype declaration */
public val removeDoctype: PluginDefinition = PluginDefinition(
    "removeDoctype",
    "removes doctype declaration",
) { _, _, _ ->
    Visitor(doctype = Callbacks(enter = { node, parentNode -> detachNodeFromParent(node, parentNode) }))
}

/** removes XML processing instructions */
public val removeXMLProcInst: PluginDefinition = PluginDefinition(
    "removeXMLProcInst",
    "removes XML processing instructions",
) { _, _, _ ->
    Visitor(
        instruction = Callbacks(
            enter = { node, parentNode -> if (node.name == "xml") detachNodeFromParent(node, parentNode) },
        ),
    )
}

private val DEFAULT_PRESERVE_PATTERNS = listOf("^!")

/** removes comments */
public val removeComments: PluginDefinition = PluginDefinition(
    "removeComments",
    "removes comments",
) { _, params, _ ->
    val raw = if (params.has("preservePatterns")) params.raw("preservePatterns") else DEFAULT_PRESERVE_PATTERNS
    val patterns: List<Regex>? = if (!Params.isTruthy(raw)) {
        null
    } else {
        val list = raw as? List<*>
            ?: throw IllegalArgumentException(
                "Expected array in removeComments preservePatterns parameter but received $raw",
            )
        list.map { Regex(it.toString()) }
    }

    Visitor(
        comment = Callbacks(
            enter = { node, parentNode ->
                if (patterns == null || patterns.none { it.containsMatchIn(node.value) }) {
                    detachNodeFromParent(node, parentNode)
                }
            },
        ),
    )
}

/** removes `<metadata>` */
public val removeMetadata: PluginDefinition = PluginDefinition(
    "removeMetadata",
    "removes <metadata>",
) { _, _, _ ->
    Visitor(
        element = Callbacks(
            enter = { node, parentNode -> if (node.name == "metadata") detachNodeFromParent(node, parentNode) },
        ),
    )
}

/** removes editors namespaces, elements and attributes */
public val removeEditorsNSData: PluginDefinition = PluginDefinition(
    "removeEditorsNSData",
    "removes editors namespaces, elements and attributes",
) { _, params, _ ->
    val additional = params.stringList("additionalNamespaces")
    val namespaces = if (additional != null) editorNamespaces + additional else editorNamespaces
    val prefixes = ArrayList<String>()

    Visitor(
        element = Callbacks(
            enter = { node, parentNode ->
                if (node.name == "svg") {
                    for ((attrName, value) in node.attributes.entryList()) {
                        if (attrName.startsWith("xmlns:") && value in namespaces) {
                            prefixes.add(attrName.removePrefix("xmlns:"))
                            node.attributes.remove(attrName)
                        }
                    }
                }
                for (attrName in node.attributes.keys.toList()) {
                    if (":" in attrName && attrName.substringBefore(":") in prefixes) {
                        node.attributes.remove(attrName)
                    }
                }
                if (":" in node.name && node.name.substringBefore(":") in prefixes) {
                    detachNodeFromParent(node, parentNode)
                }
            },
        ),
    )
}

/** removes `<title>` */
public val removeTitle: PluginDefinition = PluginDefinition(
    "removeTitle",
    "removes <title>",
) { _, _, _ ->
    Visitor(
        element = Callbacks(
            enter = { node, parentNode -> if (node.name == "title") detachNodeFromParent(node, parentNode) },
        ),
    )
}

private val STANDARD_DESCS = Regex("^(Created with|Created using)")

/** removes `<desc>` */
public val removeDesc: PluginDefinition = PluginDefinition(
    "removeDesc",
    "removes <desc>",
) { _, params, _ ->
    val removeAny = params.bool("removeAny", false)
    Visitor(
        element = Callbacks(
            enter = { node, parentNode ->
                if (node.name == "desc") {
                    val firstChild = node.children.firstOrNull()
                    if (removeAny ||
                        node.children.isEmpty() ||
                        (firstChild is Text && STANDARD_DESCS.containsMatchIn(firstChild.value))
                    ) {
                        detachNodeFromParent(node, parentNode)
                    }
                }
            },
        ),
    )
}

/** removes `<style>` element */
public val removeStyleElement: PluginDefinition = PluginDefinition(
    "removeStyleElement",
    "removes <style> element",
) { _, _, _ ->
    Visitor(
        element = Callbacks(
            enter = { node, parentNode -> if (node.name == "style") detachNodeFromParent(node, parentNode) },
        ),
    )
}

/** removes xmlns attribute (for inline svg) */
public val removeXMLNS: PluginDefinition = PluginDefinition(
    "removeXMLNS",
    "removes xmlns attribute (for inline svg)",
) { _, _, _ ->
    Visitor(
        element = Callbacks(
            enter = { node, _ -> if (node.name == "svg") node.attributes.remove("xmlns") },
        ),
    )
}

private val RASTER_IMAGE = Regex("""(\.|image/)(jpe?g|png|gif)""")

/** removes raster images */
public val removeRasterImages: PluginDefinition = PluginDefinition(
    "removeRasterImages",
    "removes raster images",
) { _, _, _ ->
    Visitor(
        element = Callbacks(
            enter = { node, parentNode ->
                val href = node.attributes["xlink:href"]
                if (node.name == "image" && href != null && RASTER_IMAGE.containsMatchIn(href)) {
                    detachNodeFromParent(node, parentNode)
                }
            },
        ),
    )
}

/** removes empty `<text>` elements */
public val removeEmptyText: PluginDefinition = PluginDefinition(
    "removeEmptyText",
    "removes empty <text> elements",
) { _, params, _ ->
    val text = params.bool("text", true)
    val tspan = params.bool("tspan", true)
    val tref = params.bool("tref", true)

    Visitor(
        element = Callbacks(
            enter = { node, parentNode ->
                if (text && node.name == "text" && node.children.isEmpty()) {
                    detachNodeFromParent(node, parentNode)
                }
                if (tspan && node.name == "tspan" && node.children.isEmpty()) {
                    detachNodeFromParent(node, parentNode)
                }
                if (tref && node.name == "tref" && node.attributes["xlink:href"] == null) {
                    detachNodeFromParent(node, parentNode)
                }
            },
        ),
    )
}

/** removes empty attributes */
public val removeEmptyAttrs: PluginDefinition = PluginDefinition(
    "removeEmptyAttrs",
    "removes empty attributes",
) { _, _, _ ->
    val conditional = attrsGroups.getValue("conditionalProcessing")
    Visitor(
        element = Callbacks(
            enter = { node, _ ->
                for ((attrName, value) in node.attributes.entryList()) {
                    if (value == "" && attrName !in conditional) node.attributes.remove(attrName)
                }
            },
        ),
    )
}

private fun asStringList(value: Any?): List<String> = when (value) {
    null -> emptyList()
    is List<*> -> value.map { it.toString() }
    else -> listOf(value.toString())
}

/** removes arbitrary elements by ID or className */
public val removeElementsByAttr: PluginDefinition = PluginDefinition(
    "removeElementsByAttr",
    "removes arbitrary elements by ID or className",
) { _, params, _ ->
    val ids = asStringList(params.raw("id"))
    val classes = asStringList(params.raw("class"))

    Visitor(
        element = Callbacks(
            enter = { node, parentNode ->
                val id = node.attributes["id"]
                if (id != null && ids.isNotEmpty() && id in ids) detachNodeFromParent(node, parentNode)
                val classAttr = node.attributes["class"]
                if (!classAttr.isNullOrEmpty() && classes.isNotEmpty()) {
                    val classList = classAttr.split(" ")
                    for (item in classes) {
                        if (item in classList) {
                            detachNodeFromParent(node, parentNode)
                            break
                        }
                    }
                }
            },
        ),
    )
}

private val VIEWBOX_ELEMS = setOf("pattern", "svg", "symbol")
private val VIEWBOX_SPLIT = Regex("[ ,]+")
private val TRAILING_PX = Regex("px$")

/** removes viewBox attribute when possible */
public val removeViewBox: PluginDefinition = PluginDefinition(
    "removeViewBox",
    "removes viewBox attribute when possible",
) { _, _, _ ->
    Visitor(
        element = Callbacks(
            enter = { node, parentNode ->
                val viewBox = node.attributes["viewBox"]
                val width = node.attributes["width"]
                val height = node.attributes["height"]
                if (node.name in VIEWBOX_ELEMS && viewBox != null && width != null && height != null) {
                    if (!(node.name == "svg" && parentNode !is io.github.tobsef.svgo.Root)) {
                        val numbers = viewBox.split(VIEWBOX_SPLIT)
                        if (numbers.size >= 4 &&
                            numbers[0] == "0" &&
                            numbers[1] == "0" &&
                            TRAILING_PX.replace(width, "") == numbers[2] &&
                            TRAILING_PX.replace(height, "") == numbers[3]
                        ) {
                            node.attributes.remove("viewBox")
                        }
                    }
                }
            },
        ),
    )
}

/** removes width and height in presence of viewBox (opposite to removeViewBox) */
public val removeDimensions: PluginDefinition = PluginDefinition(
    "removeDimensions",
    "removes width and height in presence of viewBox (opposite to removeViewBox)",
) { _, _, _ ->
    Visitor(
        element = Callbacks(
            enter = { node, _ ->
                if (node.name == "svg") {
                    val widthAttr = node.attributes["width"]
                    val heightAttr = node.attributes["height"]
                    if (node.attributes["viewBox"] != null) {
                        node.attributes.remove("width")
                        node.attributes.remove("height")
                    } else if (widthAttr != null && heightAttr != null &&
                        !jsNumber(widthAttr).isNaN() && !jsNumber(heightAttr).isNaN()
                    ) {
                        val width = jsNumber(widthAttr)
                        val height = jsNumber(heightAttr)
                        node.attributes["viewBox"] =
                            "0 0 ${jsNumberToString(width)} ${jsNumberToString(height)}"
                        node.attributes.remove("width")
                        node.attributes.remove("height")
                    }
                }
            },
        ),
    )
}

/** removes unused namespaces declaration */
public val removeUnusedNS: PluginDefinition = PluginDefinition(
    "removeUnusedNS",
    "removes unused namespaces declaration",
) { _, _, _ ->
    val unusedNamespaces = LinkedHashSet<String>()

    Visitor(
        element = Callbacks(
            enter = { node, parentNode ->
                if (node.name == "svg" && parentNode is io.github.tobsef.svgo.Root) {
                    for (attrName in node.attributes.keys) {
                        if (attrName.startsWith("xmlns:")) {
                            unusedNamespaces.add(attrName.removePrefix("xmlns:"))
                        }
                    }
                }
                if (unusedNamespaces.isNotEmpty()) {
                    if (":" in node.name) unusedNamespaces.remove(node.name.substringBefore(":"))
                    for (attrName in node.attributes.keys) {
                        if (":" in attrName) unusedNamespaces.remove(attrName.substringBefore(":"))
                    }
                }
            },
            exit = { node, parentNode ->
                if (node.name == "svg" && parentNode is io.github.tobsef.svgo.Root) {
                    for (namespace in unusedNamespaces) node.attributes.remove("xmlns:$namespace")
                }
            },
        ),
    )
}

private val nonRenderingElems: Set<String> = elemsGroups.getValue("nonRendering")

private fun collectUsefulNodes(node: Element, usefulNodes: MutableList<io.github.tobsef.svgo.XastChild>) {
    for (child in node.children) {
        if (child is Element) {
            if (child.attributes["id"] != null || child.name == "style") {
                usefulNodes.add(child)
            } else {
                collectUsefulNodes(child, usefulNodes)
            }
        }
    }
}

/** removes elements in `<defs>` without id */
public val removeUselessDefs: PluginDefinition = PluginDefinition(
    "removeUselessDefs",
    "removes elements in <defs> without id",
) { _, _, _ ->
    Visitor(
        element = Callbacks(
            enter = { node, parentNode ->
                if (node.name == "defs" ||
                    (node.name in nonRenderingElems && node.attributes["id"] == null)
                ) {
                    val usefulNodes = ArrayList<io.github.tobsef.svgo.XastChild>()
                    collectUsefulNodes(node, usefulNodes)
                    if (usefulNodes.isEmpty()) detachNodeFromParent(node, parentNode)
                    node.children = usefulNodes
                }
            },
        ),
    )
}

/** removes specified attributes */
public val removeAttrs: PluginDefinition = PluginDefinition(
    "removeAttrs",
    "removes specified attributes",
) { _, params, _ ->
    val attrsParam = params.raw("attrs")
    if (attrsParam == null) {
        warn("Warning: The plugin \"removeAttrs\" requires the \"attrs\" parameter.")
        null
    } else {
        val elemSeparator = params.string("elemSeparator") ?: ":"
        val preserveCurrentColor = params.bool("preserveCurrentColor", false)
        val attrs = if (attrsParam is List<*>) attrsParam.map { it.toString() } else listOf(attrsParam.toString())

        val patterns = attrs.map { attr ->
            var pattern = attr
            if (!pattern.contains(elemSeparator)) {
                pattern = listOf(".*", pattern, ".*").joinToString(elemSeparator)
            } else if (pattern.split(elemSeparator).size < 3) {
                pattern = listOf(pattern, ".*").joinToString(elemSeparator)
            }
            pattern.split(elemSeparator).map { value ->
                Regex("^" + (if (value == "*") ".*" else value) + "$", RegexOption.IGNORE_CASE)
            }
        }

        Visitor(
            element = Callbacks(
                enter = { node, _ ->
                    for (list in patterns) {
                        if (!list[0].containsMatchIn(node.name)) continue
                        for ((attrName, value) in node.attributes.entryList()) {
                            val actual = value ?: ""
                            val isCurrentColor = actual.lowercase() == "currentcolor"
                            val isFillCurrentColor = preserveCurrentColor && attrName == "fill" && isCurrentColor
                            val isStrokeCurrentColor = preserveCurrentColor && attrName == "stroke" && isCurrentColor
                            if (!isFillCurrentColor && !isStrokeCurrentColor &&
                                list[1].containsMatchIn(attrName) && list[2].containsMatchIn(actual)
                            ) {
                                node.attributes.remove(attrName)
                            }
                        }
                    }
                },
            ),
        )
    }
}

/** removes attributes of elements that match a css selector */
public val removeAttributesBySelector: PluginDefinition = PluginDefinition(
    "removeAttributesBySelector",
    "removes attributes of elements that match a css selector",
) { root, params, _ ->
    val selectorsParam = params.list("selectors")
    if (selectorsParam == null && (params.string("selector") == null || params.raw("attributes") == null)) {
        warn("Warning: The plugin \"removeAttributesBySelector\" is missing parameters.")
        null
    } else {
        val specs: List<Map<String, Any?>> = selectorsParam?.filterIsInstance<Map<String, Any?>>()
            ?: listOf(params.toMapInternal())
        for (spec in specs) {
            val selector = spec["selector"]?.toString() ?: continue
            val attributes = spec["attributes"]
            val nodes = try {
                querySelectorAll(root, selector, strict = true)
            } catch (_: Exception) {
                emptyList()
            }
            for (node in nodes) {
                if (attributes is List<*>) {
                    for (attrName in attributes) node.attributes.remove(attrName.toString())
                } else if (attributes != null) {
                    node.attributes.remove(attributes.toString())
                }
            }
        }
        Visitor()
    }
}

private fun Params.toMapInternal(): Map<String, Any?> = keys.associateWith { raw(it) }

/** removes non-inheritable group's presentational attributes */
public val removeNonInheritableGroupAttrs: PluginDefinition = PluginDefinition(
    "removeNonInheritableGroupAttrs",
    "removes non-inheritable group's presentational attributes",
) { _, _, _ ->
    val presentation = attrsGroups.getValue("presentation")
    Visitor(
        element = Callbacks(
            enter = { node, _ ->
                if (node.name == "g") {
                    for (attrName in node.attributes.keys.toList()) {
                        if (attrName in presentation &&
                            attrName !in io.github.tobsef.svgo.inheritableAttrs &&
                            attrName !in io.github.tobsef.svgo.presentationNonInheritableGroupAttrs
                        ) {
                            node.attributes.remove(attrName)
                        }
                    }
                }
            },
        ),
    )
}

/** adds attributes to an outer `<svg>` element */
public val addAttributesToSVGElement: PluginDefinition = PluginDefinition(
    "addAttributesToSVGElement",
    "adds attributes to an outer <svg> element",
) { _, params, _ ->
    val list = params.list("attributes")
    val single = params.raw("attribute")
    if (list == null && single == null) {
        warn("Error in plugin \"addAttributesToSVGElement\": absent parameters.")
        null
    } else {
        val attributes = list ?: listOf(single)
        Visitor(
            element = Callbacks(
                enter = { node, parentNode ->
                    if (node.name == "svg" && parentNode is io.github.tobsef.svgo.Root) {
                        for (attribute in attributes) {
                            when (attribute) {
                                is String -> if (node.attributes[attribute] == null) node.attributes[attribute] = null
                                is Map<*, *> -> for ((key, value) in attribute) {
                                    val name = key.toString()
                                    if (node.attributes[name] == null) node.attributes[name] = value?.toString()
                                }
                            }
                        }
                    }
                },
            ),
        )
    }
}

/** Produces a class name for [addClassesToSVGElement]. */
public fun interface ClassNameGenerator {
    public fun generate(node: Element, info: io.github.tobsef.svgo.PluginInfo): String
}

/** adds classnames to an outer `<svg>` element */
public val addClassesToSVGElement: PluginDefinition = PluginDefinition(
    "addClassesToSVGElement",
    "adds classnames to an outer <svg> element",
) { _, params, info ->
    val classNames = params.list("classNames")
    val single = params.raw("className")
    if ((classNames == null || classNames.isEmpty()) && single == null) {
        warn("Error in plugin \"addClassesToSVGElement\": absent parameters.")
        null
    } else {
        val names = classNames ?: listOf(single)
        Visitor(
            element = Callbacks(
                enter = { node, parentNode ->
                    if (node.name == "svg" && parentNode is io.github.tobsef.svgo.Root) {
                        val classAttr = node.attributes["class"]
                        val classList = if (classAttr != null) {
                            classAttr.split(" ").distinct().toMutableList()
                        } else {
                            mutableListOf()
                        }
                        for (className in names) {
                            if (className == null) continue
                            val toAdd = when (className) {
                                is ClassNameGenerator -> className.generate(node, info)
                                else -> className.toString()
                            }
                            if (toAdd !in classList) classList.add(toAdd)
                        }
                        node.attributes["class"] = classList.joinToString(" ")
                    }
                },
            ),
        )
    }
}

/** Sorts children of `<defs>` to improve compression */
public val sortDefsChildren: PluginDefinition = PluginDefinition(
    "sortDefsChildren",
    "Sorts children of <defs> to improve compression",
) { _, _, _ ->
    Visitor(
        element = Callbacks(
            enter = { node, _ ->
                if (node.name == "defs") {
                    val frequencies = HashMap<String, Int>()
                    for (child in node.children) {
                        if (child is Element) frequencies[child.name] = (frequencies[child.name] ?: 0) + 1
                    }
                    node.children = node.children.sortedWith { a, b ->
                        if (a !is Element || b !is Element) {
                            0
                        } else {
                            val aFreq = frequencies[a.name]
                            val bFreq = frequencies[b.name]
                            val freqComparison = if (aFreq != null && bFreq != null) bFreq - aFreq else 0
                            when {
                                freqComparison != 0 -> freqComparison
                                b.name.length - a.name.length != 0 -> b.name.length - a.name.length
                                a.name != b.name -> if (a.name > b.name) -1 else 1
                                else -> 0
                            }
                        }
                    }.toMutableList()
                }
            },
        ),
    )
}

private val DEFAULT_ATTR_ORDER = listOf(
    "id", "width", "height", "x", "x1", "x2", "y", "y1", "y2",
    "cx", "cy", "r", "fill", "stroke", "marker", "d", "points",
)

/** Sort element attributes for better compression */
public val sortAttrs: PluginDefinition = PluginDefinition(
    "sortAttrs",
    "Sort element attributes for better compression",
) { _, params, _ ->
    val order = params.stringList("order") ?: DEFAULT_ATTR_ORDER
    val xmlnsOrder = params.string("xmlnsOrder", "front")

    fun nsPriority(name: String): Int {
        if (xmlnsOrder == "front") {
            if (name == "xmlns") return 3
            if (name.startsWith("xmlns:")) return 2
        }
        return if (":" in name) 1 else 0
    }

    Visitor(
        element = Callbacks(
            enter = { node, _ ->
                val sorted = node.attributes.entries.map { it.key to it.value }.sortedWith { a, b ->
                    val priorityNs = nsPriority(b.first) - nsPriority(a.first)
                    if (priorityNs != 0) {
                        priorityNs
                    } else {
                        val aPart = a.first.substringBefore("-")
                        val bPart = b.first.substringBefore("-")
                        var result = 0
                        if (aPart != bPart) {
                            val aIn = if (aPart in order) 1 else 0
                            val bIn = if (bPart in order) 1 else 0
                            result = if (aIn == 1 && bIn == 1) {
                                order.indexOf(aPart) - order.indexOf(bPart)
                            } else {
                                bIn - aIn
                            }
                        }
                        if (result != 0) result else if (a.first < b.first) -1 else 1
                    }
                }
                val attributes = LinkedHashMap<String, String?>()
                for ((name, value) in sorted) attributes[name] = value
                node.attributes = attributes
            },
        ),
    )
}

private val REG_NEWLINES_NEED_SPACE = Regex("""(\S)\r?\n(\S)""")
private val REG_NEWLINES = Regex("""\r?\n""")
private val REG_REPEATED_SPACES = Regex("""\s{2,}""")

/** cleanups attributes from newlines, trailing and repeating spaces */
public val cleanupAttrs: PluginDefinition = PluginDefinition(
    "cleanupAttrs",
    "cleanups attributes from newlines, trailing and repeating spaces",
) { _, params, _ ->
    val newlines = params.bool("newlines", true)
    val trim = params.bool("trim", true)
    val spaces = params.bool("spaces", true)

    Visitor(
        element = Callbacks(
            enter = { node, _ ->
                for (attrName in node.attributes.keys.toList()) {
                    var value = node.attributes[attrName] ?: continue
                    if (newlines) {
                        value = REG_NEWLINES_NEED_SPACE.replace(value) {
                            it.groupValues[1] + " " + it.groupValues[2]
                        }
                        value = REG_NEWLINES.replace(value, "")
                    }
                    if (trim) value = value.trim()
                    if (spaces) value = REG_REPEATED_SPACES.replace(value, " ")
                    node.attributes[attrName] = value
                }
            },
        ),
    )
}

/** converts non-eccentric `<ellipse>`s to `<circle>`s */
public val convertEllipseToCircle: PluginDefinition = PluginDefinition(
    "convertEllipseToCircle",
    "converts non-eccentric <ellipse>s to <circle>s",
) { _, _, _ ->
    Visitor(
        element = Callbacks(
            enter = { node, _ ->
                if (node.name == "ellipse") {
                    val rx = node.attributes["rx"] ?: "0"
                    val ry = node.attributes["ry"] ?: "0"
                    if (rx == ry || rx == "auto" || ry == "auto") {
                        node.name = "circle"
                        val radius = if (rx == "auto") ry else rx
                        node.attributes.remove("rx")
                        node.attributes.remove("ry")
                        node.attributes["r"] = radius
                    }
                }
            },
        ),
    )
}
