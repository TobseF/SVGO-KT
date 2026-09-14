package io.github.tobsef.svgo

import io.github.tobsef.svgo.css.CommentToken
import io.github.tobsef.svgo.css.CssNode
import io.github.tobsef.svgo.css.DimensionToken
import io.github.tobsef.svgo.css.FunctionBlock
import io.github.tobsef.svgo.css.HashToken
import io.github.tobsef.svgo.css.IdentToken
import io.github.tobsef.svgo.css.LiteralToken
import io.github.tobsef.svgo.css.NumberToken
import io.github.tobsef.svgo.css.ParenthesesBlock
import io.github.tobsef.svgo.css.PercentageToken
import io.github.tobsef.svgo.css.SquareBracketsBlock
import io.github.tobsef.svgo.css.StringToken
import io.github.tobsef.svgo.css.UrlToken
import io.github.tobsef.svgo.css.WhitespaceToken
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * csso's value replacements -- a port of `csso/lib/replace`.
 *
 * Runs bottom-up over a declaration's component values (colour functions, zero lengths, numbers)
 * and then applies the per-property handlers (`font`, `font-weight`, `background`, `border`,
 * `outline`) to the resulting top-level list, exactly as csso's `leave` walk does.
 *
 * Whitespace is dropped here rather than at generation time, because that is where csso's value
 * scope has already folded the space around a `+`/`-` operator into the operator itself.
 */

private val HEX_DIGITS_VALUE = Regex("""^[0-9a-fA-F]+$""")

private val MATH_FUNCTIONS = setOf("calc", "min", "max", "clamp")

private val LENGTH_UNITS = setOf(
    "px", "mm", "cm", "in", "pt", "pc",
    "em", "ex", "ch", "rem",
    "vh", "vw", "vmin", "vmax", "vm",
)

/** csso's percentage blacklist: properties where `0%` must keep its unit. */
private val PERCENTAGE_BLACKLIST = setOf(
    "width", "min-width", "max-width",
    "height", "min-height", "max-height",
    "flex", "-ms-flex",
)

private val GRADIENT_SCOPE = Regex("""^(?:to|from|color-stop)$|gradient$""", RegexOption.IGNORE_CASE)

/**
 * Properties whose value may be a bare `<color>` keyword.
 *
 * csso asks css-tree's lexer whether the value position is a colour; this list is that question
 * answered ahead of time for every property csso's own lexer knows, so `font-family:tan` keeps its
 * font name while `fill:white` becomes `#fff`.
 */
private val COLOR_PROPERTIES = setOf(
    "-moz-border-bottom-colors", "-moz-border-left-colors", "-moz-border-right-colors",
    "-moz-border-top-colors", "-ms-scrollbar-3dlight-color", "-ms-scrollbar-arrow-color",
    "-ms-scrollbar-base-color", "-ms-scrollbar-darkshadow-color", "-ms-scrollbar-face-color",
    "-ms-scrollbar-highlight-color", "-ms-scrollbar-shadow-color", "-ms-scrollbar-track-color",
    "-webkit-border-before", "-webkit-border-before-color", "-webkit-tap-highlight-color",
    "-webkit-text-fill-color", "-webkit-text-stroke", "-webkit-text-stroke-color", "accent-color",
    "background", "background-color", "border", "border-block", "border-block-color",
    "border-block-end", "border-block-end-color", "border-block-start", "border-block-start-color",
    "border-bottom", "border-bottom-color", "border-color", "border-inline", "border-inline-color",
    "border-inline-end", "border-inline-end-color", "border-inline-start", "border-inline-start-color",
    "border-left", "border-left-color", "border-right", "border-right-color", "border-top",
    "border-top-color", "caret-color", "color", "column-rule", "column-rule-color", "fill", "outline",
    "outline-color", "stroke", "text-decoration", "text-decoration-color",
    "text-emphasis", "text-emphasis-color",
)

