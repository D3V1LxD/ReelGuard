package com.reelguard.tracker.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class BrainHealthView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var damageRatio: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1.5f)
            invalidate()
        }

    private var phase: Float = 0f
    private val animator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
        duration = 1800L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    private val brainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val crackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#450A0A")
        strokeCap = Paint.Cap.ROUND
    }
    private val smokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    fun getInterpolatedStatusColor(): Int {
        val clamped = damageRatio.coerceIn(0f, 1f)
        return if (clamped < 0.5f) {
            val t = clamped / 0.5f
            interpolateColor(Color.parseColor("#10B981"), Color.parseColor("#F59E0B"), t)
        } else {
            val t = (clamped - 0.5f) / 0.5f
            interpolateColor(Color.parseColor("#F59E0B"), Color.parseColor("#EF4444"), t)
        }
    }

    private fun interpolateColor(c1: Int, c2: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val r = (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * f).toInt()
        val g = (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * f).toInt()
        val b = (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * f).toInt()
        return Color.rgb(r, g, b)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = min(w, h) * 0.36f

        val baseColor = getInterpolatedStatusColor()
        brainPaint.color = baseColor
        outlinePaint.color = Color.WHITE
        outlinePaint.strokeWidth = (radius * 0.10f).coerceAtLeast(2f)
        crackPaint.strokeWidth = (radius * 0.09f).coerceAtLeast(1.8f)

        val jitterX = if (damageRatio > 0.7f) (sin(phase * 7f) * radius * 0.08f * damageRatio) else 0f
        val jitterY = if (damageRatio > 0.7f) (cos(phase * 9f) * radius * 0.06f * damageRatio) else 0f

        val pulseFreq = if (damageRatio > 0.6f) 3f else 1f
        val scale = 1f + 0.05f * sin(phase * pulseFreq)

        canvas.save()
        canvas.translate(cx + jitterX, cy + jitterY)
        canvas.scale(scale, scale)

        if (damageRatio > 0.45f) {
            val smokeIntensity = ((damageRatio - 0.45f) / 0.55f).coerceIn(0f, 1f)
            for (i in 0..2) {
                val progress = ((phase / (Math.PI * 2).toFloat()) + i * 0.33f) % 1f
                val sx = (i - 1) * radius * 0.45f + sin(progress * 6f) * radius * 0.15f
                val sy = -radius * (0.8f + progress * 0.75f)
                val alpha = ((1f - progress) * 190 * smokeIntensity).toInt().coerceIn(0, 255)
                smokePaint.color = Color.argb(alpha, 239, 68, 68)
                canvas.drawCircle(sx, sy, radius * 0.22f * (0.6f + progress * 0.5f), smokePaint)
            }
        }

        val leftRect = RectF(-radius * 1.05f, -radius * 0.85f, -radius * 0.04f, radius * 0.85f)
        val rightRect = RectF(radius * 0.04f, -radius * 0.85f, radius * 1.05f, radius * 0.85f)
        canvas.drawRoundRect(leftRect, radius * 0.55f, radius * 0.55f, brainPaint)
        canvas.drawRoundRect(rightRect, radius * 0.55f, radius * 0.55f, brainPaint)

        canvas.drawArc(
            RectF(-radius * 0.8f, -radius * 0.5f, -radius * 0.2f, radius * 0.1f),
            30f, 190f, false, outlinePaint
        )
        canvas.drawArc(
            RectF(radius * 0.2f, -radius * 0.5f, radius * 0.8f, radius * 0.1f),
            -20f, 190f, false, outlinePaint
        )
        canvas.drawArc(
            RectF(-radius * 0.75f, 0f, -radius * 0.2f, radius * 0.55f),
            160f, 180f, false, outlinePaint
        )
        canvas.drawArc(
            RectF(radius * 0.2f, 0f, radius * 0.75f, radius * 0.55f),
            10f, 180f, false, outlinePaint
        )

        if (damageRatio > 0.35f) {
            val crackPath = Path().apply {
                moveTo(-radius * 0.65f, -radius * 0.65f)
                lineTo(-radius * 0.30f, -radius * 0.20f)
                lineTo(-radius * 0.55f, radius * 0.15f)
                if (damageRatio > 0.7f) {
                    lineTo(-radius * 0.20f, radius * 0.60f)
                    moveTo(radius * 0.60f, -radius * 0.55f)
                    lineTo(radius * 0.25f, -radius * 0.05f)
                    lineTo(radius * 0.55f, radius * 0.45f)
                }
            }
            canvas.drawPath(crackPath, crackPaint)
        }

        canvas.restore()
    }
}
