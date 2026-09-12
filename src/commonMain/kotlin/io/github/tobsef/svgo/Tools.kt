package io.github.tobsef.svgo

/** Assorted helpers. Port of `lib/svgo/tools.js`. */

// JS source: /\burl\((["'])?#(.+?)\1\)/g -- a backreference to an optional group that did not
// participate cannot match, so the three quoting variants are spelled out explicitly.
internal val REG_REFERENCES_URL = Regex("""\burl\((?:"#(.+?)"|'#(.+?)'|#(.+?))\)""")
private val REG_REFERENCES_HREF = Regex("""^#(.+?)$""")
private val REG_REFERENCES_BEGIN = Regex("""(\w+)\.[a-zA-Z]""")
private val REG_CSS_VAR = Regex("""var\s*\(\s*--""")

internal fun urlRefId(match: MatchResult): String =
    match.groupValues[1].ifEmpty { match.groupValues[2].ifEmpty { match.groupValues[3] } }

// ---------------------------------------------------------------------------
// data URIs
// ---------------------------------------------------------------------------

private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

internal fun base64Encode(bytes: ByteArray): String {
    val sb = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0
    while (i + 2 < bytes.size) {
        val n = ((bytes[i].toInt() and 0xFF) shl 16) or
            ((bytes[i + 1].toInt() and 0xFF) shl 8) or
            (bytes[i + 2].toInt() and 0xFF)
        sb.append(BASE64_ALPHABET[(n ushr 18) and 63])
        sb.append(BASE64_ALPHABET[(n ushr 12) and 63])
        sb.append(BASE64_ALPHABET[(n ushr 6) and 63])
        sb.append(BASE64_ALPHABET[n and 63])
        i += 3
    }
    when (bytes.size - i) {
        1 -> {
            val n = (bytes[i].toInt() and 0xFF) shl 16
            sb.append(BASE64_ALPHABET[(n ushr 18) and 63])
            sb.append(BASE64_ALPHABET[(n ushr 12) and 63])
            sb.append("==")
        }
        2 -> {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
            sb.append(BASE64_ALPHABET[(n ushr 18) and 63])
            sb.append(BASE64_ALPHABET[(n ushr 12) and 63])
            sb.append(BASE64_ALPHABET[(n ushr 6) and 63])
            sb.append('=')
        }
    }
    return sb.toString()
}

internal fun base64Decode(text: String): ByteArray {
    val out = ArrayList<Byte>(text.length * 3 / 4)
    var buffer = 0
    var bits = 0
    for (ch in text) {
        if (ch == '=' || ch == '\n' || ch == '\r' || ch == ' ') continue
        val index = BASE64_ALPHABET.indexOf(ch)
        if (index < 0) continue
        buffer = (buffer shl 6) or index
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.add(((buffer ushr bits) and 0xFF).toByte())
        }
    }
    return out.toByteArray()
}

private fun percentEncode(value: String, safe: String): String {
    val sb = StringBuilder()
    for (byte in value.encodeToByteArray()) {
        val code = byte.toInt() and 0xFF
        val ch = code.toChar()
        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "_.-~" || safe.indexOf(ch) >= 0) {
            sb.append(ch)
        } else {
            sb.append('%')
            sb.append(HEX_UPPER[(code ushr 4) and 0xF])
            sb.append(HEX_UPPER[code and 0xF])
        }
    }
    return sb.toString()
}

private const val HEX_UPPER = "0123456789ABCDEF"

private fun percentDecode(value: String): String {
    if ('%' !in value) return value
    val out = ArrayList<Byte>(value.length)
    var i = 0
    while (i < value.length) {
        val ch = value[i]
        if (ch == '%' && i + 2 < value.length) {
            val hi = hexDigit(value[i + 1])
            val lo = hexDigit(value[i + 2])
            if (hi >= 0 && lo >= 0) {
                out.add(((hi shl 4) or lo).toByte())
                i += 3
                continue
            }
        }
        for (byte in ch.toString().encodeToByteArray()) out.add(byte)
        i++
    }
    return out.toByteArray().decodeToString()
}

private fun hexDigit(ch: Char): Int = when (ch) {
    in '0'..'9' -> ch - '0'
    in 'a'..'f' -> ch - 'a' + 10
    in 'A'..'F' -> ch - 'A' + 10
    else -> -1
}

/** JavaScript `encodeURIComponent`. */
internal fun encodeUriComponent(value: String): String = percentEncode(value, "!*'()")

/** JavaScript `encodeURI`. */
internal fun encodeUri(value: String): String = percentEncode(value, "!*'();/?:@&=+$,#")

/** JavaScript `decodeURIComponent` (lenient: invalid escapes are left alone). */
internal fun decodeUriComponent(value: String): String = percentDecode(value)

