package io.github.tobsef.svgo.css

/**
 * csso's structural optimizations -- a port of `csso/lib/restructure`.
 *
 * The minifier in `Css.kt` reproduces csso's `clean` and `replace` stages while building the model
 * below; this file is the third stage. It runs the same eight passes in the same order:
 *
 *  1. `mergeAtrule`         -- relocate `@keyframes`, merge adjacent `@media` with equal queries
 *  2. `initialMergeRuleset` -- join neighbouring rules with equal selectors or equal declarations
 *  3. `disjoinRuleset`      -- split `a, b { ... }` into one rule per selector
 *  4. `restructShorthand`   -- fold `padding-top/right/bottom/left` into `padding` (and friends)
 *  6. `restructBlock`       -- drop declarations a later one overrides, and ones a shorthand covers
 *  7. `mergeRuleset`        -- re-join rules with equal declarations into a selector list
 *  8. `restructRuleset`     -- extract shared declarations into their own rule where that is smaller
 *
 * Pass 5 does not exist upstream either; csso's file numbering skips it.
 *
 * The model uses plain lists where csso uses linked lists. csso's cursor walks translate to index
 * walks that re-read `size` after every mutation, and the `item` handles it keeps into a block
 * translate to identity lookups -- the only mutation that can invalidate them (removal) happens
 * one declaration at a time.
 */

// ---------------------------------------------------------------------------
// name descriptors (css-tree `lib/utils/names.js`)
// ---------------------------------------------------------------------------

internal fun isCustomPropertyName(name: String, offset: Int = 0): Boolean =
    name.length - offset >= 2 && name[offset] == '-' && name[offset + 1] == '-'

internal fun vendorPrefixOf(name: String, offset: Int = 0): String {
    if (name.length - offset >= 3 && name[offset] == '-' && name[offset + 1] != '-') {
        val second = name.indexOf('-', offset + 2)
        if (second != -1) return name.substring(offset, second + 1)
    }
    return ""
}

/** css-tree's `keyword()`: the at-rule/keyword name without its vendor prefix. */
internal fun keywordBasename(keyword: String): String {
    val name = keyword.lowercase()
    val vendor = if (isCustomPropertyName(name)) "" else vendorPrefixOf(name)
    return name.substring(vendor.length)
}

/** css-tree's `keyword().vendor`. */
internal fun keywordVendor(keyword: String): String {
    val name = keyword.lowercase()
    return if (isCustomPropertyName(name)) "" else vendorPrefixOf(name)
}

/** css-tree's `property()` descriptor: splits a property into hack, vendor prefix and basename. */
internal class PropertyDescriptor(property: String) {
    val hack: String
    val prefix: String
    val basename: String

    init {
        val first = property.firstOrNull()
        hack = when {
            first == '/' -> if (property.length > 1 && property[1] == '/') "//" else "/"
            first == '_' || first == '*' || first == '$' || first == '#' || first == '+' || first == '&' ->
                first.toString()
            else -> ""
        }
        val custom = isCustomPropertyName(property, hack.length)
        val name = if (custom) property else property.lowercase()
        val vendor = if (custom) "" else vendorPrefixOf(name, hack.length)
        prefix = name.substring(0, hack.length + vendor.length)
        basename = name.substring(prefix.length)
    }
}

// ---------------------------------------------------------------------------
// model
// ---------------------------------------------------------------------------

/**
 * One component of a declaration value, as seen by the shorthand pass.
 *
 * [node] is kept because a shorthand the pass synthesises must carry the component values into the
 * new declaration: `restructBlock` fingerprints a declaration from its value nodes, and a shorthand
 * without them would fingerprint as "no special values" and be merged with anything.
 */
internal class ValuePart(val text: String, val special: String?, val node: CssNode)

/**
 * A minified declaration.
 *
 * [text] is csso's `generate(declaration)` -- the string its declaration indexer hashes, and the
 * string the generator emits. [parts] and [iehack] carry the shorthand pass's view of the value;
 * [tokens] the (pre-minification) component values the fingerprint walk inspects.
 */
internal class MinDecl(
    val property: String,
    val value: String,
    val important: Boolean,
    val parts: List<ValuePart>?,
    val iehack: String,
    val tokens: List<CssNode>,
    /** The `important` keyword as written; css-tree echoes a non-lowercase spelling verbatim. */
    val importantWord: String = "important",
    /** Set when the value stayed a `Raw` node -- a custom property, or a value css-tree cannot parse. */
    val rawValue: String? = null,
) {
    val text: String = property + ":" + value + if (important) "!" + importantWord else ""
    var id: Int = 0
    var length: Int = 0
    var fingerprint: String? = null
}

