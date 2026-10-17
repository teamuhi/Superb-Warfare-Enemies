package com.teamuhi.superbenemies.config

import net.minecraftforge.common.ForgeConfigSpec

/** What the AC-130 may pick as its target (`ac130_boss.targetPriority`). */
enum class Ac130TargetPriority(val players: Boolean, val mobs: Boolean) {
    /** Survival / adventure players only; mobs are ignored. */
    PLAYERS(players = true, mobs = false),

    /** Players and any living mob alike: the nearest one is engaged. */
    PLAYERS_AND_MOBS(players = true, mobs = true),

    /** Living mobs only; players are never attacked (it still flies, and players can still shoot it down). */
    MOBS_ONLY(players = false, mobs = true)
}

/**
 * Common config of the AC-130 boss, written to `config/superbenemies-ac130-common.toml`.
 *
 * It mirrors [BossConfig] (the A-10) but lives in its own file so each boss can be tuned independently. Section names
 * carry an `ac130_` prefix so the in-game config screen's header keys stay unique across both files.
 *
 * Health, damage multipliers and block damage are applied to a boss once, when it is first created
 * (they are stored in the entity data afterwards). Everything else is read live by the AI.
 */
object Ac130Config {
    val SPEC: ForgeConfigSpec

    val MAX_HEALTH: ForgeConfigSpec.IntValue
    val CRUISE_HEIGHT: ForgeConfigSpec.IntValue
    val AGGRO_RANGE: ForgeConfigSpec.IntValue
    val GLOBAL_AGGRO: ForgeConfigSpec.BooleanValue
    val ORBIT_RADIUS: ForgeConfigSpec.IntValue
    val TARGET_PRIORITY: ForgeConfigSpec.EnumValue<Ac130TargetPriority>
    val MOB_SCAN_RANGE: ForgeConfigSpec.IntValue
    val RANDOM_TARGETING: ForgeConfigSpec.BooleanValue
    val RANDOM_TARGET_SECONDS: ForgeConfigSpec.IntValue

    val CANNON_DAMAGE_MULT: ForgeConfigSpec.DoubleValue
    val BOFORS_DAMAGE_MULT: ForgeConfigSpec.DoubleValue
    val HOWITZER_DAMAGE_MULT: ForgeConfigSpec.DoubleValue
    val BLOCK_DAMAGE: ForgeConfigSpec.BooleanValue

    val CANNON_ENABLED: ForgeConfigSpec.BooleanValue
    val BOFORS_ENABLED: ForgeConfigSpec.BooleanValue
    val HOWITZER_ENABLED: ForgeConfigSpec.BooleanValue
    val CANNON_SIMULTANEOUS: ForgeConfigSpec.BooleanValue
    val BOFORS_SIMULTANEOUS: ForgeConfigSpec.BooleanValue
    val HOWITZER_SIMULTANEOUS: ForgeConfigSpec.BooleanValue

    val LIMITED_AMMO: ForgeConfigSpec.BooleanValue
    val CANNON_ROUNDS: ForgeConfigSpec.IntValue
    val BOFORS_ROUNDS: ForgeConfigSpec.IntValue
    val HOWITZER_ROUNDS: ForgeConfigSpec.IntValue
    val RESUPPLY_SECONDS: ForgeConfigSpec.IntValue

    val DEFENSIVE_FLARES: ForgeConfigSpec.BooleanValue
    val FLARE_MISS_CHANCE: ForgeConfigSpec.DoubleValue
    val TAUNT_ENABLED: ForgeConfigSpec.BooleanValue
    val IGNORE_BLOCK_COLLISIONS: ForgeConfigSpec.BooleanValue
    val BOSS_BAR_RANGE: ForgeConfigSpec.IntValue
    val XP_REWARD: ForgeConfigSpec.IntValue
    val DEBUG_LOGGING: ForgeConfigSpec.BooleanValue

    /** Every option in file order (grouped by section); the in-game config screen is built from this. */
    val ALL: List<ForgeConfigSpec.ConfigValue<*>>

