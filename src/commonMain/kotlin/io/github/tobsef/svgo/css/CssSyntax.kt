package io.github.tobsef.svgo.css

/**
 * A dependency-free CSS tokenizer and parser following CSS Syntax Level 3.
 *
 * The node model mirrors the one SVGO's Python port consumes from `tinycss2` (component values,
 * qualified rules, at-rules and declarations) so the plugin ports stay a literal translation.
 */
public sealed class CssNode {
    public abstract val type: String
    public abstract fun serializeTo(sb: StringBuilder)

    public fun serialize(): String = StringBuilder().also { serializeTo(it) }.toString()
}

public class WhitespaceToken(public val value: String) : CssNode() {
    override val type: String get() = "whitespace"
    override fun serializeTo(sb: StringBuilder) {
        sb.append(value)
    }
}

public class CommentToken(public val value: String) : CssNode() {
    override val type: String get() = "comment"
    override fun serializeTo(sb: StringBuilder) {
        sb.append("/*").append(value).append("*/")
    }
}

public class IdentToken(public val value: String) : CssNode() {
    override val type: String get() = "ident"
    public val lowerValue: String = value.lowercase()
    override fun serializeTo(sb: StringBuilder) {
        sb.append(serializeIdentifier(value))
    }
}

public class AtKeywordToken(public val value: String) : CssNode() {
    override val type: String get() = "at-keyword"
    public val lowerValue: String = value.lowercase()
    override fun serializeTo(sb: StringBuilder) {
        sb.append('@').append(serializeIdentifier(value))
    }
}

public class HashToken(public val value: String, public val isIdentifier: Boolean) : CssNode() {
    override val type: String get() = "hash"
    override fun serializeTo(sb: StringBuilder) {
        sb.append('#').append(if (isIdentifier) serializeIdentifier(value) else serializeName(value))
    }
}

public class StringToken(public val value: String) : CssNode() {
    override val type: String get() = "string"
    override fun serializeTo(sb: StringBuilder) {
        sb.append(serializeString(value))
    }
}

public class UrlToken(public val value: String) : CssNode() {
    override val type: String get() = "url"
    override fun serializeTo(sb: StringBuilder) {
        sb.append("url(").append(serializeUrl(value)).append(')')
    }
}

public class NumberToken(
    public val value: Double,
    public val representation: String,
    public val isInteger: Boolean,
) : CssNode() {
    override val type: String get() = "number"
    override fun serializeTo(sb: StringBuilder) {
        sb.append(representation)
    }
}

public class PercentageToken(
    public val value: Double,
    public val representation: String,
    public val isInteger: Boolean,
) : CssNode() {
    override val type: String get() = "percentage"
    override fun serializeTo(sb: StringBuilder) {
        sb.append(representation).append('%')
    }
}

public class DimensionToken(
    public val value: Double,
    public val representation: String,
    public val isInteger: Boolean,
    public val unit: String,
) : CssNode() {
    override val type: String get() = "dimension"
    override fun serializeTo(sb: StringBuilder) {
        sb.append(representation).append(serializeIdentifier(unit))
    }
}

/** A delimiter, colon, semicolon, comma or any other single-character token. */
public class LiteralToken(public val value: String) : CssNode() {
    override val type: String get() = "literal"
    override fun serializeTo(sb: StringBuilder) {
        sb.append(value)
    }
}

public class FunctionBlock(
    public val name: String,
    public val arguments: MutableList<CssNode>,
) : CssNode() {
    override val type: String get() = "function"
    public val lowerName: String = name.lowercase()
    override fun serializeTo(sb: StringBuilder) {
        sb.append(serializeIdentifier(name)).append('(')
        for (node in arguments) node.serializeTo(sb)
        sb.append(')')
    }
}

public class ParenthesesBlock(public val content: MutableList<CssNode>) : CssNode() {
    override val type: String get() = "() block"
    override fun serializeTo(sb: StringBuilder) {
        sb.append('(')
        for (node in content) node.serializeTo(sb)
        sb.append(')')
    }
}

public class SquareBracketsBlock(public val content: MutableList<CssNode>) : CssNode() {
    override val type: String get() = "[] block"
    override fun serializeTo(sb: StringBuilder) {
        sb.append('[')
        for (node in content) node.serializeTo(sb)
        sb.append(']')
    }
}

public class CurlyBracketsBlock(public val content: MutableList<CssNode>) : CssNode() {
    override val type: String get() = "{} block"
    override fun serializeTo(sb: StringBuilder) {
        sb.append('{')
        for (node in content) node.serializeTo(sb)
        sb.append('}')
    }
}

