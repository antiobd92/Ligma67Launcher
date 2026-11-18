package com.ligma67.launcher.ui.home

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ligma67.launcher.data.AppInfo
import com.ligma67.launcher.data.HomeItem
import com.ligma67.launcher.data.IconRepository
import com.ligma67.launcher.data.IconStyle
import com.ligma67.launcher.glass.glass
import com.ligma67.launcher.system.LauncherWidgetView
import com.ligma67.launcher.ui.theme.LocalLauncherColors
import com.ligma67.launcher.ui.theme.LocalSettings
import kotlin.math.roundToInt

/** Caches a node's coordinates so animations can resolve its position lazily. */
class CoordsHolder {
    var coords: LayoutCoordinates? = null
    fun bounds(): Rect = coords?.takeIf { it.isAttached }?.boundsInWindow() ?: Rect.Zero
    fun center(): Offset = coords?.takeIf { it.isAttached }?.let { c ->
        val p = c.positionInWindow()
        Offset(p.x + c.size.width / 2f, p.y + c.size.height / 2f)
    } ?: Offset.Zero
    fun toWindow(local: Offset): Offset = coords?.takeIf { it.isAttached }?.localToWindow(local) ?: local
}

@Composable
fun rememberAppIcon(icons: IconRepository, app: AppInfo): ImageBitmap? {
    val version by icons.version.collectAsState()
    val bmp by produceState(initialValue = icons.peek(app.key), app.key, version) {
        value = icons.peek(app.key) ?: icons.load(app)
    }
    return bmp
}

@Composable
fun IconLabel(text: String, modifier: Modifier = Modifier) {
    val s = LocalSettings.current
    val c = LocalLauncherColors.current
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = c.label,
            fontSize = s.labelSizeSp.sp,
            fontWeight = if (s.labelBold) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            shadow = if (s.labelShadow) Shadow(c.labelShadow, Offset(0f, 1.5f), 5f) else null,
        ),
        maxLines = s.labelMaxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Generic launcher icon cell: press squish, bounce, wave, lift-off for the launch card,
 * long-press → menu + drag. All animated values are read inside graphicsLayer only.
 */
@Composable
fun IconCell(
    state: LauncherState,
    slot: String,
    key: String?,
    image: ImageBitmap?,
    label: String?,
    iconSize: Dp,
    onTap: (Rect, ImageBitmap?) -> Unit,
    onLongPress: (Rect, Offset, ImageBitmap?) -> Unit,
    modifier: Modifier = Modifier,
    plate: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    val s = LocalSettings.current
    val holder = remember { CoordsHolder() }
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(
        if (pressed) s.iconPressScale else 1f,
        spring(dampingRatio = 0.5f, stiffness = 900f),
        label = "press",
    )
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLongPress)
    val img by rememberUpdatedState(image)

    DisposableEffect(slot, key) { onDispose { state.registry.unregister(slot, key) } }

    Column(
        modifier.graphicsLayer {
            val a = state.animator
            val wp = a.waveProgress(slot) { holder.center() }
            val ws = a.waveScale(wp)
            scaleX = ws
            scaleY = ws
            alpha = if (state.drag.sourceSlot == slot) 0f else a.waveAlpha(wp)
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(iconSize)
                .onPlaced {
                    holder.coords = it
                    state.registry.register(slot, key, it)
                }
                .graphicsLayer {
                    val sc = press * state.animator.bounceScale(slot)
                    scaleX = sc
                    scaleY = sc
                    alpha = if (state.animator.liftedSlot == slot) 0f else 1f
                }
                .pointerInput(slot) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                        onTap = { tap(holder.bounds(), img) },
                        onLongPress = { off -> long(holder.bounds(), holder.toWindow(off), img) },
                    )
                }
                .then(if (plate) Modifier.glass(0.dp, iconShape = s.iconShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (content != null) {
                content()
            } else if (image != null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawBehind {
                            drawImage(
                                image = image,
                                srcOffset = IntOffset.Zero,
                                srcSize = IntSize(image.width, image.height),
                                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                                filterQuality = FilterQuality.Medium,
                            )
                        },
                )
            }
        }
        if (label != null) {
            IconLabel(label, Modifier.padding(top = 5.dp).widthIn(max = iconSize + 22.dp))
        }
    }
}

private fun usesPlate(style: IconStyle) = style == IconStyle.GLASS_PLATE || style == IconStyle.CLEAR

@Composable
fun AppIconView(
    state: LauncherState,
    app: AppInfo,
    slot: String,
    iconSize: Dp,
    showLabel: Boolean,
    modifier: Modifier = Modifier,
    homeItem: HomeItem? = null,
    folderId: Long? = null,
) {
    val s = LocalSettings.current
    val image = rememberAppIcon(state.app.icons, app)
    IconCell(
        state = state,
        slot = slot,
        key = app.key,
        image = image,
        label = if (showLabel) state.label(app) else null,
        iconSize = iconSize,
        plate = usesPlate(s.iconStyle),
        modifier = modifier,
        onTap = { b, img -> state.launchApp(app, slot, b, img) },
        onLongPress = { b, p, img ->
            val source = when {
                homeItem != null -> DragSource.Home(homeItem)
                folderId != null -> DragSource.FolderApp(folderId, app.key)
                else -> DragSource.Drawer(app)
            }
            state.drag.onLongPress(source, slot, p, b, img)
            state.appMenu(app, b, homeItem, folderId)
        },
    )
}

