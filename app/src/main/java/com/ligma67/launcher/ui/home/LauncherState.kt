package com.ligma67.launcher.ui.home

import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import android.provider.MediaStore
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.animation.core.spring
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import com.ligma67.launcher.LauncherActivity
import com.ligma67.launcher.anim.FixedMotionScale
import com.ligma67.launcher.anim.IconRegistry
import com.ligma67.launcher.anim.LaunchAnimator
import com.ligma67.launcher.anim.LaunchRecord
import com.ligma67.launcher.anim.Motion
import com.ligma67.launcher.anim.WindowAnimations
import com.ligma67.launcher.data.AppInfo
import com.ligma67.launcher.data.CONTAINER_DESKTOP
import com.ligma67.launcher.data.CONTAINER_DOCK
import com.ligma67.launcher.data.CellPos
import com.ligma67.launcher.data.GestureAction
import com.ligma67.launcher.data.GridSpec
import com.ligma67.launcher.data.HomeItem
import com.ligma67.launcher.data.HomeLayout
import com.ligma67.launcher.data.LauncherSettings
import com.ligma67.launcher.data.LayoutOps
import com.ligma67.launcher.data.OpenStyle
import com.ligma67.launcher.data.PackageEvent
import com.ligma67.launcher.launcherApp
import com.ligma67.launcher.system.GestureService
import com.ligma67.launcher.system.SystemActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.abs
import kotlin.math.roundToInt

@Immutable
data class MenuEntry(val label: String, val icon: ImageVector, val destructive: Boolean = false, val action: () -> Unit)

@Immutable
data class MenuState(
    val anchor: Rect,
    val title: String?,
    val entries: List<MenuEntry>,
    val shortcuts: List<ShortcutInfo> = emptyList(),
)

@Immutable
data class RenameRequest(val title: String, val initial: String, val onDone: (String) -> Unit)

@Immutable
data class AppPickerRequest(val title: String, val onPick: (AppInfo) -> Unit)

sealed interface DragSource {
    data class Home(val item: HomeItem) : DragSource
    data class Drawer(val app: AppInfo) : DragSource
    data class FolderApp(val folderId: Long, val appKey: String) : DragSource
}

sealed interface DropTarget {
    data class Cell(val page: Int, val x: Int, val y: Int) : DropTarget
    data class Dock(val index: Int) : DropTarget
    data object Remove : DropTarget
    data object Info : DropTarget
    data object Uninstall : DropTarget
}

/**
 * Single owner of all home-screen state and behaviour. Composables read from it and call
 * into it; nothing here triggers recomposition per animation frame.
 */
@Stable
class LauncherState(val activity: LauncherActivity, val scope: CoroutineScope) {
    val app = activity.launcherApp
    private val launcherApps = activity.getSystemService(LauncherApps::class.java)

    val animator = LaunchAnimator(scope)
    val registry = IconRegistry()
    val drag = DragController(this)

    /** 0 closed .. 1 open. */
    val drawer = Motion(0f)
    val folder = Motion(0f)
    val menuProgress = Motion(0f)

    var openFolderId by mutableStateOf<Long?>(null)
    var folderAnchor by mutableStateOf(Rect.Zero)
    var menu by mutableStateOf<MenuState?>(null)
    var widgetPicker by mutableStateOf(false)
    var appPicker by mutableStateOf<AppPickerRequest?>(null)
    var rename by mutableStateOf<RenameRequest?>(null)
    var searchQuery by mutableStateOf("")
    var searchFocusTick by mutableIntStateOf(0)
    var defaultLauncherBanner by mutableStateOf(false)

    var pager: PagerState? = null
    var settings: LauncherSettings = app.settings.value
        set(value) {
            field = value
            animator.motionContext = motionContextFor(value)
        }
    var screen by mutableStateOf(Rect.Zero)
    var screenCornerPx by mutableFloatStateOf(0f)
    val density = activity.resources.displayMetrics.density

    /** Grid geometry for drag & drop: coordinates of each page grid and of the dock row. */
    val gridCoords = HashMap<Int, LayoutCoordinates>()
    var dockCoords: LayoutCoordinates? = null
    var dropZoneBottom = 0f

    private var launchedFromDrawer = false

    init {
        animator.motionContext = motionContextFor(settings)
        scope.launch {
            app.settings.state.map { GridSpec(it.homeColumns, it.homeRows, it.dockColumns) }
                .distinctUntilChanged()
                .collect { g -> app.layout.update { LayoutOps.compactPages(LayoutOps.normalize(it, g)) } }
        }
        scope.launch {
            app.apps.apps.collect { list -> if (list.isNotEmpty()) seedIfNeeded(list) }
        }
        scope.launch {
            app.apps.events.collect { e -> onPackageEvent(e) }
        }
    }

