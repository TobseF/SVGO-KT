package io.github.tobsef.svgo

import io.github.tobsef.svgo.css.AttribSelector
import io.github.tobsef.svgo.css.AtRule
import io.github.tobsef.svgo.css.ClassSelector
import io.github.tobsef.svgo.css.CombinedSelector
import io.github.tobsef.svgo.css.CommentToken
import io.github.tobsef.svgo.css.CssNode
import io.github.tobsef.svgo.css.DeclarationIndexer
import io.github.tobsef.svgo.css.DeclarationNode
import io.github.tobsef.svgo.css.DimensionToken
import io.github.tobsef.svgo.css.FunctionBlock
import io.github.tobsef.svgo.css.FunctionSelector
import io.github.tobsef.svgo.css.HashSelector
import io.github.tobsef.svgo.css.HashToken
import io.github.tobsef.svgo.css.IdentToken
import io.github.tobsef.svgo.css.LiteralToken
import io.github.tobsef.svgo.css.MinAtRule
import io.github.tobsef.svgo.css.MinBlock
import io.github.tobsef.svgo.css.MinComment
import io.github.tobsef.svgo.css.MinDecl
import io.github.tobsef.svgo.css.MinDeclNode
import io.github.tobsef.svgo.css.MinNode
import io.github.tobsef.svgo.css.MinRaw
import io.github.tobsef.svgo.css.MinRule
import io.github.tobsef.svgo.css.MinSelector
import io.github.tobsef.svgo.css.NegationSelector
import io.github.tobsef.svgo.css.NumberToken
import io.github.tobsef.svgo.css.ParenthesesBlock
import io.github.tobsef.svgo.css.ParsedSelector
import io.github.tobsef.svgo.css.PercentageToken
import io.github.tobsef.svgo.css.PseudoSelector
import io.github.tobsef.svgo.css.QualifiedRule
import io.github.tobsef.svgo.css.SelectorNode
import io.github.tobsef.svgo.css.SquareBracketsBlock
import io.github.tobsef.svgo.css.StringToken
import io.github.tobsef.svgo.css.TypeSelector
import io.github.tobsef.svgo.css.UrlToken
import io.github.tobsef.svgo.css.ValuePart
import io.github.tobsef.svgo.css.WhitespaceToken
import io.github.tobsef.svgo.css.parseComponentValueList
import io.github.tobsef.svgo.css.parseDeclarationList
import io.github.tobsef.svgo.css.parseRuleList
import io.github.tobsef.svgo.css.parseSelectorGroupOrNull
import io.github.tobsef.svgo.css.parseStylesheet
import io.github.tobsef.svgo.css.restructure
import io.github.tobsef.svgo.css.serialize

/**
 * The CSS minifier: a port of `csso`'s three stages.
 *
 * `clean` (dropping empty rules, comments and rules no selector can reach) and `replace` (value
 * minification) happen while the [io.github.tobsef.svgo.css.MinBlock] model is built here;
 * `restructure` is [restructure] in `css/Restructure.kt`. Generation reproduces css-tree's
 * *safe* token adjacency, so a separating space appears exactly where re-parsing would otherwise
 * read a different token stream.
 */

// ---------------------------------------------------------------------------
// token adjacency (css-tree `lib/generator/token-before.js`, `safe` mode)
// ---------------------------------------------------------------------------

private const val T_IDENT = "Ident"
private const val T_FUNCTION = "Function"
private const val T_URL = "Url"
private const val T_AT_KEYWORD = "AtKeyword"
private const val T_HASH = "Hash"
private const val T_PERCENTAGE = "Percentage"
private const val T_DIMENSION = "Dimension"
private const val T_NUMBER = "Number"
private const val T_STRING = "String"
private const val T_COLON = "Colon"
private const val T_LEFT_PAREN = "("
private const val T_RIGHT_PAREN = ")"
private const val T_OTHER = "Other"

/** A delimiter token's key; css-tree indexes those by their first character. */
private fun delimKey(value: String): String = when (value) {
    "," -> "Comma"
    ":" -> T_COLON
    ";" -> "Semicolon"
    "(" -> T_LEFT_PAREN
    ")" -> T_RIGHT_PAREN
    "[" -> "["
    "]" -> "]"
    "{" -> "{"
    "}" -> "}"
    else -> "d:" + value.first()
}