public class QualifiedRule(
    public val prelude: MutableList<CssNode>,
    public val content: MutableList<CssNode>?,
) : CssNode() {
    override val type: String get() = "qualified-rule"
    override fun serializeTo(sb: StringBuilder) {
        for (node in prelude) node.serializeTo(sb)
        sb.append('{')
        content?.forEach { it.serializeTo(sb) }
        sb.append('}')
    }
}

public class AtRule(
    public val atKeyword: String,
    public val prelude: MutableList<CssNode>,
    public val content: MutableList<CssNode>?,
) : CssNode() {
    override val type: String get() = "at-rule"
    public val lowerAtKeyword: String = atKeyword.lowercase()
    override fun serializeTo(sb: StringBuilder) {
        sb.append('@').append(serializeIdentifier(atKeyword))
        for (node in prelude) node.serializeTo(sb)
        if (content == null) {
            sb.append(';')
        } else {
            sb.append('{')
            content.forEach { it.serializeTo(sb) }
            sb.append('}')
        }
    }
}

public class DeclarationNode(
    public val name: String,
    public val value: MutableList<CssNode>,
    public val important: Boolean,
) : CssNode() {
    override val type: String get() = "declaration"
    public val lowerName: String = name.lowercase()
    override fun serializeTo(sb: StringBuilder) {
        sb.append(serializeIdentifier(name)).append(':')
        for (node in value) node.serializeTo(sb)
        if (important) sb.append("!important")
    }
}

/** Serialize a list of component values back to CSS text (equivalent of `tinycss2.serialize`). */
public fun serialize(nodes: List<CssNode>): String {
    val sb = StringBuilder()
    for (node in nodes) node.serializeTo(sb)
    return sb.toString()
}

// ---------------------------------------------------------------------------
// serialization helpers
// ---------------------------------------------------------------------------

internal fun isNameStart(ch: Char): Boolean =
    ch in 'a'..'z' || ch in 'A'..'Z' || ch == '_' || ch.code > 0x7F

internal fun isNameChar(ch: Char): Boolean = isNameStart(ch) || ch in '0'..'9' || ch == '-'

private fun escapeChar(ch: Char, sb: StringBuilder) {
    if (ch.code in 0x01..0x1F || ch.code == 0x7F || ch in '0'..'9') {
        sb.append('\\').append(ch.code.toString(16)).append(' ')
    } else {
        sb.append('\\').append(ch)
    }
}

internal fun serializeName(value: String): String {
    val sb = StringBuilder()
    for (ch in value) {
        if (isNameChar(ch)) sb.append(ch) else escapeChar(ch, sb)
    }
    return sb.toString()
}

internal fun serializeIdentifier(value: String): String {
    if (value == "-") return "\\-"
    val sb = StringBuilder()
    var index = 0
    if (value.startsWith("--")) {
        sb.append("--")
        index = 2
    } else if (value.startsWith("-") && value.length > 1) {
        sb.append('-')
        index = 1
    }
    if (index < value.length) {
        val first = value[index]
        if (first in '0'..'9') {
            sb.append('\\').append(first.code.toString(16)).append(' ')
        } else if (isNameStart(first) || first == '-') {
            sb.append(first)
        } else {
            escapeChar(first, sb)
        }
        index++
    }
    while (index < value.length) {
        val ch = value[index]
        if (isNameChar(ch)) sb.append(ch) else escapeChar(ch, sb)
        index++
    }
    return sb.toString()
}

internal fun serializeString(value: String): String {
    val sb = StringBuilder("\"")
    for (ch in value) {
        when {
            ch == '"' || ch == '\\' -> sb.append('\\').append(ch)
            ch == '\n' -> sb.append("\\a ")
            else -> sb.append(ch)
        }
    }
    return sb.append('"').toString()
}

private val UNQUOTED_URL = Regex("""^[^\s"'()\\ -]*$""")

internal fun serializeUrl(value: String): String =
    if (UNQUOTED_URL.matches(value)) value else serializeString(value)

// ---------------------------------------------------------------------------
// tokenizer
// ---------------------------------------------------------------------------

private class Tokenizer(private val css: String) {
    var pos: Int = 0

    fun atEnd(): Boolean = pos >= css.length

    fun peek(offset: Int = 0): Char = if (pos + offset < css.length) css[pos + offset] else ' '

