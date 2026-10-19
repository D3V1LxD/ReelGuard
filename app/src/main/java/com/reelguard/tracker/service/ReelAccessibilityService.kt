package com.reelguard.tracker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.reelguard.tracker.data.ReelDatabaseHelper
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs

/**
 * Precision Reel & Short-Form Video Detector for Android.
 *
 * Specifically engineered so LONG VIDEOS, PAUSED VIDEOS, and SUBTITLES never cause false counts:
 * 1. Unified Post-Swipe Settle Verification:
 *    - A Reel count NEVER increments blindly on a raw scroll event (because long videos contain
 *      auto-scrolling subtitles, lyrics, comment tickers, and custom progress scrubbers that
 *      periodically emit `TYPE_VIEW_SCROLLED`).
 *    - Instead, every candidate vertical pager scroll schedules a single post-settle check (380ms
 *      after scrolling stops) that inspects the static **Reel Identity Fingerprint**.
 * 2. Static Reel Identity Fingerprint (Immune to Subtitles & Timers):
 *    - Built strictly from static per-video anchors that NEVER change while a single long video plays:
 *      (a) The Right-Rail Action Button Counts (Like count, Comment count, Share/Save count located
 *          in the right column `bounds.left >= 70%` of screen width), and
 *      (b) The static Creator Handle (`@username` or clickable short channel title),
 *      completely ignoring multi-word closed captions/subtitles and video progress timers.
 * 3. Seen-Reel Fingerprint Memory:
 *    - Remembers the current and recently seen Reel fingerprints so the same long video cannot
 *      be counted more than once no matter how long it plays or how many times it is paused.
 */
class ReelAccessibilityService : AccessibilityService() {

    private lateinit var dbHelper: ReelDatabaseHelper
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isInReelMode: Boolean = false
    private var currentReelPackage: String = ""

    private var lastCountedTimeMs: Long = 0L
    private var lastClickTimeMs: Long = 0L
    private var currentReelFingerprint: String = ""
    private val recentFingerprints = ArrayDeque<String>()

    private val verifyReelChangeRunnable = Runnable {
        if (!isInReelMode || currentReelPackage.isEmpty()) return@Runnable

        val now = System.currentTimeMillis()
        if (now - lastCountedTimeMs < SWIPE_COOLDOWN_MS) return@Runnable

        val root = rootInActiveWindow ?: findActiveAppRoot(currentReelPackage)
        val snapshot = analyzeWindowForReels(currentReelPackage, root)

        if (!snapshot.isReelScreen) {
            if (snapshot.isDefinitelyNonReelScreen) {
                exitReelMode()
            }
            return@Runnable
        }

        val newFp = snapshot.reelFingerprint
        if (newFp.isEmpty()) return@Runnable

        // If we didn't have a fingerprint yet for the initial reel, lock it in without double-counting
        if (currentReelFingerprint.isEmpty()) {
            currentReelFingerprint = newFp
            rememberFingerprint(newFp)
            return@Runnable
        }

        // ONLY count +1 if the static Reel Identity Fingerprint (Creator + Right-Rail Counts)
        // has genuinely changed to a DIFFERENT video not currently playing!
        if (newFp != currentReelFingerprint && !recentFingerprints.contains(newFp)) {
            currentReelFingerprint = newFp
            rememberFingerprint(newFp)
            lastCountedTimeMs = now
            recordAndBroadcastReel(currentReelPackage)
        }
    }

    private fun rememberFingerprint(fp: String) {
        if (fp.isEmpty()) return
        recentFingerprints.remove(fp)
        recentFingerprints.addLast(fp)
        while (recentFingerprints.size > 4) {
            recentFingerprints.removeFirst()
        }
    }