/** A single selector of a rule's selector list, with csso's `id` and `compareMarker`. */
internal class MinSelector(val id: String, val compareMarker: String)

internal sealed class MinNode

internal class MinRule(
    var selectors: MutableList<MinSelector>,
    var declarations: MutableList<MinDecl>,
    val pseudoSignature: String?,
) : MinNode()

internal class MinAtRule(
    val name: String,
    val prelude: String,
    var block: MinBlock?,
) : MinNode()

/** A declaration sitting directly in an at-rule block, such as inside `@font-face`. */
internal class MinDeclNode(val declaration: MinDecl) : MinNode()

/** A retained exclamation-mark comment, kept verbatim. */
internal class MinComment(val text: String) : MinNode()

/** Verbatim output, used for the newlines csso puts around a retained comment. */
internal class MinRaw(val text: String) : MinNode()

/** A stylesheet or an at-rule block; [id] is csso's per-block identity. */
internal class MinBlock(
    val id: Int,
    val children: MutableList<MinNode>,
    var avoidRulesMerge: Boolean = false,
)

/** Assigns csso's declaration ids: equal declaration text gets equal id. */
internal class DeclarationIndexer {
    private val ids = HashMap<String, Int>()

    fun mark(declaration: MinDecl): MinDecl {
        declaration.id = ids.getOrPut(declaration.text) { ids.size + 1 }
        declaration.length = declaration.text.length
        declaration.fingerprint = null
        return declaration
    }
}

// ---------------------------------------------------------------------------
// shared helpers (`restructure/utils.js`)
// ---------------------------------------------------------------------------

private fun isEqualSelectors(a: List<MinSelector>, b: List<MinSelector>): Boolean {
    if (a.size != b.size) return false
    for (i in a.indices) if (a[i].id != b[i].id) return false
    return true
}

private fun isEqualDeclarations(a: List<MinDecl>, b: List<MinDecl>): Boolean {
    if (a.size != b.size) return false
    for (i in a.indices) if (a[i].id != b[i].id) return false
    return true
}

private class DeclarationDiff {
    val eq = ArrayList<MinDecl>()
    val ne1 = ArrayList<MinDecl>()
    val ne2 = ArrayList<MinDecl>()
    val ne2overrided = ArrayList<MinDecl>()
}

private fun compareDeclarations(declarations1: List<MinDecl>, declarations2: List<MinDecl>): DeclarationDiff {
    val result = DeclarationDiff()
    val fingerprints = HashMap<String, Boolean>()
    val declarations2hash = HashMap<Int, Boolean>()

    for (declaration in declarations2) declarations2hash[declaration.id] = true

    for (declaration in declarations1) {
        declaration.fingerprint?.let { fingerprints[it] = declaration.important }
        if (declarations2hash[declaration.id] == true) {
            declarations2hash[declaration.id] = false
            result.eq.add(declaration)
        } else {
            result.ne1.add(declaration)
        }
    }

    for (declaration in declarations2) {
        if (declarations2hash[declaration.id] == true) {
            // when declarations1 has an overriding declaration this is not a difference, unless
            // !important is only used on the following one
            val fingerprint = declaration.fingerprint
            val known = fingerprint != null && fingerprints.containsKey(fingerprint)
            if (!known || (fingerprints[fingerprint] == false && declaration.important)) {
                result.ne2.add(declaration)
            }
            result.ne2overrided.add(declaration)
        }
    }

    return result
}

/** Merge [source] into the id-sorted [dest], skipping selectors already present. */
private fun addSelectors(dest: MutableList<MinSelector>, source: List<MinSelector>): MutableList<MinSelector> {
    for (sourceData in source) {
        val newStr = sourceData.id
        var index = 0
        var duplicate = false
        while (index < dest.size) {
            val nextStr = dest[index].id
            if (nextStr == newStr) {
                duplicate = true
                break
            }
            if (nextStr > newStr) break
            index++
        }
        if (!duplicate) dest.add(index, sourceData)
    }
    return dest
}

/** Whether the two selector lists share a compare marker (equal specificity and element). */
private fun hasSimilarSelectors(selectors1: List<MinSelector>, selectors2: List<MinSelector>): Boolean {
    for (a in selectors1) for (b in selectors2) if (a.compareMarker == b.compareMarker) return true
    return false
}

