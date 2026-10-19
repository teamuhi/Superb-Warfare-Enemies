package com.teamuhi.superbcamera.client.ui

import com.teamuhi.superbcamera.client.CameraMath
import com.teamuhi.superbcamera.client.Perspective
import com.teamuhi.superbcamera.client.ui.kit.Ui
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.roundToInt

/**
 * The axis gizmo in the corner of the free viewport: a 2D projection of the **anchor's** X / Y / Z axes into the
 * current camera view (editor convention: +X right, +Y up, +Z forward), in Blender's axis colours. Positive axes are
 * filled dots with a letter, negative axes are hollow dots. Hovering highlights a dot; clicking one snaps to the matching
 * [StandardView] (+X right view, +Y top, +Z front view, and the opposite for the negative dots).
 */
object AxisGizmo {

    /** Pixel (panel unit) distance from the centre to the end of an axis. */
    const val ARM = 24

    private const val DOT = 4
    private const val BACKDROP = ARM + DOT + 4

    private class Dot(
        val label: String, val color: Int, val positive: Boolean, val view: StandardView,
        val x: Int, val y: Int, val depth: Float
    )

    private val axes = arrayOf(
        Triple("X", Ui.AXIS_X, Vector3f(-1f, 0f, 0f)),
        Triple("Y", Ui.AXIS_Y, Vector3f(0f, 1f, 0f)),
        Triple("Z", Ui.AXIS_Z, Vector3f(0f, 0f, 1f))
    )

    private val views = arrayOf(
        StandardView.RIGHT to StandardView.LEFT,
        StandardView.TOP to StandardView.BOTTOM,
        StandardView.FRONT to StandardView.BACK
    )

    /** Dots for the camera orientation of [p], at the gizmo centre [cx], [cy], farthest first. */
    private fun dots(p: Perspective, cx: Int, cy: Int): List<Dot> {
        val inverse = Quaternionf(CameraMath.body(p.yaw, p.pitch, p.roll)).conjugate()
        val out = ArrayList<Dot>(6)
        for ((index, axis) in axes.withIndex()) {
            val camera = Vector3f(axis.third).rotate(inverse)
            // Camera space: +X is left, +Y up, +Z away from the viewer.
            val sx = -camera.x
            val sy = -camera.y
            for (positive in booleanArrayOf(true, false)) {
                val sign = if (positive) 1f else -1f
                out += Dot(
                    axis.first, axis.second, positive, if (positive) views[index].first else views[index].second,
                    cx + (sx * sign * ARM).roundToInt(), cy + (sy * sign * ARM).roundToInt(), camera.z * sign
                )
            }
        }
        return out.sortedByDescending { it.depth }
    }

    fun render(g: GuiGraphics, font: Font, p: Perspective, cx: Int, cy: Int, mx: Int, my: Int) {
        Ui.disc(g, cx, cy, BACKDROP, 0x40000000)
        val dots = dots(p, cx, cy)
        val hovered = dots.lastOrNull { near(it, mx, my) }
        for (dot in dots) {
            // Lines first so the dots sit on top of every axis.
            if (dot.positive) line(g, cx, cy, dot.x, dot.y, Ui.fade(dot.color, if (dot.depth > 0f) 0.55f else 1f))
        }
        for (dot in dots) {
            val bright = dot === hovered
            if (bright) Ui.disc(g, dot.x, dot.y, DOT + 2, 0x60FFFFFF)
            if (dot.positive) {
                Ui.disc(g, dot.x, dot.y, DOT, dot.color)
                Ui.centered(g, font, dot.label, dot.x - DOT, dot.y - DOT, 2 * DOT + 1, 2 * DOT + 1, Ui.ON_ACCENT)
            } else {
                Ui.ring(g, dot.x, dot.y, DOT, Ui.fade(dot.color, 0.9f))
            }
        }
    }

    /** The view to snap to when [mx], [my] hit a dot, or null. */
    fun hit(p: Perspective, cx: Int, cy: Int, mx: Int, my: Int): StandardView? =
        dots(p, cx, cy).lastOrNull { near(it, mx, my) }?.view

    private fun near(dot: Dot, mx: Int, my: Int): Boolean {
        val dx = mx - dot.x
        val dy = my - dot.y
        return dx * dx + dy * dy <= (DOT + 1) * (DOT + 1)
    }

    /** A straight line built from short filled steps (the kit has no line primitive and axes are short). */
    private fun line(g: GuiGraphics, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        val steps = maxOf(kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0)).coerceAtLeast(1)
        for (i in 0..steps) {
            val x = x0 + (x1 - x0) * i / steps
            val y = y0 + (y1 - y0) * i / steps
            g.fill(x, y, x + 1, y + 1, color)
        }
    }
}
