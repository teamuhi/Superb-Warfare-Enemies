package com.teamuhi.superbcamera.client.ui.kit

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

/** One tab of a [TabRail]. */
class RailTab(val label: Component, val tooltip: Component? = null)

/**
 * A thin row of flat text tabs with an accent underline on the active one. The tabs are as wide as their text plus an
 * even share of whatever room is left, so short labels do not waste space.
 */
class TabRail(
    private val tabs: List<RailTab>,
    private val selected: () -> Int,
    private val onSelect: (Int) -> Unit
) : Widget() {

    /** Left edge of each cell plus the end of the last one, in the widget's coordinates. */
    private fun edges(ui: UiRoot): IntArray {
        val widths = tabs.map { ui.font.width(it.label.string) }
        val spare = (width - widths.sum()).coerceAtLeast(0)
        val edges = IntArray(tabs.size + 1)
        var cursor = x.toDouble()
        for (index in tabs.indices) {
            edges[index] = cursor.toInt()
            cursor += widths[index] + spare.toDouble() / tabs.size
        }
        edges[tabs.size] = x + width
        return edges
    }

    override fun render(g: GuiGraphics, ui: UiRoot, mx: Int, my: Int) {
        val edges = edges(ui)
        g.fill(x, y + height - 1, x + width, y + height, Ui.BORDER)
        val over = indexAt(ui, mx.toDouble(), my.toDouble())
        val current = selected()
        for ((index, tab) in tabs.withIndex()) {
            val left = edges[index]
            val right = edges[index + 1]
            if (index == over && index != current) {
                g.fill(left, y, right, y + height - 1, Ui.HOVER)
                g.fill(left, y, left + 1, y + height - 1, Ui.ACCENT)
            }
            val isCurrent = index == current
            Ui.centered(
                g, ui.font, tab.label.string, left, y, right - left, height - 1,
                if (isCurrent) Ui.ACCENT else if (index == over) Ui.TEXT else Ui.MUTED
            )
            if (isCurrent) g.fill(left, y + height - 2, right, y + height, Ui.ACCENT)
            if (index == over) ui.tooltip(tab.tooltip)
        }
    }

    private fun indexAt(ui: UiRoot, mx: Double, my: Double): Int {
        if (!contains(mx, my)) return -1
        val edges = edges(ui)
        for (index in tabs.indices) if (mx < edges[index + 1]) return index
        return tabs.size - 1
    }

    override fun mouseClicked(ui: UiRoot, mx: Double, my: Double, button: Int): Boolean {
        if (button != 0 || !contains(mx, my)) return false
        val index = indexAt(ui, mx, my)
        if (index >= 0 && index != selected()) onSelect(index)
        return true
    }
}
