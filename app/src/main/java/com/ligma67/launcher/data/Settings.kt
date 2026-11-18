package com.ligma67.launcher.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/* ------------------------------------------------------------------------------------------
 * Every user-tweakable knob lives here. The whole object is persisted as one JSON document,
 * so adding a field is just "add it with a default" — old saves keep working.
 * ---------------------------------------------------------------------------------------- */

/** How the *app window* appears when you tap an icon. */
enum class OpenStyle(val label: String, val usesLauncherCard: Boolean = false, val isSystem: Boolean = false) {
    EXPAND("Expand from icon (Ligma67)", usesLauncherCard = true),
    SCALE_UP("Scale up from icon (system)", isSystem = true),
    CLIP_REVEAL("Clip reveal from icon (system)", isSystem = true),
    ICON_MORPH("Icon morph (system)", isSystem = true),
    ZOOM("Zoom in"),
    FADE("Fade"),
    SLIDE_UP("Slide up"),
    SLIDE_SIDE("Slide from side"),
    POP("Pop"),
    RISE("Rise from bottom"),
    SYSTEM_DEFAULT("Device default", isSystem = true),
    NONE("Instant"),
}

/** What the launcher window does underneath while the app opens (window-level). */
enum class LauncherExit(val label: String) {
    HOLD("Stay"), FADE("Fade out"), ZOOM_OUT("Zoom out"), ZOOM_IN("Zoom in"), SLIDE_AWAY("Slide away")
}

/** In-window motion of the home screen content (icons/dock) on open. */
enum class HomeMotion(val label: String) {
    NONE("None"), SCALE_DOWN("Recede"), SCALE_UP("Push through"), FADE("Fade"), BLUR("Blur & recede")
}

/** How the launcher animates when you come back home from an app. */
enum class CloseStyle(val label: String) {
    SHRINK_TO_ICON("Shrink into icon"),
    ZOOM_HOME("Zoom home in"),
    WAVE("Ripple wave"),
    FADE_HOME("Fade home in"),
    NONE("None"),
}

/** Android 14+ only: window-level animation of the closing app when home comes to front. */
enum class SystemClose(val label: String, val key: String) {
    OFF("Off (device default)", ""),
    SHRINK("Shrink", "shrink"),
    SHRINK_HARD("Shrink hard", "shrinkhard"),
    FADE("Fade", "fade"),
    SLIDE_DOWN("Slide down", "slidedown"),
    SLIDE_RIGHT("Slide right", "slideright"),
}

enum class Curve(val label: String, val windowKey: String) {
    SPRING("Spring (physics)", "emph"),
    EMPHASIZED("Emphasized", "emph"),
    STANDARD("Standard", "std"),
    DECELERATE("Decelerate", "decel"),
    OVERSHOOT("Overshoot", "over"),
    LINEAR("Linear", "lin"),
}

enum class CardFill(val label: String) { GLASS("Liquid glass"), APP_COLOR("App color"), THEME("Theme surface") }
enum class SplashStyle(val label: String) { APP_DEFAULT("App default"), ICON("Always icon"), SOLID("Solid color") }

enum class TintMode(val label: String) { AUTO("Follow wallpaper"), LIGHT("Light glass"), DARK("Dark glass"), CUSTOM("Custom tint") }
enum class WallpaperMode(val label: String) { SYSTEM("System wallpaper"), CUSTOM("Ligma67 wallpaper") }
enum class LabelColor(val label: String) { AUTO("Auto"), WHITE("White"), BLACK("Black") }
enum class IndicatorStyle(val label: String) { LIQUID("Liquid pill"), DOTS("Dots"), LINE("Line"), NONE("Hidden") }
enum class PageTransition(val label: String) {
    SLIDE("Slide"), DEPTH("Depth"), CUBE("Cube"), ZOOM("Zoom"), FADE("Fade"), CAROUSEL("Carousel"), STACK("Stack")
}
enum class DockStyle(val label: String) { FLOATING("Floating glass"), FULL("Full width"), NONE("No background") }
enum class SortMode(val label: String) { AZ("A → Z"), ZA("Z → A"), MOST_USED("Most used"), RECENT("Recently installed") }
enum class IconShape(val label: String, val cornerFraction: Float) {
    SYSTEM("Device default", 0.3f),
    SQUIRCLE("Squircle", 0.32f),
    CONTINUOUS("Continuous (iOS-like)", 0.24f),
    CIRCLE("Circle", 0.5f),
    ROUNDED("Rounded square", 0.22f),
    SQUARE("Square", 0.06f),
    TEARDROP("Teardrop", 0.4f),
}
enum class IconStyle(val label: String) {
    ORIGINAL("Original"),
    GLASS_PLATE("Glass plate"),
    CLEAR("Clear glass (mono)"),
    TINTED("Tinted (themed)"),
}
enum class FolderPreview(val label: String) { GRID_2("2 × 2"), GRID_3("3 × 3") }
enum class GestureAction(val label: String) {
    NONE("Do nothing"),
    APP_DRAWER("Open app drawer"),
    SEARCH("Open search"),
    NOTIFICATIONS("Notifications"),
    QUICK_SETTINGS("Quick settings"),
    LOCK_SCREEN("Lock screen"),
    RECENTS("Recent apps"),
    SCREENSHOT("Screenshot"),
    FIRST_PAGE("Go to first page"),
    LAUNCHER_SETTINGS("Launcher settings"),
    OPEN_APP("Open an app…"),
}

