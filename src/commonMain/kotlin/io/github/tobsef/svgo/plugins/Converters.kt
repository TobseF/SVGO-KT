package io.github.tobsef.svgo.plugins

import io.github.tobsef.svgo.Callbacks
import io.github.tobsef.svgo.Element
import io.github.tobsef.svgo.PathItem
import io.github.tobsef.svgo.PluginDefinition
import io.github.tobsef.svgo.Visitor
import io.github.tobsef.svgo.attrsGroups
import io.github.tobsef.svgo.attrsGroupsDefaults
import io.github.tobsef.svgo.collectStylesheet
import io.github.tobsef.svgo.colorsNames
import io.github.tobsef.svgo.colorsProps
import io.github.tobsef.svgo.colorsShortNames
import io.github.tobsef.svgo.computeStyle
import io.github.tobsef.svgo.detachNodeFromParent
import io.github.tobsef.svgo.entryList
import io.github.tobsef.svgo.includesCssVarReference
import io.github.tobsef.svgo.includesUrlReference
import io.github.tobsef.svgo.jsNumber
import io.github.tobsef.svgo.querySelector
import io.github.tobsef.svgo.querySelectorAll
import io.github.tobsef.svgo.stringifyPathData
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------------------
// convertColors
// ---------------------------------------------------------------------------

private const val R_NUMBER = """([+-]?(?:\d*\.\d+|\d+\.?)%?)"""
private const val R_COMMA = """(?:\s*,\s*|\s+)"""
private val REG_RGB = Regex("""^rgb\(\s*$R_NUMBER$R_COMMA$R_NUMBER$R_COMMA$R_NUMBER\s*\)$""")
private val REG_HEX = Regex("""^#(([a-fA-F0-9])\2){3}$""")

private fun convertRgbToHex(r: Int, g: Int, b: Int): String {
    // truncate toward zero (JS bitwise ToInt32) then pack
    val hexNumber = ((((256 + r) shl 8) or g) shl 8) or b
    return "#" + hexNumber.toString(16).substring(1).uppercase()
}

/** converts colors: rgb() to #rrggbb and #rrggbb to #rgb */
public val convertColors: PluginDefinition = PluginDefinition(
    "convertColors",
    "converts colors: rgb() to #rrggbb and #rrggbb to #rgb",
) { _, params, _ ->
    val currentColorRaw = params.raw("currentColor")
    val currentColorEnabled = io.github.tobsef.svgo.Params.isTruthy(currentColorRaw)
    val currentColorString = currentColorRaw as? String
    val currentColorRegex = currentColorRaw as? Regex
    val names2hex = params.bool("names2hex", true)
    val rgb2hex = params.bool("rgb2hex", true)
    val convertCase = if (params.has("convertCase")) params.raw("convertCase") else "lower"
    val shorthex = params.bool("shorthex", true)
    val shortname = params.bool("shortname", true)

    var maskCounter = 0

    Visitor(
        element = Callbacks(
            enter = { node, _ ->
                if (node.name == "mask") maskCounter++
                for ((attrName, rawValue) in node.attributes.entryList()) {
                    if (attrName !in colorsProps) continue
                    var value = rawValue ?: continue

                    if (currentColorEnabled && maskCounter == 0) {
                        val matched = when {
                            currentColorString != null -> value == currentColorString
                            currentColorRegex != null -> currentColorRegex.containsMatchIn(value)
                            else -> value != "none"
                        }
                        if (matched) value = "currentColor"
                    }

                    if (names2hex) {
                        colorsNames[value.lowercase()]?.let { value = it }
                    }

                    if (rgb2hex) {
                        val match = REG_RGB.matchEntire(value)
                        if (match != null) {
                            val numbers = (1..3).map { index ->
                                val part = match.groupValues[index]
                                val number = if ("%" in part) {
                                    floor(part.replace("%", "").toDouble() * 2.55 + 0.5)
                                } else {
                                    part.toDouble()
                                }
                                max(0.0, min(number, 255.0)).toInt()
                            }
                            value = convertRgbToHex(numbers[0], numbers[1], numbers[2])
                        }
                    }

                    if (io.github.tobsef.svgo.Params.isTruthy(convertCase) &&
                        !includesUrlReference(value) &&
                        !includesCssVarReference(value) &&
                        value != "currentColor"
                    ) {
                        if (convertCase == "lower") value = value.lowercase()
                        if (convertCase == "upper") value = value.uppercase()
                    }

                    if (shorthex) {
                        val match = REG_HEX.matchEntire(value)
                        if (match != null) {
                            val whole = match.value
                            value = "#" + whole[1] + whole[3] + whole[5]
                        }
                    }

                    if (shortname) {
                        colorsShortNames[value.lowercase()]?.let { value = it }
                    }

                    node.attributes[attrName] = value
                }
            },
            exit = { node, _ -> if (node.name == "mask") maskCounter-- },
        ),
    )
}