    companion object {
        // Minimum cooldown between two real reel swipes
        private const val SWIPE_COOLDOWN_MS = 1400L
        // Wait 380ms after a scroll event finishes settling before reading the new Reel's fingerprint
        private const val SCROLL_SETTLE_DELAY_MS = 380L

        private val TIMESTAMP_REGEX = Regex("\\b\\d{1,2}:\\d{2}\\b")

        private val IGNORED_UI_WORDS = setOf(
            "pause", "paused", "play", "playing", "mute", "muted", "unmute", "unmuted",
            "volume", "loading", "buffering", "like", "liked", "comment", "comments",
            "share", "send", "follow", "following", "subscribe", "subscribed",
            "reels", "shorts", "for you", "sponsored", "ad"
        )

        private val NON_PAGER_WIDGET_CLASSES = listOf(
            "seekbar",
            "progressbar",
            "horizontalscrollview",
            "textview",
            "button",
            "imagebutton",
            "imageview",
            "edittext",
            "switch",
            "checkbox"
        )

        private val SYSTEM_OR_NON_VIDEO_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.vending",
            "com.google.android.gm",
            "com.whatsapp",
            "com.google.android.apps.messaging",
            "com.google.android.dialer",
            "com.android.contacts",
            "com.google.android.calculator",
            "com.google.android.calendar"
        )

        private val DEDICATED_SHORT_VIDEO_APPS = setOf(
            "com.zhiliaoapp.musically",      // TikTok Global
            "com.ss.android.ugc.trill",      // TikTok Asia
            "com.zhiliaoapp.musically.go",   // TikTok Lite
            "com.kwai.video",                // Kwai
            "video.like",                    // Likee
            "in.mohalla.video",              // Moj
            "com.eterno.shortvideos",        // Josh
            "io.chingari.app"                // Chingari
        )

        private val REEL_VIEW_ID_KEYWORDS = listOf(
            "clips_viewer",
            "clips_video",
            "clips_item",
            "reel_viewer",
            "reel_recycler",
            "reel_player",
            "reel_watch",
            "shorts_player",
            "shorts_page",
            "shorts_container",
            "fb_shorts",
            "short_form_video",
            "vertical_video",
            "immersive_viewer"
        )

        private val REEL_OVERLAY_PHRASES = listOf(
            "original audio",
            "original sound",
            "use audio",
            "remix this",
            "reels and short videos",
            "reel player",
            "shorts player",
            "send post",
            "share reel",
            "share short"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        dbHelper = ReelDatabaseHelper(this)
        ensureOverlayServiceRunning()
    }

    private fun ensureOverlayServiceRunning() {
        try {
            val intent = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // Ignore transient keyboard / system UI events
        if (pkg == "com.android.systemui" ||
            pkg.contains("inputmethod") ||
            pkg.contains("keyboard") ||
            pkg.contains("honeyboard") ||
            pkg.contains("gboard")
        ) {
            return
        }

        // Hide overlay immediately when user goes to Home Screen Launcher, Settings, or ReelGuard itself
        if (pkg == packageName ||
            pkg.contains("launcher") ||
            pkg.contains("miui.home") ||
            pkg.contains("oppo.launcher") ||
            pkg.contains("oneplus.launcher") ||
            SYSTEM_OR_NON_VIDEO_PACKAGES.contains(pkg)
        ) {
            if (isInReelMode) {
                exitReelMode()
            }
            return
        }

        val now = System.currentTimeMillis()

        // Track tap/click events so tapping to pause/play is never treated as a swipe
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            lastClickTimeMs = now
            mainHandler.removeCallbacks(verifyReelChangeRunnable)
            return
        }

        // Handle Scroll Events
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            handleScrollEvent(event, pkg, now)
            return
        }

        // Window State Changes: only used to enter or exit Reel Mode (never increments while already watching)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val root = rootInActiveWindow ?: findActiveAppRoot(pkg)
            val snapshot = analyzeWindowForReels(pkg, root)

            if (!snapshot.isReelScreen) {
                if (isInReelMode) {
                    exitReelMode()
                }
                return
            }

