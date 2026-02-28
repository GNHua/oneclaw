package com.oneclaw.shadow.feature.memory.search

import kotlin.math.exp

/**
 * Time decay calculator for memory search scoring.
 */
object TimeDecayCalculator {
    /**
     * Calculate time decay factor.
     * Returns a value between 0 and 1, where 1 = today, decaying over time.
     *
     * Formula: decay = exp(-lambda * ageInDays)
     * With lambda = 0.01, half-life is ~69 days.
     */
    fun calculate(createdAtMillis: Long, lambda: Float = 0.01f): Float {
        val ageInDays = ((System.currentTimeMillis() - createdAtMillis) /
            (1000L * 60 * 60 * 24)).toFloat()
        return exp(-lambda * maxOf(ageInDays, 0f))
    }
}
