package com.teamuhi.superbenemies.entity.boss.a10.ai

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.config.BossConfig
import com.teamuhi.superbenemies.config.TargetCategory
import com.teamuhi.superbenemies.config.TargetingMethod
import com.teamuhi.superbenemies.entity.boss.a10.A10WarthogBossEntity
import com.teamuhi.superbenemies.entity.boss.common.BossCrewEntity
import com.teamuhi.superbenemies.entity.boss.common.BossRespawns
import com.teamuhi.superbenemies.entity.boss.common.ResupplyNotices
import com.teamuhi.superbenemies.entity.boss.common.ai.FlightController
import com.teamuhi.superbenemies.entity.boss.common.ai.FlightMath
import com.teamuhi.superbenemies.entity.boss.common.ai.SkyExposure
import com.teamuhi.superbenemies.entity.boss.common.ai.TargetChoice
import com.teamuhi.superbenemies.entity.boss.common.ai.TargetSight
import com.teamuhi.superbenemies.entity.boss.common.ai.TerrainGuard
import com.teamuhi.superbenemies.entity.boss.common.ai.ThreatMonitor
import com.teamuhi.superbenemies.entity.boss.common.ai.TrackedVehicles
import com.teamuhi.superbenemies.entity.boss.common.ai.categoryOf
import com.teamuhi.superbenemies.entity.boss.common.ai.isEnemyPlane
import com.teamuhi.superbenemies.entity.boss.common.ai.riddenBy
import com.teamuhi.superbenemies.entity.dummy.HumanoidDummyEntity
import com.teamuhi.superbenemies.entity.dummy.TrackedDummies
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Style
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import java.util.EnumMap
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The A-10 boss's decision making: target selection, the attack state machine and the glue between the flight
 * controller, terrain guard, threat monitor and fire control.
 *
 * `tick()` runs on the server **before** `VehicleEntity.baseTick`, so the control inputs written here are in place
 * when SBW's aircraft engine consumes them in `travel()`.
 *
 * All state is per instance (no statics), so several bosses can fly at once. Scans are staggered: targeting every
 * [TARGET_SCAN_INTERVAL] ticks (offset by entity id), threats inside [ThreatMonitor], terrain inside [TerrainGuard].
 *
 * Attack flow: INGRESS -> selector -> (ORBIT_BARRAGE | BOMB_RUN | CLIMB_FOR_DIVE -> GUN_DIVE -> PULL_OUT) -> EXTEND
 * -> selector ... EVADE interrupts any state and returns to it; CRIPPLED is terminal.
 *
 * The double gun run flies GUN_DIVE -> PULL_OUT -> STALL_TURN -> GUN_DIVE -> PULL_OUT -> EXTEND (the cannon-to-rocket and
 * rocket-to-bomb runs are the same flight with another weapon on the second pass); the rocket run with
 * missiles is a rocket CLIMB_FOR_DIVE -> GUN_DIVE that also launches AGM-65s.
 *
 * The bombing variant of the gun run strafes with the cannon from afar and, inside [BOMB_PASS_RANGE], stops firing and
 * dive-bombs the target before pulling out (see [bombPassAim]). If the stick could not go then, it is tossed on the
 * pull-up (see [tryToss]); only when that is missed too does the selector follow the run with a separate BOMB_RUN.
 */
class A10BossBrain(private val boss: A10WarthogBossEntity) {

    private val flight = FlightController(boss)
    private val guard = TerrainGuard(boss)
    private val fire = FireControl(boss)
    private val threats = ThreatMonitor(boss)

    /** Under the `DYNAMIC` targeting method this swaps a covered target for a ghost at its last known position. */
    private val sight = TargetSight(boss)

    var state: BossState = BossState.INGRESS
        private set

    /** True while the boss is away rearming, including an evade flown on the way; the boss bar is hidden meanwhile. */
    val withdrawn: Boolean
        get() = state == BossState.RESUPPLY || (state == BossState.EVADE && resumeState == BossState.RESUPPLY)

    private var phase = Phase.P1
    private var stateTicks = 0
    private var lastAttack: Attack? = null

    // --- target ---
    private var targetUuid: UUID? = null
    private var targetEntity: Entity? = null

    /**
     * What the weapons are aimed at: the target (a player, player dummy or mob), or the vehicle it rides (a plane is its
     * own). While a `DYNAMIC` target is under cover this is [sight]'s ghost, a zero-size stand-in at its last known
     * position; [targetEntity] stays the real one, so validity and range checks never see the ghost.
     */
    private var aim: Entity? = null
    private var aimVelocity: Vec3 = Vec3.ZERO
    private var lastAimPosition: Vec3 = Vec3.ZERO
    private var trackedAimId = -1

    /**
     * Where the target was last seen (refreshed every [KNOWN_POSITION_INTERVAL] ticks); gun runs raze this spot. It
     * follows [aim], so it freezes while a `DYNAMIC` target hides.
     */
    private var lastKnownPos: Vec3? = null

    /**
     * Where the boss last had a target (updated every tick while it has one, unlike [lastKnownPos] which only refreshes
     * every [KNOWN_POSITION_INTERVAL] ticks and is frozen during a run). With no target the boss circles this spot.
     */
    private var lastTargetPos: Vec3? = null
    private var razeOffset: Vec3 = Vec3.ZERO
    private var nextRazeJitterTick = 0
    private var initialScanDone = false

    // --- evade ---
    private var resumeState = BossState.INGRESS
    private var resumeTicks = 0
    private var evadeSide = 1.0
    private var evadeDuration = 35
    private var evadeManeuver = FlareManeuver.DEFAULT
    private var evadeRolled = 0.0
    private var evadeRollTicks = 0
    private var evadeLastRoll = 0f

    // --- per-attack bookkeeping ---
    private var extendDuration = 80
    private var orbitDuration = 350
    private var nextMissileTick = 0
    private var diveWeapon = DiveWeapon.CANNON
    private var diveRocketsLeft = 0
    private var nextDiveRocketTick = 0
    private var gunStreak = 0

    /** Rocket run with homing missiles: launches [DIVE_MISSILES] AGM-65s during the dive, alongside the rockets. */
    private var missileSalvoPending = false
    private var diveMissilesLeft = 0

    /** Stall-turn attacks: a second pass is still to fly after the current one (see [tickPullOut], [tickStallTurn]). */
    private var secondPassPending = false

    /** The weapon of that second pass (the cannon for the plain double gun run). */
    private var secondPassWeapon = DiveWeapon.CANNON

    /**
     * The current dive is the second pass of a stall-turn attack. It starts slow (the plane hung at the top of the
     * climb), so it pulls out earlier and holds more power (see [pullOutBase], [tickGunDive]).
     */
    private var stallPass = false

    // --- stall turn (between the two passes of a double gun run) ---
    private var stallPhase = StallPhase.CLIMB
    private var stallPhaseTicks = 0

    /** Heading held through the climb: the velocity direction is meaningless once the climb is near vertical. */
    private var stallHeading: Vec3? = null

    /** Set when the current gun run is the bombing variant (cannon from afar, bombs when close; see [tickGunDive]). */
    private var bombComboPending = false
    private var nukeRun = false

    // --- bombing variant of the cannon run ---
    /** The run may still drop its bombs: armed on entering GUN_DIVE, cleared by a release, a miss or the next attack. */
    private var tossArmed = false

    /** Dive-bomb pass: integrated aim correction beyond the target (see [bombPassAim]). */
    private var bombAimShift: Vec3 = Vec3.ZERO

    /** The predicted impact has been short of the target during the descent, so the later crossing is the real one. */
    private var tossPrimed = false
    private var tossReleased = false
    private var previousTossAlong = 0.0

    /** Bomb run: the target has been ahead of the plane since the turn-in (see [bombRun]). */
    private var bombApproached = false
    private var extraClimb = 0.0
    private var diveAimId = -1
    private var nextCorridorCheckTick = 0

    private var bombPhase = BombPhase.SETUP
    private var stickEndTick = 0
    private var previousMiss = Double.MAX_VALUE
    private var nukeAim: Vec3? = null

    // --- resupply ---
    private var depot: Vec3? = null

    /** Where the fight was when the boss left to rearm; its replacement appears around here. */
    private var resupplyFrom: Vec3? = null

    // --- taunt ---
    private var tauntPhase = TauntPhase.SETUP
    private var tauntRoll = false
    private var tauntPassed = false
    private var rolling = false
    private var rollDone = false
    private var rollSide = 1.0
    private var rolledDegrees = 0.0
    private var rollTicks = 0
    private var lastRoll = 0f
    private var lastRollTick = 0
    private var flaresDropped = 0
    private var nextTauntFlareTick = 0

    private var guardLatched = false

    private enum class Phase {
        P1, P2, P3;

        /** Multiplier applied to attack cooldowns. */
        val cooldownScale: Double get() = if (this == P3) 0.6 else 1.0

        companion object {
            fun of(healthFraction: Float): Phase = when {
                healthFraction > 0.6f -> P1
                healthFraction > 0.3f -> P2
                else -> P3
            }
        }
    }

    /**
     * Each attack uses one weapon type, except ROCKET_MISSILE_RUN (rockets and missiles together). ORBIT_BARRAGE is the
     * homing missile attack, DOUBLE_GUN_RUN is a gun run, a stall turn and a second gun run; CANNON_ROCKET_STALL
     * and ROCKET_BOMB_STALL are the same shape with a different weapon on each pass (cannon then rockets, rockets
     * then bombs); TAUNT fires nothing.
     */
    private enum class Attack(val entryState: BossState) {
        GUN_DIVE(BossState.CLIMB_FOR_DIVE),
        ROCKET_RUN(BossState.CLIMB_FOR_DIVE),
        ROCKET_MISSILE_RUN(BossState.CLIMB_FOR_DIVE),
        DOUBLE_GUN_RUN(BossState.CLIMB_FOR_DIVE),
        CANNON_ROCKET_STALL(BossState.CLIMB_FOR_DIVE),
        ROCKET_BOMB_STALL(BossState.CLIMB_FOR_DIVE),
        ORBIT_BARRAGE(BossState.ORBIT_BARRAGE),
        BOMB_RUN(BossState.BOMB_RUN),
        NUKE_RUN(BossState.BOMB_RUN),
        TAUNT(BossState.TAUNT)
    }

    private enum class TauntPhase { SETUP, RUN }

    private enum class FlareManeuver { DEFAULT, BARREL_ROLL, CLIMB, SIDE_FLARES }

    /** Stall turn: straight up, the tail swings round while the nose hangs up, then the nose drops onto the target. */
    private enum class StallPhase { CLIMB, SWING, DROP }

    /** The only weapon fired during a CLIMB_FOR_DIVE -> GUN_DIVE run. BOMBS: lines up, then a dive-bomb pass (see [bombPassAim]). */
    private enum class DiveWeapon { CANNON, ROCKETS, BOMBS }

    private enum class BombPhase { SETUP, RUN, STICK }

    // ================================================================================================== main loop

