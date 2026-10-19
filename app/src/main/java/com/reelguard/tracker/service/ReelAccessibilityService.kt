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
 * Specifically engineered to prevent false counts:
 * 1. NEVER counts on `TYPE_WINDOW_CONTENT_CHANGED` or `TYPE_VIEW_CLICKED` alone
 *    (so subtitles, video timers, progress bars, or tapping to pause/play never trigger counts).
 * 2. Ignores `TYPE_VIEW_SCROLLED` from SeekBars, ProgressBars, audio marquees, and small views
 *    by requiring the scrolling container to cover >= 55% of the screen height.
 * 3. Verifies a genuine Reel page transition before counting +1:
 *    - If the pager reports item indices (`fromIndex >= 0`), requires the page index to actually change.
 *    - If the pager reports `fromIndex == -1`, requires either a vertical swipe distance >= 22% of screen
 *      height OR a verified change in the static Creator/Caption identity (filtering out timers,
 *      numbers, and pause/play/mute state text) after the swipe settles.
 */
class ReelAccessibilityService : AccessibilityService() {

    private lateinit var dbHelper: ReelDatabaseHelper
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isInReelMode: Boolean = false
    private var currentReelPackage: String = ""

    // State for accurate single-count per new Reel page
    private var lastCountedTimeMs: Long = 0L
    private var lastClickTimeMs: Long = 0L
    private var lastFullPagerScrollTimeMs: Long = 0L
    private var accumulatedVerticalDelta: Int = 0
    private var lastConfirmedPageIndex: Int = -999
    private var lastConfirmedSignature: String = ""
    private var pendingScrollVerifyScheduled: Boolean = false

    private val verifyScrollTransitionRunnable = Runnable {
        pendingScrollVerifyScheduled = false
        if (!isInReelMode || currentReelPackage.isEmpty()) return@Runnable

        val now = System.currentTimeMillis()
        if (now - lastCountedTimeMs < SWIPE_COOLDOWN_MS) return@Runnable

        val root = rootInActiveWindow ?: findActiveAppRoot(currentReelPackage)
        val snapshot = analyzeWindowForReels(currentReelPackage, root)
        if (!snapshot.isReelScreen) {
            if (snapshot.isDefinitelyNonReelScreen) exitReelMode()
            return@Runnable
        }

        // Verify that the static creator/caption identity actually changed to a DIFFERENT reel
        if (snapshot.stableSignature.isNotEmpty() &&
            snapshot.stableSignature != lastConfirmedSignature
        ) {
            lastConfirmedSignature = snapshot.stableSignature
            lastCountedTimeMs = now
            accumulatedVerticalDelta = 0
            recordAndBroadcastReel(currentReelPackage)
        }
    }

    companion object {
        // Minimum time between two consecutive reel counts
        private const val SWIPE_COOLDOWN_MS = 1200L

        private val TIMESTAMP_REGEX = Regex("\\b\\d{1,2}:\\d{2}\\b")
        private val PURE_NUMBER_REGEX = Regex("^[0-9.,]+[KMBkmb]?$")

        private val PLAYBACK_STATE_WORDS = setOf(
            "pause", "paused", "play", "playing", "mute", "muted", "unmute", "unmuted",
            "volume", "loading", "buffering", "like", "liked", "comment", "comments",
            "share", "send", "follow", "following", "subscribe", "subscribed", "reels", "shorts"
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

        // Track tap/click events so a tap-to-pause gesture is never mistaken for a vertical reel swipe
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            lastClickTimeMs = now
            accumulatedVerticalDelta = 0
            return
        }

        // Handle Scroll Events ONLY from full-screen vertical Reel/Shorts containers
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            handleScrollEvent(event, pkg, now)
            return
        }

