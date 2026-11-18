package com.ligma67.launcher.data

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

const val CONTAINER_DESKTOP = 0
const val CONTAINER_DOCK = 1

@Immutable
@Serializable
sealed class HomeItem {
    abstract val id: Long
    abstract val container: Int
    abstract val page: Int
    abstract val x: Int
    abstract val y: Int
    open val spanX: Int get() = 1
    open val spanY: Int get() = 1

    abstract fun at(container: Int, page: Int, x: Int, y: Int): HomeItem

    @Immutable
    @Serializable
    @SerialName("app")
    data class App(
        override val id: Long,
        override val container: Int,
        override val page: Int,
        override val x: Int,
        override val y: Int,
        val app: String,
    ) : HomeItem() {
        override fun at(container: Int, page: Int, x: Int, y: Int) = copy(container = container, page = page, x = x, y = y)
    }

    @Immutable
    @Serializable
    @SerialName("folder")
    data class Folder(
        override val id: Long,
        override val container: Int,
        override val page: Int,
        override val x: Int,
        override val y: Int,
        val title: String,
        val apps: List<String>,
    ) : HomeItem() {
        override fun at(container: Int, page: Int, x: Int, y: Int) = copy(container = container, page = page, x = x, y = y)
    }

    @Immutable
    @Serializable
    @SerialName("widget")
    data class Widget(
        override val id: Long,
        override val container: Int,
        override val page: Int,
        override val x: Int,
        override val y: Int,
        override val spanX: Int,
        override val spanY: Int,
        val widgetId: Int,
        val provider: String,
    ) : HomeItem() {
        override fun at(container: Int, page: Int, x: Int, y: Int) = copy(container = container, page = page, x = x, y = y)
    }

    @Immutable
    @Serializable
    @SerialName("shortcut")
    data class Shortcut(
        override val id: Long,
        override val container: Int,
        override val page: Int,
        override val x: Int,
        override val y: Int,
        val packageName: String,
        val shortcutId: String,
        val userSerial: Long,
        val label: String,
    ) : HomeItem() {
        override fun at(container: Int, page: Int, x: Int, y: Int) = copy(container = container, page = page, x = x, y = y)
    }
}

@Immutable
@Serializable
data class HomeLayout(
    val items: List<HomeItem> = emptyList(),
    val nextId: Long = 1,
    val seeded: Boolean = false,
) {
    val pageCount: Int get() = (items.filter { it.container == CONTAINER_DESKTOP }.maxOfOrNull { it.page } ?: 0) + 1
    fun desktop(page: Int) = items.filter { it.container == CONTAINER_DESKTOP && it.page == page }
    val dock: List<HomeItem> get() = items.filter { it.container == CONTAINER_DOCK }
    fun byId(id: Long) = items.firstOrNull { it.id == id }
}

data class GridSpec(val columns: Int, val rows: Int, val dockSlots: Int)

data class CellPos(val container: Int, val page: Int, val x: Int, val y: Int)

/** Pure layout operations — easy to reason about, no side effects. */
object LayoutOps {

    fun occupancy(layout: HomeLayout, page: Int, grid: GridSpec, excludeId: Long = -1): Array<BooleanArray> {
        val occ = Array(grid.rows) { BooleanArray(grid.columns) }
        for (it in layout.items) {
            if (it.container != CONTAINER_DESKTOP || it.page != page || it.id == excludeId) continue
            for (dy in 0 until it.spanY) for (dx in 0 until it.spanX) {
                val cx = it.x + dx
                val cy = it.y + dy
                if (cy in 0 until grid.rows && cx in 0 until grid.columns) occ[cy][cx] = true
            }
        }
        return occ
    }

    fun fits(occ: Array<BooleanArray>, x: Int, y: Int, sx: Int, sy: Int, grid: GridSpec): Boolean {
        if (x < 0 || y < 0 || x + sx > grid.columns || y + sy > grid.rows) return false
        for (dy in 0 until sy) for (dx in 0 until sx) if (occ[y + dy][x + dx]) return false
        return true
    }

    fun findFree(layout: HomeLayout, page: Int, sx: Int, sy: Int, grid: GridSpec, excludeId: Long = -1): Pair<Int, Int>? {
        val occ = occupancy(layout, page, grid, excludeId)
        for (y in 0 until grid.rows) for (x in 0 until grid.columns) if (fits(occ, x, y, sx, sy, grid)) return x to y
        return null
    }

    /** Nearest free spot to (tx, ty) on a page — used when dropping onto an occupied cell. */
    fun findNearest(layout: HomeLayout, page: Int, tx: Int, ty: Int, sx: Int, sy: Int, grid: GridSpec, excludeId: Long): Pair<Int, Int>? {
        val occ = occupancy(layout, page, grid, excludeId)
        var best: Pair<Int, Int>? = null
        var bestD = Int.MAX_VALUE
        for (y in 0 until grid.rows) for (x in 0 until grid.columns) {
            if (!fits(occ, x, y, sx, sy, grid)) continue
            val d = (x - tx) * (x - tx) + (y - ty) * (y - ty)
            if (d < bestD) { bestD = d; best = x to y }
        }
        return best
    }

