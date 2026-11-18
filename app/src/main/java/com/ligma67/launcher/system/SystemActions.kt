package com.ligma67.launcher.system

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.app.role.RoleManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.Display
import android.widget.Toast

object SystemActions {

    fun expandNotifications(ctx: Context): Boolean =
        statusBar(ctx, "expandNotificationsPanel") || GestureService.perform(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)

    fun expandQuickSettings(ctx: Context): Boolean =
        statusBar(ctx, "expandSettingsPanel") || GestureService.perform(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)

    /** Hidden-but-allowed StatusBarManager call used by most launchers; falls back to a11y. */
    @SuppressLint("WrongConstant")
    private fun statusBar(ctx: Context, method: String): Boolean = runCatching {
        val sbm = ctx.getSystemService("statusbar") ?: return false
        Class.forName("android.app.StatusBarManager").getMethod(method).invoke(sbm)
        true
    }.getOrDefault(false)

    fun lockScreen(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 28 && GestureService.perform(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) return true
        needsAccessibility(ctx)
        return false
    }

    fun recents(ctx: Context): Boolean =
        GestureService.perform(AccessibilityService.GLOBAL_ACTION_RECENTS).also { if (!it) needsAccessibility(ctx) }

    fun screenshot(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 28 && GestureService.perform(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)) return true
        needsAccessibility(ctx)
        return false
    }

    private fun needsAccessibility(ctx: Context) {
        Toast.makeText(ctx, "Enable \"Ligma67 Gestures\" in Accessibility settings for this action", Toast.LENGTH_LONG).show()
    }

    fun isAccessibilityEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(ctx, GestureService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    fun openAccessibilitySettings(ctx: Context) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun isDefaultLauncher(ctx: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == ctx.packageName
    }

    /** RoleManager on Android 10+, falling back to the OEM "default apps" screens (MIUI, ColorOS…). */
    fun requestDefaultLauncher(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 29) {
            val ok = runCatching {
                val rm = activity.getSystemService(RoleManager::class.java)
                if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    @Suppress("DEPRECATION")
                    activity.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 6701)
                    true
                } else false
            }.getOrDefault(false)
            if (ok) return
        }
        val fallbacks = listOf(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in fallbacks) {
            if (runCatching { activity.startActivity(i); true }.getOrDefault(false)) return
        }
    }

    /**
     * Many OEMs (Xiaomi, OPPO, realme…) cap third-party apps at 60Hz. Explicitly asking for
     * the fastest display mode at the current resolution unlocks 90/120Hz animations.
     */
    fun applyRefreshRate(activity: Activity, maxRate: Boolean) {
        runCatching {
            @Suppress("DEPRECATION")
            val display: Display = (if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay) ?: return
            val lp = activity.window.attributes
            if (maxRate) {
                val cur = display.mode
                val best = display.supportedModes
                    .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                    .maxByOrNull { it.refreshRate } ?: return
                if (lp.preferredDisplayModeId == best.modeId) return
                lp.preferredDisplayModeId = best.modeId
            } else {
                if (lp.preferredDisplayModeId == 0) return
                lp.preferredDisplayModeId = 0
            }
            activity.window.attributes = lp
        }
    }

    fun hasUsageAccess(ctx: Context): Boolean = runCatching {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun openUsageAccess(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Most recent foreground packages (newest first) within [windowMs], excluding us. */
    fun recentForegroundPackages(ctx: Context, windowMs: Long): List<String> = runCatching {
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - windowMs, now)
        val e = UsageEvents.Event()
        val seen = ArrayList<String>()
        @Suppress("DEPRECATION")
        val resumed = if (Build.VERSION.SDK_INT >= 29) UsageEvents.Event.ACTIVITY_RESUMED else UsageEvents.Event.MOVE_TO_FOREGROUND
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == resumed && e.packageName != ctx.packageName) {
                seen.remove(e.packageName)
                seen.add(e.packageName)
            }
        }
        seen.reversed()
    }.getOrDefault(emptyList())

    fun appInfo(ctx: Context, pkg: String) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun uninstall(ctx: Context, pkg: String) {
        runCatching {
            @Suppress("DEPRECATION")
            ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun webSearch(ctx: Context, query: String) {
        val ok = runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
        if (!ok) {
            runCatching {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