public fun encodeSvgDatauri(text: String, type: String? = null): String {
    val prefix = "data:image/svg+xml"
    return when (type) {
        null, "base64" -> prefix + ";base64," + base64Encode(text.encodeToByteArray())
        "enc" -> prefix + "," + encodeUriComponent(text)
        "unenc" -> "$prefix,$text"
        else -> text
    }
}

private val DATA_URI = Regex("""data:image/svg\+xml(;charset=[^;,]*)?(;base64)?,([\s\S]*)""")

public fun decodeSvgDatauri(text: String): String {
    val match = DATA_URI.matchAt(text, 0) ?: return text
    val data = match.groupValues[3]
    if (match.groupValues[2].isNotEmpty()) return base64Decode(data).decodeToString()
    if (data.startsWith("%")) return decodeUriComponent(data)
    if (data.startsWith("<")) return data
    return text
}

// ---------------------------------------------------------------------------
// path/transform number output
// ---------------------------------------------------------------------------

/** Formatting options shared by `cleanupOutData` callers. */
public class OutDataParams(
    public val leadingZero: Boolean = true,
    public val negativeExtraSpace: Boolean = false,
    public val noSpaceAfterFlags: Boolean = false,
)

/** Convert a row of numbers to an optimized string view. */
public fun cleanupOutData(data: List<Double>, params: OutDataParams, command: String? = null): String {
    val result = StringBuilder()
    var prev = 0.0
    for (i in data.indices) {
        val item = data[i]
        var delimiter = if (i == 0) "" else " "

        if (params.noSpaceAfterFlags && (command == "A" || command == "a")) {
            val pos = i % 7
            if (pos == 4 || pos == 5) delimiter = ""
        }

        val itemStr = if (params.leadingZero) removeLeadingZero(item) else jsNumberToString(item)

        if (params.negativeExtraSpace &&
            delimiter != "" &&
            (item < 0 || (itemStr.startsWith(".") && prev % 1.0 != 0.0))
        ) {
            delimiter = ""
        }
        prev = item
        result.append(delimiter).append(itemStr)
    }
    return result.toString()
}

// ---------------------------------------------------------------------------
// scripts / references
// ---------------------------------------------------------------------------

private val HAS_SCRIPTS_EVENT_ATTRS: List<String> = buildList {
    addAll(attrsGroups["animationEvent"].orEmpty())
    addAll(attrsGroups["documentEvent"].orEmpty())
    addAll(attrsGroups["documentElementEvent"].orEmpty())
    addAll(attrsGroups["globalEvent"].orEmpty())
    addAll(attrsGroups["graphicalEvent"].orEmpty())
}

private val EXECUTABLE_DATA_MEDIA_TYPES = setOf(
    "application/xhtml+xml",
    "image/svg+xml",
    "text/html",
)

private val CONTROL_CHARS = Regex("[\t\n\r]")

public fun isExecutableUrl(value: String): Boolean {
    val normalized = CONTROL_CHARS.replace(value, "").trimStart().lowercase()
    if (normalized.startsWith("javascript:") || normalized.startsWith("vbscript:")) return true
    if (!normalized.startsWith("data:")) return false
    val rest = normalized.substring(5)
    val separator = rest.indexOfFirst { it == ';' || it == ',' }
    if (separator < 0) return false
    return rest.substring(0, separator).trim() in EXECUTABLE_DATA_MEDIA_TYPES
}

public fun hasScripts(node: Element): Boolean {
    if (node.name == "script" && node.children.isNotEmpty()) return true
    if (node.name == "a") {
        for ((key, value) in node.attributes) {
            if ((key == "href" || key.endsWith(":href")) && value != null && isExecutableUrl(value)) return true
        }
    }
    return HAS_SCRIPTS_EVENT_ATTRS.any { node.attributes[it] != null }
}

public fun includesUrlReference(body: String): Boolean = REG_REFERENCES_URL.containsMatchIn(body)

public fun includesCssVarReference(body: String): Boolean = REG_CSS_VAR.containsMatchIn(body)

public fun findReferences(attribute: String, value: String): List<String> {
    val results = ArrayList<String>()
    if (attribute in referencesProps) {
        for (match in REG_REFERENCES_URL.findAll(value)) results.add(urlRefId(match))
    }
    if (attribute == "href" || attribute.endsWith(":href")) {
        REG_REFERENCES_HREF.matchEntire(value)?.let { results.add(it.groupValues[1]) }
    }
    if (attribute == "begin") {
        REG_REFERENCES_BEGIN.find(value)?.let { results.add(it.groupValues[1]) }
    }
    return results.map { decodeUriComponent(it) }
}
