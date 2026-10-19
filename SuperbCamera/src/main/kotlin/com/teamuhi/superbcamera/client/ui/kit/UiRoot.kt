package com.teamuhi.superbcamera.client.ui.kit

import com.teamuhi.superbcamera.client.UiSize
import net.minecraft.Util
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth

/**
 * Owns the scaled panel space of the editor: the transform from the game's GUI coordinates, the root widgets, keyboard
 * focus, mouse capture and the tooltip.
 *
 * The panel is drawn under `pose.scale(scale)` so it can use a different, smaller UI scale than the game's GUI scale
 * (`scale = uiScale / guiScale`, never above 1). Screen events arrive in GUI-scaled coordinates; every handler here
 * divides them by [scale] before hit-testing, and widgets only ever see panel units.
 *
 * Focus rules: [focus] gives a widget the keyboard; a click that lands in the panel but not on the focused widget
 * removes the focus again (so a half-typed number is committed by the widget's own [Widget.onBlur]).
 */
class UiRoot(val font: Font) {

    /** GUI-scaled units per panel unit, at most 1. */
    var scale = 1.0
        private set

    /** Size of the whole window in panel units. */
    var viewWidth = 0
        private set
    var viewHeight = 0
        private set

    /** The panel rectangle (panel units); clicks inside it never reach the viewport. */
    var panelX = 0
    var panelY = 0
    var panelWidth = 0
    var panelHeight = 0

    /** False while the panel is hidden (the N key): nothing is drawn or hit-tested. */
    var shown = true

    val children = ArrayList<Widget>()

    /** Mouse position in panel units, refreshed by [begin]. */
    var mouseX = 0
        private set
    var mouseY = 0
        private set

    var focus: Widget? = null
        private set

    /** The root child that took the last click and therefore receives the drag until the button is released. */
    private var captured: Widget? = null
    private var focusKept = false

    private var pendingTooltip: String? = null
    private var shownTooltip: String? = null
    private var tooltipSince = 0L

    /** True while a text field has the keyboard. */
    val typing: Boolean get() = focus?.typing == true

    /** True while a widget holds a mouse button (a number field being scrubbed). */
    val dragging: Boolean get() = captured != null

    // ------------------------------------------------------------------------------------------------ geometry

    /** Recomputes the scale for a window of [guiWidth] x [guiHeight] GUI units at the game's [guiScale]. */
    fun configure(guiWidth: Int, guiHeight: Int, guiScale: Double, size: UiSize) {
        val safeGui = if (guiScale > 0.0) guiScale else 1.0
        val pixels = if (size.pixelsPerUnit <= 0) safeGui else size.pixelsPerUnit.toDouble().coerceAtMost(safeGui)
        scale = pixels / safeGui
        viewWidth = Mth.floor(guiWidth / scale)
        viewHeight = Mth.floor(guiHeight / scale)
    }

    /** A GUI-scaled coordinate in panel units. */
    fun toPanel(gui: Double): Double = gui / scale

    /** A panel coordinate in GUI-scaled units (for scissoring, which ignores the pose transform). */
    fun toGui(panel: Double): Double = panel * scale

    fun inPanel(mx: Double, my: Double): Boolean =
        shown && mx >= panelX && mx < panelX + panelWidth && my >= panelY && my < panelY + panelHeight

    // ------------------------------------------------------------------------------------------------ focus

    /** Gives [widget] the keyboard; the previous holder is told it lost it. */
    fun focus(widget: Widget) {
        focusKept = true
        if (focus === widget) return
        val old = focus
        focus = widget
        old?.onBlur(this)
        widget.onFocus(this)
    }

    /** A focused widget calls this when a click landed inside it, so the click does not blur it. */
    fun keepFocus() {
        focusKept = true
    }

    fun blur() {
        val old = focus ?: return
        focus = null
        old.onBlur(this)
    }

    /** Drops the root widgets (and any focus / capture on them) before the panel is rebuilt. */
    fun clear() {
        blur()
        captured = null
        children.clear()
    }

    // ------------------------------------------------------------------------------------------------ tooltip

    /** Asks for [text] to be shown next to the cursor once the cursor rests on the calling widget. */
    fun tooltip(text: String?) {
        if (text != null) pendingTooltip = text
    }

    fun tooltip(component: Component?) {
        if (component != null) pendingTooltip = component.string
    }

    // ------------------------------------------------------------------------------------------------ rendering