// ---------------------------------------------------------------------------
// convertShapeToPath
// ---------------------------------------------------------------------------

private val REG_SHAPE_NUMBER = Regex("""[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?""")

/** converts basic shapes to more compact path form */
public val convertShapeToPath: PluginDefinition = PluginDefinition(
    "convertShapeToPath",
    "converts basic shapes to more compact path form",
) { _, params, _ ->
    val convertArcs = params.bool("convertArcs", false)
    val precision = params.intOrNull("floatPrecision")

    Visitor(
        element = Callbacks(
            enter = { node, parentNode ->
                val attrs = node.attributes
                var done = false

                // rect -> path
                if (node.name == "rect" &&
                    attrs["width"] != null && attrs["height"] != null &&
                    attrs["rx"] == null && attrs["ry"] == null
                ) {
                    val x = jsNumber(attrs["x"] ?: "0")
                    val y = jsNumber(attrs["y"] ?: "0")
                    val width = jsNumber(attrs["width"])
                    val height = jsNumber(attrs["height"])
                    if ((x - y + width - height).isNaN()) {
                        done = true
                    } else {
                        val pathData = listOf(
                            PathItem("M", mutableListOf(x, y)),
                            PathItem("H", mutableListOf(x + width)),
                            PathItem("V", mutableListOf(y + height)),
                            PathItem("H", mutableListOf(x)),
                            PathItem("z", mutableListOf()),
                        )
                        node.name = "path"
                        attrs["d"] = stringifyPathData(pathData, precision)
                        for (a in listOf("x", "y", "width", "height")) attrs.remove(a)
                    }
                }

                // line -> path
                if (!done && node.name == "line") {
                    val x1 = jsNumber(attrs["x1"] ?: "0")
                    val y1 = jsNumber(attrs["y1"] ?: "0")
                    val x2 = jsNumber(attrs["x2"] ?: "0")
                    val y2 = jsNumber(attrs["y2"] ?: "0")
                    if ((x1 - y1 + x2 - y2).isNaN()) {
                        done = true
                    } else {
                        val pathData = listOf(
                            PathItem("M", mutableListOf(x1, y1)),
                            PathItem("L", mutableListOf(x2, y2)),
                        )
                        node.name = "path"
                        attrs["d"] = stringifyPathData(pathData, precision)
                        for (a in listOf("x1", "y1", "x2", "y2")) attrs.remove(a)
                    }
                }

                // polyline / polygon -> path
                if (!done && (node.name == "polyline" || node.name == "polygon") && attrs["points"] != null) {
                    val coords = REG_SHAPE_NUMBER.findAll(attrs["points"]!!).map { jsNumber(it.value) }.toList()
                    if (coords.size < 4) {
                        detachNodeFromParent(node, parentNode)
                        done = true
                    } else {
                        val pathData = ArrayList<PathItem>()
                        var i = 0
                        while (i + 1 < coords.size) {
                            pathData.add(
                                PathItem(
                                    if (i == 0) "M" else "L",
                                    mutableListOf(coords[i], coords[i + 1]),
                                ),
                            )
                            i += 2
                        }
                        if (coords.size % 2 == 1) {
                            pathData.add(PathItem("L", mutableListOf(coords[coords.size - 1])))
                        }
                        if (node.name == "polygon") pathData.add(PathItem("z", mutableListOf()))
                        node.name = "path"
                        attrs["d"] = stringifyPathData(pathData, precision)
                        attrs.remove("points")
                    }
                }

                // circle -> path (optional)
                if (!done && node.name == "circle" && convertArcs) {
                    val cx = jsNumber(attrs["cx"] ?: "0")
                    val cy = jsNumber(attrs["cy"] ?: "0")
                    val r = jsNumber(attrs["r"] ?: "0")
                    if ((cx - cy + r).isNaN()) {
                        done = true
                    } else {
                        val pathData = listOf(
                            PathItem("M", mutableListOf(cx, cy - r)),
                            PathItem("A", mutableListOf(r, r, 0.0, 1.0, 0.0, cx, cy + r)),
                            PathItem("A", mutableListOf(r, r, 0.0, 1.0, 0.0, cx, cy - r)),
                            PathItem("z", mutableListOf()),
                        )
                        node.name = "path"
                        attrs["d"] = stringifyPathData(pathData, precision)
                        for (a in listOf("cx", "cy", "r")) attrs.remove(a)
                    }
                }

                // ellipse -> path (optional)
                if (!done && node.name == "ellipse" && convertArcs) {
                    val ecx = jsNumber(attrs["cx"] ?: "0")
                    val ecy = jsNumber(attrs["cy"] ?: "0")
                    val rx = jsNumber(attrs["rx"] ?: "0")
                    val ry = jsNumber(attrs["ry"] ?: "0")
                    if (!(ecx - ecy + rx - ry).isNaN()) {
                        val pathData = listOf(
                            PathItem("M", mutableListOf(ecx, ecy - ry)),
                            PathItem("A", mutableListOf(rx, ry, 0.0, 1.0, 0.0, ecx, ecy + ry)),
                            PathItem("A", mutableListOf(rx, ry, 0.0, 1.0, 0.0, ecx, ecy - ry)),
                            PathItem("z", mutableListOf()),
                        )
                        node.name = "path"
                        attrs["d"] = stringifyPathData(pathData, precision)
                        for (a in listOf("cx", "cy", "rx", "ry")) attrs.remove(a)
                    }
                }
            },
        ),
    )
}

