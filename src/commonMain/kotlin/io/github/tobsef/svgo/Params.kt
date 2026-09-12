package io.github.tobsef.svgo

/**
 * Plugin parameters.
 *
 * Values are untyped on purpose: plugin options come from user configuration (or, in the test
 * suite, from JSON fixtures) and may be booleans, numbers, strings, lists or nested objects. The
 * accessors reproduce JavaScript's defaulting rules -- a default applies only when the key is
 * absent -- and JavaScript truthiness where SVGO relies on it.
 */
public class Params(private val values: Map<String, Any?> = emptyMap()) {

    public val keys: Set<String> get() = values.keys

    public fun has(key: String): Boolean = values.containsKey(key)

    public fun raw(key: String): Any? = values[key]

    /** JavaScript truthiness of an arbitrary value. */
    public fun truthy(key: String, default: Boolean = false): Boolean =
        if (!values.containsKey(key)) default else isTruthy(values[key])

    public fun bool(key: String, default: Boolean): Boolean = when (val value = values[key]) {
        null -> if (values.containsKey(key)) false else default
        is Boolean -> value
        else -> isTruthy(value)
    }

    public fun intOrNull(key: String): Int? = when (val value = values[key]) {
        null -> null
        is Int -> value
        is Long -> value.toInt()
        is Double -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    public fun int(key: String, default: Int): Int = if (values.containsKey(key)) intOrNull(key) ?: default else default

    public fun doubleOrNull(key: String): Double? = when (val value = values[key]) {
        null -> null
        is Int -> value.toDouble()
        is Long -> value.toDouble()
        is Double -> value
        is String -> value.toDoubleOrNull()
        else -> null
    }

    public fun double(key: String, default: Double): Double = doubleOrNull(key) ?: default

    public fun string(key: String): String? = values[key] as? String

    public fun string(key: String, default: String): String = values[key] as? String ?: default

    @Suppress("UNCHECKED_CAST")
    public fun list(key: String): List<Any?>? = values[key] as? List<Any?>

    public fun stringList(key: String): List<String>? = list(key)?.map { it.toString() }

    @Suppress("UNCHECKED_CAST")
    public fun obj(key: String): Map<String, Any?>? = values[key] as? Map<String, Any?>

    public fun params(key: String): Params? = obj(key)?.let { Params(it) }

    /**
     * A precision parameter that may be disabled by passing `false` (SVGO's `floatPrecision:false`).
     * Returns `null` when the value is `false`, the default when absent.
     */
    public fun precision(key: String, default: Int?): Int? = when (val value = values[key]) {
        null -> if (values.containsKey(key)) null else default
        false -> null
        true -> default
        is Int -> value
        is Long -> value.toInt()
        is Double -> value.toInt()
        else -> default
    }

    internal fun toMap(): Map<String, Any?> = values

    public companion object {
        public val EMPTY: Params = Params()

        public fun of(vararg pairs: Pair<String, Any?>): Params = Params(mapOf(*pairs))

        public fun isTruthy(value: Any?): Boolean = when (value) {
            null -> false
            is Boolean -> value
            is Int -> value != 0
            is Long -> value != 0L
            is Double -> value != 0.0 && !value.isNaN()
            is String -> value.isNotEmpty()
            is Collection<*> -> true
            is Map<*, *> -> true
            else -> true
        }
    }
}

/** Merge two parameter maps; entries of [overrides] win. */
internal fun mergeParams(base: Params?, overrides: Map<String, Any?>): Params =
    if (overrides.isEmpty()) base ?: Params.EMPTY else Params(LinkedHashMap(base?.toMap() ?: emptyMap()).apply { putAll(overrides) })
