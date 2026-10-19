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
 * Strictly excludes Inbox / Direct Messages / Chat threads / Story viewers / Photos:
 * 1. Explicit Inbox, DM, Story & Photo Exclusion:
 *    - Rejects any screen containing an editable text input (`EditText` / `isEditable`, such as
 *      "Message...", "Send message", or "Reply..." bars present in Inbox chats, DM media viewers, and Stories).
 *    - Rejects view IDs belonging to Direct Messages, Chat threads, or Instagram Stories/DM viewers
 *      (`direct_`, `thread_`, `inbox`, `message`, `composer`, `conversation`, and Instagram's
 *      `reel_viewer` which is used for Stories/DM disappearing media, whereas Instagram Reels uses `clips_viewer`).
 *    - Rejects screens with chat/call indicators ("audio call", "video call", "active now", "seen",
 *      "delivered", "write a message", "send a message", "photo by").
 * 2. Requires True Reel/Shorts Vertical Player Structure:
 *    - Requires the vertical right-rail action column (3+ stacked action icons on the right edge
 *      `bounds.left >= 70%` width) or explicit `clips_viewer` / `shorts` / `reel_recycler` IDs,
 *      plus a verified change in the static Creator + Right-Rail count fingerprint on swipe.
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

        if (currentReelFingerprint.isEmpty()) {
            currentReelFingerprint = newFp
            rememberFingerprint(newFp)
            return@Runnable
        }

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
        private const val SWIPE_COOLDOWN_MS = 1400L
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

        // Dedicated messaging, system, and non-reel apps that must NEVER be counted
        private val EXCLUDED_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.vending",
            "com.google.android.gm",
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.facebook.orca",             // Facebook Messenger
            "com.facebook.mlite",            // Messenger Lite
            "org.telegram.messenger",        // Telegram
            "com.discord",                   // Discord
            "com.google.android.apps.messaging",
            "com.google.android.dialer",
            "com.android.contacts",
            "com.google.android.calculator",
            "com.google.android.calendar",
            "com.google.android.apps.photos",
            "com.sec.android.gallery3d",
            "com.miui.gallery"
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

        // Note: In Instagram (`com.instagram.android`), `reel_viewer` is Stories / Direct Inbox visual media,
        // while `clips_viewer` / `clips_video` is Instagram Reels!
        private val REEL_VIEW_ID_KEYWORDS = listOf(
            "clips_viewer",
            "clips_video",
            "clips_item",
            "reel_recycler",
            "reel_player",
            "reel_watch",
            "shorts_player",
            "shorts_page",
            "shorts_container",
            "fb_shorts",
            "short_form_video"
        )

        // View IDs that definitively belong to Inbox, Direct Messages, Chat threads, Stories, or Gallery viewers
        private val INBOX_OR_CHAT_VIEW_ID_KEYWORDS = listOf(
            "direct_",
            "row_inbox",
            "inbox_",
            "thread_",
            "row_thread",
            "message_list",
            "message_composer",
            "row_message",
            "chat_",
            "conversation",
            "composer",
            "reel_viewer",        // Instagram Stories & Direct Inbox disappearing photo/video viewer
            "story_viewer",
            "raven_",             // Instagram Direct disappearing photo/video camera & viewer
            "media_viewer",
            "gallery"
        )

        // On-screen text or content descriptions that indicate an Inbox chat, DM media viewer, Story, or regular feed
        private val INBOX_OR_NON_REEL_PHRASES = listOf(
            "send message",
            "send a message",
            "write a message",
            "type a message",
            "message...",
            "reply to ",
            "reply...",
            "audio call",
            "video call",
            "voice message",
            "active now",
            "active today",
            "typing...",
            " your story",
            "add to story",
            "what's on your mind",
            "photo by "
        )

        private val REEL_OVERLAY_PHRASES = listOf(
            "original audio",
            "original sound",
            "use audio",
            "remix this",
            "reels and short videos",
            "reel player",
            "shorts player"
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

        // Hide overlay immediately when user is in Messenger, WhatsApp, Gallery, Home Launcher, Settings, or ReelGuard
        if (pkg == packageName ||
            pkg.contains("launcher") ||
            pkg.contains("miui.home") ||
            pkg.contains("oppo.launcher") ||
            pkg.contains("oneplus.launcher") ||
            pkg.contains("gallery") ||
            EXCLUDED_PACKAGES.contains(pkg)
        ) {
            if (isInReelMode) {
                exitReelMode()
            }
            return
        }

        val now = System.currentTimeMillis()

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            lastClickTimeMs = now
            mainHandler.removeCallbacks(verifyReelChangeRunnable)
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            handleScrollEvent(event, pkg, now)
            return
        }

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

        if (now - lastCountedTimeMs >= 3000L) {
            lastCountedTimeMs = now
            recordAndBroadcastReel(pkg)
        } else {
            showOverlayForPackage(pkg)
        }
    }

    private fun handleScrollEvent(event: AccessibilityEvent, pkg: String, now: Long) {
        val clsName = event.className?.toString()?.lowercase(Locale.ROOT) ?: ""
        for (nonPager in NON_PAGER_WIDGET_CLASSES) {
            if (clsName.contains(nonPager)) return
        }

        if (event.itemCount == 100 || event.itemCount == 1000) {
            return
        }

        if (now - lastClickTimeMs < 400L) {
            return
        }

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.coerceAtLeast(1)
        val screenH = dm.heightPixels.coerceAtLeast(1)

        val sourceNode = try { event.source } catch (_: Exception) { null }
        if (sourceNode != null) {
            val srcId = sourceNode.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
            for (inboxId in INBOX_OR_CHAT_VIEW_ID_KEYWORDS) {
                if (srcId.contains(inboxId)) {
                    if (isInReelMode) exitReelMode()
                    return
                }
            }
            val bounds = Rect()
            sourceNode.getBoundsInScreen(bounds)
            if (bounds.height() < screenH * 0.60 || bounds.width() < screenW * 0.75) {
                return
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val dx = abs(event.scrollDeltaX)
            val dy = abs(event.scrollDeltaY)
            // Reject horizontal photo/carousel swipes
            if (dx > dy && dx > 30) return
            if (dy in 1..(screenH * 0.08).toInt()) return
        }

        if (!isInReelMode || currentReelPackage != pkg) {
            val root = rootInActiveWindow ?: findActiveAppRoot(pkg)
            val snapshot = analyzeWindowForReels(pkg, root)
            if (snapshot.isReelScreen) {
                enterReelMode(pkg, snapshot.reelFingerprint, now)
            }
            return
        }

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
        var hasInboxOrChatOrStorySignal = false
        var hasEditableMessageField = false
        var hasSelectedReelTab = false
        var hasSelectedNonReelTab = false
        var hasReelOverlayPhrase = false
        var hasFullScreenVerticalContainer = false
        var hasLikeAndComment = 0

        val rightRailActionYPositions = mutableListOf<Int>()
        val rightRailStaticLabels = mutableListOf<String>()
        var creatorHandleToken: String = ""

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (!queue.isEmpty() && visited < 380) {
            val node = queue.removeFirst()
            visited++

            val viewId = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
            val clsName = node.className?.toString()?.lowercase(Locale.ROOT) ?: ""
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val lowerText = text.lowercase(Locale.ROOT)
            val lowerDesc = desc.lowercase(Locale.ROOT)

            // 1. Check for Editable Text Field (Message composer / Reply bar / Search bar -> NEVER a Reel viewer!)
            if (node.isEditable || clsName.contains("edittext")) {
                hasEditableMessageField = true
            }

            // 2. Check for Inbox, Direct Message, Chat Thread, Story, or Media Viewer View IDs
            if (!hasInboxOrChatOrStorySignal && viewId.isNotEmpty()) {
                for (inboxKw in INBOX_OR_CHAT_VIEW_ID_KEYWORDS) {
                    if (viewId.contains(inboxKw)) {
                        hasInboxOrChatOrStorySignal = true
                        break
                    }
                }
            }

            // 3. Check for Inbox / DM / Story / Call / Photo phrases in text or contentDescription
            if (!hasInboxOrChatOrStorySignal) {
                for (phrase in INBOX_OR_NON_REEL_PHRASES) {
                    if (lowerText.contains(phrase) || lowerDesc.contains(phrase)) {
                        hasInboxOrChatOrStorySignal = true
                        break
                    }
                }
            }

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val w = bounds.width()
            val h = bounds.height()

            // 4. Full-screen vertical container check
            if (!hasFullScreenVerticalContainer && w >= screenW * 0.80 && h >= screenH * 0.65) {
                if (node.isScrollable || clsName.contains("pager") || clsName.contains("recycler")) {
                    hasFullScreenVerticalContainer = true
                }
            }

            // 5. Right-Rail Vertical Action Column (72%..100% width, 25%..88% height)
            if (bounds.left >= screenW * 0.72 &&
                bounds.right <= screenW &&
                bounds.top >= screenH * 0.25 &&
                bounds.bottom <= screenH * 0.88
            ) {
                if (node.isClickable && w in 24..(screenW * 0.26).toInt() && h in 24..(screenH * 0.14).toInt()) {
                    val centerY = bounds.centerY()
                    if (rightRailActionYPositions.none { abs(it - centerY) < (screenH * 0.05) }) {
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

            // 6. Creator Handle (@username or short clickable author handle in lower-left)
            if (creatorHandleToken.isEmpty() && text.length in 3..28) {
                if (text.startsWith("@") && !text.contains(" ")) {
                    creatorHandleToken = text
                } else if (node.isClickable &&
                    bounds.left < screenW * 0.65 &&
                    bounds.top >= screenH * 0.55 &&
                    bounds.bottom <= screenH * 0.90 &&
                    !TIMESTAMP_REGEX.containsMatchIn(text) &&
                    !IGNORED_UI_WORDS.contains(lowerText) &&
                    text.count { it == ' ' } <= 1
                ) {
                    creatorHandleToken = text
                }
            }

            // 7. Explicit Reel / Shorts View IDs (e.g. clips_viewer, reel_recycler, shorts_player)
            if (!hasReelViewId && viewId.isNotEmpty()) {
                for (kw in REEL_VIEW_ID_KEYWORDS) {
                    if (viewId.contains(kw)) {
                        hasReelViewId = true
                        break
                    }
                }
            }

            // 8. Selected navigation tabs
            if (node.isSelected) {
                if (lowerText == "reels" || lowerText == "shorts" || lowerText == "spotlight" ||
                    lowerDesc.contains("reels") || lowerDesc.contains("shorts")
                ) {
                    hasSelectedReelTab = true
                } else if (lowerText == "home" || lowerText == "messages" || lowerText == "chats" ||
                    lowerText == "inbox" || lowerText == "notifications" || lowerText == "profile" ||
                    lowerText == "friends" || lowerText == "settings" ||
                    lowerDesc == "home" || lowerDesc == "inbox" || lowerDesc == "direct" ||
                    lowerDesc == "messages" || lowerDesc == "profile"
                ) {
                    hasSelectedNonReelTab = true
                }
            }

            // 9. Reel overlay phrases (e.g. "original audio", "use audio", "remix this")
            if (!hasReelOverlayPhrase) {
                for (phrase in REEL_OVERLAY_PHRASES) {
                    if (lowerText.contains(phrase) || lowerDesc.contains(phrase)) {
                        hasReelOverlayPhrase = true
                        break
                    }
                }
            }

            // 10. Specific Reel engagement actions (require actual Like/Comment/Remix, not generic "Send" or "Audio" from DMs)
            if (lowerDesc.contains("like") || lowerText == "like" ||
                lowerDesc.contains("comment") || lowerText == "comment" ||
                lowerDesc.contains("remix")
            ) {
                hasLikeAndComment++
            }

            val count = node.childCount
            for (i in 0 until count) {
                val child = node.getChild(i)
                if (child != null) {
                    queue.add(child)
                }
            }
        }

        // HARD EXCLUSION: If any Inbox, Direct Message, Chat, Story, or Editable Message bar is on screen,
        // it is NEVER a Reel screen (unless an explicit `clips_viewer` / `shorts_player` ID is active without an EditText).
        val isBlockedByInboxOrStory = hasEditableMessageField || hasSelectedNonReelTab ||
                (hasInboxOrChatOrStorySignal && !hasReelViewId)

        val hasRightRailActionStack = rightRailActionYPositions.size >= 3
        val hasReelStructure = hasReelViewId ||
                hasSelectedReelTab ||
                hasReelOverlayPhrase ||
                (hasFullScreenVerticalContainer && hasRightRailActionStack && hasLikeAndComment >= 2)

        val isReelScreen = if (isBlockedByInboxOrStory) {
            false
        } else if (isDedicatedShortApp) {
            hasRightRailActionStack || hasReelOverlayPhrase || hasReelViewId
        } else {
            hasReelStructure
        }

        val fpBuilder = mutableListOf<String>()
        if (creatorHandleToken.isNotEmpty()) {
            fpBuilder.add(creatorHandleToken)
        }
        fpBuilder.addAll(rightRailStaticLabels.distinct().take(3))
        val fingerprint = fpBuilder.joinToString("|")

        return WindowReelAnalysis(
            isReelScreen = isReelScreen,
            isDefinitelyNonReelScreen = isBlockedByInboxOrStory,
            reelFingerprint = fingerprint
        )
    }

    override fun onInterrupt() {
        exitReelMode()
    }
}
