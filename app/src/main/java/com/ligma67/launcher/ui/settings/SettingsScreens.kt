package com.ligma67.launcher.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.ligma67.launcher.SettingsActivity
import com.ligma67.launcher.anim.WindowAnimations
import com.ligma67.launcher.data.CardFill
import com.ligma67.launcher.data.CloseStyle
import com.ligma67.launcher.data.Curve
import com.ligma67.launcher.data.DockStyle
import com.ligma67.launcher.data.FolderPreview
import com.ligma67.launcher.data.GestureAction
import com.ligma67.launcher.data.HomeItem
import com.ligma67.launcher.data.HomeLayout
import com.ligma67.launcher.data.HomeMotion
import com.ligma67.launcher.data.IconPack
import com.ligma67.launcher.data.IconPackInfo
import com.ligma67.launcher.data.IconRepository
import com.ligma67.launcher.data.IconShape
import com.ligma67.launcher.data.IconStyle
import com.ligma67.launcher.data.IndicatorStyle
import com.ligma67.launcher.data.LabelColor
import com.ligma67.launcher.data.LauncherExit
import com.ligma67.launcher.data.LauncherJson
import com.ligma67.launcher.data.LauncherSettings
import com.ligma67.launcher.data.MotionPreset
import com.ligma67.launcher.data.OpenStyle
import com.ligma67.launcher.data.PageTransition
import com.ligma67.launcher.data.SortMode
import com.ligma67.launcher.data.SplashStyle
import com.ligma67.launcher.data.SystemClose
import com.ligma67.launcher.data.TintMode
import com.ligma67.launcher.data.WallpaperMode
import com.ligma67.launcher.data.normalizeForSearch
import com.ligma67.launcher.glass.glass
import com.ligma67.launcher.launcherApp
import com.ligma67.launcher.system.SystemActions
import com.ligma67.launcher.ui.home.rememberAppIcon
import com.ligma67.launcher.ui.theme.LauncherTheme
import com.ligma67.launcher.ui.theme.LocalLauncherColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class LauncherBackup(val version: Int = 1, val settings: LauncherSettings, val layout: HomeLayout)

private typealias Update = ((LauncherSettings) -> LauncherSettings) -> Unit

@Composable
fun SettingsRoot(activity: SettingsActivity, initial: String?) {
    val app = activity.launcherApp
    val s by app.settings.state.collectAsState()
    val tone by app.backdrop.tone.collectAsState()
    val blurred by app.backdrop.blurred.collectAsState()
    val sharp by app.backdrop.sharp.collectAsState()
    var stack by remember { mutableStateOf(listOfNotNull("main", initial)) }
    val update: Update = { f -> app.settings.update(f) }
    fun go(screen: String) { stack = stack + screen }
    fun back() { if (stack.size > 1) stack = stack.dropLast(1) else activity.finish() }

    LauncherTheme(s, tone, blurred) {
        val c = LocalLauncherColors.current
        Box(Modifier.fillMaxSize()) {
            sharp?.let { img ->
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height), dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low)
                    },
                )
            }
            Box(Modifier.fillMaxSize().background(if (c.darkGlass) Color(0x8C000000) else Color(0x73FFFFFF)))
            AnimatedContent(
                targetState = stack.last(),
                transitionSpec = {
                    val forward = targetState != "main" && initialState == "main" || stack.size > 1 && targetState == stack.last()
                    if (forward) {
                        (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 6 } + fadeOut())
                    } else {
                        (slideInHorizontally { -it / 6 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
                    }
                },
                label = "settings",
            ) { screen ->
                val onBack = { back() }
                when (screen) {
                    "animations" -> AnimationsScreen(activity, s, update, onBack)
                    "glass" -> GlassScreen(s, update, onBack, blurred != null)
                    "wallpaper" -> WallpaperScreen(activity, s, update, onBack)
                    "home" -> HomeScreenSettings(s, update, onBack)
                    "dock" -> DockScreen(s, update, onBack)
                    "drawer" -> DrawerScreen(s, update, onBack) { go("hidden") }
                    "hidden" -> HiddenAppsScreen(activity, s, update, onBack)
                    "icons" -> IconsScreen(activity, s, update, onBack)
                    "folders" -> FoldersScreen(s, update, onBack)
                    "gestures" -> GesturesScreen(activity, s, update, onBack)
                    "performance" -> PerformanceScreen(s, update, onBack)
                    "backup" -> BackupScreen(activity, onBack)
                    "about" -> AboutScreen(activity, onBack)
                    else -> MainScreen(activity) { go(it) }
                }
            }
        }
        BackHandler(enabled = true) { back() }
    }
}

/* ------------------------------------------- Main ------------------------------------------- */

