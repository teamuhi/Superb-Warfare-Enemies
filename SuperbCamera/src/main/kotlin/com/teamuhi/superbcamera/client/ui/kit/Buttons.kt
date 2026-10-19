package com.teamuhi.superbcamera.client.ui.kit

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth

/** How a [FlatButton] is filled. */
enum class ButtonStyle {
    /** Outlined, no fill: the default. */
    GHOST,

    /** No outline and no fill, only the hover highlight: for glyph buttons in a bar. */
    BARE,

    /** The single filled call-to-action (Done). */
    ACCENT
}

/**
 * A flat button with a text or glyph label. Hover is a lighter background with a 1 px accent edge on the left; a
 * disabled button draws its text at 40% alpha and ignores clicks (but still shows its tooltip).
 */
class FlatButton(
    private val label: () -> String,
    private val tooltip: () -> String? = { null },
    private val enabled: () -> Boolean = { true },
    private val style: ButtonStyle = ButtonStyle.GHOST,
    private val textColor: () -> Int = { Ui.TEXT },
    private val onPress: () -> Unit
) : Widget() {

    constructor(
        label: Component,
        tooltip: Component? = null,
        enabled: () -> Boolean = { true },
        style: ButtonStyle = ButtonStyle.GHOST,
        onPress: () -> Unit
    ) : this({ label.string }, { tooltip?.string }, enabled, style, { Ui.TEXT }, onPress)

    override fun render(g: GuiGraphics, ui: UiRoot, mx: Int, my: Int) {
        val active = enabled()
        val hovered = contains(mx.toDouble(), my.toDouble())
        when (style) {
            ButtonStyle.ACCENT -> {
                g.fill(x, y, x + width, y + height, Ui.forState(if (hovered && active) Ui.ACCENT_HOVER else Ui.ACCENT, active))
            }
            else -> {
                if (hovered && active) Ui.hoverFill(g, x, y, width, height)
                else if (style == ButtonStyle.GHOST) Ui.outline(g, x, y, width, height, Ui.BORDER)
            }
        }
        val color = if (style == ButtonStyle.ACCENT) Ui.ON_ACCENT else Ui.forState(textColor(), active)
        val text = Ui.fit(ui.font, label(), width - 4)
        Ui.centered(g, ui.font, text, x, y, width, height, if (style == ButtonStyle.ACCENT && !active) Ui.fade(color, 0.5f) else color)
        if (hovered) ui.tooltip(tooltip())
    }

    override fun mouseClicked(ui: UiRoot, mx: Double, my: Double, button: Int): Boolean {
        if (button != 0 || !contains(mx, my)) return false
        if (enabled()) onPress()
        return true
    }
}

/**
 * An on / off row: the label on the left and a pill switch on the right. [onToggle] receives the new value and is
 * responsible for storing it (and for recording undo history).
 */
class Toggle(
    private val label: Component,
    private val tooltip: Component?,
    private val value: () -> Boolean,
    private val enabled: () -> Boolean = { true },
    private val onToggle: (Boolean) -> Unit
) : Widget() {

    override fun render(g: GuiGraphics, ui: UiRoot, mx: Int, my: Int) {
        val active = enabled()
        val on = value()
        val hovered = contains(mx.toDouble(), my.toDouble())
        if (hovered && active) Ui.hoverFill(g, x, y, width, height)
        Ui.text(g, ui.font, Ui.fit(ui.font, label.string, width - PILL_WIDTH - 10), x + 4, y, height, Ui.forState(Ui.TEXT, active))

        val pillX = x + width - PILL_WIDTH - 4
        val pillY = y + (height - PILL_HEIGHT) / 2
        val track = if (on) Ui.ACCENT else Ui.RAISED
        g.fill(pillX, pillY, pillX + PILL_WIDTH, pillY + PILL_HEIGHT, Ui.forState(track, active))
        if (!on) Ui.outline(g, pillX, pillY, PILL_WIDTH, PILL_HEIGHT, Ui.forState(Ui.BORDER, active))
        val knobX = if (on) pillX + PILL_WIDTH - KNOB - 1 else pillX + 1
        g.fill(knobX, pillY + 1, knobX + KNOB, pillY + 1 + KNOB, Ui.forState(if (on) Ui.ON_ACCENT else Ui.MUTED, active))
        if (hovered) ui.tooltip(tooltip)
    }

    override fun mouseClicked(ui: UiRoot, mx: Double, my: Double, button: Int): Boolean {
        if (button != 0 || !contains(mx, my)) return false
        if (enabled()) onToggle(!value())
        return true
    }

    private companion object {
        const val PILL_WIDTH = 16
        const val PILL_HEIGHT = 8
        const val KNOB = PILL_HEIGHT - 2
    }
}

