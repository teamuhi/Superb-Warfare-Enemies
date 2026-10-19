package com.teamuhi.superbcamera.client.ui.kit

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

/**
 * A collapsible header: `▾ Title` when open, `▸ Title` when closed, with a hairline underneath. The open state lives
 * with the caller ([open] / [onToggle]) so it survives page rebuilds.
 */
class Section(
    private val title: Component,
    private val open: () -> Boolean,
    private val onToggle: () -> Unit
) : Widget() {

    override fun render(g: GuiGraphics, ui: UiRoot, mx: Int, my: Int) {
        val hovered = contains(mx.toDouble(), my.toDouble())
        if (hovered) Ui.hoverFill(g, x, y, width, height)
        val glyph = if (open()) "▾" else "▸"
        Ui.text(g, ui.font, glyph, x + 4, y, height, Ui.ACCENT)
        Ui.text(g, ui.font, Ui.fit(ui.font, title.string.uppercase(), width - 20), x + 14, y, height, if (hovered) Ui.TEXT else Ui.MUTED)
        g.fill(x, y + height - 1, x + width, y + height, Ui.BORDER)
    }

    override fun mouseClicked(ui: UiRoot, mx: Double, my: Double, button: Int): Boolean {
        if (button != 0 || !contains(mx, my)) return false
        onToggle()
        return true
    }
}
