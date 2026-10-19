package com.teamuhi.superbcamera.client

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.atan2
import kotlin.math.asin
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Rotation helpers shared by the anchors and the camera override.
 *
 * Everything uses one orientation convention, the same one Superb Warfare uses for vehicle transforms
 * (`VehicleVecUtils.getVehicleYOffsetTransform`): the body frame `B(yaw, pitch, roll) = Y(-yaw) * X(pitch) * Z(roll)`
 * with angles in degrees. Its local +Z points where Minecraft's yaw/pitch look, and vanilla's camera-to-world rotation
 * is exactly `B(yaw, pitch, roll) * Y(180)`, so a quaternion built this way can be decomposed straight back into the
 * yaw, pitch and roll that `ViewportEvent.ComputeCameraAngles` expects.
 *
 * Local axes of that frame: +Z forward, +Y up and **+X to the left** (Minecraft's entity X is the left-hand side when
 * facing +Z). [localOffset] flips X so the editor's +X means "to the right", which is what a user expects.
 * Positive roll banks to the right (clockwise as seen from behind).
 */
object CameraMath {

    private const val DEG_TO_RAD = (Math.PI / 180.0).toFloat()
    private const val RAD_TO_DEG = (180.0 / Math.PI)

    /** `B(yaw, pitch, roll)` as a new quaternion. */
    fun body(yawDeg: Double, pitchDeg: Double, rollDeg: Double): Quaternionf =
        Quaternionf()
            .rotateY((-yawDeg).toFloat() * DEG_TO_RAD)
            .rotateX(pitchDeg.toFloat() * DEG_TO_RAD)
            .rotateZ(rollDeg.toFloat() * DEG_TO_RAD)

    /** The offset the editor stores (+X right) as a vector in the anchor frame (+X left). */
    fun localOffset(x: Double, y: Double, z: Double): Vector3f = Vector3f(-x.toFloat(), y.toFloat(), z.toFloat())

    /** Rotates the editor offset [x], [y], [z] by [rotation] and returns it as a world-space vector. */
    fun worldOffset(rotation: Quaternionf, x: Double, y: Double, z: Double): Vec3 {
        val v = localOffset(x, y, z).rotate(rotation)
        return Vec3(v.x.toDouble(), v.y.toDouble(), v.z.toDouble())
    }

    /**
     * The yaw / pitch / roll (degrees) of [rotation] in the body convention. Looking straight up or down (pitch of
     * +-90) only the sum or difference of yaw and roll is defined; there the roll is reported as 0 and the whole turn
     * goes into the yaw, which keeps the result exact where a plain Euler decomposition loses information.
     */
    fun toAngles(rotation: Quaternionf): Angles = decompose(rotation, 0.0)

    /**
     * Like [toAngles], but at the poles the split keeps [referenceRoll] (the stored roll) and moves the rest into the
     * yaw, so a top view that is orbited stays at its roll instead of switching to an equivalent but confusing pair.
     */
    fun toStableAngles(rotation: Quaternionf, referenceRoll: Double): Angles = decompose(rotation, referenceRoll)

    private fun decompose(rotation: Quaternionf, poleRoll: Double): Angles {
        val forward = Vector3f(0f, 0f, 1f).rotate(rotation)
        if (kotlin.math.abs(forward.y) >= POLE_SIN) {
            // B = Y(-s) X(+-90) here, with s = yaw + roll (looking down) or yaw - roll (looking up).
            val up = Vector3f(0f, 1f, 0f).rotate(rotation)
            val roll = poleRoll.coerceIn(-180.0, 180.0)
            return if (forward.y < 0f) {
                val s = atan2(-up.x.toDouble(), up.z.toDouble()) * RAD_TO_DEG
                Angles(wrapDegrees(s - roll).toFloat(), 90f, roll.toFloat())
            } else {
                val s = atan2(up.x.toDouble(), -up.z.toDouble()) * RAD_TO_DEG
                Angles(wrapDegrees(s + roll).toFloat(), -90f, roll.toFloat())
            }
        }
        val euler = rotation.getEulerAnglesYXZ(Vector3f())
        return Angles(
            yaw = (-euler.y * RAD_TO_DEG).toFloat(),
            pitch = (euler.x * RAD_TO_DEG).toFloat(),
            roll = (euler.z * RAD_TO_DEG).toFloat()
        )
    }

