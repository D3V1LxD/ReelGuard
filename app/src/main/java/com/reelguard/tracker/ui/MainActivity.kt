package com.reelguard.tracker.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.reelguard.tracker.data.ReelDatabaseHelper
import com.reelguard.tracker.service.OverlayService

class MainActivity : AppCompatActivity() {

    private lateinit var dbHelper: ReelDatabaseHelper
    private lateinit var brainHeroView: BrainHealthView
    private lateinit var heroCountTv: TextView
    private lateinit var heroStatusTv: TextView
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
            text = "Real-Time App Icons, Top Display Counter & Brain Health"
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
        val overlayBtn = Button(this).apply {
            text = "1. Enable Top Overlay"
            setBackgroundColor(Color.parseColor("#1F2937"))
            setTextColor(Color.WHITE)
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
                    Toast.makeText(this@MainActivity, "Top Overlay Active!", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val accBtn = Button(this).apply {
            text = "2. Enable Reel Detector"
            setBackgroundColor(Color.parseColor("#1F2937"))
            setTextColor(Color.WHITE)
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
        }
        val testAddBtn = Button(this).apply {
            text = "+ Simulate Watching 5 Reels"
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
            setOnClickListener {
                val samplePackages = listOf(
                    "com.instagram.android",
                    "com.zhiliaoapp.musically",
                    "com.google.android.youtube",
                    "com.facebook.katana",
                    "com.snapchat.android"
                )
                for (pkg in samplePackages) {
                    dbHelper.recordReelView(this@MainActivity, pkg)
                }
                sendBroadcast(Intent(OverlayService.ACTION_UPDATE_COUNTER).apply {
                    setPackage(packageName)
                })
                refreshDashboard()
            }
        }
        heroCard.addView(brainHeroView)
        heroCard.addView(heroCountTv)
        heroCard.addView(heroStatusTv)
        heroCard.addView(testAddBtn)
        root.addView(heroCard)

        val summaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, dp(16))
        }
        daySummaryTv = createMetricCard(summaryRow, "TODAY", 1)
        weekSummaryTv = createMetricCard(summaryRow, "THIS WEEK", 7)
        monthSummaryTv = createMetricCard(summaryRow, "THIS MONTH", 30)
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
                    sendBroadcast(Intent(OverlayService.ACTION_UPDATE_COUNTER).apply {
                        setPackage(packageName)
                    })
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        root.addView(limitLabel)
        root.addView(limitSeek)

        val breakdownTitle = TextView(this).apply {
            text = "Breakdown by App (Real-Time Native Icons)"
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

        // Update Home Screen Launcher Icon in real time
        DynamicLauncherIconManager.updateLauncherIconForRatio(this, ratio)

        heroCountTv.text = "$todayCount Reels Today"
        heroCountTv.setTextColor(statusColor)

        val statusDesc = when {
            ratio < 0.25f -> "Brain Status: Healthy & Sharp (Green Icon Active)"
            ratio < 0.50f -> "Brain Status: Mild Attention Drift"
            ratio < 0.75f -> "Brain Status: Dopamine Overload (Amber Icon Active)"
            ratio < 1.00f -> "Brain Status: High Brain Fog Warning!"
            else -> "Brain Status: CRITICAL BRAIN ROT (Red Icon Active!)"
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
                appCategory = stat.appCategory
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    marginEnd = dp(10)
                }
            }
            val nameTv = TextView(this).apply {
                text = stat.appCategory
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
