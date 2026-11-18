package com.ligma67.launcher.ui.settings

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ligma67.launcher.anim.FixedMotionScale
import com.ligma67.launcher.anim.LaunchAnimator
import com.ligma67.launcher.anim.LaunchRecord
import com.ligma67.launcher.anim.Motion
import com.ligma67.launcher.anim.Specs
import com.ligma67.launcher.anim.WindowAnimations
import com.ligma67.launcher.anim.drawLaunchCard
import com.ligma67.launcher.anim.lerpF
import com.ligma67.launcher.data.Curve
import com.ligma67.launcher.data.LauncherSettings
import com.ligma67.launcher.data.OpenStyle
import com.ligma67.launcher.glass.GlassPainter
import com.ligma67.launcher.glass.LocalGlass
import com.ligma67.launcher.launcherApp
import com.ligma67.launcher.ui.home.rememberAppIcon
import com.ligma67.launcher.ui.theme.LocalLauncherColors
import kotlinx.coroutines.delay
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.roundToInt

private const val TARGET = 5

private class PreviewGeo(w: Float, val h: Float, d: Density) {
    private val pad = with(d) { 14.dp.toPx() }
    private val top = with(d) { 46.dp.toPx() }
    private val cell = (w - 2 * pad) / 4f
    private val cellH = cell * 1.2f
    val icon = cell * 0.64f
    val dock = Rect(pad * 0.7f, h - pad * 0.7f - cell * 1.05f, w - pad * 0.7f, h - pad * 0.7f)

    fun iconRect(i: Int): Rect {
        return if (i < 16) {
            val cx = pad + cell * (i % 4 + 0.5f)
            val cy = top + cellH * (i / 4 + 0.5f)
            Rect(cx - icon / 2, cy - icon / 2, cx + icon / 2, cy + icon / 2)
        } else {
            val cw = dock.width / 4f
            val cx = dock.left + cw * ((i - 16) + 0.5f)
            val cy = dock.center.y
            Rect(cx - icon / 2, cy - icon / 2, cx + icon / 2, cy + icon / 2)
        }
    }
}