    val grid: GridSpec get() = GridSpec(settings.homeColumns, settings.homeRows, settings.dockColumns)

    private fun motionContextFor(s: LauncherSettings): CoroutineContext {
        val sys = if (s.followSystemAnimationScale) {
            android.provider.Settings.Global.getFloat(
                activity.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            )
        } else 1f
        val factor = s.speedMultiplier * sys
        return if (factor == 1f && s.followSystemAnimationScale) EmptyCoroutineContext else FixedMotionScale(factor)
    }

    fun haptic() {
        if (settings.haptics) activity.window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun appByKey(key: String): AppInfo? = app.apps.apps.value.firstOrNull { it.key == key }

    fun label(app: AppInfo): String = settings.customLabels[app.key] ?: app.label

    private fun iconCorner(bounds: Rect?): Float = (bounds?.width ?: 0f) * settings.iconShape.cornerFraction

    private fun cardCorner(): Float =
        if (settings.cardCornerDp >= 0f) settings.cardCornerDp * density else screenCornerPx

    /* ------------------------------------ launching ------------------------------------ */

    fun launchApp(info: AppInfo, slot: String, bounds: Rect?, icon: ImageBitmap?) {
        if (drag.active != null) return
        menu = null
        val s = settings
        val rect = bounds?.toAndroidRect()
        val thumb = if (s.openStyle == OpenStyle.ICON_MORPH) runCatching { icon?.asAndroidBitmap() }.getOrNull() else null
        val opts = WindowAnimations.openOptions(activity, s, rect, thumb)
        val color = app.icons.color(info.key)
        if (bounds != null) {
            animator.onLaunch(
                LaunchRecord(info.key, info.packageName, slot, bounds, icon, color, SystemClock.uptimeMillis()),
                s, iconCorner(bounds), cardCorner(),
            )
        }
        try {
            launcherApps.startMainActivity(info.component, info.user, rect, opts)
            app.apps.recordLaunch(info.key)
            launchedFromDrawer = slot.startsWith("d:")
        } catch (e: Exception) {
            failLaunch(info, bounds, slot, icon)
        }
    }

    private fun failLaunch(info: AppInfo?, bounds: Rect?, slot: String, icon: ImageBitmap?) {
        animator.onReturn(bounds, slot, settings, iconCorner(bounds), cardCorner(), icon, null)
        if (info != null && info.isWork && Build.VERSION.SDK_INT >= 28) {
            val um = activity.getSystemService(UserManager::class.java)
            if (runCatching { um.isQuietModeEnabled(info.user) }.getOrDefault(false)) {
                runCatching { um.requestQuietModeEnabled(false, info.user) }
                return
            }
        }
        Toast.makeText(activity, "Couldn't open app", Toast.LENGTH_SHORT).show()
    }

    fun launchShortcut(item: HomeItem.Shortcut, slot: String, bounds: Rect?, icon: ImageBitmap?) {
        if (drag.active != null) return
        menu = null
        val user = app.apps.userFor(item.userSerial) ?: return
        val rect = bounds?.toAndroidRect()
        val opts = WindowAnimations.openOptions(activity, settings, rect, null)
        if (bounds != null) {
            val key = "sc:${item.packageName}:${item.shortcutId}:${item.userSerial}"
            animator.onLaunch(
                LaunchRecord(key, item.packageName, slot, bounds, icon, app.icons.color(key), SystemClock.uptimeMillis()),
                settings, iconCorner(bounds), cardCorner(),
            )
        }
        try {
            launcherApps.startShortcut(item.packageName, item.shortcutId, rect, opts, user)
        } catch (e: Exception) {
            failLaunch(null, bounds, slot, icon)
        }
    }

    fun launchShortcutInfo(info: ShortcutInfo, anchor: Rect) {
        menu = null
        runCatching {
            val r = anchor.toAndroidRect()
            launcherApps.startShortcut(info, r, WindowAnimations.openOptions(activity, settings, r, null))
        }.onFailure { Toast.makeText(activity, "Couldn't open shortcut", Toast.LENGTH_SHORT).show() }
    }

    fun shortcutsFor(info: AppInfo): List<ShortcutInfo> = runCatching {
        if (!launcherApps.hasShortcutHostPermission()) return emptyList()
        val q = LauncherApps.ShortcutQuery()
            .setPackage(info.packageName)
            .setActivity(info.component)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
        launcherApps.getShortcuts(q, info.user).orEmpty()
            .sortedWith(compareBy<ShortcutInfo>({ !it.isDeclaredInManifest }, { it.rank }))
            .take(4)
    }.getOrDefault(emptyList())

    /* ------------------------------------ lifecycle ------------------------------------ */

    fun onStop() {
        menu = null
        drag.cancel()
        if (settings.drawerCloseAfterLaunch && launchedFromDrawer) {
            drawer.snap(0f)
            searchQuery = ""
        }
        launchedFromDrawer = false
    }

    /** The launcher is visible again — play the close animation into the right icon. */
    fun onResume(fromStop: Boolean) {
        val s = settings
        val rec = animator.lastLaunch
        val drawerOpen = drawer.value > 0.5f
        val folderOpen = openFolderId != null
        if (rec != null && SystemClock.uptimeMillis() - rec.time < 45 * 60_000L) {
            var key = rec.key
            var preferred: String? = rec.slot
            if (s.preciseCloseTarget) {
                val recent = GestureService.recentPackages(45 * 60_000L).firstOrNull()
                if (recent != null && recent != rec.packageName) {
                    key = "pkg:$recent"
                    preferred = null
                }
            }
            var slot = registry.find(key, screen, drawerOpen, folderOpen, preferred)
            if (slot == null && rec.slot.startsWith("f:") && !folderOpen && !drawerOpen) {
                val folderSlot = "i:" + rec.slot.removePrefix("f:").substringBefore(':')
                if (registry.bounds(folderSlot) != null) slot = folderSlot
            }
            val bounds = slot?.let { registry.bounds(it) }
            val sameApp = key == rec.key
            val slotKey = slot?.let { registry.keyOf(it) }
            val icon = if (sameApp) rec.icon else slotKey?.let { app.icons.peek(it) }
            val color = if (sameApp) rec.color else slotKey?.let { app.icons.color(it) }
            animator.onReturn(bounds, slot, s, iconCorner(bounds), cardCorner(), icon, color)
            if (s.preciseCloseTarget && GestureService.instance == null && SystemActions.hasUsageAccess(activity)) {
                retargetFromUsage(rec)
            }
        } else if (fromStop && (s.animateOnUnlock || rec != null)) {
            animator.lastLaunch = null
            animator.onReturn(null, null, s, 0f, cardCorner(), null, null)
        }
    }

    /** Usage-stats lookup is an IPC — do it off the main thread and retarget in flight. */
    private fun retargetFromUsage(rec: LaunchRecord) {
        scope.launch {
            val pkg = withContext(Dispatchers.IO) {
                SystemActions.recentForegroundPackages(activity, 30 * 60_000L).firstOrNull()
            } ?: return@launch
            if (pkg == rec.packageName || animator.card.value < 0.5f || animator.cardAlpha.value < 0.5f) return@launch
            val slot = registry.find("pkg:$pkg", screen, drawer.value > 0.5f, openFolderId != null, null)
            val bounds = slot?.let { registry.bounds(it) }
            if (slot == null || bounds == null) {
                animator.cardAlpha.animateTo(scope, 0f, spring(stiffness = 900f), animator.motionContext)
                animator.liftedSlot = null
                return@launch
            }
            animator.cardRect = bounds
            animator.liftedSlot = slot
            registry.keyOf(slot)?.let { k ->
                animator.cardIcon = app.icons.peek(k)
                animator.cardColor = app.icons.color(k)
            }
        }
    }

    /** Home pressed while already on home. */
    fun onHomePressedOnHome() {
        when {
            rename != null -> rename = null
            appPicker != null -> appPicker = null
            menu != null -> menu = null
            widgetPicker -> widgetPicker = false
            openFolderId != null -> closeFolder()
            drawer.target > 0.5f -> closeDrawer()
            else -> perform(settings.homePress, "")
        }
    }

    /* ------------------------------------ drawer ------------------------------------ */

    private fun sheetSpec() = spring<Float>(dampingRatio = settings.sheetDamping, stiffness = settings.sheetStiffness)

    fun openDrawer(search: Boolean = false, velocity: Float = 0f) {
        menu = null
        drawer.animateTo(scope, 1f, sheetSpec(), animator.motionContext, initialVelocity = velocity)
        if (search || settings.drawerAutoKeyboard) searchFocusTick++
    }

    fun closeDrawer(velocity: Float = 0f) {
        drawer.animateTo(scope, 0f, sheetSpec(), animator.motionContext, initialVelocity = velocity)
        searchQuery = ""
    }

    /** velocity in "drawer fractions per second", positive = opening. */
    fun settleDrawer(velocity: Float) {
        val open = if (abs(velocity) > 1.1f) velocity > 0 else drawer.value > 0.3f
        if (open) openDrawer(velocity = velocity) else closeDrawer(velocity = velocity)
    }

    private var homeDragAccum = 0f
    private var homeDragMode = 0

    fun onHomeDragStart() {
        homeDragAccum = 0f
        homeDragMode = 0
    }

    fun onHomeDrag(delta: Float) {
        homeDragAccum += delta
        if (homeDragMode == 0 && abs(homeDragAccum) > 1f) homeDragMode = if (homeDragAccum < 0) 1 else -1
        if (homeDragMode == 1 && settings.swipeUp == GestureAction.APP_DRAWER) {
            menu = null
            val h = (screen.height * 0.85f).coerceAtLeast(1f)
            drawer.snap((drawer.value - delta / h).coerceIn(0f, 1f))
        }
    }

    fun onHomeDragEnd(velocityPx: Float) {
        val s = settings
        val h = (screen.height * 0.85f).coerceAtLeast(1f)
        val threshold = 56f * density
        val fling = 900f * density
        when (homeDragMode) {
            1 -> if (s.swipeUp == GestureAction.APP_DRAWER) {
                settleDrawer(-velocityPx / h)
            } else if (homeDragAccum < -threshold || velocityPx < -fling) {
                perform(s.swipeUp, s.swipeUpApp)
            }
            -1 -> if (homeDragAccum > threshold || velocityPx > fling) perform(s.swipeDown, s.swipeDownApp)
        }
        homeDragMode = 0
    }

    /* ------------------------------------ folders ------------------------------------ */

    private fun folderSpec() = spring<Float>(dampingRatio = settings.folderDamping, stiffness = settings.folderStiffness)

    fun openFolder(item: HomeItem.Folder, anchor: Rect) {
        menu = null
        openFolderId = item.id
        folderAnchor = anchor
        folder.animateTo(scope, 1f, folderSpec(), animator.motionContext)
    }

    fun closeFolder(immediate: Boolean = false) {
        if (immediate) {
            folder.snap(0f)
            openFolderId = null
            return
        }
        folder.animateTo(scope, 0f, folderSpec(), animator.motionContext, onEnd = { openFolderId = null })
    }

    fun renameFolder(id: Long, title: String) {
        app.layout.update { l ->
            val f = l.byId(id) as? HomeItem.Folder ?: return@update l
            LayoutOps.replace(l, f.copy(title = title.ifBlank { "Folder" }))
        }
    }

    /* ------------------------------------ menus ------------------------------------ */

    fun showMenu(m: MenuState) {
        haptic()
        menu = m
        menuProgress.snap(0f)
        menuProgress.animateTo(scope, 1f, spring(dampingRatio = 0.72f, stiffness = 700f), animator.motionContext)
    }

    fun appMenu(info: AppInfo, anchor: Rect, homeItem: HomeItem?, folderId: Long?) {
        val entries = ArrayList<MenuEntry>()
        if (homeItem == null && folderId == null) {
            entries += MenuEntry("Add to Home", Icons.Filled.Add) { addToHome(info.key) }
        }
        if (folderId != null) {
            entries += MenuEntry("Remove from folder", Icons.Filled.Clear) { removeFromFolder(folderId, info.key) }
        }
        if (homeItem != null && !settings.lockLayout) {
            entries += MenuEntry("Remove", Icons.Filled.Clear) { removeItem(homeItem) }
        }
        entries += MenuEntry("Rename", Icons.Filled.Edit) {
            rename = RenameRequest("Rename app", label(info)) { name ->
                app.settings.update { s ->
                    val m = s.customLabels.toMutableMap()
                    if (name.isBlank() || name == info.label) m.remove(info.key) else m[info.key] = name
                    s.copy(customLabels = m)
                }
            }
        }
        entries += MenuEntry("App info", Icons.Filled.Info) { SystemActions.appInfo(activity, info.packageName) }
        if (homeItem == null && folderId == null) {
            entries += MenuEntry("Hide app", Icons.Filled.Star) {
                app.settings.update { it.copy(hiddenApps = it.hiddenApps + info.key) }
                Toast.makeText(activity, "Hidden — unhide in Settings › App drawer", Toast.LENGTH_SHORT).show()
            }
        }
        entries += MenuEntry("Uninstall", Icons.Filled.Delete, destructive = true) { SystemActions.uninstall(activity, info.packageName) }
        showMenu(MenuState(anchor, label(info), entries, shortcutsFor(info)))
    }

    fun homeMenu(anchor: Rect) {
        showMenu(
            MenuState(
                anchor, null,
                listOf(
                    MenuEntry("Widgets", Icons.Filled.Add) { widgetPicker = true },
                    MenuEntry("Add app", Icons.Filled.Home) {
                        val page = pager?.currentPage ?: 0
                        appPicker = AppPickerRequest("Add to Home") { addToHome(it.key, page) }
                    },
                    MenuEntry("Wallpaper & glass", Icons.Filled.Edit) { activity.openSettings("wallpaper") },
                    MenuEntry("Animations", Icons.Filled.Refresh) { activity.openSettings("animations") },
                    MenuEntry("Launcher settings", Icons.Filled.Settings) { activity.openSettings(null) },
                ),
            ),
        )
    }

    fun widgetMenu(item: HomeItem.Widget, anchor: Rect) {
        val entries = ArrayList<MenuEntry>()
        if (!settings.lockLayout) {
            entries += MenuEntry("Wider", Icons.Filled.Add) { resizeWidget(item, 1, 0) }
            entries += MenuEntry("Narrower", Icons.Filled.Clear) { resizeWidget(item, -1, 0) }
            entries += MenuEntry("Taller", Icons.Filled.Add) { resizeWidget(item, 0, 1) }
            entries += MenuEntry("Shorter", Icons.Filled.Clear) { resizeWidget(item, 0, -1) }
            entries += MenuEntry("Remove", Icons.Filled.Delete, destructive = true) { removeItem(item) }
        }
        showMenu(MenuState(anchor, "Widget", entries))
    }

    fun folderMenu(item: HomeItem.Folder, anchor: Rect) {
        val entries = ArrayList<MenuEntry>()
        entries += MenuEntry("Rename", Icons.Filled.Edit) {
            rename = RenameRequest("Rename folder", item.title) { renameFolder(item.id, it) }
        }
        if (!settings.lockLayout) {
            entries += MenuEntry("Ungroup", Icons.Filled.Clear) { ungroup(item) }
            entries += MenuEntry("Remove", Icons.Filled.Delete, destructive = true) { removeItem(item) }
        }
        showMenu(MenuState(anchor, item.title, entries))
    }

    /* ------------------------------------ layout edits ------------------------------------ */

    fun addToHome(key: String, page: Int = 0) {
        app.layout.update { LayoutOps.addAppAnywhere(it, key, grid, page) }
        Toast.makeText(activity, "Added to Home", Toast.LENGTH_SHORT).show()
    }

    fun removeItem(item: HomeItem) {
        if (item is HomeItem.Widget) app.widgets.delete(item.widgetId)
        app.layout.update { LayoutOps.compactPages(LayoutOps.remove(it, item.id)) }
    }

    fun removeFromFolder(folderId: Long, key: String) {
        app.layout.update { l -> removeFromFolderOp(l, folderId, key) }
        if (app.layout.value.byId(folderId) !is HomeItem.Folder) closeFolder()
    }

    private fun removeFromFolderOp(l: HomeLayout, folderId: Long, key: String): HomeLayout {
        val f = l.byId(folderId) as? HomeItem.Folder ?: return l
        val next = LayoutOps.dissolveIfNeeded(f.copy(apps = f.apps - key))
        return if (next == null) LayoutOps.remove(l, folderId) else LayoutOps.replace(l, next)
    }

    private fun ungroup(folder: HomeItem.Folder) {
        app.layout.update { l0 ->
            var l = LayoutOps.remove(l0, folder.id)
            folder.apps.forEachIndexed { i, key ->
                l = if (i == 0) {
                    LayoutOps.add(l) { id -> HomeItem.App(id, folder.container, folder.page, folder.x, folder.y, key) }
                } else LayoutOps.addAppAnywhere(l, key, grid, folder.page)
            }
            l
        }
    }

    private fun resizeWidget(item: HomeItem.Widget, dx: Int, dy: Int) {
        val l = app.layout.value
        val sx = (item.spanX + dx).coerceIn(1, grid.columns)
        val sy = (item.spanY + dy).coerceIn(1, grid.rows)
        val occ = LayoutOps.occupancy(l, item.page, grid, item.id)
        if (LayoutOps.fits(occ, item.x, item.y, sx, sy, grid)) {
            app.layout.update { LayoutOps.replace(it, item.copy(spanX = sx, spanY = sy)) }
        } else {
            Toast.makeText(activity, "Not enough room", Toast.LENGTH_SHORT).show()
        }
    }

    fun placeWidget(widgetId: Int, info: AppWidgetProviderInfo, cellW: Float, cellH: Float) {
        val (sx, sy) = app.widgets.span(info, cellW, cellH, grid.columns, grid.rows)
        val page = pager?.currentPage ?: 0
        app.layout.update { l ->
            val pos = LayoutOps.findFree(l, page, sx, sy, grid)?.let { CellPos(CONTAINER_DESKTOP, page, it.first, it.second) }
                ?: LayoutOps.findFreeAnywhere(l, sx, sy, grid, page)
            LayoutOps.add(l) { id ->
                HomeItem.Widget(id, CONTAINER_DESKTOP, pos.page, pos.x, pos.y, sx, sy, widgetId, info.provider.flattenToString())
            }
        }
    }

    /* ------------------------------------ gestures ------------------------------------ */

    fun perform(action: GestureAction, appKey: String) {
        when (action) {
            GestureAction.NONE -> Unit
            GestureAction.APP_DRAWER -> openDrawer()
            GestureAction.SEARCH -> openDrawer(search = true)
            GestureAction.NOTIFICATIONS -> SystemActions.expandNotifications(activity)
            GestureAction.QUICK_SETTINGS -> SystemActions.expandQuickSettings(activity)
            GestureAction.LOCK_SCREEN -> SystemActions.lockScreen(activity)
            GestureAction.RECENTS -> SystemActions.recents(activity)
            GestureAction.SCREENSHOT -> SystemActions.screenshot(activity)
            GestureAction.FIRST_PAGE -> pager?.let { p ->
                scope.launch { p.animateScrollToPage(settings.defaultPage.coerceIn(0, (p.pageCount - 1).coerceAtLeast(0))) }
            }
            GestureAction.LAUNCHER_SETTINGS -> activity.openSettings(null)
            GestureAction.OPEN_APP -> {
                val target = appByKey(appKey)
                if (target != null) launchApp(target, "gesture", null, null)
                else Toast.makeText(activity, "Pick an app for this gesture in Settings › Gestures", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /* ------------------------------------ packages ------------------------------------ */

    private fun seedIfNeeded(apps: List<AppInfo>) {
        if (app.layout.value.seeded) return
        val pm = activity.packageManager
        fun pkg(intent: Intent): String? =
            runCatching { pm.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }
                .getOrNull()?.takeIf { it != "android" }
        val mine = apps.filter { !it.isWork }
        val dockPkgs = listOfNotNull(
            pkg(Intent(Intent.ACTION_DIAL)),
            pkg(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))),
            pkg(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))),
            pkg(Intent(MediaStore.ACTION_IMAGE_CAPTURE)),
        ).distinct()
        val dock = dockPkgs.mapNotNull { p -> mine.firstOrNull { it.packageName == p } }.take(settings.dockColumns)
        val preferred = listOf(
            activity.packageName, "com.android.vending", "com.google.android.apps.photos", "com.google.android.youtube",
            "com.google.android.gm", "com.google.android.apps.maps", "com.google.android.calendar",
            "com.google.android.deskclock", "com.android.settings", "com.sec.android.gallery3d", "com.miui.gallery",
        )
        val first = (preferred.mapNotNull { p -> mine.firstOrNull { it.packageName == p } } + mine)
            .distinct().filter { it !in dock }.take(settings.homeColumns * 2)
        val cols = settings.homeColumns
        val rows = settings.homeRows
        app.layout.update { l0 ->
            var l = l0.copy(seeded = true)
            dock.forEachIndexed { i, a -> l = LayoutOps.add(l) { id -> HomeItem.App(id, CONTAINER_DOCK, 0, i, 0, a.key) } }
            first.forEachIndexed { i, a ->
                l = LayoutOps.add(l) { id ->
                    HomeItem.App(id, CONTAINER_DESKTOP, 0, i % cols, (rows - 2 + i / cols).coerceIn(0, rows - 1), a.key)
                }
            }
            l
        }
        defaultLauncherBanner = !SystemActions.isDefaultLauncher(activity)
    }

    private suspend fun onPackageEvent(e: PackageEvent) {
        when (e) {
            is PackageEvent.Added -> {
                if (!settings.autoAddNewApps) return
                val list = withTimeoutOrNull(4000) {
                    app.apps.apps.first { l -> l.any { it.packageName == e.packageName && it.userSerial == e.userSerial } }
                } ?: return
                val keys = list.filter { it.packageName == e.packageName && it.userSerial == e.userSerial }.map { it.key }
                app.layout.update { l0 ->
                    var l = l0
                    for (k in keys) {
                        val exists = l.items.any { (it is HomeItem.App && it.app == k) || (it is HomeItem.Folder && k in it.apps) }
                        if (!exists) l = LayoutOps.addAppAnywhere(l, k, grid, 0)
                    }
                    l
                }
            }
            is PackageEvent.Removed -> {
                // App updates can briefly report "removed": wait and double-check.
                delay(3000)
                val stillThere = app.apps.apps.value.any { it.packageName == e.packageName && it.userSerial == e.userSerial }
                if (!stillThere) app.layout.update { LayoutOps.removePackage(it, e.packageName, e.userSerial) }
            }
        }
    }
}

fun Rect.toAndroidRect() = android.graphics.Rect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())

