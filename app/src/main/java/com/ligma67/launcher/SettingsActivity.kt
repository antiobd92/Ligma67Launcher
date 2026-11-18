package com.ligma67.launcher

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.ligma67.launcher.system.SystemActions
import com.ligma67.launcher.ui.settings.SettingsRoot
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        val section = intent.getStringExtra(EXTRA_SECTION)
        setContent { SettingsRoot(this, section) }
        lifecycleScope.launch {
            launcherApp.settings.state.collect { SystemActions.applyRefreshRate(this@SettingsActivity, it.maxRefreshRate) }
        }
    }

    override fun onStop() {
        super.onStop()
        launcherApp.settings.flush()
    }

    companion object {
        const val EXTRA_SECTION = "section"
    }
}