/** A tiny phone that plays your exact open/close settings with real icons. */
@Composable
fun AnimationPreview(s: LauncherSettings) {
    val ctx = LocalContext.current
    val app = ctx.launcherApp
    val scope = rememberCoroutineScope()
    val animator = remember { LaunchAnimator(scope) }
    val appWin = remember { Motion(0f) }
    val env = LocalGlass.current
    val colors = LocalLauncherColors.current
    val painter = remember { GlassPainter() }
    val density = LocalDensity.current
    val apps by app.apps.apps.collectAsState()
    val sample = remember(apps) { apps.filter { !it.isWork }.take(20) }
    val icons = sample.map { rememberAppIcon(app.icons, it) }
    var geo by remember { mutableStateOf<PreviewGeo?>(null) }
    var open by remember { mutableStateOf(false) }
    var loop by remember { mutableStateOf(false) }
    var spam by remember { mutableStateOf(false) }
    val maxBlur = with(density) { 10.dp.toPx() }
    animator.motionContext = if (s.speedMultiplier != 1f) FixedMotionScale(s.speedMultiplier) else EmptyCoroutineContext

    val openMs = WindowAnimations.effectiveOpenMs(s).coerceAtLeast(60)
    val closeMs = (if (s.closeCurve == Curve.SPRING) Specs.springDurationMs(s.closeSpringDamping, s.closeSpringStiffness) else s.closeDurationMs)

    fun doOpen() {
        val g = geo ?: return
        val r = g.iconRect(TARGET)
        val icon = icons.getOrNull(TARGET)
        val color = sample.getOrNull(TARGET)?.let { app.icons.color(it.key) } ?: 0xFF5B8CFF.toInt()
        val radius = r.width * s.iconShape.cornerFraction
        animator.onLaunch(LaunchRecord("preview", "preview", "i:$TARGET", r, icon, color, SystemClock.uptimeMillis()), s, radius, with(density) { 30.dp.toPx() })
        val ctxScale = animator.motionContext
        when (s.openStyle) {
            OpenStyle.EXPAND -> appWin.animateTo(scope, 1f, tween(openMs * 45 / 100, delayMillis = openMs * 55 / 100), ctxScale)
            OpenStyle.NONE -> appWin.snap(1f)
            else -> appWin.animateTo(scope, 1f, tween(openMs, easing = Specs.easing(s.openCurve)), ctxScale)
        }
        open = true
    }

    fun doClose() {
        val g = geo ?: return
        appWin.snap(0f)
        val r = g.iconRect(TARGET)
        val icon = icons.getOrNull(TARGET)
        val color = sample.getOrNull(TARGET)?.let { app.icons.color(it.key) }
        if (animator.lastLaunch == null) {
            animator.lastLaunch = LaunchRecord("preview", "preview", "i:$TARGET", r, icon, color ?: 0, SystemClock.uptimeMillis())
        }
        animator.onReturn(r, "i:$TARGET", s, r.width * s.iconShape.cornerFraction, with(density) { 30.dp.toPx() }, icon, color)
        open = false
    }

    LaunchedEffect(loop, spam, s) {
        if (!loop && !spam) return@LaunchedEffect
        while (true) {
            doOpen()
            delay(if (spam) 160L else (openMs * s.speedMultiplier).toLong() + 700L)
            doClose()
            delay(if (spam) 140L else (closeMs * s.speedMultiplier).toLong() + 800L)
        }
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(210.dp, 430.dp)
                .clip(RoundedCornerShape(32.dp))
                .border(2.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(32.dp))
                .onSizeChanged {
                    geo = PreviewGeo(it.width.toFloat(), it.height.toFloat(), density)
                    animator.screen = Rect(0f, 0f, it.width.toFloat(), it.height.toFloat())
                },
        ) {
            // wallpaper
            val bd = env.backdrop
            Box(
                Modifier.fillMaxSize().background(Color(0xFF1C2333)).drawBehind {
                    if (bd != null) {
                        val img = bd.bitmap.asImageBitmap()
                        drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height), dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low)
                    } else {
                        drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(colors.accent.copy(alpha = 0.7f), Color(0xFF101420))))
                    }
                },
            )
            // home
            Canvas(
                Modifier.fillMaxSize().graphicsLayer {
                    val sc = animator.homeScale.value
                    scaleX = sc
                    scaleY = sc
                    alpha = animator.homeAlpha.value.coerceIn(0f, 1f)
                    val b = animator.homeBlur.value * maxBlur
                    renderEffect = if (Build.VERSION.SDK_INT >= 31 && b > 0.5f) BlurEffect(b, b, TileMode.Decal) else null
                },
            ) {
                val g = geo ?: return@Canvas
                drawRoundRect(Color.White.copy(alpha = 0.18f), g.dock.topLeft, g.dock.size, CornerRadius(g.dock.height / 2.6f))
                for (i in 0 until 20) {
                    val slot = "i:$i"
                    val r = g.iconRect(i)
                    val wp = animator.waveProgress(slot) { r.center }
                    val sc = animator.waveScale(wp) * animator.bounceScale(slot)
                    val a = if (animator.liftedSlot == slot) 0f else animator.waveAlpha(wp)
                    if (a <= 0f) continue
                    val w = r.width * sc
                    val img = icons.getOrNull(i)
                    if (img != null) {
                        drawImage(
                            img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                            dstOffset = IntOffset((r.center.x - w / 2).roundToInt(), (r.center.y - w / 2).roundToInt()),
                            dstSize = IntSize(w.roundToInt(), w.roundToInt()), alpha = a, filterQuality = FilterQuality.Medium,
                        )
                    } else {
                        drawCircle(Color.White.copy(alpha = 0.6f * a), w / 2, r.center)
                    }
                }
            }
            // fake app window
            Canvas(Modifier.fillMaxSize()) {
                val t = appWin.value
                val g = geo ?: return@Canvas
                if (t <= 0.001f) return@Canvas
                drawFakeApp(s.openStyle, t, g.iconRect(TARGET), colors.accent)
            }
            // launch card
            Spacer(Modifier.fillMaxSize().drawBehind { drawLaunchCard(animator, painter, env, colors.surface.toArgb()) })
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PreviewButton(if (open) "Close app" else "Open app") { if (open) doClose() else doOpen() }
            PreviewButton(if (loop) "Stop loop" else "Loop") { loop = !loop; spam = false }
            PreviewButton(if (spam) "Stop spam" else "Spam test") { spam = !spam; loop = false }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (s.openStyle.isSystem) "System styles are drawn by Android — preview is approximate." else "Preview uses your exact settings.",
            color = colors.onGlassDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun PreviewButton(text: String, onClick: () -> Unit) {
    val c = LocalLauncherColors.current
    Text(
        text,
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(CircleShape)
            .background(c.accent)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

private fun DrawScope.drawFakeApp(style: OpenStyle, t: Float, icon: Rect, accent: Color) {
    val full = Rect(0f, 0f, size.width, size.height)
    var rect = full
    var alpha = 1f
    var scale = 1f
    var tx = 0f
    var ty = 0f
    when (style) {
        OpenStyle.EXPAND -> { alpha = t; scale = lerpF(0.97f, 1f, t) }
        OpenStyle.FADE, OpenStyle.SYSTEM_DEFAULT, OpenStyle.NONE -> alpha = t
        OpenStyle.ZOOM -> { scale = lerpF(0.86f, 1f, t); alpha = (t / 0.6f).coerceAtMost(1f) }
        OpenStyle.SLIDE_UP -> { ty = (1f - t) * size.height * 0.09f; alpha = (t / 0.7f).coerceAtMost(1f) }
        OpenStyle.SLIDE_SIDE -> tx = (1f - t) * size.width
        OpenStyle.POP -> { scale = lerpF(0.5f, 1f, t); alpha = (t / 0.4f).coerceAtMost(1f) }
        OpenStyle.RISE -> ty = (1f - t) * size.height
        OpenStyle.SCALE_UP, OpenStyle.CLIP_REVEAL, OpenStyle.ICON_MORPH -> rect = lerp(icon, full, t)
    }
    withTransform({
        translate(tx, ty)
        scale(scale, scale, full.center)
    }) {
        val bg = Color(0xFFF3F5F9)
        drawRoundRect(bg, rect.topLeft, rect.size, CornerRadius(26f), alpha = alpha.coerceIn(0f, 1f))
        val pad = rect.width * 0.08f
        drawRoundRect(accent, Offset(rect.left, rect.top), Size(rect.width, rect.height * 0.16f), CornerRadius(26f), alpha = alpha.coerceIn(0f, 1f))
        for (i in 0 until 6) {
            val y = rect.top + rect.height * (0.22f + i * 0.1f)
            drawRoundRect(
                Color(0xFFD9DEE8), Offset(rect.left + pad, y),
                Size(rect.width * (if (i % 2 == 0) 0.8f else 0.6f) - pad, rect.height * 0.05f),
                CornerRadius(10f), alpha = alpha.coerceIn(0f, 1f),
            )
        }
    }
}