    fun tokenize(stopAt: Char?): MutableList<CssNode> {
        val nodes = ArrayList<CssNode>()
        while (pos < css.length) {
            val ch = css[pos]
            if (stopAt != null && ch == stopAt) {
                pos++
                return nodes
            }
            if (stopAt == null && (ch == ')' || ch == ']' || ch == '}')) {
                // unbalanced closer at the top level: consume it as a literal
                pos++
                nodes.add(LiteralToken(ch.toString()))
                continue
            }
            nodes.add(nextToken())
        }
        return nodes
    }

    private fun nextToken(): CssNode {
        val ch = css[pos]
        return when {
            isWhitespaceChar(ch) -> {
                val start = pos
                while (pos < css.length && isWhitespaceChar(css[pos])) pos++
                WhitespaceToken(css.substring(start, pos))
            }

            ch == '/' && peek(1) == '*' -> {
                pos += 2
                val end = css.indexOf("*/", pos)
                val value: String
                if (end == -1) {
                    value = css.substring(pos)
                    pos = css.length
                } else {
                    value = css.substring(pos, end)
                    pos = end + 2
                }
                CommentToken(value)
            }

            ch == '"' || ch == '\'' -> {
                pos++
                StringToken(consumeString(ch))
            }

            ch == '#' -> {
                pos++
                if (pos < css.length && (isNameChar(css[pos]) || isValidEscape(pos))) {
                    val isIdentifier = wouldStartIdentifier(pos)
                    HashToken(consumeName(), isIdentifier)
                } else {
                    LiteralToken("#")
                }
            }

            ch == '@' -> {
                if (wouldStartIdentifier(pos + 1)) {
                    pos++
                    AtKeywordToken(consumeName())
                } else {
                    pos++
                    LiteralToken("@")
                }
            }

            ch == '+' || ch == '.' || ch == '-' -> {
                if (startsNumber(pos)) consumeNumeric()
                else if (ch == '-' && wouldStartIdentifier(pos)) consumeIdentLike()
                else {
                    pos++
                    LiteralToken(ch.toString())
                }
            }

            ch in '0'..'9' -> consumeNumeric()

            ch == '(' -> {
                pos++
                ParenthesesBlock(tokenize(')'))
            }

            ch == '[' -> {
                pos++
                SquareBracketsBlock(tokenize(']'))
            }

            ch == '{' -> {
                pos++
                CurlyBracketsBlock(tokenize('}'))
            }

            ch == '\\' && isValidEscape(pos) -> consumeIdentLike()

            isNameStart(ch) -> consumeIdentLike()

            else -> {
                pos++
                LiteralToken(ch.toString())
            }
        }
    }

    private fun isWhitespaceChar(ch: Char): Boolean =
        ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == ''

    private fun isValidEscape(index: Int): Boolean =
        index < css.length && css[index] == '\\' && index + 1 < css.length && css[index + 1] != '\n'

    private fun wouldStartIdentifier(index: Int): Boolean {
        if (index >= css.length) return false
        val ch = css[index]
        return when {
            isNameStart(ch) -> true
            ch == '-' -> index + 1 < css.length &&
                (isNameStart(css[index + 1]) || css[index + 1] == '-' || isValidEscape(index + 1))
            ch == '\\' -> isValidEscape(index)
            else -> false
        }
    }

    private fun startsNumber(index: Int): Boolean {
        if (index >= css.length) return false
        val ch = css[index]
        return when {
            ch == '+' || ch == '-' -> index + 1 < css.length && (
                css[index + 1] in '0'..'9' ||
                    (css[index + 1] == '.' && index + 2 < css.length && css[index + 2] in '0'..'9')
                )
            ch == '.' -> index + 1 < css.length && css[index + 1] in '0'..'9'
            else -> ch in '0'..'9'
        }
    }

    private fun consumeEscape(): Char {
        // `pos` points at the backslash
        pos++
        if (pos >= css.length) return '�'
        val ch = css[pos]
        val hex = StringBuilder()
        if (hexValue(ch) >= 0) {
            while (pos < css.length && hex.length < 6 && hexValue(css[pos]) >= 0) {
                hex.append(css[pos])
                pos++
            }
            if (pos < css.length && isWhitespaceChar(css[pos])) pos++
            val code = hex.toString().toInt(16)
            return if (code in 1..0xFFFF) code.toChar() else '�'
        }
        pos++
        return ch
    }

    private fun hexValue(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> -1
    }

    private fun consumeName(): String {
        val sb = StringBuilder()
        while (pos < css.length) {
            val ch = css[pos]
            when {
                isNameChar(ch) -> {
                    sb.append(ch); pos++
                }
                isValidEscape(pos) -> sb.append(consumeEscape())
                else -> return sb.toString()
            }
        }
        return sb.toString()
    }

