package com.teamuhi.superbcamera.client.ui.kit

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.util.Mth
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A vertically scrolling container. Its children are positioned in **content space** (origin at the top left of the
 * content, [contentWidth] wide); the area translates them by the scroll offset, clips them with a scissor and converts
 * mouse coordinates, so the children behave as if the content were not scrolled.
 *
 * The wheel goes to the child under the cursor first (a number field nudges itself) and scrolls the area when no child
 * takes it. A 2 px scrollbar shows the position and can be dragged.
 */
class ScrollArea : Widget() {

    private var content: List<Widget> = emptyList()
    private var contentHeight = 0

    var scroll = 0.0
        private set

    /** The child that took the current press and gets the drag / release. */
    private var pressedChild: Widget? = null
    private var draggingBar = false

    /** Width available to children (the scrollbar gutter is reserved). */
    val contentWidth: Int get() = width - GUTTER

    /** Replaces the content; the scroll position is kept (clamped to the new height). */
    fun setContent(widgets: List<Widget>, height: Int) {
        content = widgets
        contentHeight = height
        pressedChild = null
        scroll = scroll.coerceIn(0.0, maxScroll())
    }

    fun scrollTo(value: Double) {
        scroll = value.coerceIn(0.0, maxScroll())
    }

    private fun maxScroll(): Double = (contentHeight - height).coerceAtLeast(0).toDouble()

    private fun overflows(): Boolean = contentHeight > height

    // ------------------------------------------------------------------------------------------------ drawing

    override fun render(g: GuiGraphics, ui: UiRoot, mx: Int, my: Int) {
        scroll = scroll.coerceIn(0.0, maxScroll())
        // Scissoring ignores the pose transform, so convert the panel rectangle to GUI units by hand.
        g.enableScissor(
            floor(ui.toGui(x.toDouble())).toInt(), floor(ui.toGui(y.toDouble())).toInt(),
            ceil(ui.toGui((x + width).toDouble())).toInt(), ceil(ui.toGui((y + height).toDouble())).toInt()
        )
        val top = scroll.toInt()
        g.pose().pushPose()
        g.pose().translate(x.toFloat(), (y - top).toFloat(), 0f)
        val inside = contains(mx.toDouble(), my.toDouble())
        val localX = mx - x
        val localY = my - y + top
        for (child in content) {
            if (!child.visible || child.y + child.height < top || child.y > top + height) continue
            // Children below the clip must not report hover.
            child.render(g, ui, if (inside) localX else -10_000, if (inside) localY else -10_000)
        }
        g.pose().popPose()
        g.disableScissor()

        if (overflows()) {
            val barX = x + width - 2
            val thumbHeight = (height * height / contentHeight.toDouble()).toInt().coerceIn(MIN_THUMB, height)
            val thumbY = y + ((height - thumbHeight) * (scroll / maxScroll())).toInt()
            g.fill(barX, y, barX + 2, y + height, 0x20FFFFFF)
            g.fill(barX, thumbY, barX + 2, thumbY + thumbHeight, if (draggingBar) Ui.ACCENT else 0x90FFFFFF.toInt())
        }
    }

    // ------------------------------------------------------------------------------------------------ input

    private fun childAt(lx: Double, ly: Double): Widget? =
        content.asReversed().firstOrNull { it.visible && it.contains(lx, ly) }

    override fun mouseClicked(ui: UiRoot, mx: Double, my: Double, button: Int): Boolean {
        if (!contains(mx, my)) return false
        if (overflows() && mx >= x + width - BAR_HIT && button == 0) {
            draggingBar = true
            dragBarTo(my)
            return true
        }
        val lx = mx - x
        val ly = my - y + scroll.toInt()
        val child = childAt(lx, ly)
        if (child != null && child.mouseClicked(ui, lx, ly, button)) {
            pressedChild = child
        }
        // The click is ours either way (empty content space), so the root does not treat it as a miss.
        return true
    }

    override fun mouseDragged(ui: UiRoot, mx: Double, my: Double, button: Int, dx: Double, dy: Double): Boolean {
        if (draggingBar) {
            dragBarTo(my)
            return true
        }
        return pressedChild?.mouseDragged(ui, mx - x, my - y + scroll.toInt(), button, dx, dy) ?: false
    }

    override fun mouseReleased(ui: UiRoot, mx: Double, my: Double, button: Int): Boolean {
        draggingBar = false
        val child = pressedChild
        pressedChild = null
        return child?.mouseReleased(ui, mx - x, my - y + scroll.toInt(), button) ?: false
    }

    override fun mouseScrolled(ui: UiRoot, mx: Double, my: Double, delta: Double): Boolean {
        val lx = mx - x
        val ly = my - y + scroll.toInt()
        val child = childAt(lx, ly)
        if (child != null && child.mouseScrolled(ui, lx, ly, delta)) return true
        if (!overflows()) return false
        scrollTo(scroll - delta * WHEEL_STEP)
        return true
    }

    override fun tick() {
        for (child in content) child.tick()
    }

    private fun dragBarTo(my: Double) {
        val thumbHeight = (height * height / contentHeight.toDouble()).toInt().coerceIn(MIN_THUMB, height)
        val travel = (height - thumbHeight).coerceAtLeast(1)
        scrollTo(Mth.clamp((my - y - thumbHeight / 2.0) / travel, 0.0, 1.0) * maxScroll())
    }

    private companion object {
        const val GUTTER = 5
        const val BAR_HIT = 5
        const val MIN_THUMB = 8
        const val WHEEL_STEP = 14.0
    }
}
