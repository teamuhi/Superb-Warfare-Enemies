package com.teamuhi.superbenemies.entity.boss.ac130.ai

import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.config.Ac130Config
import com.teamuhi.superbenemies.config.DummyConfig
import com.teamuhi.superbenemies.entity.boss.ac130.Ac130BossEntity
import com.teamuhi.superbenemies.entity.boss.common.BossCrewEntity
import com.teamuhi.superbenemies.entity.boss.common.ai.FlightMath
import com.teamuhi.superbenemies.entity.dummy.PlayerDummyEntity
import com.teamuhi.superbenemies.entity.dummy.TrackedDummies
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Target selection for the AC-130 boss: players and/or every living mob under open sky, as `targetPriority` allows
 * (re-read every tick, so switching it drops a target that is no longer allowed straight away).
 *
 * Unlike the A-10 the gunship does not care what kind of thing it shells, so animals and villagers count the same as
 * monsters. Rules, in priority order: the last thing that hurt the boss (if it is a valid candidate), then the current
 * target (unless a candidate is more than [SWITCH_FACTOR] times nearer and the target has been held for at least
 * [MIN_DWELL_TICKS]), then the nearest candidate by horizontal distance. The dwell time stops the boss hopping between
 * mobs while it circles: on a circle of radius r every mob near the ring is "closer" than the one in the middle.
 *
 * Player dummies are a special case, controlled by `DummyConfig.BOSSES_TARGET_DUMMY` (read live): with it on a dummy is
 * a player-class candidate (listed from the dummy tracker when `targetPriority` allows players, subject to the same range
 * rule, exempt from the open-sky rule so a dummy hiding under a roof still gets shelled, and never added a second time
 * through the mob query); with it off it is an ordinary mob, sky rule included.
 *
 * With `randomTargeting` the choice above is replaced by a random draw every `randomTargetSeconds` (see [randomChoice]).
 *
 * The expensive part (the mob query) runs every [SCAN_INTERVAL] ticks, staggered by entity id; validity of the current
 * target is re-checked every tick with cheap field reads, and a lost target schedules a re-scan within
 * [RESCAN_AFTER_LOSS] ticks. All state is per instance.
 */
class Ac130Targeting(private val boss: Ac130BossEntity) {

    /** The chosen victim (player or mob). Null when nothing valid is around. */
    var target: LivingEntity? = null
        private set

    /** What the weapons aim at: the target itself, or the vehicle it rides. */
    var aim: Entity? = null
        private set

    /** Smoothed per-tick velocity of [aim], from position differences (server-side player velocity is unreliable). */
    var aimVelocity: Vec3 = Vec3.ZERO
        private set

    private var targetUuid: UUID? = null
    private var nextScanTick = 0
    private var initialScanDone = false
    private var lastSwitchTick = 0
    private var nextRerollTick = 0

    private var lastAimPosition: Vec3 = Vec3.ZERO
    private var trackedAimId = -1

    // ================================================================================================== persistence

    fun save(tag: CompoundTag) {
        targetUuid?.let { tag.putUUID(TAG_TARGET, it) }
    }

    fun load(tag: CompoundTag) {
        targetUuid = if (tag.hasUUID(TAG_TARGET)) tag.getUUID(TAG_TARGET) else null
        target = null
        aim = null
    }

    // ================================================================================================== update

    /**
     * Call once per boss tick. [orbitRadius] is the radius the boss currently circles at; the mob query box is
     * widened by it, because the boss sits on the ring while its target is in the middle.
     */
    fun update(level: ServerLevel, orbitRadius: Double) {
        val now = boss.tickCount

        var current = target
        val remembered = targetUuid
        if (current == null && remembered != null) {
            current = (level.getEntity(remembered) as? LivingEntity)?.takeIf { stillValid(it) }
            target = current
        }
        if (current != null && !stillValid(current)) {
            drop()
            current = null
            // Look for a replacement soon instead of idling for up to a full scan interval.
            nextScanTick = minOf(nextScanTick, now + RESCAN_AFTER_LOSS)
        }

        if (!initialScanDone || now >= nextScanTick) {
            initialScanDone = true
            nextScanTick = nextAlignedScan(now)
            pickTarget(level, orbitRadius)
            current = target
        }

        aim = current?.rootVehicle
        trackAim(aim)
    }

    private fun drop() {
        target = null
        targetUuid = null
        aim = null
        trackedAimId = -1
    }