    /** `sin` of the pitch (89.9 degrees) from which the Euler decomposition is treated as being at a pole. */
    private val POLE_SIN = kotlin.math.sin(Math.toRadians(89.9)).toFloat()

    /** Frame-rate independent blend factor: 0 for no smoothing, approaching 1 for a long frame. */
    fun smoothingAlpha(smoothing: Double, deltaTicks: Float): Float {
        if (smoothing <= 0.0) return 1f
        val dt = deltaTicks.coerceIn(0f, MAX_DELTA_TICKS).toDouble()
        return (1.0 - smoothing.coerceAtMost(0.99).pow(dt)).toFloat().coerceIn(0f, 1f)
    }

    /** True when every component is a finite number. */
    fun isFinite(q: Quaternionf): Boolean = q.x.isFinite() && q.y.isFinite() && q.z.isFinite() && q.w.isFinite()

    fun isFinite(v: Vec3): Boolean = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()

    /** Linear blend of two positions. */
    fun lerp(alpha: Double, from: Vec3, to: Vec3): Vec3 =
        Vec3(Mth.lerp(alpha, from.x, to.x), Mth.lerp(alpha, from.y, to.y), Mth.lerp(alpha, from.z, to.z))

    // ------------------------------------------------------------------------------------------------ viewport navigation

    /**
     * A camera pose in the anchor frame: [position] is the offset from the pivot (the anchor origin) with +X to the
     * LEFT like [localOffset], [rotation] is the camera's `B(yaw, pitch, roll)` in the same frame. The navigation
     * helpers below take and return these, so the editor does all its viewport maths without touching the angles.
     */
    class View(val position: Vector3f, val rotation: Quaternionf) {
        /** Distance from the pivot. */
        val distance: Float get() = position.length()

        /** Camera right, up and forward in the anchor frame (+X is left in the body frame, so right is -X). */
        fun right(): Vector3f = Vector3f(-1f, 0f, 0f).rotate(rotation)
        fun up(): Vector3f = Vector3f(0f, 1f, 0f).rotate(rotation)
        fun forward(): Vector3f = Vector3f(0f, 0f, 1f).rotate(rotation)

        fun isFinite(): Boolean = position.isFinite && CameraMath.isFinite(rotation)
    }

    /** Pitch (degrees) that orbiting and looking never go past, so the Euler decomposition stays well conditioned. */
    const val PITCH_LIMIT = 89.0

    /** Closest the camera dollies to the pivot. */
    const val MIN_DISTANCE = 0.05f

    /**
     * Turntable orbit around the pivot: first about the anchor's up axis by [dYawDeg] (positive turns the camera to
     * its right, like a yaw), then about the camera's right axis so the camera pitch changes by [dPitchDeg] (positive
     * looks further down). Both position and rotation turn, so the pivot keeps its place on screen. The resulting
     * pitch is held inside +-[PITCH_LIMIT]; moving back out of a larger pitch (a top view at 90) is always allowed.
     */
    fun orbit(view: View, dYawDeg: Double, dPitchDeg: Double): View {
        val yawTurn = Quaternionf().rotateY((-dYawDeg).toFloat() * DEG_TO_RAD)
        var position = Vector3f(view.position).rotate(yawTurn)
        var rotation = Quaternionf(yawTurn).mul(view.rotation)

        if (dPitchDeg != 0.0) {
            val pitch = toAngles(rotation).pitch.toDouble()
            val applied = if (dPitchDeg > 0.0) dPitchDeg.coerceAtMost(max(0.0, PITCH_LIMIT - pitch))
            else dPitchDeg.coerceAtLeast(minOf(0.0, -PITCH_LIMIT - pitch))
            // About the camera's right axis a positive angle looks UP, hence the sign.
            val right = Vector3f(-1f, 0f, 0f).rotate(rotation)
            val pitchTurn = Quaternionf().fromAxisAngleRad(right, (-applied).toFloat() * DEG_TO_RAD)
            position = position.rotate(pitchTurn)
            rotation = Quaternionf(pitchTurn).mul(rotation)
        }
        return View(position, rotation.normalize())
    }