    private fun consumeString(quote: Char): String {
        val sb = StringBuilder()
        while (pos < css.length) {
            val ch = css[pos]
            when {
                ch == quote -> {
                    pos++; return sb.toString()
                }
                ch == '\n' -> return sb.toString()
                ch == '\\' -> {
                    if (pos + 1 < css.length && css[pos + 1] == '\n') pos += 2 else sb.append(consumeEscape())
                }
                else -> {
                    sb.append(ch); pos++
                }
            }
        }
        return sb.toString()
    }

    private fun consumeNumeric(): CssNode {
        val start = pos
        var isInteger = true
        if (pos < css.length && (css[pos] == '+' || css[pos] == '-')) pos++
        while (pos < css.length && css[pos] in '0'..'9') pos++
        if (pos + 1 < css.length && css[pos] == '.' && css[pos + 1] in '0'..'9') {
            isInteger = false
            pos++
            while (pos < css.length && css[pos] in '0'..'9') pos++
        }
        if (pos < css.length && (css[pos] == 'e' || css[pos] == 'E')) {
            val save = pos
            var probe = pos + 1
            if (probe < css.length && (css[probe] == '+' || css[probe] == '-')) probe++
            if (probe < css.length && css[probe] in '0'..'9') {
                isInteger = false
                pos = probe
                while (pos < css.length && css[pos] in '0'..'9') pos++
            } else {
                pos = save
            }
        }
        val representation = css.substring(start, pos)
        val value = representation.toDoubleOrNull() ?: 0.0
        return when {
            wouldStartIdentifier(pos) -> DimensionToken(value, representation, isInteger, consumeName())
            pos < css.length && css[pos] == '%' -> {
                pos++
                PercentageToken(value, representation, isInteger)
            }
            else -> NumberToken(value, representation, isInteger)
        }
    }

    private fun consumeIdentLike(): CssNode {
        val name = consumeName()
        if (pos < css.length && css[pos] == '(') {
            pos++
            if (name.lowercase() == "url") {
                var probe = pos
                while (probe < css.length && isWhitespaceChar(css[probe])) probe++
                if (probe < css.length && (css[probe] == '"' || css[probe] == '\'')) {
                    return FunctionBlock(name, tokenize(')'))
                }
                return consumeUrl()
            }
            return FunctionBlock(name, tokenize(')'))
        }
        return IdentToken(name)
    }

    private fun consumeUrl(): CssNode {
        while (pos < css.length && isWhitespaceChar(css[pos])) pos++
        val sb = StringBuilder()
        while (pos < css.length) {
            val ch = css[pos]
            when {
                ch == ')' -> {
                    pos++; return UrlToken(sb.toString())
                }
                isWhitespaceChar(ch) -> {
                    while (pos < css.length && isWhitespaceChar(css[pos])) pos++
                    if (pos < css.length && css[pos] == ')') pos++
                    return UrlToken(sb.toString())
                }
                isValidEscape(pos) -> sb.append(consumeEscape())
                else -> {
                    sb.append(ch); pos++
                }
            }
        }
        return UrlToken(sb.toString())
    }
}

/** Tokenize [css] into a flat list of component values (blocks nested). */
public fun parseComponentValueList(css: String): MutableList<CssNode> = Tokenizer(css).tokenize(null)

// ---------------------------------------------------------------------------
// rule / declaration parsing
// ---------------------------------------------------------------------------

private fun isSkippable(node: CssNode, skipComments: Boolean, skipWhitespace: Boolean): Boolean =
    (skipComments && node is CommentToken) || (skipWhitespace && node is WhitespaceToken)

private fun trimEnds(nodes: List<CssNode>): MutableList<CssNode> {
    var start = 0
    var end = nodes.size
    while (start < end && nodes[start] is WhitespaceToken) start++
    while (end > start && nodes[end - 1] is WhitespaceToken) end--
    return nodes.subList(start, end).toMutableList()
}