    fun tick() {
        val level = boss.level() as? ServerLevel ?: return

        if (boss.crippled) {
            if (state != BossState.CRIPPLED) enterCrippled()
            flight.release()
            return
        }

        phase = Phase.of(boss.healthFraction())
        stateTicks++

        fire.tick()
        updateTarget(level)
        val target = aim
        target?.let { lastTargetPos = it.position() }

        if (threats.tick()) enterEvade()

        // The target is gone: stop attacking and orbit where it was last seen (INGRESS without a target).
        if (target == null && state.needsTarget()) enter(BossState.INGRESS)

        when (state) {
            BossState.INGRESS -> tickIngress(target)
            BossState.ORBIT_BARRAGE -> if (target != null) tickOrbit(target)
            BossState.BOMB_RUN -> if (target != null) tickBombRun(target)
            BossState.CLIMB_FOR_DIVE -> if (target != null) tickClimbForDive(target)
            BossState.GUN_DIVE -> tickGunDive(target)
            BossState.PULL_OUT -> tickPullOut()
            BossState.STALL_TURN -> if (target != null) tickStallTurn(target)
            BossState.TAUNT -> if (target != null) tickTaunt(target)
            BossState.RESUPPLY -> tickResupply()
            BossState.EXTEND -> tickExtend(target)
            BossState.EVADE -> tickEvade()
            BossState.CRIPPLED -> {}
        }

        threats.applyFlareInput()

        safetyNet()
    }

    private fun BossState.needsTarget(): Boolean =
        this == BossState.ORBIT_BARRAGE || this == BossState.BOMB_RUN ||
                this == BossState.CLIMB_FOR_DIVE || this == BossState.STALL_TURN || this == BossState.TAUNT

    // ================================================================================================== persistence

    fun save(tag: CompoundTag) {
        fire.saveAmmo(tag)
        // A reloaded evade restarts at INGRESS, except mid-resupply: the boss would rejoin silently with empty racks.
        val saved = if (state == BossState.EVADE && resumeState == BossState.RESUPPLY) BossState.RESUPPLY else state
        tag.putString(TAG_STATE, saved.name)
        targetUuid?.let { tag.putUUID(TAG_TARGET, it) }
        lastAttack?.let { tag.putString(TAG_LAST_ATTACK, it.name) }
    }

    fun load(tag: CompoundTag) {
        fire.loadAmmo(tag)
        state = BossState.entries.firstOrNull { it.name == tag.getString(TAG_STATE) } ?: BossState.INGRESS
        // A reloaded evade restarts from the beginning; the other states resume where they were.
        if (state == BossState.EVADE) state = BossState.INGRESS
        if (tag.hasUUID(TAG_TARGET)) targetUuid = tag.getUUID(TAG_TARGET)
        lastAttack = Attack.entries.firstOrNull { it.name == tag.getString(TAG_LAST_ATTACK) }
        stateTicks = 0
    }

    // ================================================================================================== targeting

    private fun updateTarget(level: ServerLevel) {
        val now = boss.tickCount
        var current = targetEntity
        val remembered = targetUuid
        if (current == null && remembered != null) {
            current = level.getEntity(remembered)
            targetEntity = current
        }
        if (current != null && !isValidTarget(current)) {
            current = null
            targetEntity = null
            targetUuid = null
        }
        if (!initialScanDone || (now + boss.id) % TARGET_SCAN_INTERVAL == 0) {
            initialScanDone = true
            pickTarget(level)
            current = targetEntity
        }

        // A player in a vehicle is attacked (and locked onto) through the vehicle. A target under cover (DYNAMIC) is
        // replaced by the ghost at its last known position, which the refresh below then simply stops moving.
        aim = sight.resolve(current?.rootVehicle, BossConfig.TARGETING_METHOD.get())
        trackAim(aim)
        // A gun run keeps razing the spot it started on: a replacement target must not drag the dive somewhere else.
        aim?.let {
            if ((lastKnownPos == null || (now + boss.id) % KNOWN_POSITION_INTERVAL == 0) &&
                (state != BossState.GUN_DIVE || it.id == diveAimId)
            ) {
                lastKnownPos = it.position()
            }
        }
    }

    /**
     * What the target weights allow: an entity is a target only when its category (see [categoryOf]) has a weight above
     * 0. That covers survival / adventure players, dummies (player dummy, dummy soldier) while their `bossesTarget...`
     * option is on (both count as players), living mobs (plain and hostile, weighted separately) and, with a plane
     * weight (experimental), manned aircraft. Read live, so changing the options drops a target that is no longer
     * allowed at once. A dummy whose option is off is a plain mob to [categoryOf], but never an ordinary mob target here.
     * Players, dummies and planes obey the aggro range; mobs obey `mobScanRange`, whatever `globalAggro` says.
     */
    private fun isValidTarget(entity: Entity): Boolean {
        val category = categoryOf(entity)
        if (category == null || BossConfig.WEIGHTS.of(category) <= 0) return false
        if (entity is VehicleEntity) {
            if (!isEnemyPlane(boss, entity)) return false
            if (BossConfig.GLOBAL_AGGRO.get()) return true
            val limit = BossConfig.AGGRO_RANGE.get() * LOSE_TARGET_FACTOR
            val dx = entity.x - boss.x
            val dz = entity.z - boss.z
            return dx * dx + dz * dz <= limit * limit
        }
        val mob = when (entity) {
            is ServerPlayer -> {
                if (entity.isSpectator || entity.isCreative) return false
                false
            }
            is HumanoidDummyEntity -> {
                // Only a dummy the bosses are set to target counts (as a player); otherwise it is not a target at all.
                if (category != TargetCategory.PLAYER) return false
                false
            }
            is Mob -> {
                if (entity.isInvulnerable || !entity.isAttackable ||
                    entity is BossCrewEntity || boss.isCrew(entity) || entity.rootVehicle === boss
                ) return false
                true
            }
            else -> return false
        }
        if (!entity.isAlive || entity.isRemoved) return false
        if (entity.level() !== boss.level()) return false
        if (!mob && BossConfig.GLOBAL_AGGRO.get()) return true
        val range = if (mob) BossConfig.MOB_SCAN_RANGE.get() else BossConfig.AGGRO_RANGE.get()
        val limit = range * LOSE_TARGET_FACTOR
        val dx = entity.x - boss.x
        val dz = entity.z - boss.z
        return dx * dx + dz * dz <= limit * limit
    }

    /**
     * A mob the plane can hit: valid, and exposed to the sky (it would circle forever over a cave mob). Required in both
     * targeting methods: `DYNAMIC` only changes how a player's cover is handled, the plane never commits to a covered
     * mob. Never loads a chunk: [SkyExposure] counts an unloaded one as "not exposed".
     */
    private fun eligibleMob(mob: Mob): Boolean = isValidTarget(mob) && SkyExposure.isExposed(mob)

    /** Runs that must not change victim half way: the aim point and the corridor checks were planned for one target. */
    private fun targetLocked(): Boolean =
        state == BossState.CLIMB_FOR_DIVE || state == BossState.GUN_DIVE || state == BossState.PULL_OUT ||
                state == BossState.STALL_TURN || state == BossState.BOMB_RUN

    /**
     * Every valid target in range, for the categories whose weight is above 0 (a category switched off is not even
     * scanned; [isValidTarget] also drops an individual entity whose own category weight is 0, e.g. zombies with
     * `hostileMobWeight` 0 while `mobWeight` is above 0).
     */
    private fun candidates(level: ServerLevel): MutableList<Entity> {
        val weights = BossConfig.WEIGHTS
        val aggro = if (BossConfig.GLOBAL_AGGRO.get()) Double.MAX_VALUE else BossConfig.AGGRO_RANGE.get().toDouble()
        val here = boss.position()
        val candidates = ArrayList<Entity>()
        if (weights.allowsPlayers) {
            for (player in level.players()) candidates.add(player)
            for (dummy in TrackedDummies.all(level)) {
                if (dummy is HumanoidDummyEntity && dummy.settings.targetedByBosses()) candidates.add(dummy)
            }
            candidates.removeAll { !(isValidTarget(it) && FlightMath.horizontalDistance(it.position(), here) <= aggro) }
        }
        if (weights.allowsPlanes) {
            for (plane in TrackedVehicles.all(level)) {
                if (isValidTarget(plane) && FlightMath.horizontalDistance(plane.position(), here) <= aggro) candidates.add(plane)
            }
        }
        if (weights.allowsMobs) {
            val reach = BossConfig.MOB_SCAN_RANGE.get().toDouble()
            val box = AABB(
                boss.x - reach, level.minBuildHeight.toDouble(), boss.z - reach,
                boss.x + reach, level.maxBuildHeight.toDouble(), boss.z + reach
            )
            // Dummies are Mobs too: they are player-category candidates above (or ignored), never an ordinary mob here.
            for (mob in level.getEntitiesOfClass(Mob::class.java, box) { it !is HumanoidDummyEntity && eligibleMob(it) }) {
                if (FlightMath.horizontalDistance(mob.position(), here) <= reach) candidates.add(mob)
            }
        }
        return candidates
    }

    /** The category weight of [entity] (0 for anything that is not a target). */
    private fun weightOf(entity: Entity): Int = BossConfig.WEIGHTS.of(categoryOf(entity))

    /** Horizontal distance from the boss: the plane works in the ground plane, whatever the altitude difference. */
    private fun distanceTo(entity: Entity): Double = FlightMath.horizontalDistance(entity.position(), boss.position())

    /** Whether [entity] (through the vehicle it rides, as [aim] is) is under cover; always false for `STATIC`. */
    private fun isHidden(entity: Entity, method: TargetingMethod): Boolean = sight.isHidden(entity.rootVehicle, method)

    /**
     * The candidates a choice is made from. `STATIC` uses all of them. `DYNAMIC` prefers the ones the boss can see: the
     * covered ones are used only when nobody is exposed, so the boss keeps hitting a hidden target's last known spot
     * only when there is nothing better to attack.
     */
    private fun poolOf(candidates: List<Entity>, method: TargetingMethod): List<Entity> {
        if (method == TargetingMethod.STATIC) return candidates
        val (hidden, exposed) = candidates.partition { isHidden(it, method) }
        return exposed.ifEmpty { hidden }
    }

    private fun pickTarget(level: ServerLevel) {
        var held = targetEntity?.takeIf { isValidTarget(it) }
        // A run in progress finishes on its victim, on the ghost if the victim has gone under cover.
        if (held != null && targetLocked()) return
        val method = BossConfig.TARGETING_METHOD.get()
        val pool = poolOf(candidates(level), method)
        // A covered target is dropped as soon as somebody exposed can be attacked instead (DYNAMIC only).
        if (held != null && isHidden(held, method) && pool.any { !isHidden(it, method) }) held = null
        // Most recent attacker first, then keep the current target, then the best weighted-distance candidate. With
        // randomTargeting the target only changes when it is lost (a new one is drawn per attack, see rerollTarget),
        // by a draw proportional to the weights.
        val chosen = if (BossConfig.RANDOM_TARGETING.get()) {
            held ?: TargetChoice.weightedRandom(pool, this::weightOf, boss.getRandom())
        } else {
            val attackerId = boss.lastAttackerUUID
            pool.firstOrNull { riddenBy(it, attackerId) }
                ?: held
                ?: TargetChoice.bestScore(pool, this::weightOf, this::distanceTo)
        }
        applyTarget(chosen)
    }

