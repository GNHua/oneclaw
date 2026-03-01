package com.oneclaw.shadow.tool.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PdfToolUtilsTest {

    @Test
    fun testParsePageRange_singlePage() {
        val result = PdfToolUtils.parsePageRange("3", 10)
        assertEquals(Pair(3, 3), result)
    }

    @Test
    fun testParsePageRange_range() {
        val result = PdfToolUtils.parsePageRange("1-5", 10)
        assertEquals(Pair(1, 5), result)
    }

    @Test
    fun testParsePageRange_commaSeparated() {
        val result = PdfToolUtils.parsePageRange("1,3,5-7", 10)
        assertEquals(Pair(1, 7), result)
    }

    @Test
    fun testParsePageRange_rangeWithSpaces() {
        val result = PdfToolUtils.parsePageRange("1 - 5", 10)
        assertEquals(Pair(1, 5), result)
    }

    @Test
    fun testParsePageRange_invalidRange() {
        val result = PdfToolUtils.parsePageRange("5-2", 10)
        assertNull(result)
    }

    @Test
    fun testParsePageRange_outOfBoundsLower() {
        val result = PdfToolUtils.parsePageRange("0", 10)
        assertNull(result)
    }

    @Test
    fun testParsePageRange_outOfBoundsUpper() {
        val result = PdfToolUtils.parsePageRange("11", 10)
        assertNull(result)
    }

    @Test
    fun testParsePageRange_nonNumeric() {
        val result = PdfToolUtils.parsePageRange("abc", 10)
        assertNull(result)
    }

    @Test
    fun testParsePageRange_singlePageRange() {
        val result = PdfToolUtils.parsePageRange("1-1", 10)
        assertEquals(Pair(1, 1), result)
    }

    @Test
    fun testParsePageRange_firstPage() {
        val result = PdfToolUtils.parsePageRange("1", 10)
        assertEquals(Pair(1, 1), result)
    }

    @Test
    fun testParsePageRange_lastPage() {
        val result = PdfToolUtils.parsePageRange("10", 10)
        assertEquals(Pair(10, 10), result)
    }

    @Test
    fun testParsePageRange_fullRange() {
        val result = PdfToolUtils.parsePageRange("1-10", 10)
        assertEquals(Pair(1, 10), result)
    }

    @Test
    fun testParsePageRange_rangeExceedsTotalPages() {
        val result = PdfToolUtils.parsePageRange("1-15", 10)
        assertNull(result)
    }

    @Test
    fun testParsePageRange_commaWithSpaces() {
        val result = PdfToolUtils.parsePageRange("1 , 3 , 5", 10)
        assertEquals(Pair(1, 5), result)
    }

    @Test
    fun testParsePageRange_commaMin() {
        // "3,1,5" should return (1, 5) -- overall min and max
        val result = PdfToolUtils.parsePageRange("3,1,5", 10)
        assertEquals(Pair(1, 5), result)
    }

    @Test
    fun testParsePageRange_singlePageDocument() {
        val result = PdfToolUtils.parsePageRange("1", 1)
        assertEquals(Pair(1, 1), result)
    }

    @Test
    fun testParsePageRange_leadingTrailingWhitespace() {
        val result = PdfToolUtils.parsePageRange("  3  ", 10)
        assertEquals(Pair(3, 3), result)
    }
}
