package com.tomandy.oneclaw.devicecontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View

class BorderOverlayView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val glowWidth = 12 * density
    private val edgeColor = Color.parseColor("#FF6600")
    private val transparent = Color.TRANSPARENT
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val labelText = "Vol Down \u00D72 to stop"
    private val labelTextSize = 11 * density
    private val labelPaddingH = 10 * density
    private val labelPaddingV = 5 * density
    private val labelRadius = 12 * density
    private val labelMarginTop = 36 * density

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC000000")
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = labelTextSize
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val g = glowWidth

        // Top edge
        paint.shader = LinearGradient(0f, 0f, 0f, g, edgeColor, transparent, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, g, paint)

        // Bottom edge
        paint.shader = LinearGradient(0f, h, 0f, h - g, edgeColor, transparent, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, h - g, w, h, paint)

        // Left edge
        paint.shader = LinearGradient(0f, 0f, g, 0f, edgeColor, transparent, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, g, h, paint)

        // Right edge
        paint.shader = LinearGradient(w, 0f, w - g, 0f, edgeColor, transparent, Shader.TileMode.CLAMP)
        canvas.drawRect(w - g, 0f, w, h, paint)
        paint.shader = null

        // Abort label
        val textWidth = labelTextPaint.measureText(labelText)
        val bgWidth = textWidth + labelPaddingH * 2
        val bgHeight = labelTextSize + labelPaddingV * 2
        val bgLeft = (w - bgWidth) / 2f
        val bgTop = labelMarginTop
        val bgRect = RectF(bgLeft, bgTop, bgLeft + bgWidth, bgTop + bgHeight)
        canvas.drawRoundRect(bgRect, labelRadius, labelRadius, labelBgPaint)

        val textY = bgTop + labelPaddingV - labelTextPaint.ascent()
        canvas.drawText(labelText, w / 2f, textY, labelTextPaint)
    }
}