@Composable
private fun MainScreen(activity: Activity, go: (String) -> Unit) {
    val c = LocalLauncherColors.current
    var isDefault by remember { mutableStateOf(SystemActions.isDefaultLauncher(activity)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { isDefault = SystemActions.isDefaultLauncher(activity) }
    SettingsPage("Ligma67", onBack = null) {
        item {
            Column(Modifier.fillMaxWidth().glass(28.dp).padding(20.dp)) {
                Text("Ligma67Launcher", color = c.onGlass, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Liquid glass · buttery motion · yours to tune", color = c.onGlassDim, fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                if (isDefault) {
                    Text("✓ Ligma67 is your home app", color = c.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Text(
                        "Set as default home app",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(c.accent)
                            .clickable { SystemActions.requestDefaultLauncher(activity) }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }
        }
        item {
            Group("Motion") {
                ActionRow("App open & close animations", "Styles, curves, springs, speed, presets", Icons.Filled.PlayArrow) { go("animations") }
                ActionRow("Gestures", "Swipes, double tap, home button", Icons.Filled.ThumbUp) { go("gestures") }
            }
        }
        item {
            Group("Look") {
                ActionRow("Liquid glass", "Tint, frost, refraction, highlights", Icons.Filled.Star) { go("glass") }
                ActionRow("Wallpaper", "System or Ligma67 wallpaper, dim", Icons.Filled.Face) { go("wallpaper") }
                ActionRow("Icons & labels", "Shape, style, icon packs, text", Icons.Filled.Favorite) { go("icons") }
            }
        }
        item {
            Group("Layout") {
                ActionRow("Home screen", "Grid, pages, transitions", Icons.Filled.Home) { go("home") }
                ActionRow("Dock", "Style, size, icons", Icons.Filled.Menu) { go("dock") }
                ActionRow("App drawer", "Columns, sorting, search, hidden apps", Icons.AutoMirrored.Filled.List) { go("drawer") }
                ActionRow("Folders", "Grid and preview", Icons.Filled.Edit) { go("folders") }
            }
        }
        item {
            Group("System") {
                ActionRow("Performance", "Refresh rate, quality, motion", Icons.Filled.Build) { go("performance") }
                ActionRow("Backup & restore", "Export / import everything", Icons.Filled.Share) { go("backup") }
                ActionRow("About & compatibility", null, Icons.Filled.Info) { go("about") }
            }
        }
    }
}

/* ---------------------------------------- Animations ---------------------------------------- */

@Composable
private fun SpringOrDuration(
    curve: Curve, durationMs: Int, damping: Float, stiffness: Float,
    onDuration: (Int) -> Unit, onDamping: (Float) -> Unit, onStiffness: (Float) -> Unit,
) {
    if (curve == Curve.SPRING) {
        SliderRow("Damping", damping, 0.3f..1.2f, summary = "Lower = bouncier", format = ::x2) { onDamping(it) }
        SliderRow("Stiffness", stiffness, 80f..1500f, summary = "Higher = snappier", format = { "${it.roundToInt()}" }) { onStiffness(it) }
    } else {
        SliderRow("Duration", durationMs.toFloat(), 100f..800f, steps = 13, format = ::ms) { onDuration(it.roundToInt()) }
    }
}

@Composable
private fun AnimationsScreen(activity: Activity, s: LauncherSettings, update: Update, onBack: () -> Unit) {
    var preset by remember { mutableStateOf<MotionPreset?>(null) }
    var usage by remember { mutableStateOf(SystemActions.hasUsageAccess(activity)) }
    var a11y by remember { mutableStateOf(SystemActions.isAccessibilityEnabled(activity)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        usage = SystemActions.hasUsageAccess(activity)
        a11y = SystemActions.isAccessibilityEnabled(activity)
    }
    SettingsPage("Animations", onBack) {
        item { Group { Spacer(Modifier.height(14.dp)); AnimationPreview(s); Spacer(Modifier.height(10.dp)) } }
        item {
            Group("Presets") {
                ChipsRow(MotionPreset.entries.toList(), preset, { it.label }) { p ->
                    preset = p
                    update(p.apply)
                }
            }
        }
        item {
            Group("Opening apps") {
                ChoiceRow("Animation style", s.openStyle, OpenStyle.entries.toList(), label = { it.label }) { v -> update { it.copy(openStyle = v) } }
                ChoiceRow("Curve", s.openCurve, Curve.entries.toList(), label = { it.label }) { v -> update { it.copy(openCurve = v) } }
                SpringOrDuration(
                    s.openCurve, s.openDurationMs, s.openSpringDamping, s.openSpringStiffness,
                    { v -> update { it.copy(openDurationMs = v) } },
                    { v -> update { it.copy(openSpringDamping = v) } },
                    { v -> update { it.copy(openSpringStiffness = v) } },
                )
                if (s.openStyle.usesLauncherCard) {
                    ChoiceRow("Card look", s.cardFill, CardFill.entries.toList(), label = { it.label }) { v -> update { it.copy(cardFill = v) } }
                    SliderRow(
                        "Card corner radius", s.cardCornerDp, -1f..60f,
                        summary = "Corner of the card when full-screen",
                        format = { if (it < 0f) "Match screen" else dp(it) },
                    ) { v -> update { it.copy(cardCornerDp = if (v < 0f) -1f else v) } }
                }
                if (!s.openStyle.usesLauncherCard && !s.openStyle.isSystem && s.openStyle != OpenStyle.NONE) {
                    ChoiceRow("Home window while opening", s.launcherExit, LauncherExit.entries.toList(), label = { it.label }) { v -> update { it.copy(launcherExit = v) } }
                }
                ChoiceRow("Home screen motion", s.homeOnOpen, HomeMotion.entries.toList(), label = { it.label }) { v -> update { it.copy(homeOnOpen = v) } }
                if (s.homeOnOpen != HomeMotion.NONE && s.homeOnOpen != HomeMotion.FADE) {
                    SliderRow("Recede amount", s.homeOpenScale, 0.75f..1f, format = ::pct) { v -> update { it.copy(homeOpenScale = v) } }
                }
                SliderRow("Icon press squish", s.iconPressScale, 0.7f..1f, format = ::pct) { v -> update { it.copy(iconPressScale = v) } }
                if (Build.VERSION.SDK_INT >= 33) {
                    ChoiceRow("Splash screen", s.splashStyle, SplashStyle.entries.toList(), label = { it.label }) { v -> update { it.copy(splashStyle = v) } }
                }
            }
        }
        item {
            Group("Closing apps (back to home)") {
                ChoiceRow("Animation style", s.closeStyle, CloseStyle.entries.toList(), label = { it.label }) { v -> update { it.copy(closeStyle = v) } }
                ChoiceRow("Curve", s.closeCurve, Curve.entries.toList(), label = { it.label }) { v -> update { it.copy(closeCurve = v) } }
                SpringOrDuration(
                    s.closeCurve, s.closeDurationMs, s.closeSpringDamping, s.closeSpringStiffness,
                    { v -> update { it.copy(closeDurationMs = v) } },
                    { v -> update { it.copy(closeSpringDamping = v) } },
                    { v -> update { it.copy(closeSpringStiffness = v) } },
                )
                SliderRow("Home zoom-in start", s.homeCloseScale, 0.6f..1f, format = ::pct) { v -> update { it.copy(homeCloseScale = v) } }
                if (s.closeStyle == CloseStyle.WAVE) {
                    SliderRow("Wave spread", s.waveStagger, 0f..1f, format = ::pct) { v -> update { it.copy(waveStagger = v) } }
                }
                SwitchRow("Blur home while closing", s.closeBlurHome, if (Build.VERSION.SDK_INT < 31) "Needs Android 12+" else null) { v -> update { it.copy(closeBlurHome = v) } }
                SwitchRow("Icon bounce on landing", s.iconBounceOnLand) { v -> update { it.copy(iconBounceOnLand = v) } }
                if (s.iconBounceOnLand) {
                    SliderRow("Bounce strength", s.bounceStrength, 0f..1.5f, format = ::pct) { v -> update { it.copy(bounceStrength = v) } }
                }
                SwitchRow("Animate when unlocking", s.animateOnUnlock) { v -> update { it.copy(animateOnUnlock = v) } }
                SwitchRow(
                    "Precise close target", s.preciseCloseTarget,
                    "Shrink into the icon of the app you actually left, even if you switched apps",
                ) { v -> update { it.copy(preciseCloseTarget = v) } }
                if (s.preciseCloseTarget) {
                    ActionRow(
                        "Ligma67 Gestures service",
                        if (a11y) "On — instant & most accurate" else "Off — tap to enable (recommended)",
                    ) { SystemActions.openAccessibilitySettings(activity) }
                    if (!a11y) {
                        ActionRow("Usage access", if (usage) "Granted" else "Not granted — fallback method") { SystemActions.openUsageAccess(activity) }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            item {
                Group("System close transition (beta)") {
                    InfoText("Asks Android to animate the closing app window itself. Honored with button navigation on most Android 14+ builds; gesture navigation is controlled by the system.")
                    ChoiceRow("Window animation", s.systemClose, SystemClose.entries.toList(), label = { it.label }) { v -> update { it.copy(systemClose = v) } }
                    if (s.systemClose != SystemClose.OFF) {
                        SliderRow("Duration", s.systemCloseDurationMs.toFloat(), 100f..800f, steps = 13, format = ::ms) { v -> update { it.copy(systemCloseDurationMs = v.roundToInt()) } }
                    }
                }
            }
        }
        item {
            Group("Global motion") {
                SliderRow("Animation speed", s.speedMultiplier, 0.5f..2f, summary = "Lower = faster, higher = slower", format = { x2(it) + "×" }) { v -> update { it.copy(speedMultiplier = v) } }
                SwitchRow("Follow system animation scale", s.followSystemAnimationScale, "Developer options › Animator duration scale") { v -> update { it.copy(followSystemAnimationScale = v) } }
                SliderRow("Drawer spring damping", s.sheetDamping, 0.4f..1.2f, format = ::x2) { v -> update { it.copy(sheetDamping = v) } }
                SliderRow("Drawer spring stiffness", s.sheetStiffness, 100f..1500f, format = { "${it.roundToInt()}" }) { v -> update { it.copy(sheetStiffness = v) } }
                SliderRow("Folder spring damping", s.folderDamping, 0.4f..1.2f, format = ::x2) { v -> update { it.copy(folderDamping = v) } }
                SliderRow("Folder spring stiffness", s.folderStiffness, 100f..1500f, format = { "${it.roundToInt()}" }) { v -> update { it.copy(folderStiffness = v) } }
                SwitchRow("Reduce motion", s.reduceMotion) { v -> update { it.copy(reduceMotion = v) } }
            }
        }
        item {
            Group {
                ActionRow("Test on a real app", "Opens system Settings with your window animation", Icons.Filled.PlayArrow) {
                    val dm = activity.resources.displayMetrics
                    val r = android.graphics.Rect(dm.widthPixels / 2 - 80, dm.heightPixels / 2 - 80, dm.widthPixels / 2 + 80, dm.heightPixels / 2 + 80)
                    runCatching {
                        activity.startActivity(Intent(Settings.ACTION_SETTINGS), WindowAnimations.openOptions(activity, s, r, null))
                    }
                }
                ActionRow("Reset animations", null, Icons.Filled.Refresh) {
                    update { cur ->
                        val d = LauncherSettings()
                        cur.copy(
                            openStyle = d.openStyle, openDurationMs = d.openDurationMs, openCurve = d.openCurve,
                            openSpringDamping = d.openSpringDamping, openSpringStiffness = d.openSpringStiffness,
                            launcherExit = d.launcherExit, homeOnOpen = d.homeOnOpen, homeOpenScale = d.homeOpenScale,
                            iconPressScale = d.iconPressScale, cardFill = d.cardFill, cardCornerDp = d.cardCornerDp,
                            closeStyle = d.closeStyle, closeDurationMs = d.closeDurationMs, closeCurve = d.closeCurve,
                            closeSpringDamping = d.closeSpringDamping, closeSpringStiffness = d.closeSpringStiffness,
                            homeCloseScale = d.homeCloseScale, closeBlurHome = d.closeBlurHome, iconBounceOnLand = d.iconBounceOnLand,
                            bounceStrength = d.bounceStrength, waveStagger = d.waveStagger, systemClose = d.systemClose,
                            speedMultiplier = d.speedMultiplier, sheetDamping = d.sheetDamping, sheetStiffness = d.sheetStiffness,
                            folderDamping = d.folderDamping, folderStiffness = d.folderStiffness,
                        )
                    }
                }
            }
        }
    }
}

/* ------------------------------------------ Glass ------------------------------------------ */

@Composable
private fun GlassScreen(s: LauncherSettings, update: Update, onBack: () -> Unit, hasBackdrop: Boolean) {
    val c = LocalLauncherColors.current
    SettingsPage("Liquid glass", onBack) {
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    Modifier.fillMaxWidth().height(86.dp).glass(s.dockCornerDp.dp).padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    repeat(4) { Box(Modifier.size(52.dp).glass(16.dp)) }
                }
                Spacer(Modifier.height(14.dp))
                Box(Modifier.size(width = 220.dp, height = 46.dp).glass(Dp.Infinity), contentAlignment = Alignment.Center) {
                    Text("Liquid glass", color = c.onGlass, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        item {
            Group("Material") {
                SwitchRow("Liquid glass", s.glassEnabled, "Off = flat frosted tint") { v -> update { it.copy(glassEnabled = v) } }
                ChoiceRow("Tint", s.glassTintMode, TintMode.entries.toList(), label = { it.label }) { v -> update { it.copy(glassTintMode = v) } }
                if (s.glassTintMode == TintMode.CUSTOM) {
                    ColorRow("Tint color", s.glassCustomTint) { v -> update { it.copy(glassCustomTint = v) } }
                }
                SliderRow("Tint strength", s.glassTintAlpha, 0f..0.8f, format = ::pct) { v -> update { it.copy(glassTintAlpha = v) } }
                SliderRow("Frost (blur)", s.glassBlurDp, 0f..60f, live = false, format = ::dp) { v -> update { it.copy(glassBlurDp = v) } }
                SliderRow("Saturation", s.glassSaturation, 0.5f..2f, format = { x2(it) + "×" }) { v -> update { it.copy(glassSaturation = v) } }
            }
        }
        item {
            Group("Liquid optics") {
                SliderRow("Refraction", s.glassRefraction, 0f..1.5f, summary = "Lens bending at the edges", format = ::pct) { v -> update { it.copy(glassRefraction = v) } }
                SliderRow("Chromatic dispersion", s.glassDispersion, 0f..1f, summary = "Rainbow fringing at the edges", format = ::pct) { v -> update { it.copy(glassDispersion = v) } }
                SliderRow("Edge highlight", s.glassHighlight, 0f..1f, format = ::pct) { v -> update { it.copy(glassHighlight = v) } }
                SliderRow("Border width", s.glassBorderDp, 0f..3f, format = { String.format(java.util.Locale.US, "%.1f dp", it) }) { v -> update { it.copy(glassBorderDp = v) } }
                SliderRow("Panel corner radius", s.glassCornerDp, 8f..48f, format = ::dp) { v -> update { it.copy(glassCornerDp = v) } }
                SwitchRow(
                    "High-quality refraction", s.glassHighQuality,
                    if (Build.VERSION.SDK_INT >= 33) "GPU shader (Android 13+)" else "Needs Android 13+ — using fast glass",
                ) { v -> update { it.copy(glassHighQuality = v) } }
            }
        }
        item {
            Group("Status") {
                InfoText(
                    if (hasBackdrop) "✓ Glass is sampling real wallpaper pixels (blur + refraction)."
                    else "Glass can't see the system wallpaper yet, so it's tint-only. Pick a Ligma67 wallpaper or allow wallpaper access in Wallpaper settings for true frosted glass.",
                )
            }
        }
    }
}

/* ---------------------------------------- Wallpaper ---------------------------------------- */

@Composable
private fun WallpaperScreen(activity: Activity, s: LauncherSettings, update: Update, onBack: () -> Unit) {
    val app = activity.launcherApp
    val scope = rememberCoroutineScope()
    val canRead by app.backdrop.canReadSystem.collectAsState()
    var alsoSystem by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = app.backdrop.setCustomWallpaper(uri, alsoSystem)
            Toast.makeText(activity, if (ok) "Wallpaper applied" else "Couldn't load image", Toast.LENGTH_SHORT).show()
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { app.backdrop.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { app.backdrop.refresh() }

    SettingsPage("Wallpaper", onBack) {
        item {
            Group("Source") {
                ChoiceRow("Wallpaper", s.wallpaperMode, WallpaperMode.entries.toList(), label = { it.label }) { v -> update { it.copy(wallpaperMode = v) } }
                ActionRow("Choose image for Ligma67", "Best glass: real blur & refraction everywhere", Icons.Filled.Add) {
                    alsoSystem = false
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                ActionRow("Choose image for Ligma67 + system", "Also sets it as home & lock wallpaper", Icons.Filled.Lock) {
                    alsoSystem = true
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                SliderRow("Dim", s.wallpaperDim, 0f..0.7f, format = ::pct) { v -> update { it.copy(wallpaperDim = v) } }
            }
        }
        item {
            Group("System wallpaper access") {
                SwitchRow("Sample system wallpaper for glass", s.readSystemWallpaper) { v -> update { it.copy(readSystemWallpaper = v) } }
                InfoText(
                    if (canRead) "✓ Reading the system wallpaper."
                    else "Android hides the wallpaper from apps unless you allow it. Live wallpapers can't be sampled.",
                )
                if (!canRead) {
                    ActionRow("Allow wallpaper access", null, Icons.Filled.Check) {
                        when {
                            Build.VERSION.SDK_INT >= 34 -> runCatching {
                                activity.startActivity(
                                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${activity.packageName}")),
                                )
                            }
                            Build.VERSION.SDK_INT == 33 -> permission.launch(Manifest.permission.READ_MEDIA_IMAGES)
                            else -> permission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    }
                }
                SwitchRow(
                    "Blur live wallpaper behind drawer", s.windowBlurDrawer,
                    if (Build.VERSION.SDK_INT >= 31) "Uses Android's cross-window blur when the wallpaper can't be sampled" else "Needs Android 12+",
                ) { v -> update { it.copy(windowBlurDrawer = v) } }
            }
        }
    }
}

/* -------------------------------------------- Home -------------------------------------------- */

@Composable
private fun HomeScreenSettings(s: LauncherSettings, update: Update, onBack: () -> Unit) {
    SettingsPage("Home screen", onBack) {
        item {
            Group("Grid") {
                SliderRow("Columns", s.homeColumns.toFloat(), 3f..8f, steps = 4, format = { "${it.roundToInt()}" }) { v -> update { it.copy(homeColumns = v.roundToInt()) } }
                SliderRow("Rows", s.homeRows.toFloat(), 3f..10f, steps = 6, format = { "${it.roundToInt()}" }) { v -> update { it.copy(homeRows = v.roundToInt()) } }
                SliderRow("Icon size", s.homeIconDp, 36f..84f, live = false, format = ::dp) { v -> update { it.copy(homeIconDp = v) } }
                SwitchRow("Show labels", s.homeLabels) { v -> update { it.copy(homeLabels = v) } }
                SliderRow("Side padding", s.homeSidePaddingDp, 0f..40f, format = ::dp) { v -> update { it.copy(homeSidePaddingDp = v) } }
            }
        }
        item {
            Group("Pages") {
                ChoiceRow("Page indicator", s.pageIndicator, IndicatorStyle.entries.toList(), label = { it.label }) { v -> update { it.copy(pageIndicator = v) } }
                ChoiceRow("Page transition", s.pageTransition, PageTransition.entries.toList(), label = { it.label }) { v -> update { it.copy(pageTransition = v) } }
                SliderRow("Default page", s.defaultPage.toFloat(), 0f..5f, steps = 4, format = { "${it.roundToInt() + 1}" }) { v -> update { it.copy(defaultPage = v.roundToInt()) } }
            }
        }
        item {
            Group("Behaviour") {
                SwitchRow("Add new apps to Home", s.autoAddNewApps) { v -> update { it.copy(autoAddNewApps = v) } }
                SwitchRow("Lock layout", s.lockLayout, "Prevent moving or removing items") { v -> update { it.copy(lockLayout = v) } }
                SwitchRow("Hide status bar", s.hideStatusBar) { v -> update { it.copy(hideStatusBar = v) } }
                SliderRow("Dim behind folders", s.homeDimOnOverlay, 0f..0.8f, format = ::pct) { v -> update { it.copy(homeDimOnOverlay = v) } }
            }
        }
    }
}

@Composable
private fun DockScreen(s: LauncherSettings, update: Update, onBack: () -> Unit) {
    SettingsPage("Dock", onBack) {
        item {
            Group {
                SwitchRow("Show dock", s.dockEnabled) { v -> update { it.copy(dockEnabled = v) } }
                SliderRow("Icons", s.dockColumns.toFloat(), 3f..7f, steps = 3, format = { "${it.roundToInt()}" }) { v -> update { it.copy(dockColumns = v.roundToInt()) } }
                ChoiceRow("Style", s.dockStyle, DockStyle.entries.toList(), label = { it.label }) { v -> update { it.copy(dockStyle = v) } }
                SliderRow("Icon size", s.dockIconDp, 36f..84f, live = false, format = ::dp) { v -> update { it.copy(dockIconDp = v) } }
                SwitchRow("Show labels", s.dockLabels) { v -> update { it.copy(dockLabels = v) } }
                SliderRow("Margin", s.dockMarginDp, 0f..40f, format = ::dp) { v -> update { it.copy(dockMarginDp = v) } }
                SliderRow("Corner radius", s.dockCornerDp, 0f..56f, format = ::dp) { v -> update { it.copy(dockCornerDp = v) } }
                SliderRow("Inner padding", s.dockPaddingDp, 0f..32f, format = ::dp) { v -> update { it.copy(dockPaddingDp = v) } }
            }
        }
    }
}

@Composable
private fun DrawerScreen(s: LauncherSettings, update: Update, onBack: () -> Unit, openHidden: () -> Unit) {
    SettingsPage("App drawer", onBack) {
        item {
            Group("Layout") {
                SliderRow("Columns", s.drawerColumns.toFloat(), 3f..7f, steps = 3, format = { "${it.roundToInt()}" }) { v -> update { it.copy(drawerColumns = v.roundToInt()) } }
                SliderRow("Icon size", s.drawerIconDp, 36f..84f, live = false, format = ::dp) { v -> update { it.copy(drawerIconDp = v) } }
                SwitchRow("Show labels", s.drawerLabels) { v -> update { it.copy(drawerLabels = v) } }
                ChoiceRow("Sort", s.drawerSort, SortMode.entries.toList(), label = { it.label }) { v -> update { it.copy(drawerSort = v) } }
                SliderRow("Sheet tint", s.drawerTintAlpha, 0f..0.9f, format = ::pct) { v -> update { it.copy(drawerTintAlpha = v) } }
            }
        }
        item {
            Group("Search & behaviour") {
                SwitchRow("Search bar", s.drawerSearch) { v -> update { it.copy(drawerSearch = v) } }
                SwitchRow("Offer web search", s.drawerWebSearch) { v -> update { it.copy(drawerWebSearch = v) } }
                SwitchRow("Open keyboard automatically", s.drawerAutoKeyboard) { v -> update { it.copy(drawerAutoKeyboard = v) } }
                SwitchRow("Close drawer after launching", s.drawerCloseAfterLaunch, "Off = return to the drawer, like One UI") { v -> update { it.copy(drawerCloseAfterLaunch = v) } }
                ActionRow("Hidden apps", "${s.hiddenApps.size} hidden") { openHidden() }
            }
        }
    }
}

@Composable
private fun HiddenAppsScreen(activity: Activity, s: LauncherSettings, update: Update, onBack: () -> Unit) {
    val app = activity.launcherApp
    val apps by app.apps.apps.collectAsState()
    val c = LocalLauncherColors.current
    SettingsPage("Hidden apps", onBack) {
        item {
            Group {
                for (a in apps) {
                    val hidden = a.key in s.hiddenApps
                    val icon = rememberAppIcon(app.icons, a)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { update { st -> st.copy(hiddenApps = if (hidden) st.hiddenApps - a.key else st.hiddenApps + a.key) } }
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(38.dp).drawBehind {
                                icon?.let { drawImage(it, srcOffset = IntOffset.Zero, srcSize = IntSize(it.width, it.height), dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium) }
                            },
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(a.label, color = c.onGlass, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = hidden,
                            onCheckedChange = { h -> update { st -> st.copy(hiddenApps = if (h) st.hiddenApps + a.key else st.hiddenApps - a.key) } },
                            colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = Color.White),
                        )
                    }
                }
            }
        }
    }
}

/* ------------------------------------------ Icons ------------------------------------------ */

@Composable
private fun IconsScreen(activity: Activity, s: LauncherSettings, update: Update, onBack: () -> Unit) {
    val c = LocalLauncherColors.current
    val packs by produceState<List<IconPackInfo>>(emptyList()) {
        value = withContext(Dispatchers.IO) { IconPack.installed(activity) }
    }
    SettingsPage("Icons & labels", onBack) {
        item {
            Group("Shape") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    for (shape in IconShape.entries) {
                        val sel = shape == s.iconShape
                        Column(
                            Modifier.clip(RoundedCornerShape(12.dp)).clickable { update { it.copy(iconShape = shape) } }.padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Canvas(Modifier.size(36.dp)) {
                                val p = IconRepository.shapePath(shape, size.width).asComposePath()
                                drawPath(p, if (sel) c.accent else c.onGlass.copy(alpha = 0.45f))
                            }
                        }
                    }
                }
                InfoText("Selected: ${s.iconShape.label}")
            }
        }
        item {
            Group("Icons") {
                ChoiceRow("Style", s.iconStyle, IconStyle.entries.toList(), label = { it.label }) { v -> update { it.copy(iconStyle = v) } }
                val options = listOf(IconPackInfo("", "System icons")) + packs
                ChoiceRow(
                    "Icon pack", options.firstOrNull { it.packageName == s.iconPack } ?: options.first(), options,
                    summary = if (packs.isEmpty()) "No icon packs installed" else null, label = { it.label },
                ) { v -> update { it.copy(iconPack = v.packageName) } }
                SwitchRow("Shape legacy icons", s.wrapLegacyIcons, "Put old square icons on a plate") { v -> update { it.copy(wrapLegacyIcons = v) } }
            }
        }
        item {
            Group("Labels") {
                SliderRow("Text size", s.labelSizeSp, 9f..18f, format = { "${it.roundToInt()} sp" }) { v -> update { it.copy(labelSizeSp = v) } }
                ChoiceRow("Color", s.labelColor, LabelColor.entries.toList(), label = { it.label }) { v -> update { it.copy(labelColor = v) } }
                SwitchRow("Shadow", s.labelShadow) { v -> update { it.copy(labelShadow = v) } }
                SwitchRow("Bold", s.labelBold) { v -> update { it.copy(labelBold = v) } }
                SliderRow("Max lines", s.labelMaxLines.toFloat(), 1f..2f, steps = 0, format = { "${it.roundToInt()}" }) { v -> update { it.copy(labelMaxLines = v.roundToInt()) } }
            }
        }
        item {
            Group("Accent color") {
                SwitchRow("Match wallpaper", s.accentAuto) { v -> update { it.copy(accentAuto = v) } }
                if (!s.accentAuto) ColorRow("Accent", s.accentColor) { v -> update { it.copy(accentColor = v) } }
            }
        }
    }
}

@Composable
private fun FoldersScreen(s: LauncherSettings, update: Update, onBack: () -> Unit) {
    SettingsPage("Folders", onBack) {
        item {
            Group {
                SliderRow("Columns inside folders", s.folderColumns.toFloat(), 2f..6f, steps = 3, format = { "${it.roundToInt()}" }) { v -> update { it.copy(folderColumns = v.roundToInt()) } }
                ChoiceRow("Folder icon preview", s.folderPreview, FolderPreview.entries.toList(), label = { it.label }) { v -> update { it.copy(folderPreview = v) } }
                SliderRow("Open spring damping", s.folderDamping, 0.4f..1.2f, format = ::x2) { v -> update { it.copy(folderDamping = v) } }
                SliderRow("Open spring stiffness", s.folderStiffness, 100f..1500f, format = { "${it.roundToInt()}" }) { v -> update { it.copy(folderStiffness = v) } }
            }
        }
    }
}

/* ----------------------------------------- Gestures ----------------------------------------- */

@Composable
private fun GesturesScreen(activity: Activity, s: LauncherSettings, update: Update, onBack: () -> Unit) {
    val app = activity.launcherApp
    val apps by app.apps.apps.collectAsState()
    var picking by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var a11y by remember { mutableStateOf(SystemActions.isAccessibilityEnabled(activity)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { a11y = SystemActions.isAccessibilityEnabled(activity) }
    fun appName(key: String) = apps.firstOrNull { it.key == key }?.label ?: "Choose app"

    Box {
        SettingsPage("Gestures", onBack) {
            item {
                Group("Home screen") {
                    ChoiceRow("Swipe up", s.swipeUp, GestureAction.entries.toList(), label = { it.label }) { v -> update { it.copy(swipeUp = v) } }
                    if (s.swipeUp == GestureAction.OPEN_APP) ActionRow("Swipe up app", appName(s.swipeUpApp)) { picking = { k -> update { it.copy(swipeUpApp = k) } } }
                    ChoiceRow("Swipe down", s.swipeDown, GestureAction.entries.toList(), label = { it.label }) { v -> update { it.copy(swipeDown = v) } }
                    if (s.swipeDown == GestureAction.OPEN_APP) ActionRow("Swipe down app", appName(s.swipeDownApp)) { picking = { k -> update { it.copy(swipeDownApp = k) } } }
                    ChoiceRow("Double tap", s.doubleTap, GestureAction.entries.toList(), label = { it.label }) { v -> update { it.copy(doubleTap = v) } }
                    if (s.doubleTap == GestureAction.OPEN_APP) ActionRow("Double tap app", appName(s.doubleTapApp)) { picking = { k -> update { it.copy(doubleTapApp = k) } } }
                    ChoiceRow("Home button (while on home)", s.homePress, GestureAction.entries.filter { it != GestureAction.OPEN_APP }, label = { it.label }) { v -> update { it.copy(homePress = v) } }
                    SwitchRow("Haptic feedback", s.haptics) { v -> update { it.copy(haptics = v) } }
                }
            }
            item {
                Group("Ligma67 Gestures service") {
                    InfoText("Lock screen, recent apps, screenshots and a reliable notification shade need the optional accessibility service. It never reads screen content.")
                    ActionRow(if (a11y) "Enabled" else "Enable in Accessibility settings", null, if (a11y) Icons.Filled.Check else Icons.Filled.Warning) {
                        SystemActions.openAccessibilitySettings(activity)
                    }
                }
            }
        }
        val onPick = picking
        if (onPick != null) {
            AppChooser(activity, onDismiss = { picking = null }) { key ->
                onPick(key)
                picking = null
            }
        }
    }
}

@Composable
private fun AppChooser(activity: Activity, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val app = activity.launcherApp
    val apps by app.apps.apps.collectAsState()
    val c = LocalLauncherColors.current
    BackHandler { onDismiss() }
    Box(Modifier.fillMaxSize().background(c.scrim).clickable { onDismiss() }) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp)
                .glass(26.dp),
        ) {
            item { Text("Choose an app", color = c.onGlass, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(20.dp)) }
            items(apps, key = { it.key }) { a ->
                val icon = rememberAppIcon(app.icons, a)
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(a.key) }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(38.dp).drawBehind {
                            icon?.let { drawImage(it, srcOffset = IntOffset.Zero, srcSize = IntSize(it.width, it.height), dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium) }
                        },
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(a.label, color = c.onGlass, fontSize = 15.sp)
                }
            }
        }
    }
}

/* ---------------------------------------- Performance ---------------------------------------- */

@Composable
private fun PerformanceScreen(s: LauncherSettings, update: Update, onBack: () -> Unit) {
    SettingsPage("Performance", onBack) {
        item {
            Group {
                SwitchRow("Max refresh rate", s.maxRefreshRate, "Unlocks 90/120 Hz on phones that cap launchers at 60 Hz") { v -> update { it.copy(maxRefreshRate = v) } }
                SwitchRow("High-quality glass", s.glassHighQuality, "Refraction shader on Android 13+") { v -> update { it.copy(glassHighQuality = v) } }
                SwitchRow("Blur home while closing apps", s.closeBlurHome, "Turn off on low-end phones") { v -> update { it.copy(closeBlurHome = v) } }
                SwitchRow("Reduce motion", s.reduceMotion) { v -> update { it.copy(reduceMotion = v) } }
            }
        }
        item {
            Group("Why it stays smooth") {
                InfoText(
                    "• Every animation runs in the render phase only: no recomposition per frame.\n" +
                        "• Animations are interruptible and keep their velocity, so spamming open/close never restarts or stutters.\n" +
                        "• Icons are rendered once off the main thread and uploaded to the GPU ahead of time.\n" +
                        "• Glass blur is computed once per wallpaper change, not per frame.\n" +
                        "• Apps launch on the same frame as your tap — no delay.\n" +
                        "• Install the release build: Compose is several times faster than in debug builds.",
                )
            }
        }
    }
}

/* ------------------------------------------ Backup ------------------------------------------ */

@Composable
private fun BackupScreen(activity: Activity, onBack: () -> Unit) {
    val app = activity.launcherApp
    val scope = rememberCoroutineScope()
    var confirmReset by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val json = LauncherJson.encodeToString(LauncherBackup.serializer(), LauncherBackup(settings = app.settings.value, layout = app.layout.value))
                    activity.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } != null
                }.getOrDefault(false)
            }
            Toast.makeText(activity, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show()
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val backup = withContext(Dispatchers.IO) {
                runCatching {
                    activity.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?.let { LauncherJson.decodeFromString(LauncherBackup.serializer(), it) }
                }.getOrNull()
            }
            if (backup == null) {
                Toast.makeText(activity, "Not a Ligma67 backup", Toast.LENGTH_SHORT).show()
            } else {
                app.settings.replace(backup.settings)
                // Widget ids don't survive across installs/devices; drop them.
                app.layout.update { backup.layout.copy(items = backup.layout.items.filterNot { it is HomeItem.Widget }, seeded = true) }
                Toast.makeText(activity, "Imported", Toast.LENGTH_SHORT).show()
            }
        }
    }
    SettingsPage("Backup & restore", onBack) {
        item {
            Group {
                ActionRow("Export settings & layout", "Saves a .json file", Icons.Filled.Share) { export.launch("ligma67-backup.json") }
                ActionRow("Import", "Restore from a .json backup", Icons.Filled.Add) { import.launch(arrayOf("application/json", "text/plain", "*/*")) }
            }
        }
        item {
            Group("Reset") {
                ActionRow(if (confirmReset) "Tap again to reset ALL settings" else "Reset all settings", null, Icons.Filled.Refresh) {
                    if (confirmReset) {
                        app.settings.replace(LauncherSettings())
                        confirmReset = false
                        Toast.makeText(activity, "Settings reset", Toast.LENGTH_SHORT).show()
                    } else confirmReset = true
                }
                ActionRow("Rebuild home layout", "Re-creates the default home screen", Icons.Filled.Delete) {
                    app.layout.update { HomeLayout() }
                    app.apps.reload(immediate = true)
                    Toast.makeText(activity, "Home layout rebuilt", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

/* ------------------------------------------- About ------------------------------------------- */

@Composable
private fun AboutScreen(activity: Activity, onBack: () -> Unit) {
    val version = remember { runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull() ?: "" }
    SettingsPage("About", onBack) {
        item {
            Group {
                InfoText("Ligma67Launcher $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
                ActionRow("Set as default home app", null, Icons.Filled.Home) { SystemActions.requestDefaultLauncher(activity) }
                ActionRow("Ligma67 Gestures (accessibility)", null, Icons.Filled.Settings) { SystemActions.openAccessibilitySettings(activity) }
            }
        }
        item {
            Group("Compatibility notes") {
                InfoText(
                    "Works on Android 8.0+ from any brand (Samsung, Xiaomi/HyperOS, Nothing, OPPO/realme/OnePlus, vivo, Pixel…).\n\n" +
                        "• The launcher-drawn parts (expanding/shrinking card, home zoom, blur, wave, bounce) are identical everywhere.\n" +
                        "• Window-level styles are handed to Android; some skins (notably MIUI/HyperOS) substitute their own animation for third-party launchers.\n" +
                        "• With gesture navigation, the system — not any third-party launcher — animates the app window while you swipe home. Ligma67 then lands it in the icon.\n" +
                        "• Xiaomi: set Ligma67 as default in Settings › Apps › Default apps › Home screen, and allow 'Display pop-up windows' if prompted.\n" +
                        "• If 90/120 Hz isn't used, enable 'Max refresh rate' in Performance.",
                )
            }
        }
    }
}

@Suppress("unused")
private fun normalize(s: String) = normalizeForSearch(s)
