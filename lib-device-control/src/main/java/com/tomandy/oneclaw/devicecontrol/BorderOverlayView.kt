package com.tomandy.oneclaw.devicecontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class BorderOverlayView(context: Context) : View(context) {

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6600")
        style = Paint.Style.STROKE
        strokeWidth = (5 * resources.displayMetrics.density)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val half = borderPaint.strokeWidth / 2f
        canvas.drawRect(half, half, width - half, height - half, borderPaint)
    }
}
