package com.ligma67.launcher.glass

import android.graphics.BitmapShader
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.ligma67.launcher.data.IconRepository
import com.ligma67.launcher.data.IconShape
import kotlin.math.min

/** Resolved (pixel-space) glass look, derived from settings in the theme. */
@Immutable
data class GlassStyle(
    val enabled: Boolean = true,
    val tint: Int = 0x29FFFFFF,
    val refractionPx: Float = 24f,
    val edgePx: Float = 40f,
    val dispersion: Float = 0.3f,
    val highlight: Float = 0.7f,
    val saturation: Float = 1.35f,
    val borderPx: Float = 3f,
    val highQuality: Boolean = true,
    val isDark: Boolean = true,
)

@Immutable
class GlassEnv(val style: GlassStyle, val backdrop: BackdropImage?)

val LocalGlass = staticCompositionLocalOf { GlassEnv(GlassStyle(), null) }

/**
 * Draws one liquid-glass shape. Holds its own paints so nothing allocates per frame.
 *
 * Android 13+: an AGSL shader samples the pre-blurred wallpaper with SDF-based edge
 * refraction, chromatic dispersion and a two-sided specular rim (the "liquid" look).
 * Older Android: the same backdrop drawn through a BitmapShader + tint + gradient rim.
 */
class GlassPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val matrix = Matrix()
    private val rect = RectF()
    private var bitmapShader: BitmapShader? = null
    private var shaderSource: BackdropImage? = null
    private var runtime: Any? = null
    private var rimKey = 0L
    private var frostKey = 0L
    private var satValue = -1f
    private var satFilter: ColorMatrixColorFilter? = null
    private var shapePath: Path? = null
    private var shapeKey = 0L

    fun draw(
        canvas: android.graphics.Canvas,
        w: Float,
        h: Float,
        originX: Float,
        originY: Float,
        radius: Float,
        style: GlassStyle,
        backdrop: BackdropImage?,
        alpha: Float = 1f,
        iconShape: IconShape? = null,
        tintOverride: Int? = null,
    ) {
        if (w <= 0f || h <= 0f || alpha <= 0f) return
        val r = min(radius, min(w, h) / 2f)
        val tintColor = tintOverride ?: style.tint
        val path = iconShape?.let { pathFor(it, w, h) }
        val a = (alpha * 255).toInt().coerceIn(0, 255)

        if (backdrop != null && style.enabled) {
            val bs = shaderFor(backdrop)
            if (Build.VERSION.SDK_INT >= 33 && style.highQuality && path == null && style.refractionPx > 0.5f) {
                val rs = runtimeShader()
                Agsl.bind(rs, bs, w, h, originX, originY, r, backdrop.scale, style, tintColor)
                fill.shader = rs
                fill.colorFilter = null
                fill.alpha = a
                canvas.drawRoundRect(0f, 0f, w, h, r, r, fill)
            } else {
                matrix.setScale(1f / backdrop.scale, 1f / backdrop.scale)
                matrix.postTranslate(-originX, -originY)
                bs.setLocalMatrix(matrix)
                fill.shader = bs
                fill.colorFilter = saturation(style.saturation)
                fill.alpha = a
                drawShape(canvas, w, h, r, path, fill)
                tint.shader = null
                tint.color = tintColor
                tint.alpha = (android.graphics.Color.alpha(tintColor) * alpha).toInt()
                drawShape(canvas, w, h, r, path, tint)
            }
        } else {
            // No wallpaper pixels available: frosted gradient tint.
            val key = (w.toLong() shl 40) xor (h.toLong() shl 20) xor tintColor.toLong()
            if (key != frostKey || tint.shader == null) {
                frostKey = key
                val base = android.graphics.Color.alpha(tintColor)
                val top = (base * 1.5f + 40).toInt().coerceAtMost(255)
                val bottom = (base + 24).coerceAtMost(255)
                val rgb = tintColor and 0x00FFFFFF
                tint.shader = LinearGradient(0f, 0f, w * 0.6f, h, (top shl 24) or rgb, (bottom shl 24) or rgb, Shader.TileMode.CLAMP)
            }
            tint.alpha = a
            drawShape(canvas, w, h, r, path, tint)
        }

        // Specular rim — bright top-left edge, softer bottom-right bounce.
        if (style.borderPx > 0f && style.highlight > 0f) {
            val key = (w.toLong() shl 32) xor h.toLong() xor (style.highlight * 1000).toLong()
            if (key != rimKey) {
                rimKey = key
                val hi = (style.highlight * 200).toInt().coerceIn(0, 255)
                val lo = (style.highlight * 90).toInt().coerceIn(0, 255)
                rim.shader = LinearGradient(
                    0f, 0f, w, h,
                    intArrayOf(hi shl 24 or 0xFFFFFF, 0x14FFFFFF, 0x05FFFFFF, lo shl 24 or 0xFFFFFF),
                    floatArrayOf(0f, 0.38f, 0.62f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            rim.strokeWidth = style.borderPx
            rim.alpha = a
            val inset = style.borderPx / 2f
            if (path != null) {
                canvas.drawPath(path, rim)
            } else {
                rect.set(inset, inset, w - inset, h - inset)
                canvas.drawRoundRect(rect, (r - inset).coerceAtLeast(0f), (r - inset).coerceAtLeast(0f), rim)
            }
        }
    }

    private fun drawShape(canvas: android.graphics.Canvas, w: Float, h: Float, r: Float, path: Path?, paint: Paint) {
        if (path != null) canvas.drawPath(path, paint) else canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)
    }

    private fun pathFor(shape: IconShape, w: Float, h: Float): Path {
        val key = (shape.ordinal.toLong() shl 48) xor (w.toLong() shl 24) xor h.toLong()
        if (key != shapeKey || shapePath == null) {
            shapeKey = key
            shapePath = IconRepository.shapePath(shape, min(w, h)).also {
                if (w != h) it.offset((w - min(w, h)) / 2f, (h - min(w, h)) / 2f)
            }
        }
        return shapePath!!
    }

    private fun shaderFor(b: BackdropImage): BitmapShader {
        if (shaderSource !== b || bitmapShader == null) {
            shaderSource = b
            bitmapShader = BitmapShader(b.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        return bitmapShader!!
    }

    private fun saturation(s: Float): ColorMatrixColorFilter? {
        if (s == 1f) return null
        if (s != satValue) {
            satValue = s
            satFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(s) })
        }
        return satFilter
    }

    @RequiresApi(33)
    private fun runtimeShader(): RuntimeShader {
        (runtime as? RuntimeShader)?.let { return it }
        return RuntimeShader(Agsl.SOURCE).also { runtime = it }
    }
}

@RequiresApi(33)
private object Agsl {
    const val SOURCE = """
uniform shader backdrop;
uniform float2 uSize;
uniform float2 uOrigin;
uniform float uRadius;
uniform float uScale;
uniform float uRefract;
uniform float uEdge;
uniform float uDispersion;
uniform float uSaturation;
uniform float4 uTint;
uniform float uHighlight;

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r, r);
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0, 0.0))) - r;
}

float3 sampleAt(float2 screenPos) {
    return float3(backdrop.eval(screenPos * uScale).rgb);
}

half4 main(float2 coord) {
    float2 halfSize = uSize * 0.5;
    float2 p = coord - halfSize;
    float r = min(uRadius, min(halfSize.x, halfSize.y));
    float d = sdRoundRect(p, halfSize, r);
    float aa = clamp(0.5 - d, 0.0, 1.0);
    if (aa <= 0.0) {
        return half4(0.0);
    }
    float dx = sdRoundRect(p + float2(1.0, 0.0), halfSize, r) - sdRoundRect(p - float2(1.0, 0.0), halfSize, r);
    float dy = sdRoundRect(p + float2(0.0, 1.0), halfSize, r) - sdRoundRect(p - float2(0.0, 1.0), halfSize, r);
    float2 n = float2(dx, dy);
    float nl = length(n);
    n = nl > 0.0001 ? n / nl : float2(0.0, 0.0);

    float depth = clamp(-d / max(uEdge, 1.0), 0.0, 1.0);
    float bend = uRefract * pow(1.0 - depth, 2.5);
    float2 screen = uOrigin + coord;
    float2 off = -n * bend;

    float3 col = float3(0.0);
    if (uDispersion > 0.001 && bend > 0.05) {
        float k = uDispersion * 0.35;
        col.r = sampleAt(screen + off * (1.0 + k)).r;
        col.g = sampleAt(screen + off).g;
        col.b = sampleAt(screen + off * (1.0 - k)).b;
    } else {
        col = sampleAt(screen + off);
    }

    float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
    col = mix(float3(lum), col, uSaturation);
    col = mix(col, uTint.rgb, uTint.a);

    float rim = 1.0 - smoothstep(0.0, 3.0, -d);
    float2 L = normalize(float2(-0.55, -0.83));
    float spec = pow(max(dot(n, L), 0.0), 2.0) + 0.45 * pow(max(dot(n, -L), 0.0), 3.0);
    float sheen = (1.0 - depth) * 0.06;
    col += (rim * spec + sheen) * uHighlight;
    col = clamp(col, 0.0, 1.0);
    return half4(half3(col * aa), half(aa));
}
"""

    fun bind(
        rs: RuntimeShader,
        backdrop: BitmapShader,
        w: Float,
        h: Float,
        ox: Float,
        oy: Float,
        r: Float,
        scale: Float,
        style: GlassStyle,
        tint: Int,
    ) {
        backdrop.setLocalMatrix(null)
        rs.setInputShader("backdrop", backdrop)
        rs.setFloatUniform("uSize", w, h)
        rs.setFloatUniform("uOrigin", ox, oy)
        rs.setFloatUniform("uRadius", r)
        rs.setFloatUniform("uScale", scale)
        rs.setFloatUniform("uRefract", style.refractionPx)
        rs.setFloatUniform("uEdge", style.edgePx)
        rs.setFloatUniform("uDispersion", style.dispersion)
        rs.setFloatUniform("uSaturation", style.saturation)
        rs.setFloatUniform(
            "uTint",
            android.graphics.Color.red(tint) / 255f,
            android.graphics.Color.green(tint) / 255f,
            android.graphics.Color.blue(tint) / 255f,
            android.graphics.Color.alpha(tint) / 255f,
        )
        rs.setFloatUniform("uHighlight", style.highlight * 0.55f)
    }
}

/* ------------------------------- Modifier plumbing ------------------------------- */

private data class GlassElement(
    val radiusPx: Float,
    val style: GlassStyle,
    val backdrop: BackdropImage?,
    val alpha: Float,
    val iconShape: IconShape?,
    val tintOverride: Int?,
    val motion: (() -> Float)?,
) : ModifierNodeElement<GlassNode>() {
    override fun create() = GlassNode(this)
    override fun update(node: GlassNode) {
        node.element = this
        node.invalidateDraw()
    }
}

private class GlassNode(var element: GlassElement) : Modifier.Node(), DrawModifierNode, LayoutAwareModifierNode {
    private val painter = GlassPainter()
    private var coords: LayoutCoordinates? = null

    override fun onPlaced(coordinates: LayoutCoordinates) {
        coords = coordinates
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val e = element
        e.motion?.invoke() // read animation state so we re-sample while moving
        val c = coords
        val origin = if (c != null && c.isAttached) c.positionInWindow() else Offset.Zero
        drawIntoCanvas {
            painter.draw(
                it.nativeCanvas, size.width, size.height, origin.x, origin.y, e.radiusPx,
                e.style, e.backdrop, e.alpha, e.iconShape, e.tintOverride,
            )
        }
        drawContent()
    }
}

/**
 * Liquid-glass background. [corner] = Dp.Infinity gives a pill. [motion] is an optional
 * lambda reading animation state; pass it for glass that moves over the wallpaper so the
 * refraction stays locked to the background while it animates.
 */
@Composable
fun Modifier.glass(
    corner: Dp,
    alpha: Float = 1f,
    iconShape: IconShape? = null,
    tint: Int? = null,
    lite: Boolean = false,
    motion: (() -> Float)? = null,
): Modifier {
    val env = LocalGlass.current
    val radius = with(LocalDensity.current) { if (corner == Dp.Infinity) Float.MAX_VALUE else corner.toPx() }
    val style = if (lite) env.style.copy(refractionPx = 0f) else env.style
    return this.then(GlassElement(radius, style, env.backdrop, alpha, iconShape, tint, motion))
}
