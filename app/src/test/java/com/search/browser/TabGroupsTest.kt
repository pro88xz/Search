package com.search.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tab groups' bookkeeping (TabManager): where tabs go as they join and leave, and empty groups going. */
class TabGroupsTest {
    private fun ids(tm: TabManager) = tm.tabs.joinToString(",") { it.title }

    @Test fun groupsStayTogetherAndPrune() {
        val tm = TabManager()
        val (a, b, c, d, e) = listOf("a", "b", "c", "d", "e").map { n -> tm.createTab("https://$n.test/").also { it.title = n } }
        val g = tm.newGroup("G", 1)
        tm.addToGroup(a, g)
        tm.addToGroup(d, g)          // d moves next to a
        assertEquals("a,d,b,c,e", ids(tm))
        tm.addToGroup(e, g)
        assertEquals("a,d,e,b,c", ids(tm))
        // A tab already beside the group stays where it is.
        val h = TabManager()
        val (x, y, z) = listOf("x", "y", "z").map { n -> h.createTab("https://$n.test/").also { it.title = n } }
        val g2 = h.newGroup("H", 2); h.addToGroup(y, g2); h.addToGroup(x, g2)
        assertEquals("x,y,z", ids(h))
        // Out of the group: just after its tabs.
        tm.removeFromGroup(a)
        assertEquals("d,e,a,b,c", ids(tm))
        assertEquals(listOf("d", "e"), tm.tabsIn(g).map { it.title })
        // The last tab leaving takes the group with it.
        tm.removeTab(d); tm.removeTab(e)
        assertEquals(0, tm.groups.size)
        assertEquals(null, tm.group(g.id))
    }

    @Test fun freeColourSkipsUsed() {
        val tm = TabManager()
        tm.newGroup("a", 0); tm.newGroup("b", 1)
        assertEquals(2, TabGroups.freeColor(tm.groups))
    }
}