@Immutable
@Serializable
data class LauncherSettings(
    // ---------------- App open ----------------
    val openStyle: OpenStyle = OpenStyle.EXPAND,
    val openDurationMs: Int = 380,
    val openCurve: Curve = Curve.EMPHASIZED,
    val openSpringDamping: Float = 0.86f,
    val openSpringStiffness: Float = 340f,
    val launcherExit: LauncherExit = LauncherExit.HOLD,
    val homeOnOpen: HomeMotion = HomeMotion.SCALE_DOWN,
    val homeOpenScale: Float = 0.92f,
    val iconPressScale: Float = 0.88f,
    val cardFill: CardFill = CardFill.GLASS,
    val cardCornerDp: Float = -1f, // -1 = match the screen's real corner radius
    val splashStyle: SplashStyle = SplashStyle.APP_DEFAULT,

    // ---------------- App close ----------------
    val closeStyle: CloseStyle = CloseStyle.SHRINK_TO_ICON,
    val closeDurationMs: Int = 420,
    val closeCurve: Curve = Curve.SPRING,
    val closeSpringDamping: Float = 0.8f,
    val closeSpringStiffness: Float = 300f,
    val homeCloseScale: Float = 0.9f,
    val closeBlurHome: Boolean = true,
    val iconBounceOnLand: Boolean = true,
    val bounceStrength: Float = 0.5f,
    val waveStagger: Float = 0.45f,
    val systemClose: SystemClose = SystemClose.OFF,
    val systemCloseDurationMs: Int = 350,
    val animateOnUnlock: Boolean = true,
    val preciseCloseTarget: Boolean = true,

    // ---------------- Global motion ----------------
    val speedMultiplier: Float = 1f, // >1 = slower, <1 = faster
    val followSystemAnimationScale: Boolean = true,
    val sheetDamping: Float = 0.86f,
    val sheetStiffness: Float = 420f,
    val folderDamping: Float = 0.78f,
    val folderStiffness: Float = 480f,
    val haptics: Boolean = true,

    // ---------------- Liquid glass ----------------
    val glassEnabled: Boolean = true,
    val glassTintMode: TintMode = TintMode.AUTO,
    val glassCustomTint: Long = 0xFFFFFFFF,
    val glassTintAlpha: Float = 0.16f,
    val glassBlurDp: Float = 22f,
    val glassRefraction: Float = 0.6f,
    val glassDispersion: Float = 0.3f,
    val glassHighlight: Float = 0.7f,
    val glassSaturation: Float = 1.35f,
    val glassCornerDp: Float = 30f,
    val glassBorderDp: Float = 1f,
    val glassShadow: Boolean = true,
    val glassHighQuality: Boolean = true,

    // ---------------- Wallpaper ----------------
    val wallpaperMode: WallpaperMode = WallpaperMode.SYSTEM,
    val wallpaperDim: Float = 0f,
    val readSystemWallpaper: Boolean = true,
    val windowBlurDrawer: Boolean = true,

    // ---------------- Home screen ----------------
    val homeColumns: Int = 4,
    val homeRows: Int = 6,
    val homeIconDp: Float = 58f,
    val homeLabels: Boolean = true,
    val homeSidePaddingDp: Float = 10f,
    val pageIndicator: IndicatorStyle = IndicatorStyle.LIQUID,
    val pageTransition: PageTransition = PageTransition.SLIDE,
    val defaultPage: Int = 0,
    val hideStatusBar: Boolean = false,
    val autoAddNewApps: Boolean = true,
    val lockLayout: Boolean = false,
    val homeDimOnOverlay: Float = 0.25f,

    // ---------------- Labels ----------------
    val labelSizeSp: Float = 12f,
    val labelColor: LabelColor = LabelColor.AUTO,
    val labelShadow: Boolean = true,
    val labelMaxLines: Int = 1,
    val labelBold: Boolean = false,

    // ---------------- Dock ----------------
    val dockEnabled: Boolean = true,
    val dockColumns: Int = 4,
    val dockStyle: DockStyle = DockStyle.FLOATING,
    val dockIconDp: Float = 58f,
    val dockLabels: Boolean = false,
    val dockMarginDp: Float = 12f,
    val dockCornerDp: Float = 34f,
    val dockPaddingDp: Float = 12f,

    // ---------------- App drawer ----------------
    val drawerColumns: Int = 4,
    val drawerIconDp: Float = 54f,
    val drawerLabels: Boolean = true,
    val drawerSort: SortMode = SortMode.AZ,
    val drawerTintAlpha: Float = 0.28f,
    val drawerAutoKeyboard: Boolean = false,
    val drawerCloseAfterLaunch: Boolean = false,
    val drawerSearch: Boolean = true,
    val drawerWebSearch: Boolean = true,
    val hiddenApps: Set<String> = emptySet(),

    // ---------------- Icons ----------------
    val iconShape: IconShape = IconShape.SQUIRCLE,
    val iconStyle: IconStyle = IconStyle.ORIGINAL,
    val iconPack: String = "",
    val wrapLegacyIcons: Boolean = true,
    val customLabels: Map<String, String> = emptyMap(),

    // ---------------- Folders ----------------
    val folderColumns: Int = 3,
    val folderPreview: FolderPreview = FolderPreview.GRID_2,

    // ---------------- Gestures ----------------
    val swipeUp: GestureAction = GestureAction.APP_DRAWER,
    val swipeDown: GestureAction = GestureAction.NOTIFICATIONS,
    val doubleTap: GestureAction = GestureAction.LOCK_SCREEN,
    val homePress: GestureAction = GestureAction.FIRST_PAGE,
    val swipeUpApp: String = "",
    val swipeDownApp: String = "",
    val doubleTapApp: String = "",

    // ---------------- Look ----------------
    val accentAuto: Boolean = true,
    val accentColor: Long = 0xFF5B8CFF,

    // ---------------- Performance ----------------
    val maxRefreshRate: Boolean = true,
    val reduceMotion: Boolean = false,
) {
    val folderPreviewCount get() = if (folderPreview == FolderPreview.GRID_3) 9 else 4
}

