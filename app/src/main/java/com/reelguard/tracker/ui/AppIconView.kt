package com.reelguard.tracker.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View

/**
 * Renders the real-time native app icon from Android's PackageManager if installed,
 * or draws crisp vector brand geometry for Instagram, TikTok, YouTube Shorts,
 * Facebook, and Other Apps (zero emojis used).
 */
class AppIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var appCategory: String = "Instagram"
        set(value) {
            field = value
            nativeIcon = resolveNativeAppIcon(value)
            invalidate()
        }

    private var nativeIcon: Drawable? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        nativeIcon = resolveNativeAppIcon(appCategory)
    }

    private fun resolveNativeAppIcon(categoryOrPkg: String): Drawable? {
        val candidatePackages = when (categoryOrPkg) {
            "Instagram" -> listOf("com.instagram.android", "com.instagram.lite")
            "TikTok" -> listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
            "YouTube" -> listOf("com.google.android.youtube")
            "Facebook" -> listOf("com.facebook.katana", "com.facebook.lite")
            else -> listOf(categoryOrPkg)
        }
        val pm = context.packageManager
        for (pkg in candidatePackages) {
            try {
                return pm.getApplicationIcon(pkg)
            } catch (_: Exception) {}
        }
        return null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = nativeIcon
        if (d != null) {
            d.setBounds(0, 0, width, height)
            d.draw(canvas)
            return
        }

        val w = width.toFloat()
        val h = height.toFloat()
        val rect = RectF(0f, 0f, w, h)
        val radius = w * 0.26f
        strokePaint.strokeWidth = (w * 0.08f).coerceAtLeast(2f)

        when (appCategory) {
            "Instagram" -> {
                paint.shader = LinearGradient(
                    0f, h, w, 0f,
                    intArrayOf(
                        Color.parseColor("#F58529"),
                        Color.parseColor("#DD2A7B"),
                        Color.parseColor("#8134AF")
                    ),
                    null,
                    Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(rect, radius, radius, paint)
                paint.shader = null
                val camRect = RectF(w * 0.22f, h * 0.22f, w * 0.78f, h * 0.78f)
                canvas.drawRoundRect(camRect, w * 0.16f, w * 0.16f, strokePaint)
                canvas.drawCircle(w * 0.5f, h * 0.5f, w * 0.13f, strokePaint)
                paint.color = Color.WHITE
                canvas.drawCircle(w * 0.66f, h * 0.34f, w * 0.04f, paint)
            }
            "TikTok" -> {
                paint.shader = null
                paint.color = Color.parseColor("#0F172A")
                canvas.drawRoundRect(rect, radius, radius, paint)
                val notePath = Path().apply {
                    moveTo(w * 0.56f, h * 0.24f)
                    lineTo(w * 0.56f, h * 0.62f)
                    quadTo(w * 0.56f, h * 0.76f, w * 0.42f, h * 0.76f)
                    quadTo(w * 0.28f, h * 0.76f, w * 0.28f, h * 0.62f)
                    quadTo(w * 0.28f, h * 0.48f, w * 0.44f, h * 0.50f)
                    moveTo(w * 0.56f, h * 0.24f)
                    quadTo(w * 0.68f, h * 0.38f, w * 0.76f, h * 0.38f)
                }
                strokePaint.color = Color.parseColor("#25F4EE")
                canvas.save()
                canvas.translate(-w * 0.03f, -h * 0.02f)
                canvas.drawPath(notePath, strokePaint)
                canvas.restore()

                strokePaint.color = Color.parseColor("#FE2C55")
                canvas.save()
                canvas.translate(w * 0.03f, h * 0.02f)
                canvas.drawPath(notePath, strokePaint)
                canvas.restore()

                strokePaint.color = Color.WHITE
                canvas.drawPath(notePath, strokePaint)
            }
            "YouTube" -> {
                paint.shader = null
                paint.color = Color.parseColor("#FF0000")
                canvas.drawRoundRect(rect, radius, radius, paint)
                val playPath = Path().apply {
                    moveTo(w * 0.38f, h * 0.28f)
                    lineTo(w * 0.70f, h * 0.50f)
                    lineTo(w * 0.38f, h * 0.72f)
                    close()
                }
                paint.color = Color.WHITE
                canvas.drawPath(playPath, paint)
            }
            "Facebook" -> {
                paint.shader = null
                paint.color = Color.parseColor("#1877F2")
                canvas.drawRoundRect(rect, radius, radius, paint)
                val fPath = Path().apply {
                    moveTo(w * 0.64f, h * 0.24f)
                    lineTo(w * 0.54f, h * 0.24f)
                    quadTo(w * 0.44f, h * 0.24f, w * 0.44f, h * 0.36f)
                    lineTo(w * 0.44f, h * 0.80f)
                    moveTo(w * 0.33f, h * 0.48f)
                    lineTo(w * 0.62f, h * 0.48f)
                }
                strokePaint.color = Color.WHITE
                canvas.drawPath(fPath, strokePaint)
            }
            else -> {
                paint.shader = null
                paint.color = Color.parseColor("#6366F1")
                canvas.drawRoundRect(rect, radius, radius, paint)
                strokePaint.color = Color.WHITE
                canvas.drawRoundRect(
                    RectF(w * 0.26f, h * 0.22f, w * 0.74f, h * 0.78f),
                    w * 0.08f, w * 0.08f, strokePaint
                )
            }
        }
    }
}