/** Whether [node] may be stepped over while looking for a rule to merge with. */
private fun unsafeToSkipNode(node: MinNode, selectors: List<MinSelector>): Boolean = when (node) {
    is MinRule -> hasSimilarSelectors(node.selectors, selectors)
    is MinAtRule -> node.block?.children?.any { unsafeToSkipNode(it, selectors) } ?: true
    is MinDeclNode -> false
    else -> true
}

// ---------------------------------------------------------------------------
// 1. mergeAtrule
// ---------------------------------------------------------------------------

private fun relocateAtrules(root: MinBlock, forceMediaMerge: Boolean) {
    // at-rule basename -> "name/prelude" -> the at-rules collected under it, in encounter order
    val collected = LinkedHashMap<String, LinkedHashMap<String, MutableList<MinAtRule>>>()
    var topInjectPoint = -1
    val kept = ArrayList<MinNode>()

    fun collect(node: MinAtRule, single: Boolean) {
        val basename = keywordBasename(node.name)
        val id = node.name.lowercase() + "/" + node.prelude
        val byId = collected.getOrPut(basename) { LinkedHashMap() }
        if (single) byId.remove(id)
        byId.getOrPut(id) { ArrayList() }.add(node)
    }

    for (node in root.children) {
        if (node is MinAtRule) {
            when (keywordBasename(node.name)) {
                "keyframes" -> {
                    collect(node, single = true)
                    continue
                }
                "media" -> if (forceMediaMerge) {
                    collect(node, single = false)
                    continue
                }
            }
            if (topInjectPoint == -1 && node.name != "charset" && node.name != "import") {
                topInjectPoint = kept.size
            }
        } else if (topInjectPoint == -1) {
            topInjectPoint = kept.size
        }
        kept.add(node)
    }

    if (collected.isEmpty()) return

    var injectAt = if (topInjectPoint == -1) kept.size else topInjectPoint
    for ((basename, byId) in collected) {
        for (nodes in byId.values) {
            if (basename == "media") {
                kept.addAll(nodes)
            } else {
                kept.addAll(injectAt, nodes)
                injectAt += nodes.size
            }
        }
    }

    root.children.clear()
    root.children.addAll(kept)
}

/** Merge an `@media` into the immediately preceding one when both carry the same query. */
private fun mergeAdjacentMedia(block: MinBlock) {
    for (child in block.children) if (child is MinAtRule) child.block?.let { mergeAdjacentMedia(it) }

    var index = block.children.size - 1
    while (index > 0) {
        val node = block.children[index]
        val prev = block.children[index - 1]
        if (node is MinAtRule && node.name == "media" && prev is MinAtRule && prev.name == "media" &&
            node.prelude.isNotEmpty() && prev.prelude.isNotEmpty() && node.prelude == prev.prelude
        ) {
            prev.block?.children?.addAll(node.block?.children.orEmpty())
            block.children.removeAt(index)
        }
        index--
    }
}

internal fun mergeAtrule(root: MinBlock, forceMediaMerge: Boolean) {
    relocateAtrules(root, forceMediaMerge)
    mergeAdjacentMedia(root)
}

// ---------------------------------------------------------------------------
// 2. initialMergeRuleset
// ---------------------------------------------------------------------------

internal fun initialMergeRuleset(block: MinBlock) {
    val children = block.children
    var index = 0
    while (index < children.size) {
        val node = children[index]
        if (node is MinAtRule) {
            node.block?.let { initialMergeRuleset(it) }
            index++
            continue
        }
        if (node !is MinRule) {
            index++
            continue
        }

        var removed = false
        var cursor = index - 1
        while (cursor >= 0) {
            val prev = children[cursor]
            if (prev !is MinRule) {
                if (unsafeToSkipNode(prev, node.selectors)) break
                cursor--
                continue
            }

            if (node.pseudoSignature == prev.pseudoSignature) {
                if (isEqualSelectors(prev.selectors, node.selectors)) {
                    prev.declarations.addAll(node.declarations)
                    children.removeAt(index)
                    removed = true
                    break
                }
                if (isEqualDeclarations(node.declarations, prev.declarations)) {
                    addSelectors(prev.selectors, node.selectors)
                    children.removeAt(index)
                    removed = true
                    break
                }
            }

            // go to the previous rule only when there are no selector similarities
            if (hasSimilarSelectors(node.selectors, prev.selectors)) break
            cursor--
        }

        if (!removed) index++
    }
}

// ---------------------------------------------------------------------------
// 3. disjoinRuleset
// ---------------------------------------------------------------------------

