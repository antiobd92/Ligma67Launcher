package com.ligma67.launcher.ui.home

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ligma67.launcher.anim.Motion
import com.ligma67.launcher.data.AppInfo
import com.ligma67.launcher.data.LauncherSettings
import com.ligma67.launcher.data.SortMode
import com.ligma67.launcher.data.normalizeForSearch
import com.ligma67.launcher.glass.glass
import com.ligma67.launcher.system.SystemActions
import com.ligma67.launcher.ui.theme.LocalLauncherColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

fun searchApps(apps: List<AppInfo>, query: String, labels: Map<String, String>): List<AppInfo> {
    val q = normalizeForSearch(query.trim())
    if (q.isEmpty()) return apps
    return apps.mapNotNull { a ->
        val name = labels[a.key]?.let { normalizeForSearch(it) } ?: a.searchKey
        val score = when {
            name.startsWith(q) -> 4
            name.split(' ', '-', '_').any { it.startsWith(q) } -> 3
            a.initials.startsWith(q) -> 2
            name.contains(q) -> 1
            a.packageName.contains(q) -> 0
            else -> -1
        }
        if (score >= 0) a to score else null
    }.sortedByDescending { it.second }.map { it.first }
}

private class DrawerScroll(private val state: LauncherState) : NestedScrollConnection {
    private fun h() = (state.screen.height * 0.85f).coerceAtLeast(1f)

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val p = state.drawer.value
        if (source == NestedScrollSource.UserInput && available.y < 0f && p < 1f && p > 0f && !state.drawer.isRunning) {
            val dp = (-available.y / h()).coerceAtMost(1f - p)
            state.drawer.snap(p + dp)
            return Offset(0f, -dp * h())
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.y > 0f) {
            val p = state.drawer.value
            val dp = (available.y / h()).coerceAtMost(p)
            state.drawer.snap(p - dp)
            return Offset(0f, dp * h())
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val p = state.drawer.value
        if (p < 0.999f && p > 0.001f && !state.drawer.isRunning) {
            state.settleDrawer(-available.y / h())
            return available
        }
        return Velocity.Zero
    }
}

@Composable
fun AppDrawer(state: LauncherState, apps: List<AppInfo>, s: LauncherSettings) {
    val usage by state.app.apps.usage.collectAsState()
    val query = state.searchQuery
    val list = remember(apps, s.hiddenApps, s.drawerSort, usage, query, s.customLabels) {
        val visible = apps.filter { it.key !in s.hiddenApps }
        if (query.isNotBlank()) {
            searchApps(visible, query, s.customLabels)
        } else {
            val byLabel = if (s.customLabels.isEmpty()) visible else visible.sortedBy { (s.customLabels[it.key] ?: it.label).lowercase() }
            when (s.drawerSort) {
                SortMode.AZ -> byLabel
                SortMode.ZA -> byLabel.reversed()
                SortMode.MOST_USED -> byLabel.sortedByDescending { usage[it.key] ?: 0 }
                SortMode.RECENT -> byLabel.sortedByDescending { it.firstInstallTime }
            }
        }
    }
    val colors = LocalLauncherColors.current
    val grid = rememberLazyGridState()
    val scroll = remember(state) { DrawerScroll(state) }
    val back = remember { Motion(0f) }
    val scope = rememberCoroutineScope()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val tint = ((s.drawerTintAlpha * 255).toInt().coerceIn(0, 255) shl 24) or (if (colors.darkGlass) 0x0E1116 else 0xFFFFFF)

    LaunchedEffect(grid) {
        snapshotFlow { state.drawer.value <= 0.001f }.distinctUntilChanged().collect { closed ->
            if (closed && grid.firstVisibleItemIndex > 0) grid.scrollToItem(0)
        }
    }

    PredictiveBackHandler(enabled = state.drawer.target > 0.5f && state.openFolderId == null && state.menu == null) { progress ->
        try {
            progress.collect { back.snap(it.progress) }
            back.animateTo(scope, 0f, spring(stiffness = 500f))
            state.closeDrawer()
        } catch (e: CancellationException) {
            back.animateTo(scope, 0f, spring(stiffness = 600f))
        }
    }

    val headerDrag = rememberDraggableState { d ->
        val h = (state.screen.height * 0.85f).coerceAtLeast(1f)
        state.drawer.snap((state.drawer.value - d / h).coerceIn(0f, 1f))
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = state.drawer.value
                val bp = back.value
                translationY = (1f - p) * size.height + bp * 56.dp.toPx()
                val sc = 1f - 0.08f * bp
                scaleX = sc
                scaleY = sc
                alpha = if (p <= 0.002f) 0f else 1f
            },
    ) {
        Box(Modifier.fillMaxSize().glass(30.dp, tint = tint, lite = true, motion = { state.drawer.value }))
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .draggable(
                        headerDrag,
                        Orientation.Vertical,
                        onDragStopped = { v -> state.settleDrawer(-v / (state.screen.height * 0.85f).coerceAtLeast(1f)) },
                    )
                    .padding(top = 10.dp, bottom = 4.dp),
            ) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .size(width = 36.dp, height = 4.dp)
                        .glass(Dp.Infinity, tint = 0x66FFFFFF),
                )
                if (s.drawerSearch) {
                    SearchBar(state, Modifier.padding(top = 14.dp)) {
                        list.firstOrNull()?.let { first ->
                            val slot = "d:${first.key}"
                            state.launchApp(first, slot, state.registry.bounds(slot), state.app.icons.peek(first.key))
                        }
                    }
                } else {
                    Spacer(Modifier.height(24.dp))
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(s.drawerColumns),
                state = grid,
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = navBottom + 24.dp),
                modifier = Modifier.weight(1f).nestedScroll(scroll),
            ) {
                if (query.isNotBlank() && s.drawerWebSearch) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "web") {
                        Row(
                            Modifier
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .fillMaxWidth()
                                .glass(Dp.Infinity)
                                .pointerInput(query) { detectTapGestures { SystemActions.webSearch(state.activity, query) } }
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Search, null, tint = colors.onGlass, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Search the web for \"$query\"", color = colors.onGlass, fontSize = 14.sp)
                        }
                    }
                }
                items(list, key = { it.key }) { app ->
                    AppIconView(
                        state, app, "d:${app.key}", s.drawerIconDp.dp, s.drawerLabels,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
                if (list.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                        Text(
                            "No apps found",
                            color = colors.onGlassDim,
                            modifier = Modifier.padding(32.dp),
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(state: LauncherState, modifier: Modifier, onGo: () -> Unit) {
    val colors = LocalLauncherColors.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(state.searchFocusTick) {
        if (state.searchFocusTick > 0) {
            delay(120)
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { state.drawer.target < 0.5f }.distinctUntilChanged().collect { closing ->
            if (closing) {
                keyboard?.hide()
                focusManager.clearFocus()
            }
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .height(50.dp)
            .glass(Dp.Infinity, motion = { state.drawer.value }),
    ) {
        Icon(
            Icons.Filled.Search, null, tint = colors.onGlassDim,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp).size(20.dp),
        )
        BasicTextField(
            value = state.searchQuery,
            onValueChange = { state.searchQuery = it },
            singleLine = true,
            textStyle = TextStyle(color = colors.onGlass, fontSize = 16.sp),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .padding(start = 48.dp, end = 16.dp)
                .focusRequester(focus),
            decorationBox = { inner ->
                if (state.searchQuery.isEmpty()) Text("Search apps", color = colors.onGlassDim, fontSize = 16.sp)
                inner()
            },
        )
    }
}