    private fun applyTarget(chosen: Entity?) {
        if (chosen?.uuid != targetUuid) trackedAimId = -1 // new target: forget the old motion estimate
        targetEntity = chosen
        targetUuid = chosen?.uuid
    }

    /**
     * `randomTargeting`: draws a fresh random target (possibly the same one) at the start of an attack, each candidate
     * in proportion to its weight (exposed ones first under `DYNAMIC`, see [poolOf]).
     */
    private fun rerollTarget(level: ServerLevel) {
        val method = BossConfig.TARGETING_METHOD.get()
        val pool = poolOf(candidates(level), method)
        // Draw by weight among the targets within reach so a far-off draw does not park the boss on a long flight; if
        // nobody is, go for the best weighted distance.
        val near = pool.filter { distanceTo(it) <= RANDOM_TARGET_REACH }
        val chosen = TargetChoice.weightedRandom(near, this::weightOf, boss.getRandom())
            ?: TargetChoice.bestScore(pool, this::weightOf, this::distanceTo) ?: return
        applyTarget(chosen)
        aim = sight.resolve(chosen.rootVehicle, method)
        trackAim(aim)
        lastKnownPos = aim?.position()
    }

    /** Smoothed per-tick velocity estimate from position differences (server-side player velocity is unreliable). */
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

    // ================================================================================================== helpers

    private fun cooldown(base: Int): Int = max(1, (base * phase.cooldownScale).toInt())

    private fun randomBetween(min: Int, max: Int): Int = min + boss.getRandom().nextInt(max - min + 1)

    private fun referenceY(): Double = aim?.y ?: lastTargetPos?.y ?: boss.homePos.y

    private fun cruiseAltitude(): Double =
        min(referenceY() + BossConfig.CRUISE_HEIGHT.get(), boss.level().maxBuildHeight + 60.0)

    private fun currentHeading(): Vec3 = FlightMath.horizontal(boss.deltaMovement, FlightMath.dir(boss.yRot, 0f))

    private fun awayFrom(entity: Entity): Vec3 =
        FlightMath.horizontal(Vec3(boss.x - entity.x, 0.0, boss.z - entity.z), currentHeading())

    private fun towards(point: Vec3): Vec3 =
        FlightMath.horizontal(Vec3(point.x - boss.x, 0.0, point.z - boss.z), currentHeading())

    private fun horizontalSpeed(): Double = hypot(boss.deltaMovement.x, boss.deltaMovement.z)

    /** Flies toward [dir] after the terrain guard has raised its pitch if the ground is coming up. */
    private fun fly(
        dir: Vec3,
        throttle: Float,
        maxBank: Float = 60f,
        airBrake: Boolean = false,
        converge: Boolean = false,
        guarded: Boolean = true
    ) {
        val wanted = if (guarded) guard.adjustDesired(dir, referenceY() + UNKNOWN_TERRAIN_OFFSET) else dir
        flight.steer(wanted, throttle, maxBank, airBrake, converge)
    }

    /** Throttle trim for the SBW-flown orbit: its own throttle logic tends to settle below the lift speed. */
    private fun trimOrbitThrottle(cruise: Double) {
        val altitudeError = cruise - boss.y
        val wanted = (ORBIT_THROTTLE + altitudeError * 0.004).coerceIn(ORBIT_THROTTLE_MIN, ORBIT_THROTTLE_MAX)
        boss.power = Mth.lerp(0.1f, boss.power, wanted.toFloat())
    }

    private fun ensureLoiter(x: Double, altitude: Double, z: Double, radius: Double) {
        val p = boss.loiterParams
        if (abs(p.x() - x) > 2.0 || abs(p.y() - altitude) > 2.0 || abs(p.z() - z) > 2.0 || abs(p.w() - radius) > 1.0) {
            boss.loiterParams = Quaternionf(x.toFloat(), altitude.toFloat(), z.toFloat(), radius.toFloat())
        }
        if (!boss.loiterActive) boss.loiterActive = true
    }

    // ================================================================================================== transitions

    /** @param resume true when returning from an evade: keeps the per-state progress */
    private fun enter(next: BossState, resume: Boolean = false) {
        if (next == state && !resume) return
        state = next
        stateTicks = 0
        // Only the orbit (and the idle loiter inside INGRESS) use SBW's autopilot; both re-enable it themselves.
        if (next != BossState.ORBIT_BARRAGE) boss.loiterActive = false
        if (resume) return

        when (next) {
            BossState.ORBIT_BARRAGE -> {
                orbitDuration = randomBetween(100, 150)
                nextMissileTick = boss.tickCount + cooldown(30)
            }

            BossState.BOMB_RUN -> {
                bombPhase = BombPhase.SETUP
                bombApproached = false
                previousMiss = Double.MAX_VALUE
                if (!nukeRun) nukeAim = null
                fire.selectBombVariant(nukeRun)
            }

            BossState.CLIMB_FOR_DIVE -> {
                extraClimb = 0.0
                stallPass = false
            }

            BossState.GUN_DIVE -> {
                aim?.let { lastKnownPos = it.position() } // the pilot has eyes on the target as the run starts
                diveAimId = aim?.id ?: -1
                nextCorridorCheckTick = 0
                diveRocketsLeft = randomBetween(DIVE_ROCKETS_MIN, DIVE_ROCKETS_MAX)
                nextDiveRocketTick = 0
                diveMissilesLeft = if (missileSalvoPending) DIVE_MISSILES else 0
                tossArmed = bombsAvailable() &&
                        ((bombComboPending && diveWeapon == DiveWeapon.CANNON) || diveWeapon == DiveWeapon.BOMBS)
                tossPrimed = false
                tossReleased = false
                previousTossAlong = 0.0
                bombAimShift = Vec3.ZERO
            }

            BossState.STALL_TURN -> {
                stallPhase = StallPhase.CLIMB
                stallPhaseTicks = 0
                stallHeading = currentHeading()
                stallPass = true // the dive that follows is the slow second pass
            }

            BossState.RESUPPLY -> {
                depot = null
                boss.announce(MSG_WITHDRAW, WITHDRAW_STYLE)
            }

            BossState.TAUNT -> {
                tauntPhase = TauntPhase.SETUP
                tauntRoll = boss.getRandom().nextBoolean()
                tauntPassed = false
                rolling = false
                rollDone = false
                rolledDegrees = 0.0
                rollTicks = 0
                rollSide = if (boss.getRandom().nextBoolean()) 1.0 else -1.0
                flaresDropped = 0
                nextTauntFlareTick = 0
            }

            BossState.EXTEND -> extendDuration = randomBetween(20, 30)
            else -> {}
        }
    }

    /**
     * Weighted random attack choice, weighted by one `*Chance` config option per attack type: gun run, gun run with
    * bombs, rocket run, homing missile orbit and bomb run. An attack type whose weapon is
     * disabled (or spent, with limited ammo) is not drawn. The two gun run variants are one [Attack.GUN_DIVE]; the
     * bombing variant drops its bombs on the target, and only when that release was missed is the run followed by a
     * separate bomb run on the same target. A cannon run may follow itself up to [MAX_GUN_STREAK] times, the others
     * never repeat back to back.
     */
    private fun selectNext() {
        if (aim == null || targetEntity == null || !isValidTarget(targetEntity!!)) {
            enter(BossState.INGRESS)
            return
        }
        // Bombing variant whose toss did not happen: a separate bomb run (any phase) on the same target, while bombs are
        // enabled, chanced above 0 and in stock. Decided before the target re-roll, which that run must skip.
        val fallbackBomb = bombComboPending && lastAttack == Attack.GUN_DIVE && !tossReleased && bombsAvailable()
        tossArmed = false
        // A new victim per attack, preferably within reach. One drawn beyond engagement range is flown to first (INGRESS never re-draws).
        if (!fallbackBomb && state != BossState.INGRESS && BossConfig.RANDOM_TARGETING.get()) {
            (boss.level() as? ServerLevel)?.let { rerollTarget(it) }
            val drawn = aim
            if (drawn == null || FlightMath.horizontalDistance(drawn.position(), boss.position()) > INGRESS_ENGAGE_DISTANCE) {
                enter(BossState.INGRESS)
                return
            }
        }
        if (fallbackBomb) {
            bombComboPending = false
            gunStreak = 0
            nukeRun = false
            lastAttack = Attack.BOMB_RUN
            enter(BossState.BOMB_RUN)
            return
        }
        // One configured chance per attack type. A type is only in the draw when every weapon it uses is enabled (read
        // live, so a weapon can be switched off between attacks) and its chance is above 0.
        val cannon = BossConfig.CANNON_ENABLED.get()
        val bombs = BossConfig.BOMBS_ENABLED.get()
        var gunPlain = if (cannon) BossConfig.GUN_RUN_CHANCE.get() else 0
        var gunBombing = if (cannon && bombs) BossConfig.GUN_RUN_BOMB_CHANCE.get() else 0
        var rocketRun = if (BossConfig.ROCKETS_ENABLED.get()) BossConfig.ROCKET_RUN_CHANCE.get() else 0
        var bombRun = if (bombs) BossConfig.BOMB_RUN_CHANCE.get() else 0
        var nukeRunChance = if (bombs && BossConfig.NUKE_ENABLED.get()) BossConfig.NUKE_RUN_CHANCE.get() else 0
        val missiles = BossConfig.MISSILES_ENABLED.get()
        val rockets = BossConfig.ROCKETS_ENABLED.get()
        var missileOrbit = if (missiles) BossConfig.MISSILE_ORBIT_CHANCE.get() else 0
        var rocketMissile = if (rockets && missiles) BossConfig.ROCKET_MISSILE_RUN_CHANCE.get() else 0
        var doubleGun = if (cannon) BossConfig.DOUBLE_GUN_RUN_CHANCE.get() else 0
        var cannonRocket = if (cannon && rockets) BossConfig.CANNON_ROCKET_STALL_CHANCE.get() else 0
        var rocketBomb = if (rockets && bombs) BossConfig.ROCKET_BOMB_STALL_CHANCE.get() else 0
        // Finite ammo: an attack whose weapon is spent is skipped; with every allowed attack spent the boss goes to rearm.
        if (BossConfig.LIMITED_AMMO.get()) {
                val hadWeapons = gunPlain + gunBombing + rocketRun + bombRun + nukeRunChance + missileOrbit + rocketMissile + doubleGun +
                    cannonRocket + rocketBomb > 0
            if (!fire.hasAmmo(FireControl.CANNON)) { gunPlain = 0; gunBombing = 0; doubleGun = 0; cannonRocket = 0 }
            if (!fire.hasAmmo(FireControl.BOMB)) { gunBombing = 0; bombRun = 0; rocketBomb = 0 }
            if (!fire.hasAmmo(FireControl.NUKE)) nukeRunChance = 0
            if (!fire.hasAmmo(FireControl.ROCKET)) { rocketRun = 0; rocketMissile = 0; cannonRocket = 0; rocketBomb = 0 }
            if (!fire.hasAmmo(FireControl.MISSILE)) { missileOrbit = 0; rocketMissile = 0 }
            if (hadWeapons && gunPlain + gunBombing + rocketRun + bombRun + missileOrbit + rocketMissile + doubleGun +
                cannonRocket + rocketBomb + nukeRunChance == 0
            ) {
                enter(BossState.RESUPPLY)
                return
            }
        }
        val weights = EnumMap<Attack, Int>(Attack::class.java)
        // Both gun run variants are one attack; the variant is rolled below in proportion to their chances.
        weights[Attack.GUN_DIVE] = gunPlain + gunBombing
        weights[Attack.ROCKET_RUN] = rocketRun
        weights[Attack.ROCKET_MISSILE_RUN] = rocketMissile
        weights[Attack.DOUBLE_GUN_RUN] = doubleGun
        weights[Attack.CANNON_ROCKET_STALL] = cannonRocket
        weights[Attack.ROCKET_BOMB_STALL] = rocketBomb
        weights[Attack.BOMB_RUN] = bombRun
        weights[Attack.NUKE_RUN] = nukeRunChance
        weights[Attack.ORBIT_BARRAGE] = missileOrbit
        weights.values.removeIf { it <= 0 }
        // A rare, harmless show-off pass; it never replaces a real attack when the others are all switched off.
        // Not against a hidden target either: there is nobody to show off to.
        if (BossConfig.TAUNT_ENABLED.get() && weights.isNotEmpty() && !sight.hidden) weights[Attack.TAUNT] = TAUNT_WEIGHT
        val last = lastAttack
        if (last != null && (last != Attack.GUN_DIVE || gunStreak >= MAX_GUN_STREAK) &&
            weights.entries.any { it.key != last && it.value > 0 }
        ) {
            weights.remove(last)
        }
        val total = weights.values.sum()
        var roll = if (total > 0) boss.getRandom().nextInt(total) else 0
        // No weapon allowed (or usable): fall back to a passive orbit, orbitFire() is gated by the missile toggle.
        var attack = Attack.ORBIT_BARRAGE
        if (total > 0) {
            for ((candidate, weight) in weights) {
                if (roll < weight) {
                    attack = candidate
                    break
                }
                roll -= weight
            }
        }
        // The first pass's weapon; the stall-turn attacks name the second pass's weapon separately.
        val rocketAttack = attack == Attack.ROCKET_RUN || attack == Attack.ROCKET_MISSILE_RUN ||
                attack == Attack.ROCKET_BOMB_STALL
        diveWeapon = if (rocketAttack) DiveWeapon.ROCKETS else DiveWeapon.CANNON
        missileSalvoPending = attack == Attack.ROCKET_MISSILE_RUN
        secondPassPending = attack == Attack.DOUBLE_GUN_RUN || attack == Attack.CANNON_ROCKET_STALL ||
                attack == Attack.ROCKET_BOMB_STALL
        secondPassWeapon = when (attack) {
            Attack.CANNON_ROCKET_STALL -> DiveWeapon.ROCKETS
            Attack.ROCKET_BOMB_STALL -> DiveWeapon.BOMBS
            else -> DiveWeapon.CANNON
        }
        stallPass = false
        gunStreak = if (attack == Attack.GUN_DIVE) gunStreak + 1 else 0
        // Each gun run picks its variant up front; the plain one (and every other attack) clears the flag.
        val gunWeight = gunPlain + gunBombing
        bombComboPending = attack == Attack.GUN_DIVE && gunWeight > 0 &&
            boss.getRandom().nextInt(gunWeight) < gunBombing
        nukeRun = attack == Attack.NUKE_RUN
        nukeAim = if (nukeRun) aim?.position() else null
        fire.selectBombVariant(false)
        if (nukeRun) fire.selectBombVariant(true)
        lastAttack = attack
        // Picking the state we are already in (only when nothing else is allowed) still restarts it.
        if (state == attack.entryState) state = BossState.EXTEND
        enter(attack.entryState)
    }

