package com.teamuhi.superbenemies.entity.boss.ac130.ai

import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.config.Ac130Config
import com.teamuhi.superbenemies.entity.boss.ac130.Ac130BossEntity
import com.teamuhi.superbenemies.entity.boss.ac130.Ac130Gun
import com.teamuhi.superbenemies.entity.boss.common.ResupplyNotices
import com.teamuhi.superbenemies.entity.boss.common.ai.FlightController
import com.teamuhi.superbenemies.entity.boss.common.ai.FlightMath
import com.teamuhi.superbenemies.entity.boss.common.ai.TerrainGuard
import com.teamuhi.superbenemies.entity.boss.common.ai.ThreatMonitor
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import java.util.EnumMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The AC-130 boss's decision making: the glue between targeting, the flight controller, terrain guard, threat monitor
 * and the side-gun gunnery.
 *
 * `tick()` runs on the server **before** `VehicleEntity.baseTick`, so control inputs written here are in place when
 * SBW's aircraft engine consumes them; `applyGunnerAim()` runs **after** it (see [Ac130Gunnery]).
 *
 * The gunship never dives. Its attack is a pylon turn: SBW's loiter autopilot circles the target counter-clockwise with
 * the aircraft's LEFT side toward the centre, which is exactly where the three gun seats look. The brain only chooses
 * the circle (smoothed centre on the target, cruise altitude, an orbit radius adapted so the target sits in the middle
 * of the guns' depression windows) and lets the gunners shoot.
 *
 * While orbiting the guns fire in one of three patterns ([Ac130FireMode]): at the main target (normal), at one target
 * each (split fire) or all at one fixed spot (focus fire); the pattern is rolled every 15-25 seconds.
 *
 * Flow: INGRESS -> ORBIT (guns fire) -> occasionally TAUNT -> INGRESS -> ORBIT ... EVADE interrupts any state and
 * returns to it; RESUPPLY replaces the orbit while every enabled gun is out of ammunition; CRIPPLED is terminal.
 *
 * All state is per instance (no statics), so several bosses can fly at once.
 */
class Ac130BossBrain(private val boss: Ac130BossEntity) {

    private val flight = FlightController(boss)
    private val guard = TerrainGuard(boss)
    private val threats = ThreatMonitor(boss)
    private val targeting = Ac130Targeting(boss)
    private val gunnery = Ac130Gunnery(boss)

    var state: Ac130State = Ac130State.INGRESS
        private set
    private var stateTicks = 0

    /** True while the boss is away rearming, including an evade flown on the way; the boss bar is hidden meanwhile. */
    val withdrawn: Boolean
        get() = state == Ac130State.RESUPPLY || (state == Ac130State.EVADE && resumeState == Ac130State.RESUPPLY)

    /** True when the autopilot (SBW loiter) was commanded this tick; the terrain guard needs to know who is flying. */
    private var autopilotThisTick = false

    // --- orbit ---
    /** Adaptive orbit radius; negative until first used so the config is only read once the server runs. */
    private var radius = -1.0
    private var orbitCentre: Vec3? = null
    private var nextRadiusAdaptTick = 0
    private var nextTauntRollTick = 0

    // --- fire patterns (during ORBIT) ---
    private var fireMode = Ac130FireMode.NORMAL
    private var modeEndTick = 0
    private var nextModeRollTick = 0

    /** Focus fire: the fixed spot every gun shoots at. */
    private var focusPoint: Vec3? = null

    /** Split fire: one order per gun, where the orbit is centred, and the angle of the spots used without enough targets. */
    private var splitPlan: Ac130FirePlan? = null
    private var splitCentre: Vec3? = null
    private var splitSpotAngle = 0.0
    private var nextSplitRefreshTick = 0

    // --- evade ---
    private var resumeState = Ac130State.INGRESS
    private var resumeTicks = 0
    private var evadeSide = 1.0
    private var evadeDuration = 35

    // --- resupply ---
    private var depot: Vec3? = null
    private var resupplyArrived = false

    /** Where the fight was when the boss left to rearm; the "hunting" notice also reaches the players there. */
    private var resupplyFrom: Vec3? = null
    private var resupplyEndTick = 0

    // --- taunt ---
    private var tauntPhase = TauntPhase.SETUP
    private var tauntPassed = false
    private var flaresDropped = 0
    private var nextTauntFlareTick = 0

    private var guardLatched = false

    private enum class TauntPhase { SETUP, RUN }

    // ================================================================================================== main loop

    fun tick() {
        val level = boss.level() as? ServerLevel ?: return

        if (boss.crippled) {
            if (state != Ac130State.CRIPPLED) enterCrippled()
            flight.release()
            return
        }

        stateTicks++
        autopilotThisTick = false

        targeting.update(level, radiusNow())
        val target = targeting.aim

        if (threats.tick()) enterEvade()

        // The target is gone: stop attacking and loiter over home (INGRESS without a target).
        if (target == null && state.needsTarget()) enter(Ac130State.INGRESS)

        // Finite ammunition: with every enabled gun empty, go and rearm.
        if ((state == Ac130State.INGRESS || state == Ac130State.ORBIT) && Ac130Config.LIMITED_AMMO.get() &&
            gunnery.anyEnabled() && !gunnery.hasUsableGun()
        ) {
            enter(Ac130State.RESUPPLY)
        }

        when (state) {
            Ac130State.INGRESS -> tickIngress(target)
            Ac130State.ORBIT -> if (target != null) tickOrbit(target)
            Ac130State.TAUNT -> if (target != null) tickTaunt(target)
            Ac130State.RESUPPLY -> tickResupply()
            Ac130State.EVADE -> tickEvade()
            Ac130State.CRIPPLED -> {}
        }

        // Aim every tick; fire only while circling, flying in or breaking away (the taunt and the rearm never shoot).
        val mayFire = state == Ac130State.INGRESS || state == Ac130State.ORBIT || state == Ac130State.EVADE
        gunnery.tick(target, targeting.aimVelocity, mayFire, firePlan())

        safetyNet(target)
    }

    /**
     * Re-applies the gunners' aim against the current seat frame. The entity calls this right after `baseTick`: SBW's
     * seat shot is queued and reads the gunner's look direction later, and `positionRider` has just turned the gunners
     * with the vehicle.
     */
    fun applyGunnerAim() {
        if (boss.crippled) return
        gunnery.applyAim()
    }

    private fun Ac130State.needsTarget(): Boolean = this == Ac130State.ORBIT || this == Ac130State.TAUNT

    // ================================================================================================== persistence

    fun save(tag: CompoundTag) {
        gunnery.saveAmmo(tag)
        targeting.save(tag)
        // A reloaded evade restarts at INGRESS, except mid-resupply: the boss would rejoin silently with empty guns.
        val saved = if (state == Ac130State.EVADE && resumeState == Ac130State.RESUPPLY) Ac130State.RESUPPLY else state
        tag.putString(TAG_STATE, saved.name)
    }

    fun load(tag: CompoundTag) {
        gunnery.loadAmmo(tag)
        targeting.load(tag)
        state = Ac130State.entries.firstOrNull { it.name == tag.getString(TAG_STATE) } ?: Ac130State.INGRESS
        // A reloaded evade restarts from the beginning; the other states resume (and re-derive their working data).
        if (state == Ac130State.EVADE) state = Ac130State.INGRESS
        stateTicks = 0
    }

    // ================================================================================================== helpers

    private fun randomBetween(min: Int, max: Int): Int = min + boss.getRandom().nextInt(max - min + 1)

    private fun referenceY(aim: Entity? = targeting.aim): Double = aim?.y ?: boss.homePos.y

    private fun cruiseAltitude(): Double =
        min(referenceY() + Ac130Config.CRUISE_HEIGHT.get().toDouble(), boss.level().maxBuildHeight + 60.0)

    private fun baseRadius(): Double = Ac130Config.ORBIT_RADIUS.get().toDouble()

    /** The current orbit radius, always inside 0.6..1.8 x the configured radius (the config can change mid-orbit). */
    private fun radiusNow(): Double {
        val base = baseRadius()
        val current = if (radius < 0.0) base else radius
        return current.coerceIn(base * MIN_RADIUS_FACTOR, base * MAX_RADIUS_FACTOR)
    }

    private fun currentHeading(): Vec3 = FlightMath.horizontal(boss.deltaMovement, FlightMath.dir(boss.yRot, 0f))

    private fun awayFrom(entity: Entity): Vec3 =
        FlightMath.horizontal(Vec3(boss.x - entity.x, 0.0, boss.z - entity.z), currentHeading())

    private fun towards(point: Vec3): Vec3 =
        FlightMath.horizontal(Vec3(point.x - boss.x, 0.0, point.z - boss.z), currentHeading())

    /** Flies toward [dir] after the terrain guard has raised its pitch if the ground is coming up. */
    private fun fly(dir: Vec3, throttle: Float, maxBank: Float = MAX_BANK, guarded: Boolean = true) {
        val wanted = if (guarded) guard.adjustDesired(dir, referenceY() + UNKNOWN_TERRAIN_OFFSET) else dir
        flight.steer(wanted, throttle, maxBank)
    }

    /** Throttle trim for the SBW-flown orbit: its own throttle logic tends to settle below the lift speed. */
    private fun trimOrbitThrottle(cruise: Double) {
        val altitudeError = cruise - boss.y
        val wanted = (ORBIT_THROTTLE + altitudeError * 0.004).coerceIn(ORBIT_THROTTLE_MIN, ORBIT_THROTTLE_MAX)
        boss.power = Mth.lerp(0.1f, boss.power, wanted.toFloat())
    }

    private fun ensureLoiter(x: Double, altitude: Double, z: Double, radius: Double) {
        autopilotThisTick = true
        val p = boss.loiterParams
        if (abs(p.x() - x) > 2.0 || abs(p.y() - altitude) > 2.0 || abs(p.z() - z) > 2.0 || abs(p.w() - radius) > 1.0) {
            boss.loiterParams = Quaternionf(x.toFloat(), altitude.toFloat(), z.toFloat(), radius.toFloat())
        }
        if (!boss.loiterActive) boss.loiterActive = true
    }

    /** Releases one flare burst this tick (unlimited: the dispenser is refilled instead of waiting for SBW's reload). */
    private fun releaseFlare() {
        if (boss.isWreck || boss.crippled) return
        if (boss.decoyCount < 1) boss.decoyCount = boss.computed().decoyMagazineSize
        boss.decoyInputDown = true
    }

    // ================================================================================================== transitions

    /** @param resume true when returning from an evade: keeps the per-state progress */
    private fun enter(next: Ac130State, resume: Boolean = false) {
        if (next == state && !resume) return
        state = next
        stateTicks = 0
        // Only the orbit (and the idle/climbing loiters inside INGRESS and RESUPPLY) use SBW's autopilot; each
        // re-enables it itself every tick.
        if (next != Ac130State.ORBIT) boss.loiterActive = false
        if (next != Ac130State.ORBIT && next != Ac130State.EVADE) clearFireMode()
        if (resume) return

        when (next) {
            Ac130State.ORBIT -> {
                orbitCentre = null
                nextRadiusAdaptTick = 0
                nextModeRollTick = randomBetween(MODE_FIRST_ROLL_MIN, MODE_FIRST_ROLL_MAX)
                nextTauntRollTick = randomBetween(TAUNT_ROLL_MIN, TAUNT_ROLL_MAX)
            }

            Ac130State.RESUPPLY -> {
                depot = null
                resupplyArrived = false
                boss.announce(MSG_WITHDRAW, ResupplyNotices.WITHDRAW_STYLE)
            }

            Ac130State.TAUNT -> {
                tauntPhase = TauntPhase.SETUP
                tauntPassed = false
                flaresDropped = 0
                nextTauntFlareTick = 0
            }

            else -> {}
        }
    }

    private fun enterEvade() {
        if (state == Ac130State.CRIPPLED) return
        if (state == Ac130State.EVADE) {
            evadeDuration = max(evadeDuration, stateTicks + 25) // a second missile extends the break
            return
        }
        resumeState = state
        resumeTicks = stateTicks
        evadeSide = if (boss.getRandom().nextBoolean()) 1.0 else -1.0
        evadeDuration = randomBetween(30, 40)
        enter(Ac130State.EVADE)
    }

    private fun enterCrippled() {
        state = Ac130State.CRIPPLED
        stateTicks = 0
        boss.loiterActive = false
        flight.release()
    }

    // ================================================================================================== ingress

    /**
     * Fly to the target at cruise altitude. Inside [INGRESS_CIRCLE_FACTOR] x radius, if the plane is still too low, it
     * circles the target while it climbs instead of overflying it. With no target it loiters over home.
     */
    private fun tickIngress(target: Entity?) {
        val cruise = cruiseAltitude()

        if (target == null) {
            ensureLoiter(boss.homePos.x, cruise, boss.homePos.z, baseRadius())
            trimOrbitThrottle(cruise)
            return
        }

        val r = radiusNow()
        val distance = FlightMath.horizontalDistance(boss.position(), target.position())
        val altitudeError = cruise - boss.y
        val altitudeReady = altitudeError < INGRESS_ARRIVAL_MARGIN

        if (distance < r * INGRESS_CIRCLE_FACTOR) {
            if (altitudeReady || stateTicks > INGRESS_TIMEOUT) {
                enter(Ac130State.ORBIT)
                return
            }
            ensureLoiter(target.x, cruise, target.z, r)
            trimOrbitThrottle(cruise)
            return
        }

        val heading = if (distance < 40.0) currentHeading() else towards(target.position())
        fly(FlightMath.tilted(heading, (altitudeError * 0.1).coerceIn(-4.0, 18.0)), INGRESS_THROTTLE)
    }

    // ================================================================================================== orbit

    private fun tickOrbit(target: Entity) {
        val cruise = cruiseAltitude()
        val r = radiusNow()

        // Too far from the target to ever bring the guns to bear (it ran, or teleported): fly in again.
        if (FlightMath.horizontalDistance(boss.position(), target.position()) > r * ORBIT_LOSE_FACTOR) {
            enter(Ac130State.INGRESS)
            return
        }
        // The autopilot sheds altitude in tight banked turns: climb back instead of orbiting low.
        if (boss.y < cruise - ALTITUDE_RECOVERY && stateTicks > 60) {
            enter(Ac130State.INGRESS)
            return
        }

        manageFireMode(target)
        val centre = followTarget(orbitFocus(target), r)
        adaptRadius(target)
        val adapted = radiusNow()
        ensureLoiter(centre.x, cruise, centre.z, adapted)
        trimOrbitThrottle(cruise)

        // A rare show-off pass, never when there is nothing to shoot it down with and never replacing the guns.
        if (stateTicks >= nextTauntRollTick) {
            nextTauntRollTick = stateTicks + randomBetween(TAUNT_ROLL_MIN, TAUNT_ROLL_MAX)
            if (fireMode == Ac130FireMode.NORMAL && Ac130Config.TAUNT_ENABLED.get() && gunnery.anyEnabled() &&
                boss.getRandom().nextInt(TAUNT_WEIGHT + ORBIT_CONTINUE_WEIGHT) < TAUNT_WEIGHT
            ) {
                enter(Ac130State.TAUNT)
            }
        }
    }

    /**
     * Orbit centre: eases toward [position] (lerp [CENTRE_LERP] per tick) and snaps once the target is more than
     * [CENTRE_SNAP_FACTOR] x radius off it. The side guns only cover +-15 degrees around the plane's left wing, so a
     * centre that trails a running target swings it out of every gun's arc.
     */
    private fun followTarget(position: Vec3, r: Double): Vec3 {
        val current = orbitCentre
        val centre = when {
            current == null -> position
            FlightMath.horizontalDistance(current, position) > r * CENTRE_SNAP_FACTOR -> position
            else -> current.lerp(position, CENTRE_LERP)
        }
        orbitCentre = centre
        return centre
    }

    /**
     * Every [RADIUS_ADAPT_INTERVAL] ticks, moves the radius toward the value that puts the target in the middle of the
     * enabled guns' depression windows ([Ac130Gunnery.suggestedRadius]): wider when the target sits too steeply below
     * the guns, tighter when it is too shallow. Eased ([RADIUS_GAIN]) because the aircraft takes a while to follow.
     */
    private fun adaptRadius(target: Entity) {
        val now = boss.tickCount
        if (now < nextRadiusAdaptTick) return
        nextRadiusAdaptTick = now + RADIUS_ADAPT_INTERVAL

        val wanted = gunnery.suggestedRadius(target) ?: return
        val base = baseRadius()
        val goal = (wanted * ORBIT_TIGHTEN).coerceIn(base * MIN_RADIUS_FACTOR, base * MAX_RADIUS_FACTOR)
        val before = radiusNow()
        radius = Mth.lerp(RADIUS_GAIN, before, goal)

        if (Ac130Config.DEBUG_LOGGING.get() && abs(radius - before) > 1.0 && boss.tickCount % 40 < RADIUS_ADAPT_INTERVAL) {
            SuperbEnemies.LOGGER.info(
                "[AC-130 boss] orbit radius {} -> {} (suggested {}, configured {})",
                "%.1f".format(before), "%.1f".format(radius), "%.1f".format(wanted), "%.1f".format(base)
            )
        }
    }

    // ================================================================================================== fire patterns

    /**
     * Rolls and ends the fire patterns while orbiting. Every [MODE_ROLL_MIN]..[MODE_ROLL_MAX] ticks a roll starts split
     * fire (`splitFireChance` percent) or focus fire (`focusFireChance` percent, checked after it); both need two
     * usable guns, so a single remaining gun (disabled, spent, lost) just fires normally. A running pattern ends on
     * time or as soon as only one gun is left.
     */
    private fun manageFireMode(target: Entity) {
        val now = boss.tickCount
        if (fireMode != Ac130FireMode.NORMAL) {
            if (now >= modeEndTick || gunnery.readyGuns().size < 2) {
                endFireMode()
            } else if (fireMode == Ac130FireMode.SPLIT && now >= nextSplitRefreshTick) {
                (boss.level() as? ServerLevel)?.let { refreshSplit(it, target) }
            }
            return
        }
        if (stateTicks < nextModeRollTick) return
        nextModeRollTick = stateTicks + randomBetween(MODE_ROLL_MIN, MODE_ROLL_MAX)
        if (gunnery.readyGuns().size < 2) return

        val split = Ac130Config.SPLIT_FIRE_CHANCE.get()
        val focus = Ac130Config.FOCUS_FIRE_CHANCE.get()
        val roll = boss.getRandom().nextInt(100)
        when {
            roll < split -> startSplit(target)
            roll < split + focus -> startFocus(target)
        }
    }

    private fun startFocus(target: Entity) {
        fireMode = Ac130FireMode.FOCUS
        modeEndTick = boss.tickCount + randomBetween(FOCUS_MIN, FOCUS_MAX)
        focusPoint = target.boundingBox.center
    }

    private fun startSplit(target: Entity) {
        fireMode = Ac130FireMode.SPLIT
        modeEndTick = boss.tickCount + randomBetween(SPLIT_MIN, SPLIT_MAX)
        splitSpotAngle = boss.getRandom().nextDouble() * Math.PI * 2.0
        (boss.level() as? ServerLevel)?.let { refreshSplit(it, target) }
    }

    /**
     * One gun per victim: the main target and the nearest others (see [Ac130Targeting.extraTargets]). A gun without a
     * victim of its own shoots a spot on a ring of [SPLIT_SPOT_RADIUS] blocks around the main target instead. The
     * orbit is centred on the victims' average position so every gun can reach its own.
     */
    private fun refreshSplit(level: ServerLevel, primary: Entity) {
        nextSplitRefreshTick = boss.tickCount + SPLIT_REFRESH_TICKS
        val guns = gunnery.readyGuns()
        val victims = listOf(primary) + targeting.extraTargets(level, primary, SPLIT_CLUSTER_RADIUS, guns.size - 1)

        val orders = EnumMap<Ac130Gun, GunAim>(Ac130Gun::class.java)
        for ((index, gun) in guns.withIndex()) {
            orders[gun] = if (index < victims.size) {
                GunAim(victims[index], Vec3.ZERO)
            } else {
                val angle = splitSpotAngle + index * (Math.PI * 2.0 / guns.size)
                GunAim(primary, Vec3(cos(angle) * SPLIT_SPOT_RADIUS, 0.0, sin(angle) * SPLIT_SPOT_RADIUS))
            }
        }
        splitPlan = Ac130FirePlan(orders)
        splitCentre = Vec3(victims.sumOf { it.x } / victims.size, primary.y, victims.sumOf { it.z } / victims.size)
    }

    private fun endFireMode() {
        clearFireMode()
        nextModeRollTick = stateTicks + randomBetween(MODE_ROLL_MIN, MODE_ROLL_MAX)
    }

    private fun clearFireMode() {
        fireMode = Ac130FireMode.NORMAL
        focusPoint = null
        splitPlan = null
        splitCentre = null
    }

    /** The point the orbit is centred on: the focus spot, the split victims' average, or the main target. */
    private fun orbitFocus(target: Entity): Vec3 = when (fireMode) {
        Ac130FireMode.FOCUS -> focusPoint ?: target.position()
        Ac130FireMode.SPLIT -> splitCentre ?: target.position()
        Ac130FireMode.NORMAL -> target.position()
    }

    /** The gun orders of the running pattern, or null for normal fire (and once the pattern's time is up). */
    private fun firePlan(): Ac130FirePlan? {
        if (fireMode == Ac130FireMode.NORMAL || boss.tickCount >= modeEndTick) return null
        return when (fireMode) {
            Ac130FireMode.SPLIT -> splitPlan
            Ac130FireMode.FOCUS -> focusPoint?.let { point ->
                Ac130FirePlan(gunnery.readyGuns().associateWith { GunAim(null, point) }, FOCUS_SCATTER_SCALE)
            }

            Ac130FireMode.NORMAL -> null
        }
    }

    // ================================================================================================== taunt

    /**
     * "Angel flares": one straight low pass over the target at ground + [TAUNT_PASS_HEIGHT], dropping flare bursts in
     * pairs within [TAUNT_FLARE_RANGE] blocks. Like the A-10's taunt it first opens the distance so the pass is a
     * straight run. It fires no guns ([tick] passes `mayFire = false`).
     */
    private fun tickTaunt(target: Entity) {
        val ground = max(target.y, guard.terrainAt(target.x, target.z, target.y))
        val altitudeError = ground + TAUNT_PASS_HEIGHT - boss.y
        val tilt = (altitudeError * 0.1).coerceIn(-6.0, 10.0)
        val distance = FlightMath.horizontalDistance(boss.position(), target.position())

        if (stateTicks > TAUNT_TIMEOUT) {
            enter(Ac130State.INGRESS)
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
                val heading = currentHeading()
                val ahead = (target.x - boss.x) * heading.x + (target.z - boss.z) * heading.z
                if (!tauntPassed && ahead < 0.0 && distance < TAUNT_PASSED_DISTANCE) tauntPassed = true

                // After the pass keep going straight; turning back to the target would end the flyby in a circle.
                fly(FlightMath.tilted(if (tauntPassed) heading else toTarget, tilt), 2.2f, maxBank = TAUNT_MAX_BANK)

                val now = boss.tickCount
                if (distance < TAUNT_FLARE_RANGE && flaresDropped < TAUNT_MAX_FLARES && now >= nextTauntFlareTick &&
                    !boss.recentlyDamaged(TAUNT_NO_FLARE_TICKS)
                ) {
                    releaseFlare()
                    flaresDropped++
                    // Two bursts back to back, then a gap: a pair every TAUNT_PAIR_INTERVAL ticks.
                    nextTauntFlareTick = now + if (flaresDropped % 2 == 1) 1 else TAUNT_PAIR_INTERVAL - 1
                }

                if (tauntPassed) enter(Ac130State.INGRESS)
            }
        }
    }

    // ================================================================================================== resupply

    /**
     * Out of ammunition: climb and fly to a point [RESUPPLY_DISTANCE] blocks beyond the target (away from where the
     * fight was), loiter there for `resupplySeconds`, then refill and go back to INGRESS to hunt again.
     */
    private fun tickResupply() {
        val aim = targeting.aim
        val dest = depot ?: run {
            val base = aim?.position() ?: boss.position()
            resupplyFrom = base
            val away = aim?.let { awayFrom(it) } ?: currentHeading()
            base.add(away.scale(resupplyDistance()))
        }.also { depot = it }
        val altitude = cruiseAltitude() + RESUPPLY_EXTRA_HEIGHT

        if (!resupplyArrived) {
            val altitudeError = altitude - boss.y
            fly(FlightMath.tilted(towards(dest), (altitudeError * 0.1).coerceIn(-4.0, 16.0)), 2.6f)
            val arrived = FlightMath.horizontalDistance(boss.position(), dest) < RESUPPLY_ARRIVE_DISTANCE
            if (arrived || stateTicks > RESUPPLY_TRAVEL_TIMEOUT) {
                resupplyArrived = true
                resupplyEndTick = boss.tickCount + Ac130Config.RESUPPLY_SECONDS.get().toInt() * 20
            }
            return
        }

        ensureLoiter(dest.x, altitude, dest.z, baseRadius())
        trimOrbitThrottle(altitude)
        if (boss.tickCount >= resupplyEndTick) {
            gunnery.refillAmmo()
            depot = null
            // The depot is far beyond the bar range: also tell the players at the fight (where the target is now).
            boss.announce(MSG_HUNTING, ResupplyNotices.HUNTING_STYLE, aim?.position() ?: resupplyFrom)
            enter(Ac130State.INGRESS)
        }
    }

    /**
     * How far beyond the target the depot lies. Without `globalAggro` a target farther than aggroRange x
     * [Ac130Targeting.LOSE_TARGET_FACTOR] is dropped, so the depot stays inside that (the boss then comes back to the
     * same player).
     */
    private fun resupplyDistance(): Double {
        if (Ac130Config.GLOBAL_AGGRO.get()) return RESUPPLY_DISTANCE
        val keep = Ac130Config.AGGRO_RANGE.get() * Ac130Targeting.LOSE_TARGET_FACTOR * RESUPPLY_KEEP_TARGET_FRACTION
        return min(RESUPPLY_DISTANCE, keep)
    }

    // ================================================================================================== evade

    private fun tickEvade() {
        // Break 90 degrees off the missile's bearing, nose slightly down if there is height to spare.
        val bearing = FlightMath.horizontal(threats.threatBearing ?: currentHeading(), currentHeading())
        val sideways = Vec3(-bearing.z * evadeSide, 0.0, bearing.x * evadeSide)
        val spare = boss.y - guard.floorAhead(referenceY() + UNKNOWN_TERRAIN_OFFSET)
        val pitch = if (spare > 120.0) -6.0 else 3.0
        fly(FlightMath.tilted(sideways, pitch), 3.0f, maxBank = EVADE_MAX_BANK)

        if (stateTicks >= evadeDuration) {
            val ticks = resumeTicks
            enter(resumeState, resume = true)
            stateTicks = ticks
        }
    }

    // ================================================================================================== terrain guard

    /**
     * Runs after the state logic and overrides the controls when ground clearance runs out. The flown states already
     * steer through [TerrainGuard.adjustDesired]; this catches what that cannot: the orbit and the loiters (flown by
     * SBW's autopilot, which has its own avoidance and is only overridden when it is losing, with a latch).
     */
    private fun safetyNet(target: Entity?) {
        if (state == Ac130State.CRIPPLED) return

        val v = boss.deltaMovement
        val descent = max(0.0, -v.y) * GUARD_DESCENT_FACTOR
        val floor = guard.floorAhead(referenceY(target) + UNKNOWN_TERRAIN_OFFSET)
        val clearance = boss.y - floor
        val buffer = GUARD_BASE_BUFFER + descent

        if (autopilotThisTick) {
            if (clearance < buffer * 0.6) guardLatched = true else if (clearance > buffer * 1.5) guardLatched = false
            if (guardLatched) flight.pullUp(currentHeading(), (1.0 - clearance / buffer).coerceIn(0.0, 1.0))
        } else {
            guardLatched = false
            if (clearance < buffer) flight.pullUp(currentHeading(), (1.0 - clearance / buffer).coerceIn(0.0, 1.0))
        }
    }

    private companion object {
        const val TAG_STATE = "State"

        const val MSG_WITHDRAW = "message.${SuperbEnemies.MOD_ID}.ac130_boss.withdraw"
        const val MSG_HUNTING = "message.${SuperbEnemies.MOD_ID}.ac130_boss.hunting"

        // Orbit
        const val MIN_RADIUS_FACTOR = 0.45
        /** The suggested radius is flown this much tighter: a tighter pylon turn keeps the guns on the target. */
        const val ORBIT_TIGHTEN = 0.85
        const val MAX_RADIUS_FACTOR = 1.8
        const val RADIUS_ADAPT_INTERVAL = 5
        const val RADIUS_GAIN = 0.1
        const val CENTRE_LERP = 0.2
        const val CENTRE_SNAP_FACTOR = 0.4
        const val ORBIT_LOSE_FACTOR = 3.0
        const val INGRESS_CIRCLE_FACTOR = 1.8
        const val INGRESS_THROTTLE = 2.8f

        // Banking (degrees); the vehicle data allows 65
        const val MAX_BANK = 65f
        const val EVADE_MAX_BANK = 75f
        const val TAUNT_MAX_BANK = 45f
        const val INGRESS_ARRIVAL_MARGIN = 20.0
        const val INGRESS_TIMEOUT = 1500
        const val ALTITUDE_RECOVERY = 40.0
        const val ORBIT_THROTTLE = 1.8
        const val ORBIT_THROTTLE_MIN = 1.6
        const val ORBIT_THROTTLE_MAX = 2.8

        // Fire patterns: a roll every MODE_ROLL ticks, each pattern lasts a few seconds
        const val MODE_FIRST_ROLL_MIN = 200
        const val MODE_FIRST_ROLL_MAX = 300
        const val MODE_ROLL_MIN = 300
        const val MODE_ROLL_MAX = 500
        const val SPLIT_MIN = 160
        const val SPLIT_MAX = 240
        const val FOCUS_MIN = 120
        const val FOCUS_MAX = 180
        const val SPLIT_CLUSTER_RADIUS = 70.0
        const val SPLIT_REFRESH_TICKS = 60
        const val SPLIT_SPOT_RADIUS = 14.0
        const val FOCUS_SCATTER_SCALE = 0.35

        // Taunt
        const val TAUNT_ROLL_MIN = 400
        const val TAUNT_ROLL_MAX = 600
        const val TAUNT_WEIGHT = 6
        const val ORBIT_CONTINUE_WEIGHT = 30
        const val TAUNT_PASS_HEIGHT = 50.0
        const val TAUNT_RUN_IN_DISTANCE = 250.0
        const val TAUNT_PASSED_DISTANCE = 200.0
        const val TAUNT_FLARE_RANGE = 90.0
        const val TAUNT_PAIR_INTERVAL = 4
        const val TAUNT_MAX_FLARES = 16
        const val TAUNT_NO_FLARE_TICKS = 200
        const val TAUNT_TIMEOUT = 700

        // Resupply
        const val RESUPPLY_DISTANCE = 600.0
        /** Without globalAggro the depot is at most this fraction of the lose-target distance from the target. */
        const val RESUPPLY_KEEP_TARGET_FRACTION = 0.75
        const val RESUPPLY_EXTRA_HEIGHT = 60.0
        const val RESUPPLY_ARRIVE_DISTANCE = 100.0
        const val RESUPPLY_TRAVEL_TIMEOUT = 2400

        // Terrain guard
        const val UNKNOWN_TERRAIN_OFFSET = 40.0
        const val GUARD_BASE_BUFFER = 12.0
        const val GUARD_DESCENT_FACTOR = 14.0
    }
}
