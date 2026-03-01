package com.oneclaw.shadow.feature.memory

import com.oneclaw.shadow.feature.memory.search.TimeDecayCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.exp

class TimeDecayCalculatorTest {

    @Test
    fun `decay at time zero returns 1`() {
        val now = System.currentTimeMillis()
        val decay = TimeDecayCalculator.calculate(now)
        assertEquals(1f, decay, 0.01f)
    }

    @Test
    fun `decay decreases with age`() {
        val now = System.currentTimeMillis()
        val oneDay = 24 * 60 * 60 * 1000L
        val yesterday = now - oneDay
        val lastWeek = now - 7 * oneDay
        val lastMonth = now - 30 * oneDay

        val d0 = TimeDecayCalculator.calculate(now)
        val d1 = TimeDecayCalculator.calculate(yesterday)
        val d7 = TimeDecayCalculator.calculate(lastWeek)
        val d30 = TimeDecayCalculator.calculate(lastMonth)

        assertTrue(d0 > d1)
        assertTrue(d1 > d7)
        assertTrue(d7 > d30)
    }

    @Test
    fun `decay for future timestamp is clamped to 1`() {
        val future = System.currentTimeMillis() + 1000 * 60 * 60 * 24 * 10L // 10 days future
        val decay = TimeDecayCalculator.calculate(future)
        // maxOf(ageInDays, 0) clamps negative ages to 0
        assertEquals(1f, decay, 0.01f)
    }

    @Test
    fun `decay matches expected exponential formula`() {
        val now = System.currentTimeMillis()
        val tenDaysAgo = now - 10L * 24 * 60 * 60 * 1000
        val expected = exp(-0.01f * 10f)
        val actual = TimeDecayCalculator.calculate(tenDaysAgo)
        assertEquals(expected, actual, 0.001f)
    }

    @Test
    fun `decay is positive for all reasonable ages`() {
        val now = System.currentTimeMillis()
        val oneYear = 365L * 24 * 60 * 60 * 1000
        val decay = TimeDecayCalculator.calculate(now - oneYear)
        assertTrue(decay > 0f)
        assertTrue(decay < 1f)
    }
}