internal fun disjoinRuleset(block: MinBlock) {
    val expanded = ArrayList<MinNode>(block.children.size)
    for (node in block.children) {
        if (node is MinAtRule) {
            node.block?.let { disjoinRuleset(it) }
            expanded.add(node)
            continue
        }
        if (node !is MinRule || node.selectors.size <= 1) {
            expanded.add(node)
            continue
        }
        for (selector in node.selectors) {
            expanded.add(
                MinRule(
                    selectors = mutableListOf(selector),
                    declarations = ArrayList(node.declarations),
                    pseudoSignature = node.pseudoSignature,
                ),
            )
        }
    }
    block.children.clear()
    block.children.addAll(expanded)
}

// ---------------------------------------------------------------------------
// 4. restructShorthand
// ---------------------------------------------------------------------------

private const val TOP = 0
private const val RIGHT = 1
private const val BOTTOM = 2
private const val LEFT = 3
private val SIDES = arrayOf("top", "right", "bottom", "left")

private val SIDE: Map<String, String> = buildMap {
    for (prefix in listOf("margin", "padding")) {
        for (side in SIDES) put("$prefix-$side", side)
    }
    for (suffix in listOf("color", "width", "style")) {
        for (side in SIDES) put("border-$side-$suffix", side)
    }
}

private val MAIN_PROPERTY: Map<String, String> = buildMap {
    for (prefix in listOf("margin", "padding")) {
        put(prefix, prefix)
        for (side in SIDES) put("$prefix-$side", prefix)
    }
    for (suffix in listOf("color", "width", "style")) {
        put("border-$suffix", "border-$suffix")
        for (side in SIDES) put("border-$side-$suffix", "border-$suffix")
    }
}

private class SideValue(val part: ValuePart, val important: Boolean)

/** csso's `TRBL`: accumulates the four sides of a `margin`/`padding`/`border-*` shorthand. */
private class Trbl(val name: String) {
    var iehack: String? = null
    val sides = HashMap<String, SideValue?>().apply {
        for (side in SIDES) put(side, null)
    }

    /** csso's `getValueSequence`; `null` stands for its `false`. */
    fun getValueSequence(declaration: MinDecl, count: Int): List<SideValue>? {
        val parts = declaration.parts ?: return null
        if (parts.size > count) return null
        val current = iehack
        if (current != null && current != declaration.iehack) return null
        iehack = declaration.iehack
        return parts.map { SideValue(it, declaration.important) }
    }

    fun canOverride(side: String, value: SideValue): Boolean {
        val currentValue = sides[side]
        return currentValue == null || (value.important && !currentValue.important)
    }

    fun add(name: String, declaration: MinDecl): Boolean {
        val side = SIDE[name]
        val added = if (side != null) {
            val values = getValueSequence(declaration, 1)
            if (values.isNullOrEmpty()) {
                false
            } else {
                // can mix only if specials are equal
                if (sides.values.any { it != null && it.part.special != values[0].part.special }) {
                    false
                } else {
                    if (canOverride(side, values[0])) sides[side] = values[0]
                    true
                }
            }
        } else if (name == this.name) {
            val parsed = getValueSequence(declaration, 4)
            if (parsed.isNullOrEmpty()) {
                false
            } else {
                val values = ArrayList(parsed)
                when (values.size) {
                    1 -> {
                        values.add(values[TOP]); values.add(values[TOP]); values.add(values[TOP])
                    }
                    2 -> {
                        values.add(values[TOP]); values.add(values[RIGHT])
                    }
                    3 -> values.add(values[RIGHT])
                }
                if (values.any { value -> sides.values.any { it != null && it.part.special != value.part.special } }) {
                    false
                } else {
                    for (i in 0 until 4) {
                        if (canOverride(SIDES[i], values[i])) sides[SIDES[i]] = values[i]
                    }
                    true
                }
            }
        } else {
            return false
        }
        return added
    }

    fun isOkToMinimize(): Boolean {
        val top = sides["top"] ?: return false
        val right = sides["right"] ?: return false
        val bottom = sides["bottom"] ?: return false
        val left = sides["left"] ?: return false
        val important = listOf(top, right, bottom, left).count { it.important }
        return important == 0 || important == 4
    }

    /** The sides that survive the `1px 1px 1px 1px` -> `1px` collapse, in shorthand order. */
    private fun usedSides(): List<SideValue> {
        val values = mutableListOf(sides["top"]!!, sides["right"]!!, sides["bottom"]!!, sides["left"]!!)
        val text = values.map { it.part.text }
        if (text[LEFT] == text[RIGHT]) {
            values.removeAt(values.size - 1)
            if (text[BOTTOM] == text[TOP]) {
                values.removeAt(values.size - 1)
                if (text[RIGHT] == text[TOP]) values.removeAt(values.size - 1)
            }
        }
        return values
    }

