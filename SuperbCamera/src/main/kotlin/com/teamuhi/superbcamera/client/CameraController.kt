package com.teamuhi.superbcamera.client

import com.teamuhi.superbcamera.client.ui.CameraEditorScreen
import com.teamuhi.superbcamera.mixin.CameraAccessor
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.client.event.RenderGuiOverlayEvent
import net.minecraftforge.client.event.ViewportEvent
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf

/**
 * Runtime heart of the mod (forge bus, client only): the per-tick work, the camera takeover and the HUD hiding.
 *
 * The takeover runs in `ViewportEvent.ComputeCameraAngles` at the lowest priority. Vanilla's `GameRenderer.renderLevel`
 * calls `Camera.setup` (where Superb Warfare's own camera mixin acts) and then this event, and only afterwards reads the
 * camera position, so whatever is set here wins without replacing `Camera.setup`.
 */
object CameraController {

    /** Editor status line for the frame that was just rendered. */
    var status: Component = Component.empty()
        private set

    /** True when [status] describes a fallback or lost target (drawn in the warning colour). */
    var statusWarning = false
        private set

    private var smoothRotation: Quaternionf? = null
    private var smoothPosition: Vec3? = null
    private var smoothFor: Perspective? = null

    /** A jump larger than this many blocks is a teleport or a new anchor, so smoothing snaps instead of sliding. */
    private const val SNAP_DISTANCE_SQ = 128.0 * 128.0

    /** Forgets the smoothed state so the next frame snaps to the target camera. */
    fun resetSmoothing() {
        smoothRotation = null
        smoothPosition = null
        smoothFor = null
    }

    /** Sets the vanilla camera type with the same side effects as the F5 handler in `Minecraft.handleKeybinds`. */
    fun switchCameraType(type: CameraType) {
        val mc = Minecraft.getInstance()
        val before = mc.options.cameraType
        mc.options.cameraType = type
        if (before.isFirstPerson != type.isFirstPerson) {
            mc.gameRenderer.checkEntityPostEffect(if (type.isFirstPerson) mc.getCameraEntity() else null)
        }
        mc.levelRenderer.needsUpdate()
    }

    /** True while the active perspective should hide the in-game HUD (never while the editor is open). */
    private fun hidesHud(): Boolean {
        val perspective = CameraStore.active ?: return false
        val mc = Minecraft.getInstance()
        return perspective.hideHud && mc.screen !is CameraEditorScreen && mc.options.cameraType == CameraType.THIRD_PERSON_BACK
    }

    // ------------------------------------------------------------------------------------------------ events

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.START) return
        val mc = Minecraft.getInstance()
        if (mc.level == null || mc.player == null) return

        // Something else (Superb Warfare's camera reset, another mod) left third person back: stop overriding.
        if (CameraStore.activeIndex >= 0 && mc.options.cameraType != CameraType.THIRD_PERSON_BACK) {
            CameraStore.deactivate()
        }
        TargetTracker.tick()
        PerspectiveCycle.tick(mc)

        // Drain the key even when it cannot be used, so presses made inside other screens do not pile up.
        while (CameraKeys.EDITOR.consumeClick()) {
            if (mc.screen == null) openEditor(mc)
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    fun onComputeCameraAngles(event: ViewportEvent.ComputeCameraAngles) {
        val perspective = CameraStore.active ?: return
        val mc = Minecraft.getInstance()
        if (mc.options.cameraType != CameraType.THIRD_PERSON_BACK) {
            CameraStore.deactivate()
            return
        }

        val partialTick = event.partialTick.toFloat()
        val frame = CameraAnchor.resolve(perspective, partialTick)
        status = frame.status
        statusWarning = frame.warning

        // Locked: relative to the anchor. Free: relative to the look direction the game would have used.
        val base = if (perspective.locked) Quaternionf(frame.rotation)
        else CameraMath.body(event.yaw.toDouble(), event.pitch.toDouble(), if (perspective.inheritRoll) event.roll.toDouble() else 0.0)
        var rotation = Quaternionf(base).mul(CameraMath.body(perspective.yaw, perspective.pitch, perspective.roll))
        var position = frame.position.add(CameraMath.worldOffset(base, perspective.x, perspective.y, perspective.z))
        if (!CameraMath.isFinite(rotation) || !CameraMath.isFinite(position)) return

        // Smoothing in quaternion space avoids yaw wrap-around and flips; 0 is an exact lock.
        val previousRotation = smoothRotation
        val previousPosition = smoothPosition
        if (perspective.smoothing > 0.0 && previousRotation != null && previousPosition != null && smoothFor === perspective &&
            previousPosition.distanceToSqr(position) < SNAP_DISTANCE_SQ
        ) {
            val alpha = CameraMath.smoothingAlpha(perspective.smoothing, mc.deltaFrameTime)
            rotation = Quaternionf(previousRotation).slerp(rotation, alpha).normalize()
            position = CameraMath.lerp(alpha.toDouble(), previousPosition, position)
        }
        smoothRotation = rotation
        smoothPosition = position
        smoothFor = perspective
        if (!CameraMath.isFinite(rotation) || !CameraMath.isFinite(position)) {
            resetSmoothing()
            return
        }

        val angles = CameraMath.toAngles(rotation)
        if (!angles.yaw.isFinite() || !angles.pitch.isFinite() || !angles.roll.isFinite()) return

        // setRotation refreshes the camera's look / up / left vectors, then the quaternion gets the roll as well
        // (particles and anything else reading Camera.rotation() would otherwise ignore it).
        val accessor = event.camera as CameraAccessor
        accessor.`superbcamera$setRotation`(angles.yaw, angles.pitch)
        event.camera.rotation().set(rotation)
        accessor.`superbcamera$setPosition`(position.x, position.y, position.z)
        event.yaw = angles.yaw
        event.pitch = angles.pitch
        event.roll = angles.roll
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    fun onComputeFov(event: ViewportEvent.ComputeFov) {
        val perspective = CameraStore.active ?: return
        // The hand is rendered in a second pass with its own fov; only the main (configured fov) pass is replaced.
        if (!event.usedConfiguredFov()) return
        if (Minecraft.getInstance().options.cameraType != CameraType.THIRD_PERSON_BACK) return
        event.fov = perspective.fov
    }

    /** Hides the HUD (including Superb Warfare's overlays) without touching the player's own F1 state. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    fun onOverlay(event: RenderGuiOverlayEvent.Pre) {
        if (!hidesHud()) return
        val id = event.overlay.id()
        if (id == VanillaGuiOverlay.CHAT_PANEL.id() || id == VanillaGuiOverlay.DEBUG_TEXT.id()) return
        event.isCanceled = true
    }

    @SubscribeEvent
    fun onLoggingOut(event: ClientPlayerNetworkEvent.LoggingOut) {
        TargetTracker.forget()
        CameraStore.resetRuntime()
        CameraStore.save()
    }

    // ------------------------------------------------------------------------------------------------ editor

    /** Opens the editor on the active perspective (or the last used one); creates the first perspective if none exist. */
    fun openEditor(mc: Minecraft) {
        if (CameraStore.perspectives.isEmpty()) {
            val riding = (mc.cameraEntity ?: mc.player)?.vehicle != null
            val first = Perspective(
                anchor = if (riding) Anchor.VEHICLE else Anchor.PLAYER,
                locked = riding
            )
            if (CameraStore.add(first) < 0) return
        }
        val index = if (CameraStore.activeIndex >= 0) CameraStore.activeIndex else CameraStore.lastUsedIndex
        if (!CameraStore.activate(index)) return
        mc.setScreen(CameraEditorScreen())
    }
}
