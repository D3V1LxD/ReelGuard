package com.reelguard.tracker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.reelguard.tracker.data.ReelDatabaseHelper
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs

/**
 * Universal Reel & Short-Form Video Detector for Android.
 *
 * Works across ANY platform (Instagram, Facebook, Facebook Lite, YouTube, TikTok,
 * Snapchat, X/Twitter, Likee, Kwai, ShareChat, Moj, Reddit, Browsers, etc.):
 *
 * 1. Universal Reel/Shorts Screen Detection:
 *    - Combines explicit view IDs, selected tab labels, and a geometric detector
 *      (Full-screen vertical scrollable surface + right-rail vertical action button stack
 *      or engagement overlays) so even 100% obfuscated views (like Facebook Litho) are detected.
 * 2. Reliable Swipe Counting:
 *    - Counts +1 when entering a Reel/Shorts feed.
 *    - Counts +1 on every vertical swipe (`TYPE_VIEW_SCROLLED`) inside a Reel/Shorts feed
 *      without requiring `scrollDeltaY > 60` (since PagerSnapHelper/ViewPager2 often reports
 *      `scrollDeltaY = 0` and `fromIndex = -1` on Android 9–15).
 *    - Also tracks on-screen Reel content signatures (author/caption/counts) as a backup
 *      when custom players only emit `TYPE_WINDOW_CONTENT_CHANGED`.
 * 3. Auto-Show / Auto-Hide Top Overlay:
 *    - Shows the floating counter ONLY while watching Reels/Shorts, and hides it immediately
 *      when leaving to normal feeds, chats, home screen, or other non-video screens.
 */
class ReelAccessibilityService : AccessibilityService() {

    private lateinit var dbHelper: ReelDatabaseHelper

    private var isInReelMode: Boolean = false
    private var currentReelPackage: String = ""
    private var lastCountedTimeMs: Long = 0L
    private var lastEntryCountTimeMs: Long = 0L
    private var lastReelSignature: String = ""

    companion object {
        // Cooldown between two swipes so a single finger fling never counts twice
        private const val SWIPE_COOLDOWN_MS = 1150L
        // Cooldown for content-signature fallback when TYPE_VIEW_SCROLLED is not emitted
        private const val SIGNATURE_CHANGE_COOLDOWN_MS = 1800L

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
            "add a comment",
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

        // Ignore transient keyboard / volume / notification shade events
        if (pkg == "com.android.systemui" ||
            pkg.contains("inputmethod") ||
            pkg.contains("keyboard") ||
            pkg.contains("honeyboard") ||
            pkg.contains("gboard")
        ) {
            return
        }

        // Immediately hide overlay when user goes to Home Screen Launcher, Settings, or ReelGuard itself
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
        val root = rootInActiveWindow ?: findActiveAppRoot(pkg)
        val snapshot = analyzeWindowForReels(pkg, root)

        // FAST PATH: If user is already in Reel Mode in this package and swipes vertically (`TYPE_VIEW_SCROLLED`),
        // count the swipe immediately unless the event clearly shows they exited to a non-reel tab!
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val isHorizontalSwipe = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                abs(event.scrollDeltaX) > abs(event.scrollDeltaY) && abs(event.scrollDeltaX) > 60
            } else {
                false
            }

