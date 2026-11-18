# Ligma67Launcher

A Liquid Glass Android home screen with One UI Home Up–style, fully tunable app open/close
animations that stay smooth even when you spam apps open and closed.

Kotlin + Jetpack Compose · Android 8.0+ (API 26) · targets API 36 · ~1.9 MB release APK

## Download

**[⬇ Download the latest APK](https://github.com/antiobd92/Ligma67Launcher/releases/latest)**

Works on any phone running Android 8.0 or newer, from any brand and any CPU type.

1. Open the link on your phone and download `Ligma67Launcher-vX.Y.Z.apk`.
2. Open the file. If Android asks, allow your browser or file manager to **install unknown apps**.
3. If Play Protect warns about an unknown app, tap **More details › Install anyway**. It warns
   because the app isn't from the Play Store.
4. Press Home and choose **Ligma67Launcher**, or open **Ligma67 Settings** and tap
   **Set as default home app**. On Xiaomi, use Settings › Apps › Default apps › Home screen.

## Build from source

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Use the **release** build. It's R8-optimized and ships a baseline profile, and Compose is several
times smoother there than in debug. Release builds are signed with the key described in
`~/Ligma67Keys/keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`), or
`-Pligma67.keystore=<path>`. Without one, they fall back to the debug key, so anyone can build.
Requires JDK 17+ (Android Studio's bundled JBR works).

## What's in it

**Open/close animations (Settings › Animations)**
- **Expand from icon:** the launcher draws a liquid-glass (or app-color) card that grows from the
  tapped icon while the app window fades in on top.
- **Window styles** handed to Android: scale-up, clip-reveal, icon morph, zoom, fade, slide up,
  slide sideways, pop, rise, device default, instant. Every style × curve × duration is generated at
  build time (691 resources, see `GenerateWindowAnimsTask` in `app/build.gradle.kts`).
- **Close:** card shrinks into the icon you came from, plus zoom, ripple wave, fade, or none. Optional
  home blur and icon bounce on landing.
- **Curves:** physics spring (damping/stiffness) or emphasized / standard / decelerate / overshoot /
  linear with 100–800 ms durations. Global speed multiplier. Optionally follows the system animator
  scale.
- **Presets:** One UI, Liquid (iOS-like), Snappy, Bouncy, Cinematic, Pixel-like, Instant.
- **Live preview** in settings with real icons, including a **Spam test** button.
- **Precise close target:** shrinks into the app you actually left, using the optional accessibility
  service or Usage access.
- **Android 14+ system close transition (beta):** asks the system to animate the closing app window
  itself.

**Why it doesn't lag when spammed**
- Apps launch on the same frame as the tap. Nothing waits for an animation.
- Every animation is an interruptible `Motion` that retargets from its current value *and velocity*,
  so open→close→open reverses mid-flight instead of restarting.
- Animated values are read only inside `graphicsLayer`/draw lambdas, so there is no recomposition
  per frame.
- Motions run on `AndroidUiDispatcher.Main` (Choreographer vsync), and the display is asked for its
  highest refresh rate (many Xiaomi/OPPO builds cap launchers at 60 Hz).
- Icons are rendered once off the main thread and pre-uploaded to the GPU.
- The wallpaper blur is computed once per wallpaper change, not per frame.
- The widget host keeps listening while the launcher is in the background, so returning home doesn't
  replay widget updates during the close animation.

**Liquid Glass (Settings › Liquid glass / Wallpaper)**
- Android 13+: an AGSL shader samples the pre-blurred wallpaper with SDF edge refraction, chromatic
  dispersion and a two-sided specular rim. Older versions use the same backdrop with a tint and
  gradient rim.
- Tunable tint (auto / light / dark / custom), frost, refraction, dispersion, highlight, saturation,
  border and corners.
- True glass needs wallpaper pixels. Either pick a **Ligma67 wallpaper** (optionally also set as the
  system wallpaper) or allow wallpaper access. Without that, glass is tint-only, and the drawer uses
  Android 12's cross-window blur.

**Launcher features**
- Paged grid with drag & drop, folders (drop an app onto an app), widgets (add, move, resize, remove),
  pinned shortcuts ("Add to Home screen" from Chrome etc.) and app shortcuts in the long-press menu.
- Page transitions: slide, depth, cube, zoom, fade, carousel, stack. Liquid / dots / line page
  indicator.
- Glass dock (floating / full / none) with 3–7 icons.
- App drawer: swipe up, follows your finger, predictive back. Search with web fallback, four sort
  modes, hidden apps, custom labels.
- Icons: 7 shapes, styles (original, glass plate, clear glass mono, tinted/themed), ADW/Nova icon packs,
  legacy icon wrapping, work-profile badges.
- Gestures: swipe up/down, double tap, Home-on-home → drawer, search, notifications, quick settings,
  lock, recents, screenshot, first page, settings, or any app.
- Backup/restore of all settings and the layout as JSON.

## Compatibility notes

- Everything the launcher draws itself behaves the same on Samsung, Xiaomi/HyperOS, Nothing,
  OPPO/realme/OnePlus, vivo, Pixel, etc.: the expanding/shrinking card, home zoom, blur, wave,
  bounce, glass, drawer and folders.
- Window-level animations are requests to Android. Some skins (notably MIUI/HyperOS) substitute their
  own for third-party launchers.
- With gesture navigation, the system animates the app window while you swipe home. No third-party
  launcher can drive that without root/QuickSwitch. Ligma67 then lands it into the icon.
- Lock screen, recents and screenshot gestures need the optional **Ligma67 Gestures** accessibility
  service. It never reads screen content.

## Project layout

```
app/src/main/java/com/ligma67/launcher/
  LauncherApp.kt / LauncherActivity.kt / SettingsActivity.kt / AddItemActivity.kt
  anim/    Motion (interruptible springs), LaunchAnimator (card + home motion), WindowAnimations
  glass/   Backdrop (wallpaper sampling + blur), LiquidGlass (AGSL shader, painter, Modifier.glass)
  data/    Settings model, repositories (apps, icons, icon packs, home layout)
  system/  System actions, accessibility gesture service, widget host
  ui/home  Home screen, drawer, icons, folders, menus, drag & drop
  ui/settings  Settings screens + live animation preview
```
