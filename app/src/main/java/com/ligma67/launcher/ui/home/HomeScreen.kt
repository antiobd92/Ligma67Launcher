package com.ligma67.launcher.ui.home

import android.app.WallpaperManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ligma67.launcher.anim.drawLaunchCard
import com.ligma67.launcher.anim.lerpF
import com.ligma67.launcher.anim.smoothstep
import com.ligma67.launcher.data.AppInfo
import com.ligma67.launcher.data.DockStyle
import com.ligma67.launcher.data.HomeItem
import com.ligma67.launcher.data.HomeLayout
import com.ligma67.launcher.data.IndicatorStyle
import com.ligma67.launcher.data.LauncherSettings
import com.ligma67.launcher.data.PageTransition
import com.ligma67.launcher.glass.GlassPainter
import com.ligma67.launcher.glass.LocalGlass
import com.ligma67.launcher.glass.glass
import com.ligma67.launcher.system.SystemActions
import com.ligma67.launcher.ui.theme.LauncherTheme
import com.ligma67.launcher.ui.theme.LocalLauncherColors
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun LauncherRoot(state: LauncherState) {
    val app = state.app
    val settings by app.settings.state.collectAsState()
    val tone by app.backdrop.tone.collectAsState()
    val blurred by app.backdrop.blurred.collectAsState()
    val sharp by app.backdrop.sharp.collectAsState()
    val layout by app.layout.state.collectAsState()
    val apps by app.apps.apps.collectAsState()
    val appsByKey = remember(apps) { apps.associateBy { it.key } }
    state.settings = settings

    // Lowest-priority back handler: never let Back close the home screen.
    BackHandler(enabled = true) {}

    LauncherTheme(settings, tone, blurred) {
        val density = LocalDensity.current
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        SideEffect { state.dropZoneBottom = with(density) { (statusTop + 84.dp).toPx() } }
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged {
                    val r = Rect(0f, 0f, it.width.toFloat(), it.height.toFloat())
                    state.screen = r
                    state.animator.screen = r
                }
                .pointerInput(Unit) {
                    // Drag & drop interceptor: once an icon is lifted, the root owns the pointer.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull() ?: break
                            if (!state.drag.hasPending) {
                                if (!ch.pressed) break else continue
                            }
                            if (ch.pressed) {
                                if (state.drag.move(ch.position)) ch.consume()
                            } else {
                                val wasActive = state.drag.active != null
                                state.drag.up()
                                if (wasActive) ch.consume()
                                break
                            }
                        }
                    }
                },
        ) {
            WallpaperLayer(sharp, settings.wallpaperDim)
            HomeLayer(state, layout, appsByKey, settings)
            AppDrawer(state, apps, settings)
            FolderOverlay(state, layout, appsByKey, settings)
            DragOverlay(state)
            MenuOverlay(state)
            WidgetPicker(state)
            AppPicker(state, apps)
            RenameDialog(state)
            DefaultLauncherBanner(state)
            LaunchCardLayer(state)
        }
    }
}

@Composable
private fun WallpaperLayer(sharp: ImageBitmap?, dim: Float) {
    if (sharp != null) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawImage(
                        image = sharp,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(sharp.width, sharp.height),
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        filterQuality = FilterQuality.Low,
                    )
                },
        )
    }
    if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
}

@Composable
private fun LaunchCardLayer(state: LauncherState) {
    val env = LocalGlass.current
    val colors = LocalLauncherColors.current
    val painter = remember { GlassPainter() }
    val surface = colors.surface.toArgb()
    Spacer(Modifier.fillMaxSize().drawBehind { drawLaunchCard(state.animator, painter, env, surface) })
}

@Composable
private fun HomeLayer(state: LauncherState, layout: HomeLayout, appsByKey: Map<String, AppInfo>, s: LauncherSettings) {
    val a = state.animator
    val density = LocalDensity.current
    val maxBlur = with(density) { 26.dp.toPx() }
    val dragState = rememberDraggableState { delta -> state.onHomeDrag(delta) }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val d = state.drawer.value.coerceIn(0f, 1f)
                val f = state.folder.value.coerceIn(0f, 1f)
                val sc = a.homeScale.value * lerpF(1f, 0.93f, d)
                scaleX = sc
                scaleY = sc
                alpha = (a.homeAlpha.value * (1f - d)).coerceIn(0f, 1f)
                val blur = max(a.homeBlur.value, f * 0.9f) * maxBlur
                renderEffect = if (Build.VERSION.SDK_INT >= 31 && blur > 0.5f) BlurEffect(blur, blur, TileMode.Decal) else null
            }
            .onPlaced { state.registry.homeCoords = it }
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                enabled = state.drag.active == null && state.openFolderId == null,
                onDragStarted = { state.onHomeDragStart() },
                onDragStopped = { v -> state.onHomeDragEnd(v) },
            ),
    ) {
        val dragging = state.drag.active != null
        val count = layout.pageCount + if (dragging) 1 else 0
        val pagerState = rememberPagerState(initialPage = s.defaultPage.coerceIn(0, layout.pageCount - 1)) { count }
        state.pager = pagerState
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            HomePager(state, pagerState, layout, appsByKey, s, Modifier.weight(1f))
            PageIndicator(pagerState, s.pageIndicator)
            if (s.dockEnabled) Dock(state, layout, appsByKey, s, navBottom)
            else Spacer(Modifier.height(navBottom + 12.dp))
        }
    }
}