    fun getDeclaration(): MinDecl {
        val used = usedSides()
        val parts = used.map { it.part.text }.toMutableList()
        val tokens = used.mapTo(ArrayList<CssNode>()) { it.part.node }
        iehack?.takeIf { it.isNotEmpty() }?.let {
            parts.add(it)
            tokens.add(IdentToken(it))
        }
        return MinDecl(
            property = name,
            value = parts.joinToString(" "),
            important = sides["top"]!!.important,
            parts = null,
            iehack = iehack.orEmpty(),
            tokens = tokens,
        )
    }
}

private const val OP_REPLACE = 1
private const val OP_REMOVE = 2

private class ShortDeclaration(
    val operation: Int,
    val block: MutableList<MinDecl>,
    val declaration: MinDecl,
    val shorthand: Trbl,
)

private fun processShorthandRule(
    rule: MinRule,
    shorts: HashMap<String, Trbl>,
    shortDeclarations: MutableList<ShortDeclaration>,
    lastShortSelectorIn: String?,
): String? {
    var lastShortSelector = lastShortSelectorIn
    val declarations = rule.declarations
    val selector = rule.selectors.first().id

    for (index in declarations.indices.reversed()) {
        val declaration = declarations[index]
        val property = declaration.property
        val key = MAIN_PROPERTY[property] ?: continue

        var operation = OP_REPLACE
        var shorthand: Trbl? = null

        if (lastShortSelector == null || selector == lastShortSelector) {
            shorts[key]?.let {
                operation = OP_REMOVE
                shorthand = it
            }
        }

        if (shorthand == null || !shorthand!!.add(property, declaration)) {
            operation = OP_REPLACE
            shorthand = Trbl(key)
            // if the value cannot be parsed, ignore it and break the shorthand chain
            if (!shorthand!!.add(property, declaration)) {
                lastShortSelector = null
                continue
            }
        }

        shorts[key] = shorthand!!
        shortDeclarations.add(ShortDeclaration(operation, declarations, declaration, shorthand!!))
        lastShortSelector = selector
    }

    return lastShortSelector
}

internal fun restructShorthand(root: MinBlock, indexer: DeclarationIndexer) {
    val stylesheetMap = HashMap<Int, HashMap<String, HashMap<String, Trbl>>>()
    val lastSelectorMap = HashMap<Int, String?>()
    val shortDeclarations = ArrayList<ShortDeclaration>()

    fun walkBlock(block: MinBlock) {
        for (index in block.children.indices.reversed()) {
            when (val node = block.children[index]) {
                is MinAtRule -> node.block?.let { walkBlock(it) }
                is MinRule -> {
                    val ruleMap = stylesheetMap.getOrPut(block.id) { HashMap() }
                    val ruleId = (node.pseudoSignature ?: "") + "|" + node.selectors.first().id
                    val shorts = ruleMap.getOrPut(ruleId) { HashMap() }
                    lastSelectorMap[block.id] =
                        processShorthandRule(node, shorts, shortDeclarations, lastSelectorMap[block.id])
                }
                else -> {}
            }
        }
    }
    walkBlock(root)

    for (item in shortDeclarations) {
        if (!item.shorthand.isOkToMinimize()) continue
        val position = item.block.indexOfFirst { it === item.declaration }
        if (position < 0) continue
        if (item.operation == OP_REPLACE) {
            item.block[position] = indexer.mark(item.shorthand.getDeclaration())
        } else {
            item.block.removeAt(position)
        }
    }
}

// ---------------------------------------------------------------------------
// 6. restructBlock
// ---------------------------------------------------------------------------

private val DONT_RESTRUCTURE = setOf("src")

private val DONT_MIX_VALUE = mapOf(
    "display" to Regex("""table|ruby|flex|-(flex)?box$|grid|contents|run-in""", RegexOption.IGNORE_CASE),
    "text-align" to Regex("""^(start|end|match-parent|justify-all)$""", RegexOption.IGNORE_CASE),
)

private val SAFE_VALUES = mapOf(
    "cursor" to setOf(
        "auto", "crosshair", "default", "move", "text", "wait", "help",
        "n-resize", "e-resize", "s-resize", "w-resize",
        "ne-resize", "nw-resize", "se-resize", "sw-resize",
        "pointer", "progress", "not-allowed", "no-drop", "vertical-text", "all-scroll",
        "col-resize", "row-resize",
    ),
    "overflow" to setOf("hidden", "visible", "scroll", "auto"),
    "position" to setOf("static", "relative", "absolute", "fixed"),
)

