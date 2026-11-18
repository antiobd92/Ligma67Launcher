package com.ligma67.launcher.glass

import android.annotation.SuppressLint
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.ligma67.launcher.data.SettingsRepository
import com.ligma67.launcher.data.WallpaperMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** A pre-blurred copy of the wallpaper in screen space, scaled by [scale]. */
@Immutable
class BackdropImage(
    val bitmap: Bitmap,
    /** backdrop pixels per screen pixel */
    val scale: Float,
    val screenWidth: Int,
    val screenHeight: Int,
)

@Immutable
data class WallpaperTone(val isDark: Boolean = true, val primary: Int = 0xFF334455.toInt(), val secondary: Int = 0xFF223344.toInt())

/**
 * Owns everything the Liquid Glass needs to know about what's behind it.
 *
 * The blur is computed ONCE per wallpaper change on a background thread (quarter-res box
 * blur, ~5ms). Glass surfaces then just sample that texture with a shader — no per-frame
 * blur passes, which is why the glass stays smooth on low-end phones.
 */
class Backdrop(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val wm = WallpaperManager.getInstance(context)
    private val customFile = File(context.filesDir, "wallpaper.img")

    private val _blurred = MutableStateFlow<BackdropImage?>(null)
    val blurred: StateFlow<BackdropImage?> = _blurred.asStateFlow()
    private val _sharp = MutableStateFlow<ImageBitmap?>(null)
    val sharp: StateFlow<ImageBitmap?> = _sharp.asStateFlow()
    private val _tone = MutableStateFlow(WallpaperTone())
    val tone: StateFlow<WallpaperTone> = _tone.asStateFlow()
    private val _canReadSystem = MutableStateFlow(false)
    val canReadSystem: StateFlow<Boolean> = _canReadSystem.asStateFlow()

    private var job: Job? = null

    init {
        if (Build.VERSION.SDK_INT >= 27) {
            runCatching {
                wm.addOnColorsChangedListener({ _, which ->
                    if (which and WallpaperManager.FLAG_SYSTEM != 0) refresh()
                }, Handler(Looper.getMainLooper()))
            }
        }
        scope.launch {
            settings.state
                .map { Triple(it.wallpaperMode, it.glassBlurDp, it.readSystemWallpaper) }
                .distinctUntilChanged()
                .drop(1)
                .collect { refresh() }
        }
        refresh()
    }

    fun refresh() {
        job?.cancel()
        job = scope.launch(Dispatchers.Default) {
            val s = settings.value
            val (sw, sh) = screenSize()
            val density = context.resources.displayMetrics.density
            val source: Bitmap? = when (s.wallpaperMode) {
                WallpaperMode.CUSTOM -> decodeCustom(sw, sh)
                WallpaperMode.SYSTEM -> if (s.readSystemWallpaper) readSystem() else null
            }
            val systemTone = if (s.wallpaperMode == WallpaperMode.SYSTEM) systemTone() else null

            if (source == null) {
                _sharp.value = null
                _blurred.value = null
                _tone.value = systemTone ?: WallpaperTone()
                return@launch
            }

            val sharp = if (s.wallpaperMode == WallpaperMode.CUSTOM) centerCrop(source, sw, sh) else null
            val scale = 0.25f
            val small = centerCrop(source, max(1, (sw * scale).roundToInt()), max(1, (sh * scale).roundToInt()))
            val radius = (s.glassBlurDp * density * scale / 2f).roundToInt().coerceIn(1, 60)
            val blurred = BoxBlur.blur(small, radius)
            if (small != source && small != sharp) small.recycle()
            blurred.prepareToDraw()
            sharp?.prepareToDraw()

            _sharp.value = sharp?.asImageBitmap()
            _blurred.value = BackdropImage(blurred, scale, sw, sh)
            _tone.value = systemTone ?: toneOf(blurred)
            if (s.wallpaperMode == WallpaperMode.SYSTEM) runCatching { wm.forgetLoadedWallpaper() }
        }
    }

    /** Copies a picked image into private storage and (optionally) sets it as system wallpaper. */
    suspend fun setCustomWallpaper(uri: Uri, alsoSystem: Boolean): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                customFile.outputStream().use { input.copyTo(it) }
            } ?: return@runCatching false
            true
        }.getOrDefault(false)
        if (!ok) return@withContext false
        if (alsoSystem) {
            val (sw, sh) = screenSize()
            decodeCustom(sw, sh)?.let { bmp ->
                runCatching {
                    @SuppressLint("MissingPermission")
                    wm.setBitmap(centerCrop(bmp, sw, sh), null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                }
            }
        }
        settings.update { it.copy(wallpaperMode = WallpaperMode.CUSTOM) }
        refresh()
        true
    }

    @SuppressLint("MissingPermission")
    private fun readSystem(): Bitmap? {
        val result = runCatching {
            if (wm.wallpaperInfo != null) return@runCatching null // live wallpaper: nothing static to sample
            val d = wm.drawable ?: return@runCatching null
            (d as? BitmapDrawable)?.bitmap ?: d.toBitmap()
        }
        _canReadSystem.value = result.isSuccess && result.getOrNull() != null
        return result.getOrNull()
    }

    private fun systemTone(): WallpaperTone? {
        if (Build.VERSION.SDK_INT < 27) return null
        val colors: WallpaperColors = runCatching { wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull() ?: return null
        val primary = colors.primaryColor.toArgb()
        val secondary = colors.secondaryColor?.toArgb() ?: primary
        val dark = if (Build.VERSION.SDK_INT >= 31) {
            colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT == 0
        } else luminance(primary) < 0.55f
        return WallpaperTone(dark, primary, secondary)
    }

    private fun toneOf(bmp: Bitmap): WallpaperTone {
        val tiny = Bitmap.createScaledBitmap(bmp, 8, 16, true)
        var lum = 0f
        var r = 0
        var g = 0
        var b = 0
        for (y in 0 until 16) for (x in 0 until 8) {
            val p = tiny.getPixel(x, y)
            lum += luminance(p)
            r += Color.red(p); g += Color.green(p); b += Color.blue(p)
        }
        if (tiny != bmp) tiny.recycle()
        val n = 128
        val avg = Color.rgb(r / n, g / n, b / n)
        return WallpaperTone(lum / n < 0.55f, avg, avg)
    }

    private fun luminance(c: Int) = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f

    private fun decodeCustom(sw: Int, sh: Int): Bitmap? = runCatching {
        if (!customFile.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(customFile.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sw && bounds.outHeight / (sample * 2) >= sh) sample *= 2
        BitmapFactory.decodeFile(customFile.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    fun screenSize(): Pair<Int, Int> {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val p = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(p)
        // Match the current orientation reported by resources.
        val portrait = context.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val w = if (portrait) minOf(p.x, p.y) else maxOf(p.x, p.y)
        val h = if (portrait) maxOf(p.x, p.y) else minOf(p.x, p.y)
        return w to h
    }

    companion object {
        /** Center-crop [src] to exactly [w] x [h] (like the system draws a static wallpaper). */
        fun centerCrop(src: Bitmap, w: Int, h: Int): Bitmap {
            if (src.width == w && src.height == h) return src
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
            val cw = (w / scale).roundToInt().coerceAtMost(src.width)
            val ch = (h / scale).roundToInt().coerceAtMost(src.height)
            val l = (src.width - cw) / 2
            val t = (src.height - ch) / 2
            Canvas(out).drawBitmap(src, Rect(l, t, l + cw, t + ch), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            return out
        }
    }
}

/** Three-pass box blur ≈ gaussian. Runs on a quarter-res bitmap, so it's a few ms. */
object BoxBlur {
    fun blur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        src.getPixels(a, 0, w, 0, 0, w, h)
        val r = max(1, radius / 2)
        repeat(3) {
            pass(a, b, w, h, r, horizontal = true)
            pass(b, a, w, h, r, horizontal = false)
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun idx(i: Int): Int {
                val c = i.coerceIn(0, len - 1)
                return if (horizontal) line * w + c else c * w + line
            }
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = src[idx(i)]
                sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
            }
            for (i in 0 until len) {
                dst[idx(i)] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val outP = src[idx(i - r)]
                val inP = src[idx(i + r + 1)]
                sr += ((inP shr 16) and 0xFF) - ((outP shr 16) and 0xFF)
                sg += ((inP shr 8) and 0xFF) - ((outP shr 8) and 0xFF)
                sb += (inP and 0xFF) - (outP and 0xFF)
            }
        }
    }
}