            if ((isInReelMode && currentReelPackage == pkg && !snapshot.isDefinitelyNonReelScreen) || snapshot.isReelScreen) {
                if (!isInReelMode || currentReelPackage != pkg) {
                    isInReelMode = true
                    currentReelPackage = pkg
                }
                if (!isHorizontalSwipe && now - lastCountedTimeMs >= SWIPE_COOLDOWN_MS) {
                    lastCountedTimeMs = now
                    if (snapshot.contentSignature.isNotEmpty()) {
                        lastReelSignature = snapshot.contentSignature
                    }
                    recordAndBroadcastReel(pkg)
                }
                return
            }
        }

        // Check if user left Reels/Shorts screen
        if (!snapshot.isReelScreen) {
            if (isInReelMode && (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || snapshot.isDefinitelyNonReelScreen)) {
                exitReelMode()
            }
            return
        }

        // User IS on a Reel/Shorts screen!
        if (!isInReelMode || currentReelPackage != pkg) {
            isInReelMode = true
            currentReelPackage = pkg
            lastReelSignature = snapshot.contentSignature

            // Count the initial Reel when opening the Reel/Shorts viewer (with 2.5s cooldown against rapid tab toggles)
            if (now - lastEntryCountTimeMs >= 2500L && now - lastCountedTimeMs >= SWIPE_COOLDOWN_MS) {
                lastEntryCountTimeMs = now
                lastCountedTimeMs = now
                recordAndBroadcastReel(pkg)
            } else {
                // Ensure the overlay is visible even if within entry cooldown
                showOverlayForPackage(pkg)
            }
            return
        }

        // Backup detection when swiping in custom/obfuscated players (e.g. Facebook Litho)
        // where a swipe updates the on-screen Reel signature (author, caption, or counts)
        if (snapshot.contentSignature.isNotEmpty() &&
            lastReelSignature.isNotEmpty() &&
            snapshot.contentSignature != lastReelSignature &&
            now - lastCountedTimeMs >= SIGNATURE_CHANGE_COOLDOWN_MS
        ) {
            lastReelSignature = snapshot.contentSignature
            lastCountedTimeMs = now
            recordAndBroadcastReel(pkg)
        } else if (lastReelSignature.isEmpty() && snapshot.contentSignature.isNotEmpty()) {
            lastReelSignature = snapshot.contentSignature
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
        lastReelSignature = ""
        val hideIntent = Intent(OverlayService.ACTION_SET_OVERLAY_VISIBILITY).apply {
            setPackage(packageName)
            putExtra(OverlayService.EXTRA_IS_WATCHING_REELS, false)
        }
        sendBroadcast(hideIntent)
    }

    private data class WindowReelAnalysis(
        val isReelScreen: Boolean,
        val isDefinitelyNonReelScreen: Boolean,
        val contentSignature: String
    )

    private fun analyzeWindowForReels(pkg: String, root: AccessibilityNodeInfo?): WindowReelAnalysis {
        if (root == null) {
            return WindowReelAnalysis(
                isReelScreen = isInReelMode && currentReelPackage == pkg,
                isDefinitelyNonReelScreen = false,
                contentSignature = lastReelSignature
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
        val signatureTokens = mutableListOf<String>()

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (!queue.isEmpty() && visited < 380) {
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

            // 1. Check full-screen vertical scrollable / video surface
            if (!hasFullScreenVerticalContainer && w >= screenW * 0.80 && h >= screenH * 0.65) {
                val cls = node.className?.toString()?.lowercase(Locale.ROOT) ?: ""
                if (node.isScrollable || cls.contains("pager") || cls.contains("recycler") ||
                    cls.contains("surface") || cls.contains("texture") || cls.contains("video")
                ) {
                    hasFullScreenVerticalContainer = true
                }
            }

            // 2. Check Right-Rail Vertical Action Column (Universal across TikTok, IG Reels, FB Reels, YT Shorts, etc.)
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

            // 3. Check explicit Reel / Shorts View IDs
            if (!hasReelViewId && viewId.isNotEmpty()) {
                for (kw in REEL_VIEW_ID_KEYWORDS) {
                    if (viewId.contains(kw)) {
                        hasReelViewId = true
                        break
                    }
                }
            }

            // 4. Check selected navigation tabs
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

            // 5. Check Reel overlay phrases
            if (!hasReelOverlayPhrase) {
                for (phrase in REEL_OVERLAY_PHRASES) {
                    if (lowerText.contains(phrase) || lowerDesc.contains(phrase)) {
                        hasReelOverlayPhrase = true
                        break
                    }
                }
            }

            // 6. Count social engagement action words
            if (lowerDesc.contains("like") || lowerText == "like" ||
                lowerDesc.contains("comment") || lowerText == "comment" ||
                lowerDesc.contains("share") || lowerDesc.contains("send") ||
                lowerDesc.contains("remix") || lowerDesc.contains("audio") ||
                lowerDesc.contains("sound")
            ) {
                engagementWordMatches++
            }

            // 7. Build content signature from visible author/caption/counter text in lower 75% of screen
            if (signatureTokens.size < 5 && (text.length in 2..55 || desc.length in 3..55)) {
                if (bounds.top >= screenH * 0.18 && bounds.bottom <= screenH * 0.90) {
                    val token = if (text.isNotEmpty()) text else desc
                    val lt = token.lowercase(Locale.ROOT)
                    if (lt != "reels" && lt != "shorts" && lt != "like" && lt != "comment" && lt != "share" && lt != "follow") {
                        signatureTokens.add(token)
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

        // Right-rail vertical stack of 3+ action buttons over a vertical screen is the universal signature of Reels/Shorts
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

        val signature = signatureTokens.distinct().take(4).joinToString("|")

        return WindowReelAnalysis(
            isReelScreen = isReelScreen,
            isDefinitelyNonReelScreen = hasSelectedNonReelTab,
            contentSignature = signature
        )
    }

    override fun onInterrupt() {
        exitReelMode()
    }
}
