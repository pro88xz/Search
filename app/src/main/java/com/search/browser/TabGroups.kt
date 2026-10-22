package com.search.browser

import android.content.Context
import android.content.res.Configuration

/**
 * A tab group: a name and a colour the user picked, and the tabs that carry
 * its [id] (Tab.groupId). Kept in TabManager.groups; one with no tabs left
 * is dropped.
 */
class TabGroup(val id: Long, var name: String, var color: Int)

object TabGroups {

    val NAMES = arrayOf("Lavender", "Blue", "Teal", "Green", "Amber", "Coral", "Pink", "Grey")

    // One lightness and chroma for every hue, in OKLCH, so no colour shouts
    // over the others: L 0.58 on light cards (4:1 or better against white),
    // L 0.76 at a little less chroma on dark ones (7.8:1 against #17171A).
    // Hues 300, 262, 195, 150, 75, 35, 350, and a near-grey at 280.
    private val LIGHT = intArrayOf(
        0xFF8962C5.toInt(), 0xFF4777D2.toInt(), 0xFF008E8E.toInt(), 0xFF348F4F.toInt(),
        0xFFA66D00.toInt(), 0xFFC25237.toInt(), 0xFFB94F87.toInt(), 0xFF777989.toInt()
    )
    private val DARK = intArrayOf(
        0xFFBE9EF6.toInt(), 0xFF84B0FF.toInt(), 0xFF60C4C3.toInt(), 0xFF7CC58B.toInt(),
        0xFFDAA75C.toInt(), 0xFFF79279.toInt(), 0xFFED8EBC.toInt(), 0xFFAEB0BF.toInt()
    )

    private fun dark(c: Context) =
        (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** The colour [index] is drawn in, in the theme now showing. */
    fun color(c: Context, index: Int): Int {
        val i = index.mod(LIGHT.size)
        return if (dark(c)) DARK[i] else LIGHT[i]
    }

    /** The first colour no group is wearing yet, or the next in turn when all are. */
    fun freeColor(groups: List<TabGroup>): Int {
        val used = groups.map { it.color }.toSet()
        return (LIGHT.indices).firstOrNull { it !in used } ?: (groups.size % LIGHT.size)
    }
}