    init {
        val b = ForgeConfigSpec.Builder()

        b.comment("AC-130 boss: Angel of Death").push("ac130_boss")

        MAX_HEALTH = b
            .comment(
                "Boss health. The boss bar empties at 10% of this value, which is when the gunship starts to crash.",
                "Applied when the boss is first created."
            )
            .defineInRange("maxHealth", 3000, 100, 1_000_000)

        CRUISE_HEIGHT = b
            .comment("Orbit altitude, in blocks above the target. The guns fire down at it from this height.")
            .defineInRange("cruiseHeightAboveTarget", 90, 40, 400)

        AGGRO_RANGE = b
            .comment(
                "Horizontal range (blocks) in which the boss picks a survival or adventure player as its target.",
                "Only used when globalAggro is false."
            )
            .defineInRange("aggroRange", 256, 32, 1024)

        GLOBAL_AGGRO = b
            .comment(
                "The boss hunts the nearest survival or adventure player anywhere in its dimension and never loses them",
                "by distance (its chunks are kept loaded). Also shows the boss bar to every player in the dimension.",
                "Read live."
            )
            .define("globalAggro", true)

        ORBIT_RADIUS = b
            .comment(
                "Radius (blocks) of the left-hand orbit the gunship flies around its target. The AI widens or tightens it",
                "within 0.6x to 1.8x of this value to keep the target inside the guns' firing window.",
                "Read live."
            )
            .defineInRange("orbitRadius", 120, 60, 400)

        TARGET_PRIORITY = b
            .comment(
                "What the boss attacks: PLAYERS (players only), PLAYERS_AND_MOBS (players and any living mob, the nearest",
                "is engaged) or MOBS_ONLY (never players). Whoever last hurt it takes priority if it is an allowed target.",
                "Mobs are only engaged under open sky, so it never shells caves. Read live."
            )
            .defineEnum("targetPriority", Ac130TargetPriority.PLAYERS_AND_MOBS)

        MOB_SCAN_RANGE = b
            .comment("Unless targetPriority is PLAYERS: horizontal range (blocks) around the boss in which mobs are considered. Read live.")
            .defineInRange("mobScanRange", 128, 32, 256)

        RANDOM_TARGETING = b
            .comment(
                "Switch to a random target every randomTargetSeconds instead of sticking to one (the nearest, or whoever last",
                "hurt it). Players (and dummies) are drawn first; mobs only when no player is a valid target. Read live."
            )
            .define("randomTargeting", true)

        RANDOM_TARGET_SECONDS = b
            .comment("Only with randomTargeting: average seconds on one target before a new one is drawn (varies by +-25%). Read live.")
            .defineInRange("randomTargetSeconds", 20, 5, 300)

        b.pop().push("ac130_damage")

        CANNON_DAMAGE_MULT = b
            .comment("Multiplier for 25mm cannon damage (direct and explosion). Applied when the boss is first created.")
            .defineInRange("cannon25mmDamageMultiplier", 1.0, 0.0, 100.0)
        BOFORS_DAMAGE_MULT = b
            .comment("Multiplier for 40mm Bofors cannon damage. Applied when the boss is first created.")
            .defineInRange("bofors40mmDamageMultiplier", 1.0, 0.0, 100.0)
        HOWITZER_DAMAGE_MULT = b
            .comment("Multiplier for 105mm howitzer damage. Applied when the boss is first created.")
            .defineInRange("howitzer105mmDamageMultiplier", 1.0, 0.0, 100.0)

        BLOCK_DAMAGE = b
            .comment(
                "Whether the shells and the final crash explosion break blocks.",
                "Applied when the boss is first created."
            )
            .define("blockDamage", true)

        b.pop().push("ac130_weapons")

        CANNON_ENABLED = b
            .comment(
                "Whether the boss may fire the 25mm cannon (M61).",
                "Read live. If every weapon is disabled the boss still flies and circles its target but never attacks."
            )
            .define("cannon25mmEnabled", true)
        BOFORS_ENABLED = b
            .comment("Whether the boss may fire the 40mm Bofors cannon. Read live.")
            .define("bofors40mmEnabled", true)
        HOWITZER_ENABLED = b
            .comment("Whether the boss may fire the 105mm howitzer. Read live.")
            .define("howitzer105mmEnabled", true)

        CANNON_SIMULTANEOUS = b
            .comment(
                "Whether the 25mm cannon fires at the same time as the other guns marked simultaneous.",
                "Guns marked simultaneous fire together as one group; every gun not marked gets its own turn and fires",
                "alone. Turns rotate: about 3 seconds each (the 105mm turn ends after one shell), passed on early when the",
                "gun cannot fire. All on = everything fires at once; all off = one gun at a time. Read live."
            )
            .define("cannon25mmSimultaneous", true)
        BOFORS_SIMULTANEOUS = b
            .comment("Whether the 40mm Bofors fires at the same time as the other guns marked simultaneous. Read live.")
            .define("bofors40mmSimultaneous", true)
        HOWITZER_SIMULTANEOUS = b
            .comment("Whether the 105mm howitzer fires at the same time as the other guns marked simultaneous. Read live.")
            .define("howitzer105mmSimultaneous", true)

        b.pop().push("ac130_ammo")

        LIMITED_AMMO = b
            .comment(
                "Whether the boss has a finite load of ammunition. When every gun it may use is spent it flies far away",
                "to resupply, then comes back to hunt again.",
                "Read live. A full load is restored on every resupply."
            )
            .define("limitedAmmo", false)
        CANNON_ROUNDS = b
            .comment("25mm cannon rounds per load (the gun fires 20 rounds a second).")
            .defineInRange("cannon25mmRounds", 1500, 1, 100_000)
        BOFORS_ROUNDS = b
            .comment("40mm Bofors rounds per load.")
            .defineInRange("bofors40mmRounds", 120, 1, 10_000)
        HOWITZER_ROUNDS = b
            .comment("105mm howitzer rounds per load.")
            .defineInRange("howitzer105mmRounds", 30, 1, 1_000)
        RESUPPLY_SECONDS = b
            .comment("Seconds the boss spends at its resupply point before it returns (the flight there and back is extra).")
            .defineInRange("resupplySeconds", 40, 0, 3_600)

        b.pop().push("ac130_behaviour")

        DEFENSIVE_FLARES = b
            .comment(
                "Whether the boss releases flares to defeat homing missiles that are tracking it.",
                "Off by default so player missiles can actually hit. It still breaks away from incoming missiles.",
                "Read live."
            )
            .define("defensiveFlares", false)

        FLARE_MISS_CHANCE = b
            .comment("Only with defensiveFlares: chance (0-1) that the boss fails to notice an incoming homing missile and releases no flares.")
            .defineInRange("flareMissChance", 0.1, 0.0, 1.0)

        TAUNT_ENABLED = b
            .comment(
                "Whether the boss may taunt: a rare low pass over the target, dropping \"angel\" flares. Harmless.",
                "Read live."
            )
            .define("tauntEnabled", true)

        IGNORE_BLOCK_COLLISIONS = b
            .comment(
                "The boss flies straight through terrain and buildings: no collision damage, no bouncing, no getting stuck,",
                "and it does not smash blocks on the way. Use this if it keeps crashing into mountains.",
                "A crippled boss collides normally again so its wreck still crashes and explodes.",
                "Read live."
            )
            .define("ignoreBlockCollisions", false)

        BOSS_BAR_RANGE = b
            .comment("Players within this many blocks of the boss see its boss bar. Read live.")
            .defineInRange("bossBarRange", 300, 32, 1024)

        XP_REWARD = b
            .comment("Experience dropped when the boss is defeated. Read live.")
            .defineInRange("xpReward", 800, 0, 100_000)

        DEBUG_LOGGING = b
            .comment("Log the AI's targeting and gunnery decisions to the server log. Read live.")
            .define("debugLogging", false)

        b.pop()
        SPEC = b.build()

        ALL = listOf(
            MAX_HEALTH, CRUISE_HEIGHT, AGGRO_RANGE, GLOBAL_AGGRO, ORBIT_RADIUS, TARGET_PRIORITY, MOB_SCAN_RANGE,
            RANDOM_TARGETING, RANDOM_TARGET_SECONDS,
            CANNON_DAMAGE_MULT, BOFORS_DAMAGE_MULT, HOWITZER_DAMAGE_MULT, BLOCK_DAMAGE,
            CANNON_ENABLED, BOFORS_ENABLED, HOWITZER_ENABLED,
            CANNON_SIMULTANEOUS, BOFORS_SIMULTANEOUS, HOWITZER_SIMULTANEOUS,
            LIMITED_AMMO, CANNON_ROUNDS, BOFORS_ROUNDS, HOWITZER_ROUNDS, RESUPPLY_SECONDS,
            DEFENSIVE_FLARES, FLARE_MISS_CHANCE, TAUNT_ENABLED, IGNORE_BLOCK_COLLISIONS, BOSS_BAR_RANGE, XP_REWARD, DEBUG_LOGGING
        )
    }
}
