package com.ligma67.launcher.data

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

data class IconConfig(
    val sizePx: Int = 192,
    val shape: IconShape = IconShape.SQUIRCLE,
    val style: IconStyle = IconStyle.ORIGINAL,
    val pack: String = "",
    val wrapLegacy: Boolean = true,
    val tint: Int = 0xFF5B8CFF.toInt(),
)

/**
 * Renders every icon once, off the main thread, into a shaped bitmap that is already
 * uploaded to the GPU (prepareToDraw). Scrolling the drawer or animating the launch card
 * never decodes or masks anything — it just draws textures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IconRepository(
    private val context: Context,
    private val apps: AppRepository,
    private val scope: CoroutineScope,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val density = context.resources.displayMetrics.densityDpi
    private val cache = object : LruCache<String, ImageBitmap>(96 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val colors = ConcurrentHashMap<String, Int>()
    private val worker = Dispatchers.Default.limitedParallelism(2)

    @Volatile
    var config = IconConfig()
        private set

    @Volatile
    private var pack: IconPack? = null
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun configure(cfg: IconConfig) {
        if (cfg == config) return
        val packChanged = cfg.pack != config.pack
        config = cfg
        scope.launch(worker) {
            if (packChanged) pack = if (cfg.pack.isNotEmpty()) IconPack.load(context, cfg.pack) else null
            cache.evictAll()
            colors.clear()
            _version.value = _version.value + 1
            prefetch(apps.apps.value)
        }
    }

    fun peek(key: String): ImageBitmap? = cache.get(key)

    /** Dominant (average, saturation-weighted) color of the icon; used for the launch card. */
    fun color(key: String): Int = colors[key] ?: 0xFF607080.toInt()

    suspend fun load(app: AppInfo): ImageBitmap? = cache.get(app.key) ?: withContext(worker) {
        cache.get(app.key) ?: render(app)?.also { cache.put(app.key, it) }
    }

    suspend fun loadShortcut(item: HomeItem.Shortcut): ImageBitmap? {
        val key = "sc:${item.packageName}:${item.shortcutId}:${item.userSerial}"
        cache.get(key)?.let { return it }
        return withContext(worker) {
            val info = queryShortcut(item.packageName, item.shortcutId, item.userSerial) ?: return@withContext null
            val d = runCatching { launcherApps.getShortcutIconDrawable(info, density) }.getOrNull() ?: return@withContext null
            renderDrawable(d, config)?.also { colors[key] = dominantColor(it); it.prepareToDraw() }
                ?.asImageBitmap()?.also { cache.put(key, it) }
        }
    }

    fun queryShortcut(pkg: String, id: String, serial: Long): ShortcutInfo? = runCatching {
        val user = apps.userFor(serial) ?: return null
        val q = LauncherApps.ShortcutQuery()
            .setPackage(pkg)
            .setShortcutIds(listOf(id))
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST,
            )
        launcherApps.getShortcuts(q, user)?.firstOrNull()
    }.getOrNull()

    suspend fun shortcutIcon(info: ShortcutInfo): ImageBitmap? = withContext(worker) {
        val d = runCatching { launcherApps.getShortcutIconDrawable(info, density) }.getOrNull() ?: return@withContext null
        renderDrawable(d, config.copy(style = IconStyle.ORIGINAL))?.also { it.prepareToDraw() }?.asImageBitmap()
    }

    fun prefetch(list: List<AppInfo>) {
        scope.launch(worker) {
            for (app in list) {
                if (cache.get(app.key) == null) render(app)?.let { cache.put(app.key, it) }
            }
        }
    }

    private fun render(app: AppInfo): ImageBitmap? {
        val info = apps.activityInfo(app.key) ?: return null
        val cfg = config
        val packed = pack?.drawableFor(app.component)
        val bmp = if (packed != null) {
            Bitmap.createBitmap(cfg.sizePx, cfg.sizePx, Bitmap.Config.ARGB_8888).also { b ->
                packed.setBounds(0, 0, cfg.sizePx, cfg.sizePx)
                packed.draw(Canvas(b))
            }
        } else {
            val raw = runCatching { info.getIcon(density) }.getOrNull()
                ?: context.packageManager.defaultActivityIcon
            renderDrawable(raw, cfg) ?: return null
        }
        val finalBmp = if (app.isWork) badge(bmp, app) else bmp
        colors[app.key] = dominantColor(finalBmp)
        finalBmp.prepareToDraw()
        return finalBmp.asImageBitmap()
    }

    private fun badge(bmp: Bitmap, app: AppInfo): Bitmap = runCatching {
        val d = context.packageManager.getUserBadgedIcon(BitmapDrawable(context.resources, bmp), app.user)
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, bmp.width, bmp.height)
        d.draw(Canvas(out))
        out
    }.getOrDefault(bmp)

    private fun renderDrawable(d: Drawable, cfg: IconConfig): Bitmap? = runCatching {
        val size = cfg.sizePx
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val adaptive = d as? AdaptiveIconDrawable
        val shape = shapePath(cfg.shape, size.toFloat(), adaptive)
        val mono: Drawable? = if (Build.VERSION.SDK_INT >= 33) adaptive?.monochrome else null

        when (cfg.style) {
            IconStyle.ORIGINAL -> when {
                adaptive != null -> masked(canvas, size, shape) { c ->
                    drawLayer(c, adaptive.background, size)
                    drawLayer(c, adaptive.foreground, size)
                }
                cfg.wrapLegacy -> masked(canvas, size, shape) { c ->
                    c.drawColor(Color.WHITE)
                    drawInset(c, d, size, 0.18f)
                }
                else -> { d.setBounds(0, 0, size, size); d.draw(canvas) }
            }

            IconStyle.GLASS_PLATE -> when {
                adaptive?.foreground != null -> masked(canvas, size, shape) { c -> drawLayer(c, adaptive.foreground, size) }
                else -> drawInset(canvas, d, size, 0.16f)
            }

            IconStyle.CLEAR -> when {
                mono != null -> masked(canvas, size, shape) { c ->
                    mono.mutate().colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                    drawLayer(c, mono, size)
                }
                else -> {
                    val paint = Paint().apply {
                        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.15f) })
                    }
                    val tmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    val tc = Canvas(tmp)
                    if (adaptive?.foreground != null) masked(tc, size, shape) { c -> drawLayer(c, adaptive.foreground, size) }
                    else drawInset(tc, d, size, 0.16f)
                    paint.alpha = 235
                    canvas.drawBitmap(tmp, 0f, 0f, paint)
                    tmp.recycle()
                }
            }

            IconStyle.TINTED -> masked(canvas, size, shape) { c ->
                val plate = blend(cfg.tint, Color.WHITE, 0.82f)
                val glyph = blend(cfg.tint, Color.BLACK, 0.35f)
                c.drawColor(plate)
                if (mono != null) {
                    mono.mutate().colorFilter = PorterDuffColorFilter(glyph, PorterDuff.Mode.SRC_IN)
                    drawLayer(c, mono, size)
                } else {
                    val fg = adaptive?.foreground
                    if (fg != null) {
                        fg.mutate().colorFilter = PorterDuffColorFilter(glyph, PorterDuff.Mode.SRC_IN)
                        drawLayer(c, fg, size)
                        fg.colorFilter = null
                    } else {
                        d.mutate().colorFilter = PorterDuffColorFilter(glyph, PorterDuff.Mode.SRC_IN)
                        drawInset(c, d, size, 0.2f)
                        d.colorFilter = null
                    }
                }
            }
        }
        out
    }.getOrNull()

    /** Adaptive layers are 108dp with a 72dp visible viewport: draw them 1.5x, centered. */
    private fun drawLayer(c: Canvas, layer: Drawable?, size: Int) {
        layer ?: return
        val inset = size / 4
        layer.setBounds(-inset, -inset, size + inset, size + inset)
        layer.draw(c)
    }

    private fun drawInset(c: Canvas, d: Drawable, size: Int, fraction: Float) {
        val i = (size * fraction).toInt()
        d.setBounds(i, i, size - i, size - i)
        d.draw(c)
    }

    /** Draws [content] into a temp layer and stamps it through an anti-aliased shape mask. */
    private inline fun masked(canvas: Canvas, size: Int, shape: Path, content: (Canvas) -> Unit) {
        val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        content(Canvas(layer))
        val save = canvas.saveLayer(0f, 0f, size.toFloat(), size.toFloat(), null)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawPath(shape, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(layer, 0f, 0f, paint)
        canvas.restoreToCount(save)
        layer.recycle()
    }

    private fun dominantColor(bmp: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bmp, 12, 12, true)
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var wSum = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until 12) for (x in 0 until 12) {
            val p = small.getPixel(x, y)
            if (Color.alpha(p) < 160) continue
            Color.colorToHSV(p, hsv)
            val w = 0.15 + hsv[1] * hsv[2]
            r += Color.red(p) * w
            g += Color.green(p) * w
            b += Color.blue(p) * w
            wSum += w
        }
        if (small != bmp) small.recycle()
        if (wSum == 0.0) return 0xFF607080.toInt()
        return Color.rgb((r / wSum).toInt(), (g / wSum).toInt(), (b / wSum).toInt())
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    companion object {
        /** Icon outline as an Android path of [size] x [size]. Shared with Compose via asComposePath(). */
        fun shapePath(shape: IconShape, size: Float, adaptive: AdaptiveIconDrawable? = null): Path = when (shape) {
            IconShape.CIRCLE -> Path().apply { addOval(RectF(0f, 0f, size, size), Path.Direction.CW) }
            IconShape.SQUIRCLE -> superellipse(size, 4f)
            IconShape.CONTINUOUS -> superellipse(size, 5.2f)
            IconShape.ROUNDED -> roundRect(size, size * 0.22f)
            IconShape.SQUARE -> roundRect(size, size * 0.06f)
            IconShape.TEARDROP -> Path().apply {
                val r = size / 2f
                val s = size * 0.1f
                addRoundRect(RectF(0f, 0f, size, size), floatArrayOf(r, r, r, r, s, s, r, r), Path.Direction.CW)
            }
            IconShape.SYSTEM -> adaptive?.let {
                it.setBounds(0, 0, size.toInt(), size.toInt())
                Path(it.iconMask)
            } ?: systemMask(size) ?: superellipse(size, 4f)
        }

        private fun systemMask(size: Float): Path? = runCatching {
            val d = AdaptiveIconDrawable(null, null)
            d.setBounds(0, 0, size.toInt(), size.toInt())
            Path(d.iconMask)
        }.getOrNull()

        private fun roundRect(size: Float, r: Float) =
            Path().apply { addRoundRect(RectF(0f, 0f, size, size), r, r, Path.Direction.CW) }

        private fun superellipse(size: Float, n: Float): Path {
            val path = Path()
            val r = size / 2f
            val steps = 120
            val e = 2f / n
            for (i in 0..steps) {
                val t = (2.0 * Math.PI * i / steps)
                val c = cos(t).toFloat()
                val s = sin(t).toFloat()
                val x = r + r * sign(c) * abs(c).pow(e)
                val y = r + r * sign(s) * abs(s).pow(e)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            return path
        }
    }
}
