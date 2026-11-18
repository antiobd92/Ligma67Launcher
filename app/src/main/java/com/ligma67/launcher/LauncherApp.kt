package com.ligma67.launcher

import android.app.Application
import android.content.Context
import com.ligma67.launcher.data.AppRepository
import com.ligma67.launcher.data.IconConfig
import com.ligma67.launcher.data.IconRepository
import com.ligma67.launcher.data.LauncherSettings
import com.ligma67.launcher.data.LayoutRepository
import com.ligma67.launcher.data.SettingsRepository
import com.ligma67.launcher.glass.Backdrop
import com.ligma67.launcher.system.WidgetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.max

/** Process-wide singletons. Everything heavy is created once and survives activity restarts. */
class LauncherApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var settings: SettingsRepository
        private set
    lateinit var apps: AppRepository
        private set
    lateinit var layout: LayoutRepository
        private set
    lateinit var icons: IconRepository
        private set
    lateinit var backdrop: Backdrop
        private set
    lateinit var widgets: WidgetManager
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this, appScope)
        apps = AppRepository(this, appScope)
        layout = LayoutRepository(this, appScope)
        icons = IconRepository(this, apps, appScope)
        backdrop = Backdrop(this, settings, appScope)
        widgets = WidgetManager(this)

        appScope.launch {
            settings.state.map { iconConfig(it) }.distinctUntilChanged().collect { icons.configure(it) }
        }
        appScope.launch {
            apps.apps.collect { icons.prefetch(it) }
        }
    }

    private fun iconConfig(s: LauncherSettings): IconConfig {
        val density = resources.displayMetrics.density
        val maxDp = max(s.homeIconDp, max(s.dockIconDp, s.drawerIconDp))
        return IconConfig(
            sizePx = (maxDp * density * 1.1f).toInt().coerceIn(96, 288),
            shape = s.iconShape,
            style = s.iconStyle,
            pack = s.iconPack,
            wrapLegacy = s.wrapLegacyIcons,
            tint = s.accentColor.toInt(),
        )
    }
}

val Context.launcherApp: LauncherApp get() = applicationContext as LauncherApp
