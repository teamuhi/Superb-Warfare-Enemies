package com.teamuhi.superbcamera.client

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf

/**
 * Where the camera is attached this frame: a [position], the anchor's [rotation] (pitch/roll already filtered by the
 * perspective's inherit toggles) and a [status] line for the editor.
 */
class AnchorFrame(val position: Vec3, val rotation: Quaternionf, val status: Component, val warning: Boolean = false)

/** Resolves a [Perspective]'s anchor into an [AnchorFrame], falling back to the player when it cannot be used. */
object CameraAnchor {

    /** The frame for [perspective] at [partialTick]. Never fails: the worst case is the player frame. */
    fun resolve(perspective: Perspective, partialTick: Float): AnchorFrame {
        val mc = Minecraft.getInstance()
        val viewer = mc.cameraEntity ?: mc.player
            ?: return AnchorFrame(Vec3.ZERO, Quaternionf(), Component.translatable("status.superbcamera.fallback"), true)

        return when (perspective.anchor) {
            Anchor.PLAYER -> playerFrame(viewer, partialTick, Component.translatable("status.superbcamera.player"))
            Anchor.VEHICLE -> vehicleFrame(perspective, viewer, partialTick)
            Anchor.WORLD -> worldFrame(perspective, viewer, partialTick)
            Anchor.TARGET -> targetFrame(perspective, viewer, partialTick)
        }
    }

    private fun vehicleFrame(perspective: Perspective, viewer: Entity, partialTick: Float): AnchorFrame {
        val vehicle = SbwBridge.findVehicle(viewer) ?: viewer.rootVehicle.takeIf { it !== viewer }
            ?: return playerFrame(viewer, partialTick, Component.translatable("status.superbcamera.fallback"), warning = true)
        return entityFrame(vehicle, partialTick, perspective)
    }

    private fun worldFrame(perspective: Perspective, viewer: Entity, partialTick: Float): AnchorFrame {
        val level = Minecraft.getInstance().level
        val here = level?.dimension()?.location()?.toString()
        if (perspective.worldDim.isEmpty() || here != perspective.worldDim) {
            if (perspective.worldDim.isNotEmpty() && !perspective.dimensionWarned) {
                perspective.dimensionWarned = true
                Minecraft.getInstance().player?.displayClientMessage(
                    Component.translatable("message.superbcamera.dimension", perspective.name), false
                )
            }
            return playerFrame(viewer, partialTick, Component.translatable("status.superbcamera.fallback"), warning = true)
        }
        perspective.dimensionWarned = false
        return AnchorFrame(
            perspective.worldPos,
            CameraMath.body(perspective.worldYaw, perspective.worldPitch, 0.0),
            Component.translatable("status.superbcamera.world")
        )
    }

    private fun targetFrame(perspective: Perspective, viewer: Entity, partialTick: Float): AnchorFrame {
        val target = TargetTracker.resolve(perspective)
        if (target == null) {
            return playerFrame(viewer, partialTick, Component.translatable("status.superbcamera.target_lost"), warning = true)
        }
        return entityFrame(target, partialTick, perspective)
    }

    /** The viewer's eye position with the body yaw, level (no pitch / roll). */
    private fun playerFrame(viewer: Entity, partialTick: Float, status: Component, warning: Boolean = false) =
        AnchorFrame(viewer.getEyePosition(partialTick), CameraMath.body(bodyYaw(viewer, partialTick), 0.0, 0.0), status, warning)

    /** Superb Warfare vehicles keep full pitch and roll; any other entity uses its body yaw (and pitch when inherited). */
    private fun entityFrame(entity: Entity, partialTick: Float, perspective: Perspective): AnchorFrame {
        val status = Component.translatable("status.superbcamera.anchored", entity.displayName)
        SbwBridge.frame(entity, partialTick, perspective.inheritPitch, perspective.inheritRoll)?.let { (pivot, rotation) ->
            return AnchorFrame(pivot, rotation, status)
        }
        val pitch = if (perspective.inheritPitch) Mth.lerp(partialTick, entity.xRotO, entity.xRot).toDouble() else 0.0
        val centre = entity.getPosition(partialTick).add(0.0, entity.bbHeight * 0.5, 0.0)
        return AnchorFrame(centre, CameraMath.body(bodyYaw(entity, partialTick), pitch, 0.0), status)
    }

    /** Interpolated body yaw: living entities use the body, everything else its facing. */
    private fun bodyYaw(entity: Entity, partialTick: Float): Double =
        if (entity is LivingEntity) Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot).toDouble()
        else Mth.rotLerp(partialTick, entity.yRotO, entity.yRot).toDouble()
}