    /** Moves the camera by [dx] along its right and [dy] along its up axis (blocks). The pivot and the rotation stay. */
    fun pan(view: View, dx: Double, dy: Double): View {
        val position = Vector3f(view.position)
            .add(view.right().mul(dx.toFloat()))
            .add(view.up().mul(dy.toFloat()))
        return View(position, Quaternionf(view.rotation))
    }

    /**
     * Moves the camera [amount] blocks toward the pivot (negative: away), never closer than [MIN_DISTANCE]. At the
     * pivot itself there is no direction to move along, so zooming out goes backwards along the view.
     */
    fun dolly(view: View, amount: Double): View {
        val length = view.position.length()
        val outward = if (length > EPSILON) Vector3f(view.position).div(length) else view.forward().negate()
        // Zooming in while already at the minimum keeps the position; zooming out from there is always possible.
        val target = if (amount > 0.0 && length <= MIN_DISTANCE) length else max(length - amount.toFloat(), MIN_DISTANCE)
        return View(outward.mul(target), Quaternionf(view.rotation))
    }

    /**
     * Turns the camera in place by [dYawDeg] / [dPitchDeg] (yaw positive to the right, pitch positive downwards).
     * [referenceRoll] is the stored roll, used to split yaw and roll sensibly when looking straight up or down.
     */
    fun look(view: View, dYawDeg: Double, dPitchDeg: Double, referenceRoll: Double = 0.0): View {
        val a = toStableAngles(view.rotation, referenceRoll)
        val rotation = body(
            a.yaw + dYawDeg,
            (a.pitch + dPitchDeg).coerceIn(-PITCH_LIMIT, PITCH_LIMIT),
            a.roll.toDouble()
        )
        return View(Vector3f(view.position), rotation)
    }

    /** Banks the camera by [dRollDeg] around its own view axis (positive to the right). */
    fun rollBy(view: View, dRollDeg: Double): View =
        View(Vector3f(view.position), Quaternionf(view.rotation).rotateZ(dRollDeg.toFloat() * DEG_TO_RAD).normalize())

    /** The view at [distance] from the pivot looking at it along `body(yaw, pitch, 0)`. */
    fun viewFrom(distance: Float, yawDeg: Double, pitchDeg: Double): View {
        val rotation = body(yawDeg, pitchDeg, 0.0)
        return View(Vector3f(0f, 0f, -distance).rotate(rotation), rotation)
    }

    /** Yaw and pitch (roll 0) that aim a camera at [position] at the pivot, or null when it sits on the pivot. */
    fun anglesToPivot(position: Vector3f): Angles? {
        val length = position.length()
        if (!(length > EPSILON)) return null
        val fx = -position.x / length
        val fy = -position.y / length
        val fz = -position.z / length
        return Angles(
            yaw = (atan2(-fx.toDouble(), fz.toDouble()) * RAD_TO_DEG).toFloat(),
            pitch = (asin((-fy).toDouble().coerceIn(-1.0, 1.0)) * RAD_TO_DEG).toFloat(),
            roll = 0f
        )
    }

    /** Wraps an angle into -180..180. */
    fun wrapDegrees(degrees: Double): Double {
        var d = degrees % 360.0
        if (d >= 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    /** Euclidean length of a 2D vector, for the screen-space maths in the editor. */
    fun length(x: Double, y: Double): Double = sqrt(x * x + y * y)

    private const val EPSILON = 1e-3f

    /** Yaw / pitch / roll in degrees, as handed to the camera. */
    data class Angles(val yaw: Float, val pitch: Float, val roll: Float)

    private const val MAX_DELTA_TICKS = 5f
}