private val SAFE_PAIRS: Set<String> = buildSet {
    fun pairs(prev: String, vararg next: String) {
        for (n in next) add("$prev|$n")
    }
    // --- spec pairs
    pairs(T_IDENT, T_IDENT, T_FUNCTION, T_URL, "d:-", T_NUMBER, T_PERCENTAGE, T_DIMENSION, T_LEFT_PAREN)
    pairs(T_AT_KEYWORD, T_IDENT, T_FUNCTION, T_URL, "d:-", T_NUMBER, T_PERCENTAGE, T_DIMENSION)
    pairs(T_HASH, T_IDENT, T_FUNCTION, T_URL, "d:-", T_NUMBER, T_PERCENTAGE, T_DIMENSION)
    pairs(T_DIMENSION, T_IDENT, T_FUNCTION, T_URL, "d:-", T_NUMBER, T_PERCENTAGE, T_DIMENSION)
    pairs("d:#", T_IDENT, T_FUNCTION, T_URL, "d:-", T_NUMBER, T_PERCENTAGE, T_DIMENSION)
    pairs("d:-", T_IDENT, T_FUNCTION, T_URL, "d:-", T_NUMBER, T_PERCENTAGE, T_DIMENSION)
    pairs(T_NUMBER, T_IDENT, T_FUNCTION, T_URL, T_NUMBER, T_PERCENTAGE, T_DIMENSION, "d:%")
    pairs("d:@", T_IDENT, T_FUNCTION, T_URL, "d:-")
    pairs("d:.", T_NUMBER, T_PERCENTAGE, T_DIMENSION)
    pairs("d:+", T_NUMBER, T_PERCENTAGE, T_DIMENSION)
    pairs("d:/", "d:*")
    // --- additionally safe pairs
    pairs(T_IDENT, T_HASH)
    pairs(T_DIMENSION, T_HASH)
    pairs(T_HASH, T_HASH)
    pairs(T_AT_KEYWORD, T_LEFT_PAREN, T_STRING, T_COLON)
    pairs(T_PERCENTAGE, T_PERCENTAGE, T_DIMENSION, T_FUNCTION, "d:-")
    pairs(T_RIGHT_PAREN, T_IDENT, T_FUNCTION, T_PERCENTAGE, T_DIMENSION, T_HASH, "d:-")
}

/** Emits tokens, inserting the separating spaces css-tree's `safe` mode requires. */
private class TokenWriter {
    private val sb = StringBuilder()
    private var prev: String? = null

    fun token(type: String, value: String) {
        if (value.isEmpty()) return
        val first = value[0]
        val lookup = if ((first == '-' && type != T_IDENT && type != T_FUNCTION) || first == '+') {
            "d:$first"
        } else {
            type
        }
        if (prev != null && "$prev|$lookup" in SAFE_PAIRS) sb.append(' ')
        sb.append(value)
        prev = type
    }

    /** Emit pre-tokenized text verbatim, as css-tree does for `Raw` nodes. */
    fun raw(text: String) {
        if (text.isEmpty()) return
        sb.append(text)
        prev = T_OTHER
    }

    fun result(): String = sb.toString()
}

// ---------------------------------------------------------------------------
// string / url encoding (csso's css-tree fork in `lib/syntax.js`)
// ---------------------------------------------------------------------------

private fun isHexDigitChar(ch: Char): Boolean =
    ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'