@Composable
private fun HomePager(
    state: LauncherState,
    pagerState: PagerState,
    layout: HomeLayout,
    appsByKey: Map<String, AppInfo>,
    s: LauncherSettings,
    modifier: Modifier,
) {
    val settings by rememberUpdatedState(s)
    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        beyondViewportPageCount = 1,
        key = { it },
    ) { page ->
        val holder = remember { CoordsHolder() }
        Box(
            Modifier
                .fillMaxSize()
                .onPlaced { holder.coords = it }
                .graphicsLayer { pageTransition(settings.pageTransition, page, pagerState) },
        ) {
            val ctx = state.activity
            Spacer(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { state.perform(settings.doubleTap, settings.doubleTapApp) },
                            onTap = { off ->
                                val w = holder.toWindow(off)
                                runCatching {
                                    WallpaperManager.getInstance(ctx).sendWallpaperCommand(
                                        ctx.window.decorView.windowToken, WallpaperManager.COMMAND_TAP,
                                        w.x.roundToInt(), w.y.roundToInt(), 0, null,
                                    )
                                }
                            },
                            onLongPress = { off ->
                                if (!state.drag.hasPending) {
                                    val w = holder.toWindow(off)
                                    state.homeMenu(Rect(w, Size(1f, 1f)))
                                }
                            },
                        )
                    },
            )
            PageGrid(state, page, layout.desktop(page), appsByKey, s)
        }
    }
}

private fun GraphicsLayerScope.pageTransition(t: PageTransition, page: Int, ps: PagerState) {
    if (t == PageTransition.SLIDE) return
    val pos = (page - ps.currentPage - ps.currentPageOffsetFraction).coerceIn(-1f, 1f)
    val ap = abs(pos)
    if (ap < 0.0001f) return
    val w = size.width
    when (t) {
        PageTransition.SLIDE -> Unit
        PageTransition.DEPTH -> if (pos > 0) {
            translationX = -w * pos
            alpha = 1f - pos
            val sc = 0.75f + 0.25f * (1f - pos)
            scaleX = sc; scaleY = sc
        }
        PageTransition.CUBE -> {
            cameraDistance = 14f * density
            transformOrigin = TransformOrigin(if (pos < 0) 1f else 0f, 0.5f)
            rotationY = 70f * pos
        }
        PageTransition.ZOOM -> {
            val sc = 1f - 0.18f * ap
            scaleX = sc; scaleY = sc
            alpha = 1f - 0.6f * ap
        }
        PageTransition.FADE -> {
            translationX = -w * pos
            alpha = 1f - ap
        }
        PageTransition.CAROUSEL -> {
            cameraDistance = 14f * density
            val sc = 1f - 0.2f * ap
            scaleX = sc; scaleY = sc
            rotationY = -28f * pos
            alpha = 1f - 0.35f * ap
        }
        PageTransition.STACK -> if (pos > 0) {
            translationX = -w * pos
            val sc = 1f - 0.12f * pos
            scaleX = sc; scaleY = sc
            alpha = 1f - pos
        }
    }
}

@Composable
private fun PageGrid(state: LauncherState, page: Int, items: List<HomeItem>, appsByKey: Map<String, AppInfo>, s: LauncherSettings) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(horizontal = s.homeSidePaddingDp.dp)
            .onPlaced { state.gridCoords[page] = it },
    ) {
        DisposableEffect(page) { onDispose { state.gridCoords.remove(page) } }
        val cw = maxWidth / s.homeColumns
        val ch = maxHeight / s.homeRows
        for (item in items) {
            key(item.id) {
                Box(
                    Modifier
                        .offset(cw * item.x, ch * item.y)
                        .size(cw * item.spanX, ch * item.spanY),
                    contentAlignment = Alignment.Center,
                ) {
                    HomeItemView(state, item, appsByKey, s.homeIconDp.dp, s.homeLabels, cw, ch)
                }
            }
        }
    }
}