private val NEEDLESS_TABLE: Map<String, List<String>> = buildMap {
    put("border-width", listOf("border"))
    put("border-style", listOf("border"))
    put("border-color", listOf("border"))
    for (side in SIDES) {
        put("border-$side", listOf("border"))
        put("border-$side-width", listOf("border-$side", "border-width", "border"))
        put("border-$side-style", listOf("border-$side", "border-style", "border"))
        put("border-$side-color", listOf("border-$side", "border-color", "border"))
        put("margin-$side", listOf("margin"))
        put("padding-$side", listOf("padding"))
    }
    for (part in listOf("style", "variant", "weight", "size", "family")) put("font-$part", listOf("font"))
    for (part in listOf("type", "position", "image")) put("list-style-$part", listOf("list-style"))
}

private val IE_HACK_IDENT = Regex("""\\[09]""")

private class FingerprintState {
    val cache = HashMap<Int, String>()
    var nextRawId = 1
}

private fun collectFingerprint(
    tokens: List<CssNode>,
    realName: String,
    special: MutableSet<String>,
    state: Array<String>,
) {
    for (node in tokens) {
        when (node) {
            is ParenthesesBlock -> collectFingerprint(node.content, realName, special, state)
            is SquareBracketsBlock -> collectFingerprint(node.content, realName, special, state)
            is IdentToken -> {
                val name = node.value
                if (state[0].isEmpty()) state[0] = keywordVendor(name)
                IE_HACK_IDENT.find(name)?.let { state[1] = it.value }
                val safe = SAFE_VALUES[realName]
                val dontMix = DONT_MIX_VALUE[realName]
                if (safe != null) {
                    if (name !in safe) special.add(name)
                } else if (dontMix != null) {
                    if (dontMix.containsMatchIn(name)) special.add(name)
                }
            }
            is FunctionBlock -> {
                var name = node.name
                if (state[0].isEmpty()) state[0] = keywordVendor(name)
                if (name == "rect") {
                    // rect() has a comma form and a legacy space form; only equal forms may merge
                    val hasComma = node.arguments.any { it is LiteralToken && it.value == "," }
                    if (!hasComma) name = "rect-backward"
                }
                special.add("$name()")
                collectFingerprint(node.arguments, realName, special, state)
            }
            is DimensionToken -> {
                val unit = node.unit
                IE_HACK_IDENT.find(unit)?.let { state[1] = it.value }
                if (unit in setOf("rem", "vw", "vh", "vmin", "vmax", "vm")) special.add(unit)
            }
            else -> {}
        }
    }
}

private fun getPropertyFingerprint(
    propertyName: String,
    declaration: MinDecl,
    fingerprints: FingerprintState,
): String {
    val realName = PropertyDescriptor(propertyName).basename
    if (realName == "background") return "$propertyName:${declaration.value}"

    fingerprints.cache[declaration.id]?.let { return propertyName + it }

    // a Raw value is opaque: csso fingerprints it by its text, so two custom properties with
    // different values never look interchangeable
    declaration.rawValue?.let {
        val fingerprint = "!$it"
        fingerprints.cache[declaration.id] = fingerprint
        return propertyName + fingerprint
    }

    val special = LinkedHashSet<String>()
    val state = arrayOf("", "") // [vendorId, iehack]
    collectFingerprint(declaration.tokens, realName, special, state)
    val fingerprint = "!" + special.sorted().joinToString(",") + "|" + state[1] + state[0]

    fingerprints.cache[declaration.id] = fingerprint
    return propertyName + fingerprint
}

private class PropSlot(val block: MutableList<MinDecl>, val declaration: MinDecl)

private fun needless(
    props: MutableMap<String, PropSlot>,
    declaration: MinDecl,
    fingerprints: FingerprintState,
): PropSlot? {
    val property = PropertyDescriptor(declaration.property)
    val table = NEEDLESS_TABLE[property.basename] ?: return null
    for (entry in table) {
        val key = getPropertyFingerprint(property.prefix + entry, declaration, fingerprints)
        val prev = props[key]
        if (prev != null && (!declaration.important || prev.declaration.important)) return prev
    }
    return null
}

