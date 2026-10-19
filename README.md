# ReelGuard — Short-Form Video Counter & Brain Health Overlay (Android)

ReelGuard is a native Android application (Kotlin) that counts every Reel or Short you watch across **Instagram, TikTok, YouTube Shorts, Facebook, Snapchat, and other apps**, displays a **live floating counter at the top of your phone screen** with the **real-time active app icon** and **Green → Amber → Crimson Red** transition, animates a **progressive Brain Health ("Brain Rot") indicator**, dynamically updates the **Android Home Screen App Launcher Icon** (`activity-alias` switching between Healthy Green, Warning Amber, and Critical Red icons), and provides a **Day / Week / Month analytics dashboard** with real native app icons.

---

## How to Get Your `.apk` File (2 Easy Ways)

### Option A: Build the APK Online in 2 Minutes (No PC or Android Studio Needed!)
This project includes a ready-to-run **GitHub Actions workflow** (`.github/workflows/build-apk.yml`) that compiles the `.apk` in the cloud for free:
1. Create a free repository on [GitHub](https://github.com/new) and upload the contents of this ZIP folder.
2. Click the **Actions** tab in your GitHub repository.
3. Select the **Build ReelGuard APK** workflow run, scroll to **Artifacts** at the bottom of the summary page, and download **`ReelGuard-debug.apk`** directly to your phone!

### Option B: Build Locally in Android Studio
1. Open **Android Studio** (Ladybug / Koala or newer) and select **Open** -> choose this project folder.
2. Wait for Gradle Sync to finish (Android Studio will automatically download the Gradle wrapper and Android SDK dependencies).
3. In the top menu, click **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. Click **Locate** in the notification popup to grab `app/build/outputs/apk/debug/app-debug.apk` and install it on your phone.

---

## Real-Time Icons & Key Features
- **Real-Time Native App Icons (`AppIconRenderer.kt` & `PackageManager`)**: Queries `PackageManager.getApplicationIcon(packageName)` to display the exact live app icon from the user's device in the Top Floating Overlay and Dashboard, backed by precision vector canvas drawables for Instagram, TikTok, YouTube Shorts, Facebook, and Other Apps.
- **Real-Time Dynamic Home Screen Launcher Icon (`DynamicLauncherIconManager.kt`)**: Automatically updates the ReelGuard app icon on the phone's home screen between **Healthy Green (`ic_launcher_green`)**, **Warning Amber (`ic_launcher_amber`)**, and **Critical Brain-Rot Red (`ic_launcher_red`)** as your daily reel count increases.
- **`OverlayService.kt` + `BrainHealthView.kt`**: Pins a floating pill at the top of the phone display showing the real-time active app icon, the animated Brain Health canvas, and the Green-to-Red reel counter.
