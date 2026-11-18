package com.ligma67.launcher

import android.content.pm.LauncherApps
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.ligma67.launcher.data.CONTAINER_DESKTOP
import com.ligma67.launcher.data.GridSpec
import com.ligma67.launcher.data.HomeItem
import com.ligma67.launcher.data.LayoutOps

/** Handles "Add to Home screen" requests from other apps (Chrome, Maps, contacts…). */
class AddItemActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val la = getSystemService(LauncherApps::class.java)
        val req = runCatching { la.getPinItemRequest(intent) }.getOrNull()
        if (req != null && req.isValid && req.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) {
            val info = req.shortcutInfo
            if (info != null && runCatching { req.accept() }.getOrDefault(false)) {
                val app = launcherApp
                val s = app.settings.value
                val grid = GridSpec(s.homeColumns, s.homeRows, s.dockColumns)
                val serial = app.apps.serial(info.userHandle)
                val label = (info.shortLabel ?: info.longLabel ?: "").toString()
                app.layout.update { l ->
                    val pos = LayoutOps.findFreeAnywhere(l, 1, 1, grid, 0)
                    LayoutOps.add(l) { id ->
                        HomeItem.Shortcut(id, CONTAINER_DESKTOP, pos.page, pos.x, pos.y, info.`package`, info.id, serial, label)
                    }
                }
                app.layout.flush()
                Toast.makeText(this, "Added to Home", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}