private fun processBlockRule(
    rule: MinRule,
    props: MutableMap<String, PropSlot>,
    fingerprints: FingerprintState,
) {
    val declarations = rule.declarations
    for (index in declarations.indices.reversed()) {
        if (index >= declarations.size) continue
        val declaration = declarations[index]
        val fingerprint = getPropertyFingerprint(declaration.property, declaration, fingerprints)
        val prev = props[fingerprint]

        if (prev != null && declaration.property !in DONT_RESTRUCTURE) {
            if (declaration.important && !prev.declaration.important) {
                props[fingerprint] = PropSlot(declarations, declaration)
                prev.block.removeAll { it === prev.declaration }
            } else {
                declarations.removeAt(index)
            }
        } else {
            val needlessPrev = needless(props, declaration, fingerprints)
            if (needlessPrev != null) {
                declarations.removeAt(index)
            } else {
                declaration.fingerprint = fingerprint
                props[fingerprint] = PropSlot(declarations, declaration)
            }
        }
    }
}

internal fun restructBlock(root: MinBlock) {
    val stylesheetMap = HashMap<Int, HashMap<String, MutableMap<String, PropSlot>>>()
    val fingerprints = FingerprintState()

    fun walkBlock(block: MinBlock) {
        var index = block.children.size - 1
        while (index >= 0) {
            val node = block.children[index]
            when (node) {
                is MinAtRule -> node.block?.let { walkBlock(it) }
                is MinRule -> {
                    val ruleMap = stylesheetMap.getOrPut(block.id) { HashMap() }
                    val ruleId = (node.pseudoSignature ?: "") + "|" + node.selectors.first().id
                    val props = ruleMap.getOrPut(ruleId) { LinkedHashMap() }
                    processBlockRule(node, props, fingerprints)
                    if (node.declarations.isEmpty()) block.children.removeAll { it === node }
                }
                else -> {}
            }
            val position = block.children.indexOfFirst { it === node }
            index = if (position >= 0) position - 1 else index - 1
        }
    }
    walkBlock(root)
}

// ---------------------------------------------------------------------------
// 7. mergeRuleset
// ---------------------------------------------------------------------------

internal fun mergeRuleset(block: MinBlock) {
    val children = block.children
    var index = 0
    while (index < children.size) {
        val node = children[index]
        if (node is MinAtRule) {
            node.block?.let { mergeRuleset(it) }
            index++
            continue
        }
        if (node !is MinRule) {
            index++
            continue
        }

        val selectors = node.selectors
        val declarations = node.declarations
        val nodeCompareMarker = selectors.first().compareMarker
        val skippedCompareMarkers = HashSet<String>()

        var cursor = index + 1
        while (cursor < children.size) {
            val next = children[cursor]
            if (next !is MinRule) {
                if (unsafeToSkipNode(next, selectors)) break
                cursor++
                continue
            }
            if (node.pseudoSignature != next.pseudoSignature) break

            val nextFirstSelector = next.selectors.first()
            val nextCompareMarker = nextFirstSelector.compareMarker
            if (nextCompareMarker in skippedCompareMarkers) break

            // try to join by selectors
            if (selectors.size == 1 && selectors.first().id == nextFirstSelector.id) {
                declarations.addAll(next.declarations)
                children.removeAt(cursor)
                continue
            }

            // try to join by properties
            if (isEqualDeclarations(declarations, next.declarations)) {
                val nextStr = nextFirstSelector.id
                var inserted = false
                for (position in selectors.indices) {
                    if (nextStr < selectors[position].id) {
                        selectors.add(position, nextFirstSelector)
                        inserted = true
                        break
                    }
                    if (position == selectors.size - 1) {
                        selectors.add(nextFirstSelector)
                        inserted = true
                        break
                    }
                }
                if (!inserted) selectors.add(nextFirstSelector)
                children.removeAt(cursor)
                continue
            }

            // go to the next rule when the current one can be skipped
            if (nextCompareMarker == nodeCompareMarker) break
            skippedCompareMarkers.add(nextCompareMarker)
            cursor++
        }

        index++
    }
}

// ---------------------------------------------------------------------------
// 8. restructRuleset
// ---------------------------------------------------------------------------

private fun calcSelectorLength(list: List<MinSelector>): Int =
    list.sumOf { it.id.length + 1 } - 1

private fun calcDeclarationsLength(tokens: List<MinDecl>): Int =
    tokens.sumOf { it.length } + tokens.size - 1

private fun collectDownMarkers(block: MinBlock, into: MutableSet<String>) {
    for (child in block.children) {
        when (child) {
            is MinRule -> for (selector in child.selectors) into.add(selector.compareMarker)
            is MinAtRule -> child.block?.let { collectDownMarkers(it, into) }
            else -> {}
        }
    }
}

