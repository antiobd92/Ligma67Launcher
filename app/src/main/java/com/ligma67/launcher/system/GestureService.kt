package com.ligma67.launcher.system

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Optional accessibility service. It only:
 *  - performs global actions (lock, recents, notifications, quick settings, screenshot), and
 *  - remembers which app package was last in the foreground, so the close animation can
 *    shrink into the icon of the app you actually came from (even if opened from recents).
 * It never reads window content (canRetrieveWindowContent=false).
 */
class GestureService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == "android") return
        synchronized(recent) {
            recent.removeAll { it.first == pkg }
            recent.add(0, pkg to SystemClock.uptimeMillis())
            if (recent.size > 8) recent.removeAt(recent.lastIndex)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: GestureService? = null
            private set

        private val recent = ArrayList<Pair<String, Long>>()

        fun perform(action: Int): Boolean = instance?.performGlobalAction(action) ?: false

        /** Recent foreground packages, newest first, seen within [windowMs]. */
        fun recentPackages(windowMs: Long): List<String> {
            if (instance == null) return emptyList()
            val now = SystemClock.uptimeMillis()
            return synchronized(recent) { recent.filter { now - it.second <= windowMs }.map { it.first } }
        }
    }
}
