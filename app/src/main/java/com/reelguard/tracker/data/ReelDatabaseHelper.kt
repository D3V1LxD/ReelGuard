package com.reelguard.tracker.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class AppUsageStat(
    val appName: String,
    val packageName: String,
    val count: Int,
    val percentage: Float
)

data class DailyBucket(
    val label: String,
    val count: Int
)

class ReelDatabaseHelper(private val appContext: Context) :
    SQLiteOpenHelper(appContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "reelguard.db"
        private const val DATABASE_VERSION = 2
        const val TABLE_REELS = "reel_views"
        const val COL_ID = "id"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_DATE_STR = "date_str"
        const val COL_PACKAGE = "package_name"
        const val COL_CATEGORY = "app_category"

        const val PREFS_NAME = "reelguard_prefs"
        const val KEY_DAILY_LIMIT = "daily_limit"
        const val KEY_LAST_ACTIVE_APP = "last_active_app"
        const val KEY_LAST_ACTIVE_PKG = "last_active_pkg"
        const val DEFAULT_DAILY_LIMIT = 50

        fun resolvePlatformName(context: Context, pkg: String): String {
            val lower = pkg.lowercase(Locale.ROOT)
            when {
                lower.contains("instagram") -> return "Instagram"
                lower.contains("facebook.katana") || lower.contains("facebook.lite") || lower.contains("facebook") -> return "Facebook"
                lower.contains("youtube") -> return "YouTube"
                lower.contains("musically") || lower.contains("trill") || lower.contains("tiktok") -> return "TikTok"
                lower.contains("snapchat") -> return "Snapchat"
                lower.contains("twitter") || lower.contains("com.x.") -> return "X (Twitter)"
            }
            return try {
                val pm = context.packageManager
                val info = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(info).toString()
            } catch (_: Exception) {
                pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_REELS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_DATE_STR TEXT NOT NULL,
                $COL_PACKAGE TEXT NOT NULL,
                $COL_CATEGORY TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_timestamp ON $TABLE_REELS($COL_TIMESTAMP)")
        db.execSQL("CREATE INDEX idx_date_str ON $TABLE_REELS($COL_DATE_STR)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_REELS")
        onCreate(db)
    }

    fun recordReelView(context: Context, packageName: String): Int {
        val now = System.currentTimeMillis()
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        val platformName = resolvePlatformName(context, packageName)

        val values = ContentValues().apply {
            put(COL_TIMESTAMP, now)
            put(COL_DATE_STR, dateStr)
            put(COL_PACKAGE, packageName)
            put(COL_CATEGORY, platformName)
        }
        writableDatabase.insert(TABLE_REELS, null, values)

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_ACTIVE_APP, platformName)
            .putString(KEY_LAST_ACTIVE_PKG, packageName)
            .apply()

        return getTodayCount()
    }

    fun getTodayCount(): Int {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_REELS WHERE $COL_DATE_STR = ?",
            arrayOf(todayStr)
        )
        var count = 0
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0)
        }
        cursor.close()
        return count
    }

    fun getCountSinceDays(days: Int): Int {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_REELS WHERE $COL_TIMESTAMP >= ?",
            arrayOf(cal.timeInMillis.toString())
        )
        var count = 0
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0)
        }
        cursor.close()
        return count
    }

    fun getAppBreakdownSinceDays(days: Int): List<AppUsageStat> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val defaultPlatforms = linkedMapOf(
            "Instagram" to Pair("com.instagram.android", 0),
            "Facebook" to Pair("com.facebook.katana", 0),
            "YouTube" to Pair("com.google.android.youtube", 0),
            "TikTok" to Pair("com.zhiliaoapp.musically", 0)
        )

        val cursor = readableDatabase.rawQuery(
            "SELECT $COL_CATEGORY, $COL_PACKAGE, COUNT(*) FROM $TABLE_REELS WHERE $COL_TIMESTAMP >= ? GROUP BY $COL_CATEGORY",
            arrayOf(cal.timeInMillis.toString())
        )
        var total = 0
        while (cursor.moveToNext()) {
            val appName = cursor.getString(0) ?: "Other"
            val pkgName = cursor.getString(1) ?: ""
            val c = cursor.getInt(2)
            val existing = defaultPlatforms[appName]
            defaultPlatforms[appName] = Pair(
                if (pkgName.isNotEmpty()) pkgName else (existing?.first ?: ""),
                (existing?.second ?: 0) + c
            )
            total += c
        }
        cursor.close()

        val denom = if (total > 0) total.toFloat() else 1f
        return defaultPlatforms.map { (appName, pair) ->
            AppUsageStat(appName, pair.first, pair.second, (pair.second / denom) * 100f)
        }.sortedByDescending { it.count }
    }

    fun getDailySeries(days: Int): List<DailyBucket> {
        val result = mutableListOf<DailyBucket>()
        val dbFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val labelFmt = if (days <= 7) SimpleDateFormat("EEE", Locale.US) else SimpleDateFormat("MM/dd", Locale.US)

        val countsByDate = mutableMapOf<String, Int>()
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)

        val cursor = readableDatabase.rawQuery(
            "SELECT $COL_DATE_STR, COUNT(*) FROM $TABLE_REELS WHERE $COL_TIMESTAMP >= ? GROUP BY $COL_DATE_STR",
            arrayOf(cal.timeInMillis.toString())
        )
        while (cursor.moveToNext()) {
            countsByDate[cursor.getString(0)] = cursor.getInt(1)
        }
        cursor.close()

        val iterCal = Calendar.getInstance()
        iterCal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        for (i in 0 until days) {
            val d = iterCal.time
            val key = dbFmt.format(d)
            val label = labelFmt.format(d)
            result.add(DailyBucket(label, countsByDate[key] ?: 0))
            iterCal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return result
    }
}