/** One-tap motion presets, Home Up style. */
enum class MotionPreset(val label: String, val apply: (LauncherSettings) -> LauncherSettings) {
    ONE_UI("One UI", {
        it.copy(
            openStyle = OpenStyle.EXPAND, openCurve = Curve.EMPHASIZED, openDurationMs = 400,
            homeOnOpen = HomeMotion.SCALE_DOWN, homeOpenScale = 0.92f, cardFill = CardFill.GLASS,
            closeStyle = CloseStyle.SHRINK_TO_ICON, closeCurve = Curve.SPRING,
            closeSpringDamping = 0.86f, closeSpringStiffness = 300f, homeCloseScale = 0.9f,
            closeBlurHome = true, iconBounceOnLand = true, bounceStrength = 0.35f, speedMultiplier = 1f,
        )
    }),
    LIQUID("Liquid (iOS-like)", {
        it.copy(
            openStyle = OpenStyle.EXPAND, openCurve = Curve.SPRING, openSpringDamping = 0.82f,
            openSpringStiffness = 380f, homeOnOpen = HomeMotion.BLUR, homeOpenScale = 0.94f,
            cardFill = CardFill.GLASS, closeStyle = CloseStyle.SHRINK_TO_ICON, closeCurve = Curve.SPRING,
            closeSpringDamping = 0.72f, closeSpringStiffness = 340f, homeCloseScale = 0.92f,
            iconBounceOnLand = true, bounceStrength = 0.6f, speedMultiplier = 1f,
        )
    }),
    SNAPPY("Snappy", {
        it.copy(
            openStyle = OpenStyle.EXPAND, openCurve = Curve.STANDARD, openDurationMs = 250,
            homeOnOpen = HomeMotion.SCALE_DOWN, homeOpenScale = 0.95f, closeStyle = CloseStyle.SHRINK_TO_ICON,
            closeCurve = Curve.SPRING, closeSpringDamping = 0.95f, closeSpringStiffness = 750f,
            homeCloseScale = 0.95f, iconBounceOnLand = false, speedMultiplier = 0.85f,
        )
    }),
    BOUNCY("Bouncy", {
        it.copy(
            openStyle = OpenStyle.EXPAND, openCurve = Curve.SPRING, openSpringDamping = 0.6f,
            openSpringStiffness = 320f, closeStyle = CloseStyle.WAVE, closeCurve = Curve.SPRING,
            closeSpringDamping = 0.55f, closeSpringStiffness = 280f, iconBounceOnLand = true,
            bounceStrength = 1f, waveStagger = 0.6f, homeCloseScale = 0.82f,
        )
    }),
    CINEMATIC("Cinematic", {
        it.copy(
            openStyle = OpenStyle.EXPAND, openCurve = Curve.EMPHASIZED, openDurationMs = 700,
            homeOnOpen = HomeMotion.BLUR, homeOpenScale = 0.88f, closeStyle = CloseStyle.SHRINK_TO_ICON,
            closeCurve = Curve.EMPHASIZED, closeDurationMs = 700, homeCloseScale = 0.85f, closeBlurHome = true,
        )
    }),
    PIXEL("Pixel-like", {
        it.copy(
            openStyle = OpenStyle.SCALE_UP, homeOnOpen = HomeMotion.SCALE_DOWN, homeOpenScale = 0.95f,
            closeStyle = CloseStyle.ZOOM_HOME, closeCurve = Curve.STANDARD, closeDurationMs = 350,
            homeCloseScale = 0.92f, launcherExit = LauncherExit.HOLD,
        )
    }),
    INSTANT("Instant", {
        it.copy(
            openStyle = OpenStyle.NONE, homeOnOpen = HomeMotion.NONE, closeStyle = CloseStyle.NONE,
            launcherExit = LauncherExit.HOLD, iconBounceOnLand = false,
        )
    }),
}
