package com.teamuhi.superbcamera.client.ui.kit

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Describes a numeric property for a [NumberField]: where the value lives, its limits and how it is stepped.
 *
 * @param min hard lower clamp, applied to every write
 * @param max hard upper clamp
 * @param barMin start of the range shown by the subtle fill bar, or null for no bar (unbounded values)
 * @param barMax end of the bar range
 * @param decimals digits shown after the point
 * @param dragPerUnit value change per panel unit of horizontal drag
 * @param wheelStep value change per wheel notch
 * @param snap step used while Ctrl is held (1 block, 5 degrees)
 * @param default value the per-row reset button restores, or null when there is no reset
 */
class NumberSpec(
    val get: () -> Double,
    val set: (Double) -> Unit,
    val min: Double,
    val max: Double,
    val barMin: Double? = null,
    val barMax: Double? = null,
    val decimals: Int = 2,
    val dragPerUnit: Double = 0.05,
    val wheelStep: Double = 0.1,
    val snap: Double = 1.0,
    val default: Double? = null,
    val unit: String = ""
)

/**
 * A Blender-style number field.
 *
 * - Press and drag horizontally to scrub (Shift = fine, x0.1; Ctrl = snap).
 * - A click without a drag opens text editing; Enter or a click elsewhere commits, Esc reverts.
 * - The wheel over the value nudges it.
 * - A small reset glyph appears on hover when the value differs from its default.
 *
 * [beginEdit] runs before every user change with a key that lets the caller coalesce a burst (wheel notches) into one
 * undo step; scrubbing and typed values pass null (always a new step).
 */
