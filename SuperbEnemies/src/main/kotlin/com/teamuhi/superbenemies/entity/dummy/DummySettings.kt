package com.teamuhi.superbenemies.entity.dummy

import com.teamuhi.superbenemies.config.DummyConfig
import com.teamuhi.superbenemies.config.DummyMovement

/**
 * The tunables every [HumanoidDummyEntity] and its goals read, so one body and one set of movement goals serve both the
 * player dummy and the dummy soldier, each with its own config file. Every getter reads the config live (nothing is
 * cached), so editing an option affects entities that are already standing in the world.
 */
interface DummySettings {
    fun maxHealth(): Int
    fun armor(): Double
    fun armorToughness(): Double
    fun invulnerable(): Boolean
    fun showHealthTag(): Boolean
    fun showDeathMessage(): Boolean

    /** Whether the bosses hunt this kind of dummy like a survival player. */
    fun targetedByBosses(): Boolean

    fun movement(): DummyMovement
    fun strafeRadius(): Int
    fun roamDistance(): Int
    fun roamPauseSeconds(): Int
    fun jumping(): Boolean
    fun sprinting(): Boolean
    fun crouching(): Boolean
}

/** The player dummy's settings: [DummyConfig]. It has no armor, so it takes damage exactly as it always did. */
object PlayerDummySettings : DummySettings {
    override fun maxHealth(): Int = DummyConfig.MAX_HEALTH.get()
    override fun armor(): Double = 0.0
    override fun armorToughness(): Double = 0.0
    override fun invulnerable(): Boolean = DummyConfig.INVULNERABLE.get()
    override fun showHealthTag(): Boolean = DummyConfig.SHOW_HEALTH_TAG.get()
    override fun showDeathMessage(): Boolean = DummyConfig.SHOW_DEATH_MESSAGE.get()
    override fun targetedByBosses(): Boolean = DummyConfig.BOSSES_TARGET_DUMMY.get()
    override fun movement(): DummyMovement = DummyConfig.MOVEMENT.get()
    override fun strafeRadius(): Int = DummyConfig.STRAFE_RADIUS.get()
    override fun roamDistance(): Int = DummyConfig.ROAM_DISTANCE.get()
    override fun roamPauseSeconds(): Int = DummyConfig.ROAM_PAUSE_SECONDS.get()
    override fun jumping(): Boolean = DummyConfig.STRAFE_JUMPING.get()
    override fun sprinting(): Boolean = DummyConfig.STRAFE_SPRINTING.get()
    override fun crouching(): Boolean = DummyConfig.STRAFE_CROUCHING.get()
}
