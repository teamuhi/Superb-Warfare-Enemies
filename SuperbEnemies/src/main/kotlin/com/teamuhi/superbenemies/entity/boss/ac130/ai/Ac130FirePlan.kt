package com.teamuhi.superbenemies.entity.boss.ac130.ai

import com.teamuhi.superbenemies.entity.boss.ac130.Ac130Gun
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

/** Which fire pattern the gunship's guns follow while it orbits. */
enum class Ac130FireMode {
    /** All guns share the main target (the default). */
    NORMAL,

    /** Every gun shoots a different target (or a different spot around the main one) at the same time. */
    SPLIT,

    /** Every gun shoots the same fixed spot. */
    FOCUS
}

/**
 * What one gun aims at: [entity]'s centre plus [offset], or, with no entity, the fixed world point [offset].
 */
class GunAim(val entity: Entity?, val offset: Vec3)

/**
 * Per-gun aim orders that replace the shared target in [Ac130Gunnery.tick]. Guns without an entry do not fire.
 *
 * @property scatterScale multiplier for the 25mm cannon's random scatter (a focus barrage is tighter)
 */
class Ac130FirePlan(val perGun: Map<Ac130Gun, GunAim>, val scatterScale: Double = 1.0)