/** One choice of a [Segmented] control. */
class Segment(
    val label: Component,
    val tooltip: Component? = null,
    val enabled: () -> Boolean = { true }
)

/**
 * Choices side by side with the selected one highlighted (lighter background, accent text and an accent underline).
 * [onSelect] gets the index; re-selecting the current one does nothing.
 */
class Segmented(
    private val segments: List<Segment>,
    private val selected: () -> Int,
    private val onSelect: (Int) -> Unit
) : Widget() {

    private fun cellLeft(index: Int): Int = x + width * index / segments.size
    private fun cellRight(index: Int): Int = x + width * (index + 1) / segments.size

    private fun indexAt(mx: Double): Int =
        Mth.floor((mx - x) * segments.size / width.coerceAtLeast(1)).coerceIn(0, segments.size - 1)

    override fun render(g: GuiGraphics, ui: UiRoot, mx: Int, my: Int) {
        g.fill(x, y, x + width, y + height, Ui.RAISED)
        val current = selected()
        val over = if (contains(mx.toDouble(), my.toDouble())) indexAt(mx.toDouble()) else -1
        for ((index, segment) in segments.withIndex()) {
            val left = cellLeft(index)
            val right = cellRight(index)
            val active = segment.enabled()
            val isCurrent = index == current
            if (isCurrent) {
                g.fill(left, y, right, y + height, Ui.HOVER)
                g.fill(left, y + height - 1, right, y + height, Ui.ACCENT)
            } else if (index == over && active) {
                g.fill(left, y, right, y + height, Ui.HOVER)
                g.fill(left, y, left + 1, y + height, Ui.ACCENT)
            }
            val color = if (isCurrent) Ui.ACCENT else Ui.TEXT
            val text = Ui.fit(ui.font, segment.label.string, right - left - 2)
            Ui.centered(g, ui.font, text, left, y, right - left, height, Ui.forState(if (isCurrent || index == over) color else Ui.MUTED, active))
            if (index == over) ui.tooltip(segment.tooltip)
        }
    }

    override fun mouseClicked(ui: UiRoot, mx: Double, my: Double, button: Int): Boolean {
        if (button != 0 || !contains(mx, my)) return false
        val index = indexAt(mx)
        val segment = segments[index]
        if (segment.enabled() && index != selected()) onSelect(index)
        return true
    }
}

/** A line of wrapped text; its height follows the wrapped line count. */
class TextBlock(
    private val text: () -> Component,
    private val color: () -> Int = { Ui.MUTED },
    private val lineGap: Int = 1
) : Widget() {

    private var cachedKey: String? = null
    private var cachedWidth = -1
    private var lines: List<net.minecraft.util.FormattedCharSequence> = emptyList()

    /** Height needed for the current text at [forWidth]. Call before placing, to lay out a column. */
    fun measure(ui: UiRoot, forWidth: Int): Int {
        wrap(ui, forWidth)
        return lines.size * (ui.font.lineHeight + lineGap)
    }

    private fun wrap(ui: UiRoot, forWidth: Int) {
        val component = text()
        val key = component.string
        if (key == cachedKey && forWidth == cachedWidth) return
        cachedKey = key
        cachedWidth = forWidth
        lines = ui.font.split(component, forWidth.coerceAtLeast(8))
    }

    override fun render(g: GuiGraphics, ui: UiRoot, mx: Int, my: Int) {
        wrap(ui, width)
        val step = ui.font.lineHeight + lineGap
        for ((index, line) in lines.withIndex()) {
            g.drawString(ui.font, line, x, y + index * step, color(), false)
        }
    }
}
