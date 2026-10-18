package com.teamuhi.superbenemies.config

/** What a boss may pick as its target (`a10_boss.targetPriority`, `ac130_boss.targetPriority`). */
enum class TargetPriority(val players: Boolean, val mobs: Boolean, val planes: Boolean = false) {
    /** Survival / adventure players (and player dummies) only; mobs are ignored. */
    PLAYERS(players = true, mobs = false),

    /** Players and any living mob alike. */
    PLAYERS_AND_MOBS(players = true, mobs = true),

    /** Living mobs only; players are never attacked (the boss still flies, and players can still shoot it down). */
    MOBS_ONLY(players = false, mobs = true),

    /**
     * EXPERIMENTAL. Other aircraft only: manned SBW airplanes and helicopters, and the other boss. Players on foot, mobs
     * and empty parked planes are ignored. The normal attack patterns are reused, so fast movers are often missed.
     */
    OTHER_PLANES(players = false, mobs = false, planes = true)
}
