package com.teamuhi.superbcamera.client.ui

import com.teamuhi.superbcamera.client.SbwBridge
import com.teamuhi.superbcamera.client.TargetTracker
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import java.util.UUID

/** Which entities the Targets tab lists. */
enum class TargetFilter(val key: String) {
    ALL("all"), VEHICLES("vehicles"), BOSSES("bosses"), MOBS("mobs"), PLAYERS("players");

    fun next(): TargetFilter = entries[(ordinal + 1) % entries.size]
}

/** How the Targets tab orders its rows. */
enum class TargetSort(val key: String) {
    DISTANCE("distance"), NAME("name"), HEALTH("health");

    fun next(): TargetSort = entries[(ordinal + 1) % entries.size]
}

/** A snapshot of one candidate entity (taken when the list is rebuilt, not every frame). */
class TargetInfo(
    val entity: Entity,
    val name: String,
    val customName: String,
    val typeId: String,
    val distance: Double,
    val health: Float?,
    val maxHealth: Float?,
    val vehicle: Boolean,
    val boss: Boolean,
    val player: Boolean
) {
    val uuid: UUID get() = entity.uuid
}

/** Why the Targets tab has nothing to show. */
enum class TargetEmpty(val key: String) {
    /** No candidate entity is loaded at all. */
    NONE_LOADED("empty"),

    /** Entities exist but the search / filter hides them all. */
    NO_MATCHES("no_matches")
}

/**
 * The Targets tab data: loaded entities of the current level, filtered by [filter] and [query], ordered by [sort] and
 * capped at [MAX_ROWS] (nearest first). [refresh] rebuilds [rows] on demand (the editor does it every 10 ticks); the
 * selected entry is identified by UUID by the view, so it survives rebuilds.
 *
 * Candidates are living entities (mobs, other players) and Superb Warfare vehicles. Armour stands and invisible
 * non-players are skipped: the boss crew sits invisibly inside the aircraft and would only add noise.
 */
class TargetList(private val minecraft: Minecraft) {

    var query = ""
    var filter = TargetFilter.ALL
    var sort = TargetSort.DISTANCE

    var rows: List<TargetInfo> = emptyList()
        private set

    /** Set when [rows] is empty. */
    var empty: TargetEmpty? = TargetEmpty.NONE_LOADED
        private set

    /** Rebuilds [rows] from the loaded entities. */
    fun refresh() {
        val level = minecraft.level
        val self = minecraft.player
        if (level == null || self == null) {
            rows = emptyList()
            empty = TargetEmpty.NONE_LOADED
            return
        }
        val viewer = minecraft.cameraEntity ?: self
        val candidates = ArrayList<TargetInfo>()
        for (entity in level.entitiesForRendering()) {
            if (entity === self || entity.isRemoved) continue
            snapshot(entity, viewer)?.let { candidates += it }
        }

        val needle = query.trim().lowercase()
        rows = candidates.filter { matchesFilter(it) && matchesQuery(it, needle) }
            .sortedBy { it.distance }
            .take(MAX_ROWS)
            .let { sorted(it) }
        empty = when {
            rows.isNotEmpty() -> null
            candidates.isEmpty() -> TargetEmpty.NONE_LOADED
            else -> TargetEmpty.NO_MATCHES
        }
    }

    private fun snapshot(entity: Entity, viewer: Entity): TargetInfo? {
        val vehicle = SbwBridge.isVehicle(entity)
        if (!vehicle && entity !is LivingEntity) return null
        if (entity is ArmorStand) return null
        if (!vehicle && entity.isInvisible && entity !is Player) return null

        val typeId = TargetTracker.typeId(entity)
        val vehicleHealth = if (vehicle) SbwBridge.health(entity) else null
        val health = (entity as? LivingEntity)?.health ?: vehicleHealth?.first
        val maxHealth = (entity as? LivingEntity)?.maxHealth ?: vehicleHealth?.second
        return TargetInfo(
            entity = entity,
            name = entity.displayName.string,
            customName = entity.customName?.string.orEmpty(),
            typeId = typeId,
            distance = kotlin.math.sqrt(entity.distanceToSqr(viewer)),
            health = health,
            maxHealth = maxHealth,
            vehicle = vehicle,
            boss = typeId.startsWith("superbenemies:"),
            player = entity is Player
        )
    }

    private fun matchesFilter(info: TargetInfo): Boolean = when (filter) {
        TargetFilter.ALL -> true
        TargetFilter.VEHICLES -> info.vehicle
        TargetFilter.BOSSES -> info.boss
        TargetFilter.MOBS -> !info.vehicle && !info.player
        TargetFilter.PLAYERS -> info.player
    }

    /** Case-insensitive match on the display name, the custom name and the type id (for example `a10`). */
    private fun matchesQuery(info: TargetInfo, needle: String): Boolean =
        needle.isEmpty() || info.name.lowercase().contains(needle) ||
            info.customName.lowercase().contains(needle) || info.typeId.contains(needle)

    private fun sorted(list: List<TargetInfo>): List<TargetInfo> = when (sort) {
        TargetSort.DISTANCE -> list
        TargetSort.NAME -> list.sortedBy { it.name.lowercase() }
        // Lowest health first; entities without health go last.
        TargetSort.HEALTH -> list.sortedBy { it.health ?: Float.MAX_VALUE }
    }

    companion object {
        const val MAX_ROWS = 200
    }
}
