package com.reelguard.tracker.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Switches the Android Home Screen Launcher Icon in real time based on the user's
 * current Reel damage ratio (Green Healthy -> Amber Warning -> Red Brain Rot).
 */
object DynamicLauncherIconManager {

    fun updateLauncherIconForRatio(context: Context, ratio: Float) {
        val pm = context.packageManager
        val greenComp = ComponentName(context, "com.reelguard.tracker.ui.MainActivity")
        val amberComp = ComponentName(context, "com.reelguard.tracker.ui.MainActivityAmberAlias")
        val redComp = ComponentName(context, "com.reelguard.tracker.ui.MainActivityRedAlias")

        val targetActive = when {
            ratio < 0.5f -> greenComp
            ratio < 1.0f -> amberComp
            else -> redComp
        }

        for (comp in listOf(greenComp, amberComp, redComp)) {
            val desiredState = if (comp == targetActive)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED

            if (pm.getComponentEnabledSetting(comp) != desiredState) {
                pm.setComponentEnabledSetting(
                    comp,
                    desiredState,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
    }
}