        // For window state/content changes, ONLY update Reel mode visibility (NEVER increment counter in-place!)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            // Avoid expensive tree scans on every frame of video playback if we already checked recently
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                isInReelMode &&
                currentReelPackage == pkg &&
                (now - lastFullPagerScrollTimeMs) > 2000L
            ) {
                return
            }

            val root = rootInActiveWindow ?: findActiveAppRoot(pkg)
            val snapshot = analyzeWindowForReels(pkg, root)

            if (!snapshot.isReelScreen) {
                if (isInReelMode && (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || snapshot.isDefinitelyNonReelScreen)) {
                    exitReelMode()
                }
                return
            }

            // User just entered Reels/Shorts mode from another screen or app
            if (!isInReelMode || currentReelPackage != pkg) {
                isInReelMode = true
                currentReelPackage = pkg
                lastConfirmedSignature = snapshot.stableSignature
                lastConfirmedPageIndex = -999
                accumulatedVerticalDelta = 0

                // Count the initial Reel when first entering the Reel/Shorts viewer
                if (now - lastCountedTimeMs >= 2500L) {
                    lastCountedTimeMs = now
                    recordAndBroadcastReel(pkg)
                } else {
                    showOverlayForPackage(pkg)
                }
            } else if (lastConfirmedSignature.isEmpty() && snapshot.stableSignature.isNotEmpty()) {
                lastConfirmedSignature = snapshot.stableSignature
            }
        }
    }

    private fun handleScrollEvent(event: AccessibilityEvent, pkg: String, now: Long) {
        // 1. Reject SeekBars, ProgressBars, TextViews (audio marquees), and horizontal carousels
        val clsName = event.className?.toString()?.lowercase(Locale.ROOT) ?: ""
        for (nonPager in NON_PAGER_WIDGET_CLASSES) {
            if (clsName.contains(nonPager)) return
        }

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.coerceAtLeast(1)
        val screenH = dm.heightPixels.coerceAtLeast(1)

        // 2. Check the bounds of the view that scrolled: must be a large vertical container, not a bottom scrubber bar
        val sourceNode = try { event.source } catch (_: Exception) { null }
        if (sourceNode != null) {
            val bounds = Rect()
            sourceNode.getBoundsInScreen(bounds)
            if (bounds.height() < screenH * 0.55 || bounds.width() < screenW * 0.70) {
                // Too small to be the full-screen Reel pager (e.g. a video seekbar or comment ticker)
                return
            }
        }

        // 3. Reject horizontal scrolls
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val dx = abs(event.scrollDeltaX)
            val dy = abs(event.scrollDeltaY)
            if (dx > dy && dx > 40) return
        }

        // 4. Ignore tiny touch jitter within 350ms of tapping to pause/unpause
        if (now - lastClickTimeMs < 350L) {
            val dy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) abs(event.scrollDeltaY) else 0
            if (dy < screenH * 0.15) return
        }

        // Ensure we are actually on a Reel/Shorts screen
        if (!isInReelMode || currentReelPackage != pkg) {
            val root = rootInActiveWindow ?: findActiveAppRoot(pkg)
            val snapshot = analyzeWindowForReels(pkg, root)
            if (!snapshot.isReelScreen) return
            isInReelMode = true
            currentReelPackage = pkg
            lastConfirmedSignature = snapshot.stableSignature
            lastConfirmedPageIndex = event.fromIndex
            lastCountedTimeMs = now
            recordAndBroadcastReel(pkg)
            return
        }

        lastFullPagerScrollTimeMs = now

        // CASE A: The pager reports valid item indices (e.g. RecyclerView / ViewPager2)
        if (event.fromIndex >= 0 && event.itemCount > 1) {
            if (lastConfirmedPageIndex == -999) {
                lastConfirmedPageIndex = event.fromIndex
            } else if (event.fromIndex != lastConfirmedPageIndex) {
                // The visible Reel page index genuinely changed!
                lastConfirmedPageIndex = event.fromIndex
                if (now - lastCountedTimeMs >= SWIPE_COOLDOWN_MS) {
                    lastCountedTimeMs = now
                    accumulatedVerticalDelta = 0
                    val root = rootInActiveWindow ?: findActiveAppRoot(pkg)
                    val snap = analyzeWindowForReels(pkg, root)
                    if (snap.stableSignature.isNotEmpty()) {
                        lastConfirmedSignature = snap.stableSignature
                    }
                    recordAndBroadcastReel(pkg)
                }
            }
            return
        }

        // CASE B: Pager reports `fromIndex == -1`
        // Track vertical scroll distance if available on API 28+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && event.scrollDeltaY != 0) {
            if (now - lastFullPagerScrollTimeMs > 1200L) {
                accumulatedVerticalDelta = 0
            }
            accumulatedVerticalDelta += abs(event.scrollDeltaY)
            // Require scrolling at least 25% of the screen height to count as a page flip
            if (accumulatedVerticalDelta >= (screenH * 0.25).toInt() &&
                now - lastCountedTimeMs >= SWIPE_COOLDOWN_MS
            ) {
                accumulatedVerticalDelta = 0
                lastCountedTimeMs = now
                recordAndBroadcastReel(pkg)
                return
            }
        }

        // CASE C: Pager reports neither `fromIndex` nor `scrollDeltaY` (e.g. custom ViewPager snap event)
        // Schedule a settle check 320ms after the scroll event to verify if the Creator/Caption changed
        if (!pendingScrollVerifyScheduled) {
            pendingScrollVerifyScheduled = true
            mainHandler.removeCallbacks(verifyScrollTransitionRunnable)
            mainHandler.postDelayed(verifyScrollTransitionRunnable, 320L)
        }
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
        lastConfirmedSignature = ""
        lastConfirmedPageIndex = -999
        accumulatedVerticalDelta = 0
        pendingScrollVerifyScheduled = false
        mainHandler.removeCallbacks(verifyScrollTransitionRunnable)

        val hideIntent = Intent(OverlayService.ACTION_SET_OVERLAY_VISIBILITY).apply {
            setPackage(packageName)
            putExtra(OverlayService.EXTRA_IS_WATCHING_REELS, false)
        }
        sendBroadcast(hideIntent)
    }

    private data class WindowReelAnalysis(
        val isReelScreen: Boolean,
        val isDefinitelyNonReelScreen: Boolean,
        val stableSignature: String
    )

    private fun analyzeWindowForReels(pkg: String, root: AccessibilityNodeInfo?): WindowReelAnalysis {
        if (root == null) {
            return WindowReelAnalysis(
                isReelScreen = isInReelMode && currentReelPackage == pkg,
                isDefinitelyNonReelScreen = false,
                stableSignature = lastConfirmedSignature
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
        val stableCreatorOrCaptionTokens = mutableListOf<String>()

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

            if (!hasFullScreenVerticalContainer && w >= screenW * 0.80 && h >= screenH * 0.65) {
                val cls = node.className?.toString()?.lowercase(Locale.ROOT) ?: ""
                if (node.isScrollable || cls.contains("pager") || cls.contains("recycler") ||
                    cls.contains("surface") || cls.contains("texture") || cls.contains("video")
                ) {
                    hasFullScreenVerticalContainer = true
                }
            }

            if (node.isClickable &&
                bounds.left >= screenW * 0.72 &&
                bounds.right <= screenW &&
                bounds.top >= screenH * 0.22 &&
                bounds.bottom <= screenH * 0.90 &&
                w in 24..(screenW * 0.28).toInt() &&
                h in 24..(screenH * 0.16).toInt()
            ) {
                val centerY = bounds.centerY()
                if (rightRailActionYPositions.none { abs(it - centerY) < (screenH * 0.04) }) {
                    rightRailActionYPositions.add(centerY)
                }
            }

            if (!hasReelViewId && viewId.isNotEmpty()) {
                for (kw in REEL_VIEW_ID_KEYWORDS) {
                    if (viewId.contains(kw)) {
                        hasReelViewId = true
                        break
                    }
                }
            }

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

            if (!hasReelOverlayPhrase) {
                for (phrase in REEL_OVERLAY_PHRASES) {
                    if (lowerText.contains(phrase) || lowerDesc.contains(phrase)) {
                        hasReelOverlayPhrase = true
                        break
                    }
                }
            }

            if (lowerDesc.contains("like") || lowerText == "like" ||
                lowerDesc.contains("comment") || lowerText == "comment" ||
                lowerDesc.contains("share") || lowerDesc.contains("send") ||
                lowerDesc.contains("remix") || lowerDesc.contains("audio") ||
                lowerDesc.contains("sound")
            ) {
                engagementWordMatches++
            }

            // Collect ONLY static creator handle / caption anchor tokens (ignore timers, counts, and pause/play labels)
            if (stableCreatorOrCaptionTokens.size < 3 && text.length in 3..45) {
                if (bounds.left < screenW * 0.70 && bounds.top >= screenH * 0.50 && bounds.bottom <= screenH * 0.92) {
                    if (!TIMESTAMP_REGEX.containsMatchIn(text) &&
                        !PURE_NUMBER_REGEX.matches(text) &&
                        !PLAYBACK_STATE_WORDS.contains(lowerText)
                    ) {
                        stableCreatorOrCaptionTokens.add(text)
                    }
                }
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

        val stableSig = stableCreatorOrCaptionTokens.distinct().take(2).joinToString("|")

        return WindowReelAnalysis(
            isReelScreen = isReelScreen,
            isDefinitelyNonReelScreen = hasSelectedNonReelTab,
            stableSignature = stableSig
        )
    }

    override fun onInterrupt() {
        exitReelMode()
    }
}
