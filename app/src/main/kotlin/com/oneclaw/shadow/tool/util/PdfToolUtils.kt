package com.oneclaw.shadow.tool.util

import android.content.Context
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * Located in: tool/util/PdfToolUtils.kt
 *
 * Shared utilities for PDF tools: PDFBox initialization
 * and page range parsing.
 */
object PdfToolUtils {

    private const val TAG = "PdfToolUtils"
    private var initialized = false

    /**
     * Initialize PDFBox resource loader. Must be called once
     * before any PDFBox operations. Safe to call multiple times.
     */
    fun initPdfBox(context: Context) {
        if (!initialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
            Log.i(TAG, "PDFBox initialized")
        }
    }

    /**
     * Parse a page range specification string.
     *
     * Supported formats:
     * - Single page: "3"
     * - Range: "1-5"
     * - Comma-separated: "1,3,5-7"
     *
     * @param spec Page range specification string
     * @param totalPages Total number of pages in the document
     * @return Pair of (startPage, endPage) 1-based inclusive, or null if invalid
     */
    fun parsePageRange(spec: String, totalPages: Int): Pair<Int, Int>? {
        val trimmed = spec.trim()

        // Comma-separated: find overall min and max
        if (trimmed.contains(",")) {
            val parts = trimmed.split(",").map { it.trim() }
            var min = Int.MAX_VALUE
            var max = Int.MIN_VALUE
            for (part in parts) {
                val range = parsePageRange(part, totalPages) ?: return null
                min = minOf(min, range.first)
                max = maxOf(max, range.second)
            }
            return Pair(min, max)
        }

        // Range: "start-end"
        if (trimmed.contains("-")) {
            val parts = trimmed.split("-", limit = 2)
            val start = parts[0].trim().toIntOrNull() ?: return null
            val end = parts[1].trim().toIntOrNull() ?: return null
            if (start < 1 || end > totalPages || start > end) return null
            return Pair(start, end)
        }

        // Single page
        val page = trimmed.toIntOrNull() ?: return null
        if (page < 1 || page > totalPages) return null
        return Pair(page, page)
    }
}
