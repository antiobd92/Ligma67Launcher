package com.ligma67.launcher.ui.home

import android.appwidget.AppWidgetProviderInfo
import android.content.pm.ShortcutInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.ligma67.launcher.anim.lerpF
import com.ligma67.launcher.data.AppInfo
import com.ligma67.launcher.data.HomeItem
import com.ligma67.launcher.data.HomeLayout
import com.ligma67.launcher.data.LauncherSettings
import com.ligma67.launcher.glass.GlassPainter
import com.ligma67.launcher.glass.LocalGlass
import com.ligma67.launcher.glass.glass
import com.ligma67.launcher.ui.theme.LocalLauncherColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/* ----------------------------------------- Folder ----------------------------------------- */

@Composable
fun FolderOverlay(state: LauncherState, layout: HomeLayout, appsByKey: Map<String, AppInfo>, s: LauncherSettings) {
    val id = state.openFolderId ?: return
    val folder = layout.byId(id) as? HomeItem.Folder
    if (folder == null) {
        LaunchedEffect(id) { state.closeFolder(immediate = true) }
        return
    }
    val colors = LocalLauncherColors.current
    val holder = remember { CoordsHolder() }
    BackHandler { state.closeFolder() }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = state.folder.value.coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = s.homeDimOnOverlay))
                .pointerInput(id) { detectTapGestures { state.closeFolder() } },
        )
        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.86f)
                .onPlaced { holder.coords = it }
                .graphicsLayer {
                    val v = state.folder.value
                    val a = state.folderAnchor
                    val panel = holder.bounds()
                    if (panel.width > 0f && a.width > 0f) {
                        val sx = lerpF(a.width / panel.width, 1f, v)
                        val sy = lerpF(a.height / panel.height, 1f, v)
                        scaleX = sx
                        scaleY = sy
                        translationX = lerpF(a.center.x - panel.center.x, 0f, v)
                        translationY = lerpF(a.center.y - panel.center.y, 0f, v)
                    }
                    alpha = (v * 2.2f).coerceIn(0f, 1f)
                }
                .glass(s.glassCornerDp.dp, motion = { state.folder.value })
                .padding(horizontal = 14.dp, vertical = 18.dp),
        ) {
            Text(
                folder.title,
                color = colors.onGlass,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(start = 8.dp, bottom = 12.dp)
                    .clickable {
                        state.rename = RenameRequest("Rename folder", folder.title) { state.renameFolder(folder.id, it) }
                    },
            )
            val cols = s.folderColumns.coerceIn(2, 6)
            val rows = folder.apps.mapNotNull { appsByKey[it] }.chunked(cols)
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                for (row in rows) {
                    Row(Modifier.fillMaxWidth()) {
                        for (app in row) {
                            Box(Modifier.weight(1f).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                AppIconView(
                                    state, app, "f:${folder.id}:${app.key}", (s.homeIconDp * 0.94f).dp, true,
                                    folderId = folder.id,
                                )
                            }
                        }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/* ------------------------------------------ Menu ------------------------------------------ */

@Composable
fun MenuOverlay(state: LauncherState) {
    val m = state.menu ?: return
    val colors = LocalLauncherColors.current
    BackHandler { state.menu = null }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(m) { detectTapGestures { state.menu = null } },
    ) {
        Column(
            Modifier
                .layout { measurable, constraints ->
                    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    val margin = 12.dp.roundToPx()
                    val gap = 12.dp.roundToPx()
                    val top = 48.dp.roundToPx()
                    val aboveY = m.anchor.top.roundToInt() - p.height - gap
                    val y = if (aboveY > top) aboveY else (m.anchor.bottom.roundToInt() + gap)
                        .coerceAtMost(constraints.maxHeight - p.height - margin)
                    val x = (m.anchor.center.x.roundToInt() - p.width / 2)
                        .coerceIn(margin, (constraints.maxWidth - p.width - margin).coerceAtLeast(margin))
                    layout(constraints.maxWidth, constraints.maxHeight) { p.place(x, y) }
                }
                .graphicsLayer {
                    val v = state.menuProgress.value
                    val sc = lerpF(0.82f, 1f, v)
                    scaleX = sc
                    scaleY = sc
                    alpha = v.coerceIn(0f, 1f)
                }
                .width(250.dp)
                .glass(24.dp)
                .padding(vertical = 6.dp),
        ) {
            if (m.title != null) {
                Text(
                    m.title,
                    color = colors.onGlassDim,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
            for (sc in m.shortcuts) ShortcutRow(state, sc, m.anchor)
            if (m.shortcuts.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).height(1.dp).background(colors.onGlassDim.copy(alpha = 0.2f)))
            }
            for (e in m.entries) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            state.menu = null
                            e.action()
                        }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tint = if (e.destructive) Color(0xFFFF6B6B) else colors.onGlass
                    Icon(e.icon, null, tint = tint, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(e.label, color = tint, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun ShortcutRow(state: LauncherState, info: ShortcutInfo, anchor: Rect) {
    val colors = LocalLauncherColors.current
    val icon by produceState<ImageBitmap?>(null, info.id) { value = state.app.icons.shortcutIcon(info) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { state.launchShortcutInfo(info, anchor) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(24.dp).drawBehind {
                icon?.let {
                    drawImage(it, srcOffset = IntOffset.Zero, srcSize = IntSize(it.width, it.height), dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium)
                }
            },
        )
        Spacer(Modifier.width(12.dp))
        Text(
            (info.shortLabel ?: info.longLabel ?: "").toString(),
            color = colors.onGlass, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/* --------------------------------------- Drag & drop --------------------------------------- */

@Composable
fun DragOverlay(state: LauncherState) {
    val d = state.drag
    val src = d.active ?: return
    val colors = LocalLauncherColors.current
    val env = LocalGlass.current
    val painter = remember { GlassPainter() }
    val density = LocalDensity.current
    val canRemove = src is DragSource.Home || src is DragSource.FolderApp

    Box(Modifier.fillMaxSize()) {
        // Target highlight
        Box(
            Modifier.fillMaxSize().drawBehind {
                val t = d.target
                val radius = CornerRadius(18.dp.toPx())
                when (t) {
                    is DropTarget.Cell -> {
                        val c = state.gridCoords[t.page]?.takeIf { it.isAttached } ?: return@drawBehind
                        val cw = c.size.width / state.settings.homeColumns.toFloat()
                        val ch = c.size.height / state.settings.homeRows.toFloat()
                        val item = (src as? DragSource.Home)?.item
                        val sx = item?.spanX ?: 1
                        val sy = item?.spanY ?: 1
                        val tl = c.localToWindow(Offset(t.x * cw, t.y * ch))
                        val inset = 6.dp.toPx()
                        drawRoundRect(Color.White.copy(alpha = 0.14f), tl + Offset(inset, inset), Size(cw * sx - 2 * inset, ch * sy - 2 * inset), radius)
                        drawRoundRect(Color.White.copy(alpha = 0.45f), tl + Offset(inset, inset), Size(cw * sx - 2 * inset, ch * sy - 2 * inset), radius, style = Stroke(1.5.dp.toPx()))
                    }
                    is DropTarget.Dock -> {
                        val c = state.dockCoords?.takeIf { it.isAttached } ?: return@drawBehind
                        val b = c.boundsInWindow()
                        val w = b.width / state.settings.dockColumns
                        drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(b.left + w * t.index + 4.dp.toPx(), b.top + 4.dp.toPx()), Size(w - 8.dp.toPx(), b.height - 8.dp.toPx()), radius)
                    }
                    else -> Unit
                }
            },
        )

        // Drop zones
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .onGloballyPositioned { state.dropZoneBottom = it.boundsInWindow().bottom + with(density) { 16.dp.toPx() } },
        ) {
            DropZone("Remove", Icons.Filled.Close, d.target == DropTarget.Remove, canRemove, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            DropZone("App info", Icons.Filled.Info, d.target == DropTarget.Info, true, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            DropZone("Uninstall", Icons.Filled.Delete, d.target == DropTarget.Uninstall, true, Modifier.weight(1f))
        }

        // Floating item
        Box(
            Modifier.fillMaxSize().drawBehind {
                val lift = d.lift.value
                val scale = lerpF(1f, 1.12f, lift)
                val w = d.size.width * scale
                val h = d.size.height * scale
                val c = d.pointer - d.grab
                val left = c.x - w / 2f
                val top = c.y - h / 2f
                val icon = d.icon
                if (icon != null) {
                    drawImage(
                        icon, srcOffset = IntOffset.Zero, srcSize = IntSize(icon.width, icon.height),
                        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                        alpha = 0.95f, filterQuality = FilterQuality.Medium,
                    )
                } else {
                    drawIntoCanvas { cv ->
                        val nc = cv.nativeCanvas
                        val save = nc.save()
                        nc.translate(left, top)
                        painter.draw(nc, w, h, left, top, 22.dp.toPx(), env.style, env.backdrop, 0.95f)
                        nc.restoreToCount(save)
                    }
                }
            },
        )
    }
}

@Composable
private fun DropZone(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, enabled: Boolean, modifier: Modifier) {
    val colors = LocalLauncherColors.current
    val tint = when {
        !enabled -> colors.onGlassDim.copy(alpha = 0.35f)
        active && label == "Uninstall" -> Color(0xFFFF6B6B)
        active -> colors.accent
        else -> colors.onGlass
    }
    Row(
        modifier
            .height(48.dp)
            .graphicsLayer {
                val sc = if (active) 1.06f else 1f
                scaleX = sc
                scaleY = sc
            }
            .glass(Dp.Infinity)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = tint, fontSize = 13.sp, maxLines = 1)
    }
}

/* ------------------------------------- Sheets & dialogs ------------------------------------- */

@Composable
private fun GlassSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val colors = LocalLauncherColors.current
    BackHandler { onDismiss() }
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp)
                .glass(28.dp)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(top = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = colors.onGlass, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Close, "Close", tint = colors.onGlass, modifier = Modifier.size(28.dp).clickable { onDismiss() }.padding(2.dp))
            }
            content()
        }
    }
}

@Composable
fun WidgetPicker(state: LauncherState) {
    if (!state.widgetPicker) return
    val colors = LocalLauncherColors.current
    val ctx = state.activity
    val groups by produceState<List<Pair<String, List<AppWidgetProviderInfo>>>>(emptyList()) {
        value = withContext(Dispatchers.Default) {
            val pm = ctx.packageManager
            state.app.widgets.providers()
                .groupBy { it.provider.packageName }
                .map { (pkg, list) ->
                    val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                    label to list
                }
                .sortedBy { it.first.lowercase() }
        }
    }
    GlassSheet("Widgets", onDismiss = { state.widgetPicker = false }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            for ((label, list) in groups) {
                item(key = "h:$label") {
                    Text(label, color = colors.onGlassDim, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp, top = 14.dp, bottom = 6.dp))
                }
                items(list, key = { it.provider.flattenToString() + it.profile.hashCode() }) { info ->
                    WidgetRow(state, info)
                }
            }
        }
    }
}

@Composable
private fun WidgetRow(state: LauncherState, info: AppWidgetProviderInfo) {
    val colors = LocalLauncherColors.current
    val ctx = state.activity
    val density = ctx.resources.displayMetrics.densityDpi
    val preview by produceState<ImageBitmap?>(null, info) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val d = info.loadPreviewImage(ctx, density) ?: info.loadIcon(ctx, density)
                val w = d.intrinsicWidth.coerceIn(1, 480)
                val h = (d.intrinsicHeight.coerceAtLeast(1) * (w.toFloat() / d.intrinsicWidth.coerceAtLeast(1))).toInt().coerceIn(1, 480)
                d.toBitmap(w, h).asImageBitmap()
            }.getOrNull()
        }
    }
    val label = remember(info) { runCatching { info.loadLabel(ctx.packageManager) }.getOrDefault("Widget") }
    val cell = state.gridCoords[state.pager?.currentPage ?: 0]
    val span = remember(info, cell) {
        val c = cell?.takeIf { it.isAttached }
        if (c == null) "" else {
            val (sx, sy) = state.app.widgets.span(info, c.size.width / state.settings.homeColumns.toFloat(), c.size.height / state.settings.homeRows.toFloat(), state.settings.homeColumns, state.settings.homeRows)
            "$sx × $sy"
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .glass(20.dp)
            .clickable {
                state.widgetPicker = false
                state.activity.addWidget(info)
            }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(84.dp).drawBehind {
                preview?.let { img ->
                    val scale = minOf(size.width / img.width, size.height / img.height)
                    val w = img.width * scale
                    val h = img.height * scale
                    drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                        dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
                        dstSize = IntSize(w.roundToInt(), h.roundToInt()), filterQuality = FilterQuality.Medium)
                }
            },
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = colors.onGlass, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (span.isNotEmpty()) Text(span, color = colors.onGlassDim, fontSize = 12.sp)
        }
    }
}