/* ===================================== Drag & drop ===================================== */

@Stable
class DragController(private val st: LauncherState) {
    private var pending: DragSource? = null
    private var pendingSlot: String? = null
    private var pendingIcon: ImageBitmap? = null
    private var pendingBounds = Rect.Zero
    private var start = Offset.Zero
    private var edgeJob: Job? = null
    private var edgeDir = 0

    var active by mutableStateOf<DragSource?>(null)
        private set
    var sourceSlot by mutableStateOf<String?>(null)
        private set
    var icon by mutableStateOf<ImageBitmap?>(null)
        private set
    var size by mutableStateOf(androidx.compose.ui.geometry.Size.Zero)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set
    var target by mutableStateOf<DropTarget?>(null)
        private set
    var grab = Offset.Zero
        private set
    val lift = Motion(0f)

    val hasPending: Boolean get() = pending != null || active != null

    fun onLongPress(source: DragSource, slot: String, pointerWindow: Offset, bounds: Rect, image: ImageBitmap?) {
        if (st.settings.lockLayout && source !is DragSource.Drawer) return
        pending = source
        pendingSlot = slot
        pendingIcon = image
        pendingBounds = bounds
        start = pointerWindow
        pointer = pointerWindow
    }

    /** Returns true if the event was consumed by an active drag. */
    fun move(pos: Offset): Boolean {
        if (active == null) {
            pending ?: return false
            if ((pos - start).getDistance() < 10f * st.density) return false
            begin()
        }
        pointer = pos
        target = computeTarget(pos)
        edgeScroll(pos)
        return true
    }