@Composable
private fun Dock(
    state: LauncherState,
    layout: HomeLayout,
    appsByKey: Map<String, AppInfo>,
    s: LauncherSettings,
    navBottom: androidx.compose.ui.unit.Dp,
) {
    val items = layout.dock
    val floating = s.dockStyle == DockStyle.FLOATING
    val outer = if (floating) Modifier.padding(start = s.dockMarginDp.dp, end = s.dockMarginDp.dp, bottom = navBottom + s.dockMarginDp.dp)
    else Modifier
    val bg = when (s.dockStyle) {
        DockStyle.FLOATING -> Modifier.glass(s.dockCornerDp.dp)
        DockStyle.FULL -> Modifier.glass(s.dockCornerDp.dp * 0.6f)
        DockStyle.NONE -> Modifier
    }
    Box(outer.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(bg)
                .padding(
                    top = s.dockPaddingDp.dp,
                    bottom = s.dockPaddingDp.dp + if (floating) 0.dp else navBottom,
                    start = 6.dp, end = 6.dp,
                )
                .onPlaced { state.dockCoords = it },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in 0 until s.dockColumns) {
                val item = items.firstOrNull { it.x == i }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (item != null) {
                        key(item.id) {
                            HomeItemView(state, item, appsByKey, s.dockIconDp.dp, s.dockLabels, s.dockIconDp.dp, s.dockIconDp.dp)
                        }
                    } else {
                        Spacer(Modifier.size(s.dockIconDp.dp))
                    }
                }
            }
        }
    }
}

/** Liquid page indicator: the active pill stretches between dots like a droplet. */
@Composable
private fun PageIndicator(pager: PagerState, style: IndicatorStyle) {
    val count = pager.pageCount
    val color = LocalLauncherColors.current.label
    if (style == IndicatorStyle.NONE || count <= 1) {
        Spacer(Modifier.height(14.dp))
        return
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        val glassMod = if (style == IndicatorStyle.LIQUID) Modifier.glass(androidx.compose.ui.unit.Dp.Infinity) else Modifier
        Canvas(
            glassMod
                .height(18.dp)
                .width((count * 14 + 14).dp),
        ) {
            val pos = pager.currentPage + pager.currentPageOffsetFraction
            val dot = 6.dp.toPx()
            val gap = 14.dp.toPx()
            val start = (size.width - (count - 1) * gap) / 2f
            val cy = size.height / 2f
            for (i in 0 until count) {
                drawCircle(color.copy(alpha = 0.32f), dot / 2f, Offset(start + i * gap, cy))
            }
            when (style) {
                IndicatorStyle.LIQUID -> {
                    val base = floor(pos)
                    val f = pos - base
                    val head = start + gap * (base + smoothstep(0f, 0.65f, f))
                    val tail = start + gap * (base + smoothstep(0.35f, 1f, f))
                    val l = minOf(head, tail) - dot / 2f
                    val r = maxOf(head, tail) + dot / 2f
                    drawRoundRect(color, Offset(l, cy - dot / 2f), Size(r - l, dot), CornerRadius(dot / 2f))
                }
                IndicatorStyle.DOTS -> drawCircle(color, dot * 0.6f, Offset(start + pos * gap, cy))
                IndicatorStyle.LINE -> {
                    val w = gap * 0.9f
                    drawRoundRect(color, Offset(start + pos * gap - w / 2f, cy - 1.5.dp.toPx()), Size(w, 3.dp.toPx()), CornerRadius(2.dp.toPx()))
                }
                IndicatorStyle.NONE -> Unit
            }
        }
    }
}

@Composable
private fun DefaultLauncherBanner(state: LauncherState) {
    if (!state.defaultLauncherBanner) return
    val c = LocalLauncherColors.current
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
                .glass(androidx.compose.ui.unit.Dp.Infinity)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Make Ligma67 your home screen", color = c.onGlass, fontSize = 14.sp)
            Spacer(Modifier.width(14.dp))
            Text(
                "Set",
                color = c.accent,
                fontSize = 14.sp,
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures {
                        state.defaultLauncherBanner = false
                        SystemActions.requestDefaultLauncher(state.activity)
                    }
                },
            )
            Spacer(Modifier.width(14.dp))
            Text(
                "✕",
                color = c.onGlassDim,
                fontSize = 14.sp,
                modifier = Modifier.pointerInput(Unit) { detectTapGestures { state.defaultLauncherBanner = false } },
            )
        }
    }
}
