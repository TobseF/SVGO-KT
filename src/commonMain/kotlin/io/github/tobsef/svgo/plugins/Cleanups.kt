package io.github.tobsef.svgo.plugins

import io.github.tobsef.svgo.Callbacks
import io.github.tobsef.svgo.Declaration
import io.github.tobsef.svgo.Element
import io.github.tobsef.svgo.PluginDefinition
import io.github.tobsef.svgo.RootCallbacks
import io.github.tobsef.svgo.VISIT_SKIP
import io.github.tobsef.svgo.Visitor
import io.github.tobsef.svgo.encodeUri
import io.github.tobsef.svgo.entryList
import io.github.tobsef.svgo.findReferences
import io.github.tobsef.svgo.hasScripts
import io.github.tobsef.svgo.jsNumberToString
import io.github.tobsef.svgo.jsToFixed
import io.github.tobsef.svgo.parseStyleDeclarations
import io.github.tobsef.svgo.removeLeadingZero
import io.github.tobsef.svgo.visit

private val REG_NUMERIC_OPTIONAL_UNIT = Regex("""^([-+]?\d*\.?\d+([eE][-+]?\d+)?)(px|pt|pc|mm|cm|m|in|ft|em|ex|%)?$""")
private val REG_VIEWBOX_SPLIT = Regex("""(?:\s,?|,)\s*""")

private val ABSOLUTE_LENGTHS = mapOf(
    "cm" to 96 / 2.54,
    "mm" to 96 / 25.4,
    "in" to 96.0,
    "pt" to 4.0 / 3,
    "pc" to 16.0,
    "px" to 1.0,
)

private fun toNumber(value: String?): Double = value?.toDoubleOrNull() ?: Double.NaN

/** rounds numeric values to the fixed precision, removes default "px" units */
public val cleanupNumericValues: PluginDefinition = PluginDefinition(
    "cleanupNumericValues",
    "rounds numeric values to the fixed precision, removes default \"px\" units",
) { _, params, _ ->
    val floatPrecision = params.int("floatPrecision", 3)
    val leadingZero = params.bool("leadingZero", true)
    val defaultPx = params.bool("defaultPx", true)
    val convertToPx = params.bool("convertToPx", true)

    Visitor(
        element = Callbacks(
            enter = { node, _ ->
                val viewBox = node.attributes["viewBox"]
                if (viewBox != null) {
                    node.attributes["viewBox"] = viewBox.trim().split(REG_VIEWBOX_SPLIT).joinToString(" ") { value ->
                        val number = toNumber(value)
                        if (number.isNaN()) value else jsNumberToString(jsToFixed(number, floatPrecision))
                    }
                }

                for ((attrName, rawValue) in node.attributes.entryList()) {
                    if (attrName == "version") continue
                    val value = rawValue ?: continue
                    val match = REG_NUMERIC_OPTIONAL_UNIT.matchEntire(value) ?: continue
                    var number = jsToFixed(toNumber(match.groupValues[1]), floatPrecision)
                    var units = match.groupValues[3]

                    val factor = ABSOLUTE_LENGTHS[units]
                    if (convertToPx && units.isNotEmpty() && factor != null) {
                        val pxNumber = jsToFixed(factor * toNumber(match.groupValues[1]), floatPrecision)
                        if (jsNumberToString(pxNumber).length < match.value.length) {
                            number = pxNumber
                            units = "px"
                        }
                    }

                    val text = if (leadingZero) removeLeadingZero(number) else jsNumberToString(number)
                    if (defaultPx && units == "px") units = ""
                    node.attributes[attrName] = text + units
                }
            },
        ),
    )
}

private val REG_LIST_SEPARATOR = Regex("""\s+,?\s*|,\s*""")
private val REG_NEW = Regex("new")

