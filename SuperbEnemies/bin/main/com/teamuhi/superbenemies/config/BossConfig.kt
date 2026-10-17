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

    val GUN_RUN_CHANCE: ForgeConfigSpec.IntValue
    val GUN_RUN_BOMB_CHANCE: ForgeConfigSpec.IntValue
    val ROCKET_RUN_CHANCE: ForgeConfigSpec.IntValue
    val BOMB_RUN_CHANCE: ForgeConfigSpec.IntValue
    val MISSILE_ORBIT_CHANCE: ForgeConfigSpec.IntValue
    val ROCKET_MISSILE_RUN_CHANCE: ForgeConfigSpec.IntValue
    val DOUBLE_GUN_RUN_CHANCE: ForgeConfigSpec.IntValue

    val LIMITED_AMMO: ForgeConfigSpec.BooleanValue
    val CANNON_ROUNDS: ForgeConfigSpec.IntValue
    val ROCKET_COUNT: ForgeConfigSpec.IntValue
    val BOMB_COUNT: ForgeConfigSpec.IntValue
    val MISSILE_COUNT: ForgeConfigSpec.IntValue
    val RESUPPLY_SECONDS: ForgeConfigSpec.IntValue

    val DEFENSIVE_FLARES: ForgeConfigSpec.BooleanValue
    val FLARE_MISS_CHANCE: ForgeConfigSpec.IntValue
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
            .comment("Boss health. The boss bar empties at 10% of this, when the plane starts to crash. Applied when the boss is first created.")
            .defineInRange("maxHealth", 2000, 100, 1_000_000)

        CRUISE_HEIGHT = b
            .comment("Cruise altitude in blocks above the target player.")
            .defineInRange("cruiseHeightAboveTarget", 40, 20, 400)

        AGGRO_RANGE = b
            .comment("Range (blocks) in which the boss picks a player as target. Unused if globalAggro is on.")
            .defineInRange("aggroRange", 256, 32, 1024)

        GLOBAL_AGGRO = b
            .comment("Hunt the nearest player anywhere in the dimension and show the boss bar to everyone.")
            .define("globalAggro", true)

        RANDOM_TARGETING = b
            .comment("Pick a random player as target at the start of every attack. Never swapped mid-dive or bomb run.")
            .define("randomTargeting", true)

        ORBIT_RADIUS = b
            .comment("Radius (blocks) of the missile orbit and of idling over its home.")
            .defineInRange("orbitRadius", 90, 40, 400)

        b.pop().push("damage")

        CANNON_DAMAGE_MULT = b
            .comment("Damage multiplier for the GAU-8 cannon. Applied when the boss is first created.")
            .defineInRange("cannonDamageMultiplier", 1.0, 0.0, 100.0)
        ROCKET_DAMAGE_MULT = b
            .comment("Damage multiplier for 70mm rockets.")
            .defineInRange("rocketDamageMultiplier", 1.0, 0.0, 100.0)
        BOMB_DAMAGE_MULT = b
            .comment("Damage multiplier for Mk 82 bombs.")
            .defineInRange("bombDamageMultiplier", 1.0, 0.0, 100.0)
        MISSILE_DAMAGE_MULT = b
            .comment("Damage multiplier for AGM-65 missiles.")
            .defineInRange("missileDamageMultiplier", 1.0, 0.0, 100.0)

        BLOCK_DAMAGE = b
            .comment("Whether weapons and the crash explosion break blocks. Applied when the boss is first created.")
            .define("blockDamage", true)

        b.pop().push("weapons")

        CANNON_ENABLED = b
            .comment("Whether the boss may use the GAU-8 cannon. Off removes both gun runs. With every weapon off it only flies and circles.")
            .define("cannonEnabled", true)
        ROCKETS_ENABLED = b
            .comment("Whether the boss may use 70mm rockets. Off removes the rocket run.")
            .define("rocketsEnabled", true)
        BOMBS_ENABLED = b
            .comment("Whether the boss may drop Mk 82 bombs. Off removes the bomb run and the gun run with bombs.")
            .define("bombsEnabled", true)
        MISSILES_ENABLED = b
            .comment("Whether the boss may fire AGM-65 missiles. Off removes the missile orbit.")
            .define("missilesEnabled", true)

        GUN_RUN_CHANCE = b
            .comment(
                "Weight (percent) of plain gun runs. Attack chances are relative weights among attacks whose weapons",
                "are enabled and have ammo, so they need not add up to 100. 0 = never. Needs cannonEnabled."
            )
            .defineInRange("gunRunChance", 25, 0, 100)
        GUN_RUN_BOMB_CHANCE = b
            .comment("Weight (percent) of gun runs with bombs: strafe, then drop bombs up close. Needs cannonEnabled and bombsEnabled.")
            .defineInRange("gunRunWithBombsChance", 25, 0, 100)
        ROCKET_RUN_CHANCE = b
            .comment("Weight (percent) of 70mm rocket runs. Needs rocketsEnabled.")
            .defineInRange("rocketRunChance", 20, 0, 100)
        BOMB_RUN_CHANCE = b
            .comment("Weight (percent) of straight Mk 82 bomb runs (second phase on). Needs bombsEnabled.")
            .defineInRange("bombRunChance", 20, 0, 100)
        MISSILE_ORBIT_CHANCE = b
            .comment("Weight (percent) of AGM-65 missile orbits. Needs missilesEnabled.")
            .defineInRange("missileOrbitChance", 10, 0, 100)
        ROCKET_MISSILE_RUN_CHANCE = b
            .comment("Weight (percent) of rocket runs that also launch missiles. Needs rocketsEnabled and missilesEnabled.")
            .defineInRange("rocketMissileRunChance", 10, 0, 100)
        DOUBLE_GUN_RUN_CHANCE = b
            .comment("Weight (percent) of double gun runs: a gun run, a barrel-roll U-turn, then a second run. Needs cannonEnabled.")
            .defineInRange("doubleGunRunChance", 10, 0, 100)

        b.pop().push("ammo")

        LIMITED_AMMO = b
            .comment("Finite ammo. When spent, the boss flies away to resupply, then returns with a full load.")
            .define("limitedAmmo", false)
        CANNON_ROUNDS = b
            .comment("Cannon rounds per load (a gun run burst is about 40-70).")
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
            .comment("Seconds spent resupplying (the flight there and back is extra).")
            .defineInRange("resupplySeconds", 40, 0, 3_600)

        b.pop().push("behaviour")

        DEFENSIVE_FLARES = b
            .comment("Whether the boss releases flares against homing missiles. Off by default so missiles can hit.")
            .define("defensiveFlares", false)

        FLARE_MISS_CHANCE = b
            .comment("With defensiveFlares: chance (percent) of missing an incoming missile and releasing no flares.")
            .defineInRange("flareMissChance", 10, 0, 100)

        TAUNT_ENABLED = b
            .comment("Whether the boss may taunt: a low flyby or barrel roll near the player, dropping flares. Harmless.")
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
            GUN_RUN_CHANCE, GUN_RUN_BOMB_CHANCE, ROCKET_RUN_CHANCE, BOMB_RUN_CHANCE, MISSILE_ORBIT_CHANCE,
            ROCKET_MISSILE_RUN_CHANCE, DOUBLE_GUN_RUN_CHANCE,
            LIMITED_AMMO, CANNON_ROUNDS, ROCKET_COUNT, BOMB_COUNT, MISSILE_COUNT, RESUPPLY_SECONDS,
            DEFENSIVE_FLARES, FLARE_MISS_CHANCE, TAUNT_ENABLED, IGNORE_BLOCK_COLLISIONS, BOSS_BAR_RANGE, XP_REWARD, DEBUG_LOGGING
        )
    }
}
