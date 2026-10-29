package com.teamuhi.superbenemies.entity.boss.common.ai

import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity

/**
 * Stateless helpers that turn target weights (see `TargetWeights`) into a choice. A weight of 0 or less always means
 * "never", so such candidates are skipped by every function here.
 */
object TargetChoice {

    /** Largest score, returned for a weight that rules a target out. */
    private const val NEVER = Double.MAX_VALUE

    /** A random candidate whose chance is proportional to its weight, or null when no candidate has a positive weight. */
    fun <T : Entity> weightedRandom(candidates: List<T>, weightOf: (T) -> Int, random: RandomSource): T? {
        var total = 0
        for (candidate in candidates) total += weightOf(candidate).coerceAtLeast(0)
        if (total <= 0) return null
        var roll = random.nextInt(total)
        for (candidate in candidates) {
            roll -= weightOf(candidate).coerceAtLeast(0)
            if (roll < 0) return candidate
        }
        return null
    }

    /** `distance / weight`: lower is better, so a weight-5 target counts as five times closer than a weight-1 one. */
    fun score(distance: Double, weight: Int): Double = if (weight <= 0) NEVER else distance / weight

    /** The candidate with the lowest [score], ignoring weights of 0 or less; null when none qualifies. */
    fun <T : Entity> bestScore(candidates: List<T>, weightOf: (T) -> Int, distanceOf: (T) -> Double): T? {
        var best: T? = null
        var bestScore = NEVER
        for (candidate in candidates) {
            val weight = weightOf(candidate)
            if (weight <= 0) continue
            val score = score(distanceOf(candidate), weight)
            if (best == null || score < bestScore) {
                best = candidate
                bestScore = score
            }
        }
        return best
    }
}