// ---------------------------------------------------------------------------
// convertStyleToAttrs
// ---------------------------------------------------------------------------

private fun group(vararg alternatives: String): String = "(?:" + alternatives.joinToString("|") + ")"

private const val R_ESCAPE = """\\(?:[0-9a-f]{1,6}\s?|\r\n|.)"""
private val R_ATTR = """\s*(""" + group("""[^:;\\]""", R_ESCAPE) + """*?)\s*"""
private val R_SINGLE_QUOTES = """'(?:[^'\n\r\\]|""" + R_ESCAPE + """)*?(?:'|$)"""
private val R_QUOTES = """"(?:[^"\n\r\\]|""" + R_ESCAPE + """)*?(?:"|$)"""
private val REG_QUOTED_STRING = Regex("^" + group(R_SINGLE_QUOTES, R_QUOTES) + "$")
private val R_PARENTHESIS =
    """\(""" + group("""[^'"()\\]+""", R_ESCAPE, R_SINGLE_QUOTES, R_QUOTES) + """*?""" + """\)"""
private val R_VALUE = """\s*(""" +
    group("""[^!'"();\\]+?""", R_ESCAPE, R_SINGLE_QUOTES, R_QUOTES, R_PARENTHESIS, """[^;]*?""") +
    """*?""" + """)"""
private const val R_DECL_END = """\s*(?:;\s*|$)"""
private const val R_IMPORTANT = """(\s*!important(?![-(\w]))?"""
private val REG_DECLARATION_BLOCK =
    Regex(R_ATTR + ":" + R_VALUE + R_IMPORTANT + R_DECL_END, RegexOption.IGNORE_CASE)
private val REG_STRIP_COMMENTS =
    Regex(group(R_ESCAPE, R_SINGLE_QUOTES, R_QUOTES, """/\*[\s\S]*?\*/"""), RegexOption.IGNORE_CASE)
private val REG_GZ = Regex("[-g-z]", RegexOption.IGNORE_CASE)

/** converts style to attributes */
public val convertStyleToAttrs: PluginDefinition = PluginDefinition(
    "convertStyleToAttrs",
    "converts style to attributes",
) { _, params, _ ->
    val keepImportant = params.bool("keepImportant", false)
    val stylingProps = attrsGroups.getValue("presentation")

    Visitor(
        element = Callbacks(
            enter = { node, _ ->
                val style = node.attributes["style"]
                if (style != null) {
                    var styles = ArrayList<Pair<String, String>>()
                    val newAttributes = LinkedHashMap<String, String>()

                    val styleValue = REG_STRIP_COMMENTS.replace(style) { match ->
                        val text = match.value
                        when {
                            text[0] == '/' -> ""
                            text[0] == '\\' && text.length > 1 && REG_GZ.matchesAt(text, 1) -> text[1].toString()
                            else -> text
                        }
                    }

                    for (match in REG_DECLARATION_BLOCK.findAll(styleValue)) {
                        val important = match.groupValues[3]
                        if (!keepImportant || important.isEmpty()) {
                            styles.add(match.groupValues[1] to match.groupValues[2])
                        }
                    }

                    if (styles.isNotEmpty()) {
                        val kept = ArrayList<Pair<String, String>>()
                        for (stylePair in styles) {
                            var keep = true
                            if (stylePair.first.isNotEmpty()) {
                                val property = stylePair.first.lowercase()
                                var value = stylePair.second
                                if (REG_QUOTED_STRING.matches(value)) value = value.substring(1, value.length - 1)
                                if (property in stylingProps) {
                                    newAttributes[property] = value
                                    keep = false
                                }
                            }
                            if (keep) kept.add(stylePair)
                        }
                        styles = kept

                        for ((key, value) in newAttributes) node.attributes[key] = value

                        if (styles.isNotEmpty()) {
                            node.attributes["style"] = styles.joinToString(";") { it.first + ":" + it.second }
                        } else {
                            node.attributes.remove("style")
                        }
                    }
                }
            },
        ),
    )
}