    private fun bombsAvailable(): Boolean =
        BossConfig.BOMBS_ENABLED.get() && fire.hasAmmo(FireControl.BOMB)

    private fun enterEvade() {
        if (state == BossState.CRIPPLED) return
        if (state == BossState.EVADE) {
            evadeDuration = max(evadeDuration, stateTicks + 25) // a second missile extends the break
            return
        }
        resumeState = state
        resumeTicks = stateTicks
        evadeSide = if (boss.getRandom().nextBoolean()) 1.0 else -1.0
        evadeDuration = randomBetween(30, 40)
        evadeManeuver = if (BossConfig.DEFENSIVE_FLARES.get()) selectFlareManeuver() else FlareManeuver.DEFAULT
        evadeRolled = 0.0
        evadeRollTicks = 0
        evadeLastRoll = boss.roll
        enter(BossState.EVADE)
    }

    private fun selectFlareManeuver(): FlareManeuver {
        val defaultChance = BossConfig.DEFAULT_FLARE_CHANCE.get()
        val barrelRollChance = BossConfig.BARREL_ROLL_FLARE_CHANCE.get()
        val climbChance = BossConfig.CLIMB_FLARE_CHANCE.get()
        val sideFlaresChance = BossConfig.SIDE_FLARES_CHANCE.get()
        val total = defaultChance + barrelRollChance + climbChance + sideFlaresChance
        if (total <= 0) return FlareManeuver.DEFAULT

        var roll = boss.getRandom().nextInt(total)
        if (roll < defaultChance) return FlareManeuver.DEFAULT
        roll -= defaultChance
        if (roll < barrelRollChance) return FlareManeuver.BARREL_ROLL
        roll -= barrelRollChance
        if (roll < climbChance) return FlareManeuver.CLIMB
        return FlareManeuver.SIDE_FLARES
    }

    private fun enterCrippled() {
        state = BossState.CRIPPLED
        stateTicks = 0
        boss.loiterActive = false
        flight.release()
    }

    // ================================================================================================== states

    private fun tickIngress(target: Entity?) {
        val cruise = cruiseAltitude()

        if (target == null) {
            // Nobody to fight: circle the spot where the last target was seen (it died, left or logged off) until a new
            // one turns up; the home position only before the boss has had a target at all.
            val spot = lastTargetPos ?: boss.homePos
            ensureLoiter(spot.x, cruise, spot.z, BossConfig.ORBIT_RADIUS.get().toDouble())
            trimOrbitThrottle(cruise)
            return
        }

        val altitudeError = cruise - boss.y
        val heading = if (FlightMath.horizontalDistance(boss.position(), target.position()) < 40.0) currentHeading()
        else towards(target.position())

        fly(FlightMath.tilted(heading, (altitudeError * 0.12).coerceIn(-4.0, 22.0)), 2.2f)

        val distance = FlightMath.horizontalDistance(boss.position(), target.position())
        if (distance < INGRESS_ENGAGE_DISTANCE && (altitudeError < INGRESS_ARRIVAL_MARGIN || stateTicks > INGRESS_TIMEOUT)) {
            selectNext()
        }
    }

    private fun tickOrbit(target: Entity) {
        val cruise = cruiseAltitude()
        ensureLoiter(target.x, cruise, target.z, BossConfig.ORBIT_RADIUS.get().toDouble())
        trimOrbitThrottle(cruise)

        // The autopilot sheds altitude in tight banked turns: climb back instead of orbiting low.
        if (boss.y < cruise - ALTITUDE_RECOVERY && stateTicks > 60) {
            enter(BossState.INGRESS)
            return
        }

        orbitFire(target)
        if (stateTicks >= orbitDuration) selectNext()
    }

    /** The orbit fires nothing but AGM-65s, one every 60-90 ticks (shortened in the final phase). */
    private fun orbitFire(target: Entity) {
        val now = boss.tickCount
        if (!BossConfig.MISSILES_ENABLED.get() || BossConfig.MISSILE_ORBIT_CHANCE.get() <= 0) return
        if (now >= nextMissileTick && fire.fireMissile(target, guided = !sight.isGhost(target))) {
            nextMissileTick = now + cooldown(randomBetween(60, 90))
        }
    }

    /** A random point on a ring 6-12 blocks around the target, led by the target's motion. */
    private fun rocketRipplePoint(target: Entity): Vec3 {
        val angle = boss.getRandom().nextDouble() * Math.PI * 2.0
        val radius = 6.0 + boss.getRandom().nextDouble() * 6.0
        val speed = boss.getProjectileVelocity("Rocket").toDouble().coerceAtLeast(1.0)
        val base = Vec3(target.x + cos(angle) * radius, target.y, target.z + sin(angle) * radius)
        val flightTicks = boss.position().distanceTo(base) / speed
        return base.add(aimVelocity.scale(flightTicks))
    }

    // ---------------------------------------------------------------------------------------------- bomb run

    private fun tickBombRun(target: Entity) {
        val runAltitude = max(cruiseAltitude(), target.y + BOMB_MIN_HEIGHT)
        val altitudeError = runAltitude - boss.y

        when (bombPhase) {
            BombPhase.SETUP -> {
                // A straight run needs room: first open the distance, then line up.
                val distance = FlightMath.horizontalDistance(boss.position(), target.position())
                if (distance < BOMB_RUN_IN_DISTANCE) {
                    fly(FlightMath.tilted(awayFrom(target), (altitudeError * 0.1).coerceIn(-6.0, 12.0)), 2.4f)
                } else {
                    bombPhase = BombPhase.RUN
                    previousMiss = Double.MAX_VALUE
                }
                if (stateTicks > BOMB_SETUP_TIMEOUT) enter(BossState.EXTEND)
            }

            BombPhase.RUN -> bombRun(target, altitudeError)

            BombPhase.STICK -> {
                // Hold course and altitude while the rest of the stick leaves the rack.
                fly(FlightMath.tilted(currentHeading(), (altitudeError * 0.05).coerceIn(-3.0, 3.0)), 2.0f, maxBank = 10f)
                if (boss.tickCount >= stickEndTick) enter(BossState.EXTEND)
            }
        }
    }

    private fun bombRun(target: Entity, altitudeError: Double) {
        // Aim for where the target will be when the bombs arrive.
        val predicted = if (nukeRun) nukeAim ?: target.position() else fire.bombLeadPoint(target)
        val heading = towards(predicted)
        fly(FlightMath.tilted(heading, (altitudeError * 0.08).coerceIn(-5.0, 8.0)), 2.0f, maxBank = 45f)

        // Far from the release point the flat-ground estimate is enough; SBW's terrain-sampling prediction (hundreds of
        // heightmap reads per call) only runs once the plane is close.
        // A Mk-84 should still release over roofs and enclosed targets. Terrain sampling treats a rooftop as the
        // impact surface and can keep the precise pipper outside the narrow Mk-84 release window indefinitely.
        val flat = fire.estimateBombImpact(target.y)
        val impact = if (nukeRun || FlightMath.horizontalDistance(flat, predicted) > COARSE_MISS_RANGE) {
            flat
        } else {
            fire.predictBombImpact()
        }

        // The stick falls along the flight path, one bomb per STICK_INTERVAL ticks. Release the first bomb one spacing
        // short of the target so the middle bomb lands on it and the others straddle it.
        val track = FlightMath.horizontal(boss.deltaMovement, heading)
        val spacing = horizontalSpeed() * STICK_INTERVAL
        // A Mk-84 run releases one bomb; applying the Mk-82 stick spacing makes the single bomb land short.
        val aimPoint = if (nukeRun) predicted else predicted.subtract(track.scale(spacing))
        val miss = FlightMath.horizontalDistance(impact, aimPoint)

        val ahead = (predicted.x - boss.x) * track.x + (predicted.z - boss.z) * track.z
        // Release at the closest approach: the miss was shrinking and has started to grow.
        val pastMinimum = previousMiss < RELEASE_WINDOW && miss > previousMiss
        previousMiss = miss

        // SETUP ends with the plane flying away from the target, so "behind" only means overflown once the turn-in is
        // done and the target has been ahead at least once (otherwise every pass was abandoned on its first tick).
        if (ahead > 0.0) bombApproached = true

        when {
            miss < RELEASE_RADIUS || pastMinimum -> releaseStick(miss, predicted, impact)
            // The Mk-84 blast is large and its flat-ground pipper can jump over a roof/building edge. Keep the
            // single-bomb attack armed through the overflight and retry instead of losing the whole pass.
            nukeRun && bombApproached && ahead < -spacing - 20.0 -> releaseStick(miss, predicted, impact)
            bombApproached && ahead < -spacing - 20.0 -> enter(BossState.EXTEND) // overflown: the pass is lost
            stateTicks > BOMB_SETUP_TIMEOUT + BOMB_RUN_TIMEOUT -> enter(BossState.EXTEND) // never lined up
        }
    }

