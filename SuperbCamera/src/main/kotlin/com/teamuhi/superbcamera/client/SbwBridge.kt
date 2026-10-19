package com.teamuhi.superbcamera.client

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import net.minecraftforge.fml.ModList
import org.joml.Quaternionf
import org.joml.Vector3d

/**
 * The only place that touches Superb Warfare classes. Superb Warfare is an optional dependency, so every caller goes
 * through [available] first and this object is never class-loaded when the mod is missing (the JVM resolves the
 * `VehicleEntity` references lazily, on the first call into an [Impl] method).
 */
object SbwBridge {

    private const val MOD_ID = "superbwarfare"

    /** True when Superb Warfare is installed. Cached: the mod list does not change after startup. */
    val available: Boolean by lazy { ModList.get().isLoaded(MOD_ID) }

    /** The first Superb Warfare vehicle in the riding chain of [entity] (the entity itself included), or null. */
    fun findVehicle(entity: Entity?): Entity? {
        if (!available || entity == null) return null
        return Impl.findVehicle(entity)
    }

    /** True when [entity] is a Superb Warfare vehicle. */
    fun isVehicle(entity: Entity): Boolean = available && Impl.isVehicle(entity)

    /** Current and maximum health of a vehicle, or null when [entity] is not one. */
    fun health(entity: Entity): Pair<Float, Float>? = if (available) Impl.health(entity) else null

    /** Pivot and rotation of a Superb Warfare vehicle, or null when [vehicle] is not one. */
    fun frame(vehicle: Entity, partialTick: Float, inheritPitch: Boolean, inheritRoll: Boolean): Pair<Vec3, Quaternionf>? =
        if (available) Impl.frame(vehicle, partialTick, inheritPitch, inheritRoll) else null

    /** Kept separate so the Superb Warfare imports are only resolved once [available] is true. */
    private object Impl {

        fun findVehicle(entity: Entity): Entity? {
            var current: Entity? = entity
            while (current != null) {
                if (current is VehicleEntity) return current
                current = current.vehicle
            }
            return null
        }

        fun isVehicle(entity: Entity): Boolean = entity is VehicleEntity

        fun health(entity: Entity): Pair<Float, Float>? {
            val vehicle = entity as? VehicleEntity ?: return null
            return vehicle.health to vehicle.getMaxHealth()
        }

        fun frame(entity: Entity, partialTick: Float, inheritPitch: Boolean, inheritRoll: Boolean): Pair<Vec3, Quaternionf>? {
            val vehicle = entity as? VehicleEntity ?: return null
            val transform = vehicle.getVehicleYOffsetTransform(partialTick)
            val pivot = transform.getTranslation(Vector3d())
            val rotation = if (inheritPitch && inheritRoll) {
                val q = org.joml.Quaterniond()
                transform.getNormalizedRotation(q)
                Quaternionf(q.x.toFloat(), q.y.toFloat(), q.z.toFloat(), q.w.toFloat())
            } else {
                CameraMath.body(
                    Mth.rotLerp(partialTick, vehicle.yRotO, vehicle.yRot).toDouble(),
                    if (inheritPitch) vehicle.getPitch(partialTick).toDouble() else 0.0,
                    if (inheritRoll) vehicle.getRoll(partialTick).toDouble() else 0.0
                )
            }
            return Vec3(pivot.x, pivot.y, pivot.z) to rotation
        }
    }
}
