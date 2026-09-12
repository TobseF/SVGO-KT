package io.github.tobsef.svgo

import kotlin.math.floor

/** SVG path data parsing/stringifying. Port of `lib/path.js`. */
public class PathItem(
    public var command: String,
    public var args: MutableList<Double>,
) {
    /** Absolute coordinates of the point the command starts from (set by `convertToRelative`). */
    internal var base: MutableList<Double>? = null

    /** Absolute coordinates of the point the command ends at. */
    internal var coords: MutableList<Double>? = null

    /** Expanded (longhand) arguments of a smooth curve, cached by `convertPathData`. */
    internal var sdata: MutableList<Double>? = null

    internal fun copy(): PathItem = PathItem(command, ArrayList(args)).also {
        it.base = base
        it.coords = coords
        it.sdata = sdata
    }
}

private val ARGS_COUNT = mapOf(
    "M" to 2, "m" to 2, "Z" to 0, "z" to 0, "L" to 2, "l" to 2, "H" to 1, "h" to 1,
    "V" to 1, "v" to 1, "C" to 6, "c" to 6, "S" to 4, "s" to 4, "Q" to 4, "q" to 4,
    "T" to 2, "t" to 2, "A" to 7, "a" to 7,
)

private fun isCommand(c: Char): Boolean = ARGS_COUNT.containsKey(c.toString())

private fun isWhitespace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\r' || c == '\n'

private fun isDigit(c: Char): Boolean = c in '0'..'9'

private class NumberRead(val cursor: Int, val number: Double?)

private fun readNumber(string: String, cursor: Int): NumberRead {
    var i = cursor
    val value = StringBuilder()
    var state = "none"
    val n = string.length
    loop@ while (i < n) {
        val c = string[i]
        if (c == '+' || c == '-') {
            if (state == "none") {
                state = "sign"; value.append(c); i++; continue@loop
            }
            if (state == "e") {
                state = "exponent_sign"; value.append(c); i++; continue@loop
            }
        }
        if (isDigit(c)) {
            when (state) {
                "none", "sign", "whole" -> {
                    state = "whole"; value.append(c); i++; continue@loop
                }
                "decimal_point", "decimal" -> {
                    state = "decimal"; value.append(c); i++; continue@loop
                }
                "e", "exponent_sign", "exponent" -> {
                    state = "exponent"; value.append(c); i++; continue@loop
                }
            }
        }
        if (c == '.') {
            if (state == "none" || state == "sign" || state == "whole") {
                state = "decimal_point"; value.append(c); i++; continue@loop
            }
        }
        if (c == 'E' || c == 'e') {
            if (state == "whole" || state == "decimal_point" || state == "decimal") {
                state = "e"; value.append(c); i++; continue@loop
            }
        }
        break
    }
    val number = jsParseFloat(value.toString())
    if (number == null || number.isNaN()) return NumberRead(cursor, null)
    return NumberRead(i - 1, number)
}

public fun parsePathData(string: String): MutableList<PathItem> {
    val pathData = ArrayList<PathItem>()
    var command: String? = null
    var args = ArrayList<Double>()
    var argsCount = 0
    var canHaveComma = false
    var hadComma = false
    var i = 0
    val n = string.length
    while (i < n) {
        val c = string[i]
        if (isWhitespace(c)) {
            i++
            continue
        }
        if (canHaveComma && c == ',') {
            if (hadComma) break
            hadComma = true
            i++
            continue
        }
        if (isCommand(c)) {
            if (hadComma) return pathData
            if (command == null) {
                if (c != 'M' && c != 'm') return pathData
            } else if (args.isNotEmpty()) {
                return pathData
            }
            command = c.toString()
            args = ArrayList()
            argsCount = ARGS_COUNT.getValue(command)
            canHaveComma = false
            if (argsCount == 0) pathData.add(PathItem(command, args))
            i++
            continue
        }
        if (command == null) return pathData
        var newCursor = i
        var number: Double? = null
        if (command == "A" || command == "a") {
            when (args.size) {
                0, 1, 2, 5, 6 -> {
                    val read = readNumber(string, i)
                    newCursor = read.cursor
                    number = read.number
                }
                3, 4 -> {
                    if (c == '0') number = 0.0
                    if (c == '1') number = 1.0
                }
            }
        } else {
            val read = readNumber(string, i)
            newCursor = read.cursor
            number = read.number
        }
        if (number == null) return pathData
        args.add(number)
        canHaveComma = true
        hadComma = false
        i = newCursor
        if (args.size == argsCount) {
            if (command == "A" || command == "a") {
                args[0] = kotlin.math.abs(args[0])
                args[1] = kotlin.math.abs(args[1])
            }
            pathData.add(PathItem(command, args))
            if (command == "M") command = "L"
            if (command == "m") command = "l"
            args = ArrayList()
        }
        i++
    }
    return pathData
}

private class RoundedNumber(val text: String, val value: Double)

private fun roundAndStringify(number: Double, precision: Int?): RoundedNumber {
    val rounded = if (precision != null) toFixed(number, precision) else number
    return RoundedNumber(removeLeadingZero(rounded), rounded)
}

private fun isIntegerValue(x: Double): Boolean = !x.isNaN() && !x.isInfinite() && x == floor(x)

private fun stringifyArgs(
    command: String,
    args: List<Double>,
    precision: Int?,
    disableSpaceAfterFlags: Boolean,
): String {
    val result = StringBuilder()
    var previous = 0.0
    for (i in args.indices) {
        val rounded = roundAndStringify(args[i], precision)
        if (disableSpaceAfterFlags && (command == "A" || command == "a") && (i % 7 == 4 || i % 7 == 5)) {
            result.append(rounded.text)
        } else if (i == 0 || rounded.value < 0) {
            result.append(rounded.text)
        } else if (!isIntegerValue(previous) && !isDigit(rounded.text[0])) {
            result.append(rounded.text)
        } else {
            result.append(' ').append(rounded.text)
        }
        previous = rounded.value
    }
    return result.toString()
}

public fun stringifyPathData(
    pathData: List<PathItem>,
    precision: Int? = null,
    disableSpaceAfterFlags: Boolean = false,
): String {
    if (pathData.size == 1) {
        val item = pathData[0]
        return item.command + stringifyArgs(item.command, item.args, precision, disableSpaceAfterFlags)
    }
    if (pathData.isEmpty()) return ""

    val result = StringBuilder()
    var prevCommand = pathData[0].command
    var prevArgs: MutableList<Double> = ArrayList(pathData[0].args)

    if (pathData[1].command == "L") {
        prevCommand = "M"
    } else if (pathData[1].command == "l") {
        prevCommand = "m"
    }

    for (i in 1 until pathData.size) {
        val command = pathData[i].command
        val args = pathData[i].args
        if ((prevCommand == command && prevCommand != "M" && prevCommand != "m") ||
            (prevCommand == "M" && command == "L") ||
            (prevCommand == "m" && command == "l")
        ) {
            prevArgs = ArrayList<Double>(prevArgs).also { it.addAll(args) }
            if (i == pathData.size - 1) {
                result.append(prevCommand)
                    .append(stringifyArgs(prevCommand, prevArgs, precision, disableSpaceAfterFlags))
            }
        } else {
            result.append(prevCommand)
                .append(stringifyArgs(prevCommand, prevArgs, precision, disableSpaceAfterFlags))
            if (i == pathData.size - 1) {
                result.append(command)
                    .append(stringifyArgs(command, args, precision, disableSpaceAfterFlags))
            } else {
                prevCommand = command
                prevArgs = args
            }
        }
    }

    return result.toString()
}
