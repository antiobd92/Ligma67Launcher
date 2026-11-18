package com.ligma67.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.Collator
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class AppInfo(
    val key: String,
    val component: ComponentName,
    val user: UserHandle,
    val userSerial: Long,
    val label: String,
    val searchKey: String,
    val initials: String,
    val firstInstallTime: Long,
    val isWork: Boolean,
) {
    val packageName: String get() = component.packageName
}

object AppKeys {
    fun make(component: ComponentName, serial: Long) = "${component.packageName}/${component.className}#$serial"
    fun packageOf(key: String) = key.substringBefore('/')
    fun serialOf(key: String) = key.substringAfterLast('#').toLongOrNull() ?: 0L
}

sealed interface PackageEvent {
    val packageName: String
    val userSerial: Long

    data class Added(override val packageName: String, override val userSerial: Long) : PackageEvent
    data class Removed(override val packageName: String, override val userSerial: Long) : PackageEvent
}

fun normalizeForSearch(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

/**
 * Live list of launchable activities for every user profile (personal + work), kept in sync
 * through LauncherApps callbacks. Loaded once; never reloaded on resume — that's one of the
 * things that keeps returning home instant.
 */
class AppRepository(context: Context, private val scope: CoroutineScope) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val usagePrefs = context.getSharedPreferences("ligma67_usage", Context.MODE_PRIVATE)

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
    private val _events = MutableSharedFlow<PackageEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<PackageEvent> = _events
    private val _usage = MutableStateFlow(loadUsage())
    val usage: StateFlow<Map<String, Int>> = _usage.asStateFlow()

    private val infos = ConcurrentHashMap<String, LauncherActivityInfo>()
    private var reloadJob: Job? = null

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            _events.tryEmit(PackageEvent.Removed(packageName, serial(user)))
            reload()
        }

        override fun onPackageAdded(packageName: String, user: UserHandle) {
            _events.tryEmit(PackageEvent.Added(packageName, serial(user)))
            reload()
        }

        override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) = reload()
        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) = reload()
    }

    init {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        reload(immediate = true)
    }

    fun serial(user: UserHandle): Long = userManager.getSerialNumberForUser(user)

    fun userFor(serial: Long): UserHandle? = userManager.getUserForSerialNumber(serial)

    fun activityInfo(key: String): LauncherActivityInfo? = infos[key]

    fun reload(immediate: Boolean = false) {
        reloadJob?.cancel()
        reloadJob = scope.launch(Dispatchers.Default) {
            if (!immediate) delay(120) // package installs fire bursts of callbacks
            val me = Process.myUserHandle()
            val list = ArrayList<AppInfo>(256)
            val newInfos = HashMap<String, LauncherActivityInfo>()
            for (user in runCatching { launcherApps.profiles }.getOrDefault(listOf(me))) {
                val serial = serial(user)
                val activities = runCatching { launcherApps.getActivityList(null, user) }.getOrDefault(emptyList())
                for (info in activities) {
                    val cn = info.componentName
                    val key = AppKeys.make(cn, serial)
                    val label = info.label?.toString()?.trim().orEmpty().ifEmpty { cn.packageName }
                    val norm = normalizeForSearch(label)
                    list += AppInfo(
                        key = key,
                        component = cn,
                        user = user,
                        userSerial = serial,
                        label = label,
                        searchKey = norm,
                        initials = norm.split(' ', '-', '_', '.').filter { it.isNotEmpty() }.joinToString("") { it.take(1) },
                        firstInstallTime = runCatching { info.firstInstallTime }.getOrDefault(0L),
                        isWork = user != me,
                    )
                    newInfos[key] = info
                }
            }
            val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
            list.sortWith { a, b -> collator.compare(a.label, b.label) }
            infos.keys.retainAll(newInfos.keys)
            infos.putAll(newInfos)
            _apps.value = list
            _loaded.value = true
        }
    }

    fun recordLaunch(key: String) {
        val next = _usage.value.toMutableMap()
        val count = (next[key] ?: 0) + 1
        next[key] = count
        _usage.value = next
        usagePrefs.edit().putInt(key, count).apply()
    }

    private fun loadUsage(): Map<String, Int> =
        usagePrefs.all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()
}
