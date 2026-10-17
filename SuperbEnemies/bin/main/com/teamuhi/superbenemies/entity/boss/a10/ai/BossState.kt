package com.teamuhi.superbenemies.entity.boss.a10.ai

/**
 * Flight / attack states of the A-10 boss. The transitions live in [A10BossBrain].
 *
 * There is deliberately no separate "loiter" state: [INGRESS] without a target orbits the home position.
 */
enum class BossState {
    /** Climb to cruise altitude on the way to the target (or orbit the home position when there is none). */
    INGRESS,

    /** Orbit the target at cruise altitude firing missiles and rockets; SBW loiter autopilot flies the circle. */
    ORBIT_BARRAGE,

    /** Straight, level run over the target, releasing a stick of bombs. */
    BOMB_RUN,

    /** Fly away from the target and climb to the top of the dive. */
    CLIMB_FOR_DIVE,

    /** Steep dive with GAU-8 cannon bursts. */
    GUN_DIVE,

    /** Recover from the dive. */
    PULL_OUT,

    /** Between the two passes of a double gun run: a barrel-roll U-turn back toward the target, climbing to dive height. */
    U_TURN,

    /** Harmless show-off pass over the target: a low flyby or a barrel roll, dropping flares. */
    TAUNT,

    /** Fly away and regain altitude after an attack. */
    EXTEND,

    /** Break turn after a homing missile was detected. Interrupts any other state, which resumes afterwards. */
    EVADE,

    /** Out of ammunition (`limitedAmmo`): fly far away, loiter while "rearming", then return and hunt again. */
    RESUPPLY,

    /** Health at or below 10%: controls released, the airframe crashes under SBW physics. Terminal. */
    CRIPPLED
}