private fun encodeCssString(value: String, apostrophe: Boolean): String {
    val quote = if (apostrophe) '\'' else '"'
    val sb = StringBuilder().append(quote)
    var wsBeforeHexIsNeeded = false
    for (ch in value) {
        val code = ch.code
        when {
            code == 0 -> sb.append('\uFFFD')
            code <= 0x1f || code == 0x7f -> {
                sb.append('\\').append(code.toString(16))
                wsBeforeHexIsNeeded = true
            }
            ch == quote || ch == '\\' -> {
                sb.append('\\').append(ch)
                wsBeforeHexIsNeeded = false
            }
            else -> {
                if (wsBeforeHexIsNeeded && (isHexDigitChar(ch) || ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r')) {
                    sb.append(' ')
                }
                sb.append(ch)
                wsBeforeHexIsNeeded = false
            }
        }
    }
    return sb.append(quote).toString()
}

/** csso picks whichever quoting comes out shorter. */
internal fun encodeStringShortest(value: String): String {
    val apostrophe = encodeCssString(value, apostrophe = true)
    val quote = encodeCssString(value, apostrophe = false)
    return if (apostrophe.length < quote.length) apostrophe else quote
}

private fun encodeUrlBody(value: String): String {
    val sb = StringBuilder()
    var wsBeforeHexIsNeeded = false
    for (ch in value) {
        val code = ch.code
        when {
            code == 0 -> sb.append('\uFFFD')
            code <= 0x1f || code == 0x7f -> {
                sb.append('\\').append(code.toString(16))
                wsBeforeHexIsNeeded = true
            }
            ch == ' ' || ch == '\\' || ch == '"' || ch == '\'' || ch == '(' || ch == ')' -> {
                sb.append('\\').append(ch)
                wsBeforeHexIsNeeded = false
            }
            else -> {
                if (wsBeforeHexIsNeeded && isHexDigitChar(ch)) sb.append(' ')
                sb.append(ch)
                wsBeforeHexIsNeeded = false
            }
        }
    }
    return sb.toString()
}

/** csso keeps the quoted form when escaping the bare form would be longer. */
internal fun encodeUrl(value: String): String {
    // csso's `replace/Url.js`: backslashes in a url are path separators
    val normalized = value.replace('\\', '/')
    val encoded = "url(" + encodeUrlBody(normalized) + ")"
    val quoted = encodeStringShortest(normalized)
    return if (encoded.length <= quoted.length + 5) encoded else "url($quoted)"
}

// ---------------------------------------------------------------------------
// value generation
// ---------------------------------------------------------------------------

private val HEX_DIGITS = Regex("""^[0-9a-fA-F]+$""")

private fun writeValueTokens(tokens: List<CssNode>, out: TokenWriter) {
    for (token in tokens) {
        when (token) {
            is WhitespaceToken, is CommentToken -> {}
            is NumberToken -> out.token(T_NUMBER, token.representation)
            is PercentageToken -> out.token(T_PERCENTAGE, token.representation + "%")
            is DimensionToken -> out.token(T_DIMENSION, token.representation + (token.unitRaw ?: token.unit))
            is HashToken -> out.token(T_HASH, token.serialize())
            is IdentToken -> out.token(T_IDENT, token.serialize())
            is StringToken -> out.token(T_STRING, encodeStringShortest(token.value))
            is UrlToken -> out.token(T_URL, encodeUrl(token.value))
            is FunctionBlock -> {
                val asUrl = urlFunctionValue(token)
                if (asUrl != null) {
                    out.token(T_URL, encodeUrl(asUrl))
                } else {
                    out.token(T_FUNCTION, token.name + "(")
                    writeValueTokens(token.arguments, out)
                    out.token(T_RIGHT_PAREN, ")")
                }
            }
            is ParenthesesBlock -> {
                out.token(T_LEFT_PAREN, "(")
                writeValueTokens(token.content, out)
                out.token(T_RIGHT_PAREN, ")")
            }
            is SquareBracketsBlock -> {
                out.token("[", "[")
                writeValueTokens(token.content, out)
                out.token("]", "]")
            }
            // an operator that carries its own whitespace, as css-tree's value scope produces it
            is LiteralToken ->
                if (token.value.length > 1) out.raw(token.value) else out.token(delimKey(token.value), token.value)
            else -> out.token(T_OTHER, token.serialize())
        }
    }
}

/** `url("x")` parses as a `Url` node in css-tree, not as a function call. */
private fun urlFunctionValue(token: FunctionBlock): String? {
    if (token.lowerName != "url") return null
    val meaningful = token.arguments.filter { it !is WhitespaceToken && it !is CommentToken }
    val only = meaningful.singleOrNull() ?: return null
    return (only as? StringToken)?.value
}

internal fun minifyValueTokens(tokens: List<CssNode>): String {
    val out = TokenWriter()
    writeValueTokens(tokens, out)
    return out.result()
}

// ---------------------------------------------------------------------------
// selector generation
// ---------------------------------------------------------------------------

private val SELECTOR_SEPARATORS = setOf(">", "+", "~", ",")

// "Can unquote attribute" detection, adopted by csso from Mathias Bynens
private val BLOCK_UNQUOTE = Regex(
    """^(-?\d|--)|[\u0000-\u002c\u002e\u002f\u003a-\u0040\u005b-\u005e\u0060\u007b-\u009f]""",
)

private fun packSelectorNumber(representation: String): String =
    if (representation.startsWith("+")) "+" + minNumberRepr(representation.substring(1)) else minNumberRepr(representation)

private fun canUnquoteAttribute(value: String): Boolean =
    value != "" && value != "-" && !BLOCK_UNQUOTE.containsMatchIn(value)

/**
 * Emit a selector the way css-tree does: whitespace between two compounds is a descendant
 * combinator and survives, whitespace anywhere else does not, and an attribute value loses its
 * quotes when it can.
 */
private fun writeSelectorTokens(tokens: List<CssNode>, out: TokenWriter, insideBrackets: Boolean) {
    var pendingWhitespace = false
    var previousWasSeparator = true
    var index = 0
    while (index < tokens.size) {
        val token = tokens[index]
        index++
        if (token is WhitespaceToken) {
            pendingWhitespace = true
            continue
        }
        if (token is CommentToken) continue

        // the deprecated shadow-piercing combinator is a single combinator to css-tree
        if (token is LiteralToken && token.value == "/" &&
            (tokens.getOrNull(index) as? IdentToken)?.lowerValue == "deep" &&
            (tokens.getOrNull(index + 1) as? LiteralToken)?.value == "/"
        ) {
            out.raw("/deep/")
            index += 2
            pendingWhitespace = false
            previousWasSeparator = true
            continue
        }

        val isSeparator = token is LiteralToken && token.value in SELECTOR_SEPARATORS
        if (!insideBrackets && pendingWhitespace && !previousWasSeparator && !isSeparator) out.raw(" ")
        pendingWhitespace = false

        when (token) {
            is SquareBracketsBlock -> {
                out.token("[", "[")
                writeSelectorTokens(token.content, out, insideBrackets = true)
                out.token("]", "]")
            }
            is ParenthesesBlock -> {
                out.token(T_LEFT_PAREN, "(")
                writeSelectorTokens(token.content, out, insideBrackets)
                out.token(T_RIGHT_PAREN, ")")
            }
            is FunctionBlock -> {
                out.token(T_FUNCTION, token.name + "(")
                writeSelectorTokens(token.arguments, out, insideBrackets)
                out.token(T_RIGHT_PAREN, ")")
            }
            is StringToken ->
                if (insideBrackets && canUnquoteAttribute(token.value)) {
                    out.token(T_IDENT, token.value)
                } else {
                    out.token(T_STRING, encodeStringShortest(token.value))
                }
            // `nth-child(2n+1)` is one An+B term to css-tree: the sign belongs to the term, so
            // unlike in a value it must survive number packing
            is NumberToken -> out.token(T_NUMBER, packSelectorNumber(token.representation))
            is PercentageToken -> out.token(T_PERCENTAGE, packSelectorNumber(token.representation) + "%")
            is DimensionToken ->
                out.token(T_DIMENSION, packSelectorNumber(token.representation) + (token.unitRaw ?: token.unit))
            // `nth-child(2n+1)`: css-tree parses the sign into the An+B term, never spaced
            is LiteralToken ->
                if (token.value == "+" || token.value == "-") {
                    out.raw(token.value)
                } else {
                    out.token(delimKey(token.value), token.value)
                }
            else -> out.token(T_OTHER, token.serialize())
        }
        previousWasSeparator = isSeparator
    }
}

internal fun minifySelectorTokens(tokens: List<CssNode>): String {
    val out = TokenWriter()
    writeSelectorTokens(tokens, out, insideBrackets = false)
    return out.result()
}

/** Split a selector list prelude on its top-level commas. */
private fun splitSelectorTokens(tokens: List<CssNode>): List<List<CssNode>> {
    val parts = ArrayList<List<CssNode>>()
    var current = ArrayList<CssNode>()
    for (token in tokens) {
        if (token is LiteralToken && token.value == ",") {
            parts.add(current)
            current = ArrayList()
        } else {
            current.add(token)
        }
    }
    parts.add(current)
    return parts
}

// ---------------------------------------------------------------------------
// declarations
// ---------------------------------------------------------------------------

private val TRBL_SPECIAL_UNITS = setOf("rem", "vw", "vh", "vmin", "vmax", "vm")
private val TRBL_SPECIAL_IDENTS = setOf("inherit", "initial", "unset", "revert")

/**
 * Whether css-tree would keep the value as a `Raw` node: custom properties never get a parsed
 * value, and a value it cannot parse (`filter:progid:...`) falls back to the source text.
 */
private fun isRawValue(property: String, tokens: List<CssNode>): Boolean {
    if (property.startsWith("--")) return true
    // a top-level colon never appears in a parsable value; it is the `progid:` hack
    return tokens.any { it is LiteralToken && it.value == ":" }
}

/** The shorthand pass's view of a value; `null` where the value may not take part in one. */
private class TrblValue(val parts: List<ValuePart>?, val iehack: String)

private val IE_HACK_VALUE = Regex("""^\\[09]$""")

private fun trblValueOf(tokens: List<CssNode>, raw: Boolean): TrblValue {
    if (raw) return TrblValue(null, "")
    val parts = ArrayList<ValuePart>()
    var iehack = ""
    for (token in tokens) {
        if (token is WhitespaceToken || token is CommentToken) continue
        var special: String? = null
        when (token) {
            is IdentToken -> {
                if (IE_HACK_VALUE.matches(token.value) || token.value == "\\0" || token.value == "\\9") {
                    iehack = token.value
                    continue
                }
                if (token.value in TRBL_SPECIAL_IDENTS) special = token.value
            }
            is DimensionToken -> if (token.unit in TRBL_SPECIAL_UNITS) special = token.unit
            is HashToken, is NumberToken, is PercentageToken -> {}
            is FunctionBlock -> {
                if (token.lowerName == "var") return TrblValue(null, "")
                special = token.name
            }
            else -> return TrblValue(null, "")
        }
        parts.add(ValuePart(minifyValueTokens(listOf(token)), special, token))
    }
    return TrblValue(parts, iehack)
}

private fun buildDeclaration(declaration: DeclarationNode, indexer: DeclarationIndexer): MinDecl {
    val property = declaration.name
    val raw = isRawValue(property, declaration.value)
    val tokens = if (raw) declaration.value else compressValueTokens(property, declaration.value)
    val value = if (raw) serialize(declaration.value).trim() else minifyValueTokens(tokens)
    val trbl = trblValueOf(tokens, raw)
    return indexer.mark(
        MinDecl(
            property = property,
            value = value,
            important = declaration.important,
            parts = trbl.parts,
            iehack = trbl.iehack,
            tokens = tokens,
            importantWord = declaration.importantRaw ?: "important",
            rawValue = if (raw) value else null,
        ),
    )
}

private fun buildDeclarations(content: List<CssNode>, indexer: DeclarationIndexer): MutableList<MinDecl> =
    parseDeclarationList(content)
        .filterIsInstance<DeclarationNode>()
        .mapTo(ArrayList()) { buildDeclaration(it, indexer) }
        .also { declarations -> declarations.removeAll { it.value.isEmpty() } }

// ---------------------------------------------------------------------------
// selectors
// ---------------------------------------------------------------------------

private val NON_FREEZE_PSEUDO_ELEMENTS = setOf("first-letter", "first-line", "after", "before")
private val NON_FREEZE_PSEUDO_CLASSES =
    setOf("link", "visited", "hover", "active", "first-letter", "first-line", "after", "before")
private val SELECTOR_LIST_PSEUDOS = setOf("not", "has", "is", "matches", "-webkit-any", "-moz-any")

/** csso's `specificity()`: §16 of Selectors 4, as csso implements it. */
private fun cssoSpecificity(node: SelectorNode, acc: IntArray) {
    when (node) {
        is TypeSelector -> if (node.element != null) acc[2]++
        is HashSelector -> {
            acc[0]++
            cssoSpecificity(node.selector, acc)
        }
        is ClassSelector -> {
            acc[1]++
            cssoSpecificity(node.selector, acc)
        }
        is AttribSelector -> {
            acc[1]++
            cssoSpecificity(node.selector, acc)
        }
        is PseudoSelector -> {
            when (node.ident.lowercase()) {
                "before", "after", "first-line", "first-letter" -> acc[2]++
                else -> acc[1]++
            }
            cssoSpecificity(node.selector, acc)
        }
        is NegationSelector -> {
            val inner = intArrayOf(0, 0, 0)
            cssoSpecificity(node.subselector, inner)
            acc[0] += inner[0]
            acc[1] += inner[1]
            acc[2] += inner[2]
            cssoSpecificity(node.selector, acc)
        }
        is FunctionSelector -> {
            when (val name = node.name.lowercase()) {
                in SELECTOR_LIST_PSEUDOS -> {
                    val max = maxSelectorListSpecificity(node.arguments)
                    acc[0] += max[0]
                    acc[1] += max[1]
                    acc[2] += max[2]
                }
                "nth-child", "nth-last-child" -> acc[1]++
                "where" -> {}
                else -> {
                    // keep the compiler honest about the unused binding
                    if (name.isNotEmpty()) acc[1]++
                }
            }
            cssoSpecificity(node.selector, acc)
        }
        is CombinedSelector -> {
            cssoSpecificity(node.selector, acc)
            cssoSpecificity(node.subselector, acc)
        }
    }
}

private fun maxSelectorListSpecificity(selectorList: String): IntArray {
    val parsed = parseSelectorGroupOrNull(selectorList) ?: return intArrayOf(0, 0, 0)
    var result = intArrayOf(0, 0, 0)
    for (selector in parsed) {
        val current = selectorSpecificity(selector)
        for (i in 0 until 3) {
            if (current[i] != result[i]) {
                if (current[i] > result[i]) result = current
                break
            }
        }
    }
    return result
}

private fun selectorSpecificity(selector: ParsedSelector): IntArray {
    val acc = intArrayOf(0, 0, 0)
    cssoSpecificity(selector.parsedTree, acc)
    if (selector.pseudoElement != null) acc[2]++
    return acc
}

/** The type selector of the right-most compound; csso resets it at every combinator. */
private fun rightmostTag(node: SelectorNode): String? = when (node) {
    is CombinedSelector -> rightmostTag(node.subselector)
    is TypeSelector -> node.element?.lowercase()
    is HashSelector -> rightmostTag(node.selector)
    is ClassSelector -> rightmostTag(node.selector)
    is AttribSelector -> rightmostTag(node.selector)
    is PseudoSelector -> rightmostTag(node.selector)
    is FunctionSelector -> rightmostTag(node.selector)
    is NegationSelector -> rightmostTag(node.selector)
}

private fun collectPseudos(node: SelectorNode, into: MutableSet<String>) {
    when (node) {
        is PseudoSelector -> {
            val name = node.ident.lowercase()
            if (name !in NON_FREEZE_PSEUDO_CLASSES) into.add(":$name")
            collectPseudos(node.selector, into)
        }
        is FunctionSelector -> {
            val name = node.name.lowercase()
            if (name !in NON_FREEZE_PSEUDO_CLASSES) into.add(":$name")
            collectPseudos(node.selector, into)
        }
        is NegationSelector -> {
            into.add(":not")
            collectPseudos(node.selector, into)
        }
        is HashSelector -> collectPseudos(node.selector, into)
        is ClassSelector -> collectPseudos(node.selector, into)
        is AttribSelector -> collectPseudos(node.selector, into)
        is CombinedSelector -> {
            collectPseudos(node.selector, into)
            collectPseudos(node.subselector, into)
        }
        is TypeSelector -> {}
    }
}

// ---------------------------------------------------------------------------
// model building (csso's `clean` + `replace`)
// ---------------------------------------------------------------------------

private class Builder(private val usage: CssUsage?, private val indexer: DeclarationIndexer) {
    private var seed = 1

    fun nextBlockId(): Int = ++seed

    fun buildRule(rule: QualifiedRule, keyframes: Boolean): MinRule? {
        val declarations = buildDeclarations(rule.content ?: emptyList(), indexer)
        if (declarations.isEmpty()) return null

        val pseudos = HashSet<String>()
        val selectors = ArrayList<MinSelector>()

        for (part in splitSelectorTokens(rule.prelude)) {
            val text = minifySelectorTokens(part)
            if (text.isEmpty()) continue
            val parsed = parseSelectorGroupOrNull(text)

            if (keyframes) {
                // csso rewrites keyframe stops, and compares them by value rather than specificity
                val id = when (text) {
                    "from" -> "0%"
                    "100%" -> "to"
                    else -> text
                }
                selectors.add(MinSelector(id, id))
                continue
            }

            if (parsed != null && usage != null && parsed.any { !selectorIsUsable(it.parsedTree, usage) }) {
                continue
            }

            val marker = if (parsed != null && parsed.size == 1) {
                val selector = parsed[0]
                val specificity = selectorSpecificity(selector)
                collectPseudos(selector.parsedTree, pseudos)
                selector.pseudoElement?.lowercase()?.let {
                    if (it !in NON_FREEZE_PSEUDO_ELEMENTS) pseudos.add("::$it")
                }
                val tag = rightmostTag(selector.parsedTree)
                val base = "${specificity[0]},${specificity[1]},${specificity[2]}"
                if (tag != null) "$base,$tag" else base
            } else {
                // an unparsable selector cannot be reasoned about; keep it unmergeable
                text
            }
            selectors.add(MinSelector(text, marker))
        }

        if (selectors.isEmpty()) return null
        return MinRule(selectors, declarations, if (pseudos.isEmpty()) null else pseudos.sorted().joinToString(","))
    }

    fun buildAtRule(node: AtRule, firstAtrulesAllowed: Boolean, hasPrecedingRule: Boolean): MinAtRule? {
        val prelude = generateAtRulePrelude(node.prelude)
        val basename = atRuleBasename(node.atKeyword)
        val content = node.content

        if (content == null) {
            when (node.atKeyword.lowercase()) {
                "charset" -> if (prelude.isEmpty() || hasPrecedingRule) return null
                "import" -> if (!firstAtrulesAllowed) return null
            }
            return MinAtRule(node.atKeyword.lowercase(), prelude, null)
        }

        val keyframes = basename == "keyframes"
        val block = MinBlock(nextBlockId(), ArrayList(), avoidRulesMerge = keyframes)
        val inner = parseRuleList(content)
        if (inner.any { it is QualifiedRule || it is AtRule }) {
            block.children.addAll(buildChildren(inner, insideKeyframes = keyframes))
        } else {
            for (declaration in buildDeclarations(content, indexer)) {
                block.children.add(MinDeclNode(declaration))
            }
        }

        if (block.children.isEmpty()) return null
        if ((basename == "media" || basename == "supports") && prelude.isEmpty()) return null
        return MinAtRule(node.atKeyword.lowercase(), prelude, block)
    }

    fun buildChildren(nodes: List<CssNode>, insideKeyframes: Boolean): MutableList<MinNode> {
        val out = ArrayList<MinNode>()
        var firstAtrulesAllowed = true
        for (node in nodes) {
            when (node) {
                is QualifiedRule -> {
                    buildRule(node, insideKeyframes)?.let { out.add(it) }
                    firstAtrulesAllowed = false
                }
                is AtRule -> {
                    val built = buildAtRule(node, firstAtrulesAllowed, out.isNotEmpty())
                    val keyword = node.atKeyword.lowercase()
                    if (keyword != "import" && keyword != "charset") firstAtrulesAllowed = false
                    built?.let { out.add(it) }
                }
                else -> {}
            }
        }
        return out
    }

    fun rootBlock(nodes: List<CssNode>): MinBlock = MinBlock(1, buildChildren(nodes, insideKeyframes = false))
}

// ---------------------------------------------------------------------------
// generation
// ---------------------------------------------------------------------------

private fun generateDeclarations(declarations: List<MinDecl>): String =
    declarations.joinToString(";") { it.text }

private fun generateNode(node: MinNode, sb: StringBuilder) {
    when (node) {
        is MinRule -> {
            sb.append(node.selectors.joinToString(",") { it.id })
            sb.append('{')
            sb.append(generateDeclarations(node.declarations))
            sb.append('}')
        }
        is MinAtRule -> {
            sb.append('@').append(node.name)
            if (node.prelude.isNotEmpty()) sb.append(' ').append(node.prelude)
            val block = node.block
            if (block == null) {
                sb.append(';')
            } else {
                sb.append('{')
                generateBlockBody(block, sb)
                sb.append('}')
            }
        }
        is MinDeclNode -> sb.append(node.declaration.text)
        is MinComment -> sb.append("/*").append(node.text).append("*/")
        is MinRaw -> sb.append(node.text)
    }
}

private fun generateBlockBody(block: MinBlock, sb: StringBuilder) {
    var previousWasDeclaration = false
    for (node in block.children) {
        if (node is MinDeclNode && previousWasDeclaration) sb.append(';')
        generateNode(node, sb)
        previousWasDeclaration = node is MinDeclNode
    }
}

// ---------------------------------------------------------------------------
// entry points
// ---------------------------------------------------------------------------

/**
 * Minify a stylesheet the way `csso.minify` does.
 *
 * [preserveExclamation] keeps exclamation-mark comments (csso's `comments: 'exclamation'`);
 * [usage] drops rules whose selectors cannot match anything in the document.
 */
public fun minifyCss(
    css: String,
    preserveExclamation: Boolean = true,
    usage: CssUsage? = null,
    restructuring: Boolean = true,
): String = try {
    val effectiveUsage = if (usage == null || usage.isEmpty) null else usage
    val nodes = parseStylesheet(css, skipComments = false)
    val sb = StringBuilder()

    for (chunk in splitChunks(nodes, preserveExclamation)) {
        val indexer = DeclarationIndexer()
        val root = Builder(effectiveUsage, indexer).rootBlock(chunk.nodes)
        if (restructuring) restructure(root, indexer)

        val body = StringBuilder()
        generateBlockBody(root, body)

        val comment = chunk.comment
        if (comment != null) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("/*").append(comment).append("*/")
            if (body.isNotEmpty()) sb.append('\n')
        }
        sb.append(body)
    }
    sb.toString()
} catch (_: Exception) {
    minifyWhitespaceOnly(css, preserveExclamation)
}

