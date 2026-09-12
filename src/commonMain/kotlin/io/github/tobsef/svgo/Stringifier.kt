package io.github.tobsef.svgo

/** XAST -> SVG string. Port of `lib/stringifier.js`. */

/** Serialization options (`js2svg` in the SVGO config). */
public class StringifyOptions(
    public val indent: Any? = null,
    public val pretty: Boolean = false,
    public val useShortTags: Boolean = true,
    public val eol: String = "lf",
    public val finalNewline: Boolean = false,
)

private const val TAG_OPEN_START = "<"
private const val TAG_OPEN_END = ">"
private const val TAG_CLOSE_START = "</"
private const val TAG_CLOSE_END = ">"
private const val TAG_SHORT_START = "<"
private const val TAG_SHORT_END = "/>"

private val REG_ENTITIES = Regex("""['"<>&]""")
private val REG_VAL_ENTITIES = Regex("""["<>&]""")

private fun encodeEntity(value: String): String = when (value) {
    "&" -> "&amp;"
    "'" -> "&apos;"
    "\"" -> "&quot;"
    ">" -> "&gt;"
    "<" -> "&lt;"
    else -> value
}

private class StringifyConfig(options: StringifyOptions?) {
    val pretty = options?.pretty ?: false
    val useShortTags = options?.useShortTags ?: true
    val finalNewline = options?.finalNewline ?: false
    val eol = if (options?.eol == "crlf") "\r\n" else "\n"

    val indent: String = when (val value = options?.indent) {
        null -> "    "
        is Int -> if (value < 0) "\t" else " ".repeat(value)
        is Double -> if (value < 0) "\t" else " ".repeat(value.toInt())
        is String -> value
        else -> "    "
    }

    val doctypeStart = "<!DOCTYPE"
    val doctypeEnd = if (pretty) ">$eol" else ">"
    val procInstStart = "<?"
    val procInstEnd = if (pretty) "?>$eol" else "?>"
    val tagOpenEnd = if (pretty) "$TAG_OPEN_END$eol" else TAG_OPEN_END
    val tagCloseEnd = if (pretty) "$TAG_CLOSE_END$eol" else TAG_CLOSE_END
    val tagShortEnd = if (pretty) "$TAG_SHORT_END$eol" else TAG_SHORT_END
    val commentStart = "<!--"
    val commentEnd = if (pretty) "-->$eol" else "-->"
    val cdataStart = "<![CDATA["
    val cdataEnd = if (pretty) "]]>$eol" else "]]>"
    val textStart = ""
    val textEnd = if (pretty) eol else ""
}

private class State(val indent: String) {
    var textContext: Element? = null
    var indentLevel: Int = 0
}

public fun stringifySvg(data: Root, options: StringifyOptions? = null): String {
    val config = StringifyConfig(options)
    val state = State(config.indent)
    var svg = stringifyChildren(data, config, state)
    if (config.finalNewline && svg.isNotEmpty() && !svg.endsWith("\n")) svg += config.eol
    return svg
}

private fun stringifyChildren(data: XastParent, config: StringifyConfig, state: State): String {
    val svg = StringBuilder()
    state.indentLevel++
    for (item in data.children) {
        when (item) {
            is Element -> svg.append(stringifyElement(item, config, state))
            is Text -> svg.append(stringifyText(item, config, state))
            is Doctype -> svg.append(config.doctypeStart + item.data["doctype"] + config.doctypeEnd)
            is Instruction ->
                svg.append(config.procInstStart + item.name + " " + item.value + config.procInstEnd)
            is Comment -> svg.append(config.commentStart + item.value + config.commentEnd)
            is Cdata ->
                svg.append(createIndent(config, state) + config.cdataStart + item.value + config.cdataEnd)
        }
    }
    state.indentLevel--
    return svg.toString()
}

private fun createIndent(config: StringifyConfig, state: State): String =
    if (config.pretty && state.textContext == null) state.indent.repeat(state.indentLevel - 1) else ""

private fun stringifyElement(node: Element, config: StringifyConfig, state: State): String {
    if (node.children.isEmpty()) {
        return if (config.useShortTags) {
            createIndent(config, state) + TAG_SHORT_START + node.name +
                stringifyAttributes(node) + config.tagShortEnd
        } else {
            createIndent(config, state) + TAG_SHORT_START + node.name + stringifyAttributes(node) +
                config.tagOpenEnd + TAG_CLOSE_START + node.name + config.tagCloseEnd
        }
    }

    var tagOpenStart = TAG_OPEN_START
    var tagOpenEnd = config.tagOpenEnd
    var tagCloseStart = TAG_CLOSE_START
    var tagCloseEnd = config.tagCloseEnd
    var openIndent = createIndent(config, state)
    var closeIndent = createIndent(config, state)

    if (state.textContext != null) {
        tagOpenStart = TAG_OPEN_START
        tagOpenEnd = TAG_OPEN_END
        tagCloseStart = TAG_CLOSE_START
        tagCloseEnd = TAG_CLOSE_END
        openIndent = ""
    } else if (node.name in textElems) {
        tagOpenEnd = TAG_OPEN_END
        tagCloseStart = TAG_CLOSE_START
        closeIndent = ""
        state.textContext = node
    }

    val children = stringifyChildren(node, config, state)

    if (state.textContext === node) state.textContext = null

    return openIndent + tagOpenStart + node.name + stringifyAttributes(node) + tagOpenEnd +
        children + closeIndent + tagCloseStart + node.name + tagCloseEnd
}

private fun stringifyAttributes(node: Element): String {
    val attrs = StringBuilder()
    for ((name, value) in node.attributes) {
        attrs.append(' ').append(name)
        if (value != null) {
            val encoded = REG_VAL_ENTITIES.replace(value) { encodeEntity(it.value) }
            attrs.append("=\"").append(encoded).append('"')
        }
    }
    return attrs.toString()
}

private fun stringifyText(node: Text, config: StringifyConfig, state: State): String =
    createIndent(config, state) + config.textStart +
        REG_ENTITIES.replace(node.value) { encodeEntity(it.value) } +
        (if (state.textContext != null) "" else config.textEnd)