    /** Starts a frame: stores the mouse in panel units and enters the scaled space. Pair with [end]. */
    fun begin(g: GuiGraphics, guiMouseX: Int, guiMouseY: Int) {
        mouseX = Mth.floor(toPanel(guiMouseX.toDouble()))
        mouseY = Mth.floor(toPanel(guiMouseY.toDouble()))
        pendingTooltip = null
        g.pose().pushPose()
        g.pose().scale(scale.toFloat(), scale.toFloat(), 1f)
    }

    /** Draws the root widgets (when the panel is shown). */
    fun renderChildren(g: GuiGraphics) {
        if (!shown) return
        for (child in children) if (child.visible) child.render(g, this, mouseX, mouseY)
    }

    /** Draws the tooltip on top and leaves the scaled space. */
    fun end(g: GuiGraphics) {
        drawTooltip(g)
        g.pose().popPose()
    }

    private fun drawTooltip(g: GuiGraphics) {
        val text = pendingTooltip
        if (text == null) {
            shownTooltip = null
            return
        }
        val now = Util.getMillis()
        if (text != shownTooltip) {
            shownTooltip = text
            tooltipSince = now
        }
        if (now - tooltipSince < TOOLTIP_DELAY_MS) return

        val lines = font.split(Component.literal(text), TOOLTIP_MAX_WIDTH)
        if (lines.isEmpty()) return
        val boxWidth = lines.maxOf { font.width(it) } + 2 * TOOLTIP_PAD
        val boxHeight = lines.size * font.lineHeight + 2 * TOOLTIP_PAD - 1
        var x = mouseX + 8
        var y = mouseY + 10
        if (x + boxWidth > viewWidth - 2) x = (mouseX - boxWidth - 4).coerceAtLeast(2)
        if (y + boxHeight > viewHeight - 2) y = (mouseY - boxHeight - 4).coerceAtLeast(2)
        g.pose().pushPose()
        g.pose().translate(0f, 0f, TOOLTIP_Z)
        g.fill(x, y, x + boxWidth, y + boxHeight, 0xF00C0D10.toInt())
        Ui.outline(g, x, y, boxWidth, boxHeight, Ui.fade(Ui.ACCENT, 0.55f))
        for ((index, line) in lines.withIndex()) {
            g.drawString(font, line, x + TOOLTIP_PAD, y + TOOLTIP_PAD + index * font.lineHeight, Ui.TEXT, false)
        }
        g.pose().popPose()
    }

    // ------------------------------------------------------------------------------------------------ events

    /** Handles a click given in GUI units. Returns true when it landed inside the panel (consumed). */
    fun mouseClicked(guiX: Double, guiY: Double, button: Int): Boolean {
        val mx = toPanel(guiX)
        val my = toPanel(guiY)
        if (!inPanel(mx, my)) return false
        val before = focus
        focusKept = false
        captured = null
        for (child in children.asReversed()) {
            if (child.visible && child.mouseClicked(this, mx, my, button)) {
                captured = child
                break
            }
        }
        if (before != null && focus === before && !focusKept) blur()
        return true
    }

    fun mouseDragged(guiX: Double, guiY: Double, button: Int, guiDx: Double, guiDy: Double): Boolean {
        val target = captured ?: return false
        target.mouseDragged(this, toPanel(guiX), toPanel(guiY), button, toPanel(guiDx), toPanel(guiDy))
        return true
    }

    fun mouseReleased(guiX: Double, guiY: Double, button: Int): Boolean {
        val target = captured ?: return false
        captured = null
        target.mouseReleased(this, toPanel(guiX), toPanel(guiY), button)
        return true
    }

    /** Handles the wheel. Returns true when the cursor is inside the panel (consumed). */
    fun mouseScrolled(guiX: Double, guiY: Double, delta: Double): Boolean {
        val mx = toPanel(guiX)
        val my = toPanel(guiY)
        if (!inPanel(mx, my)) return false
        for (child in children.asReversed()) {
            if (child.visible && child.contains(mx, my) && child.mouseScrolled(this, mx, my, delta)) break
        }
        return true
    }

    fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean =
        focus?.keyPressed(this, keyCode, scanCode, modifiers) ?: false

    fun charTyped(codePoint: Char, modifiers: Int): Boolean = focus?.charTyped(this, codePoint, modifiers) ?: false

    fun tick() {
        for (child in children) child.tick()
    }

    private companion object {
        const val TOOLTIP_DELAY_MS = 350L
        const val TOOLTIP_MAX_WIDTH = 140
        const val TOOLTIP_PAD = 3
        const val TOOLTIP_Z = 400f
    }
}
