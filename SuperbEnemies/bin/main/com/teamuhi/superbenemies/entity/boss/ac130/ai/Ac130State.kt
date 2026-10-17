package com.teamuhi.superbenemies.entity.boss.ac130.ai

/**
 * The AC-130 boss's high-level behaviour. There are deliberately no dive, pull-out or extend states: the gunship
 * never leaves its altitude to attack, it circles the target and lets the left-side gunners do the work.
 *
 *  - [INGRESS]  fly to the target at cruise altitude (or loiter over home without a target)
 *  - [ORBIT]    the pylon turn around the target; all guns fire from here
 *  - [TAUNT]    rare low pass over the target dropping "angel" flares; fires nothing
 *  - [EVADE]    break away from an incoming homing missile, then resume the previous state
 *  - [RESUPPLY] limited ammunition ran dry: fly to a depot, loiter, refill
 *  - [CRIPPLED] terminal; the controls are released and the shared lifecycle handles the crash
 */
enum class Ac130State {
    INGRESS,
    ORBIT,
    TAUNT,
    EVADE,
    RESUPPLY,
    CRIPPLED
}
