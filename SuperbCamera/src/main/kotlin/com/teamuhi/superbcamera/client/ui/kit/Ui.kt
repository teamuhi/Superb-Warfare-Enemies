package com.teamuhi.superbcamera.client.ui.kit

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics

/**
 * Palette and drawing helpers of the editor's widget kit.
 *
 * Minecraft has no light / dark theme and the panel floats over the live world, so contrast comes from a translucent
 * near-black base with light text and one warm accent that stays readable on blue sky as well as in a dark cave.
 * Everything is drawn flat with [GuiGraphics.fill]: no vanilla button textures anywhere.
 */
object Ui {
    const val SURFACE = 0xD0141619.toInt()
    const val RAISED = 0xFF1E2126.toInt()
    const val HOVER = 0xFF2A2E35.toInt()
    const val BORDER = 0x40FFFFFF
    const val TEXT = 0xFFE8E8E8.toInt()
    const val MUTED = 0xFF8A9099.toInt()
    const val ACCENT = 0xFFFFB84D.toInt()
    const val ACCENT_HOVER = 0xFFFFCB7A.toInt()
    const val ACCENT_FILL = 0x30FFB84D
    const val ON_ACCENT = 0xFF14161A.toInt()
    const val DANGER = 0xFFFF6B6B.toInt()
    const val SCRIM = 0x90000000.toInt()

    // Blender's axis colours.
    const val AXIS_X = 0xFFFF5C5C.toInt()
    const val AXIS_Y = 0xFF8BDC5A.toInt()
    const val AXIS_Z = 0xFF5C9DFF.toInt()

    /** Height of a standard control row. */
    const val ROW = 14

    /** Alpha applied to the text of a disabled control. */
    private const val DISABLED_ALPHA = 0.4f

    /** [color] with its alpha scaled by [factor]. */
    fun fade(color: Int, factor: Float): Int {
        val alpha = ((color ushr 24) * factor).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (color and 0xFFFFFF)
    }

    /** [color], dimmed when the control is disabled. */
    fun forState(color: Int, enabled: Boolean): Int = if (enabled) color else fade(color, DISABLED_ALPHA)

    /** [text] cut to [maxWidth] pixels with a trailing ellipsis. */
    fun fit(font: Font, text: String, maxWidth: Int): String {
        if (maxWidth <= 0) return ""
        if (font.width(text) <= maxWidth) return text
        val ellipsis = "…"
        return font.plainSubstrByWidth(text, (maxWidth - font.width(ellipsis)).coerceAtLeast(0)) + ellipsis
    }

    /** Draws [text] without a shadow (flat look), vertically centred in a row of [height] starting at [y]. */
    fun text(g: GuiGraphics, font: Font, text: String, x: Int, y: Int, height: Int, color: Int) {
        g.drawString(font, text, x, y + (height - font.lineHeight + 1) / 2, color, false)
    }

    /** [text] centred horizontally in `[x, x + width)` and vertically in the row. */
    fun centered(g: GuiGraphics, font: Font, text: String, x: Int, y: Int, width: Int, height: Int, color: Int) {
        text(g, font, text, x + (width - font.width(text)) / 2, y, height, color)
    }

    /** A 1 px outline around the rectangle. */
    fun outline(g: GuiGraphics, x: Int, y: Int, width: Int, height: Int, color: Int) {
        g.fill(x, y, x + width, y + 1, color)
        g.fill(x, y + height - 1, x + width, y + height, color)
        g.fill(x, y + 1, x + 1, y + height - 1, color)
        g.fill(x + width - 1, y + 1, x + width, y + height - 1, color)
    }

    /** The hover look shared by all controls: a lighter background plus a 1 px accent edge on the left. */
    fun hoverFill(g: GuiGraphics, x: Int, y: Int, width: Int, height: Int) {
        g.fill(x, y, x + width, y + height, HOVER)
        g.fill(x, y, x + 1, y + height, ACCENT)
    }

    /** A filled circle of [radius] built from scanlines (panel units are whole pixels, so this stays crisp). */
    fun disc(g: GuiGraphics, cx: Int, cy: Int, radius: Int, color: Int) {
        for (dy in -radius..radius) {
            val half = Math.sqrt((radius * radius - dy * dy).toDouble()).toInt()
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color)
        }
    }

    /** A circle outline of [radius] with a hollow centre. */
    fun ring(g: GuiGraphics, cx: Int, cy: Int, radius: Int, color: Int) {
        val inner = radius - 1
        for (dy in -radius..radius) {
            val outerHalf = Math.sqrt((radius * radius - dy * dy).toDouble()).toInt()
            val innerHalf = if (kotlin.math.abs(dy) >= inner) -1 else Math.sqrt((inner * inner - dy * dy).toDouble()).toInt()
            if (innerHalf < 0) {
                g.fill(cx - outerHalf, cy + dy, cx + outerHalf + 1, cy + dy + 1, color)
            } else {
                g.fill(cx - outerHalf, cy + dy, cx - innerHalf, cy + dy + 1, color)
                g.fill(cx + innerHalf + 1, cy + dy, cx + outerHalf + 1, cy + dy + 1, color)
            }
        }
    }
}