    fun up() {
        if (active != null) drop()
        pending = null
    }

    fun cancel() {
        pending = null
        active = null
        sourceSlot = null
        target = null
        icon = null
        edgeJob?.cancel()
        edgeDir = 0
    }

    private fun begin() {
        val src = pending ?: return
        st.menu = null
        active = src
        sourceSlot = pendingSlot
        icon = pendingIcon
        size = pendingBounds.size
        grab = start - pendingBounds.center
        lift.snap(0f)
        lift.animateTo(st.scope, 1f, spring(dampingRatio = 0.6f, stiffness = 600f))
        when (src) {
            is DragSource.Drawer -> st.closeDrawer()
            is DragSource.FolderApp -> st.closeFolder()
            is DragSource.Home -> Unit
        }
    }

    private fun computeTarget(pos: Offset): DropTarget? {
        val w = st.screen.width
        if (pos.y < st.dropZoneBottom) {
            val src = active
            val canRemove = src is DragSource.Home || src is DragSource.FolderApp
            return when {
                pos.x < w / 3f -> if (canRemove) DropTarget.Remove else null
                pos.x < 2f * w / 3f -> DropTarget.Info
                else -> DropTarget.Uninstall
            }
        }
        val isWidget = (active as? DragSource.Home)?.item is HomeItem.Widget
        st.dockCoords?.takeIf { it.isAttached && st.settings.dockEnabled && !isWidget }?.let { c ->
            val b = c.boundsInWindow()
            if (b.contains(pos)) {
                val slots = st.settings.dockColumns.coerceAtLeast(1)
                val idx = ((pos.x - b.left) / (b.width / slots)).toInt().coerceIn(0, slots - 1)
                return DropTarget.Dock(idx)
            }
        }
        val page = st.pager?.currentPage ?: 0
        val c = st.gridCoords[page]?.takeIf { it.isAttached } ?: return null
        val sz = c.size
        if (sz.width <= 0 || sz.height <= 0) return null
        val cw = sz.width / st.settings.homeColumns.toFloat()
        val ch = sz.height / st.settings.homeRows.toFloat()
        val item = (active as? DragSource.Home)?.item
        val probe = if (item is HomeItem.Widget) {
            pos - grab - Offset(size.width / 2f, size.height / 2f) + Offset(cw / 2f, ch / 2f)
        } else pos
        val local = c.windowToLocal(probe)
        if (local.x < -cw || local.y < -ch || local.x > sz.width + cw || local.y > sz.height + ch) return null
        val x = (local.x / cw).toInt().coerceIn(0, st.settings.homeColumns - 1)
        val y = (local.y / ch).toInt().coerceIn(0, st.settings.homeRows - 1)
        return DropTarget.Cell(page, x, y)
    }

