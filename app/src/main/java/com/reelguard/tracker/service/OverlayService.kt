package com.reelguard.tracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.reelguard.tracker.data.ReelDatabaseHelper
import com.reelguard.tracker.ui.AppIconView
import com.reelguard.tracker.ui.BrainHealthView
import com.reelguard.tracker.ui.DynamicLauncherIconManager
import com.reelguard.tracker.ui.MainActivity

/**
 * Foreground Service that displays the live Reel/Shorts counter pill at the
 * very top of the phone display, complete with the real-time active app icon,
 * Green -> Amber -> Red color interpolation, and the animated BrainHealthView.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_UPDATE_COUNTER = "com.reelguard.tracker.ACTION_UPDATE_COUNTER"
        private const val CHANNEL_ID = "reelguard_overlay_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private lateinit var dbHelper: ReelDatabaseHelper
    private var overlayContainer: LinearLayout? = null
    private var activeAppIconView: AppIconView? = null
    private var brainView: BrainHealthView? = null
    private var countTextView: TextView? = null
    private var statusLabelView: TextView? = null
    private var pillBackground: GradientDrawable? = null

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshOverlayData()
        }
    }

    override fun onCreate() {
        super.onCreate()
        dbHelper = ReelDatabaseHelper(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val filter = IntentFilter(ACTION_UPDATE_COUNTER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }

        if (Settings.canDrawOverlays(this)) {
            showTopOverlay()
            refreshOverlayData()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (overlayContainer == null && Settings.canDrawOverlays(this)) {
            showTopOverlay()
        }
        refreshOverlayData()
        return START_STICKY
    }

    private fun showTopOverlay() {
        val density = resources.displayMetrics.density

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (10 * density).toInt(),
                (6 * density).toInt(),
                (14 * density).toInt(),
                (6 * density).toInt()
            )
        }

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(Color.parseColor("#E6111827"))
            setStroke((1.5f * density).toInt(), Color.parseColor("#10B981"))
        }
        container.background = bg
        pillBackground = bg

        // Real-time Active App Icon (Instagram, TikTok, YouTube, Facebook, or Other)
        val appIcon = AppIconView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (22 * density).toInt(),
                (22 * density).toInt()
            ).apply {
                marginEnd = (6 * density).toInt()
            }
        }
        activeAppIconView = appIcon
        container.addView(appIcon)

        val brain = BrainHealthView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (26 * density).toInt(),
                (26 * density).toInt()
            ).apply {
                marginEnd = (8 * density).toInt()
            }
        }
        brainView = brain
        container.addView(brain)

        val countTv = TextView(this).apply {
            text = "0 Reels"
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        countTextView = countTv
        container.addView(countTv)

        val statusTv = TextView(this).apply {
            text = " • Sharp"
            setTextColor(Color.parseColor("#D1FAE5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        }
        statusLabelView = statusTv
        container.addView(statusTv)

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (12 * density).toInt()
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(container, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = kotlin.math.abs(event.rawX - initialTouchX)
                    val dy = kotlin.math.abs(event.rawY - initialTouchY)
                    if (dx < 10 && dy < 10) {
                        val openIntent = Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(openIntent)
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(container, layoutParams)
        overlayContainer = container
    }

    fun refreshOverlayData() {
        val todayCount = dbHelper.getTodayCount()
        val prefs = getSharedPreferences(ReelDatabaseHelper.PREFS_NAME, Context.MODE_PRIVATE)
        val dailyLimit = prefs.getInt(
            ReelDatabaseHelper.KEY_DAILY_LIMIT,
            ReelDatabaseHelper.DEFAULT_DAILY_LIMIT
        ).coerceAtLeast(1)
        val lastApp = prefs.getString(ReelDatabaseHelper.KEY_LAST_ACTIVE_APP, "Instagram") ?: "Instagram"

        activeAppIconView?.appCategory = lastApp

        val ratio = todayCount.toFloat() / dailyLimit.toFloat()
        brainView?.damageRatio = ratio
        val statusColor = brainView?.getInterpolatedStatusColor() ?: Color.parseColor("#10B981")

        // Also update the Home Screen App Launcher Icon in real time
        DynamicLauncherIconManager.updateLauncherIconForRatio(this, ratio)

        countTextView?.text = "$todayCount Reels"
        countTextView?.setTextColor(statusColor)

        val stageText = when {
            ratio < 0.25f -> " • Brain Sharp"
            ratio < 0.50f -> " • Mild Haze"
            ratio < 0.75f -> " • Dopamine Drain"
            ratio < 1.00f -> " • Brain Fog!"
            else -> " • BRAIN ROT!"
        }
        statusLabelView?.text = stageText
        statusLabelView?.setTextColor(statusColor)

        val density = resources.displayMetrics.density
        pillBackground?.setStroke((1.8f * density).toInt(), statusColor)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ReelGuard Top Display Counter",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the top-of-screen reel counter active"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ReelGuard Active")
            .setContentText("Counting Reels & Shorts at the top of your display")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(updateReceiver)
        } catch (_: Exception) {}
        overlayContainer?.let {
            windowManager.removeView(it)
            overlayContainer = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
