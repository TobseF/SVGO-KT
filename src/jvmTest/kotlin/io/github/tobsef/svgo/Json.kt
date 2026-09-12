package io.github.tobsef.svgo

/**
 * A minimal JSON reader used only by the test harness to decode the `params` block of the upstream
 * plugin fixtures. Objects become `Map<String, Any?>`, arrays `List<Any?>`, numbers `Double` (or
 * `Int` when integral), and the rest map to `String`/`Boolean`/`null`.
 */
internal object Json {
    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue()
        reader.skipWhitespace()
        require(reader.atEnd()) { "Trailing content in JSON at ${reader.pos}" }
        return value
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> = parse(text) as? Map<String, Any?> ?: emptyMap()

    private class Reader(private val text: String) {
        var pos = 0

        fun atEnd() = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            skipWhitespace()
            return when (val ch = text[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (ch == '-' || ch.isDigit()) readNumber() else error("Unexpected '$ch' at $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            require(text.startsWith(word, pos)) { "Invalid literal at $pos" }
            pos += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            pos++ // '{'
            skipWhitespace()
            if (text[pos] == '}') {
                pos++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                require(text[pos] == ':') { "Expected ':' at $pos" }
                pos++
                result[key] = readValue()
                skipWhitespace()
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++; return result
                    }
                    else -> error("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val result = ArrayList<Any?>()
            pos++ // '['
            skipWhitespace()
            if (text[pos] == ']') {
                pos++
                return result
            }
            while (true) {
                result.add(readValue())
                skipWhitespace()
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++; return result
                    }
                    else -> error("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun readString(): String {
            require(text[pos] == '"') { "Expected string at $pos" }
            pos++
            val sb = StringBuilder()
            while (text[pos] != '"') {
                if (text[pos] == '\\') {
                    pos++
                    when (val escape = text[pos]) {
                        '"', '\\', '/' -> sb.append(escape)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            sb.append(text.substring(pos + 1, pos + 5).toInt(16).toChar())
                            pos += 4
                        }
                        else -> error("Invalid escape '\\$escape' at $pos")
                    }
                    pos++
                } else {
                    sb.append(text[pos])
                    pos++
                }
            }
            pos++
            return sb.toString()
        }

        private fun readNumber(): Any {
            val start = pos
            if (text[pos] == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            val raw = text.substring(start, pos)
            val value = raw.toDouble()
            return if ('.' !in raw && 'e' !in raw && 'E' !in raw && value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
                value.toInt()
            } else {
                value
            }
        }
    }
}