    private fun releaseStick(predictedMiss: Double, predictedTarget: Vec3, impact: Vec3) {
        if (!BossConfig.BOMBS_ENABLED.get()) { // switched off mid-run: abandon the pass
            enter(BossState.EXTEND)
            return
        }
        if (nukeRun) {
            if (!fire.dropNuke()) return // rack busy: try again next tick while the release window holds
            nukeRun = false
            nukeAim = null
            fire.selectBombVariant(false)
            enter(BossState.EXTEND)
            return
        }
        if (!fire.dropBomb()) return // rack busy: try again next tick (the pass times out via the overfly check)
        fire.dropBombStick(BOMBS_PER_STICK - 1, STICK_INTERVAL)
        bombPhase = BombPhase.STICK
        stickEndTick = boss.tickCount + (BOMBS_PER_STICK - 1) * STICK_INTERVAL + STICK_EXTRA_TICKS
        if (BossConfig.DEBUG_LOGGING.get()) {
            SuperbEnemies.LOGGER.info(
                "[A10 boss] bomb release: predicted miss {} blocks, impact {}, predicted target {}, target now {}",
                "%.1f".format(predictedMiss), impact, predictedTarget, aim?.position()
            )
        }
    }

    // ---------------------------------------------------------------------------------------------- gun dive

    /** Gap between the planned dive from [from] to [known] and the terrain under it (see [TerrainGuard.diveClearance]). */
    private fun diveClearance(from: Vec3, known: Vec3): Double {
        val ground = max(guard.terrainAt(known.x, known.z, known.y), known.y)
        return guard.diveClearance(
            from, Vec3(known.x, ground + 0.5, known.z), ground + pullOutBase(), known.y + UNKNOWN_TERRAIN_OFFSET
        )
    }

    /** Height above the target ground at which the pull-out bottoms out; the slow second pass of a stall turn keeps more. */
    private fun pullOutBase(): Double = if (stallPass) STALL_PULL_OUT_BASE else PULL_OUT_BASE

    private fun pullOutSpeedFactor(): Double = if (stallPass) STALL_PULL_OUT_SPEED_FACTOR else PULL_OUT_SPEED_FACTOR

    /**
     * Climbs away from the target to the dive start height, then checks that the straight dive line (and the run-out
     * beyond the target) clears every ridge. A blocked line is answered with a higher start, up to
     * [DIVE_MAX_EXTRA_CLIMB] blocks; if it is still blocked (or the climb times out) the attack is abandoned.
     */
    private fun tickClimbForDive(target: Entity) {
        val diveTop = min(cruiseAltitude() + DIVE_TOP_EXTRA + extraClimb, boss.level().maxBuildHeight + 60.0)
        val altitudeError = diveTop - boss.y
        val distance = FlightMath.horizontalDistance(boss.position(), target.position())
        // At a low cruise height the run-in is what gives the gun time to fire, so never start it close.
        // The bombing variant starts farther out, so the cannon still gets a long strafe before the bomb pass.
        val minStandoff = if (bombComboPending && diveWeapon == DiveWeapon.CANNON) BOMB_PASS_STANDOFF else MIN_DIVE_STANDOFF
        val standoff = max(DIVE_STANDOFF_FACTOR * (diveTop - target.y), minStandoff)

        fly(FlightMath.tilted(awayFrom(target), (altitudeError * 0.12).coerceIn(-4.0, 20.0)), 2.4f)

        val timedOut = stateTicks > CLIMB_TIMEOUT
        if ((distance >= standoff * 0.95 && altitudeError < 20.0) || timedOut) {
            val known = lastKnownPos ?: target.position()
            when {
                diveClearance(boss.position(), known) >= DIVE_CLEARANCE_MIN -> enter(BossState.GUN_DIVE)
                !timedOut && extraClimb < DIVE_MAX_EXTRA_CLIMB -> extraClimb += DIVE_CLIMB_STEP
                else -> enter(BossState.EXTEND)
            }
        }
    }

    /**
     * The cannon run razes the target's last known position: no lead, a scatter of a few blocks that is re-rolled every
     * few ticks and long bursts, so a player who keeps moving is missed and one who stands still is shredded. Rocket
     * runs still lead the live target. Without a live target (it left or died mid-run) the run finishes on the
     * last known position.
     */
    private fun tickGunDive(target: Entity?) {
        val known = lastKnownPos ?: target?.position() ?: boss.homePos
        val origin = boss.getShootPos("Cannon", 1f)
        val speed = boss.deltaMovement.length()

        // Bombing variant: the cannon strafes from afar, then inside BOMB_PASS_RANGE the run turns into a dive-bomb pass.
        val bombPass = tossArmed && !tossReleased && bombsAvailable() &&
                FlightMath.horizontalDistance(boss.position(), known) < BOMB_PASS_RANGE

        val aimPoint = when {
            bombPass -> bombPassAim(known)
            diveWeapon == DiveWeapon.CANNON -> fire.cannonAimAt(razePoint(known))
            diveWeapon == DiveWeapon.BOMBS -> fire.cannonAimAt(known) // lines up; the bombs only go inside BOMB_PASS_RANGE
            target != null -> fire.cannonLeadPoint(target)
            else -> fire.cannonAimAt(known)
        }
        val wanted = aimPoint.subtract(origin)

        // Unguarded on purpose: the dive goes low by design and ends at the pull-out altitude below. The slow second
        // pass of a stall turn keeps more power on so it does not sink into the ground at low speed.
        val throttle = if (stallPass) STALL_DIVE_THROTTLE else DIVE_THROTTLE
        fly(wanted, throttle, maxBank = 70f, airBrake = speed > DIVE_BRAKE_SPEED, converge = true, guarded = false)

        // Mountains: re-check the dive line every few ticks and break off while there is still room to pull out.
        if (boss.tickCount >= nextCorridorCheckTick) {
            nextCorridorCheckTick = boss.tickCount + CORRIDOR_CHECK_INTERVAL
            if (diveClearance(boss.position(), known) < DIVE_ABORT_CLEARANCE) {
                enter(BossState.PULL_OUT)
                return
            }
        }

        val error = flight.angleErrorDeg(wanted)
        val range = origin.distanceTo(known)
        // No line-of-sight test: the barrage is fired at the spot whatever stands in between (the shells break blocks).
        val clear = range < MAX_GUN_RANGE
        // One weapon type per run.
        when (diveWeapon) {
            DiveWeapon.CANNON -> if (!bombPass && error < RAZE_FIRE_ANGLE && clear && BossConfig.CANNON_ENABLED.get()) {
                fire.fireCannon(raze = true) // burst timing and cooldown are handled inside FireControl
            }

            DiveWeapon.ROCKETS -> if (target != null && clear && diveRocketsLeft > 0 && error < DIVE_ROCKET_ANGLE &&
                BossConfig.ROCKETS_ENABLED.get() &&
                range < MAX_DIVE_ROCKET_RANGE && boss.tickCount >= nextDiveRocketTick &&
                fire.fireRocket(rocketRipplePoint(target))
            ) {
                diveRocketsLeft--
                nextDiveRocketTick = boss.tickCount + ROCKET_INTERVAL
            }

            DiveWeapon.BOMBS -> {} // dropped by the bomb pass / toss below
        }

        // Rocket run with homing missiles: the AGM-65s leave while the rockets are being fired (FireControl paces them).
        if (diveWeapon == DiveWeapon.ROCKETS && target != null && diveMissilesLeft > 0 && clear &&
            BossConfig.MISSILES_ENABLED.get() && range < MAX_DIVE_ROCKET_RANGE &&
            fire.fireMissile(target, guided = !sight.isGhost(target))
        ) {
            diveMissilesLeft--
        }

        val ground = max(guard.terrainAt(known.x, known.z, known.y), known.y)
        val basePullOut = ground + pullOutBase() + pullOutSpeedFactor() * speed * speed
        // Also respect terrain on the way (a mountain beside the target), but never demand more than a bounded raise.
        val pullOutAltitude = max(basePullOut, min(guard.floorAhead(known.y + UNKNOWN_TERRAIN_OFFSET), basePullOut + 60.0))
        val passedTarget = FlightMath.horizontalDistance(boss.position(), known) < 120.0 &&
                (known.x - boss.x) * currentHeading().x + (known.z - boss.z) * currentHeading().z < 0.0
        val diveOver = boss.y <= pullOutAltitude || passedTarget || stateTicks > DIVE_TIMEOUT

        // Bombs away (then climb out), or still short when the dive has to end: drop anyway if close enough to count.
        if (bombPass && tryBombPassRelease(known, lastDive = diveOver)) {
            enter(BossState.PULL_OUT)
            return
        }
        if (diveOver) enter(BossState.PULL_OUT)

        // Still armed after the pass (bomb rack busy, or the stick would have landed too far off): the toss on the pull-up
        // is the second chance (it primes here, during the dive).
        if (tossArmed && !tossReleased) tryToss()
    }

    /** Where the bomb released now would land: flat-ground estimate, terrain-aware close to the target. */
    private fun bombImpactNear(known: Vec3): Vec3 {
        val ground = max(guard.terrainAt(known.x, known.z, known.y), known.y)
        val flat = fire.estimateBombImpact(ground)
        return if (FlightMath.horizontalDistance(flat, known) > TOSS_PRECISE_RANGE) flat else fire.predictBombImpact()
    }

    /**
     * Dive-bomb steering: in a dive aimed at the target the bomb falls short (gravity), so the aim point is pushed beyond
     * the target by an integrated correction ([bombAimShift]) until the predicted impact sits on it. That flattens the
     * dive slightly, the way a pilot walks the CCIP pipper onto the target.
     */
    private fun bombPassAim(known: Vec3): Vec3 {
        val impact = bombImpactNear(known)
        val shortfall = Vec3(known.x - impact.x, 0.0, known.z - impact.z)
        var shift = bombAimShift.add(shortfall.scale(BOMB_AIM_GAIN))
        if (shift.length() > BOMB_AIM_MAX_SHIFT) shift = shift.normalize().scale(BOMB_AIM_MAX_SHIFT)
        bombAimShift = shift
        return known.add(shift).add(0.0, 0.5, 0.0)
    }

