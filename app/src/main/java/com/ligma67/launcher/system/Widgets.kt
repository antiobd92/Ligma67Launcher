package com.ligma67.launcher.system

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.ceil

class LauncherWidgetHost(context: Context) : AppWidgetHost(context, HOST_ID) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        LauncherWidgetView(context)

    companion object {
        const val HOST_ID = 6767
    }
}

/** Host view that detects long-press itself (widgets swallow touches) so widgets can be moved. */
class LauncherWidgetView(context: Context) : AppWidgetHostView(context) {
    /** Called with the press position in window coordinates. */
    var onLongPress: ((Float, Float) -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val trigger = Runnable {
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val loc = IntArray(2)
        getLocationInWindow(loc)
        onLongPress?.invoke(loc[0] + downX, loc[1] + downY)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                downX = ev.x
                downY = ev.y
                postDelayed(trigger, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(trigger)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(trigger)
        }
        return longPressed
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (longPressed) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) removeCallbacks(trigger)
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        removeCallbacks(trigger)
    }
}

class WidgetManager(context: Context) {
    private val appContext = context.applicationContext
    val host = LauncherWidgetHost(appContext)
    val awm: AppWidgetManager = AppWidgetManager.getInstance(appContext)
    private val launcherApps = appContext.getSystemService(LauncherApps::class.java)

    fun providers(): List<AppWidgetProviderInfo> = runCatching {
        launcherApps.profiles.flatMap { user -> awm.getInstalledProvidersForProfile(user) }
    }.getOrDefault(emptyList())

    fun info(id: Int): AppWidgetProviderInfo? = runCatching { awm.getAppWidgetInfo(id) }.getOrNull()
    fun allocate(): Int = host.allocateAppWidgetId()
    fun delete(id: Int) = runCatching { host.deleteAppWidgetId(id) }
    fun bindIfAllowed(id: Int, info: AppWidgetProviderInfo): Boolean =
        runCatching { awm.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null) }.getOrDefault(false)

    fun startListening() = runCatching { host.startListening() }
    fun stopListening() = runCatching { host.stopListening() }

    /** Grid span a provider asks for, given the cell size in pixels. */
    fun span(info: AppWidgetProviderInfo, cellW: Float, cellH: Float, cols: Int, rows: Int): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            return info.targetCellWidth.coerceIn(1, cols) to info.targetCellHeight.coerceIn(1, rows)
        }
        val sx = ceil(info.minWidth / cellW.coerceAtLeast(1f)).toInt().coerceIn(1, cols)
        val sy = ceil(info.minHeight / cellH.coerceAtLeast(1f)).toInt().coerceIn(1, rows)
        return sx to sy
    }

    /** Tells the widget its size in dp so it can pick the right layout. */
    fun updateSize(view: AppWidgetHostView, wDp: Float, hDp: Float) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                view.updateAppWidgetSize(Bundle(), listOf(SizeF(wDp, hDp)))
            } else {
                @Suppress("DEPRECATION")
                view.updateAppWidgetSize(Bundle(), wDp.toInt(), hDp.toInt(), wDp.toInt(), hDp.toInt())
            }
        }
    }
}
