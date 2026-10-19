package com.reelguard.tracker.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.reelguard.tracker.data.ReelDatabaseHelper
import com.reelguard.tracker.service.OverlayService
import com.reelguard.tracker.service.ReelAccessibilityService

class MainActivity : AppCompatActivity() {

    private lateinit var dbHelper: ReelDatabaseHelper
    private lateinit var brainHeroView: BrainHealthView
    private lateinit var heroCountTv: TextView
    private lateinit var heroStatusTv: TextView
    private lateinit var overlayBtn: Button
    private lateinit var accBtn: Button
    private lateinit var daySummaryTv: TextView
    private lateinit var weekSummaryTv: TextView
    private lateinit var monthSummaryTv: TextView
    private lateinit var appBreakdownContainer: LinearLayout
    private lateinit var seriesChartContainer: LinearLayout
    private var selectedRangeDays = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dbHelper = ReelDatabaseHelper(this)
        buildDashboardUi()
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
        if (Settings.canDrawOverlays(this)) {
            startTopOverlayService()
            // Keep overlay hidden while user is viewing the ReelGuard Dashboard
            sendBroadcast(Intent(OverlayService.ACTION_SET_OVERLAY_VISIBILITY).apply {
                setPackage(packageName)
                putExtra(OverlayService.EXTRA_IS_WATCHING_REELS, false)
            })
        }
    }

    private fun startTopOverlayService() {
        val serviceIntent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = ComponentName(this, ReelAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val componentStr = splitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentStr)
            if (enabledComponent != null && enabledComponent == expectedComponent) {
                return true
            }
        }
        return false
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildDashboardUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0F19"))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(36))
        }
        scroll.addView(root)

        val titleTv = TextView(this).apply {
            text = "ReelGuard Dashboard"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val subtitleTv = TextView(this).apply {
            text = "Live Reel & Shorts Tracker Across All Platforms"
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(16))
        }
        root.addView(titleTv)
        root.addView(subtitleTv)

        val permRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(16))
        }
        overlayBtn = Button(this).apply {
            text = "1. Top Overlay"
            setBackgroundColor(Color.parseColor("#1F2937"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    startTopOverlayService()
                    Toast.makeText(
                        this@MainActivity,
                        "Overlay ready! It will appear automatically when you watch Reels or Shorts.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        accBtn = Button(this).apply {
            text = "2. Reel Detector"
            setBackgroundColor(Color.parseColor("#1F2937"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        permRow.addView(overlayBtn)
        permRow.addView(accBtn)
        root.addView(permRow)

        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#111827"))
                setStroke(dp(1), Color.parseColor("#1F2937"))
            }
        }
        brainHeroView = BrainHealthView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96))
        }
        heroCountTv = TextView(this).apply {
            text = "0 Reels Today"
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(2))
        }
        heroStatusTv = TextView(this).apply {
            text = "Brain Status: Healthy & Sharp"
            setTextColor(Color.parseColor("#D1D5DB"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }
        val infoSubTv = TextView(this).apply {
            text = "Open Instagram, Facebook, YouTube Shorts, TikTok, or any short-video app — your reels are counted automatically as you swipe."
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), 0)
        }
        heroCard.addView(brainHeroView)
        heroCard.addView(heroCountTv)
        heroCard.addView(heroStatusTv)
        heroCard.addView(infoSubTv)
        root.addView(heroCard)

        val summaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, dp(16))
        }
        daySummaryTv = createMetricCard(summaryRow, "IN A DAY", 1)
        weekSummaryTv = createMetricCard(summaryRow, "IN A WEEK", 7)
        monthSummaryTv = createMetricCard(summaryRow, "IN A MONTH", 30)
        root.addView(summaryRow)

        val prefs = getSharedPreferences(ReelDatabaseHelper.PREFS_NAME, Context.MODE_PRIVATE)
        val currentLimit = prefs.getInt(
            ReelDatabaseHelper.KEY_DAILY_LIMIT,
            ReelDatabaseHelper.DEFAULT_DAILY_LIMIT
        )
        val limitLabel = TextView(this).apply {
            text = "Daily Red-Alert Threshold: $currentLimit Reels"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val limitSeek = SeekBar(this).apply {
            max = 200
            progress = currentLimit
            setPadding(0, dp(8), 0, dp(18))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val safeLimit = progress.coerceAtLeast(10)
                    limitLabel.text = "Daily Red-Alert Threshold: $safeLimit Reels"
                    prefs.edit().putInt(ReelDatabaseHelper.KEY_DAILY_LIMIT, safeLimit).apply()
                    refreshDashboard()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        root.addView(limitLabel)
        root.addView(limitSeek)

        val breakdownTitle = TextView(this).apply {
            text = "Actual Reels Watched by Platform"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(10))
        }
        appBreakdownContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(breakdownTitle)
        root.addView(appBreakdownContainer)

        val trendTitle = TextView(this).apply {
            text = "Daily Reel Trend"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(18), 0, dp(10))
        }
        seriesChartContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(trendTitle)
        root.addView(seriesChartContainer)

        setContentView(scroll)
    }

    private fun createMetricCard(parent: LinearLayout, label: String, days: Int): TextView {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#111827"))
                setStroke(dp(1), Color.parseColor("#374151"))
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = if (days != 30) dp(8) else 0
            }
            setOnClickListener {
                selectedRangeDays = days
                refreshDashboard()
            }
        }
        val title = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val valueTv = TextView(this).apply {
            text = "0"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(title)
        card.addView(valueTv)
        parent.addView(card)
        return valueTv
    }

    private fun refreshDashboard() {
        val overlayGranted = Settings.canDrawOverlays(this)
        val accessibilityGranted = isAccessibilityServiceEnabled()

        overlayBtn.text = if (overlayGranted) "Overlay: Active ✓" else "1. Enable Overlay"
        overlayBtn.setBackgroundColor(
            Color.parseColor(if (overlayGranted) "#065F46" else "#991B1B")
        )

        accBtn.text = if (accessibilityGranted) "Detector: Active ✓" else "2. Enable Detector"
        accBtn.setBackgroundColor(
            Color.parseColor(if (accessibilityGranted) "#065F46" else "#991B1B")
        )

        val todayCount = dbHelper.getTodayCount()
        val weekCount = dbHelper.getCountSinceDays(7)
        val monthCount = dbHelper.getCountSinceDays(30)

        daySummaryTv.text = todayCount.toString()
        weekSummaryTv.text = weekCount.toString()
        monthSummaryTv.text = monthCount.toString()

        val prefs = getSharedPreferences(ReelDatabaseHelper.PREFS_NAME, Context.MODE_PRIVATE)
        val limit = prefs.getInt(
            ReelDatabaseHelper.KEY_DAILY_LIMIT,
            ReelDatabaseHelper.DEFAULT_DAILY_LIMIT
        ).coerceAtLeast(1)

        val ratio = todayCount.toFloat() / limit.toFloat()
        brainHeroView.damageRatio = ratio
        val statusColor = brainHeroView.getInterpolatedStatusColor()

        DynamicLauncherIconManager.updateLauncherIconForRatio(this, ratio)

        heroCountTv.text = "$todayCount Reels Today"
        heroCountTv.setTextColor(statusColor)

        val statusDesc = when {
            ratio < 0.25f -> "Brain Status: Healthy & Sharp (Green Zone)"
            ratio < 0.50f -> "Brain Status: Mild Attention Drift"
            ratio < 0.75f -> "Brain Status: Dopamine Overload (Amber Zone)"
            ratio < 1.00f -> "Brain Status: High Brain Fog Warning!"
            else -> "Brain Status: CRITICAL BRAIN ROT (Limit Exceeded!)"
        }
        heroStatusTv.text = statusDesc
        heroStatusTv.setTextColor(statusColor)

        appBreakdownContainer.removeAllViews()
        val breakdown = dbHelper.getAppBreakdownSinceDays(selectedRangeDays)
        for (stat in breakdown) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.parseColor("#111827"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
            }
            val iconView = AppIconView(this).apply {
                packageHint = stat.packageName
                appCategory = stat.appName
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    marginEnd = dp(10)
                }
            }
            val nameTv = TextView(this).apply {
                text = stat.appName
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val countTv = TextView(this).apply {
                text = "${stat.count} reels (${String.format("%.0f", stat.percentage)}%)"
                setTextColor(Color.parseColor("#D1D5DB"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
            row.addView(iconView)
            row.addView(nameTv)
            row.addView(countTv)
            appBreakdownContainer.addView(row)
        }

        seriesChartContainer.removeAllViews()
        val seriesDays = if (selectedRangeDays == 30) 14 else 7
        val buckets = dbHelper.getDailySeries(seriesDays)
        for (bucket in buckets) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            val dayLbl = TextView(this).apply {
                text = bucket.label
                setTextColor(Color.parseColor("#9CA3AF"))
                layoutParams = LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val countLbl = TextView(this).apply {
                text = "${bucket.count} reels"
                setTextColor(Color.WHITE)
            }
            row.addView(dayLbl)
            row.addView(countLbl)
            seriesChartContainer.addView(row)
        }
    }
}