@Composable
fun ShortcutIconView(state: LauncherState, item: HomeItem.Shortcut, iconSize: Dp, showLabel: Boolean) {
    val icons = state.app.icons
    val version by icons.version.collectAsState()
    val image by produceState<ImageBitmap?>(null, item, version) { value = icons.loadShortcut(item) }
    val slot = "i:${item.id}"
    IconCell(
        state = state,
        slot = slot,
        key = "sc:${item.packageName}:${item.shortcutId}",
        image = image,
        label = if (showLabel) item.label else null,
        iconSize = iconSize,
        onTap = { b, img -> state.launchShortcut(item, slot, b, img) },
        onLongPress = { b, p, img ->
            state.drag.onLongPress(DragSource.Home(item), slot, p, b, img)
            state.showMenu(
                MenuState(
                    b, item.label,
                    listOf(MenuEntry("Remove", Icons.Filled.Clear) { state.removeItem(item) }),
                ),
            )
        },
    )
}

/** Liquid-glass folder bubble with a live mini-grid preview. */
@Composable
fun FolderIconView(
    state: LauncherState,
    folder: HomeItem.Folder,
    appsByKey: Map<String, AppInfo>,
    iconSize: Dp,
    showLabel: Boolean,
) {
    val s = LocalSettings.current
    val slot = "i:${folder.id}"
    val count = s.folderPreviewCount
    val per = if (count == 9) 3 else 2
    val previews = folder.apps.take(count).mapNotNull { appsByKey[it] }
    IconCell(
        state = state,
        slot = slot,
        key = "folder:${folder.id}",
        image = null,
        label = if (showLabel) folder.title else null,
        iconSize = iconSize,
        onTap = { b, _ -> state.openFolder(folder, b) },
        onLongPress = { b, p, _ ->
            state.drag.onLongPress(DragSource.Home(folder), slot, p, b, null)
            state.folderMenu(folder, b)
        },
        content = {
            Box(
                Modifier
                    .fillMaxSize()
                    .glass(iconSize * s.iconShape.cornerFraction),
            ) {
                val icons = previews.map { rememberAppIcon(state.app.icons, it) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(iconSize * 0.14f)
                        .drawBehind {
                            val gap = size.width * 0.08f
                            val cell = (size.width - gap * (per - 1)) / per
                            icons.forEachIndexed { i, img ->
                                img ?: return@forEachIndexed
                                val cx = (i % per) * (cell + gap)
                                val cy = (i / per) * (cell + gap)
                                drawImage(
                                    image = img,
                                    srcOffset = IntOffset.Zero,
                                    srcSize = IntSize(img.width, img.height),
                                    dstOffset = IntOffset(cx.roundToInt(), cy.roundToInt()),
                                    dstSize = IntSize(cell.roundToInt(), cell.roundToInt()),
                                    filterQuality = FilterQuality.Medium,
                                )
                            }
                        },
                )
            }
        },
    )
}

/** Hosts a real AppWidgetHostView inside Compose. */
@Composable
fun WidgetCell(state: LauncherState, item: HomeItem.Widget, wDp: Dp, hDp: Dp) {
    val ctx = LocalContext.current
    val widgets = state.app.widgets
    val info = remember(item.widgetId) { widgets.info(item.widgetId) }
    val holder = remember { CoordsHolder() }
    val slot = "i:${item.id}"
    val density = LocalDensity.current
    val c = LocalLauncherColors.current
    Box(
        Modifier
            .fillMaxSize()
            .padding(6.dp)
            .onPlaced { holder.coords = it }
            .graphicsLayer {
                alpha = if (state.drag.sourceSlot == slot) 0f else 1f
                val sc = state.animator.bounceScale(slot)
                scaleX = sc
                scaleY = sc
            },
    ) {
        if (info == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .glass(22.dp)
                    .pointerInput(item.id) {
                        detectTapGestures(onLongPress = { off ->
                            state.drag.onLongPress(DragSource.Home(item), slot, holder.toWindow(off), holder.bounds(), null)
                            state.widgetMenu(item, holder.bounds())
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("Widget unavailable", color = c.onGlassDim, fontSize = 12.sp)
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { _ ->
                    (widgets.host.createView(ctx, item.widgetId, info) as LauncherWidgetView).apply {
                        if (Build.VERSION.SDK_INT >= 31) clipToOutline = true
                    }
                },
                update = { v ->
                    v.onLongPress = { x, y ->
                        val b = holder.bounds()
                        state.drag.onLongPress(DragSource.Home(item), slot, Offset(x, y), b, null)
                        state.widgetMenu(item, b)
                    }
                    with(density) { widgets.updateSize(v, (wDp - 12.dp).value, (hDp - 12.dp).value) }
                },
            )
        }
    }
}

@Composable
fun HomeItemView(
    state: LauncherState,
    item: HomeItem,
    appsByKey: Map<String, AppInfo>,
    iconSize: Dp,
    showLabel: Boolean,
    cellW: Dp,
    cellH: Dp,
) {
    when (item) {
        is HomeItem.App -> appsByKey[item.app]?.let {
            AppIconView(state, it, "i:${item.id}", iconSize, showLabel, homeItem = item)
        }
        is HomeItem.Folder -> FolderIconView(state, item, appsByKey, iconSize, showLabel)
        is HomeItem.Shortcut -> ShortcutIconView(state, item, iconSize, showLabel)
        is HomeItem.Widget -> WidgetCell(state, item, cellW * item.spanX, cellH * item.spanY)
    }
}

@Suppress("unused")
private val Transparent = Color.Transparent
