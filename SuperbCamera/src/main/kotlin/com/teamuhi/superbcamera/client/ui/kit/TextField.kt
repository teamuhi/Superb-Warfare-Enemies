package com.teamuhi.superbcamera.client.ui.kit

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.util.function.Predicate

/**
 * A single-line text input: an unbordered vanilla [EditBox] (for caret, selection, clipboard and IME handling) drawn
 * over our own flat background, with an accent underline while it has the focus.
 *
 * The widget decides what Enter and Esc mean through [onSubmit] and [onCancel]; both are expected to blur the field
 * (directly or by calling [UiRoot.blur]).
 */
class TextField(
    font: Font,
    hint: Component,
    maxLength: Int,
    private val onChange: (String) -> Unit = {},
    private val onSubmit: (UiRoot) -> Unit = { it.blur() },
    private val onCancel: (UiRoot) -> Unit = { it.blur() }
) : Widget() {

    private val box = EditBox(font, 0, 0, 10, font.lineHeight, hint)

    init {
        box.setBordered(false)
        box.setMaxLength(maxLength)
        box.setTextColor(Ui.TEXT)
        box.setHint(hint.copy().withStyle(net.minecraft.ChatFormatting.DARK_GRAY))
        box.setResponder { onChange(it) }
    }

    var value: String
        get() = box.value
        set(text) {
            box.value = text
        }

    /** Restricts the accepted text (the predicate sees the whole new value). */
    fun setFilter(filter: Predicate<String>) {
        box.setFilter(filter)
    }

    fun selectAll() {
        box.moveCursorToEnd()
        box.setHighlightPos(0)
    }

    override val typing: Boolean get() = box.isFocused

    private fun layoutBox() {
        box.x = x + PAD
        box.y = y + (height - 8) / 2
        box.width = width - 2 * PAD
    }

    override fun render(g: GuiGraphics, ui: UiRoot, mx: Int, my: Int) {
        layoutBox()
        g.fill(x, y, x + width, y + height, Ui.RAISED)
        val hovered = contains(mx.toDouble(), my.toDouble())
        g.fill(x, y + height - 1, x + width, y + height, if (box.isFocused) Ui.ACCENT else if (hovered) Ui.MUTED else Ui.BORDER)
        box.render(g, mx, my, 0f)
    }

    override fun mouseClicked(ui: UiRoot, mx: Double, my: Double, button: Int): Boolean {
        if (button != 0 || !contains(mx, my)) return false
        layoutBox()
        // Embedded in a NumberField the field is already focused by its owner; only claim the focus when free.
        if (!box.isFocused) ui.focus(this)
        ui.keepFocus()
        // Clicks on the padding still place the caret: clamp into the box bounds EditBox tests against.
        box.mouseClicked(mx.coerceIn(box.x.toDouble(), (box.x + box.width - 1).toDouble()), box.y.toDouble(), button)
        return true
    }

    override fun onFocus(ui: UiRoot) {
        box.setFocused(true)
    }

    override fun onBlur(ui: UiRoot) {
        box.setFocused(false)
    }

    override fun keyPressed(ui: UiRoot, keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (!box.isFocused) return false
        when (keyCode) {
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                onSubmit(ui)
                return true
            }
            GLFW.GLFW_KEY_ESCAPE -> {
                onCancel(ui)
                return true
            }
        }
        // Everything else is the text box's business; swallow it so no hotkey fires while typing.
        box.keyPressed(keyCode, scanCode, modifiers)
        return true
    }

    override fun charTyped(ui: UiRoot, codePoint: Char, modifiers: Int): Boolean {
        if (!box.isFocused) return false
        box.charTyped(codePoint, modifiers)
        return true
    }

    override fun tick() {
        box.tick()
    }

    private companion object {
        const val PAD = 4
    }
}