    /**
     * Releases the stick when the predicted impact is within [BOMB_PASS_LEAD] short of the target (so the stick straddles
     * it) and within [TOSS_MAX_LATERAL] of the track; on the [lastDive] tick also whenever the miss is under
     * [BOMB_PASS_MAX_MISS] (near enough for the blast). Returns true once the bombs are away.
     */
    private fun tryBombPassRelease(known: Vec3, lastDive: Boolean): Boolean {
        val impact = bombImpactNear(known)
        val track = currentHeading()
        val dx = impact.x - known.x
        val dz = impact.z - known.z
        val along = dx * track.x + dz * track.z
        val lateral = abs(dx * track.z - dz * track.x)
        val onTarget = along >= -BOMB_PASS_LEAD && lateral < TOSS_MAX_LATERAL
        val closeEnough = lastDive && hypot(dx, dz) < BOMB_PASS_MAX_MISS
        if (!onTarget && !closeEnough) return false
        if (!fire.dropBomb()) return false // rack busy: next tick, or the pull-up toss
        fire.dropBombStick(BOMBS_PER_STICK - 1, BOMB_PASS_STICK_INTERVAL)
        tossReleased = true
        tossArmed = false
        if (BossConfig.DEBUG_LOGGING.get()) {
            SuperbEnemies.LOGGER.info(
                "[A10 boss] gun run bomb release ({}): along {}, lateral {}, impact {}, target {}",
                if (onTarget) "on target" else "end of dive", "%.1f".format(along), "%.1f".format(lateral), impact, known
            )
        }
        return true
    }

    /**
     * Toss bombing for the bombing variant of the cannon run. In a dive a bomb's predicted impact is always short of the
     * aim point (gravity); once the nose comes up the impact point sweeps forward through the target. This releases the
     * stick at that crossing, which is why it runs on the dive tail, the pull-up and the extension.
     *
     * Primed while descending and still short of the target (so the turn-in at the dive start, when the impact point can
     * be anywhere, never releases); released when the impact reaches [TOSS_LEAD] short of the last known position and is
     * within [TOSS_MAX_LATERAL] of the track. Giving up (crossed off to the side, swept [TOSS_GIVE_UP] past, or the
     * plane stopped descending without ever being primed) disarms it, and [selectNext] flies the separate bomb run.
     */
    private fun tryToss() {
        if (!bombsAvailable()) { // switched off or spent mid-run
            tossArmed = false
            return
        }
        val known = lastKnownPos ?: return
        val ground = max(guard.terrainAt(known.x, known.z, known.y), known.y)
        // Far from the target the flat-ground estimate is enough; the terrain-aware prediction only runs close in.
        val flat = fire.estimateBombImpact(ground)
        val impact = if (FlightMath.horizontalDistance(flat, known) > TOSS_PRECISE_RANGE) flat else fire.predictBombImpact()

        val track = currentHeading()
        val dx = impact.x - known.x
        val dz = impact.z - known.z
        val along = dx * track.x + dz * track.z
        val lateral = abs(dx * track.z - dz * track.x)
        val descending = boss.deltaMovement.y < 0.0

        if (!tossPrimed) {
            if (descending && along < -TOSS_LEAD &&
                FlightMath.horizontalDistance(boss.position(), known) < TOSS_MAX_RANGE
            ) {
                tossPrimed = true
            } else if (!descending && state != BossState.GUN_DIVE) {
                tossArmed = false // climbing and never lined up: nothing left to wait for
                if (BossConfig.DEBUG_LOGGING.get()) SuperbEnemies.LOGGER.info("[A10 boss] bomb toss missed: never primed")
            }
            previousTossAlong = along
            return
        }

        when {
            along >= -TOSS_LEAD && lateral < TOSS_MAX_LATERAL -> {
                if (!fire.dropBomb()) return // rack busy: try again next tick while the window holds
                fire.dropBombStick(BOMBS_PER_STICK - 1, TOSS_STICK_INTERVAL)
                tossReleased = true
                tossArmed = false
                if (BossConfig.DEBUG_LOGGING.get()) {
                    SuperbEnemies.LOGGER.info(
                        "[A10 boss] bomb toss release: along {}, lateral {}, impact {}, target {}",
                        "%.1f".format(along), "%.1f".format(lateral), impact, known
                    )
                }
            }

            along >= -TOSS_LEAD || along > TOSS_GIVE_UP -> {
                tossArmed = false
                if (BossConfig.DEBUG_LOGGING.get()) {
                    SuperbEnemies.LOGGER.info(
                        "[A10 boss] bomb toss missed: along {}, lateral {}, previous along {}",
                        "%.1f".format(along), "%.1f".format(lateral), "%.1f".format(previousTossAlong)
                    )
                }
            }
        }
        previousTossAlong = along
    }

    /** [known] plus a random scatter of up to [RAZE_RADIUS] blocks, re-rolled every [RAZE_JITTER_TICKS] ticks. */
    private fun razePoint(known: Vec3): Vec3 {
        if (boss.tickCount >= nextRazeJitterTick) {
            nextRazeJitterTick = boss.tickCount + RAZE_JITTER_TICKS
            val angle = boss.getRandom().nextDouble() * Math.PI * 2.0
            val radius = boss.getRandom().nextDouble() * RAZE_RADIUS
            razeOffset = Vec3(cos(angle) * radius, 0.0, sin(angle) * radius)
        }
        return known.add(razeOffset).add(0.0, 0.5, 0.0)
    }

    private fun tickPullOut() {
        // Full throttle, nose well up, wings nearly level.
        // Through the terrain guard: it steepens the climb if the ground ahead is higher than the plane.
        fly(FlightMath.tilted(currentHeading(), 40.0), 3.0f, maxBank = 25f)
        val v = boss.deltaMovement
        val climbAngle = Math.toDegrees(atan2(v.y, hypot(v.x, v.z)))
        if (climbAngle >= PULL_OUT_CLIMB_ANGLE || stateTicks > PULL_OUT_TIMEOUT) {
            // Double gun run: the first pass is done, stall-turn round for the second one.
            if (secondPassPending) {
                secondPassPending = false
                enter(BossState.STALL_TURN)
            } else {
                enter(BossState.EXTEND)
            }
        }
        if (tossArmed && !tossReleased) tryToss()
    }

    /** The second pass's weapon is still enabled and in stock (read live; the first pass may have spent it). */
    private fun secondPassReady(): Boolean = when (secondPassWeapon) {
        DiveWeapon.CANNON -> BossConfig.CANNON_ENABLED.get() && fire.hasAmmo(FireControl.CANNON)
        DiveWeapon.ROCKETS -> BossConfig.ROCKETS_ENABLED.get() && fire.hasAmmo(FireControl.ROCKET)
        DiveWeapon.BOMBS -> bombsAvailable()
    }

    private fun enterStallPhase(next: StallPhase) {
        stallPhase = next
        stallPhaseTicks = 0
    }

    /**
     * Second half of the double gun run: a stall turn (hammerhead) instead of a wide U-turn, so the plane stays over the
     * spot where the pull-out ended and never swings out over unseen terrain.
     *  - CLIMB: nose near vertical on the pull-out heading at full power, until the height it will coast up to is dive
     *    height (capped so the dive is no steeper than [STALL_MAX_DIVE_SLOPE]) or it hangs (slow), and it clears the
     *    terrain ahead by [STALL_ROTATE_CLEARANCE]. In mountains it climbs on until above the ridge, or gives the pass up
     *    when clearing the ridge would mean a dive steeper than allowed.
     *  - SWING: power cut, nose held up while the yaw is swung round to the target at [STALL_YAW_STEP] degrees per tick.
     *  - DROP: the nose falls onto a [STALL_DIVE_PITCH] degree descent at the target; once it points there and the dive
     *    line is clear it flies the second pass ([BossState.GUN_DIVE], cannon only) from the opposite side.
     * Abandoned (to EXTEND) when the climb never gets above the terrain, the line is blocked, the target is too close
     * for a dive, or a phase takes too long.
     */
    private fun tickStallTurn(target: Entity) {
        val known = lastKnownPos ?: target.position()
        val toKnown = towards(known)
        val heading = stallHeading ?: currentHeading().also { stallHeading = it }
        stallPhaseTicks++

        if (stateTicks > STALL_TURN_TIMEOUT) {
            enter(BossState.EXTEND)
            return
        }

        when (stallPhase) {
            StallPhase.CLIMB -> {
                flight.steer(
                    FlightMath.tilted(heading, STALL_CLIMB_PITCH), 3.0f, maxBank = STALL_MAX_BANK, stallGuard = false
                )
                val distance = FlightMath.horizontalDistance(boss.position(), known)
                // The plane keeps rising for a while after the climb ends (speed, then the hang), so judge the height it
                // will reach, not the current one.
                val projected = boss.y + max(boss.deltaMovement.y, 0.0) * STALL_COAST_TICKS
                // Highest start that still leaves a dive no steeper than STALL_MAX_DIVE_SLOPE: higher than that and the
                // second pass is too steep to pull out of.
                val ceiling = known.y + distance * STALL_MAX_DIVE_SLOPE
                val apex = min(
                    cruiseAltitude() + DIVE_TOP_EXTRA + STALL_APEX_EXTRA, min(ceiling, boss.level().maxBuildHeight + 60.0)
                )
                val clearance = projected - guard.floorAhead(known.y + UNKNOWN_TERRAIN_OFFSET)
                val nosedUp = -boss.xRot >= STALL_MIN_NOSE_UP
                val topped = projected >= apex || boss.deltaMovement.length() < STALL_HANG_SPEED
                if (nosedUp && topped && clearance >= STALL_ROTATE_CLEARANCE) {
                    enterStallPhase(StallPhase.SWING)
                } else if (stallPhaseTicks > STALL_CLIMB_TIMEOUT || distance < STALL_MIN_DISTANCE ||
                    (projected > ceiling && clearance < STALL_ROTATE_CLEARANCE) // the ridge needs a steeper dive than allowed
                ) {
                    enter(BossState.EXTEND)
                }
            }

            StallPhase.SWING -> {
                val nosedUp = FlightMath.tilted(toKnown, STALL_CLIMB_PITCH)
                flight.steer(nosedUp, STALL_SWING_THROTTLE, maxBank = STALL_MAX_BANK, stallGuard = false)
                flight.pivotYaw(nosedUp, STALL_YAW_STEP)
                val yawError = abs(Mth.wrapDegrees(FlightMath.yaw(nosedUp) - boss.yRot))
                if (yawError < STALL_SWING_DONE || stallPhaseTicks > STALL_SWING_TIMEOUT) enterStallPhase(StallPhase.DROP)
            }

            StallPhase.DROP -> {
                val dive = FlightMath.tilted(toKnown, -STALL_DIVE_PITCH)
                flight.steer(dive, STALL_DROP_THROTTLE, maxBank = STALL_DROP_MAX_BANK, converge = true)
                if (flight.angleErrorDeg(dive) < STALL_RELEASE_ANGLE) {
                    val distance = FlightMath.horizontalDistance(boss.position(), known)
                    val shallowEnough = boss.y - known.y <= distance * STALL_MAX_ENTRY_SLOPE
                    if (distance > STALL_MIN_DISTANCE && shallowEnough && secondPassReady() &&
                        diveClearance(boss.position(), known) >= DIVE_CLEARANCE_MIN
                    ) {
                        diveWeapon = secondPassWeapon // the second pass may use another weapon than the first
                        enter(BossState.GUN_DIVE)
                    } else {
                        enter(BossState.EXTEND)
                    }
                } else if (stallPhaseTicks > STALL_DROP_TIMEOUT) {
                    enter(BossState.EXTEND)
                }
            }
        }
    }

