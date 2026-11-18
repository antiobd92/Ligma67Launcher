package com.ligma67.launcher.anim

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.window.SplashScreen
import com.ligma67.launcher.data.Curve
import com.ligma67.launcher.data.LauncherExit
import com.ligma67.launcher.data.LauncherSettings
import com.ligma67.launcher.data.OpenStyle
import com.ligma67.launcher.data.SplashStyle
import com.ligma67.launcher.data.SystemClose

/**
 * Picks the window-level transition for the *app's* window.
 *
 * Third-party launchers can't drive another app's window frame-by-frame (that needs a
 * system-only permission), so we hand Android one of the ~700 animation resources generated
 * at build time — every style x curve x duration — and the system compositor plays it.
 */
object WindowAnimations {
    private val ids = HashMap<String, Int>()

    private fun res(ctx: Context, name: String): Int =
        ids.getOrPut(name) { ctx.resources.getIdentifier(name, "anim", ctx.packageName) }

    /** Snaps any duration to the generated 50ms grid (100..800ms). */
    fun bucket(ms: Int): Int = (((ms.coerceIn(100, 800) + 25) / 50) * 50).coerceIn(100, 800)

    fun effectiveOpenMs(s: LauncherSettings): Int {
        val base = if (s.openCurve == Curve.SPRING) Specs.springDurationMs(s.openSpringDamping, s.openSpringStiffness) else s.openDurationMs
        return (base * s.speedMultiplier).toInt()
    }

    fun openOptions(activity: Activity, s: LauncherSettings, bounds: Rect?, thumbnail: Bitmap?): Bundle? {
        val view = activity.window.decorView
        val d = bucket(effectiveOpenMs(s))
        val curve = s.openCurve.windowKey
        val exit = exitName(s, d)
        val opts: ActivityOptions? = runCatching {
            when (s.openStyle) {
                OpenStyle.SYSTEM_DEFAULT -> null
                OpenStyle.SCALE_UP -> bounds?.let { ActivityOptions.makeScaleUpAnimation(view, it.left, it.top, it.width(), it.height()) }
                OpenStyle.CLIP_REVEAL -> bounds?.let { ActivityOptions.makeClipRevealAnimation(view, it.left, it.top, it.width(), it.height()) }
                OpenStyle.ICON_MORPH -> if (bounds != null && thumbnail != null) {
                    ActivityOptions.makeThumbnailScaleUpAnimation(view, thumbnail, bounds.left, bounds.top)
                } else null
                OpenStyle.NONE -> custom(activity, "l67_none", "l67_none")
                OpenStyle.EXPAND -> custom(activity, "l67_e_expand_$d", "l67_x_hold_$d")
                OpenStyle.ZOOM -> custom(activity, "l67_e_zoom_${curve}_$d", exit)
                OpenStyle.FADE -> custom(activity, "l67_e_fade_${curve}_$d", exit)
                OpenStyle.SLIDE_UP -> custom(activity, "l67_e_slideup_${curve}_$d", exit)
                OpenStyle.SLIDE_SIDE -> custom(activity, "l67_e_slide_${curve}_$d", exit)
                OpenStyle.POP -> custom(activity, "l67_e_pop_${curve}_$d", exit)
                OpenStyle.RISE -> custom(activity, "l67_e_rise_${curve}_$d", exit)
            }
        }.getOrNull()

        val withSplash = applySplash(opts, s)
        return withSplash?.toBundle()
    }

    private fun exitName(s: LauncherSettings, d: Int) = when (s.launcherExit) {
        LauncherExit.HOLD -> "l67_x_hold_$d"
        LauncherExit.FADE -> "l67_x_fade_$d"
        LauncherExit.ZOOM_OUT -> "l67_x_zoomout_$d"
        LauncherExit.ZOOM_IN -> "l67_x_zoomin_$d"
        LauncherExit.SLIDE_AWAY -> "l67_x_slideaway_$d"
    }

    private fun custom(ctx: Context, enter: String, exit: String): ActivityOptions? {
        val e = res(ctx, enter)
        val x = res(ctx, exit)
        if (e == 0 || x == 0) return null
        return ActivityOptions.makeCustomAnimation(ctx, e, x)
    }

    private fun applySplash(opts: ActivityOptions?, s: LauncherSettings): ActivityOptions? {
        if (Build.VERSION.SDK_INT < 33 || s.splashStyle == SplashStyle.APP_DEFAULT) return opts
        val o = opts ?: ActivityOptions.makeBasic()
        o.splashScreenStyle = when (s.splashStyle) {
            SplashStyle.ICON -> SplashScreen.SPLASH_SCREEN_STYLE_ICON
            else -> SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR
        }
        return o
    }

    /**
     * Android 14+: tell the system how to animate *this* launcher coming to front over the
     * closing app. Works on stock-like builds with button navigation; gesture navigation is
     * owned by the system's recents animation on most devices.
     */
    fun applySystemClose(activity: Activity, s: LauncherSettings) {
        if (Build.VERSION.SDK_INT < 34) return
        runCatching {
            if (s.systemClose == SystemClose.OFF) {
                activity.clearOverrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN)
            } else {
                val d = bucket((s.systemCloseDurationMs * s.speedMultiplier).toInt())
                val enter = res(activity, "l67_he_${s.systemClose.key}_$d")
                val exit = res(activity, "l67_ax_${s.systemClose.key}_$d")
                if (enter != 0 && exit != 0) {
                    activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, enter, exit)
                }
            }
        }
    }
}