/**
 * Shadow properties: a colour keyword is one only in a value that is a well-formed shadow.
 *
 * `box-shadow:white` does not match the `<shadow>` grammar, so the lexer types nothing in it as a
 * colour, while `box-shadow:0 0 1px white` types its last term as one. Requiring a numeric term is
 * the same distinction without the grammar.
 */
private val SHADOW_PROPERTIES = setOf("box-shadow", "text-shadow", "-webkit-box-shadow", "-moz-box-shadow")

/**
 * Properties whose grammar is a fixed pair of colours (`scrollbar-color: auto | <color>{2}`).
 *
 * A single keyword does not match, so it is not a colour position -- the same arity check the
 * lexer performs, expressed as a term count.
 */
private val COLOR_PAIR_PROPERTIES = setOf("scrollbar-color")

/**
 * Functions whose arguments are `<length-percentage>`.
 *
 * The lexer types `translate(0%)` as a length even though `transform` itself is not a length
 * property, so a zero there loses its unit like any other; `scale(0%)` and `matrix(...)` do not.
 */
private val LENGTH_FUNCTIONS = setOf(
    "translate", "translatex", "translatey", "translate3d", "perspective",
    "inset", "circle", "ellipse", "polygon", "blur", "minmax", "repeat",
)

/** A function whose arguments carry colour stops, so a bare colour keyword is a colour there. */
private val GRADIENT_FUNCTION = Regex("""gradient$""", RegexOption.IGNORE_CASE)

/**
 * Properties that accept an `<image>`.
 *
 * A gradient only introduces colour positions where the property takes an image in the first
 * place: `stroke:linear-gradient(red,blue)` is not a paint value, so csso's lexer rejects it and
 * leaves the keywords alone.
 */
private val IMAGE_PROPERTIES = setOf(
    "background", "background-image", "border-image", "border-image-source", "content",
    "list-style", "list-style-image", "mask", "mask-border", "mask-border-source", "mask-image",
    "shape-outside",
    "-webkit-box-reflect", "-webkit-mask", "-webkit-mask-box-image", "-webkit-mask-image",
)

/**
 * Properties where `0%` may be written as `0`.
 *
 * csso replaces the percentage with a number and asks css-tree's lexer whether the declaration
 * still types as a `<length>`, rolling back when it does not; this list is that question answered
 * ahead of time for every property csso's own lexer knows.
 */