/** rounds list of values to the fixed precision */
public val cleanupListOfValues: PluginDefinition = PluginDefinition(
    "cleanupListOfValues",
    "rounds list of values to the fixed precision",
) { _, params, _ ->
    val floatPrecision = params.int("floatPrecision", 3)
    val leadingZero = params.bool("leadingZero", true)
    val defaultPx = params.bool("defaultPx", true)
    val convertToPx = params.bool("convertToPx", true)

    fun roundValues(lists: String): String {
        val rounded = ArrayList<String>()
        for (element in lists.split(REG_LIST_SEPARATOR)) {
            val match = REG_NUMERIC_OPTIONAL_UNIT.matchEntire(element)
            if (match != null) {
                var number = jsToFixed(toNumber(match.groupValues[1]), floatPrecision)
                var units = match.groupValues[3]
                val factor = ABSOLUTE_LENGTHS[units]
                if (convertToPx && units.isNotEmpty() && factor != null) {
                    val pxNumber = jsToFixed(factor * toNumber(match.groupValues[1]), floatPrecision)
                    if (jsNumberToString(pxNumber).length < match.value.length) {
                        number = pxNumber
                        units = "px"
                    }
                }
                val text = if (leadingZero) removeLeadingZero(number) else jsNumberToString(number)
                if (defaultPx && units == "px") units = ""
                rounded.add(text + units)
            } else if (REG_NEW.containsMatchIn(element)) {
                rounded.add("new")
            } else if (element.isNotEmpty()) {
                rounded.add(element)
            }
        }
        return rounded.joinToString(" ")
    }

    Visitor(
        element = Callbacks(
            enter = { node, _ ->
                for (attr in listOf("points", "enable-background", "viewBox", "stroke-dasharray", "dx", "dy", "x", "y")) {
                    val value = node.attributes[attr] ?: continue
                    node.attributes[attr] = roundValues(value)
                }
            },
        ),
    )
}

private val REG_ENABLE_BACKGROUND =
    Regex("""^new\s0\s0\s([-+]?\d*\.?\d+([eE][-+]?\d+)?)\s([-+]?\d*\.?\d+([eE][-+]?\d+)?)$""")

private fun serializeDeclarations(declarations: List<Declaration>): String =
    declarations.joinToString(";") { "${it.name}:${it.value}${if (it.important) "!important" else ""}" }

private fun cleanupEnableBackgroundValue(
    value: String,
    nodeName: String,
    width: String?,
    height: String?,
): String? {
    val match = REG_ENABLE_BACKGROUND.matchEntire(value)
    if (match != null && width == match.groupValues[1] && height == match.groupValues[3]) {
        return if (nodeName == "svg") null else "new"
    }
    return value
}

/** remove or cleanup enable-background attribute when possible */
public val cleanupEnableBackground: PluginDefinition = PluginDefinition(
    "cleanupEnableBackground",
    "remove or cleanup enable-background attribute when possible",
) { root, _, _ ->
    var hasFilter = false
    visit(
        root,
        Visitor(element = Callbacks(enter = { node, _ -> if (node.name == "filter") hasFilter = true })),
    )

    Visitor(
        element = Callbacks(
            enter = { node, _ ->
                var declarations: MutableList<Declaration>? = null
                var ebIndex: Int? = null

                val styleAttr = node.attributes["style"]
                if (styleAttr != null) {
                    val parsed = parseStyleDeclarations(styleAttr).toMutableList()
                    // remove all but the last enable-background declaration
                    val positions = parsed.indices.filter { parsed[it].name == "enable-background" }
                    val keepLast = positions.lastOrNull()
                    val filtered = parsed.filterIndexed { index, _ ->
                        parsed[index].name != "enable-background" || index == keepLast
                    }.toMutableList()
                    declarations = filtered
                    ebIndex = filtered.indices.lastOrNull { filtered[it].name == "enable-background" }
                }

                if (!hasFilter) {
                    node.attributes.remove("enable-background")
                    if (declarations != null) {
                        if (ebIndex != null) declarations.removeAt(ebIndex)
                        if (declarations.isEmpty()) {
                            node.attributes.remove("style")
                        } else {
                            node.attributes["style"] = serializeDeclarations(declarations)
                        }
                    }
                } else {
                    val hasDimensions =
                        node.attributes["width"] != null && node.attributes["height"] != null
                    if (node.name in setOf("svg", "mask", "pattern") && hasDimensions) {
                        val attrValue = node.attributes["enable-background"]
                        if (attrValue != null) {
                            val cleaned = cleanupEnableBackgroundValue(
                                attrValue,
                                node.name,
                                node.attributes["width"],
                                node.attributes["height"],
                            )
                            if (!cleaned.isNullOrEmpty()) {
                                node.attributes["enable-background"] = cleaned
                            } else {
                                node.attributes.remove("enable-background")
                            }
                        }

                        if (declarations != null && ebIndex != null) {
                            val cleaned = cleanupEnableBackgroundValue(
                                declarations[ebIndex].value,
                                node.name,
                                node.attributes["width"],
                                node.attributes["height"],
                            )
                            if (!cleaned.isNullOrEmpty()) {
                                declarations[ebIndex].value = cleaned
                            } else {
                                declarations.removeAt(ebIndex)
                            }
                        }
                    }

                    if (declarations != null) {
                        if (declarations.isEmpty()) {
                            node.attributes.remove("style")
                        } else {
                            node.attributes["style"] = serializeDeclarations(declarations)
                        }
                    }
                }
            },
        ),
    )
}

