package com.ligma67.launcher.anim

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.ligma67.launcher.data.CardFill
import com.ligma67.launcher.data.CloseStyle
import com.ligma67.launcher.data.Curve
import com.ligma67.launcher.data.HomeMotion
import com.ligma67.launcher.data.LauncherSettings
import com.ligma67.launcher.glass.GlassEnv
import com.ligma67.launcher.glass.GlassPainter
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What was tapped, so the close animation can fly back into it. */
data class LaunchRecord(
    val key: String,
    val packageName: String,
    val slot: String,
    val bounds: Rect,
    val icon: ImageBitmap?,
    val color: Int,
    val time: Long,
)

/**
 * Remembers where every visible icon is. Coordinates are captured once per node (no
 * per-frame callbacks) and resolved to bounds only when an animation needs them.
 *
 * Slot ids: "i:<itemId>" home/dock, "f:<folderId>:<appKey>" folder, "d:<appKey>" drawer.
 */
class IconRegistry {
    private val slots = HashMap<String, LayoutCoordinates>()
    private val byKey = HashMap<String, LinkedHashSet<String>>()
    private val slotKey = HashMap<String, String>()

    /** Coordinates *inside* the home layer (unaffected by its scale animation). */
    var homeCoords: LayoutCoordinates? = null

    fun register(slot: String, key: String?, coords: LayoutCoordinates) {
        slots[slot] = coords
        if (key != null) {
            byKey.getOrPut(key) { LinkedHashSet() }.add(slot)
            slotKey[slot] = key
        }
    }

    fun unregister(slot: String, key: String?) {
        slots.remove(slot)
        slotKey.remove(slot)
        if (key != null) byKey[key]?.remove(slot)
    }

    fun keyOf(slot: String): String? = slotKey[slot]

    fun coords(slot: String): LayoutCoordinates? = slots[slot]?.takeIf { it.isAttached }

    /** Final (rest-state) bounds of a slot in window coordinates. */
    fun bounds(slot: String): Rect? {
        val c = coords(slot) ?: return null
        val home = homeCoords
        return if (slot.startsWith("i:") && home != null && home.isAttached) {
            runCatching { home.localBoundingBoxOf(c, clipBounds = false) }.getOrNull()
        } else c.boundsInWindow()
    }

    /** Best visible slot showing [key] (an app key or "pkg:<package>"), honoring what's on top. */
    fun find(key: String, screen: Rect, drawerOpen: Boolean, folderOpen: Boolean, preferred: String?): String? {
        fun visible(slot: String) = bounds(slot)?.let { it.width > 0 && it.overlaps(screen) } == true
        if (preferred != null && visible(preferred)) {
            val onTop = when {
                preferred.startsWith("f:") -> folderOpen
                preferred.startsWith("d:") -> drawerOpen && !folderOpen
                else -> !drawerOpen && !folderOpen
            }
            if (onTop) return preferred
        }
        val candidates = if (key.startsWith("pkg:")) {
            val pkg = key.removePrefix("pkg:")
            byKey.filterKeys { it.substringBefore('/') == pkg }.values.flatten()
        } else byKey[key]?.toList().orEmpty()
        // Only icons on the top-most visible layer can be landed on.
        val prefix = when {
            folderOpen -> "f:"
            drawerOpen -> "d:"
            else -> "i:"
        }
        return candidates.firstOrNull { it.startsWith(prefix) && visible(it) }
    }
}

/**
 * The launcher-side half of every open/close animation.
 *
 * Everything here is a [Motion]: values read only in graphicsLayer/draw, retargetable at
 * any time from the current value+velocity. Tapping an icon while the close animation is
 * still running simply reverses it — that's what makes spamming feel buttery.
 */
@Stable
class LaunchAnimator(private val scope: CoroutineScope) {
    /** 0 = card sits on the icon, 1 = card fills the screen. */
    val card = Motion(0f)
    val cardAlpha = Motion(0f)
    val homeScale = Motion(1f)
    val homeAlpha = Motion(1f)
    /** 0..1, multiplied by the max blur radius at draw time. */
    val homeBlur = Motion(0f)
    val wave = Motion(1f)
    val bounce = Motion(1f)

    var liftedSlot by mutableStateOf<String?>(null)
    var bounceSlot by mutableStateOf<String?>(null)
    var waveOrigin by mutableStateOf(Offset.Zero)
    var waveStagger = 0.45f
    var waveFrom = 0.85f
    var waveBouncy = false

    var cardRect by mutableStateOf(Rect.Zero)
    var cardIcon by mutableStateOf<ImageBitmap?>(null)
    var cardColor by mutableIntStateOf(0xFF607080.toInt())
    var cardIconCorner by mutableFloatStateOf(0f)
    var cardScreenCorner by mutableFloatStateOf(0f)
    var cardFill by mutableStateOf(CardFill.GLASS)
    var screen = Rect.Zero

    var lastLaunch: LaunchRecord? = null
    var motionContext: CoroutineContext = EmptyCoroutineContext

    private fun openSpec(s: LauncherSettings) =
        Specs.of<Float>(s.openCurve, s.openDurationMs, s.openSpringDamping, s.openSpringStiffness)

