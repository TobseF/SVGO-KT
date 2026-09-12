package io.github.tobsef.svgo.css

/**
 * A dependency-free CSS selector parser.
 *
 * The parse tree mirrors `cssselect`'s node classes (which SVGO's Python port consumes), so the
 * matching, specificity and selector-introspection logic ported from it stays a literal
 * translation.
 */
public class SelectorSyntaxException(message: String) : Exception(message)

public sealed class SelectorNode {
    /** Specificity triple `(ids, classes/attributes, types/pseudo-elements)`. */
    public abstract fun specificity(): IntArray
}

/** Type selector; [element] is `null` for `*` and for an implicit universal selector. */
public class TypeSelector(public val namespace: String?, public val element: String?) : SelectorNode() {
    override fun specificity(): IntArray = if (element == null) intArrayOf(0, 0, 0) else intArrayOf(0, 0, 1)
}

public class HashSelector(public val selector: SelectorNode, public val id: String) : SelectorNode() {
    override fun specificity(): IntArray = selector.specificity().plusSpecificity(1, 0, 0)
}

public class ClassSelector(public val selector: SelectorNode, public val className: String) : SelectorNode() {
    override fun specificity(): IntArray = selector.specificity().plusSpecificity(0, 1, 0)
}

public class AttribSelector(
    public val selector: SelectorNode,
    public val namespace: String?,
    public val attrib: String,
    public val operator: String,
    public val value: String?,
) : SelectorNode() {
    override fun specificity(): IntArray = selector.specificity().plusSpecificity(0, 1, 0)
}

/** A plain pseudo-class such as `:hover` or `:first-child`. */
public class PseudoSelector(public val selector: SelectorNode, public val ident: String) : SelectorNode() {
    override fun specificity(): IntArray = selector.specificity().plusSpecificity(0, 0, 1)
}

/** A functional pseudo-class such as `:nth-child(2n+1)` or `:is(a, b)`. */
public class FunctionSelector(
    public val selector: SelectorNode,
    public val name: String,
    public val arguments: String,
) : SelectorNode() {
    override fun specificity(): IntArray = selector.specificity().plusSpecificity(0, 1, 0)
}

/** `:not(...)` with a single simple selector argument. */
public class NegationSelector(
    public val selector: SelectorNode,
    public val subselector: SelectorNode,
) : SelectorNode() {
    override fun specificity(): IntArray {
        val a = selector.specificity()
        val b = subselector.specificity()
        return intArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
    }
}

public class CombinedSelector(
    public val selector: SelectorNode,
    public val combinator: String,
    public val subselector: SelectorNode,
) : SelectorNode() {
    override fun specificity(): IntArray {
        val a = selector.specificity()
        val b = subselector.specificity()
        return intArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
    }
}

private fun IntArray.plusSpecificity(a: Int, b: Int, c: Int): IntArray =
    intArrayOf(this[0] + a, this[1] + b, this[2] + c)

/** One selector of a selector list, plus its optional pseudo-element. */
public class ParsedSelector(
    public val parsedTree: SelectorNode,
    public val pseudoElement: String? = null,
) {
    public fun specificity(): IntArray {
        val spec = parsedTree.specificity()
        if (pseudoElement != null) spec[2] += 1
        return spec
    }
}

// ---------------------------------------------------------------------------
// parser
// ---------------------------------------------------------------------------

private class SelectorParser(private val text: String) {
    var pos = 0

    fun parseGroup(): List<ParsedSelector> {
        val result = ArrayList<ParsedSelector>()
        while (true) {
            result.add(parseSelector())
            skipWhitespace()
            if (pos < text.length && text[pos] == ',') {
                pos++
                continue
            }
            break
        }
        if (pos < text.length) throw SelectorSyntaxException("Unexpected '${text[pos]}' at $pos")
        return result
    }