@Composable
fun AppPicker(state: LauncherState, apps: List<AppInfo>) {
    val req = state.appPicker ?: return
    val colors = LocalLauncherColors.current
    var query by remember { mutableStateOf("") }
    val list = remember(apps, query) { searchApps(apps, query, state.settings.customLabels) }
    GlassSheet(req.title, onDismiss = { state.appPicker = null }) {
        Box(
            Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .height(48.dp)
                .glass(Dp.Infinity),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = colors.onGlass, fontSize = 16.sp),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search", color = colors.onGlassDim, fontSize = 16.sp)
                    inner()
                },
            )
        }
        LazyColumn(Modifier.fillMaxSize().imePadding()) {
            items(list, key = { it.key }) { app ->
                val icon = rememberAppIcon(state.app.icons, app)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            state.appPicker = null
                            req.onPick(app)
                        }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(40.dp).drawBehind {
                            icon?.let { drawImage(it, srcOffset = IntOffset.Zero, srcSize = IntSize(it.width, it.height), dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium) }
                        },
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(state.label(app), color = colors.onGlass, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
fun RenameDialog(state: LauncherState) {
    val req = state.rename ?: return
    val colors = LocalLauncherColors.current
    var text by remember(req) { mutableStateOf(req.initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(req) {
        delay(80)
        runCatching { focus.requestFocus() }
    }
    BackHandler { state.rename = null }
    fun done() {
        state.rename = null
        req.onDone(text.trim())
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .imePadding()
            .pointerInput(req) { detectTapGestures { state.rename = null } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.84f)
                .glass(26.dp)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(20.dp),
        ) {
            Text(req.title, color = colors.onGlass, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(48.dp).glass(14.dp), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = TextStyle(color = colors.onGlass, fontSize = 16.sp),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { done() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).focusRequester(focus),
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                Text("Cancel", color = colors.onGlassDim, fontSize = 15.sp, modifier = Modifier.clickable { state.rename = null }.padding(10.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save", color = colors.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { done() }.padding(10.dp))
            }
        }
    }
}