private val LENGTH_PROPERTIES = setOf(
    "-moz-border-radius-bottomleft", "-moz-border-radius-bottomright", "-moz-border-radius-topleft",
    "-moz-border-radius-topright", "-moz-outline-radius", "-moz-outline-radius-bottomleft",
    "-moz-outline-radius-bottomright", "-moz-outline-radius-topleft", "-moz-outline-radius-topright",
    "-ms-flex-preferred-size", "-ms-grid-columns", "-ms-grid-rows", "-ms-hyphenate-limit-zone",
    "-ms-scroll-limit-x-max", "-ms-scroll-limit-x-min", "-ms-scroll-limit-y-max",
    "-ms-scroll-limit-y-min", "-ms-wrap-margin", "-webkit-border-before", "-webkit-border-before-width",
    "-webkit-box-reflect", "-webkit-mask", "-webkit-mask-position", "-webkit-mask-position-x",
    "-webkit-mask-position-y", "-webkit-mask-size", "-webkit-text-stroke", "-webkit-text-stroke-width",
    "background", "background-position", "background-position-x", "background-position-y",
    "background-size", "block-size", "border", "border-block", "border-block-end",
    "border-block-end-width", "border-block-start", "border-block-start-width", "border-block-width",
    "border-bottom", "border-bottom-left-radius", "border-bottom-right-radius", "border-bottom-width",
    "border-end-end-radius", "border-end-start-radius", "border-inline", "border-inline-end",
    "border-inline-end-width", "border-inline-start", "border-inline-start-width",
    "border-inline-width", "border-left", "border-left-width", "border-radius", "border-right",
    "border-right-width", "border-spacing", "border-start-end-radius", "border-start-start-radius",
    "border-top", "border-top-left-radius", "border-top-right-radius", "border-top-width",
    "border-width", "bottom", "column-gap", "column-rule", "column-rule-width", "column-width",
    "flex-basis", "font-size", "font-smooth", "gap", "grid-auto-columns", "grid-auto-rows",
    "grid-column-gap", "grid-gap", "grid-row-gap", "grid-template-columns", "grid-template-rows",
    "height", "inline-size", "inset", "inset-block", "inset-block-end", "inset-block-start",
    "inset-inline", "inset-inline-end", "inset-inline-start", "left", "letter-spacing",
    "line-height-step", "margin", "margin-block", "margin-block-end", "margin-block-start",
    "margin-bottom", "margin-inline", "margin-inline-end", "margin-inline-start", "margin-left",
    "margin-right", "margin-top", "mask", "mask-position", "mask-size", "max-block-size", "max-height",
    "max-inline-size", "max-width", "min-block-size", "min-height", "min-inline-size", "min-width",
    "object-position", "offset", "offset-anchor", "offset-distance", "offset-position", "outline",
    "outline-offset", "outline-width", "overflow-clip-margin", "padding", "padding-block",
    "padding-block-end", "padding-block-start", "padding-bottom", "padding-inline",
    "padding-inline-end", "padding-inline-start", "padding-left", "padding-right", "padding-top",
    "perspective", "perspective-origin", "right", "row-gap", "scroll-margin", "scroll-margin-block",
    "scroll-margin-block-end", "scroll-margin-block-start", "scroll-margin-bottom",
    "scroll-margin-inline", "scroll-margin-inline-end", "scroll-margin-inline-start",
    "scroll-margin-left", "scroll-margin-right", "scroll-margin-top", "scroll-padding",
    "scroll-padding-block", "scroll-padding-block-end", "scroll-padding-block-start",
    "scroll-padding-bottom", "scroll-padding-inline", "scroll-padding-inline-end",
    "scroll-padding-inline-start", "scroll-padding-left", "scroll-padding-right", "scroll-padding-top",
    "scroll-snap-coordinate", "scroll-snap-destination", "shape-margin", "text-decoration",
    "text-decoration-thickness", "text-indent", "text-underline-offset", "top", "transform-origin",
    "translate", "vertical-align", "width", "word-spacing",
)

/**
 * Whether a bare keyword here is a `<color>`.
 *
 * At the top level of a colour property it is; inside a function it only is where the function
 * is a gradient in a property that takes an image -- `color:linear-gradient(red,blue)` is not a
 * valid colour, so csso's lexer leaves its keywords alone.
 */
private fun isColorPosition(
    basename: String,
    colorProperty: Boolean,
    enclosingFunction: String?,
): Boolean = when {
    enclosingFunction == null -> colorProperty
    GRADIENT_FUNCTION.containsMatchIn(enclosingFunction) -> basename in IMAGE_PROPERTIES
    else -> false
}

/** csso's `compressHex`: shorten a hex colour, and swap it for a shorter colour name. */
private fun compressHexToken(value: String): CssNode {
    val compressed = minHex("#" + value)
    return if (compressed.startsWith("#")) {
        HashToken(compressed.substring(1), isIdentifier = false)
    } else {
        IdentToken(compressed)
    }
}

/** The value of a `url(<string>)`, which css-tree folds into a `Url` node. */
private fun quotedUrlValue(token: FunctionBlock): String? {
    if (token.lowerName != "url") return null
    val meaningful = token.arguments.filter { it !is WhitespaceToken && it !is CommentToken }
    return (meaningful.singleOrNull() as? StringToken)?.value
}

private fun number(text: String): NumberToken =
    NumberToken(text.toDoubleOrNull() ?: 0.0, text, !text.contains('.'))

/** csso's `compressIdent`: a colour keyword becomes a hex value when that is shorter. */
internal fun compressColorIdent(name: String): String? {
    val color = name.lowercase()
    val hex = colorsNames[color] ?: return null
    if (hex.length <= color.length) return hex
    return if (color == "grey") "gray" else color
}

// ---------------------------------------------------------------------------
// colour functions
// ---------------------------------------------------------------------------