    private fun parseSelector(): ParsedSelector {
        skipWhitespace()
        var result = parseSimpleSelector()
        var pseudoElement = result.second

        while (true) {
            val savedPos = pos
            var hadWhitespace = false
            while (pos < text.length && text[pos].isCssWhitespace()) {
                pos++
                hadWhitespace = true
            }
            if (pos >= text.length) {
                pos = if (hadWhitespace) pos else savedPos
                break
            }
            val ch = text[pos]
            val combinator = when {
                ch == '>' || ch == '+' || ch == '~' -> {
                    pos++
                    ch.toString()
                }
                ch == ',' || ch == ')' -> {
                    pos = savedPos
                    break
                }
                hadWhitespace -> " "
                else -> {
                    pos = savedPos
                    break
                }
            }
            if (pseudoElement != null) {
                throw SelectorSyntaxException("A pseudo-element must be at the end of a selector")
            }
            skipWhitespace()
            val next = parseSimpleSelector()
            pseudoElement = next.second
            result = CombinedSelector(result.first, combinator, next.first) to pseudoElement
        }
        return ParsedSelector(result.first, pseudoElement)
    }

    /** Parse one compound selector; returns the tree and an optional pseudo-element name. */
    private fun parseSimpleSelector(): Pair<SelectorNode, String?> {
        var namespace: String? = null
        var element: String? = null
        var matched = false

        if (pos < text.length) {
            val ch = text[pos]
            if (ch == '*' || isIdentStart(ch)) {
                val first = if (ch == '*') {
                    pos++
                    null
                } else {
                    readIdentifier()
                }
                if (pos < text.length && text[pos] == '|' && (pos + 1 >= text.length || text[pos + 1] != '=')) {
                    pos++
                    namespace = first
                    element = if (pos < text.length && text[pos] == '*') {
                        pos++
                        null
                    } else {
                        readIdentifier()
                    }
                } else {
                    element = first
                }
                matched = true
            }
        }

        var result: SelectorNode = TypeSelector(namespace, element)
        var pseudoElement: String? = null

        while (pos < text.length) {
            if (pseudoElement != null) break
            when (val ch = text[pos]) {
                '#' -> {
                    pos++
                    result = HashSelector(result, readIdentifier())
                    matched = true
                }

                '.' -> {
                    pos++
                    result = ClassSelector(result, readIdentifier())
                    matched = true
                }

                '[' -> {
                    pos++
                    result = parseAttrib(result)
                    matched = true
                }

                ':' -> {
                    pos++
                    if (pos < text.length && text[pos] == ':') {
                        pos++
                        pseudoElement = readIdentifier()
                        matched = true
                        continue
                    }
                    val identifier = readIdentifier()
                    if (pos < text.length && text[pos] == '(') {
                        pos++
                        val arguments = readBalanced()
                        result = if (identifier.lowercase() == "not") {
                            val inner = SelectorParser(arguments).parseGroup()
                            if (inner.size != 1) {
                                FunctionSelector(result, identifier, arguments)
                            } else {
                                NegationSelector(result, inner[0].parsedTree)
                            }
                        } else {
                            FunctionSelector(result, identifier, arguments)
                        }
                    } else {
                        if (identifier.lowercase() in LEGACY_PSEUDO_ELEMENTS) {
                            pseudoElement = identifier
                        } else {
                            result = PseudoSelector(result, identifier)
                        }
                    }
                    matched = true
                }

                else -> {
                    if (ch.isCssWhitespace() || ch == ',' || ch == '>' || ch == '+' || ch == '~' || ch == ')') break
                    throw SelectorSyntaxException("Unexpected '$ch' at $pos")
                }
            }
        }

        if (!matched) throw SelectorSyntaxException("Expected selector at $pos")
        return result to pseudoElement
    }

