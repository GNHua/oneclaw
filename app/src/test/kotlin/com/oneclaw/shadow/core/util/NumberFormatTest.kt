package com.oneclaw.shadow.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NumberFormatTest {

    @Test
    fun `formatWithCommas formats large long with commas`() {
        assertEquals("1,234,567", formatWithCommas(1_234_567L))
    }

    @Test
    fun `formatWithCommas returns 0 for zero long`() {
        assertEquals("0", formatWithCommas(0L))
    }

    @Test
    fun `formatWithCommas returns value under 1000 as-is long`() {
        assertEquals("999", formatWithCommas(999L))
    }

    @Test
    fun `formatWithCommas formats int with commas`() {
        assertEquals("1,234", formatWithCommas(1_234))
    }

    @Test
    fun `formatWithCommas returns 0 for zero int`() {
        assertEquals("0", formatWithCommas(0))
    }

    @Test
    fun `formatWithCommas returns small int as-is`() {
        assertEquals("999", formatWithCommas(999))
    }

    @Test
    fun `abbreviateNumber returns K abbreviation for thousands`() {
        assertEquals("1.2K", abbreviateNumber(1_234L))
    }

    @Test
    fun `abbreviateNumber returns M abbreviation for millions`() {
        assertEquals("1.2M", abbreviateNumber(1_234_567L))
    }

    @Test
    fun `abbreviateNumber returns small number as-is`() {
        assertEquals("500", abbreviateNumber(500L))
    }

    @Test
    fun `abbreviateNumber returns exactly 1000 as 1_0K`() {
        assertEquals("1.0K", abbreviateNumber(1_000L))
    }

    @Test
    fun `abbreviateNumber returns exactly 1 million as 1_0M`() {
        assertEquals("1.0M", abbreviateNumber(1_000_000L))
    }
}