private class ColorArg(var type: String, var value: Double)

/** csso's `parseFunctionArgs`; `null` where csso bails out and leaves the function alone. */
private fun parseColorArgs(arguments: List<CssNode>, count: Int, rgb: Boolean): DoubleArray? {
    val args = ArrayList<ColorArg>()
    var wasValue = false

    for (token in arguments) {
        if (token is WhitespaceToken || token is CommentToken) continue
        when (token) {
            is NumberToken -> {
                if (wasValue) return null
                wasValue = true
                args.add(ColorArg("Number", token.value))
            }
            is PercentageToken -> {
                if (wasValue) return null
                wasValue = true
                args.add(ColorArg("Percentage", token.value))
            }
            is LiteralToken -> {
                if (token.value == ",") {
                    if (!wasValue) return null
                    wasValue = false
                } else if (wasValue || token.value != "+") {
                    return null
                }
            }
            else -> return null
        }
    }

    if (args.size != count) return null

    if (args.size == 4) {
        if (args[3].type != "Number") return null
        args[3].type = "Alpha"
    }

    if (rgb) {
        if (args[0].type != args[1].type || args[0].type != args[2].type) return null
    } else {
        if (args[0].type != "Number" || args[1].type != "Percentage" || args[2].type != "Percentage") {
            return null
        }
        args[0].type = "Angle"
    }

    return DoubleArray(args.size) { index ->
        val arg = args[index]
        var value = max(0.0, arg.value)
        when (arg.type) {
            "Number" -> value = min(value, 255.0)
            "Percentage" -> {
                value = min(value, 100.0) / 100
                if (!rgb) return@DoubleArray value
                value *= 255
            }
            "Angle" -> return@DoubleArray (((value % 360) + 360) % 360) / 360
            "Alpha" -> return@DoubleArray min(value, 1.0)
        }
        round(value)
    }
}

private fun hueToRgb(p: Double, q: Double, tIn: Double): Double {
    var t = tIn
    if (t < 0) t += 1
    if (t > 1) t -= 1
    if (t < 1.0 / 6) return p + (q - p) * 6 * t
    if (t < 1.0 / 2) return q
    if (t < 2.0 / 3) return p + (q - p) * (2.0 / 3 - t) * 6
    return p
}

private fun hslToRgb(h: Double, s: Double, l: Double, a: Double): DoubleArray {
    val r: Double
    val g: Double
    val b: Double
    if (s == 0.0) {
        r = l
        g = l
        b = l
    } else {
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        r = hueToRgb(p, q, h + 1.0 / 3)
        g = hueToRgb(p, q, h)
        b = hueToRgb(p, q, h - 1.0 / 3)
    }
    return doubleArrayOf(round(r * 255), round(g * 255), round(b * 255), a)
}

private fun toHex(value: Double): String {
    val text = value.toInt().toString(16)
    return if (text.length == 1) "0$text" else text
}

/** csso's `compressFunction`: `rgb()`/`hsl()` and friends collapse to a hex value or a keyword. */
private fun compressColorFunction(node: FunctionBlock, enclosingFunction: String?): CssNode {
    var functionName = node.lowerName
    var args: DoubleArray? = null

    if (functionName == "rgba" || functionName == "hsla") {
        args = parseColorArgs(node.arguments, 4, functionName == "rgba") ?: return node

        if (functionName == "hsla") args = hslToRgb(args[0], args[1], args[2], args[3])

        if (args[3] == 0.0) {
            // `rgba(0,0,0,0)` is always `transparent`; other fully transparent colours only outside
            // a gradient, where the colour still drives the interpolation
            val scope = enclosingFunction.orEmpty()
            if ((args[0] == 0.0 && args[1] == 0.0 && args[2] == 0.0) || !GRADIENT_SCOPE.containsMatchIn(scope)) {
                return IdentToken("transparent")
            }
        }

        if (args[3] != 1.0) {
            val rewritten = ArrayList<CssNode>()
            var index = 0
            for (token in node.arguments) {
                if (token is WhitespaceToken || token is CommentToken) continue
                if (token is LiteralToken) {
                    if (token.value == ",") rewritten.add(token)
                    continue
                }
                rewritten.add(number(minNumberRepr(formatColorComponent(args[index++]))))
            }
            return FunctionBlock("rgba", rewritten)
        }

        functionName = "rgb"
    }

    if (functionName == "hsl") {
        val parsed = args ?: parseColorArgs(node.arguments, 3, false) ?: return node
        args = hslToRgb(parsed[0], parsed[1], parsed[2], 1.0)
        functionName = "rgb"
    }

    if (functionName == "rgb") {
        val parsed = args ?: parseColorArgs(node.arguments, 3, true) ?: return node
        return compressHexToken(toHex(parsed[0]) + toHex(parsed[1]) + toHex(parsed[2]))
    }

    return node
}

