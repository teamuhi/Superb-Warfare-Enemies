package com.teamuhi.superbenemies.config

import net.minecraftforge.common.ForgeConfigSpec

/**
 * Common config, written to `config/superbenemies-common.toml`.
 *
 * Health, damage multipliers and block damage are applied to a boss once, when it is first created
 * (they are stored in the entity data afterwards). Everything else is read live by the AI.
 */
object BossConfig {
    val SPEC: ForgeConfigSpec

    val MAX_HEALTH: ForgeConfigSpec.IntValue
    val CRUISE_HEIGHT: ForgeConfigSpec.IntValue
    val AGGRO_RANGE: ForgeConfigSpec.IntValue
    val GLOBAL_AGGRO: ForgeConfigSpec.BooleanValue
    val RANDOM_TARGETING: ForgeConfigSpec.BooleanValue
    val ORBIT_RADIUS: ForgeConfigSpec.IntValue

    val CANNON_DAMAGE_MULT: ForgeConfigSpec.DoubleValue
    val ROCKET_DAMAGE_MULT: ForgeConfigSpec.DoubleValue
    val BOMB_DAMAGE_MULT: ForgeConfigSpec.DoubleValue
    val MISSILE_DAMAGE_MULT: ForgeConfigSpec.DoubleValue

    val CANNON_ENABLED: ForgeConfigSpec.BooleanValue
    val ROCKETS_ENABLED: ForgeConfigSpec.BooleanValue
    val BOMBS_ENABLED: ForgeConfigSpec.BooleanValue
    val MISSILES_ENABLED: ForgeConfigSpec.BooleanValue

    val CANNON_CHANCE: ForgeConfigSpec.IntValue
    val ROCKET_CHANCE: ForgeConfigSpec.IntValue
    val BOMB_CHANCE: ForgeConfigSpec.IntValue
    val MISSILE_CHANCE: ForgeConfigSpec.IntValue
    val GUN_RUN_BOMB_CHANCE: ForgeConfigSpec.IntValue

    val LIMITED_AMMO: ForgeConfigSpec.BooleanValue
    val CANNON_ROUNDS: ForgeConfigSpec.IntValue
    val ROCKET_COUNT: ForgeConfigSpec.IntValue
    val BOMB_COUNT: ForgeConfigSpec.IntValue
    val MISSILE_COUNT: ForgeConfigSpec.IntValue
    val RESUPPLY_SECONDS: ForgeConfigSpec.IntValue

    val DEFENSIVE_FLARES: ForgeConfigSpec.BooleanValue
    val FLARE_MISS_CHANCE: ForgeConfigSpec.DoubleValue
    val TAUNT_ENABLED: ForgeConfigSpec.BooleanValue
    val IGNORE_BLOCK_COLLISIONS: ForgeConfigSpec.BooleanValue
    val BOSS_BAR_RANGE: ForgeConfigSpec.IntValue
    val XP_REWARD: ForgeConfigSpec.IntValue
    val BLOCK_DAMAGE: ForgeConfigSpec.BooleanValue
    val DEBUG_LOGGING: ForgeConfigSpec.BooleanValue

    /** Every option in file order (grouped by section); the in-game config screen is built from this. */
    val ALL: List<ForgeConfigSpec.ConfigValue<*>>