    /** The next tick at which `(tick + id) % SCAN_INTERVAL == 0`, so bosses spread their scans over the interval. */
    private fun nextAlignedScan(now: Int): Int {
        val phase = Math.floorMod(now + boss.id, SCAN_INTERVAL)
        return now + (SCAN_INTERVAL - phase)
    }

    // ================================================================================================== validity

    /** Cheap per-tick check of the current target. Heavy conditions (sky exposure, scan box) are scan-only. */
    private fun stillValid(entity: LivingEntity): Boolean {
        if (!entity.isAlive || entity.isRemoved || entity.level() !== boss.level()) return false
        if (entity is ServerPlayer) return validPlayer(entity, lose = true)
        if (entity is PlayerDummyEntity && dummiesArePlayers()) return validDummy(entity, lose = true)
        if (entity !is Mob) return false
        return Ac130Config.TARGET_PRIORITY.get().mobs && !entity.isInvulnerable && !boss.isCrew(entity) &&
                entity.rootVehicle !== boss
    }

    private fun validPlayer(player: ServerPlayer, lose: Boolean): Boolean {
        if (!Ac130Config.TARGET_PRIORITY.get().players) return false
        if (!player.isAlive || player.isRemoved || player.isSpectator || player.isCreative) return false
        if (player.level() !== boss.level()) return false
        if (Ac130Config.GLOBAL_AGGRO.get()) return true
        val limit = Ac130Config.AGGRO_RANGE.get() * (if (lose) LOSE_TARGET_FACTOR else 1.0)
        val dx = player.x - boss.x
        val dz = player.z - boss.z
        return dx * dx + dz * dz <= limit * limit
    }

    private fun dummiesArePlayers(): Boolean = DummyConfig.BOSSES_TARGET_DUMMY.get()

    /** A dummy treated as a player: the player-class rules without the creative / spectator checks, same range rule. */
    private fun validDummy(dummy: PlayerDummyEntity, lose: Boolean): Boolean {
        if (!Ac130Config.TARGET_PRIORITY.get().players) return false
        if (!dummy.isAlive || dummy.isRemoved || dummy.level() !== boss.level()) return false
        if (Ac130Config.GLOBAL_AGGRO.get()) return true
        val limit = Ac130Config.AGGRO_RANGE.get() * (if (lose) LOSE_TARGET_FACTOR else 1.0)
        val dx = dummy.x - boss.x
        val dz = dummy.z - boss.z
        return dx * dx + dz * dz <= limit * limit
    }

    /**
     * A mob worth shelling: alive, attackable, not one of the boss's own crew and standing under open sky. The sky rule
     * keeps the gunship from circling forever over a cave mob it cannot hit (players are exempt, like the A-10).
     */
    private fun eligibleMob(mob: Mob): Boolean {
        if (!mob.isAlive || mob.isRemoved || !mob.isAttackable || mob.isInvulnerable) return false
        if (mob is PlayerDummyEntity && dummiesArePlayers()) return false // handled as a player-class candidate
        if (mob is BossCrewEntity || mob.rootVehicle === boss || boss.isCrew(mob)) return false
        return underOpenSky(mob)
    }

