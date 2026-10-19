package com.teamuhi.superbcamera.client

import net.minecraft.client.Minecraft
import net.minecraftforge.registries.ForgeRegistries
import net.minecraft.world.entity.Entity

/**
 * Keeps a [Perspective] attached to its picked entity.
 *
 * The entity is cached on the perspective and only looked up again when it is gone (removed, or the world changed).
 * A missing target is searched for at most every [SCAN_INTERVAL_TICKS] ticks: first by UUID, then (when
 * [Perspective.reacquire] is on) the nearest loaded entity of the same type id, which is how a boss that respawned with
 * a new UUID is picked up again.
 *
 * Out-of-range targets only exist on the client while the server keeps them tracked. In spectator mode with
 * permission level 2 the tracker therefore also runs `/spectate <uuid>`, and `/spectate` to release.
 */
object TargetTracker {
    private const val SCAN_INTERVAL_TICKS = 10L
    private const val SPECTATE_PERMISSION = 2

    /** True while a `/spectate <uuid>` sent by this mod is in effect. */
    private var spectating = false

    /** Registry id of [entity]'s type, for example `superbenemies:a10_warthog_boss`. */
    fun typeId(entity: Entity): String = ForgeRegistries.ENTITY_TYPES.getKey(entity.type).toString()

    /** The entity [perspective] follows right now, or null when it is not loaded (the caller falls back). */
    fun resolve(perspective: Perspective): Entity? {
        val level = Minecraft.getInstance().level ?: return null
        val cached = perspective.targetEntity
        if (cached != null && !cached.isRemoved && cached.level() === level) return cached
        perspective.targetEntity = null

        val now = level.gameTime
        if (now < perspective.nextScanTick) return null
        perspective.nextScanTick = now + SCAN_INTERVAL_TICKS

        val mc = Minecraft.getInstance()
        val self = mc.player
        val viewer = mc.cameraEntity ?: self
        val uuid = perspective.targetUuid
        var nearest: Entity? = null
        var nearestDistance = Double.MAX_VALUE
        for (entity in level.entitiesForRendering()) {
            if (entity.isRemoved) continue
            if (uuid != null && entity.uuid == uuid) {
                perspective.targetEntity = entity
                return entity
            }
            if (!perspective.reacquire || entity === self || typeId(entity) != perspective.targetType) continue
            val distance = if (viewer != null) entity.distanceToSqr(viewer) else 0.0
            if (distance < nearestDistance) {
                nearest = entity
                nearestDistance = distance
            }
        }
        if (nearest != null) {
            perspective.targetEntity = nearest
            perspective.targetUuid = nearest.uuid
        }
        return nearest
    }

    /** Points [perspective] at [entity]: the anchor becomes TARGET (remembering the previous one) and spectating starts if possible. */
    fun select(perspective: Perspective, entity: Entity) {
        if (perspective.anchor != Anchor.TARGET) perspective.anchorBeforeTarget = perspective.anchor
        perspective.anchor = Anchor.TARGET
        perspective.targetUuid = entity.uuid
        perspective.targetEntity = entity
        perspective.targetType = typeId(entity)
        perspective.targetName = entity.displayName.string
        perspective.nextScanTick = 0L
        if (canSpectate()) {
            Minecraft.getInstance().player?.connection?.sendCommand("spectate ${entity.stringUUID}")
            spectating = true
        }
    }

    /** Back to the anchor used before the target was picked; the picked target is forgotten. */
    fun stopFollowing(perspective: Perspective) {
        if (perspective.anchor == Anchor.TARGET) perspective.anchor = perspective.anchorBeforeTarget
        perspective.targetUuid = null
        perspective.targetEntity = null
        perspective.targetType = ""
        perspective.targetName = ""
        release()
    }

    /** Per-tick upkeep: drops the spectate state when it can no longer apply or when no perspective needs it. */
    fun tick() {
        if (!spectating) return
        val player = Minecraft.getInstance().player
        if (player == null || !player.isSpectator) {
            // Leaving spectator mode already released the server side tracking.
            spectating = false
            return
        }
        if (CameraStore.active?.anchor != Anchor.TARGET) release()
    }

    /** Forgets the spectate state without sending anything (the world is being left). */
    fun forget() {
        spectating = false
    }

    /** Stops spectating the followed entity, if this mod started it. */
    fun release() {
        if (!spectating) return
        spectating = false
        if (canSpectate()) Minecraft.getInstance().player?.connection?.sendCommand("spectate")
    }

    /** Spectator mode with operator permission: the only case where `/spectate` works. */
    fun canSpectate(): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        return player.isSpectator && player.hasPermissions(SPECTATE_PERMISSION)
    }
}