    init {
        val b = ForgeConfigSpec.Builder()

        b.comment("A-10 Warthog boss: The Plane That Burns the Sky").push("a10_boss")

        MAX_HEALTH = b
            .comment(
                "Boss health. The boss bar empties at 10% of this value, which is when the plane starts to crash.",
                "Applied when the boss is first created."
            )
            .defineInRange("maxHealth", 2000, 100, 1_000_000)

        CRUISE_HEIGHT = b
            .comment("Cruise altitude, in blocks above the target player.")
            .defineInRange("cruiseHeightAboveTarget", 40, 20, 400)

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

        RANDOM_TARGETING = b
            .comment(
                "Pick a random survival or adventure player as the target at the start of every attack instead of sticking",
                "to one (the nearest, or whoever last hurt it). The target is never swapped in the middle of a dive or bomb run.",
                "Read live."
            )
            .define("randomTargeting", true)

        ORBIT_RADIUS = b
            .comment("Radius (blocks) of the orbit flown during the missile orbit and while idling over its home.")
            .defineInRange("orbitRadius", 90, 40, 400)

        b.pop().push("damage")

        CANNON_DAMAGE_MULT = b
            .comment("Multiplier for GAU-8 cannon damage (direct and explosion). Applied when the boss is first created.")
            .defineInRange("cannonDamageMultiplier", 1.0, 0.0, 100.0)
        ROCKET_DAMAGE_MULT = b
            .comment("Multiplier for 70mm rocket damage.")
            .defineInRange("rocketDamageMultiplier", 1.0, 0.0, 100.0)
        BOMB_DAMAGE_MULT = b
            .comment("Multiplier for Mk 82 bomb damage.")
            .defineInRange("bombDamageMultiplier", 1.0, 0.0, 100.0)
        MISSILE_DAMAGE_MULT = b
            .comment("Multiplier for AGM-65 missile damage.")
            .defineInRange("missileDamageMultiplier", 1.0, 0.0, 100.0)

        BLOCK_DAMAGE = b
            .comment(
                "Whether the bombs, rockets, missiles, cannon shells and the final crash explosion break blocks.",
                "Applied when the boss is first created."
            )
            .define("blockDamage", true)

        b.pop().push("weapons")

        CANNON_ENABLED = b
            .comment(
                "Whether the boss may use the GAU-8 cannon (gun runs).",
                "Read live. If every weapon is disabled the boss still flies and circles its target but never attacks."
            )
            .define("cannonEnabled", true)
        ROCKETS_ENABLED = b
            .comment("Whether the boss may use 70mm rockets (rocket runs).")
            .define("rocketsEnabled", true)
        BOMBS_ENABLED = b
            .comment("Whether the boss may drop Mk 82 bombs (bomb runs).")
            .define("bombsEnabled", true)
        MISSILES_ENABLED = b
            .comment("Whether the boss may fire AGM-65 homing missiles (missile orbits).")
            .define("missilesEnabled", true)

        CANNON_CHANCE = b
            .comment(
                "Share (percent) of attacks that are GAU-8 gun runs. The four chances are relative weights: the boss rolls",
                "among the weapons that are enabled and have ammo, so they need not add up to 100. 0 = never chosen.",
                "Bombs are only used from the second phase on. Read live."
            )
            .defineInRange("cannonChance", 50, 0, 100)
        ROCKET_CHANCE = b
            .comment("Share (percent) of attacks that are 70mm rocket runs. See cannonChance. Read live.")
            .defineInRange("rocketChance", 20, 0, 100)
        BOMB_CHANCE = b
            .comment("Share (percent) of attacks that are Mk 82 bomb runs (second phase on). See cannonChance. Read live.")
            .defineInRange("bombChance", 20, 0, 100)
        MISSILE_CHANCE = b
            .comment("Share (percent) of attacks that are AGM-65 missile orbits. See cannonChance. Read live.")
            .defineInRange("missileChance", 10, 0, 100)

        GUN_RUN_BOMB_CHANCE = b
            .comment(
                "The gun run has two variants: a plain one, and a bombing one whose bombs are tossed on the pull-up (a",
                "separate bomb run follows only if that toss window is missed). This is the chance (percent) that a gun",
                "run is the bombing variant. 0 = never, 100 = always. Needs bombsEnabled and bombChance above 0 and bombs",
                "in stock, otherwise the plain variant is flown. Read live."
            )
            .defineInRange("gunRunBombChance", 50, 0, 100)

        b.pop().push("ammo")

        LIMITED_AMMO = b
            .comment(
                "Whether the boss has a finite load of ammunition. When the cannon, rockets, missiles and bombs it may use",
                "are all spent it flies far away to resupply, then comes back to hunt the player again.",
                "Read live. A full load is restored on every resupply."
            )
            .define("limitedAmmo", false)
        CANNON_ROUNDS = b
            .comment("Cannon rounds per load (a gun run burst is about 40-70 rounds).")
            .defineInRange("cannonRounds", 600, 1, 100_000)
        ROCKET_COUNT = b
            .comment("70mm rockets per load.")
            .defineInRange("rockets", 56, 1, 10_000)
        BOMB_COUNT = b
            .comment("Mk 82 bombs per load (a bomb run drops 3).")
            .defineInRange("bombs", 9, 1, 1_000)
        MISSILE_COUNT = b
            .comment("AGM-65 missiles per load.")
            .defineInRange("missiles", 8, 1, 1_000)
        RESUPPLY_SECONDS = b
            .comment("Seconds the boss spends at its resupply point before it returns (the flight there and back is extra).")
            .defineInRange("resupplySeconds", 40, 0, 3_600)

        b.pop().push("behaviour")

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
                "Whether the boss may taunt: a low flyby or a barrel roll near the player, dropping flares. Harmless.",
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
            .comment("Players within this many blocks of the boss see its boss bar.")
            .defineInRange("bossBarRange", 300, 32, 1024)

        XP_REWARD = b
            .comment("Experience dropped when the boss is defeated.")
            .defineInRange("xpReward", 500, 0, 100_000)

        DEBUG_LOGGING = b
            .comment("Log bomb-run solutions (predicted miss distance at release) to the server log.")
            .define("debugLogging", false)

        b.pop()
        SPEC = b.build()

        ALL = listOf(
            MAX_HEALTH, CRUISE_HEIGHT, AGGRO_RANGE, GLOBAL_AGGRO, RANDOM_TARGETING, ORBIT_RADIUS,
            CANNON_DAMAGE_MULT, ROCKET_DAMAGE_MULT, BOMB_DAMAGE_MULT, MISSILE_DAMAGE_MULT, BLOCK_DAMAGE,
            CANNON_ENABLED, ROCKETS_ENABLED, BOMBS_ENABLED, MISSILES_ENABLED,
            CANNON_CHANCE, ROCKET_CHANCE, BOMB_CHANCE, MISSILE_CHANCE, GUN_RUN_BOMB_CHANCE,
            LIMITED_AMMO, CANNON_ROUNDS, ROCKET_COUNT, BOMB_COUNT, MISSILE_COUNT, RESUPPLY_SECONDS,
            DEFENSIVE_FLARES, FLARE_MISS_CHANCE, TAUNT_ENABLED, IGNORE_BLOCK_COLLISIONS, BOSS_BAR_RANGE, XP_REWARD, DEBUG_LOGGING
        )
    }
}
