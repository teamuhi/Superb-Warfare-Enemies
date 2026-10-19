package com.teamuhi.superbcamera.client.ui

import com.teamuhi.superbcamera.client.ui.kit.Ui
import com.teamuhi.superbcamera.client.ui.kit.UiRoot
import com.teamuhi.superbcamera.client.ui.kit.Widget
import net.minecraft.client.gui.GuiGraphics
import java.util.UUID

/**
 * One entry of the Targets list: a compact two-line row (name and distance, then the type id) with a thin health bar
 * along the bottom. The selected row has an accent edge; clicking a row calls [onPick].
 */
class TargetRow(
    private val info: TargetInfo,
    private val selectedUuid: () -> UUID?,
    private val onPick: (TargetInfo) -> Unit
) : Widget() {

    override fun render(g: GuiGraphics, ui: UiRoot, mx: Int, my: Int) {
        val font = ui.font
        val selected = selectedUuid() == info.uuid
        val hovered = contains(mx.toDouble(), my.toDouble())
        g.fill(x, y, x + width, y + height, if (hovered) Ui.HOVER else Ui.RAISED)
        if (selected) g.fill(x, y, x + 2, y + height, Ui.ACCENT)
        else if (hovered) g.fill(x, y, x + 1, y + height, Ui.ACCENT)

        val distance = "${info.distance.toInt()} m"
        val distanceWidth = font.width(distance)
        g.drawString(font, distance, x + width - distanceWidth - 3, y + 3, Ui.MUTED, false)
        g.drawString(font, Ui.fit(font, info.name, width - distanceWidth - 12), x + 5, y + 3, if (selected) Ui.ACCENT else Ui.TEXT, false)
        g.drawString(font, Ui.fit(font, info.typeId, width - 10), x + 5, y + 12, Ui.MUTED, false)

        val health = info.health
        val maxHealth = info.maxHealth
        if (health != null && maxHealth != null && maxHealth > 0f) {
            val fraction = (health / maxHealth).coerceIn(0f, 1f)
            val barTop = y + height - 2
            g.fill(x + 5, barTop, x + width - 3, barTop + 1, Ui.SCRIM)
            g.fill(x + 5, barTop, x + 5 + ((width - 8) * fraction).toInt(), barTop + 1, healthColor(fraction))
        }
        if (hovered) ui.tooltip(info.typeId)
    }

    override fun mouseClicked(ui: UiRoot, mx: Double, my: Double, button: Int): Boolean {
        if (button != 0 || !contains(mx, my)) return false
        onPick(info)
        return true
    }

    private fun healthColor(fraction: Float): Int = when {
        fraction > 0.5f -> 0xFF55FF55.toInt()
        fraction > 0.25f -> 0xFFFFFF55.toInt()
        else -> 0xFFFF5555.toInt()
    }

    companion object {
        const val HEIGHT = 23
    }
}