    fun findFreeAnywhere(layout: HomeLayout, sx: Int, sy: Int, grid: GridSpec, startPage: Int = 0): CellPos {
        val pages = layout.pageCount
        for (p in (startPage until pages) + (0 until startPage)) {
            findFree(layout, p, sx, sy, grid)?.let { return CellPos(CONTAINER_DESKTOP, p, it.first, it.second) }
        }
        return CellPos(CONTAINER_DESKTOP, pages, 0, 0)
    }

    fun itemAt(layout: HomeLayout, container: Int, page: Int, x: Int, y: Int): HomeItem? =
        layout.items.firstOrNull {
            it.container == container &&
                (container == CONTAINER_DOCK || it.page == page) &&
                x >= it.x && x < it.x + it.spanX && y >= it.y && y < it.y + it.spanY
        }

    fun add(layout: HomeLayout, make: (id: Long) -> HomeItem): HomeLayout =
        layout.copy(items = layout.items + make(layout.nextId), nextId = layout.nextId + 1)

    fun remove(layout: HomeLayout, id: Long) = layout.copy(items = layout.items.filterNot { it.id == id })

    fun replace(layout: HomeLayout, item: HomeItem) =
        layout.copy(items = layout.items.map { if (it.id == item.id) item else it })

    fun addAppAnywhere(layout: HomeLayout, key: String, grid: GridSpec, startPage: Int = 0): HomeLayout {
        if (layout.items.any { it is HomeItem.App && it.app == key && it.container == CONTAINER_DESKTOP }) return layout
        val pos = findFreeAnywhere(layout, 1, 1, grid, startPage)
        return add(layout) { HomeItem.App(it, pos.container, pos.page, pos.x, pos.y, key) }
    }

    fun removePackage(layout: HomeLayout, pkg: String, serial: Long): HomeLayout {
        fun gone(key: String) = AppKeys.packageOf(key) == pkg && AppKeys.serialOf(key) == serial
        val items = layout.items.mapNotNull { item ->
            when (item) {
                is HomeItem.App -> if (gone(item.app)) null else item
                is HomeItem.Shortcut -> if (item.packageName == pkg && item.userSerial == serial) null else item
                is HomeItem.Folder -> dissolveIfNeeded(item.copy(apps = item.apps.filterNot(::gone)))
                is HomeItem.Widget -> item
            }
        }
        return compactPages(layout.copy(items = items))
    }

    /** Folder with 1 app becomes that app; empty folder disappears. */
    fun dissolveIfNeeded(folder: HomeItem.Folder): HomeItem? = when (folder.apps.size) {
        0 -> null
        1 -> HomeItem.App(folder.id, folder.container, folder.page, folder.x, folder.y, folder.apps.first())
        else -> folder
    }

    /** Removes empty pages (keeps page 0) so the pager never shows blank screens. */
    fun compactPages(layout: HomeLayout): HomeLayout {
        val used = layout.items.filter { it.container == CONTAINER_DESKTOP }.map { it.page }.toSortedSet()
        if (used.isEmpty()) return layout
        // Page 0 stays 0, every other used page becomes 1..n in order.
        val remap = HashMap<Int, Int>()
        var idx = 1
        for (p in used) remap[p] = if (p == 0) 0 else idx++
        if (remap.all { (k, v) -> k == v }) return layout
        return layout.copy(items = layout.items.map {
            if (it.container == CONTAINER_DESKTOP) it.at(it.container, remap[it.page] ?: it.page, it.x, it.y) else it
        })
    }

    /** After the grid shrinks, pull out-of-bounds items back into free cells. */
    fun normalize(layout: HomeLayout, grid: GridSpec): HomeLayout {
        var out = layout
        val bad = layout.items.filter {
            if (it.container == CONTAINER_DOCK) it.x >= grid.dockSlots
            else it.x + it.spanX > grid.columns || it.y + it.spanY > grid.rows
        }
        if (bad.isEmpty()) return layout
        for (item in bad) {
            out = remove(out, item.id)
            val sx = item.spanX.coerceAtMost(grid.columns)
            val sy = item.spanY.coerceAtMost(grid.rows)
            val pos = findFreeAnywhere(out, sx, sy, grid, item.page.coerceAtLeast(0))
            val moved = when (item) {
                is HomeItem.Widget -> item.copy(container = CONTAINER_DESKTOP, page = pos.page, x = pos.x, y = pos.y, spanX = sx, spanY = sy)
                else -> item.at(CONTAINER_DESKTOP, pos.page, pos.x, pos.y)
            }
            out = out.copy(items = out.items + moved)
        }
        return out
    }
}

/** Persists the home layout as JSON in app-private storage (atomic writes, debounced). */
class LayoutRepository(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "home_layout.json")
    private val _state = MutableStateFlow(load())
    val state: StateFlow<HomeLayout> = _state.asStateFlow()
    val value: HomeLayout get() = _state.value
    private var saveJob: Job? = null

    private fun load(): HomeLayout = runCatching {
        if (file.exists()) LauncherJson.decodeFromString(HomeLayout.serializer(), file.readText()) else HomeLayout()
    }.getOrDefault(HomeLayout())

    fun update(transform: (HomeLayout) -> HomeLayout) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(300)
            write(next)
        }
    }

    fun flush() {
        saveJob?.cancel()
        write(_state.value)
    }

    private fun write(layout: HomeLayout) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(LauncherJson.encodeToString(HomeLayout.serializer(), layout))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }
}