    private fun closeSpec(s: LauncherSettings) =
        Specs.of<Float>(s.closeCurve, s.closeDurationMs, s.closeSpringDamping, s.closeSpringStiffness)

    private fun closeMs(s: LauncherSettings) =
        if (s.closeCurve == Curve.SPRING) Specs.springDurationMs(s.closeSpringDamping, s.closeSpringStiffness) else s.closeDurationMs

    fun onLaunch(rec: LaunchRecord, s: LauncherSettings, iconCorner: Float, screenCorner: Float) {
        lastLaunch = rec
        val ctx = motionContext
        val spec = openSpec(s)
        bounceSlot = null
        wave.snap(1f)

        if (s.openStyle.usesLauncherCard && !s.reduceMotion) {
            val wasVisible = cardAlpha.value > 0.01f
            cardRect = rec.bounds
            cardIcon = rec.icon
            cardColor = rec.color
            cardFill = s.cardFill
            cardIconCorner = iconCorner
            cardScreenCorner = screenCorner
            liftedSlot = rec.slot
            if (!wasVisible) card.snap(0f)
            cardAlpha.snap(1f)
            card.animateTo(scope, 1f, spec, ctx)
        } else {
            cardAlpha.snap(0f)
            card.snap(0f)
            liftedSlot = null
        }

        if (s.reduceMotion) return
        when (s.homeOnOpen) {
            HomeMotion.NONE -> Unit
            HomeMotion.SCALE_DOWN -> homeScale.animateTo(scope, s.homeOpenScale, spec, ctx)
            HomeMotion.SCALE_UP -> homeScale.animateTo(scope, 2f - s.homeOpenScale, spec, ctx)
            HomeMotion.FADE -> homeAlpha.animateTo(scope, 0f, spec, ctx)
            HomeMotion.BLUR -> {
                homeScale.animateTo(scope, s.homeOpenScale, spec, ctx)
                homeBlur.animateTo(scope, 1f, spec, ctx)
            }
        }
    }

    /**
     * Home became visible again. [target] is where the card should land (null = no icon
     * found on screen → generic zoom-in).
     */
    fun onReturn(
        target: Rect?,
        targetSlot: String?,
        s: LauncherSettings,
        iconCorner: Float,
        screenCorner: Float,
        icon: ImageBitmap?,
        color: Int?,
    ) {
        val rec = lastLaunch
        lastLaunch = null
        val ctx = motionContext
        val spec = closeSpec(s)
        val ms = closeMs(s)
        var style = s.closeStyle
        if (s.reduceMotion) style = CloseStyle.FADE_HOME
        if (style == CloseStyle.SHRINK_TO_ICON && (target == null || (rec == null && icon == null))) style = CloseStyle.ZOOM_HOME

        if (style == CloseStyle.SHRINK_TO_ICON && target != null) {
            cardRect = target
            cardIconCorner = iconCorner
            cardScreenCorner = screenCorner
            cardIcon = icon ?: rec?.icon
            cardColor = color ?: rec?.color ?: cardColor
            cardFill = s.cardFill
            liftedSlot = targetSlot
            if (cardAlpha.value < 0.01f) card.snap(1f)
            cardAlpha.snap(1f)
            var landed = false
            card.animateTo(
                scope, 0f, spec, ctx,
                onFrame = { v -> if (!landed && v <= 0.012f) { landed = true; land(targetSlot, s) } },
                onEnd = { if (!landed) { landed = true; land(targetSlot, s) } },
            )
        } else {
            if (cardAlpha.value > 0.001f) {
                cardAlpha.animateTo(scope, 0f, tween(140), ctx, onEnd = { liftedSlot = null })
            } else {
                liftedSlot = null
            }
        }

        when (style) {
            CloseStyle.NONE -> {
                homeScale.snap(1f); homeAlpha.snap(1f); homeBlur.snap(0f); wave.snap(1f)
                return
            }
            CloseStyle.FADE_HOME -> {
                if (!homeAlpha.isRunning && homeAlpha.value > 0.99f) homeAlpha.snap(0f)
                homeAlpha.animateTo(scope, 1f, tween(ms), ctx)
                homeScale.animateTo(scope, 1f, spec, ctx)
            }
            CloseStyle.WAVE -> {
                homeScale.animateTo(scope, 1f, spec, ctx)
                homeAlpha.animateTo(scope, 1f, tween(ms / 2), ctx)
                waveOrigin = target?.center ?: screen.center
                waveStagger = s.waveStagger
                waveFrom = s.homeCloseScale
                waveBouncy = s.closeCurve == Curve.SPRING && s.closeSpringDamping < 0.75f || s.closeCurve == Curve.OVERSHOOT
                wave.snap(0f)
                wave.animateTo(scope, 1f, tween((ms * (1f + s.waveStagger)).roundToInt(), easing = LinearEasing), ctx)
            }
            CloseStyle.SHRINK_TO_ICON, CloseStyle.ZOOM_HOME -> {
                if (!homeScale.isRunning && homeScale.value == 1f) homeScale.snap(s.homeCloseScale)
                homeScale.animateTo(scope, 1f, spec, ctx)
                homeAlpha.animateTo(scope, 1f, tween(ms / 2), ctx)
            }
        }

        if (s.closeBlurHome && !s.reduceMotion) {
            if (!homeBlur.isRunning && homeBlur.value < 0.01f) homeBlur.snap(1f)
            homeBlur.animateTo(scope, 0f, tween((ms * 0.8f).roundToInt()), ctx)
        } else {
            homeBlur.animateTo(scope, 0f, tween(120), ctx)
        }
    }