    private fun parseAttrib(selector: SelectorNode): SelectorNode {
        skipWhitespace()
        var namespace: String? = null
        var attrib = if (pos < text.length && text[pos] == '*') {
            pos++
            "*"
        } else {
            readIdentifier()
        }
        if (pos < text.length && text[pos] == '|' && (pos + 1 >= text.length || text[pos + 1] != '=')) {
            pos++
            namespace = attrib
            attrib = readIdentifier()
        }
        skipWhitespace()
        if (pos < text.length && text[pos] == ']') {
            pos++
            return AttribSelector(selector, namespace, attrib, "exists", null)
        }
        val operator = when {
            text.startsWith("=", pos) -> {
                pos += 1; "="
            }
            text.startsWith("~=", pos) -> {
                pos += 2; "~="
            }
            text.startsWith("|=", pos) -> {
                pos += 2; "|="
            }
            text.startsWith("^=", pos) -> {
                pos += 2; "^="
            }
            text.startsWith("$=", pos) -> {
                pos += 2; "$="
            }
            text.startsWith("*=", pos) -> {
                pos += 2; "*="
            }
            text.startsWith("!=", pos) -> {
                pos += 2; "!="
            }
            else -> throw SelectorSyntaxException("Invalid attribute operator at $pos")
        }
        skipWhitespace()
        val value = if (pos < text.length && (text[pos] == '"' || text[pos] == '\'')) {
            readString(text[pos])
        } else {
            readIdentifierLoose()
        }
        skipWhitespace()
        // an optional case-sensitivity flag (`i` / `s`)
        if (pos < text.length && (text[pos] == 'i' || text[pos] == 'I' || text[pos] == 's' || text[pos] == 'S')) {
            val save = pos
            pos++
            skipWhitespace()
            if (pos >= text.length || text[pos] != ']') pos = save
        }
        if (pos >= text.length || text[pos] != ']') throw SelectorSyntaxException("Expected ']' at $pos")
        pos++
        return AttribSelector(selector, namespace, attrib, operator, value)
    }

    private fun readBalanced(): String {
        val start = pos
        var depth = 1
        while (pos < text.length) {
            when (text[pos]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        val value = text.substring(start, pos)
                        pos++
                        return value
                    }
                }
                '"', '\'' -> {
                    readString(text[pos])
                    continue
                }
            }
            pos++
        }
        throw SelectorSyntaxException("Unbalanced parentheses")
    }

    private fun readString(quote: Char): String {
        pos++
        val sb = StringBuilder()
        while (pos < text.length) {
            val ch = text[pos]
            if (ch == quote) {
                pos++
                return sb.toString()
            }
            if (ch == '\\' && pos + 1 < text.length) {
                pos++
                sb.append(text[pos])
                pos++
                continue
            }
            sb.append(ch)
            pos++
        }
        throw SelectorSyntaxException("Unterminated string")
    }

    private fun readIdentifier(): String {
        val value = readIdentifierLoose()
        if (value.isEmpty()) throw SelectorSyntaxException("Expected identifier at $pos")
        return value
    }

    private fun readIdentifierLoose(): String {
        val sb = StringBuilder()
        while (pos < text.length) {
            val ch = text[pos]
            when {
                isNameChar(ch) -> {
                    sb.append(ch); pos++
                }
                ch == '\\' && pos + 1 < text.length -> {
                    pos++
                    sb.append(text[pos])
                    pos++
                }
                else -> return sb.toString()
            }
        }
        return sb.toString()
    }

    private fun isIdentStart(ch: Char): Boolean = isNameStart(ch) || ch == '-' || ch == '\\'

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isCssWhitespace()) pos++
    }
}

private fun Char.isCssWhitespace(): Boolean =
    this == ' ' || this == '\t' || this == '\n' || this == '\r' || this == ''

private val LEGACY_PSEUDO_ELEMENTS = setOf("before", "after", "first-line", "first-letter")

/** Parse a selector list; throws [SelectorSyntaxException] on malformed input. */
public fun parseSelectorGroup(selector: String): List<ParsedSelector> {
    if (selector.isBlank()) throw SelectorSyntaxException("Empty selector")
    return SelectorParser(selector).parseGroup()
}

/** Parse a selector list, returning `null` instead of throwing. */
public fun parseSelectorGroupOrNull(selector: String): List<ParsedSelector>? = try {
    parseSelectorGroup(selector)
} catch (_: SelectorSyntaxException) {
    null
} catch (_: IndexOutOfBoundsException) {
    null
}
