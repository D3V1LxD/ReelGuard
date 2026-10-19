package com.reelguard.tracker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.reelguard.tracker.data.ReelDatabaseHelper
import java.util.Locale

class ReelAccessibilityService : AccessibilityService() {

    private lateinit var dbHelper: ReelDatabaseHelper
    private var lastCountedTimestamp: Long = 0L
    private var lastScrollIndex: Int = -1
    private var lastPackageName: String = ""

    companion object {
        private const val DEBOUNCE_MS = 1600L

        private val DEDICATED_SHORT_VIDEO_PACKAGES = setOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically.go"
        )

        private val HYBRID_SOCIAL_PACKAGES = setOf(
            "com.instagram.android",
            "com.instagram.lite",
            "com.facebook.katana",
            "com.facebook.lite",
            "com.google.android.youtube",
            "com.snapchat.android",
            "com.twitter.android"
        )

        private val REEL_INDICATORS = listOf(
            "reel", "shorts", "clips_viewer", "reel_viewer", "shorts_player",
            "spotlight", "vertical_video", "tiktok"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        dbHelper = ReelDatabaseHelper(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val now = System.currentTimeMillis()
            if (now - lastCountedTimestamp < DEBOUNCE_MS) return

            val isShortVideoScreen = when {
                DEDICATED_SHORT_VIDEO_PACKAGES.contains(pkg) -> true
                HYBRID_SOCIAL_PACKAGES.contains(pkg) -> isCurrentlyInReelOrShortsView(event)
                else -> isGenericVerticalPagerReelView(event)
            }

            if (isShortVideoScreen) {
                val currentIdx = event.fromIndex
                if (currentIdx == -1 || currentIdx != lastScrollIndex || pkg != lastPackageName) {
                    lastCountedTimestamp = now
                    lastScrollIndex = currentIdx
                    lastPackageName = pkg
                    dbHelper.recordReelView(this, pkg)
                    sendBroadcast(Intent(OverlayService.ACTION_UPDATE_COUNTER).apply {
                        setPackage(packageName)
                    })
                }
            }
        }
    }

    private fun isCurrentlyInReelOrShortsView(event: AccessibilityEvent): Boolean {
        val className = event.className?.toString()?.lowercase(Locale.ROOT) ?: ""
        val isPagerOrList = className.contains("viewpager") ||
                className.contains("recyclerview") ||
                className.contains("scroll")

        if (!isPagerOrList) return false

        val root = rootInActiveWindow ?: return true
        return containsReelMarkers(root, 0)
    }

    private fun isGenericVerticalPagerReelView(event: AccessibilityEvent): Boolean {
        val className = event.className?.toString()?.lowercase(Locale.ROOT) ?: ""
        if (!className.contains("viewpager")) return false
        val root = rootInActiveWindow ?: return false
        return containsReelMarkers(root, 0)
    }

    private fun containsReelMarkers(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 6) return false
        val viewId = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
        val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""

        for (marker in REEL_INDICATORS) {
            if (viewId.contains(marker) || desc.contains(marker) || text == "reels" || text == "shorts") {
                return true
            }
        }
        for (i in 0 until node.childCount) {
            if (containsReelMarkers(node.getChild(i), depth + 1)) {
                return true
            }
        }
        return false
    }

    override fun onInterrupt() {}
}
