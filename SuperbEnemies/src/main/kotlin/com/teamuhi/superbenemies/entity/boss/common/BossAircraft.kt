package com.teamuhi.superbenemies.entity.boss.common

import net.minecraft.network.chat.Style
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

/**
 * What the shared boss code ([BossLifecycle], [BossCrewEntity], the `common.ai` helpers and the spawn egg) needs to know
 * about a boss aircraft. Implemented by every boss vehicle (the A-10 and the AC-130), so the shared pieces never refer
 * to a concrete boss class or to a boss's own config object.
 */
interface BossAircraft {
    /** True once health has dropped to the crash threshold: the controls are released and the wreck burns down. */
    val crippled: Boolean

    /** Remaining health as a fraction of the maximum, 0..1. */
    fun healthFraction(): Float

    /** True when [entity] is one of this boss's hidden crew members (pilot or gunner). */
    fun isCrew(entity: Entity?): Boolean

    /** Same as [isCrew] for a string UUID, e.g. the target UUID of a homing missile. Null is never crew. */
    fun isCrewUuid(uuid: String?): Boolean

    /** Whether the boss may release flares against homing missiles (config, read live). */
    fun defensiveFlares(): Boolean

    /** Chance (0-1) that the boss fails to notice an incoming homing missile (config, read live). */
    fun flareMissChance(): Double

    /** Whether flare bursts use a finite load instead of SBW's replenished dispenser. */
    fun limitedAmmo(): Boolean

    /** Configured flare bursts in a finite load. */
    fun flareAmmoLoad(): Int

    /** Called by the spawn egg before the entity is added: sets the home point and puts the aircraft in the air. */
    fun prepareLaunch(home: Vec3)

    /** True when the boss took damage within the last [ticks] ticks. */
    fun recentlyDamaged(ticks: Int): Boolean

    /**
     * Makes this (new) boss start at [fraction] of its maximum health instead of full: the replacement of a boss that
     * withdrew to rearm (see [BossRespawns]). Call before the entity is added to the level.
     */
    fun restoreHealthFraction(fraction: Float)

    /** Chat message to the players around the boss, and around [alsoNear] when given. */
    fun announce(translationKey: String, style: Style, alsoNear: Vec3? = null)
}