private fun parseOneDeclaration(nodes: List<CssNode>): DeclarationNode? {
    var index = 0
    while (index < nodes.size && (nodes[index] is WhitespaceToken || nodes[index] is CommentToken)) index++
    if (index >= nodes.size) return null
    val nameToken = nodes[index] as? IdentToken ?: return null
    index++
    while (index < nodes.size && (nodes[index] is WhitespaceToken || nodes[index] is CommentToken)) index++
    val colon = nodes.getOrNull(index) as? LiteralToken ?: return null
    if (colon.value != ":") return null
    index++

    val value = nodes.subList(index, nodes.size).toMutableList()
    // strip a trailing `!important`
    var end = value.size
    while (end > 0 && (value[end - 1] is WhitespaceToken || value[end - 1] is CommentToken)) end--
    var important = false
    if (end > 0) {
        val last = value[end - 1]
        if (last is IdentToken && last.lowerValue == "important") {
            var bangIndex = end - 2
            while (bangIndex >= 0 && (value[bangIndex] is WhitespaceToken || value[bangIndex] is CommentToken)) {
                bangIndex--
            }
            val bang = value.getOrNull(bangIndex)
            if (bang is LiteralToken && bang.value == "!") {
                important = true
                while (value.size > bangIndex) value.removeAt(value.size - 1)
            }
        }
    }
    return DeclarationNode(nameToken.value, trimEnds(value), important)
}

/** Parse a declaration list (the content of a rule or of a `style` attribute). */
public fun parseDeclarationList(
    nodes: List<CssNode>,
    skipComments: Boolean = true,
    skipWhitespace: Boolean = true,
): List<CssNode> {
    val result = ArrayList<CssNode>()
    var current = ArrayList<CssNode>()
    var index = 0
    while (index < nodes.size) {
        val node = nodes[index]
        if (node is LiteralToken && node.value == ";") {
            parseOneDeclaration(current)?.let { result.add(it) }
            current = ArrayList()
            index++
            continue
        }
        if (node is AtKeywordToken) {
            parseOneDeclaration(current)?.let { result.add(it) }
            current = ArrayList()
            val atRule = consumeAtRule(nodes, index, node)
            result.add(atRule.first)
            index = atRule.second
            continue
        }
        current.add(node)
        index++
    }
    parseOneDeclaration(current)?.let { result.add(it) }
    return result.filter { !isSkippable(it, skipComments, skipWhitespace) }
}

/** Convenience overload parsing a declaration list from raw CSS text. */
public fun parseDeclarationList(
    css: String,
    skipComments: Boolean = true,
    skipWhitespace: Boolean = true,
): List<CssNode> = parseDeclarationList(parseComponentValueList(css), skipComments, skipWhitespace)

private fun consumeAtRule(
    nodes: List<CssNode>,
    startIndex: Int,
    keyword: AtKeywordToken,
): Pair<AtRule, Int> {
    var index = startIndex + 1
    val prelude = ArrayList<CssNode>()
    var content: MutableList<CssNode>? = null
    while (index < nodes.size) {
        val node = nodes[index]
        if (node is LiteralToken && node.value == ";") {
            index++
            break
        }
        if (node is CurlyBracketsBlock) {
            content = node.content
            index++
            break
        }
        prelude.add(node)
        index++
    }
    // NOTE: the prelude keeps its leading/trailing whitespace verbatim (like tinycss2), so that
    // re-serializing an at-rule does not glue the keyword to its prelude ("@mediascreen").
    return AtRule(keyword.value, prelude, content) to index
}

/** Parse a stylesheet into qualified rules, at-rules and (optionally) comments. */
public fun parseStylesheet(
    css: String,
    skipComments: Boolean = true,
    skipWhitespace: Boolean = true,
): List<CssNode> = parseRuleList(parseComponentValueList(css), skipComments, skipWhitespace)

/** Parse a list of component values into rules. */
public fun parseRuleList(
    nodes: List<CssNode>,
    skipComments: Boolean = true,
    skipWhitespace: Boolean = true,
): List<CssNode> {
    val result = ArrayList<CssNode>()
    var index = 0
    while (index < nodes.size) {
        val node = nodes[index]
        when {
            node is WhitespaceToken -> {
                if (!skipWhitespace) result.add(node)
                index++
            }

            node is CommentToken -> {
                if (!skipComments) result.add(node)
                index++
            }

            node is AtKeywordToken -> {
                val atRule = consumeAtRule(nodes, index, node)
                result.add(atRule.first)
                index = atRule.second
            }

            node is LiteralToken && (node.value == "<!--" || node.value == "-->") -> index++

            else -> {
                val prelude = ArrayList<CssNode>()
                var content: MutableList<CssNode>? = null
                while (index < nodes.size) {
                    val current = nodes[index]
                    if (current is CurlyBracketsBlock) {
                        content = current.content
                        index++
                        break
                    }
                    prelude.add(current)
                    index++
                }
                if (content != null) result.add(QualifiedRule(prelude, content))
            }
        }
    }
    return result
}