    /** Never loads a chunk: an unloaded chunk counts as "not exposed". */
    private fun underOpenSky(mob: Mob): Boolean {
        val x = Math.floor(mob.x).toInt()
        val z = Math.floor(mob.z).toInt()
        val chunk = boss.level().chunkSource.getChunkNow(x shr 4, z shr 4) ?: return false
        val surface = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x and 15, z and 15)
        return mob.y >= surface - SKY_TOLERANCE
    }

    // ================================================================================================== choice

    private fun pickTarget(level: ServerLevel, orbitRadius: Double) {
        val now = boss.tickCount
        val bossPos = boss.position()
        val global = Ac130Config.GLOBAL_AGGRO.get()
        val aggro = Ac130Config.AGGRO_RANGE.get().toDouble()
        val priority = Ac130Config.TARGET_PRIORITY.get()

        val candidates = ArrayList<LivingEntity>()
        if (priority.players) {
            for (player in level.players()) {
                if (validPlayer(player, lose = false) && (global || FlightMath.horizontalDistance(player.position(), bossPos) <= aggro)) {
                    candidates.add(player)
                }
            }
        }
        if (priority.players && dummiesArePlayers()) {
            for (dummy in TrackedDummies.all(level)) {
                if (dummy is PlayerDummyEntity && validDummy(dummy, lose = false) &&
                    (global || FlightMath.horizontalDistance(dummy.position(), bossPos) <= aggro)
                ) {
                    candidates.add(dummy)
                }
            }
        }
        if (priority.mobs) {
            val reach = Ac130Config.MOB_SCAN_RANGE.get() + orbitRadius.coerceAtLeast(0.0)
            val box = AABB(
                boss.x - reach, level.minBuildHeight.toDouble(), boss.z - reach,
                boss.x + reach, level.maxBuildHeight.toDouble(), boss.z + reach
            )
            candidates.addAll(level.getEntitiesOfClass(Mob::class.java, box) { eligibleMob(it) })
        }

        // A player slightly beyond the aggro range keeps its lock until the (wider) lose range.
        val held = target?.takeIf { t ->
            candidates.any { it === t } || (t is ServerPlayer && validPlayer(t, lose = true)) ||
                    (t is PlayerDummyEntity && dummiesArePlayers() && validDummy(t, lose = true))
        }
        if (held != null && candidates.none { it === held }) candidates.add(held)

        fun distance(entity: Entity) = FlightMath.horizontalDistance(entity.position(), bossPos)

        val attackerId: String? = boss.lastAttackerUUID
        val attacker = if (attackerId.isNullOrEmpty()) null else candidates.firstOrNull { it.stringUUID == attackerId }
        val nearest = candidates.minByOrNull { distance(it) }

        val chosen = if (Ac130Config.RANDOM_TARGETING.get()) {
            randomChoice(candidates, held, now)
        } else {
            attacker ?: when {
                held != null && nearest != null && nearest !== held &&
                        now - lastSwitchTick >= MIN_DWELL_TICKS &&
                        distance(nearest) < distance(held) * SWITCH_FACTOR -> nearest

                held != null -> held
                else -> nearest
            }
        }

        if (chosen !== target) {
            trackedAimId = -1 // new target: forget the old motion estimate
            lastSwitchTick = now
            if (Ac130Config.DEBUG_LOGGING.get()) {
                SuperbEnemies.LOGGER.info(
                    "[AC-130 boss] target {} -> {} ({} candidates)",
                    target?.name?.string ?: "none", chosen?.name?.string ?: "none", candidates.size
                )
            }
        }
        target = chosen
        targetUuid = chosen?.uuid
    }

    /**
     * `randomTargeting`: the held target stays until [nextRerollTick], then a random one is drawn (it may be the same).
     * Players and player-class dummies are drawn first, mobs only when no such candidate exists. The last-attacker and
     * nearest rules are not used.
     */
    private fun randomChoice(candidates: List<LivingEntity>, held: LivingEntity?, now: Int): LivingEntity? {
        if (held != null && now < nextRerollTick) return held
        if (candidates.isEmpty()) return null
        val players = candidates.filter { it is ServerPlayer || (it is PlayerDummyEntity && dummiesArePlayers()) }
        val pool = players.ifEmpty { candidates }
        val seconds = Ac130Config.RANDOM_TARGET_SECONDS.get()
        nextRerollTick = now + (seconds * 20 * (0.75 + 0.5 * boss.getRandom().nextDouble())).toInt()
        return pool[boss.getRandom().nextInt(pool.size)]
    }

    // ================================================================================================== motion estimate

    private fun trackAim(entity: Entity?) {
        if (entity == null) {
            trackedAimId = -1
            aimVelocity = Vec3.ZERO
            return
        }
        val position = entity.position()
        if (entity.id != trackedAimId) {
            trackedAimId = entity.id
            lastAimPosition = position
            aimVelocity = Vec3.ZERO
            return
        }
        val delta = position.subtract(lastAimPosition)
        lastAimPosition = position
        // A teleport or respawn is not motion.
        aimVelocity = if (delta.lengthSqr() > TELEPORT_DISTANCE_SQR) Vec3.ZERO
        else aimVelocity.scale(1.0 - VELOCITY_SMOOTHING).add(delta.scale(VELOCITY_SMOOTHING))
    }

    private companion object {
        const val TAG_TARGET = "Target"

        const val SCAN_INTERVAL = 20
        const val RESCAN_AFTER_LOSS = 5
        const val LOSE_TARGET_FACTOR = 1.5
        const val SWITCH_FACTOR = 0.7 // a candidate must be at least 30% nearer to steal the lock
        const val MIN_DWELL_TICKS = 200
        const val SKY_TOLERANCE = 2

        const val VELOCITY_SMOOTHING = 0.3
        const val TELEPORT_DISTANCE_SQR = 20.0 * 20.0
    }
}
