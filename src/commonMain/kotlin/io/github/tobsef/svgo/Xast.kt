package io.github.tobsef.svgo

/**
 * XAST (XML Abstract Syntax Tree) node model.
 *
 * Mirrors the plain-object node model used by SVGO's JavaScript implementation (`lib/types.ts`).
 * Nodes are mutable and compared by identity (they are used as map/set keys throughout the
 * plugins), so none of them override `equals`/`hashCode`.
 */
public sealed interface XastNode {
    /** Node kind: `root`, `element`, `text`, `comment`, `cdata`, `instruction` or `doctype`. */
    public val type: String
}

/** A node that can contain children ([Root] or [Element]). */
public sealed interface XastParent : XastNode {
    public var children: MutableList<XastChild>
}

/** A node that can appear as a child of a [XastParent]. */
public sealed interface XastChild : XastNode

public class Root(
    override var children: MutableList<XastChild> = mutableListOf(),
) : XastParent {
    override val type: String get() = "root"
}

public class Element(
    public var name: String,
    /**
     * Attribute insertion order is preserved, matching the JS object property ordering that SVGO
     * relies on. A `null` value denotes a valueless attribute (serialized without `="..."`).
     */
    public var attributes: MutableMap<String, String?> = LinkedHashMap(),
    override var children: MutableList<XastChild> = mutableListOf(),
) : XastParent, XastChild {
    override val type: String get() = "element"

    /** Cache used by `path2js`/`js2path` (mirrors the `pathJS` property stashed on JS nodes). */
    internal var pathJS: MutableList<PathItem>? = null
}

public class Text(public var value: String) : XastChild {
    override val type: String get() = "text"
}

public class Comment(public var value: String) : XastChild {
    override val type: String get() = "comment"
}

public class Cdata(public var value: String) : XastChild {
    override val type: String get() = "cdata"
}

public class Instruction(public var name: String, public var value: String) : XastChild {
    override val type: String get() = "instruction"
}

public class Doctype(
    public var name: String,
    public var data: MutableMap<String, String>,
) : XastChild {
    override val type: String get() = "doctype"
}

/** Remove [node] from [parentNode]'s children without breaking in-progress iteration. */
public fun detachNodeFromParent(node: XastNode, parentNode: XastParent) {
    parentNode.children = parentNode.children.filterTo(ArrayList()) { it !== node }
}

/** Index of [node] within this list, compared by identity (`-1` when absent). */
internal fun <T> List<T>.indexOfIdentity(node: Any?): Int {
    for (i in indices) if (this[i] === node) return i
    return -1
}

/** `true` when this list contains [node] by identity. */
internal fun <T> List<T>.containsIdentity(node: Any?): Boolean = indexOfIdentity(node) >= 0

/**
 * A true snapshot of a map's entries.
 *
 * `entries.toList()` looks like a snapshot but keeps *live* entry views: on Kotlin/JS and
 * Kotlin/Native, reading `key`/`value` after the map has been modified throws
 * `ConcurrentModificationException`. Plugins routinely remove attributes while walking them, so
 * they must materialize the pairs up front.
 */
internal fun <K, V> Map<K, V>.entryList(): List<Pair<K, V>> = entries.map { it.key to it.value }