    private fun tickExtend(target: Entity?) {
        val cruise = cruiseAltitude()
        val altitudeError = cruise - boss.y
        val heading = if (target != null) awayFrom(target) else currentHeading()
        fly(FlightMath.tilted(heading, (altitudeError * 0.1).coerceIn(-3.0, 15.0)), 2.2f)
        if (tossArmed && !tossReleased) tryToss()
        if (stateTicks >= extendDuration) selectNext()
    }

    // ---------------------------------------------------------------------------------------------- resupply

    /**
     * Out of ammunition: climb and fly to a point [RESUPPLY_DISTANCE] blocks beyond the target (away from where the
     * fight was). Once it has arrived, or no player can see it any more, the boss is removed and a replacement with the
     * same health and a full load appears around the fight after `resupplySeconds` (see [BossRespawns]), which saves
     * keeping a far-away plane loaded and ticking.
     */
    private fun tickResupply() {
        val dest = depot ?: run {
            val base = aim?.position() ?: boss.position()
            resupplyFrom = base
            val away = aim?.let { awayFrom(it) } ?: currentHeading()
            base.add(away.scale(resupplyDistance()))
        }.also { depot = it }
        val altitude = cruiseAltitude() + RESUPPLY_EXTRA_HEIGHT

        val altitudeError = altitude - boss.y
        fly(FlightMath.tilted(towards(dest), (altitudeError * 0.1).coerceIn(-4.0, 18.0)), 2.6f)
        val arrived = FlightMath.horizontalDistance(boss.position(), dest) < RESUPPLY_ARRIVE_DISTANCE
        val unseen = stateTicks > RESUPPLY_MIN_VISIBLE_TICKS &&
                !BossRespawns.playerWithin(boss, BossConfig.BOSS_BAR_RANGE.get() + RESUPPLY_UNSEEN_MARGIN)
        if (arrived || unseen || stateTicks > RESUPPLY_TRAVEL_TIMEOUT) {
            BossRespawns.withdraw(
                boss, MSG_HUNTING, aim?.position() ?: resupplyFrom ?: boss.homePos, boss.homePos,
                BossConfig.RESUPPLY_SECONDS.get() * 20L, RESUPPLY_CLEARANCE_HALF_WIDTH
            )
        }
    }

    /**
     * How far beyond the target the depot lies. Without `globalAggro` a target farther than aggroRange x
     * [LOSE_TARGET_FACTOR] is dropped, so the depot stays inside that (the boss then comes back to the same player).
     */
    private fun resupplyDistance(): Double {
        if (BossConfig.GLOBAL_AGGRO.get()) return RESUPPLY_DISTANCE
        val keep = BossConfig.AGGRO_RANGE.get() * LOSE_TARGET_FACTOR * RESUPPLY_KEEP_TARGET_FRACTION
        return min(RESUPPLY_DISTANCE, keep)
    }

    // ---------------------------------------------------------------------------------------------- taunt

    /**
     * A show-off pass that fires nothing. Either a low flyby right over the target, or (50%) a higher pass with a
     * full barrel roll; flares are dropped while the plane is near the target in both. Like the bomb run it first opens
     * the distance so the pass is a straight run, then flies in.
     */
    private fun tickTaunt(target: Entity) {
        val ground = max(target.y, guard.terrainAt(target.x, target.z, target.y))
        val passAltitude = ground + if (tauntRoll) TAUNT_ROLL_HEIGHT else TAUNT_FLYBY_HEIGHT
        val altitudeError = passAltitude - boss.y
        val tilt = (altitudeError * 0.1).coerceIn(-6.0, 12.0)
        val distance = FlightMath.horizontalDistance(boss.position(), target.position())

        if (stateTicks > TAUNT_TIMEOUT) {
            enter(BossState.EXTEND)
            return
        }

        when (tauntPhase) {
            TauntPhase.SETUP -> {
                if (distance < TAUNT_RUN_IN_DISTANCE) {
                    fly(FlightMath.tilted(awayFrom(target), tilt), 2.4f)
                } else {
                    tauntPhase = TauntPhase.RUN
                }
            }

            TauntPhase.RUN -> {
                val toTarget = towards(target.position())
                val ahead = (target.x - boss.x) * currentHeading().x + (target.z - boss.z) * currentHeading().z
                if (!tauntPassed && ahead < 0.0 && distance < TAUNT_PASSED_DISTANCE) tauntPassed = true

                // After the pass keep going straight; turning back to the target would end the flyby in a circle.
                val heading = if (tauntPassed) currentHeading() else toTarget
                fly(FlightMath.tilted(heading, tilt), 2.2f, maxBank = 45f)

                val aligned = currentHeading().dot(toTarget) > TAUNT_ALIGNED_DOT
                if (tauntRoll && !rolling && !rollDone && !tauntPassed && aligned && distance < TAUNT_ROLL_START) startRoll()
                if (rolling) continueRoll()

                if (distance < TAUNT_FLARE_RANGE && flaresDropped < TAUNT_MAX_FLARES && boss.tickCount >= nextTauntFlareTick &&
                    !boss.recentlyDamaged(TAUNT_NO_FLARE_TICKS)
                ) {
                    fire.releaseFlare()
                    flaresDropped++
                    nextTauntFlareTick = boss.tickCount + TAUNT_FLARE_INTERVAL
                }

                if (tauntPassed && !rolling) enter(BossState.EXTEND)
            }
        }
    }

    private fun startRoll() {
        rolling = true
        rolledDegrees = 0.0
        rollTicks = 0
        lastRoll = boss.roll
        lastRollTick = boss.tickCount
    }

    /** Commands a full-rate roll and counts the degrees turned until one full turn is done (or it stops progressing). */
    private fun continueRoll() {
        val now = boss.tickCount
        // An evade (or anything else) took the controls for a while: abandon the roll rather than finishing it late.
        if (now - lastRollTick > 1) {
            rolling = false
            rollDone = true
            return
        }
        rolledDegrees += Mth.wrapDegrees(boss.roll - lastRoll) * rollSide
        lastRoll = boss.roll
        lastRollTick = now
        rollTicks++

        if (Math.abs(rolledDegrees) >= FULL_ROLL_DEGREES || rollTicks > ROLL_TIMEOUT) {
            rolling = false
            rollDone = true
            return
        }
        flight.applyRoll(rollSide)
    }

    // ---------------------------------------------------------------------------------------------- evade

    /** True once the evade's barrel roll has turned a full circle, or stopped progressing (terrain guard, timeout). */
    private fun evadeRollFinished(): Boolean =
        Math.abs(evadeRolled) >= FULL_ROLL_DEGREES || evadeRollTicks > ROLL_TIMEOUT

    private fun tickEvade() {
        val bearing = FlightMath.horizontal(threats.threatBearing ?: currentHeading(), currentHeading())
        val sideways = Vec3(-bearing.z * evadeSide, 0.0, bearing.x * evadeSide)
        val spare = boss.y - guard.floorAhead(referenceY() + UNKNOWN_TERRAIN_OFFSET)
        when (evadeManeuver) {
            FlareManeuver.DEFAULT -> {
                fly(FlightMath.tilted(sideways, if (spare > 120.0) -8.0 else 3.0), 3.0f, maxBank = 75f)
            }

            FlareManeuver.BARREL_ROLL -> {
                fly(FlightMath.tilted(sideways, if (spare > 120.0) -4.0 else 4.0), 3.0f, maxBank = 75f)
                evadeRolled += Mth.wrapDegrees(boss.roll - evadeLastRoll) * evadeSide
                evadeLastRoll = boss.roll
                evadeRollTicks++
                if (!evadeRollFinished()) flight.applyRoll(evadeSide)
            }

            FlareManeuver.CLIMB -> {
                fly(FlightMath.tilted(currentHeading(), 38.0), 3.0f, maxBank = 35f)
            }

            FlareManeuver.SIDE_FLARES -> {
                fly(FlightMath.tilted(sideways, if (spare > 120.0) 8.0 else 3.0), 3.0f, maxBank = 75f)
            }
        }

        // A barrel roll is never cut short (that leaves the plane inverted): the break lasts until the turn is done.
        val rollPending = evadeManeuver == FlareManeuver.BARREL_ROLL && !evadeRollFinished()
        if (stateTicks >= evadeDuration && !rollPending) {
            val ticks = resumeTicks
            enter(resumeState, resume = true)
            stateTicks = ticks
        }
    }

    // ---------------------------------------------------------------------------------------------- terrain guard

    /**
     * Runs after the state logic in every state and overrides the controls when ground clearance runs out.
     * The normal states already steer through [TerrainGuard.adjustDesired]; this catches what that cannot: the
     * orbit (flown by SBW's autopilot) and the dive (deliberately low).
     */
    private fun safetyNet() {
        if (state == BossState.CRIPPLED || state == BossState.PULL_OUT) return

        val v = boss.deltaMovement
        val descent = max(0.0, -v.y) * GUARD_DESCENT_FACTOR

        // The stall turn's drop is a dive: it gets the dive's emergency rule, not the level-flight buffer (which grows
        // with the descent rate and would cancel every drop within a few ticks).
        if (state == BossState.GUN_DIVE || (state == BossState.STALL_TURN && stallPhase == StallPhase.DROP)) {
            // Last resort for the dive: the terrain right below and just ahead (the pull-out altitude normally acts first).
            var ground = guard.terrainAt(boss.x, boss.z, boss.y - 100.0)
            for (ticks in EMERGENCY_LOOK_AHEAD_TICKS) {
                val ahead = boss.position().add(v.scale(ticks))
                ground = max(ground, guard.terrainAt(ahead.x, ahead.z, boss.y - 100.0))
            }
            val emergency = if (stallPass) STALL_DIVE_EMERGENCY_CLEARANCE else DIVE_EMERGENCY_CLEARANCE
            val descentFactor = if (stallPass) STALL_DIVE_DESCENT_FACTOR else DIVE_DESCENT_FACTOR
            if (boss.y - ground < emergency + max(0.0, -v.y) * descentFactor) {
                flight.pullUp(currentHeading(), 1.0)
                enter(BossState.PULL_OUT)
            }
            return
        }

        val floor = guard.floorAhead(referenceY() + UNKNOWN_TERRAIN_OFFSET)
        val clearance = boss.y - floor
        val buffer = GUARD_BASE_BUFFER + descent
        val autopilot = state == BossState.ORBIT_BARRAGE || (state == BossState.INGRESS && aim == null)

        if (autopilot) {
            // SBW's loiter has its own avoidance; step in only when it is losing, and hold until well clear.
            if (clearance < buffer * 0.6) guardLatched = true else if (clearance > buffer * 1.5) guardLatched = false
            if (guardLatched) flight.pullUp(currentHeading(), (1.0 - clearance / buffer).coerceIn(0.0, 1.0))
        } else {
            guardLatched = false
            if (clearance < buffer) {
                flight.pullUp(currentHeading(), (1.0 - clearance / buffer).coerceIn(0.0, 1.0))
                // The SETUP leg only opens the distance while climbing back to run altitude (always the case right after
                // a gun dive), so low clearance there must not cancel the pass; only the actual run is abandoned.
                if (state == BossState.BOMB_RUN && bombPhase != BombPhase.SETUP && !nukeRun) enter(BossState.EXTEND)
                // The climb itself may start low (it is what gets the plane up); the swing must not go on near the ground.
                if (state == BossState.STALL_TURN && stallPhase == StallPhase.SWING) enter(BossState.EXTEND)
            }
        }
    }