    private fun edgeScroll(pos: Offset) {
        val p = st.pager ?: return
        val edge = 26f * st.density
        val dir = when {
            pos.y < st.dropZoneBottom -> 0
            pos.x < edge -> -1
            pos.x > st.screen.width - edge -> 1
            else -> 0
        }
        if (dir == edgeDir) return
        edgeDir = dir
        edgeJob?.cancel()
        if (dir == 0) return
        edgeJob = st.scope.launch {
            while (true) {
                delay(520)
                val next = (p.currentPage + dir).coerceIn(0, p.pageCount - 1)
                if (next == p.currentPage) break
                p.animateScrollToPage(next)
                target = computeTarget(pointer)
            }
        }
    }

    private fun drop() {
        val src = active ?: return
        val t = target
        val pkg = when (src) {
            is DragSource.Drawer -> src.app.packageName
            is DragSource.FolderApp -> src.appKey.substringBefore('/')
            is DragSource.Home -> when (val i = src.item) {
                is HomeItem.App -> i.app.substringBefore('/')
                is HomeItem.Shortcut -> i.packageName
                is HomeItem.Widget -> i.provider.substringBefore('/')
                is HomeItem.Folder -> null
            }
        }
        when (t) {
            null -> Unit
            DropTarget.Remove -> when (src) {
                is DragSource.Home -> st.removeItem(src.item)
                is DragSource.FolderApp -> st.removeFromFolder(src.folderId, src.appKey)
                is DragSource.Drawer -> Unit
            }
            DropTarget.Info -> pkg?.let { SystemActions.appInfo(st.activity, it) }
            DropTarget.Uninstall -> pkg?.let { SystemActions.uninstall(st.activity, it) }
            is DropTarget.Cell -> place(src, CONTAINER_DESKTOP, t.page, t.x, t.y)
            is DropTarget.Dock -> place(src, CONTAINER_DOCK, 0, t.index, 0)
        }
        cancel()
    }

