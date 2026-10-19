# ReelGuard

ReelGuard is an Android app that helps you stay aware of short-form video
consumption. It detects Reel and Shorts feeds while you use supported apps,
counts confirmed page transitions, and shows the current total without
requiring you to leave the app you are watching.

The project is written in Kotlin and targets Android 8.0 (API 26) and newer.

## What ReelGuard does

### Confirmed Reel and Shorts counting

ReelGuard uses an Android Accessibility Service to recognize short-form video
surfaces in apps such as:

- Instagram
- Facebook and Facebook Lite
- YouTube and YouTube Shorts
- TikTok
- Snapchat
- Other apps whose accessibility tree exposes a recognizable short-video feed

The detector is designed to avoid common false positives. It does not count a
tap, a subtitle update, a timer change, or a progress-bar scroll as a new
video. It checks the active window, full-screen scrolling geometry, feed
labels, page indices, swipe distance, and stable creator/caption text. Counts
are also protected by a transition cooldown so one fling does not produce
multiple entries.

Detection depends on the accessibility information exposed by each app.
Private or custom-rendered screens may not expose enough information to be
recognized reliably.

### Live top-of-screen overlay

When a Reel or Short feed is active, a foreground service displays a movable
pill at the top of the screen containing:

- The current day's Reel count
- An animated brain-health indicator
- The active app's installed icon when it can be resolved
- A color-coded status label

The overlay is hidden while you are on normal feeds, chats, the home screen,
Settings, or the ReelGuard dashboard. Tapping the pill opens the dashboard,
and dragging it moves it horizontally or vertically.

### Brain-health status

The dashboard and overlay compare today's count with a configurable daily
red-alert threshold. The default threshold is **50 Reels** and can be changed
from 10 to 200.

The status progresses through these states:

| Usage ratio | Status |
| --- | --- |
| Below 25% | Healthy & Sharp |
| 25% to below 50% | Mild Attention Drift |
| 50% to below 75% | Dopamine Overload |
| 75% to below 100% | High Brain Fog Warning |
| 100% or more | Critical Brain Rot |

The indicator transitions from green to amber to red. The launcher icon also
switches between the green, amber, and red variants based on the ratio.

### Dashboard and analytics

The dashboard provides:

- Today's total
- Rolling 7-day total
- Rolling 30-day total
- A selectable daily threshold
- Platform breakdown with counts and percentages
- A daily trend view
- The installed icon for each recognized platform when available

Counts are stored locally in an SQLite database. Each record includes its
timestamp, date, package name, and resolved platform name.

## Required permissions

ReelGuard needs the following Android capabilities:

1. **Display over other apps** — required for the floating counter.
2. **Accessibility Service** — required to identify Reel/Short screens and
   confirmed swipe transitions.
3. **Notifications** on Android 13 and newer — used for the foreground-service
   notification.

Open ReelGuard and use the two setup buttons on the dashboard:

1. **Enable Overlay** to grant display-over-other-apps access.
2. **Enable Detector** to open Accessibility settings and enable the
   `ReelGuard Reel & Shorts Detector` service.

The accessibility service is used for screen-structure and interaction
signals. ReelGuard does not read private messages or intentionally store
personal text. Accessibility-based detection is subject to Android and
platform-specific behavior.

## Build the APK

### Build locally with Android Studio

1. Open the project directory in Android Studio Ladybug, Koala, or newer.
2. Allow Gradle to sync and download the Android SDK and dependencies.
3. Select **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. Install the generated debug APK from:

   `app/build/outputs/apk/debug/app-debug.apk`

The project uses:

- Kotlin/JVM target 17
- Android compile SDK 34
- Minimum SDK 26
- AndroidX Core KTX 1.13.1
- AndroidX AppCompat 1.7.0
- Material Components 1.12.0

### Build with GitHub Actions

The repository includes
`.github/workflows/build-apk.yml`. To build online:

1. Push the project to a GitHub repository.
2. Open the repository's **Actions** tab.
3. Run or select **Build ReelGuard APK**.
4. Download the `ReelGuard-debug.apk` artifact from the workflow summary.

## Project structure

| Path | Purpose |
| --- | --- |
| `app/src/main/java/com/reelguard/tracker/ui/MainActivity.kt` | Dashboard, permission buttons, threshold, and analytics UI |
| `app/src/main/java/com/reelguard/tracker/service/ReelAccessibilityService.kt` | Feed detection, transition validation, counting, and overlay broadcasts |
| `app/src/main/java/com/reelguard/tracker/service/OverlayService.kt` | Foreground service and movable top overlay |
| `app/src/main/java/com/reelguard/tracker/data/ReelDatabaseHelper.kt` | Local SQLite storage and analytics queries |
| `app/src/main/java/com/reelguard/tracker/ui/BrainHealthView.kt` | Animated green/amber/red brain indicator |
| `app/src/main/java/com/reelguard/tracker/ui/AppIconView.kt` | Installed app icon lookup and vector fallbacks |
| `app/src/main/java/com/reelguard/tracker/ui/DynamicLauncherIconManager.kt` | Green, amber, and red launcher switching |
| `app/src/main/AndroidManifest.xml` | Permissions, activities, aliases, and services |

## Data and privacy

ReelGuard stores usage records locally on the device. A record contains the
time of a confirmed Reel/Short transition, the source package name, and a
platform label. The app does not include a network API or account system, and
the project does not upload viewing records.

Because Accessibility Service access is powerful, enable it only if you are
comfortable with Android's accessibility model. You can disable the service or
overlay permission at any time from Android Settings.

## License

No license file is currently included in this repository. Add a license before
redistributing the project or publishing derivative work.
