package com.teamuhi.superbenemies.config

import net.minecraftforge.common.ForgeConfigSpec

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
    val TARGET_PRIORITY: ForgeConfigSpec.EnumValue<TargetPriority>
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
    val SPLIT_FIRE_CHANCE: ForgeConfigSpec.IntValue
    val FOCUS_FIRE_CHANCE: ForgeConfigSpec.IntValue

    val LIMITED_AMMO: ForgeConfigSpec.BooleanValue
    val CANNON_ROUNDS: ForgeConfigSpec.IntValue
    val BOFORS_ROUNDS: ForgeConfigSpec.IntValue
    val HOWITZER_ROUNDS: ForgeConfigSpec.IntValue
    val FLARE_COUNT: ForgeConfigSpec.IntValue
    val RESUPPLY_SECONDS: ForgeConfigSpec.IntValue

    val DEFENSIVE_FLARES: ForgeConfigSpec.BooleanValue
    val FLARE_MISS_CHANCE: ForgeConfigSpec.IntValue
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
            .comment("Boss health. The boss bar empties at 10% of this, when the gunship starts to crash.")
            .defineInRange("maxHealth", 3000, 100, 1_000_000)

        CRUISE_HEIGHT = b
            .comment("Orbit altitude in blocks above the target.")
            .defineInRange("cruiseHeightAboveTarget", 90, 40, 400)

        AGGRO_RANGE = b
            .comment("Range (blocks) in which the boss picks a player as target. Unused if globalAggro is on.")
            .defineInRange("aggroRange", 256, 32, 1024)

        GLOBAL_AGGRO = b
            .comment("Hunt the nearest player anywhere in the dimension and show the boss bar to everyone.")
            .define("globalAggro", true)

        ORBIT_RADIUS = b
            .comment("Standoff radius (blocks) of the orbit around the target. The AI adjusts it between 0.45x and 1.8x.")
            .defineInRange("orbitRadius", 180, 100, 400)

        TARGET_PRIORITY = b
            .comment(
                "What the boss attacks: PLAYERS, PLAYERS_AND_MOBS (nearest), MOBS_ONLY or OTHER_PLANES",
                "(experimental: manned airplanes/helicopters and other bosses only).",
                "Mobs are only engaged under open sky."
            )
            .defineEnum("targetPriority", TargetPriority.PLAYERS_AND_MOBS)

        MOB_SCAN_RANGE = b
            .comment("Range (blocks) in which mobs are considered as targets. Only used with PLAYERS_AND_MOBS / MOBS_ONLY.")
            .defineInRange("mobScanRange", 128, 32, 256)

        RANDOM_TARGETING = b
            .comment("Switch to a random target periodically. Players are drawn first, mobs only if no player is valid.")
            .define("randomTargeting", true)

        RANDOM_TARGET_SECONDS = b
            .comment("With randomTargeting: average seconds on one target (varies by +-25%).")
            .defineInRange("randomTargetSeconds", 20, 5, 300)

        b.pop().push("ac130_damage")

        CANNON_DAMAGE_MULT = b
            .comment("Damage multiplier for the 25mm cannon. Applied when the boss is first created.")
            .defineInRange("cannon25mmDamageMultiplier", 1.0, 0.0, 100.0)
        BOFORS_DAMAGE_MULT = b
            .comment("Damage multiplier for the 40mm Bofors. Applied when the boss is first created.")
            .defineInRange("bofors40mmDamageMultiplier", 1.0, 0.0, 100.0)
        HOWITZER_DAMAGE_MULT = b
            .comment("Damage multiplier for the 105mm howitzer. Applied when the boss is first created.")
            .defineInRange("howitzer105mmDamageMultiplier", 1.0, 0.0, 100.0)

        BLOCK_DAMAGE = b
            .comment("Whether shells and the crash explosion break blocks. Applied when the boss is first created.")
            .define("blockDamage", true)

        b.pop().push("ac130_weapons")

        CANNON_ENABLED = b
            .comment("Whether the boss may fire the 25mm cannon. With every weapon off it only flies and circles.")
            .define("cannon25mmEnabled", true)
        BOFORS_ENABLED = b
            .comment("Whether the boss may fire the 40mm Bofors.")
            .define("bofors40mmEnabled", true)
        HOWITZER_ENABLED = b
            .comment("Whether the boss may fire the 105mm howitzer.")
            .define("howitzer105mmEnabled", true)

        CANNON_SIMULTANEOUS = b
            .comment(
                "Whether the 25mm cannon fires together with the other guns marked simultaneous.",
                "Unmarked guns take turns firing alone (about 3 seconds each)."
            )
            .define("cannon25mmSimultaneous", true)
        BOFORS_SIMULTANEOUS = b
            .comment("Whether the 40mm Bofors fires together with the other simultaneous guns.")
            .define("bofors40mmSimultaneous", true)
        HOWITZER_SIMULTANEOUS = b
            .comment("Whether the 105mm howitzer fires together with the other simultaneous guns.")
            .define("howitzer105mmSimultaneous", true)

        SPLIT_FIRE_CHANCE = b
            .comment(
                "Split fire: every gun shoots a different target at the same time (other players or mobs near the main one,",
                "otherwise a different spot around it). Percent chance that the periodic pattern roll (every 15-25 seconds of",
                "circling) starts it. 0 = never. Needs at least two usable guns. Read live."
            )
            .defineInRange("splitFireChance", 20, 0, 100)
        FOCUS_FIRE_CHANCE = b
            .comment(
                "Focus fire: every gun pounds the same fixed spot (where the target stood when it began) while the gunship",
                "circles it. Percent chance that the periodic pattern roll starts it, checked after splitFireChance",
                "(the two add up, the rest of the time the guns fire normally). 0 = never. Needs at least two usable guns.",
                "Read live."
            )
            .defineInRange("focusFireChance", 20, 0, 100)

        b.pop().push("ac130_ammo")

        LIMITED_AMMO = b
            .comment("Finite ammo. When spent, the boss flies away to resupply, then returns with a full load.")
            .define("limitedAmmo", false)
        CANNON_ROUNDS = b
            .comment("25mm rounds per load (fires 20 a second).")
            .defineInRange("cannon25mmRounds", 1500, 1, 100_000)
        BOFORS_ROUNDS = b
            .comment("40mm Bofors rounds per load.")
            .defineInRange("bofors40mmRounds", 120, 1, 10_000)
        HOWITZER_ROUNDS = b
            .comment("105mm howitzer rounds per load.")
            .defineInRange("howitzer105mmRounds", 30, 1, 1_000)
        FLARE_COUNT = b
            .comment("Flare bursts per load when limitedAmmo is on.")
            .defineInRange("flares", 32, 1, 1_000)
        RESUPPLY_SECONDS = b
            .comment("Seconds a withdrawn boss stays gone before its replacement (same health, full load) appears near the fight. The flight away is extra.")
            .defineInRange("resupplySeconds", 40, 0, 3_600)

        b.pop().push("ac130_behaviour")

        DEFENSIVE_FLARES = b
            .comment("Whether the boss releases flares against homing missiles. Off by default so missiles can hit.")
            .define("defensiveFlares", false)

        FLARE_MISS_CHANCE = b
            .comment("With defensiveFlares: chance (percent) of missing an incoming missile and releasing no flares.")
            .defineInRange("flareMissChance", 10, 0, 100)

        TAUNT_ENABLED = b
            .comment("Whether the boss may taunt: a rare low pass dropping \"angel\" flares. Harmless.")
            .define("tauntEnabled", true)

        IGNORE_BLOCK_COLLISIONS = b
            .comment(
                "Fly straight through terrain and buildings with no collision damage. A crippled boss collides",
                "normally again so its wreck still crashes."
            )
            .define("ignoreBlockCollisions", false)

        BOSS_BAR_RANGE = b
            .comment("Players within this many blocks see the boss bar.")
            .defineInRange("bossBarRange", 300, 32, 1024)

        XP_REWARD = b
            .comment("Experience dropped when the boss is defeated.")
            .defineInRange("xpReward", 800, 0, 100_000)

        DEBUG_LOGGING = b
            .comment("Log the AI's targeting and gunnery decisions to the server log.")
            .define("debugLogging", false)

        b.pop()
        SPEC = b.build()

        ALL = listOf(
            MAX_HEALTH, CRUISE_HEIGHT, AGGRO_RANGE, GLOBAL_AGGRO, ORBIT_RADIUS, TARGET_PRIORITY, MOB_SCAN_RANGE,
            RANDOM_TARGETING, RANDOM_TARGET_SECONDS,
            CANNON_DAMAGE_MULT, BOFORS_DAMAGE_MULT, HOWITZER_DAMAGE_MULT, BLOCK_DAMAGE,
            CANNON_ENABLED, BOFORS_ENABLED, HOWITZER_ENABLED,
            CANNON_SIMULTANEOUS, BOFORS_SIMULTANEOUS, HOWITZER_SIMULTANEOUS,
            SPLIT_FIRE_CHANCE, FOCUS_FIRE_CHANCE,
            LIMITED_AMMO, CANNON_ROUNDS, BOFORS_ROUNDS, HOWITZER_ROUNDS, FLARE_COUNT, RESUPPLY_SECONDS,
            DEFENSIVE_FLARES, FLARE_MISS_CHANCE, TAUNT_ENABLED, IGNORE_BLOCK_COLLISIONS, BOSS_BAR_RANGE, XP_REWARD, DEBUG_LOGGING
        )
    }
}
