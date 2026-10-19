package com.teamuhi.superbcamera.client.ui

import com.teamuhi.superbcamera.client.CameraStore
import com.teamuhi.superbcamera.client.Perspective
import com.teamuhi.superbcamera.client.ui.kit.ButtonStyle
import com.teamuhi.superbcamera.client.ui.kit.FlatButton
import com.teamuhi.superbcamera.client.ui.kit.TextField
import com.teamuhi.superbcamera.client.ui.kit.Ui
import com.teamuhi.superbcamera.client.ui.kit.UiRoot
import com.teamuhi.superbcamera.client.ui.kit.Widget
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

/**
 * The top bar of the panel: `‹ ›` to switch perspective, the name (click to rename, Enter or a click elsewhere
 * commits, Esc reverts), a muted `1/2` counter, and the `+` (add) and `×` (delete) buttons. Delete asks inline with
 * `Delete? ✓ ✕` instead of opening a confirmation screen.
 *
 * **The name field is bound to one [Perspective] instance** ([editTarget], captured when editing starts). Typing,
 * blurring and Enter rename exactly that instance through [CameraStore.rename]; nothing here reads the active
 * perspective at commit time, so switching perspectives (which blurs this widget after the active one changed) can never
 * write the text onto the wrong one.
 */
class HeaderBar(
    private val font: Font,
    private val perspective: () -> Perspective?,
    private val index: () -> Int,
    private val count: () -> Int,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit,
    private val onAdd: () -> Unit,
    private val onDelete: () -> Unit
) : Widget() {

    private var editor: TextField? = null
    private var editTarget: Perspective? = null
    private var deletePending = false

    private val previous = glyphButton("‹", "screen.superbcamera.previous", { count() > 1 }) { onPrevious() }
    private val next = glyphButton("›", "screen.superbcamera.next", { count() > 1 }) { onNext() }
    private val add = FlatButton(
        label = { "+" },
        tooltip = {
            if (CameraStore.isFull) Component.translatable("screen.superbcamera.add.full", CameraStore.MAX_PERSPECTIVES).string
            else Component.translatable("screen.superbcamera.add.tooltip").string
        },
        enabled = { !CameraStore.isFull },
        style = ButtonStyle.BARE,
        onPress = { onAdd() }
    )
    private val delete = glyphButton("×", "screen.superbcamera.delete.tooltip", { true }) { beginDelete() }
    private val confirm = glyphButton("✓", "screen.superbcamera.delete.confirm", { true }, Ui.ACCENT) { confirmDelete() }
    private val cancel = glyphButton("✕", "screen.superbcamera.delete.cancel", { true }, Ui.DANGER) { deletePending = false }

    override val typing: Boolean get() = editor != null

    private fun glyphButton(glyph: String, tooltipKey: String, enabled: () -> Boolean, color: Int = Ui.TEXT, onPress: () -> Unit) =
        FlatButton(
            label = { glyph },
            tooltip = { Component.translatable(tooltipKey).string },
            enabled = enabled,
            style = ButtonStyle.BARE,
            textColor = { color },
            onPress = onPress
        )

    // ------------------------------------------------------------------------------------------------ layout

    private fun counterText(): String = "${index() + 1}/${count()}"

    private val nameLeft: Int get() = x + 2 * BUTTON + 4
    private val nameRight: Int get() = x + width - 2 * BUTTON - font.width(counterText()) - 10

    private fun layout() {
        previous.place(x, y, BUTTON, height)
        next.place(x + BUTTON, y, BUTTON, height)
        delete.place(x + width - BUTTON, y, BUTTON, height)
        add.place(x + width - 2 * BUTTON, y, BUTTON, height)
        // The confirmation takes the place of the counter and the name; its buttons sit where add / delete are.
        confirm.place(x + width - 2 * BUTTON, y, BUTTON, height)
        cancel.place(x + width - BUTTON, y, BUTTON, height)
    }

    private fun nameHit(mx: Double, my: Double): Boolean =
        mx >= nameLeft && mx < nameRight && my >= y && my < y + height

    // ------------------------------------------------------------------------------------------------ drawing

    override fun render(g: GuiGraphics, ui: UiRoot, mx: Int, my: Int) {
        layout()
        g.fill(x, y + height, x + width, y + height + 1, Ui.BORDER)
        previous.render(g, ui, mx, my)
        next.render(g, ui, mx, my)

        if (deletePending) {
            val prompt = Component.translatable("screen.superbcamera.delete.prompt").string
            Ui.text(g, font, Ui.fit(font, prompt, nameRight - nameLeft + 40), nameLeft, y, height, Ui.DANGER)
            confirm.render(g, ui, mx, my)
            cancel.render(g, ui, mx, my)
            return
        }

        val counter = counterText()
        Ui.text(g, font, counter, x + width - 2 * BUTTON - font.width(counter) - 4, y, height, Ui.MUTED)
        add.render(g, ui, mx, my)
        delete.render(g, ui, mx, my)

        val editing = editor
        if (editing != null) {
            editing.place(nameLeft, y + 1, nameRight - nameLeft, height - 2)
            editing.render(g, ui, mx, my)
            return
        }

        val name = perspective()?.name.orEmpty()
        val hovered = nameHit(mx.toDouble(), my.toDouble())
        val shown = Ui.fit(font, name, nameRight - nameLeft - (if (hovered) 10 else 0))
        Ui.text(g, font, shown, nameLeft, y, height, Ui.TEXT)
        if (hovered) {
            Ui.text(g, font, "✎", nameLeft + font.width(shown) + 3, y, height, Ui.ACCENT)
            ui.tooltip(Component.translatable("screen.superbcamera.name"))
        }
    }

    // ------------------------------------------------------------------------------------------------ input

    override fun mouseClicked(ui: UiRoot, mx: Double, my: Double, button: Int): Boolean {
        if (!contains(mx, my)) return false
        layout()
        if (button != 0) return true

        val editing = editor
        if (editing != null && editing.mouseClicked(ui, mx, my, button)) return true

        if (deletePending) {
            if (confirm.contains(mx, my)) confirm.mouseClicked(ui, mx, my, button)
            else if (cancel.contains(mx, my)) cancel.mouseClicked(ui, mx, my, button)
            return true
        }
        for (b in arrayOf(previous, next, add, delete)) {
            if (b.contains(mx, my)) {
                b.mouseClicked(ui, mx, my, button)
                // A pending delete question holds the focus, so any click elsewhere in the panel cancels it.
                if (deletePending) ui.focus(this)
                return true
            }
        }
        if (nameHit(mx, my)) beginRename(ui)
        return true
    }

    override fun keyPressed(ui: UiRoot, keyCode: Int, scanCode: Int, modifiers: Int): Boolean =
        editor?.keyPressed(ui, keyCode, scanCode, modifiers) ?: false

    override fun charTyped(ui: UiRoot, codePoint: Char, modifiers: Int): Boolean =
        editor?.charTyped(ui, codePoint, modifiers) ?: false

    override fun tick() {
        editor?.tick()
    }

    // ------------------------------------------------------------------------------------------------ rename

    private fun beginRename(ui: UiRoot) {
        val target = perspective() ?: return
        deletePending = false
        val field = TextField(
            font, Component.translatable("screen.superbcamera.name"), Perspective.MAX_NAME_LENGTH,
            onSubmit = { root -> root.blur() },
            onCancel = { root ->
                // Esc reverts: drop the editor first so the blur has nothing to commit.
                editor = null
                editTarget = null
                root.blur()
            }
        )
        field.value = target.name
        editTarget = target
        editor = field
        ui.focus(this)
        field.onFocus(ui)
        field.selectAll()
    }

    /** Applies the typed text to the perspective the edit started on, never to whatever is active now. */
    private fun commitRename() {
        val field = editor ?: return
        val target = editTarget
        editor = null
        editTarget = null
        if (target != null) CameraStore.rename(target, field.value)
    }

    override fun onBlur(ui: UiRoot) {
        commitRename()
        deletePending = false
    }

    // ------------------------------------------------------------------------------------------------ delete

    private fun beginDelete() {
        if (perspective() == null) return
        deletePending = true
    }

    private fun confirmDelete() {
        deletePending = false
        onDelete()
    }

    private companion object {
        /** Width of every glyph button in the bar. */
        const val BUTTON = 12
    }
}
