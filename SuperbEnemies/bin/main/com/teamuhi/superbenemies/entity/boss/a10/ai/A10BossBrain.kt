package com.teamuhi.superbenemies.entity.boss.a10.ai

import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.config.BossConfig
import com.teamuhi.superbenemies.entity.boss.a10.A10WarthogBossEntity
import com.teamuhi.superbenemies.entity.boss.common.ai.FlightController
import com.teamuhi.superbenemies.entity.boss.common.ai.FlightMath
import com.teamuhi.superbenemies.entity.boss.common.ai.TerrainGuard
import com.teamuhi.superbenemies.entity.boss.common.ai.ThreatMonitor
import com.teamuhi.superbenemies.config.DummyConfig
import com.teamuhi.superbenemies.entity.dummy.PlayerDummyEntity
import com.teamuhi.superbenemies.entity.dummy.TrackedDummies
import net.minecraft.ChatFormatting
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Style
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
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
 * The bombing variant of the gun run tosses its bombs on the pull-up (see [tryToss]); only when that window is missed
 * does the selector follow the run with a separate BOMB_RUN.
 */
class A10BossBrain(private val boss: A10WarthogBossEntity) {

    private val flight = FlightController(boss)
    private val guard = TerrainGuard(boss)
    private val fire = FireControl(boss)
    private val threats = ThreatMonitor(boss)

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
    private var targetEntity: LivingEntity? = null

    /** What the weapons are aimed at: the target (a player or a player dummy), or the vehicle a player rides. */
    private var aim: Entity? = null
    private var aimVelocity: Vec3 = Vec3.ZERO
    private var lastAimPosition: Vec3 = Vec3.ZERO
    private var trackedAimId = -1

    /** Where the target was last seen (refreshed every [KNOWN_POSITION_INTERVAL] ticks); gun runs raze this spot. */
    private var lastKnownPos: Vec3? = null
    private var razeOffset: Vec3 = Vec3.ZERO
    private var nextRazeJitterTick = 0
    private var initialScanDone = false

    // --- evade ---
    private var resumeState = BossState.INGRESS
    private var resumeTicks = 0
    private var evadeSide = 1.0
    private var evadeDuration = 35

    // --- per-attack bookkeeping ---
    private var extendDuration = 80
    private var orbitDuration = 350
    private var nextMissileTick = 0
    private var diveWeapon = DiveWeapon.CANNON
    private var diveRocketsLeft = 0
    private var nextDiveRocketTick = 0
    private var gunStreak = 0

    /** Set when the current gun run is the bombing variant (bombs tossed on the pull-up, see [tryToss]). */
    private var bombComboPending = false

    // --- toss bombing (bombing variant of the cannon run) ---
    /** The run may still toss its bombs: armed on entering GUN_DIVE, cleared by a release, a miss or the next attack. */
    private var tossArmed = false

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

    // --- resupply ---
    private var depot: Vec3? = null
    private var resupplyArrived = false
    private var resupplyEndTick = 0

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

    /** Each attack uses exactly one weapon type. ORBIT_BARRAGE is the homing missile attack, TAUNT fires nothing. */
    private enum class Attack(val entryState: BossState) {
        GUN_DIVE(BossState.CLIMB_FOR_DIVE),
        ROCKET_RUN(BossState.CLIMB_FOR_DIVE),
        ORBIT_BARRAGE(BossState.ORBIT_BARRAGE),
        BOMB_RUN(BossState.BOMB_RUN),
        TAUNT(BossState.TAUNT)
    }

    private enum class TauntPhase { SETUP, RUN }

    /** The only weapon fired during a CLIMB_FOR_DIVE -> GUN_DIVE run. */
    private enum class DiveWeapon { CANNON, ROCKETS }

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

        if (threats.tick()) enterEvade()

        // The target is gone: stop attacking and orbit the home position (INGRESS without a target).
        if (target == null && state.needsTarget()) enter(BossState.INGRESS)

        when (state) {
            BossState.INGRESS -> tickIngress(target)
            BossState.ORBIT_BARRAGE -> if (target != null) tickOrbit(target)
            BossState.BOMB_RUN -> if (target != null) tickBombRun(target)
            BossState.CLIMB_FOR_DIVE -> if (target != null) tickClimbForDive(target)
            BossState.GUN_DIVE -> tickGunDive(target)
            BossState.PULL_OUT -> tickPullOut()
            BossState.TAUNT -> if (target != null) tickTaunt(target)
            BossState.RESUPPLY -> tickResupply()
            BossState.EXTEND -> tickExtend(target)
            BossState.EVADE -> tickEvade()
            BossState.CRIPPLED -> {}
        }

