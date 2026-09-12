package io.github.tobsef.svgo

/**
 * AST visitor engine. Port of `lib/util/visit.js`.
 *
 * Returning [VISIT_SKIP] from an `enter` callback skips descending into that node's children.
 */
public val VISIT_SKIP: Any = Any()

public class Callbacks<T : XastNode>(
    public val enter: ((node: T, parentNode: XastParent) -> Any?)? = null,
    public val exit: ((node: T, parentNode: XastParent) -> Any?)? = null,
)

public class RootCallbacks(
    public val enter: ((node: Root) -> Any?)? = null,
    public val exit: ((node: Root) -> Any?)? = null,
)

/** A set of per-node-type callbacks, as returned by a plugin's `fn`. */
public class Visitor(
    public val root: RootCallbacks? = null,
    public val element: Callbacks<Element>? = null,
    public val text: Callbacks<Text>? = null,
    public val comment: Callbacks<Comment>? = null,
    public val cdata: Callbacks<Cdata>? = null,
    public val instruction: Callbacks<Instruction>? = null,
    public val doctype: Callbacks<Doctype>? = null,
)

public fun visit(node: XastNode, visitor: Visitor, parentNode: XastParent? = null) {
    var skip = false
    when (node) {
        is Root -> visitor.root?.enter?.let { skip = it(node) === VISIT_SKIP }
        is Element -> visitor.element?.enter?.let { skip = it(node, parentNode!!) === VISIT_SKIP }
        is Text -> visitor.text?.enter?.let { skip = it(node, parentNode!!) === VISIT_SKIP }
        is Comment -> visitor.comment?.enter?.let { skip = it(node, parentNode!!) === VISIT_SKIP }
        is Cdata -> visitor.cdata?.enter?.let { skip = it(node, parentNode!!) === VISIT_SKIP }
        is Instruction -> visitor.instruction?.enter?.let { skip = it(node, parentNode!!) === VISIT_SKIP }
        is Doctype -> visitor.doctype?.enter?.let { skip = it(node, parentNode!!) === VISIT_SKIP }
    }
    if (skip) return

    // NOTE: iterate the live children reference (not a copy). detachNodeFromParent reassigns
    // `children` to a *new* filtered list rather than mutating in place, so an in-progress
    // iterator over the old list is unaffected -- matching JS `for...of` semantics exactly.
    if (node is Root) {
        val children = node.children
        for (child in children) visit(child, visitor, node)
    }

    if (node is Element) {
        // visit element children only if the node is still attached to its parent
        if (parentNode!!.children.containsIdentity(node)) {
            val children = node.children
            for (child in children) visit(child, visitor, node)
        }
    }

    when (node) {
        is Root -> visitor.root?.exit?.invoke(node)
        is Element -> visitor.element?.exit?.invoke(node, parentNode!!)
        is Text -> visitor.text?.exit?.invoke(node, parentNode!!)
        is Comment -> visitor.comment?.exit?.invoke(node, parentNode!!)
        is Cdata -> visitor.cdata?.exit?.invoke(node, parentNode!!)
        is Instruction -> visitor.instruction?.exit?.invoke(node, parentNode!!)
        is Doctype -> visitor.doctype?.exit?.invoke(node, parentNode!!)
    }
}
