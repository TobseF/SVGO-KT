package io.github.tobsef.svgo

import kotlin.test.Test
import kotlin.test.assertEquals

/** Port of `lib/xast.test.js` (visitor behavior). */
class VisitTest {

    private fun element(name: String, children: List<XastChild> = emptyList()) =
        Element(name, LinkedHashMap(), children.toMutableList())

    private fun tree() = Root(
        mutableListOf(
            element("g", listOf(element("rect"), element("circle"))),
            element("ellipse"),
        ),
    )

    @Test
    fun entersNodes() {
        val entered = ArrayList<String>()
        visit(
            tree(),
            Visitor(
                root = RootCallbacks(enter = { node -> entered.add(node.type) }),
                element = Callbacks(enter = { node, _ -> entered.add("${node.type}:${node.name}") }),
            ),
        )
        assertEquals(
            listOf("root", "element:g", "element:rect", "element:circle", "element:ellipse"),
            entered,
        )
    }

    @Test
    fun exitsNodes() {
        val exited = ArrayList<String>()
        visit(
            tree(),
            Visitor(
                root = RootCallbacks(exit = { node -> exited.add(node.type) }),
                element = Callbacks(exit = { node, _ -> exited.add("${node.type}:${node.name}") }),
            ),
        )
        assertEquals(
            listOf("element:rect", "element:circle", "element:g", "element:ellipse", "root"),
            exited,
        )
    }

    @Test
    fun skipsChildrenOfDetachedNodes() {
        val ast = tree()
        val entered = ArrayList<String>()
        visit(
            ast,
            Visitor(
                element = Callbacks(
                    enter = { node, parentNode ->
                        entered.add(node.name)
                        if (node.name == "g") detachNodeFromParent(node, parentNode)
                    },
                ),
            ),
        )
        assertEquals(listOf("g", "ellipse"), entered)
        assertEquals(listOf("ellipse"), ast.children.map { (it as Element).name })
    }

    @Test
    fun skipsChildrenOnSkipSymbol() {
        val ast = tree()
        val entered = ArrayList<String>()
        visit(
            ast,
            Visitor(
                element = Callbacks(
                    enter = { node, _ ->
                        entered.add(node.name)
                        if (node.name == "g") VISIT_SKIP else null
                    },
                ),
            ),
        )
        assertEquals(listOf("g", "ellipse"), entered)
        assertEquals(listOf("g", "ellipse"), ast.children.map { (it as Element).name })
    }
}
