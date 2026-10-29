package com.teamuhi.superbenemies.config

import net.minecraftforge.common.ForgeConfigSpec

/** How the player dummy moves (`player_dummy_movement.movement`). */
enum class DummyMovement {
    /** Stands still and only turns to face the nearest threat. */
    STATIC,

    /** Strafes side to side around its spawn point, with optional jumps, sprint bursts and crouching. */
    STRAFE,

    /** Walks to random nearby spots one after another, pausing at each, within a leash of its spawn point. */
    ROAM
}

/**
 * Common config of the player dummy, written to `config/superbenemies-dummy-common.toml`.
 *
 * Every option is read live (the dummy re-checks them while it ticks), so changing one affects dummies that are
 * already standing in the world. Section names carry a `player_dummy` prefix so the in-game config screen's header
 * keys stay unique across all the mod's files.
 */
object DummyConfig {
    val SPEC: ForgeConfigSpec

    val MAX_HEALTH: ForgeConfigSpec.IntValue
    val INVULNERABLE: ForgeConfigSpec.BooleanValue
    val SHOW_HEALTH_TAG: ForgeConfigSpec.BooleanValue
    val SHOW_DEATH_MESSAGE: ForgeConfigSpec.BooleanValue
    val SHOW_COUNTER: ForgeConfigSpec.BooleanValue
    val BOSSES_TARGET_DUMMY: ForgeConfigSpec.BooleanValue

    val MOVEMENT: ForgeConfigSpec.EnumValue<DummyMovement>
    val STRAFE_RADIUS: ForgeConfigSpec.IntValue
    val ROAM_DISTANCE: ForgeConfigSpec.IntValue
    val ROAM_PAUSE_SECONDS: ForgeConfigSpec.IntValue
    val STRAFE_JUMPING: ForgeConfigSpec.BooleanValue
    val STRAFE_SPRINTING: ForgeConfigSpec.BooleanValue
    val STRAFE_CROUCHING: ForgeConfigSpec.BooleanValue
    val HIDE_FROM_BOSSES: ForgeConfigSpec.BooleanValue

    /** Every option in file order (grouped by section); the in-game config screen is built from this. */
    val ALL: List<ForgeConfigSpec.ConfigValue<*>>

    init {
        val b = ForgeConfigSpec.Builder()

        b.comment("Player dummy: a player-looking test target for the bosses").push("player_dummy")

        MAX_HEALTH = b
            .comment(
                "Health of every player dummy. Applied to existing dummies too (health above the new maximum is clamped).",
                "Capped at 1024, the vanilla limit of the max health attribute.",
                "Read live."
            )
            .defineInRange("maxHealth", 20, 1, 1024)

        INVULNERABLE = b
            .comment(
                "Dummies take no damage. Hits still flash and make the hurt sound, and the health tag still shows them.",
                "/kill and the void still remove a dummy. Read live."
            )
            .define("invulnerable", false)

        SHOW_HEALTH_TAG = b
            .comment(
                "Show a name tag above the dummy with its health and the damage of the last hit it took,",
                "e.g. \"Player Dummy 18.5 / 20 (-4.5)\". Read live."
            )
            .define("showHealthTag", true)

        SHOW_DEATH_MESSAGE = b
            .comment(
                "Announce in chat when a dummy is killed, e.g. \"Player Dummy was shot by A-10 Warthog\". Read live."
            )
            .define("showDeathMessage", true)

        SHOW_COUNTER = b
            .comment("Show the number of loaded player dummies on the client hotbar. Read live.")
            .define("showCounter", false)

        BOSSES_TARGET_DUMMY = b
            .comment(
                "The bosses hunt dummies like survival players. With this off the A-10 ignores them and the AC-130 treats",
                "them as ordinary mobs. Read live."
            )
            .define("bossesTargetDummy", true)

        b.pop().push("player_dummy_movement")

        MOVEMENT = b
            .comment(
                "STATIC (stands still, only turns to face the nearest boss or player), STRAFE (side to side around the",
                "spawn point) or ROAM (walks to random nearby spots one after another). Read live."
            )
            .defineEnum("movement", DummyMovement.STRAFE)

        STRAFE_RADIUS = b
            .comment("STRAFE: how far (blocks) the dummy wanders from where it was spawned before it strafes back. Read live.")
            .defineInRange("strafeRadius", 6, 1, 32)

        ROAM_DISTANCE = b
            .comment(
                "ROAM: how far (blocks) each new spot may be from where the dummy stands. The dummy is also kept within",
                "48 blocks of its spawn point. Read live."
            )
            .defineInRange("roamDistance", 12, 4, 32)

        ROAM_PAUSE_SECONDS = b
            .comment("ROAM: at each spot the dummy pauses for a random time between 0 and this many seconds. Read live.")
            .defineInRange("roamPauseSeconds", 2, 0, 30)

        STRAFE_JUMPING = b
            .comment("STRAFE and ROAM: the dummy jumps now and then. Read live.")
            .define("jumping", true)

        STRAFE_SPRINTING = b
            .comment("STRAFE and ROAM: the dummy now and then sprints (STRAFE: a second or two, ROAM: some of the walks). Read live.")
            .define("sprinting", true)

        STRAFE_CROUCHING = b
            .comment("STRAFE: the dummy now and then crouches and moves slowly. Read live.")
            .define("crouching", true)

        HIDE_FROM_BOSSES = b
            .comment(
                "In any movement mode: when a boss is within 400 blocks the dummy runs for cover under a roof or trees",
                "(within 24 blocks), crouches there and keeps watching the boss. Without cover it keeps strafing.",
                "It walks back to its spawn point once no boss has been around for 5 seconds. Read live."
            )
            .define("hideFromBosses", false)

        b.pop()
        SPEC = b.build()

        ALL = listOf(
            MAX_HEALTH, INVULNERABLE, SHOW_HEALTH_TAG, SHOW_DEATH_MESSAGE, SHOW_COUNTER, BOSSES_TARGET_DUMMY,
            MOVEMENT, STRAFE_RADIUS, ROAM_DISTANCE, ROAM_PAUSE_SECONDS, STRAFE_JUMPING, STRAFE_SPRINTING, STRAFE_CROUCHING, HIDE_FROM_BOSSES
        )
    }
}