private fun processRestructRule(children: MutableList<MinNode>, index: Int, avoidRulesMerge: Boolean) {
    val node = children[index] as MinRule
    val selectors = node.selectors
    val disallowDownMarkers = HashSet<String>()
    var allowMergeUp = true
    var allowMergeDown: Boolean

    var cursor = index - 1
    while (cursor >= 0) {
        val prev = children[cursor]

        if (prev !is MinRule) {
            val unsafe = unsafeToSkipNode(prev, selectors)
            if (!unsafe && prev is MinAtRule) {
                prev.block?.let { collectDownMarkers(it, disallowDownMarkers) }
            }
            if (unsafe) break
            cursor--
            continue
        }

        if (node.pseudoSignature != prev.pseudoSignature) break

        val prevSelectors = prev.selectors
        allowMergeDown = prevSelectors.none { it.compareMarker in disallowDownMarkers }

        // try the previous rule when the selectors have no equal specificity nor element selector
        if (!allowMergeDown && !allowMergeUp) break

        // try to join by selectors
        if (allowMergeUp && isEqualSelectors(prevSelectors, selectors)) {
            prev.declarations.addAll(node.declarations)
            children.removeAt(index)
            return
        }

        // try to join by properties
        val diff = compareDeclarations(node.declarations, prev.declarations)

        if (diff.eq.isNotEmpty()) {
            if (diff.ne1.isEmpty() && diff.ne2.isEmpty()) {
                // equal blocks
                if (allowMergeDown) {
                    addSelectors(selectors, prevSelectors)
                    children.removeAt(cursor)
                }
                return
            } else if (!avoidRulesMerge) {
                if (diff.ne1.isNotEmpty() && diff.ne2.isEmpty()) {
                    // the previous block is a subset of this one
                    val selectorLength = calcSelectorLength(selectors)
                    val blockLength = calcDeclarationsLength(diff.eq)
                    if (allowMergeUp && selectorLength < blockLength) {
                        addSelectors(prevSelectors, selectors)
                        node.declarations = ArrayList(diff.ne1)
                    }
                } else if (diff.ne1.isEmpty() && diff.ne2.isNotEmpty()) {
                    // this block is a subset of the previous one
                    val selectorLength = calcSelectorLength(prevSelectors)
                    val blockLength = calcDeclarationsLength(diff.eq)
                    if (allowMergeDown && selectorLength < blockLength) {
                        addSelectors(selectors, prevSelectors)
                        prev.declarations = ArrayList(diff.ne2)
                    }
                } else {
                    // extract the equal declarations into a rule of their own when that is smaller
                    val newSelectors = addSelectors(ArrayList(prevSelectors), selectors)
                    val newBlockLength = calcSelectorLength(newSelectors) + 2 // + curly braces
                    val blockLength = calcDeclarationsLength(diff.eq)

                    if (blockLength >= newBlockLength) {
                        val newRule = MinRule(newSelectors, ArrayList(diff.eq), node.pseudoSignature)
                        node.declarations = ArrayList(diff.ne1)
                        prev.declarations = ArrayList(diff.ne2overrided)
                        children.add(if (allowMergeUp) cursor else index, newRule)
                        return
                    }
                }
            }
        }

        if (allowMergeUp) {
            allowMergeUp = prevSelectors.none { prevSelector ->
                selectors.any { it.compareMarker == prevSelector.compareMarker }
            }
        }

        for (data in prevSelectors) disallowDownMarkers.add(data.compareMarker)
        cursor--
    }
}

internal fun restructRuleset(block: MinBlock, avoidRulesMerge: Boolean = false) {
    var index = block.children.size - 1
    while (index >= 0) {
        val node = block.children[index]
        when (node) {
            is MinAtRule -> node.block?.let { restructRuleset(it, it.avoidRulesMerge) }
            is MinRule -> processRestructRule(block.children, index, avoidRulesMerge)
            else -> {}
        }
        // csso walks a linked-list cursor the list keeps up to date; re-reading the node's own
        // position reproduces that after a removal or an insertion next to it
        val position = block.children.indexOfFirst { it === node }
        index = if (position >= 0) position - 1 else index - 1
    }
}

// ---------------------------------------------------------------------------
// entry point
// ---------------------------------------------------------------------------

/** Run csso's eight restructuring passes over [root] in place. */
internal fun restructure(root: MinBlock, indexer: DeclarationIndexer, forceMediaMerge: Boolean = false) {
    mergeAtrule(root, forceMediaMerge)
    initialMergeRuleset(root)
    disjoinRuleset(root)
    restructShorthand(root, indexer)
    restructBlock(root)
    mergeRuleset(root)
    restructRuleset(root, root.avoidRulesMerge)
}
