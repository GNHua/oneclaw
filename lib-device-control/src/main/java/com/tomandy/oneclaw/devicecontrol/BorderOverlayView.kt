package com.tomandy.oneclaw.devicecontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

class BorderOverlayView(context: Context) : View(context) {

    private val glowWidth = 12 * resources.displayMetrics.density
    private val edgeColor = Color.parseColor("#FF6600")
    private val transparent = Color.TRANSPARENT
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

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
    }
}
