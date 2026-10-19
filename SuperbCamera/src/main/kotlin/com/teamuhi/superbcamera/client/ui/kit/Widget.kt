package com.teamuhi.superbcamera.client.ui.kit

import net.minecraft.client.gui.GuiGraphics

/**
 * Base of every control in the kit. Coordinates ([x], [y], [width], [height]) are in the **coordinate space of the
 * container** that holds the widget (panel units for root children, content space inside a [ScrollArea]); all mouse
 * events arrive already converted to that same space, so a widget never needs to know where it is on screen.
 *
 * A widget that returns true from [mouseClicked] keeps receiving [mouseDragged] and [mouseReleased] until the button is
 * let go, wherever the cursor is.
 */
abstract class Widget {
    var x = 0
    var y = 0
    var width = 0
    var height = 0
    var visible = true

    /** True while this widget takes the keyboard (a text field being edited); hotkeys then stay out of the way. */
    open val typing: Boolean get() = false

    fun place(x: Int, y: Int, width: Int, height: Int) {
        this.x = x
        this.y = y
        this.width = width
        this.height = height
    }

    fun contains(mx: Double, my: Double): Boolean = mx >= x && mx < x + width && my >= y && my < y + height

    abstract fun render(g: GuiGraphics, ui: UiRoot, mx: Int, my: Int)

    open fun mouseClicked(ui: UiRoot, mx: Double, my: Double, button: Int): Boolean = false
    open fun mouseDragged(ui: UiRoot, mx: Double, my: Double, button: Int, dx: Double, dy: Double): Boolean = false
    open fun mouseReleased(ui: UiRoot, mx: Double, my: Double, button: Int): Boolean = false
    open fun mouseScrolled(ui: UiRoot, mx: Double, my: Double, delta: Double): Boolean = false
    open fun keyPressed(ui: UiRoot, keyCode: Int, scanCode: Int, modifiers: Int): Boolean = false
    open fun charTyped(ui: UiRoot, codePoint: Char, modifiers: Int): Boolean = false

    /** Called when [UiRoot] hands this widget the keyboard focus. */
    open fun onFocus(ui: UiRoot) {}

    /** Called when the focus leaves (a click elsewhere, Enter / Esc handled by the widget, a rebuild). */
    open fun onBlur(ui: UiRoot) {}

    /** Game tick, for cursor blinking. */
    open fun tick() {}
}
