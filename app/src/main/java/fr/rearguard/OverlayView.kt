package fr.rearguard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Dessine les cadres des véhicules détectés par-dessus l'aperçu. */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var result: FrameResult? = null
    private var level = AlertLevel.NONE

    private val boxPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true }
    private val textPaint = Paint().apply { color = Color.WHITE; textSize = 32f; isAntiAlias = true }
    private val zonePaint = Paint().apply { color = Color.argb(60, 255, 255, 255); strokeWidth = 2f }
    var showCenterZone = true

    fun update(r: FrameResult?, l: AlertLevel) {
        result = r; level = l
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (showCenterZone) {
            canvas.drawLine(w * 0.2f, 0f, w * 0.2f, h, zonePaint)
            canvas.drawLine(w * 0.8f, 0f, w * 0.8f, h, zonePaint)
        }
        boxPaint.color = when (level) {
            AlertLevel.DANGER -> Color.RED
            AlertLevel.ATTENTION -> Color.rgb(255, 179, 0)
            AlertLevel.NONE -> Color.GREEN
        }
        result?.vehicles?.forEach { v ->
            val l = v.box.left * w; val t = v.box.top * h
            canvas.drawRect(l, t, v.box.right * w, v.box.bottom * h, boxPaint)
            canvas.drawText("${v.label} ${(v.score * 100).toInt()}%", l + 6, t + 34, textPaint)
        }
    }
}