    private fun place(src: DragSource, container: Int, page: Int, x: Int, y: Int) {
        val grid = st.grid
        var landedId: Long? = null
        st.app.layout.update { l0 ->
            var l = l0
            val single: String? = when (src) {
                is DragSource.Drawer -> src.app.key
                is DragSource.FolderApp -> src.appKey
                is DragSource.Home -> (src.item as? HomeItem.App)?.app
            }
            val moving: HomeItem? = (src as? DragSource.Home)?.item
            // 1. lift the payload out of its source
            when (src) {
                is DragSource.Home -> l = LayoutOps.remove(l, src.item.id)
                is DragSource.FolderApp -> l = removeFromFolder(l, src.folderId, src.appKey)
                is DragSource.Drawer -> Unit
            }
            val occupant = LayoutOps.itemAt(l, container, page, x, y)
            val sx = moving?.spanX ?: 1
            val sy = moving?.spanY ?: 1

            fun put(c: Int, p: Int, px: Int, py: Int): HomeLayout {
                if (moving != null) {
                    landedId = moving.id
                    return l.copy(items = l.items + moving.at(c, p, px, py))
                }
                val key = single ?: return l0
                return LayoutOps.add(l) { id ->
                    landedId = id
                    HomeItem.App(id, c, p, px, py, key)
                }
            }

            val result = when {
                occupant == null && container == CONTAINER_DOCK -> put(container, 0, x, 0)
                occupant == null -> {
                    val occ = LayoutOps.occupancy(l, page, grid)
                    if (LayoutOps.fits(occ, x, y, sx, sy, grid)) put(container, page, x, y)
                    else LayoutOps.findNearest(l, page, x, y, sx, sy, grid, -1)?.let { (nx, ny) -> put(container, page, nx, ny) } ?: l0
                }
                occupant is HomeItem.App && single != null && occupant.app != single -> {
                    landedId = occupant.id
                    LayoutOps.replace(
                        l,
                        HomeItem.Folder(occupant.id, occupant.container, occupant.page, occupant.x, occupant.y, "Folder", listOf(occupant.app, single)),
                    )
                }
                occupant is HomeItem.Folder && single != null -> {
                    landedId = occupant.id
                    if (single in occupant.apps) l else LayoutOps.replace(l, occupant.copy(apps = occupant.apps + single))
                }
                container == CONTAINER_DOCK -> {
                    val used = l.dock.map { it.x }.toSet()
                    val free = (0 until grid.dockSlots).filter { it !in used }.minByOrNull { abs(it - x) }
                    if (free != null) put(container, 0, free, 0) else l0
                }
                else -> LayoutOps.findNearest(l, page, x, y, sx, sy, grid, -1)?.let { (nx, ny) -> put(container, page, nx, ny) } ?: l0
            }
            LayoutOps.compactPages(result)
        }
        landedId?.let { id ->
            st.animator.bounceSlot = "i:$id"
            st.animator.bounce.snap(0.86f)
            st.animator.bounce.animateTo(st.scope, 1f, spring(dampingRatio = 0.45f, stiffness = 500f))
        }
    }

    private fun removeFromFolder(l: HomeLayout, folderId: Long, key: String): HomeLayout {
        val f = l.byId(folderId) as? HomeItem.Folder ?: return l
        val next = LayoutOps.dissolveIfNeeded(f.copy(apps = f.apps - key))
        return if (next == null) LayoutOps.remove(l, folderId) else LayoutOps.replace(l, next)
    }
}