        safetyNet()
    }

    private fun BossState.needsTarget(): Boolean =
        this == BossState.ORBIT_BARRAGE || this == BossState.BOMB_RUN ||
                this == BossState.CLIMB_FOR_DIVE || this == BossState.TAUNT

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
            current = level.getEntity(remembered) as? LivingEntity
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

        // A player in a vehicle is attacked (and locked onto) through the vehicle.
        aim = current?.rootVehicle
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
     * Survival / adventure players, and player dummies while `bossesTargetDummy` is on (read live, so switching it off
     * drops a dummy target at once). Both obey the same range rule.
     */
    private fun isValidTarget(entity: LivingEntity): Boolean {
        when (entity) {
            is ServerPlayer -> if (entity.isSpectator || entity.isCreative) return false
            is PlayerDummyEntity -> if (!DummyConfig.BOSSES_TARGET_DUMMY.get()) return false
            else -> return false
        }
        if (!entity.isAlive || entity.isRemoved) return false
        if (entity.level() !== boss.level()) return false
        if (BossConfig.GLOBAL_AGGRO.get()) return true
        val limit = BossConfig.AGGRO_RANGE.get() * LOSE_TARGET_FACTOR
        val dx = entity.x - boss.x
        val dz = entity.z - boss.z
        return dx * dx + dz * dz <= limit * limit
    }

    /** Runs that must not change victim half way: the aim point and the corridor checks were planned for one target. */
    private fun targetLocked(): Boolean =
        state == BossState.CLIMB_FOR_DIVE || state == BossState.GUN_DIVE || state == BossState.PULL_OUT ||
                state == BossState.BOMB_RUN

    private fun candidates(level: ServerLevel): MutableList<LivingEntity> {
        val aggro = if (BossConfig.GLOBAL_AGGRO.get()) Double.MAX_VALUE else BossConfig.AGGRO_RANGE.get().toDouble()
        val candidates = ArrayList<LivingEntity>()
        for (player in level.players()) candidates.add(player)
        if (DummyConfig.BOSSES_TARGET_DUMMY.get()) {
            for (dummy in TrackedDummies.all(level)) (dummy as? LivingEntity)?.let { candidates.add(it) }
        }
        candidates.removeAll { !(isValidTarget(it) && FlightMath.horizontalDistance(it.position(), boss.position()) <= aggro) }
        return candidates
    }

    private fun pickTarget(level: ServerLevel) {
        val held = targetEntity?.takeIf { isValidTarget(it) }
        if (held != null && targetLocked()) return
        val candidates = candidates(level)
        // Most recent attacker first, then keep the current target, then the nearest candidate. With randomTargeting the
        // target only changes when it is lost (a new one is drawn per attack, see rerollTarget).
        val chosen = if (BossConfig.RANDOM_TARGETING.get()) {
            held ?: candidates.randomOrNull(boss.getRandom())
        } else {
            val attackerId = boss.lastAttackerUUID
            candidates.firstOrNull { it.stringUUID == attackerId }
                ?: held
                ?: candidates.minByOrNull { FlightMath.horizontalDistance(it.position(), boss.position()) }
        }
        applyTarget(chosen)
    }

    private fun applyTarget(chosen: LivingEntity?) {
        if (chosen?.uuid != targetUuid) trackedAimId = -1 // new target: forget the old motion estimate
        targetEntity = chosen
        targetUuid = chosen?.uuid
    }

    /** `randomTargeting`: draws a fresh random target (possibly the same one) at the start of an attack. */
    private fun rerollTarget(level: ServerLevel) {
        val all = candidates(level)
        // Prefer players within reach so a far-off draw does not park the boss on a long flight; if nobody is, go nearest.
        val here = boss.position()
        val near = all.filter { FlightMath.horizontalDistance(it.position(), here) <= RANDOM_TARGET_REACH }
        val chosen = near.randomOrNull(boss.getRandom())
            ?: all.minByOrNull { FlightMath.horizontalDistance(it.position(), here) } ?: return
        applyTarget(chosen)
        aim = chosen.rootVehicle
        trackAim(aim)
        lastKnownPos = aim?.position()
    }

    private fun <T> List<T>.randomOrNull(random: net.minecraft.util.RandomSource): T? =
        if (isEmpty()) null else this[random.nextInt(size)]

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

    private fun referenceY(): Double = aim?.y ?: boss.homePos.y

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
            }

            BossState.CLIMB_FOR_DIVE -> extraClimb = 0.0

            BossState.GUN_DIVE -> {
                aim?.let { lastKnownPos = it.position() } // the pilot has eyes on the target as the run starts
                diveAimId = aim?.id ?: -1
                nextCorridorCheckTick = 0
                diveRocketsLeft = randomBetween(DIVE_ROCKETS_MIN, DIVE_ROCKETS_MAX)
                nextDiveRocketTick = 0
                tossArmed = bombComboPending && bombsAvailable() && diveWeapon == DiveWeapon.CANNON
                tossPrimed = false
                tossReleased = false
                previousTossAlong = 0.0
            }

            BossState.RESUPPLY -> {
                depot = null
                resupplyArrived = false
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
     * Weighted random attack choice, weighted by the `*Chance` config options (bombs only from phase 2), except that the
     * gun run has two variants: plain, or (`gunRunBombChance`) bombing, which tosses its bombs on the pull-up. Only when
     * that toss window was missed is the run followed by a separate bomb run on the same target. One attack =
     * one weapon type: cannon run, rocket run, homing missile orbit or bomb run. A cannon run may follow itself up to [MAX_GUN_STREAK] times, the others never
     * repeat back to back.
     */
    private fun selectNext() {
        if (aim == null) {
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
            lastAttack = Attack.BOMB_RUN
            enter(BossState.BOMB_RUN)
            return
        }
        val weights = EnumMap<Attack, Int>(Attack::class.java)
        // Weights are the configured chances; a chance of 0 keeps the attack out of the draw. Bombs wait for phase 2.
        weights[Attack.GUN_DIVE] = BossConfig.CANNON_CHANCE.get()
        weights[Attack.ROCKET_RUN] = BossConfig.ROCKET_CHANCE.get()
        weights[Attack.ORBIT_BARRAGE] = BossConfig.MISSILE_CHANCE.get()
        if (phase != Phase.P1) weights[Attack.BOMB_RUN] = BossConfig.BOMB_CHANCE.get()
        weights.values.removeIf { it <= 0 }
        // Config toggles are read live, so a weapon can be switched off between attacks.
        if (!BossConfig.CANNON_ENABLED.get()) weights.remove(Attack.GUN_DIVE)
        if (!BossConfig.ROCKETS_ENABLED.get()) weights.remove(Attack.ROCKET_RUN)
        if (!BossConfig.MISSILES_ENABLED.get()) weights.remove(Attack.ORBIT_BARRAGE)
        if (!BossConfig.BOMBS_ENABLED.get()) weights.remove(Attack.BOMB_RUN)
        // Finite ammo: a spent weapon's attack is skipped; with every enabled weapon spent the boss goes to rearm.
        if (BossConfig.LIMITED_AMMO.get()) {
            val hadWeapons = weights.isNotEmpty()
            if (!fire.hasAmmo(FireControl.CANNON)) weights.remove(Attack.GUN_DIVE)
            if (!fire.hasAmmo(FireControl.ROCKET)) weights.remove(Attack.ROCKET_RUN)
            if (!fire.hasAmmo(FireControl.MISSILE)) weights.remove(Attack.ORBIT_BARRAGE)
            if (!fire.hasAmmo(FireControl.BOMB)) weights.remove(Attack.BOMB_RUN)
            if (hadWeapons && weights.isEmpty()) {
                enter(BossState.RESUPPLY)
                return
            }
        }
        // A rare, harmless show-off pass; it never replaces a real attack when the others are all switched off.
        if (BossConfig.TAUNT_ENABLED.get() && weights.isNotEmpty()) weights[Attack.TAUNT] = TAUNT_WEIGHT
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
        diveWeapon = if (attack == Attack.ROCKET_RUN) DiveWeapon.ROCKETS else DiveWeapon.CANNON
        gunStreak = if (attack == Attack.GUN_DIVE) gunStreak + 1 else 0
        // Each gun run picks its variant up front; the plain one (and every other attack) clears the flag.
        bombComboPending = attack == Attack.GUN_DIVE && boss.getRandom().nextInt(100) < BossConfig.GUN_RUN_BOMB_CHANCE.get()
        lastAttack = attack
        // Picking the state we are already in (only when nothing else is allowed) still restarts it.
        if (state == attack.entryState) state = BossState.EXTEND
        enter(attack.entryState)
    }

    private fun bombsAvailable(): Boolean =
        BossConfig.BOMBS_ENABLED.get() && BossConfig.BOMB_CHANCE.get() > 0 && fire.hasAmmo(FireControl.BOMB)

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
        enter(BossState.EVADE)
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
            // Nobody to fight: orbit the home position.
            ensureLoiter(boss.homePos.x, cruise, boss.homePos.z, BossConfig.ORBIT_RADIUS.get().toDouble())
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
        if (!BossConfig.MISSILES_ENABLED.get() || BossConfig.MISSILE_CHANCE.get() <= 0) return
        if (now >= nextMissileTick && fire.fireMissile(target)) {
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
        val predicted = fire.bombLeadPoint(target)
        val heading = towards(predicted)
        fly(FlightMath.tilted(heading, (altitudeError * 0.08).coerceIn(-5.0, 8.0)), 2.0f, maxBank = 45f)

        // Far from the release point the flat-ground estimate is enough; SBW's terrain-sampling prediction (hundreds of
        // heightmap reads per call) only runs once the plane is close.
        val flat = fire.estimateBombImpact(target.y)
        val impact = if (FlightMath.horizontalDistance(flat, predicted) > COARSE_MISS_RANGE) flat else fire.predictBombImpact()

        // The stick falls along the flight path, one bomb per STICK_INTERVAL ticks. Release the first bomb one spacing
        // short of the target so the middle bomb lands on it and the others straddle it.
        val track = FlightMath.horizontal(boss.deltaMovement, heading)
        val spacing = horizontalSpeed() * STICK_INTERVAL
        val aimPoint = predicted.subtract(track.scale(spacing))
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
            bombApproached && ahead < -spacing - 20.0 -> enter(BossState.EXTEND) // overflown: the pass is lost
            stateTicks > BOMB_SETUP_TIMEOUT + BOMB_RUN_TIMEOUT -> enter(BossState.EXTEND) // never lined up
        }
    }

    private fun releaseStick(predictedMiss: Double, predictedTarget: Vec3, impact: Vec3) {
        if (!BossConfig.BOMBS_ENABLED.get()) { // switched off mid-run: abandon the pass
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
            from, Vec3(known.x, ground + 0.5, known.z), ground + PULL_OUT_BASE, known.y + UNKNOWN_TERRAIN_OFFSET
        )
    }

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
        val standoff = max(DIVE_STANDOFF_FACTOR * (diveTop - target.y), MIN_DIVE_STANDOFF)

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

        val aimPoint = when {
            diveWeapon == DiveWeapon.CANNON -> fire.cannonAimAt(razePoint(known))
            target != null -> fire.cannonLeadPoint(target)
            else -> fire.cannonAimAt(known)
        }
        val wanted = aimPoint.subtract(origin)

        // Unguarded on purpose: the dive goes low by design and ends at the pull-out altitude below.
        fly(wanted, DIVE_THROTTLE, maxBank = 70f, airBrake = speed > DIVE_BRAKE_SPEED, converge = true, guarded = false)

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
            DiveWeapon.CANNON -> if (error < RAZE_FIRE_ANGLE && clear && BossConfig.CANNON_ENABLED.get()) {
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
        }

        val ground = max(guard.terrainAt(known.x, known.z, known.y), known.y)
        val basePullOut = ground + PULL_OUT_BASE + PULL_OUT_SPEED_FACTOR * speed * speed
        // Also respect terrain on the way (a mountain beside the target), but never demand more than a bounded raise.
        val pullOutAltitude = max(basePullOut, min(guard.floorAhead(known.y + UNKNOWN_TERRAIN_OFFSET), basePullOut + 60.0))
        val passedTarget = FlightMath.horizontalDistance(boss.position(), known) < 120.0 &&
                (known.x - boss.x) * currentHeading().x + (known.z - boss.z) * currentHeading().z < 0.0
        if (boss.y <= pullOutAltitude || passedTarget || stateTicks > DIVE_TIMEOUT) enter(BossState.PULL_OUT)

        if (tossArmed && !tossReleased) tryToss()
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
        if (climbAngle >= PULL_OUT_CLIMB_ANGLE || stateTicks > PULL_OUT_TIMEOUT) enter(BossState.EXTEND)
        if (tossArmed && !tossReleased) tryToss()
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
     * fight was), loiter there for `resupplySeconds`, then refill and go back to INGRESS to hunt again.
     */
    private fun tickResupply() {
        val dest = depot ?: run {
            val base = aim?.position() ?: boss.position()
            val away = aim?.let { awayFrom(it) } ?: currentHeading()
            base.add(away.scale(RESUPPLY_DISTANCE))
        }.also { depot = it }
        val altitude = cruiseAltitude() + RESUPPLY_EXTRA_HEIGHT

        if (!resupplyArrived) {
            val altitudeError = altitude - boss.y
            fly(FlightMath.tilted(towards(dest), (altitudeError * 0.1).coerceIn(-4.0, 18.0)), 2.6f)
            val arrived = FlightMath.horizontalDistance(boss.position(), dest) < RESUPPLY_ARRIVE_DISTANCE
            if (arrived || stateTicks > RESUPPLY_TRAVEL_TIMEOUT) {
                resupplyArrived = true
                resupplyEndTick = boss.tickCount + BossConfig.RESUPPLY_SECONDS.get() * 20
            }
            return
        }

        ensureLoiter(dest.x, altitude, dest.z, BossConfig.ORBIT_RADIUS.get().toDouble())
        trimOrbitThrottle(altitude)
        if (boss.tickCount >= resupplyEndTick) {
            fire.refillAmmo()
            depot = null
            boss.announce(MSG_HUNTING, HUNTING_STYLE)
            enter(BossState.INGRESS)
        }
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

    private fun tickEvade() {
        // Break 90 degrees off the missile's bearing, nose slightly down if there is height to spare.
        val bearing = FlightMath.horizontal(threats.threatBearing ?: currentHeading(), currentHeading())
        val sideways = Vec3(-bearing.z * evadeSide, 0.0, bearing.x * evadeSide)
        val spare = boss.y - guard.floorAhead(referenceY() + UNKNOWN_TERRAIN_OFFSET)
        val pitch = if (spare > 120.0) -8.0 else 3.0
        fly(FlightMath.tilted(sideways, pitch), 3.0f, maxBank = 75f)

        if (stateTicks >= evadeDuration) {
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

        if (state == BossState.GUN_DIVE) {
            // Last resort for the dive: the terrain right below and just ahead (the pull-out altitude normally acts first).
            var ground = guard.terrainAt(boss.x, boss.z, boss.y - 100.0)
            for (ticks in EMERGENCY_LOOK_AHEAD_TICKS) {
                val ahead = boss.position().add(v.scale(ticks))
                ground = max(ground, guard.terrainAt(ahead.x, ahead.z, boss.y - 100.0))
            }
            if (boss.y - ground < DIVE_EMERGENCY_CLEARANCE + max(0.0, -v.y) * DIVE_DESCENT_FACTOR) {
                flight.pullUp(currentHeading(), 1.0)
                enter(BossState.PULL_OUT)
            }
            return
        }

        val floor = guard.floorAhead(referenceY() + UNKNOWN_TERRAIN_OFFSET)
        val clearance = boss.y - floor
        val buffer = GUARD_BASE_BUFFER + descent
        val autopilot = state == BossState.ORBIT_BARRAGE || (state == BossState.INGRESS && aim == null) ||
                (state == BossState.RESUPPLY && resupplyArrived)

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
                if (state == BossState.BOMB_RUN && bombPhase != BombPhase.SETUP) enter(BossState.EXTEND)
            }
        }
    }

    private companion object {
        const val TAG_STATE = "State"
        const val TAG_TARGET = "Target"
        const val TAG_LAST_ATTACK = "LastAttack"

        const val MSG_WITHDRAW = "message.${SuperbEnemies.MOD_ID}.a10_boss.withdraw"
        const val MSG_HUNTING = "message.${SuperbEnemies.MOD_ID}.a10_boss.hunting"
        /** ChatFormatting has no orange, so the withdraw notice uses a true orange RGB. */
        val WITHDRAW_STYLE: Style = Style.EMPTY.withColor(0xFF8C00)
        val HUNTING_STYLE: Style = Style.EMPTY.withColor(ChatFormatting.RED).withBold(true)

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
        const val RESUPPLY_EXTRA_HEIGHT = 60.0
        const val RESUPPLY_ARRIVE_DISTANCE = 100.0
        const val RESUPPLY_TRAVEL_TIMEOUT = 2400

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

        // Toss bombing (bombing gun run)
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
        const val UNKNOWN_TERRAIN_OFFSET = 40.0
        const val GUARD_BASE_BUFFER = 12.0
        const val GUARD_DESCENT_FACTOR = 14.0
    }
}