// ---------------------------------------------------------------------------
// cleanupIds
// ---------------------------------------------------------------------------

private val GEN_CHARS: List<Char> = ('a'..'z').toList() + ('A'..'Z').toList()
private val MAX_ID_INDEX = GEN_CHARS.size - 1

private fun generateId(currentId: MutableList<Int>?): MutableList<Int> {
    if (currentId == null) return mutableListOf(0)
    currentId[currentId.size - 1]++
    for (i in currentId.size - 1 downTo 1) {
        if (currentId[i] > MAX_ID_INDEX) {
            currentId[i] = 0
            currentId[i - 1]++
        }
    }
    if (currentId[0] > MAX_ID_INDEX) {
        currentId[0] = 0
        currentId.add(0, 0)
    }
    return currentId
}

private fun getIdString(arr: List<Int>): String = arr.map { GEN_CHARS[it] }.joinToString("")

private class IdReference(val element: Element, val name: String)

/** removes unused IDs and minifies used */
public val cleanupIds: PluginDefinition = PluginDefinition(
    "cleanupIds",
    "removes unused IDs and minifies used",
) { _, params, _ ->
    val remove = params.bool("remove", true)
    val minify = params.bool("minify", true)
    val preserveIds = (params.raw("preserve").let { if (it is String) listOf(it) else params.stringList("preserve") }
        ?: emptyList()).toSet()
    val preserveIdPrefixes = params.raw("preservePrefixes").let {
        if (it is String) listOf(it) else params.stringList("preservePrefixes")
    } ?: emptyList()
    val force = params.bool("force", false)

    val nodeById = LinkedHashMap<String, Element>()
    val referencesById = LinkedHashMap<String, MutableList<IdReference>>()
    var deoptimized = false

    fun isIdPreserved(id: String): Boolean =
        id in preserveIds || preserveIdPrefixes.any { id.startsWith(it) }

    Visitor(
        element = Callbacks(
            enter = { node, _ ->
                var skip = false
                var result: Any? = null
                if (!force) {
                    if ((node.name == "style" && node.children.isNotEmpty()) || hasScripts(node)) {
                        deoptimized = true
                        skip = true
                    } else if (node.name == "svg" && node.children.all { it is Element && it.name == "defs" }) {
                        result = VISIT_SKIP
                        skip = true
                    }
                }
                if (!skip) {
                    for ((attrName, value) in node.attributes.entryList()) {
                        if (attrName == "id") {
                            val id = value ?: continue
                            if (id in nodeById) {
                                node.attributes.remove("id")
                            } else {
                                nodeById[id] = node
                            }
                        } else if (value != null) {
                            for (id in findReferences(attrName, value)) {
                                referencesById.getOrPut(id) { ArrayList() }.add(IdReference(node, attrName))
                            }
                        }
                    }
                }
                result
            },
        ),
        root = RootCallbacks(
            exit = {
                if (!deoptimized) {
                    var currentId: MutableList<Int>? = null
                    for ((id, refs) in referencesById.entryList()) {
                        val target = nodeById[id]
                        if (target != null) {
                            if (minify && !isIdPreserved(id)) {
                                var currentIdString: String
                                while (true) {
                                    currentId = generateId(currentId)
                                    currentIdString = getIdString(currentId)
                                    if (!(
                                            isIdPreserved(currentIdString) ||
                                                (referencesById.containsKey(currentIdString) && nodeById[currentIdString] == null)
                                            )
                                    ) {
                                        break
                                    }
                                }
                                target.attributes["id"] = currentIdString
                                for (ref in refs) {
                                    val value = ref.element.attributes[ref.name] ?: continue
                                    if ("#" in value) {
                                        var updated = value.replace("#" + encodeUri(id), "#$currentIdString")
                                        updated = updated.replace("#$id", "#$currentIdString")
                                        ref.element.attributes[ref.name] = updated
                                    } else {
                                        ref.element.attributes[ref.name] = value.replace("$id.", "$currentIdString.")
                                    }
                                }
                            }
                            nodeById.remove(id)
                        }
                    }
                    if (remove) {
                        for ((id, target) in nodeById.entryList()) {
                            if (!isIdPreserved(id)) target.attributes.remove("id")
                        }
                    }
                }
            },
        ),
    )
}
