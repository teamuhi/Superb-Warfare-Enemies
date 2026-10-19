package com.teamuhi.superbcamera.client.ui

import com.mojang.blaze3d.platform.InputConstants
import com.teamuhi.superbcamera.client.CameraMath
import com.teamuhi.superbcamera.client.CameraStore
import com.teamuhi.superbcamera.client.Perspective
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.joml.Vector3f
import org.lwjgl.glfw.GLFW
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.tan

/** The fixed views of the numpad (and of the axis gizmo): where the camera sits around the pivot and which way it looks. */
enum class StandardView(val yaw: Double, val pitch: Double) {
    /** In front of the anchor, looking back at it. */
    FRONT(180.0, 0.0),

    /** Behind the anchor: the default chase view. */
    BACK(0.0, 0.0),

    /** On the anchor's right-hand side. */
    RIGHT(-90.0, 0.0),
    LEFT(90.0, 0.0),
    TOP(0.0, 90.0),
    BOTTOM(0.0, -90.0)
}

/**
 * Blender-style viewport navigation for the perspective being edited: mouse gestures, the G / R modal operations, the
 * numpad views, undo / redo and fly mode.
 *
 * Everything works in the **anchor frame** with the pivot at its origin: the camera position is
 * [CameraMath.localOffset] of the stored offsets (+X to the left), the orientation is `CameraMath.body(yaw, pitch, roll)`.
 * After each operation the pose is written back (X flipped again) and clamped to the [Perspective] limits.
 *
 * Modal operations (G, R, fly) snapshot the perspective when they start and recompute from that snapshot every frame, so
 * Esc / RMB restores it exactly. The target perspective is remembered: if the active perspective is no longer that one
 * (deleted, or another was selected) the modal is cancelled.
 *
 * Mouse positions of modals are raw window pixels (`MouseHandler.xpos()`), which stay precise at any GUI scale.
 */