class NumberField(
    private val font: Font,
    private val label: String,
    private val labelColor: Int,
    private val labelWidth: Int,
    private val spec: NumberSpec,
    private val tooltip: Component?,
    private val resetTooltip: Component?,
    private val beginEdit: (Any?) -> Unit
) : Widget() {

    private var editor: TextField? = null
    private var pressed = false
    private var scrubbing = false
    private var pressTravel = 0.0
    private var scrubRaw = 0.0

    override val typing: Boolean get() = editor != null

    private val valueX: Int get() = x + labelWidth
    private val valueWidth: Int get() = width - labelWidth

    private fun canReset(): Boolean = spec.default != null && abs(spec.get() - spec.default) > RESET_EPSILON

    /** The reset glyph's hit area: the right end of the value box. */
    private fun inReset(mx: Double): Boolean = canReset() && mx >= x + width - RESET_WIDTH

    private fun write(value: Double) {
        if (!value.isFinite()) return
        spec.set(value.coerceIn(spec.min, spec.max))
    }

    // ------------------------------------------------------------------------------------------------ drawing

    override fun render(g: GuiGraphics, ui: UiRoot, mx: Int, my: Int) {
        val hovered = contains(mx.toDouble(), my.toDouble())
        g.fill(x, y, x + width, y + height, Ui.RAISED)

        // Axis strip + label.
        g.fill(x, y, x + 2, y + height, labelColor)
        Ui.text(g, ui.font, Ui.fit(ui.font, label, labelWidth - 6), x + 5, y, height, Ui.MUTED)

        val editing = editor
        if (editing != null) {
            editing.place(valueX, y, valueWidth, height)
            editing.render(g, ui, mx, my)
            return
        }

        val value = spec.get()
        val barMin = spec.barMin
        val barMax = spec.barMax
        if (barMin != null && barMax != null && barMax > barMin) {
            val fraction = ((value - barMin) / (barMax - barMin)).coerceIn(0.0, 1.0)
            g.fill(valueX, y, valueX + (valueWidth * fraction).toInt(), y + height, Ui.ACCENT_FILL)
        }
        if (hovered || scrubbing) {
            g.fill(valueX, y, valueX + valueWidth, y + height, 0x18FFFFFF)
            g.fill(x, y, x + 1, y + height, Ui.ACCENT)
        }
        Ui.centered(g, ui.font, format(value, spec.decimals) + spec.unit, valueX, y, valueWidth, height, Ui.TEXT)

        if (hovered && canReset()) {
            val resetX = x + width - RESET_WIDTH
            val overReset = mx >= resetX
            Ui.centered(g, ui.font, RESET_GLYPH, resetX, y, RESET_WIDTH, height, if (overReset) Ui.ACCENT else Ui.MUTED)
            if (overReset) ui.tooltip(resetTooltip) else ui.tooltip(tooltip)
        } else if (hovered) {
            ui.tooltip(tooltip)
        }
    }

    // ------------------------------------------------------------------------------------------------ input

    override fun mouseClicked(ui: UiRoot, mx: Double, my: Double, button: Int): Boolean {
        if (!contains(mx, my)) return false
        val editing = editor
        if (editing != null) {
            editing.place(valueX, y, valueWidth, height)
            // The text field keeps the focus when it was hit; a click on the label strip lets the root blur (commit).
            editing.mouseClicked(ui, mx, my, button)
            return true
        }
        if (button != 0) return false
        if (inReset(mx)) {
            beginEdit(null)
            write(spec.default ?: return true)
            return true
        }
        pressed = true
        scrubbing = false
        pressTravel = 0.0
        scrubRaw = spec.get()
        return true
    }

    override fun mouseDragged(ui: UiRoot, mx: Double, my: Double, button: Int, dx: Double, dy: Double): Boolean {
        if (!pressed) return false
        pressTravel += abs(dx)
        if (!scrubbing) {
            if (pressTravel < SCRUB_THRESHOLD) return true
            scrubbing = true
            beginEdit(null)
            scrubRaw = spec.get()
        }
        scrubRaw += dx * spec.dragPerUnit * (if (Screen.hasShiftDown()) FINE else 1.0)
        write(if (Screen.hasControlDown()) snapTo(scrubRaw, spec.snap) else scrubRaw)
        return true
    }

    override fun mouseReleased(ui: UiRoot, mx: Double, my: Double, button: Int): Boolean {
        if (!pressed) return false
        pressed = false
        val wasScrub = scrubbing
        scrubbing = false
        if (!wasScrub) beginTextEdit(ui)
        return true
    }

    override fun mouseScrolled(ui: UiRoot, mx: Double, my: Double, delta: Double): Boolean {
        if (editor != null || mx < valueX || delta == 0.0) return false
        beginEdit(this)
        val step = spec.wheelStep * (if (Screen.hasShiftDown()) FINE else 1.0)
        write(roundTo(spec.get() + (if (delta > 0) step else -step), spec.decimals + 1))
        return true
    }

    override fun keyPressed(ui: UiRoot, keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val editing = editor ?: return false
        when (keyCode) {
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                commit()
                ui.blur()
                return true
            }
            GLFW.GLFW_KEY_ESCAPE -> {
                editor = null
                ui.blur()
                return true
            }
        }
        return editing.keyPressed(ui, keyCode, scanCode, modifiers)
    }

    override fun charTyped(ui: UiRoot, codePoint: Char, modifiers: Int): Boolean =
        editor?.charTyped(ui, codePoint, modifiers) ?: false

    override fun onBlur(ui: UiRoot) {
        // A click elsewhere confirms what was typed (Esc has already dropped the editor).
        commit()
    }

    override fun tick() {
        editor?.tick()
    }

    private fun beginTextEdit(ui: UiRoot) {
        val text = TextField(font, Component.empty(), MAX_TEXT)
        text.setFilter { it.all { c -> c.isDigit() || c == '-' || c == '.' || c == ',' || c == '+' || c == 'e' || c == 'E' } }
        text.value = format(spec.get(), spec.decimals).trimEnd('0').trimEnd('.').ifEmpty { "0" }
        text.place(valueX, y, valueWidth, height)
        editor = text
        ui.focus(this)
        text.onFocus(ui)
        text.selectAll()
    }

    /** Applies the typed text if it parses; an unparsable entry is simply dropped. */
    private fun commit() {
        val text = editor ?: return
        editor = null
        val parsed = text.value.trim().replace(',', '.').toDoubleOrNull() ?: return
        if (!parsed.isFinite()) return
        beginEdit(null)
        write(parsed)
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private companion object {
        const val SCRUB_THRESHOLD = 2.0
        const val FINE = 0.1
        const val RESET_WIDTH = 10
        const val RESET_GLYPH = "↺"
        const val RESET_EPSILON = 1e-6
        const val MAX_TEXT = 12

        fun snapTo(value: Double, step: Double): Double = if (step > 0.0) (value / step).roundToLong() * step else value

        fun roundTo(value: Double, decimals: Int): Double {
            val factor = Math.pow(10.0, decimals.toDouble())
            return (value * factor).roundToLong() / factor
        }

        /** Fixed decimals, never "-0.00". */
        fun format(value: Double, decimals: Int): String {
            val text = String.format(Locale.ROOT, "%.${decimals}f", value)
            return if (text.startsWith("-") && text.all { it == '-' || it == '0' || it == '.' }) text.substring(1) else text
        }
    }
}