            if (!isInReelMode || currentReelPackage != pkg) {
                enterReelMode(pkg, snapshot.reelFingerprint, now)
            } else if (currentReelFingerprint.isEmpty() && snapshot.reelFingerprint.isNotEmpty()) {
                currentReelFingerprint = snapshot.reelFingerprint
                rememberFingerprint(snapshot.reelFingerprint)
            }
        }
    }

    private fun enterReelMode(pkg: String, initialFingerprint: String, now: Long) {
        isInReelMode = true
        currentReelPackage = pkg
        recentFingerprints.clear()
        if (initialFingerprint.isNotEmpty()) {
            currentReelFingerprint = initialFingerprint
            rememberFingerprint(initialFingerprint)
        } else {
            currentReelFingerprint = ""
        }

        // Count the first Reel when entering the Reel/Shorts player (with 3s guard against rapid tab switches)
        if (now - lastCountedTimeMs >= 3000L) {
            lastCountedTimeMs = now
            recordAndBroadcastReel(pkg)
        } else {
            showOverlayForPackage(pkg)
        }
    }

    private fun handleScrollEvent(event: AccessibilityEvent, pkg: String, now: Long) {
        // 1. Ignore SeekBars, ProgressBars, TextViews, and small widgets
        val clsName = event.className?.toString()?.lowercase(Locale.ROOT) ?: ""
        for (nonPager in NON_PAGER_WIDGET_CLASSES) {
            if (clsName.contains(nonPager)) return
        }

        // 2. Ignore 0-100% video progress scrubbers that emit itemCount == 100 or 1000
        if (event.itemCount == 100 || event.itemCount == 1000) {
            return
        }

        // 3. Ignore scroll events right after a tap-to-pause click
        if (now - lastClickTimeMs < 400L) {
            return
        }

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.coerceAtLeast(1)
        val screenH = dm.heightPixels.coerceAtLeast(1)

        // 4. If the event exposes its source node, verify it is a full-screen vertical container
        val sourceNode = try { event.source } catch (_: Exception) { null }
        if (sourceNode != null) {
            val bounds = Rect()
            sourceNode.getBoundsInScreen(bounds)
            if (bounds.height() < screenH * 0.60 || bounds.width() < screenW * 0.75) {
                return
            }
        }

        // 5. Reject horizontal scrolls
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val dx = abs(event.scrollDeltaX)
            val dy = abs(event.scrollDeltaY)
            if (dx > dy && dx > 30) return
            // If scrollDeltaY is reported, ignore tiny micro-jitters (< 8% of screen height)
            if (dy in 1..(screenH * 0.08).toInt()) return
        }

        // If not yet in Reel Mode, check if this scroll happened inside a Reel/Shorts player
        if (!isInReelMode || currentReelPackage != pkg) {
            val root = rootInActiveWindow ?: findActiveAppRoot(pkg)
            val snapshot = analyzeWindowForReels(pkg, root)
            if (snapshot.isReelScreen) {
                enterReelMode(pkg, snapshot.reelFingerprint, now)
            }
            return
        }

        // Debounce: reset the 380ms settle timer on each scroll frame so we only inspect the window
        // AFTER the swipe animation finishes snapping to the new Reel!
        mainHandler.removeCallbacks(verifyReelChangeRunnable)
        mainHandler.postDelayed(verifyReelChangeRunnable, SCROLL_SETTLE_DELAY_MS)
    }

    private fun findActiveAppRoot(targetPkg: String): AccessibilityNodeInfo? {
        return try {
            for (win in windows) {
                val r = win.root
                if (r != null && r.packageName?.toString() == targetPkg) {
                    return r
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun showOverlayForPackage(pkg: String) {
        val platformName = ReelDatabaseHelper.resolvePlatformName(this, pkg)
        val showIntent = Intent(OverlayService.ACTION_SET_OVERLAY_VISIBILITY).apply {
            setPackage(packageName)
            putExtra(OverlayService.EXTRA_IS_WATCHING_REELS, true)
            putExtra(OverlayService.EXTRA_ACTIVE_APP, platformName)
            putExtra(OverlayService.EXTRA_ACTIVE_PKG, pkg)
        }
        sendBroadcast(showIntent)
    }

    private fun recordAndBroadcastReel(pkg: String) {
        val platformName = ReelDatabaseHelper.resolvePlatformName(this, pkg)
        dbHelper.recordReelView(this, pkg)
        val updateIntent = Intent(OverlayService.ACTION_UPDATE_COUNTER).apply {
            setPackage(packageName)
            putExtra(OverlayService.EXTRA_ACTIVE_APP, platformName)
            putExtra(OverlayService.EXTRA_ACTIVE_PKG, pkg)
        }
        sendBroadcast(updateIntent)
    }

    private fun exitReelMode() {
        isInReelMode = false
        currentReelPackage = ""
        currentReelFingerprint = ""
        recentFingerprints.clear()
        mainHandler.removeCallbacks(verifyReelChangeRunnable)

        val hideIntent = Intent(OverlayService.ACTION_SET_OVERLAY_VISIBILITY).apply {
            setPackage(packageName)
            putExtra(OverlayService.EXTRA_IS_WATCHING_REELS, false)
        }
        sendBroadcast(hideIntent)
    }

    private data class WindowReelAnalysis(
        val isReelScreen: Boolean,
        val isDefinitelyNonReelScreen: Boolean,
        val reelFingerprint: String
    )

    /**
     * Inspects the active window to determine:
     * 1. Whether the screen is a Reel/Shorts player.
     * 2. A static `reelFingerprint` composed strictly of:
     *    - Right-rail action button counts/labels (Like count, Comment count, Share count)
     *    - Creator handle (`@username` or short clickable author name)
     *    EXCLUDING multi-word subtitles/captions and video timers so long videos never change fingerprint!
     */
    private fun analyzeWindowForReels(pkg: String, root: AccessibilityNodeInfo?): WindowReelAnalysis {
        if (root == null) {
            return WindowReelAnalysis(
                isReelScreen = isInReelMode && currentReelPackage == pkg,
                isDefinitelyNonReelScreen = false,
                reelFingerprint = currentReelFingerprint
            )
        }

        val isDedicatedShortApp = DEDICATED_SHORT_VIDEO_APPS.contains(pkg) ||
                pkg.contains("tiktok") || pkg.contains("musically") || pkg.contains("trill")

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.coerceAtLeast(1)
        val screenH = dm.heightPixels.coerceAtLeast(1)

        var hasReelViewId = false
        var hasSelectedReelTab = false
        var hasSelectedNonReelTab = false
        var hasReelOverlayPhrase = false
        var hasFullScreenVerticalContainer = false
        var engagementWordMatches = 0

        val rightRailActionYPositions = mutableListOf<Int>()
        val rightRailStaticLabels = mutableListOf<String>()
        var creatorHandleToken: String = ""

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (!queue.isEmpty() && visited < 360) {
            val node = queue.removeFirst()
            visited++

            val viewId = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val lowerText = text.lowercase(Locale.ROOT)
            val lowerDesc = desc.lowercase(Locale.ROOT)

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val w = bounds.width()
            val h = bounds.height()

            // 1. Full-screen vertical container check
            if (!hasFullScreenVerticalContainer && w >= screenW * 0.80 && h >= screenH * 0.65) {
                val cls = node.className?.toString()?.lowercase(Locale.ROOT) ?: ""
                if (node.isScrollable || cls.contains("pager") || cls.contains("recycler") ||
                    cls.contains("surface") || cls.contains("texture") || cls.contains("video")
                ) {
                    hasFullScreenVerticalContainer = true
                }
            }

            // 2. Right-Rail Vertical Action Column (70%..100% width, 20%..90% height)
            // Collect both button positions AND static engagement counts (e.g. "24.3K", "512")
            if (bounds.left >= screenW * 0.70 &&
                bounds.right <= screenW &&
                bounds.top >= screenH * 0.20 &&
                bounds.bottom <= screenH * 0.90
            ) {
                if (node.isClickable && w in 24..(screenW * 0.28).toInt() && h in 24..(screenH * 0.16).toInt()) {
                    val centerY = bounds.centerY()
                    if (rightRailActionYPositions.none { abs(it - centerY) < (screenH * 0.04) }) {
                        rightRailActionYPositions.add(centerY)
                    }
                }
                val railToken = if (text.isNotEmpty()) text else desc
                if (railToken.isNotEmpty() &&
                    railToken.length <= 28 &&
                    !TIMESTAMP_REGEX.containsMatchIn(railToken) &&
                    !IGNORED_UI_WORDS.contains(railToken.lowercase(Locale.ROOT)) &&
                    rightRailStaticLabels.size < 4
                ) {
                    rightRailStaticLabels.add(railToken)
                }
            }

            // 3. Creator Handle (@username or short clickable author handle in lower-left, never multi-sentence captions)
            if (creatorHandleToken.isEmpty() && text.length in 3..28) {
                if (text.startsWith("@") && !text.contains(" ")) {
                    creatorHandleToken = text
                } else if (node.isClickable &&
                    bounds.left < screenW * 0.65 &&
                    bounds.top >= screenH * 0.55 &&
                    bounds.bottom <= screenH * 0.92 &&
                    !TIMESTAMP_REGEX.containsMatchIn(text) &&
                    !IGNORED_UI_WORDS.contains(lowerText) &&
                    text.count { it == ' ' } <= 1
                ) {
                    creatorHandleToken = text
                }
            }

            // 4. Explicit Reel / Shorts View IDs
            if (!hasReelViewId && viewId.isNotEmpty()) {
                for (kw in REEL_VIEW_ID_KEYWORDS) {
                    if (viewId.contains(kw)) {
                        hasReelViewId = true
                        break
                    }
                }
            }

            // 5. Selected navigation tabs
            if (node.isSelected) {
                if (lowerText == "reels" || lowerText == "shorts" || lowerText == "spotlight" ||
                    lowerDesc.contains("reels") || lowerDesc.contains("shorts")
                ) {
                    hasSelectedReelTab = true
                } else if (lowerText == "messages" || lowerText == "chats" || lowerText == "inbox" ||
                    lowerText == "notifications" || lowerText == "settings" ||
                    lowerDesc == "inbox" || lowerDesc == "direct" || lowerDesc == "messages"
                ) {
                    hasSelectedNonReelTab = true
                }
            }

            // 6. Reel overlay phrases
            if (!hasReelOverlayPhrase) {
                for (phrase in REEL_OVERLAY_PHRASES) {
                    if (lowerText.contains(phrase) || lowerDesc.contains(phrase)) {
                        hasReelOverlayPhrase = true
                        break
                    }
                }
            }

            // 7. Social engagement words
            if (lowerDesc.contains("like") || lowerText == "like" ||
                lowerDesc.contains("comment") || lowerText == "comment" ||
                lowerDesc.contains("share") || lowerDesc.contains("send") ||
                lowerDesc.contains("remix") || lowerDesc.contains("audio") ||
                lowerDesc.contains("sound")
            ) {
                engagementWordMatches++
            }

            val count = node.childCount
            for (i in 0 until count) {
                val child = node.getChild(i)
                if (child != null) {
                    queue.add(child)
                }
            }
        }

        val hasRightRailActionStack = rightRailActionYPositions.size >= 3
        val hasGeometryAndActions = (hasFullScreenVerticalContainer && (hasRightRailActionStack || engagementWordMatches >= 3)) ||
                (hasRightRailActionStack && engagementWordMatches >= 2)

        val isReelScreen = when {
            isDedicatedShortApp -> !hasSelectedNonReelTab
            else -> !hasSelectedNonReelTab && (
                hasReelViewId ||
                hasSelectedReelTab ||
                hasReelOverlayPhrase ||
                hasGeometryAndActions
            )
        }

        val fpBuilder = mutableListOf<String>()
        if (creatorHandleToken.isNotEmpty()) {
            fpBuilder.add(creatorHandleToken)
        }
        fpBuilder.addAll(rightRailStaticLabels.distinct().take(3))
        val fingerprint = fpBuilder.joinToString("|")

        return WindowReelAnalysis(
            isReelScreen = isReelScreen,
            isDefinitelyNonReelScreen = hasSelectedNonReelTab,
            reelFingerprint = fingerprint
        )
    }

    override fun onInterrupt() {
        exitReelMode()
    }
}