/** Renders a colour component the way JavaScript's `String(number)` would. */
private fun formatColorComponent(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

// ---------------------------------------------------------------------------
// per-property handlers
// ---------------------------------------------------------------------------

private fun compressFontWeight(tokens: MutableList<CssNode>) {
    val first = tokens.firstOrNull() as? IdentToken ?: return
    when (first.value) {
        "normal" -> tokens[0] = number("400")
        "bold" -> tokens[0] = number("700")
    }
}

private fun compressFont(tokens: MutableList<CssNode>) {
    for (index in tokens.indices.reversed()) {
        if (index >= tokens.size) continue
        val token = tokens[index] as? IdentToken ?: continue
        when (token.value) {
            "bold" -> tokens[index] = number("700")
            "normal" -> {
                val previous = tokens.getOrNull(index - 1)
                tokens.removeAt(index)
                if (previous is LiteralToken && previous.value == "/") tokens.removeAt(index - 1)
            }
        }
    }
    if (tokens.isEmpty()) tokens.add(IdentToken("normal"))
}

private val BACKGROUND_DEFAULTS = setOf("transparent", "none", "repeat", "scroll")

private fun compressBackground(tokens: MutableList<CssNode>) {
    val newValue = ArrayList<CssNode>()
    var buffer = ArrayList<CssNode>()

    fun flush() {
        if (buffer.isEmpty()) {
            buffer.add(number("0"))
            buffer.add(number("0"))
        }
        newValue.addAll(buffer)
        buffer = ArrayList()
    }

    for (token in tokens) {
        if (token is LiteralToken && token.value == ",") {
            flush()
            newValue.add(token)
            continue
        }
        if (token is IdentToken && token.value in BACKGROUND_DEFAULTS) continue
        buffer.add(token)
    }
    flush()

    tokens.clear()
    tokens.addAll(newValue)
}

private fun compressBorder(tokens: MutableList<CssNode>) {
    for (index in tokens.indices.reversed()) {
        val token = tokens[index]
        if (token is IdentToken && token.lowerValue == "none") {
            if (tokens.size == 1) {
                tokens[index] = number("0")
            } else {
                tokens.removeAt(index)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// entry point
// ---------------------------------------------------------------------------

private fun compressTokens(
    tokens: List<CssNode>,
    property: String,
    basename: String,
    colorProperty: Boolean,
    insideMathFunction: Boolean,
    enclosingFunction: String?,
): MutableList<CssNode> {
    val out = ArrayList<CssNode>(tokens.size)
    var index = 0

    while (index < tokens.size) {
        val token = tokens[index]
        if (token is WhitespaceToken) {
            index++
            continue
        }
        if (token is CommentToken) {
            index++
            continue
        }

        // css-tree folds the whitespace around a `+`/`-` operator into the operator, because
        // `calc(1px + 2px)` and `calc(1px+2px)` are not the same expression
        if (token is LiteralToken && (token.value == "+" || token.value == "-")) {
            val leading = index > 0 && tokens[index - 1] is WhitespaceToken
            var next = index + 1
            var trailing = false
            while (next < tokens.size && (tokens[next] is WhitespaceToken || tokens[next] is CommentToken)) {
                if (tokens[next] is WhitespaceToken) trailing = true
                next++
            }
            val text = (if (leading) " " else "") + token.value + (if (trailing) " " else "")
            out.add(if (leading || trailing) LiteralToken(text) else token)
            index++
            continue
        }

        out.add(
            when (token) {
                is DimensionToken -> {
                    val packed = minNumberRepr(token.representation)
                    if (packed == "0" && token.unit.lowercase() in LENGTH_UNITS &&
                        property != "flex" && property != "-ms-flex" && !insideMathFunction
                    ) {
                        number("0")
                    } else {
                        DimensionToken(token.value, packed, token.isInteger, token.unit, token.unitRaw)
                    }
                }

                is PercentageToken -> {
                    val packed = minNumberRepr(token.representation)
                    val lengthPosition = basename in LENGTH_PROPERTIES ||
                        (enclosingFunction != null && enclosingFunction in LENGTH_FUNCTIONS)
                    if (packed == "0" && property !in PERCENTAGE_BLACKLIST && lengthPosition) {
                        number("0")
                    } else {
                        PercentageToken(token.value, packed, token.isInteger)
                    }
                }

                is NumberToken -> NumberToken(token.value, minNumberRepr(token.representation), token.isInteger)

                // csso's `compressHex` runs before restructuring, so a hex that has a shorter
                // colour name really is an identifier by the time declarations get fingerprinted
                is HashToken ->
                    if (token.isIdentifier || HEX_DIGITS_VALUE.matches(token.value)) {
                        compressHexToken(token.value)
                    } else {
                        token
                    }

                is IdentToken ->
                    if (isColorPosition(basename, colorProperty, enclosingFunction)) {
                        when (val replacement = compressColorIdent(token.value)) {
                            null -> token
                            else ->
                                if (replacement.startsWith("#")) {
                                    HashToken(replacement.substring(1), isIdentifier = false)
                                } else {
                                    IdentToken(replacement)
                                }
                        }
                    } else {
                        token
                    }

                is FunctionBlock -> {
                    // `url("x")` is a Url node to css-tree, never a function call
                    val quoted = quotedUrlValue(token)
                    if (quoted != null) {
                        UrlToken(quoted)
                    } else {
                        val math = insideMathFunction || token.lowerName in MATH_FUNCTIONS
                        val compressed = FunctionBlock(
                            token.name,
                            compressTokens(token.arguments, property, basename, colorProperty, math, token.lowerName),
                        )
                        compressColorFunction(compressed, enclosingFunction)
                    }
                }

                is ParenthesesBlock ->
                    ParenthesesBlock(
                        compressTokens(token.content, property, basename, colorProperty, insideMathFunction, enclosingFunction),
                    )

                is SquareBracketsBlock ->
                    SquareBracketsBlock(
                        compressTokens(token.content, property, basename, colorProperty, insideMathFunction, enclosingFunction),
                    )

                else -> token
            },
        )
        index++
    }

    return out
}

/** Apply csso's `replace` stage to one declaration's value. */
internal fun compressValueTokens(property: String, tokens: List<CssNode>): MutableList<CssNode> {
    val basename = io.github.tobsef.svgo.css.PropertyDescriptor(property).basename
    // a `<shadow>` needs an offset pair before its colour, so one numeric term is not enough:
    // `box-shadow:1px white` is not a shadow and csso leaves its keyword alone
    val terms = tokens.count { it !is WhitespaceToken && it !is CommentToken }
    val colorProperty = basename in COLOR_PROPERTIES ||
        (basename in SHADOW_PROPERTIES && tokens.count { it is DimensionToken || it is NumberToken } >= 2) ||
        (basename in COLOR_PAIR_PROPERTIES && terms == 2)
    val out = compressTokens(
        tokens,
        property.lowercase(),
        basename,
        colorProperty,
        insideMathFunction = false,
        enclosingFunction = null,
    )

    when (basename) {
        "font" -> compressFont(out)
        "font-weight" -> compressFontWeight(out)
        "background" -> compressBackground(out)
        "border", "outline" -> compressBorder(out)
    }

    return out
}