// ---------------------------------------------------------------------------
// convertOneStopGradients
// ---------------------------------------------------------------------------

/** converts one-stop (single color) gradients to a plain color */
public val convertOneStopGradients: PluginDefinition = PluginDefinition(
    "convertOneStopGradients",
    "converts one-stop (single color) gradients to a plain color",
) { root, _, _ ->
    val stylesheet = collectStylesheet(root)
    val effectedDefs = LinkedHashSet<Element>()
    val allDefs = LinkedHashMap<Element, io.github.tobsef.svgo.XastParent>()
    val gradientsToDetach = LinkedHashMap<Element, io.github.tobsef.svgo.XastParent>()
    var xlinkCount = 0

    Visitor(
        element = Callbacks(
            enter = { node, parentNode ->
                if (node.attributes["xlink:href"] != null) xlinkCount++

                if (node.name == "defs") {
                    allDefs[node] = parentNode
                } else if (node.name == "linearGradient" || node.name == "radialGradient") {
                    val stops = node.children.filterIsInstance<Element>().filter { it.name == "stop" }
                    val href = node.attributes["xlink:href"] ?: node.attributes["href"]
                    val effectiveNode: Element? = if (stops.isEmpty() && href != null && href.startsWith("#")) {
                        try {
                            querySelector(root, href, strict = true)
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        node
                    }

                    if (effectiveNode == null) {
                        gradientsToDetach[node] = parentNode
                    } else {
                        val effectiveStops =
                            effectiveNode.children.filterIsInstance<Element>().filter { it.name == "stop" }
                        if (effectiveStops.size == 1) {
                            if (parentNode is Element && parentNode.name == "defs") effectedDefs.add(parentNode)
                            gradientsToDetach[node] = parentNode

                            val style = computeStyle(stylesheet, effectiveStops[0])["stop-color"]
                            val color = if (style != null && style.isStatic) style.value else null

                            val selectorValue = "url(#${node.attributes["id"]})"
                            val selector = colorsProps.joinToString(",") { "[$it=\"$selectorValue\"]" }
                            val elements = try {
                                querySelectorAll(root, selector, strict = true)
                            } catch (_: Exception) {
                                emptyList()
                            }
                            for (element in elements) {
                                for (attr in colorsProps) {
                                    if (element.attributes[attr] != selectorValue) continue
                                    if (color != null) {
                                        element.attributes[attr] = color
                                    } else {
                                        element.attributes.remove(attr)
                                    }
                                }
                            }

                            for (element in findStyleReferencing(root, selectorValue)) {
                                element.attributes["style"] = element.attributes["style"]!!.replace(
                                    selectorValue,
                                    color ?: attrsGroupsDefaults.getValue("presentation").getValue("stop-color"),
                                )
                            }
                        }
                    }
                }
            },
            exit = { node, _ ->
                if (node.name == "svg") {
                    for ((gradient, parent) in gradientsToDetach) {
                        if (gradient.attributes["xlink:href"] != null) xlinkCount--
                        detachNodeFromParent(gradient, parent)
                    }
                    if (xlinkCount == 0) node.attributes.remove("xmlns:xlink")
                    for ((defs, parent) in allDefs) {
                        if (defs in effectedDefs && defs.children.isEmpty()) detachNodeFromParent(defs, parent)
                    }
                }
            },
        ),
    )
}

/** Equivalent of `querySelectorAll(root, "[style*=<value>]")`. */
private fun findStyleReferencing(root: io.github.tobsef.svgo.XastParent, selectorValue: String): List<Element> {
    val results = ArrayList<Element>()

    fun walk(node: io.github.tobsef.svgo.XastParent) {
        for (child in node.children) {
            if (child is Element) {
                val style = child.attributes["style"]
                if (style != null && selectorValue in style) results.add(child)
                walk(child)
            }
        }
    }

    walk(root)
    return results
}