    private companion object {
        const val TAG_STATE = "State"
        const val TAG_TARGET = "Target"
        const val TAG_LAST_ATTACK = "LastAttack"

        const val MSG_WITHDRAW = "message.${SuperbEnemies.MOD_ID}.a10_boss.withdraw"
        const val MSG_HUNTING = "message.${SuperbEnemies.MOD_ID}.a10_boss.hunting"
        val WITHDRAW_STYLE: Style = ResupplyNotices.WITHDRAW_STYLE

        // Targeting
        const val TARGET_SCAN_INTERVAL = 20
        const val LOSE_TARGET_FACTOR = 1.5

        const val VELOCITY_SMOOTHING = 0.3
        const val TELEPORT_DISTANCE_SQR = 20.0 * 20.0

        // Cruise / orbit
        const val INGRESS_ARRIVAL_MARGIN = 15.0
        const val INGRESS_TIMEOUT = 1500
        const val INGRESS_ENGAGE_DISTANCE = 450.0
        const val RANDOM_TARGET_REACH = 700.0
        const val ALTITUDE_RECOVERY = 25.0
        const val MAX_GUN_STREAK = 3
        const val ORBIT_THROTTLE = 1.8
        const val ORBIT_THROTTLE_MIN = 1.6
        const val ORBIT_THROTTLE_MAX = 2.8
        const val ROCKET_INTERVAL = 3

        // Bomb run
        const val BOMB_MIN_HEIGHT = 40.0
        const val BOMB_RUN_IN_DISTANCE = 200.0
        const val BOMB_SETUP_TIMEOUT = 600
        const val BOMB_RUN_TIMEOUT = 500
        const val BOMBS_PER_STICK = 3
        const val STICK_INTERVAL = 6
        const val STICK_EXTRA_TICKS = 10
        const val RELEASE_RADIUS = 4.0
        const val RELEASE_WINDOW = 14.0
        const val COARSE_MISS_RANGE = 90.0

        // Resupply
        const val RESUPPLY_DISTANCE = 600.0
        /** Without globalAggro the depot is at most this fraction of the lose-target distance from the target. */
        const val RESUPPLY_KEEP_TARGET_FRACTION = 0.75
        const val RESUPPLY_EXTRA_HEIGHT = 60.0
        const val RESUPPLY_ARRIVE_DISTANCE = 100.0
        const val RESUPPLY_TRAVEL_TIMEOUT = 2400

        /** The boss only vanishes this long after leaving, and once no player is within the bar range plus the margin. */
        const val RESUPPLY_MIN_VISIBLE_TICKS = 100
        const val RESUPPLY_UNSEEN_MARGIN = 64.0
        const val RESUPPLY_CLEARANCE_HALF_WIDTH = 6.0

        // Taunt
        const val TAUNT_WEIGHT = 10
        const val TAUNT_FLYBY_HEIGHT = 28.0
        const val TAUNT_ROLL_HEIGHT = 45.0
        const val TAUNT_RUN_IN_DISTANCE = 170.0
        const val TAUNT_PASSED_DISTANCE = 140.0
        const val TAUNT_ALIGNED_DOT = 0.97
        const val TAUNT_ROLL_START = 130.0
        const val TAUNT_FLARE_RANGE = 90.0
        const val TAUNT_FLARE_INTERVAL = 5
        const val TAUNT_MAX_FLARES = 8
        const val TAUNT_NO_FLARE_TICKS = 200
        const val TAUNT_TIMEOUT = 700
        const val FULL_ROLL_DEGREES = 350.0
        const val ROLL_TIMEOUT = 120

        // Bombing gun run: cannon strafe from afar, dive-bomb pass when close
        /** Within this horizontal distance (blocks) of the target the cannon stops and the run lines up its bombs. */
        const val BOMB_PASS_RANGE = 110.0
        /** Minimum run-in distance of the bombing variant (the plain gun run uses MIN_DIVE_STANDOFF). */
        const val BOMB_PASS_STANDOFF = 220.0
        /** First bomb this far short of the target so the stick (3 bombs, ~9 blocks apart in a dive) straddles it. */
        const val BOMB_PASS_LEAD = 9.0
        /** At the end of the dive the stick is still dropped if it would land within this distance (Mk 82 blast ~22). */
        const val BOMB_PASS_MAX_MISS = 25.0
        const val BOMB_PASS_STICK_INTERVAL = 3
        const val BOMB_AIM_GAIN = 0.15
        const val BOMB_AIM_MAX_SHIFT = 60.0

        // Toss bombing (second chance of the bombing gun run)
        /** The first bomb is released this far (blocks) before the predicted impact reaches the target, so the stick straddles it. */
        const val TOSS_LEAD = 6.0
        const val TOSS_MAX_RANGE = 220.0
        const val TOSS_MAX_LATERAL = 14.0
        /** Closer than this (blocks) to the target the terrain-aware impact prediction replaces the flat-ground estimate. */
        const val TOSS_PRECISE_RANGE = 30.0
        /** The impact point swept this far past the target without a release: the window is lost. */
        const val TOSS_GIVE_UP = 40.0
        /** Ticks between bombs of a toss stick; short because the impact point sweeps fast while the nose is coming up. */
        const val TOSS_STICK_INTERVAL = 2

        // Gun dive
        const val DIVE_TOP_EXTRA = 25.0
        const val DIVE_STANDOFF_FACTOR = 1.2
        const val MIN_DIVE_STANDOFF = 110.0
        const val DIVE_ROCKET_ANGLE = 10.0
        const val MAX_DIVE_ROCKET_RANGE = 280.0
        /** Homing missiles launched during a rocket run with missiles. */
        const val DIVE_MISSILES = 2

        // Double gun run: stall turn between the two passes
        /** Above 75 degrees the flight controller levels the wings, which a straight-up climb needs. */
        /**
         * The second pass starts slow, so it bottoms its pull-out higher (plain dives: 18 + 2 v^2), holds more power and
         * takes the emergency pull-up earlier.
         */
        const val STALL_PULL_OUT_BASE = 40.0
        const val STALL_PULL_OUT_SPEED_FACTOR = 3.5
        const val STALL_DIVE_THROTTLE = 1.6f
        const val STALL_DIVE_EMERGENCY_CLEARANCE = 40.0
        const val STALL_DIVE_DESCENT_FACTOR = 18.0
        /** Extra apex height of the climb; the dive slope cap ([STALL_MAX_DIVE_SLOPE]) still limits it. */
        const val STALL_APEX_EXTRA = 30.0
        const val STALL_CLIMB_PITCH = 80.0
        const val STALL_MAX_BANK = 15f
        const val STALL_DROP_MAX_BANK = 40f
        /** The climb only ends with the nose at least this far above the horizon. */
        const val STALL_MIN_NOSE_UP = 55.0
        /** Blocks per tick below which the plane counts as hanging at the top of the climb. */
        const val STALL_HANG_SPEED = 1.6
        /** Required height above [TerrainGuard.floorAhead] (terrain plus its margin) before the swing starts. */
        const val STALL_ROTATE_CLEARANCE = 40.0
        const val STALL_SWING_THROTTLE = 0.6f
        const val STALL_DROP_THROTTLE = 1.5f
        const val STALL_YAW_STEP = 7f
        const val STALL_SWING_DONE = 15.0
        const val STALL_DIVE_PITCH = 35.0
        const val STALL_RELEASE_ANGLE = 20.0
        /** Closer than this (blocks) to the target the drop would be too steep to pull out of: no second pass. */
        const val STALL_MIN_DISTANCE = 70.0
        /** Steepest dive the climb may set up: tan(35 degrees), the slope of a normal gun run start. */
        const val STALL_MAX_DIVE_SLOPE = 0.7
        /** Backstop at the end of the drop: tan(42 degrees); steeper and the second pass is dropped. */
        const val STALL_MAX_ENTRY_SLOPE = 0.9
        /** Ticks of upward speed the plane is assumed to carry on after the climb ends. */
        const val STALL_COAST_TICKS = 12
        const val STALL_CLIMB_TIMEOUT = 160
        const val STALL_SWING_TIMEOUT = 60
        const val STALL_DROP_TIMEOUT = 100
        const val STALL_TURN_TIMEOUT = 420
        const val DIVE_ROCKETS_MIN = 8
        const val DIVE_ROCKETS_MAX = 12
        const val CLIMB_TIMEOUT = 700
        const val DIVE_THROTTLE = 0.8f
        const val DIVE_BRAKE_SPEED = 3.2
        const val RAZE_FIRE_ANGLE = 6.0
        const val RAZE_RADIUS = 6.0
        const val RAZE_JITTER_TICKS = 3
        const val KNOWN_POSITION_INTERVAL = 30
        const val MAX_GUN_RANGE = 450.0
        const val PULL_OUT_BASE = 18.0
        const val PULL_OUT_SPEED_FACTOR = 2.0
        const val DIVE_TIMEOUT = 500
        const val PULL_OUT_CLIMB_ANGLE = 20.0
        const val PULL_OUT_TIMEOUT = 160
        const val DIVE_EMERGENCY_CLEARANCE = 20.0
        const val DIVE_DESCENT_FACTOR = 12.0
        val EMERGENCY_LOOK_AHEAD_TICKS = doubleArrayOf(8.0, 16.0, 25.0)
        // Over flat ground the planned path bottoms out PULL_OUT_BASE (18) above the terrain, so these sit well below that.
        const val DIVE_CLEARANCE_MIN = 4.0
        const val DIVE_ABORT_CLEARANCE = -6.0
        const val DIVE_MAX_EXTRA_CLIMB = 125.0
        const val DIVE_CLIMB_STEP = 25.0
        const val CORRIDOR_CHECK_INTERVAL = 5

        // Terrain guard
        /**
         * Altitude (relative to the target) assumed for chunks that are not loaded, i.e. everything beyond the nearest
         * player's render distance. It must be low: a "mountain" there (it used to be +40) failed every dive corridor
         * check whose run-out left the loaded area and forced a steep pull-up on every look-ahead into it, so with a
         * short render distance the plane kept climbing away and never started its attack. Below the target it blocks
         * nothing, and the terrain near the target (always loaded, the player is there) is still read for real.
         */
        const val UNKNOWN_TERRAIN_OFFSET = -10.0
        const val GUARD_BASE_BUFFER = 12.0
        const val GUARD_DESCENT_FACTOR = 14.0
    }
}