private class Chunk(val comment: String?, val nodes: List<CssNode>)

/** csso's `readChunk`: a retained comment starts a new chunk, and restructuring never crosses one. */
private fun splitChunks(nodes: List<CssNode>, preserveExclamation: Boolean): List<Chunk> {
    val chunks = ArrayList<Chunk>()
    var comment: String? = null
    var current = ArrayList<CssNode>()
    var nonSpaceTokenInBuffer = false
    var specialComments = preserveExclamation

    for (node in nodes) {
        if (node is CommentToken) {
            if (!specialComments || !node.value.startsWith("!")) continue
            if (nonSpaceTokenInBuffer || comment != null) {
                chunks.add(Chunk(comment, current))
                // only the first chunk may keep a comment when the mode is `first-exclamation`
                comment = node.value
                current = ArrayList()
                nonSpaceTokenInBuffer = false
            } else {
                comment = node.value
            }
            continue
        }
        if (node !is WhitespaceToken) nonSpaceTokenInBuffer = true
        current.add(node)
    }
    chunks.add(Chunk(comment, current))
    return chunks
}

/**
 * Minify a declaration block -- the content of a `style` attribute.
 *
 * csso wraps the declarations in a synthetic rule so that the restructuring passes, which only
 * ever look at rules, apply here as well; that is what folds `margin-top/right/bottom/left` in a
 * `style` attribute into `margin`.
 */
public fun minifyBlock(css: String, restructuring: Boolean = true): String = try {
    val indexer = DeclarationIndexer()
    val declarations = buildDeclarations(parseComponentValueList(css), indexer)
    if (restructuring && declarations.isNotEmpty()) {
        val rule = MinRule(mutableListOf(MinSelector("x", "0,0,1,x")), declarations, null)
        val root = MinBlock(1, mutableListOf(rule))
        restructure(root, indexer)
        val survivor = root.children.filterIsInstance<MinRule>().firstOrNull()
        generateDeclarations(survivor?.declarations.orEmpty())
    } else {
        generateDeclarations(declarations)
    }
} catch (_: Exception) {
    minifyWhitespaceOnly(css).removeSuffix(";")
}