    private fun land(slot: String?, s: LauncherSettings) {
        liftedSlot = null
        cardAlpha.animateTo(scope, 0f, tween(90), motionContext)
        if (s.iconBounceOnLand && slot != null && !s.reduceMotion) {
            bounceSlot = slot
            bounce.snap(1f)
            // A downward "thump": start at rest with negative velocity, spring back to 1.
            bounce.animateTo(
                scope, 1f,
                spring(dampingRatio = 0.42f, stiffness = 520f),
                motionContext,
                initialVelocity = -2.2f * s.bounceStrength,
            )
        }
    }

    fun reset() {
        card.snap(0f); cardAlpha.snap(0f); homeScale.snap(1f); homeAlpha.snap(1f)
        homeBlur.snap(0f); wave.snap(1f); bounce.snap(1f)
        liftedSlot = null; bounceSlot = null; lastLaunch = null
    }

    /* ----------------------------- per-icon helpers ----------------------------- */

    /** Bounce scale for an icon. Call only inside graphicsLayer. */
    fun bounceScale(slot: String): Float = if (bounceSlot == slot) bounce.value else 1f

    /** 0..1 local wave progress of the icon at [center] (1 = settled). */
    fun waveProgress(slot: String, center: () -> Offset): Float {
        val w = wave.value
        if (w >= 1f || !slot.startsWith("i:")) return 1f
        return waveLocal(w, center())
    }

    fun waveScale(p: Float): Float = if (p >= 1f) 1f else lerpF(waveFrom, 1f, p)
    fun waveAlpha(p: Float): Float = if (p >= 1f) 1f else (p * 2.5f).coerceIn(0f, 1f)

    fun waveLocal(w: Float, c: Offset): Float {
        val diag = max(1f, hypot(screen.width, screen.height))
        val d = ((c - waveOrigin).getDistance() / diag).coerceIn(0f, 1f)
        val k = waveStagger
        val t = ((w * (1f + k)) - d * k).coerceIn(0f, 1f)
        return if (waveBouncy) Specs.Overshoot.transform(t) else Specs.Emphasized.transform(t)
    }
}

fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t
fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Draws the expanding / shrinking launch card. Reads Motion values → redraw only. */
fun DrawScope.drawLaunchCard(a: LaunchAnimator, painter: GlassPainter, env: GlassEnv, surface: Int) {
    val alpha = a.cardAlpha.value
    if (alpha <= 0.002f) return
    val p = a.card.value
    val from = a.cardRect
    if (from.width <= 0f) return
    val to = Rect(0f, 0f, size.width, size.height)
    val rect = lerp(from, to, p)
    val pc = p.coerceIn(0f, 1f)
    val radius = lerpF(a.cardIconCorner, a.cardScreenCorner, pc)
    val fillAlpha = smoothstep(0f, 0.28f, p) * alpha

    if (fillAlpha > 0.003f) {
        val fill = if (a.cardFill == CardFill.GLASS && env.backdrop == null) CardFill.THEME else a.cardFill
        when (fill) {
            CardFill.GLASS -> drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                val save = nc.save()
                nc.translate(rect.left, rect.top)
                val tint = if (env.style.isDark) 0x52101418 else 0x66FFFFFF
                painter.draw(nc, rect.width, rect.height, rect.left, rect.top, radius, env.style, env.backdrop, fillAlpha, tintOverride = tint)
                nc.restoreToCount(save)
            }
            CardFill.APP_COLOR -> drawRoundRect(Color(a.cardColor), rect.topLeft, rect.size, CornerRadius(radius), alpha = fillAlpha)
            CardFill.THEME -> drawRoundRect(Color(surface), rect.topLeft, rect.size, CornerRadius(radius), alpha = fillAlpha)
        }
    }

    val icon = a.cardIcon ?: return
    val iconAlpha = (1f - smoothstep(0.3f, 0.78f, p)) * alpha
    if (iconAlpha <= 0.003f) return
    val base = from.width
    val sz = if (p < 0f) rect.width else lerpF(base, max(base, min(to.width, to.height) * 0.24f), pc)
    if (sz < 1f) return
    val c = rect.center
    drawImage(
        image = icon,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(icon.width, icon.height),
        dstOffset = IntOffset((c.x - sz / 2f).roundToInt(), (c.y - sz / 2f).roundToInt()),
        dstSize = IntSize(sz.roundToInt(), sz.roundToInt()),
        alpha = iconAlpha,
        filterQuality = FilterQuality.Medium,
    )
}