class ViewportNav(
    private val mc: Minecraft,
    private val history: EditHistory,
    private val perspective: () -> Perspective?
) {

    private enum class Gesture { NONE, MIDDLE, LOOK }
    private enum class Axis { X, Y, Z }

    /** Common part of the running modal operation. */
    private sealed class Modal(val target: Perspective, val snapshot: Perspective)

    private class Transform(val rotate: Boolean, target: Perspective, snapshot: Perspective, val startX: Double, val startY: Double) :
        Modal(target, snapshot) {
        var axis: Axis? = null
        var local = false
        val typed = StringBuilder()
        var lastAngle = Double.NaN
        var accumulatedDeg = 0.0

        /** The amount shown in the status line (blocks or degrees). */
        var shown = 0.0
    }

    private class Fly(
        target: Perspective, snapshot: Perspective,
        val restoreX: Double, val restoreY: Double
    ) : Modal(target, snapshot) {
        var lastMillis = Util.getMillis()
        var speed = DEFAULT_FLY_SPEED
        var lastGuiX = Double.NaN
        var lastGuiY = Double.NaN
        var pendingDx = 0.0
        var pendingDy = 0.0
    }

    private var gesture = Gesture.NONE
    private var gestureButton = -1
    private var gestureMoved = false
    private var modal: Modal? = null

    /** True while G, R or fly mode is running; the screen then routes all input here. */
    val modalActive: Boolean get() = modal != null

    private val guiScale: Double get() = mc.window.guiScale

    // ------------------------------------------------------------------------------------------------ pose access

    private fun viewOf(p: Perspective) =
        CameraMath.View(CameraMath.localOffset(p.x, p.y, p.z), CameraMath.body(p.yaw, p.pitch, p.roll))

    /** Writes [view] back into [p] (position always, orientation when [withRotation]), clamped. False on bad numbers. */
    private fun write(p: Perspective, view: CameraMath.View, withRotation: Boolean): Boolean {
        if (!view.isFinite()) return false
        val limit = Perspective.POSITION_LIMIT
        var yaw = p.yaw
        var pitch = p.pitch
        var roll = p.roll
        if (withRotation) {
            val a = CameraMath.toStableAngles(view.rotation, p.roll)
            if (!a.yaw.isFinite() || !a.pitch.isFinite() || !a.roll.isFinite()) return false
            yaw = a.yaw.toDouble()
            pitch = a.pitch.toDouble()
            roll = a.roll.toDouble()
        }
        p.x = (-view.position.x).toDouble().coerceIn(-limit, limit)
        p.y = view.position.y.toDouble().coerceIn(-limit, limit)
        p.z = view.position.z.toDouble().coerceIn(-limit, limit)
        p.yaw = yaw.coerceIn(-180.0, 180.0)
        p.pitch = pitch.coerceIn(-90.0, 90.0)
        p.roll = roll.coerceIn(-180.0, 180.0)
        return true
    }

    /** Blocks one screen pixel covers at [depth] blocks from the camera, for pan and move. */
    private fun blocksPerPixel(p: Perspective, depth: Float): Double {
        val height = max(mc.window.height, 1)
        return 2.0 * tan(Math.toRadians(p.fov) / 2.0) * max(depth, 1f) / height
    }

    // ------------------------------------------------------------------------------------------------ mouse gestures

    /** A click in the free viewport. Returns true when it starts a gesture. */
    fun mouseClicked(button: Int): Boolean {
        if (perspective() == null) return false
        gestureMoved = false
        gestureButton = button
        gesture = when {
            button == 2 -> Gesture.MIDDLE
            button == 0 && CameraStore.emulateMiddleMouse && Screen.hasAltDown() -> Gesture.MIDDLE
            button == 0 -> Gesture.LOOK
            else -> Gesture.NONE
        }
        return gesture != Gesture.NONE
    }

    /** A drag with [dxGui] / [dyGui] in GUI units. Returns true when it was part of a gesture. */
    fun mouseDragged(button: Int, dxGui: Double, dyGui: Double): Boolean {
        if (modal != null || gesture == Gesture.NONE || button != gestureButton) return false
        val p = perspective() ?: return false
        val dx = dxGui * guiScale
        val dy = dyGui * guiScale
        if (!dx.isFinite() || !dy.isFinite() || (dx == 0.0 && dy == 0.0)) return true
        if (!gestureMoved) {
            gestureMoved = true
            history.push(p)
        }
        var view = viewOf(p)
        var rotated = false
        when (gesture) {
            Gesture.MIDDLE -> when {
                Screen.hasShiftDown() -> {
                    val scale = blocksPerPixel(p, view.distance)
                    view = CameraMath.pan(view, -dx * scale, dy * scale)
                }
                Screen.hasControlDown() -> view = CameraMath.dolly(view, -dy * view.distance.coerceAtLeast(1f) * DOLLY_PER_PIXEL)
                else -> {
                    view = CameraMath.orbit(view, dx * ORBIT_DEG_PER_PIXEL, dy * ORBIT_DEG_PER_PIXEL)
                    rotated = true
                }
            }
            Gesture.LOOK -> {
                view = CameraMath.look(view, dx * LOOK_DEG_PER_PIXEL, dy * LOOK_DEG_PER_PIXEL, p.roll)
                rotated = true
            }
            Gesture.NONE -> return false
        }
        write(p, view, rotated)
        return true
    }

    fun mouseReleased(button: Int): Boolean {
        if (button != gestureButton) return false
        gesture = Gesture.NONE
        gestureButton = -1
        return true
    }

    /** The wheel in the viewport: dolly, Ctrl pan sideways, Shift pan vertically, Alt field of view. */
    fun mouseScrolled(delta: Double): Boolean {
        val fly = modal as? Fly
        if (fly != null) {
            fly.speed = (fly.speed * Math.pow(FLY_SPEED_STEP, delta)).coerceIn(FLY_SPEED_MIN, FLY_SPEED_MAX)
            return true
        }
        if (modal != null) return true
        val p = perspective() ?: return false
        if (delta == 0.0 || !delta.isFinite()) return true
        history.push(p, WHEEL_KEY)
        val view = viewOf(p)
        val step = max(view.distance * WHEEL_FRACTION, WHEEL_MIN)
        when {
            Screen.hasAltDown() ->
                p.fov = (p.fov - delta * FOV_PER_NOTCH).coerceIn(Perspective.FOV_MIN, Perspective.FOV_MAX)
            Screen.hasControlDown() -> write(p, CameraMath.pan(view, delta * step, 0.0), false)
            Screen.hasShiftDown() -> write(p, CameraMath.pan(view, 0.0, delta * step), false)
            else -> write(p, CameraMath.dolly(view, delta * step), false)
        }
        return true
    }

    // ------------------------------------------------------------------------------------------------ keys

    /** A key with no text field focused and no modal running. Returns true when it was a viewport command. */
    fun keyPressed(keyCode: Int, modifiers: Int): Boolean {
        val p = perspective() ?: return false
        val ctrl = modifiers and GLFW.GLFW_MOD_CONTROL != 0
        val shift = modifiers and GLFW.GLFW_MOD_SHIFT != 0
        when (keyCode) {
            GLFW.GLFW_KEY_G -> startTransform(p, rotate = false)
            GLFW.GLFW_KEY_R -> startTransform(p, rotate = true)
            GLFW.GLFW_KEY_GRAVE_ACCENT -> if (shift) startFly(p) else return false
            GLFW.GLFW_KEY_Z -> if (ctrl) {
                if (shift) redo(p) else undo(p)
            } else return false
            GLFW.GLFW_KEY_Y -> if (ctrl) redo(p) else return false
            GLFW.GLFW_KEY_HOME -> resetTransform(p)

            GLFW.GLFW_KEY_KP_1, GLFW.GLFW_KEY_1 -> snapView(if (ctrl) StandardView.BACK else StandardView.FRONT)
            GLFW.GLFW_KEY_KP_3, GLFW.GLFW_KEY_3 -> snapView(if (ctrl) StandardView.LEFT else StandardView.RIGHT)
            GLFW.GLFW_KEY_KP_7, GLFW.GLFW_KEY_7 -> snapView(if (ctrl) StandardView.BOTTOM else StandardView.TOP)
            GLFW.GLFW_KEY_KP_2 -> orbitStep(0.0, ORBIT_STEP)
            GLFW.GLFW_KEY_KP_8 -> orbitStep(0.0, -ORBIT_STEP)
            GLFW.GLFW_KEY_KP_4 -> if (shift) rollStep(-ORBIT_STEP) else orbitStep(-ORBIT_STEP, 0.0)
            GLFW.GLFW_KEY_KP_6 -> if (shift) rollStep(ORBIT_STEP) else orbitStep(ORBIT_STEP, 0.0)
            GLFW.GLFW_KEY_KP_DECIMAL, GLFW.GLFW_KEY_PERIOD -> frameStep(p)
            GLFW.GLFW_KEY_KP_ADD, GLFW.GLFW_KEY_EQUAL -> dollyStep(p, 1.0)
            GLFW.GLFW_KEY_KP_SUBTRACT, GLFW.GLFW_KEY_MINUS -> dollyStep(p, -1.0)
            else -> return false
        }
        return true
    }

    fun undo(p: Perspective) {
        history.undo(p)
    }

    fun redo(p: Perspective) {
        history.redo(p)
    }

    private fun resetTransform(p: Perspective) {
        history.push(p)
        p.x = 0.0
        p.y = 0.0
        p.z = Perspective.DEFAULT_Z
        p.yaw = 0.0
        p.pitch = 0.0
        p.roll = 0.0
    }

    /** Puts the camera on [view]'s side of the pivot at the current distance, looking at it. */
    fun snapView(view: StandardView) {
        val p = perspective() ?: return
        history.push(p)
        val distance = viewOf(p).distance.let { if (it.isFinite() && it >= SNAP_MIN_DISTANCE) it else DEFAULT_SNAP_DISTANCE }
        val target = CameraMath.viewFrom(distance, view.yaw, view.pitch)
        if (!write(p, target, false)) return
        // The angles are set directly: converting a pitch of exactly +-90 back from a quaternion would be ambiguous.
        p.yaw = view.yaw
        p.pitch = view.pitch
        p.roll = 0.0
    }

    private fun orbitStep(dYaw: Double, dPitch: Double) {
        val p = perspective() ?: return
        history.push(p)
        write(p, CameraMath.orbit(viewOf(p), dYaw, dPitch), true)
    }

    private fun rollStep(degrees: Double) {
        val p = perspective() ?: return
        history.push(p)
        write(p, CameraMath.rollBy(viewOf(p), degrees), true)
    }

    private fun frameStep(p: Perspective) {
        val angles = CameraMath.anglesToPivot(viewOf(p).position) ?: return
        history.push(p)
        p.yaw = angles.yaw.toDouble()
        p.pitch = angles.pitch.toDouble().coerceIn(-90.0, 90.0)
        p.roll = 0.0
    }

    private fun dollyStep(p: Perspective, direction: Double) {
        history.push(p, WHEEL_KEY)
        val view = viewOf(p)
        write(p, CameraMath.dolly(view, direction * max(view.distance * WHEEL_FRACTION, WHEEL_MIN)), false)
    }

    // ------------------------------------------------------------------------------------------------ modals

    private fun startTransform(p: Perspective, rotate: Boolean) {
        if (modal != null) return
        val window = mc.mouseHandler
        modal = Transform(rotate, p, history.copyOf(p), window.xpos(), window.ypos())
        gesture = Gesture.NONE
    }

    private fun startFly(p: Perspective) {
        if (modal != null) return
        val handle = mc.window.window
        val mouse = mc.mouseHandler
        modal = Fly(p, history.copyOf(p), mouse.xpos(), mouse.ypos())
        gesture = Gesture.NONE
        InputConstants.grabOrReleaseMouse(
            handle, GLFW.GLFW_CURSOR_DISABLED, mc.window.screenWidth / 2.0, mc.window.screenHeight / 2.0
        )
    }

    /** Keeps the result of the running modal operation. */
    fun confirmModal() {
        val m = modal ?: return
        modal = null
        if (m is Fly) releaseCursor(m)
        history.pushSnapshot(m.target, m.snapshot)
    }

    /** Throws the running modal operation away and restores the perspective as it was. */
    fun cancelModal() {
        val m = modal ?: return
        modal = null
        if (m is Fly) releaseCursor(m)
        if (CameraStore.perspectives.any { it === m.target }) history.restore(m.target, m.snapshot)
    }

    private fun releaseCursor(m: Fly) {
        InputConstants.grabOrReleaseMouse(mc.window.window, GLFW.GLFW_CURSOR_NORMAL, m.restoreX, m.restoreY)
    }

    /** A mouse button while a modal runs: left confirms, right cancels. */
    fun modalMouse(button: Int) {
        when (button) {
            0 -> confirmModal()
            1 -> cancelModal()
        }
    }

    /** A key while a modal runs (always consumed). */
    fun modalKey(keyCode: Int) {
        val m = modal ?: return
        when (keyCode) {
            GLFW.GLFW_KEY_ESCAPE -> cancelModal()
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> confirmModal()
        }
        if (m !is Transform || modal !== m) return
        when (keyCode) {
            GLFW.GLFW_KEY_X -> constrain(m, Axis.X)
            GLFW.GLFW_KEY_Y -> constrain(m, Axis.Y)
            GLFW.GLFW_KEY_Z -> constrain(m, Axis.Z)
            GLFW.GLFW_KEY_BACKSPACE -> if (m.typed.isNotEmpty()) m.typed.setLength(m.typed.length - 1)
        }
    }

    /** Typed text while a modal runs: digits, a point and a minus sign build an exact value. */
    fun modalChar(c: Char) {
        val m = modal as? Transform ?: return
        when {
            c.isDigit() -> if (m.typed.length < MAX_TYPED) m.typed.append(c)
            c == '.' || c == ',' -> if ('.' !in m.typed && m.typed.length < MAX_TYPED) m.typed.append('.')
            c == '-' -> if (m.typed.startsWith("-")) m.typed.deleteCharAt(0) else m.typed.insert(0, '-')
            else -> return
        }
        // Typing without a constraint addresses X (Blender does the same).
        if (m.axis == null) m.axis = Axis.X
    }

    /** X / Y / Z: first press constrains to the anchor axis, a second one to the camera's own axis (moving only), a third clears. */
    private fun constrain(m: Transform, axis: Axis) {
        when {
            m.axis != axis -> {
                m.axis = axis
                m.local = false
            }
            m.rotate -> m.axis = null
            !m.local -> m.local = true
            else -> {
                m.axis = null
                m.local = false
            }
        }
    }

    /** Fly mode look: [guiX] / [guiY] are the (virtual, unbounded) cursor position in GUI units. */
    fun mouseMoved(guiX: Double, guiY: Double) {
        val fly = modal as? Fly ?: return
        if (!fly.lastGuiX.isNaN()) {
            val dx = (guiX - fly.lastGuiX) * guiScale
            val dy = (guiY - fly.lastGuiY) * guiScale
            if (dx.isFinite() && dy.isFinite()) {
                fly.pendingDx += dx
                fly.pendingDy += dy
            }
        }
        fly.lastGuiX = guiX
        fly.lastGuiY = guiY
    }

    /** Per-frame upkeep: drops a modal whose perspective is gone and applies the running one. */
    fun frame() {
        val m = modal ?: return
        val active = perspective()
        if (active == null || active !== m.target || CameraStore.perspectives.none { it === m.target }) {
            cancelModal()
            return
        }
        when (m) {
            is Transform -> if (m.rotate) applyRotate(m) else applyGrab(m)
            is Fly -> stepFly(m)
        }
    }

    // ------------------------------------------------------------------------------------------------ G

    private fun axisVector(m: Transform, start: CameraMath.View): Vector3f? {
        val axis = m.axis ?: return null
        // Editor +X is to the right, which is -X in the anchor frame.
        val global = when (axis) {
            Axis.X -> Vector3f(-1f, 0f, 0f)
            Axis.Y -> Vector3f(0f, 1f, 0f)
            Axis.Z -> Vector3f(0f, 0f, 1f)
        }
        return if (m.local) global.rotate(start.rotation) else global
    }

    private fun typedValue(m: Transform): Double? {
        if (m.typed.isEmpty()) return null
        return m.typed.toString().toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun applyGrab(m: Transform) {
        val mouse = mc.mouseHandler
        val dxPx = mouse.xpos() - m.startX
        val dyPx = mouse.ypos() - m.startY
        val precision = if (Screen.hasShiftDown()) PRECISION else 1.0
        val start = viewOf(m.snapshot)
        val perPixel = blocksPerPixel(m.snapshot, start.distance) * precision
        val right = start.right()
        val up = start.up()

        val offset: Vector3f
        val axis = axisVector(m, start)
        if (axis == null) {
            offset = Vector3f(right).mul((dxPx * perPixel).toFloat()).add(Vector3f(up).mul((-dyPx * perPixel).toFloat()))
            m.shown = offset.length().toDouble()
        } else {
            var amount = typedValue(m)
            if (amount == null) {
                // Follow the axis' direction on screen; an axis pointing into the view falls back to vertical movement.
                val sx = axis.dot(right).toDouble()
                val sy = -axis.dot(up).toDouble()
                val length = CameraMath.length(sx, sy)
                amount = if (length > AXIS_MIN_SCREEN) {
                    (dxPx * sx + dyPx * sy) / length * perPixel / max(length, AXIS_FLOOR)
                } else {
                    -dyPx * perPixel * (if (axis.dot(start.forward()) >= 0f) 1.0 else -1.0)
                }
                if (Screen.hasControlDown()) amount = snapTo(amount, GRAB_SNAP)
            }
            offset = Vector3f(axis).mul(amount.toFloat())
            m.shown = amount
        }
        val moved = CameraMath.View(Vector3f(start.position).add(offset), start.rotation)
        val target = m.target
        // Position only: copy the snapshot's angles back so a clamped move can never leave stale ones behind.
        if (write(target, moved, false)) {
            target.yaw = m.snapshot.yaw
            target.pitch = m.snapshot.pitch
            target.roll = m.snapshot.roll
        }
    }

    // ------------------------------------------------------------------------------------------------ R

    private fun applyRotate(m: Transform) {
        val mouse = mc.mouseHandler
        val centreX = mc.window.screenWidth / 2.0
        val centreY = mc.window.screenHeight / 2.0
        val angle = atan2(mouse.ypos() - centreY, mouse.xpos() - centreX)
        if (m.lastAngle.isNaN()) m.lastAngle = angle
        // Unwrap the step so a full turn keeps counting.
        var step = Math.toDegrees(angle - m.lastAngle)
        if (step > 180.0) step -= 360.0
        if (step < -180.0) step += 360.0
        m.lastAngle = angle
        if (step.isFinite()) m.accumulatedDeg += step * (if (Screen.hasShiftDown()) PRECISION else 1.0)

        var degrees = typedValue(m) ?: m.accumulatedDeg
        if (typedValue(m) == null && Screen.hasControlDown()) degrees = snapTo(degrees, ROTATE_SNAP)
        m.shown = degrees
        val s = m.snapshot
        val target = m.target
        target.x = s.x
        target.y = s.y
        target.z = s.z
        target.yaw = s.yaw
        target.pitch = s.pitch
        target.roll = s.roll
        when (m.axis ?: Axis.Z) {
            Axis.X -> target.pitch = (s.pitch + degrees).coerceIn(-90.0, 90.0)
            Axis.Y -> target.yaw = CameraMath.wrapDegrees(s.yaw + degrees)
            Axis.Z -> target.roll = CameraMath.wrapDegrees(s.roll + degrees)
        }
    }

    // ------------------------------------------------------------------------------------------------ fly

    private fun stepFly(m: Fly) {
        val now = Util.getMillis()
        val dt = ((now - m.lastMillis) / 1000.0).coerceIn(0.0, MAX_FLY_DT)
        m.lastMillis = now
        val p = m.target
        var view = viewOf(p)

        if (m.pendingDx != 0.0 || m.pendingDy != 0.0) {
            view = CameraMath.look(view, m.pendingDx * FLY_LOOK_DEG_PER_PIXEL, m.pendingDy * FLY_LOOK_DEG_PER_PIXEL, p.roll)
            m.pendingDx = 0.0
            m.pendingDy = 0.0
        }

        val handle = mc.window.window
        fun down(key: Int) = InputConstants.isKeyDown(handle, key)
        val direction = Vector3f()
        val forward = view.forward()
        val right = view.right()
        if (down(GLFW.GLFW_KEY_W)) direction.add(forward)
        if (down(GLFW.GLFW_KEY_S)) direction.sub(forward)
        if (down(GLFW.GLFW_KEY_D)) direction.add(right)
        if (down(GLFW.GLFW_KEY_A)) direction.sub(right)
        if (down(GLFW.GLFW_KEY_E)) direction.add(0f, 1f, 0f)
        if (down(GLFW.GLFW_KEY_Q)) direction.sub(0f, 1f, 0f)
        if (direction.lengthSquared() > 1e-6f) {
            val factor = when {
                Screen.hasShiftDown() -> FLY_FAST
                Screen.hasAltDown() -> FLY_SLOW
                else -> 1.0
            }
            direction.normalize().mul((m.speed * factor * dt).toFloat())
            view = CameraMath.View(Vector3f(view.position).add(direction), view.rotation)
        }
        write(p, view, true)
    }

    // ------------------------------------------------------------------------------------------------ status line

    /** The Blender-style header for the running modal operation, or null when none runs. */
    fun status(): Component? {
        return when (val m = modal) {
            null -> null
            is Fly -> Component.translatable(
                "overlay.superbcamera.fly", String.format(Locale.ROOT, "%.1f", m.speed)
            )
            is Transform -> {
                val title = Component.translatable(if (m.rotate) "overlay.superbcamera.rotate" else "overlay.superbcamera.move").string
                val axisKey = (m.axis ?: if (m.rotate) Axis.Z else null)
                val axisText = when {
                    axisKey == null -> ""
                    m.rotate -> Component.translatable("overlay.superbcamera.rot_axis.${axisKey.name.lowercase()}").string
                    m.local -> Component.translatable("overlay.superbcamera.local", axisKey.name).string
                    else -> axisKey.name
                }
                val typed = if (m.typed.isNotEmpty()) m.typed.toString() + "_" else null
                val value = typed ?: String.format(Locale.ROOT, if (m.rotate) "%.1f°" else "%.3f", m.shown)
                val prefix = if (axisText.isEmpty()) "" else "$axisText: "
                Component.literal("$title  $prefix$value   ").append(Component.translatable("overlay.superbcamera.modal_hint"))
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private companion object {
        val WHEEL_KEY = Any()

        const val ORBIT_DEG_PER_PIXEL = 0.25
        const val LOOK_DEG_PER_PIXEL = 0.1
        const val FLY_LOOK_DEG_PER_PIXEL = 0.15
        const val DOLLY_PER_PIXEL = 0.005
        const val WHEEL_FRACTION = 0.1f
        const val WHEEL_MIN = 0.1f
        const val FOV_PER_NOTCH = 2.0
        const val ORBIT_STEP = 15.0
        const val SNAP_MIN_DISTANCE = 0.5f
        const val DEFAULT_SNAP_DISTANCE = 4f

        const val PRECISION = 0.1
        const val GRAB_SNAP = 1.0
        const val ROTATE_SNAP = 5.0
        const val AXIS_MIN_SCREEN = 0.15
        const val AXIS_FLOOR = 0.3
        const val MAX_TYPED = 10

        const val DEFAULT_FLY_SPEED = 6.0
        const val FLY_SPEED_STEP = 1.25
        const val FLY_SPEED_MIN = 0.5
        const val FLY_SPEED_MAX = 64.0
        const val FLY_FAST = 4.0
        const val FLY_SLOW = 0.25
        const val MAX_FLY_DT = 0.1

        fun snapTo(value: Double, step: Double): Double = (value / step).roundToLong() * step
    }
}
