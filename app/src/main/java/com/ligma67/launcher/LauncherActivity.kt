package com.ligma67.launcher

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.RoundedCorner
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.ligma67.launcher.anim.WindowAnimations
import com.ligma67.launcher.data.LabelColor
import com.ligma67.launcher.system.SystemActions
import com.ligma67.launcher.ui.home.LauncherRoot
import com.ligma67.launcher.ui.home.LauncherState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class LauncherActivity : ComponentActivity() {
    lateinit var state: LauncherState
        private set
    private val app by lazy { launcherApp }
    private var stopped = false
    private var pendingWidget: Pair<Int, AppWidgetProviderInfo>? = null
    private var lastBlur = -1

    private val bindWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val (id, info) = pendingWidget ?: return@registerForActivityResult
        if (r.resultCode == RESULT_OK) configureOrPlace(id, info) else {
            app.widgets.delete(id)
            pendingWidget = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // AndroidUiDispatcher.Main carries the Choreographer-backed MonotonicFrameClock, so every
        // Motion is stepped exactly once per display frame (60/90/120 Hz) and dies with the activity.
        state = LauncherState(this, CoroutineScope(lifecycleScope.coroutineContext + AndroidUiDispatcher.Main))
        setContent { LauncherRoot(state) }

        // Keep the widget host listening for the whole activity lifetime: restarting it on
        // every return home would replay all widget updates right during the close animation.
        app.widgets.startListening()

        lifecycleScope.launch {
            app.settings.state.collect { s ->
                SystemActions.applyRefreshRate(this@LauncherActivity, s.maxRefreshRate)
                WindowAnimations.applySystemClose(this@LauncherActivity, s)
            }
        }
        lifecycleScope.launch {
            combine(app.settings.state, app.backdrop.tone) { s, t -> Triple(s.hideStatusBar, s.labelColor, t.isDark) }
                .distinctUntilChanged()
                .collect { (hide, labels, dark) ->
                    val c = WindowInsetsControllerCompat(window, window.decorView)
                    val lightBars = when (labels) {
                        LabelColor.AUTO -> !dark
                        LabelColor.BLACK -> true
                        LabelColor.WHITE -> false
                    }
                    c.isAppearanceLightStatusBars = lightBars
                    c.isAppearanceLightNavigationBars = lightBars
                    if (hide) {
                        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        c.hide(WindowInsetsCompat.Type.statusBars())
                    } else {
                        c.show(WindowInsetsCompat.Type.statusBars())
                    }
                }
        }
        // Real cross-window blur of the live wallpaper behind the drawer when we can't sample it.
        if (Build.VERSION.SDK_INT >= 31) {
            lifecycleScope.launch {
                snapshotFlow { state.drawer.value }.collect { p -> applyWindowBlur(p) }
            }
        }
    }

    private fun applyWindowBlur(p: Float) {
        if (Build.VERSION.SDK_INT < 31) return
        val s = app.settings.value
        val want = s.windowBlurDrawer && app.backdrop.blurred.value == null && s.glassEnabled
        val r = if (want) ((p.coerceIn(0f, 1f) * 36f * resources.displayMetrics.density).roundToInt() / 4) * 4 else 0
        if (r == lastBlur) return
        lastBlur = r
        runCatching { window.setBackgroundBlurRadius(r) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= 31) {
            val r = window.decorView.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
            state.screenCornerPx = r.toFloat()
        }
    }

    override fun onResume() {
        super.onResume()
        val fromStop = stopped
        stopped = false
        state.onResume(fromStop)
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        state.onStop()
        app.settings.flush()
    }

    override fun onDestroy() {
        app.widgets.stopListening()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            val alreadyOnHome = !stopped && hasWindowFocus() &&
                (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0
            if (alreadyOnHome) state.onHomePressedOnHome()
        }
    }

    /* ----------------------------------- widgets ----------------------------------- */

    fun addWidget(info: AppWidgetProviderInfo) {
        val id = app.widgets.allocate()
        pendingWidget = id to info
        if (app.widgets.bindIfAllowed(id, info)) {
            configureOrPlace(id, info)
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            runCatching { bindWidget.launch(intent) }.onFailure {
                app.widgets.delete(id)
                pendingWidget = null
            }
        }
    }

    private fun configureOrPlace(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure != null) {
            val ok = runCatching {
                app.widgets.host.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, null)
            }.isSuccess
            if (ok) return
        }
        place(id, info)
    }

    private fun place(id: Int, info: AppWidgetProviderInfo) {
        pendingWidget = null
        val c = state.gridCoords[state.pager?.currentPage ?: 0]?.takeIf { it.isAttached }
        val s = app.settings.value
        val cw = (c?.size?.width ?: resources.displayMetrics.widthPixels) / s.homeColumns.toFloat()
        val ch = (c?.size?.height ?: resources.displayMetrics.heightPixels / 2) / s.homeRows.toFloat()
        state.placeWidget(id, info, cw, ch)
    }

    @Deprecated("Needed for AppWidgetHost configure results")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CONFIGURE) {
            val (id, info) = pendingWidget ?: return
            if (resultCode == RESULT_OK) place(id, info) else {
                app.widgets.delete(id)
                pendingWidget = null
            }
        }
    }

    fun openSettings(section: String?) {
        state.menu = null
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_SECTION, section)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private companion object {
        const val REQ_CONFIGURE = 6702
    }
}
