package com.teamuhi.superbcamera.client.ui

import com.teamuhi.superbcamera.client.CameraKeys
import com.teamuhi.superbcamera.client.CameraStore
import com.teamuhi.superbcamera.client.PanelSide
import com.teamuhi.superbcamera.client.ui.kit.Ui
import com.teamuhi.superbcamera.client.ui.kit.UiRoot
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * The live camera editor: a flat, translucent side panel over the real view that never pauses the game and draws no
 * background, so every change is previewed on the actual shot.
 *
 * The screen only routes input and draws the viewport overlays; the panel itself is [EditorPanel] (drawn at its own
 * UI scale through [UiRoot]) and the Blender-style viewport controls are [ViewportNav]. Routing order for every event:
 * a running modal operation (G / R / fly) first, then a text field that has the keyboard, then the panel, then the
 * viewport.
 *
 * The edited perspective is always [CameraStore.active]; all state lives in the store and the perspective, so a window
 * resize (which calls [init] again) loses nothing. Closing keeps the camera locked on the perspective.
 */
class CameraEditorScreen : Screen(Component.translatable("screen.superbcamera.editor.title")), EditorPanel.Host {

    private var ui: UiRoot? = null
    private var panel: EditorPanel? = null
    private var nav: ViewportNav? = null

    // ------------------------------------------------------------------------------------------------ lifecycle

    override fun init() {
        val mc = minecraft ?: return
        val root = ui ?: UiRoot(font).also { created ->
            val history = EditHistory()
            ui = created
            nav = ViewportNav(mc, history) { CameraStore.active }
            panel = EditorPanel(created, mc, history, this)
        }
        root.configure(width, height, mc.window.guiScale, CameraStore.uiSize)
        panel?.requestRebuild()
    }

    override fun tick() {
        // The camera was taken away from under the editor (Exit, a deleted last perspective, a view reset by another mod).
        if (CameraStore.active == null) {
            onClose()
            return
        }
        panel?.tick()
    }

    override fun removed() {
        // Blurring commits a name that is still being typed (to the perspective it was started on).
        ui?.blur()
        nav?.cancelModal()
        CameraStore.save()
    }

    override fun isPauseScreen(): Boolean = false

    /** No dimmed backdrop: the edit is previewed live on the real view. */
    override fun renderBackground(graphics: GuiGraphics) {}

    // EditorPanel.Host
    override fun close() {
        onClose()
    }

    override fun perspectiveChanged() {
        nav?.cancelModal()
    }

    override fun relayout() {
        val mc = minecraft ?: return
        ui?.configure(width, height, mc.window.guiScale, CameraStore.uiSize)
        panel?.requestRebuild()
    }

    // ------------------------------------------------------------------------------------------------ rendering

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val root = ui ?: return
        val navigation = nav ?: return
        navigation.frame()
        panel?.update()

        root.begin(graphics, mouseX, mouseY)
        val p = CameraStore.active
        if (p != null) {
            val (cx, cy) = gizmoCentre(root)
            val hover = !navigation.modalActive && !root.inPanel(root.mouseX.toDouble(), root.mouseY.toDouble())
            AxisGizmo.render(graphics, font, p, cx, cy, if (hover) root.mouseX else -10_000, if (hover) root.mouseY else -10_000)
        }
        drawStatusLine(graphics, root, navigation)
        root.renderChildren(graphics)
        root.end(graphics)
    }

    /** Centre of the axis gizmo: the top corner of the free viewport, opposite the panel. */
    private fun gizmoCentre(root: UiRoot): Pair<Int, Int> {
        val edge = GIZMO_MARGIN + AxisGizmo.ARM + 8
        val x = if (CameraStore.panelSide == PanelSide.RIGHT) edge else root.viewWidth - edge
        return x to edge
    }

    /** The Blender-style header line at the bottom of the free viewport: modal status, or a muted hint. */
    private fun drawStatusLine(graphics: GuiGraphics, root: UiRoot, navigation: ViewportNav) {
        val modal = navigation.status()
        val text = modal ?: Component.translatable("overlay.superbcamera.hint")

        var left = 0
        var right = root.viewWidth
        if (root.shown) {
            if (CameraStore.panelSide == PanelSide.RIGHT) right = root.panelX else left = root.panelX + root.panelWidth
        }
        val available = right - left - 2 * STATUS_MARGIN
        if (available < 24) return
        val shown = Ui.fit(font, text.string, available - 8)
        val barWidth = font.width(shown) + 8
        val barX = left + (right - left - barWidth) / 2
        val barY = root.viewHeight - STATUS_MARGIN - 12
        graphics.fill(barX, barY, barX + barWidth, barY + 12, Ui.SCRIM)
        if (modal != null) graphics.fill(barX, barY, barX + 1, barY + 12, Ui.ACCENT)
        Ui.text(graphics, font, shown, barX + 4, barY, 12, if (modal != null) Ui.TEXT else Ui.fade(Ui.TEXT, 0.8f))
    }

    // ------------------------------------------------------------------------------------------------ keyboard

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val root = ui ?: return false
        val navigation = nav ?: return false
        if (navigation.modalActive) {
            navigation.modalKey(keyCode)
            return true
        }
        // A focused text field owns the keyboard: no hotkey fires while typing.
        if (root.typing) {
            root.keyPressed(keyCode, scanCode, modifiers)
            return true
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || CameraKeys.EDITOR.matches(keyCode, scanCode)) {
            onClose()
            return true
        }
        val plain = modifiers and (GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_ALT or GLFW.GLFW_MOD_SHIFT) == 0
        if (keyCode == GLFW.GLFW_KEY_N && plain) {
            root.shown = !root.shown
            if (!root.shown) root.blur()
            return true
        }
        // Not Screen.keyPressed: its Tab handling would move a focus this screen does not use.
        return navigation.keyPressed(keyCode, modifiers)
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        val navigation = nav ?: return false
        if (navigation.modalActive) {
            navigation.modalChar(codePoint)
            return true
        }
        return ui?.charTyped(codePoint, modifiers) ?: false
    }

    // ------------------------------------------------------------------------------------------------ mouse

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val root = ui ?: return false
        val navigation = nav ?: return false
        if (navigation.modalActive) {
            navigation.modalMouse(button)
            return true
        }
        if (root.mouseClicked(mouseX, mouseY, button)) return true

        // The gizmo's dots snap to a numpad view.
        val p = CameraStore.active
        if (button == 0 && p != null) {
            val (cx, cy) = gizmoCentre(root)
            val view = AxisGizmo.hit(p, cx, cy, Math.floor(root.toPanel(mouseX)).toInt(), Math.floor(root.toPanel(mouseY)).toInt())
            if (view != null) {
                root.blur()
                navigation.snapView(view)
                return true
            }
        }
        // A click on the viewport drops any text focus and starts a gesture.
        root.blur()
        return navigation.mouseClicked(button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        val root = ui ?: return false
        val navigation = nav ?: return false
        if (navigation.modalActive) return true
        if (root.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true
        return navigation.mouseDragged(button, dragX, dragY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val root = ui ?: return false
        if (root.mouseReleased(mouseX, mouseY, button)) return true
        return nav?.mouseReleased(button) ?: false
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean {
        val root = ui ?: return false
        val navigation = nav ?: return false
        if (navigation.modalActive) return navigation.mouseScrolled(delta)
        if (root.mouseScrolled(mouseX, mouseY, delta)) return true
        return navigation.mouseScrolled(delta)
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        nav?.mouseMoved(mouseX, mouseY)
    }

    private companion object {
        const val GIZMO_MARGIN = 4
        const val STATUS_MARGIN = 4
    }
}
