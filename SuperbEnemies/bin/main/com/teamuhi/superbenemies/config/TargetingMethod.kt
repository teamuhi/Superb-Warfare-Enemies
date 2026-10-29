package com.teamuhi.superbenemies.config

/** How well a boss keeps track of its target (`a10_boss.targetingMethod`, `ac130_boss.targetingMethod`). */
enum class TargetingMethod {
    /** The boss always knows exactly where its target is, wherever it hides. */
    STATIC,

    /**
     * A target with a cover block above it is hidden, and the boss attacks the last position it saw it at. Cover is any
     * block with collision that is not in the `superbenemies:boss_see_through` block tag (glass, leaves, fences and
     * carpets are in it); water, air and plants have no collision, so they never count. A target that is exposed to the
     * sky again is tracked live again.
     */
    DYNAMIC
}
