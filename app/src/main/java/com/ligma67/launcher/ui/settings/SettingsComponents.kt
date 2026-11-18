package com.ligma67.launcher.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ligma67.launcher.glass.glass
import com.ligma67.launcher.ui.theme.LocalLauncherColors

@Composable
fun SettingsPage(title: String, onBack: (() -> Unit)?, content: LazyListScope.() -> Unit) {
    val c = LocalLauncherColors.current
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable { onBack() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.onGlass)
                }
                Spacer(Modifier.width(4.dp))
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Text(title, color = c.onGlass, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
fun Group(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalLauncherColors.current
    Column {
        if (title != null) {
            Text(
                title.uppercase(),
                color = c.onGlassDim,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 14.dp, bottom = 8.dp),
            )
        }
        Column(Modifier.fillMaxWidth().glass(24.dp).padding(vertical = 6.dp), content = content)
    }
}

@Composable
fun RowText(title: String, summary: String?, modifier: Modifier = Modifier) {
    val c = LocalLauncherColors.current
    Column(modifier) {
        Text(title, color = c.onGlass, fontSize = 16.sp)
        if (!summary.isNullOrEmpty()) Text(summary, color = c.onGlassDim, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, summary: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = LocalLauncherColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title, summary, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = { onChange(it) },
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = Color.White),
        )
    }
}

/**
 * Slider row. [live] = false keeps the value local while dragging and commits on release —
 * used for settings that are expensive to apply (icon re-rendering, wallpaper re-blur).
 */
@Composable
fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    live: Boolean = true,
    summary: String? = null,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    val c = LocalLauncherColors.current
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value) }
    val shown = if (!live && dragging) local else value
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowText(title, summary, Modifier.weight(1f))
            Text(format(shown), color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = shown,
            onValueChange = {
                if (live) {
                    onChange(it)
                } else {
                    dragging = true
                    local = it
                }
            },
            onValueChangeFinished = {
                if (!live && dragging) onChange(local)
                dragging = false
            },
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = c.accent,
                inactiveTrackColor = c.onGlassDim.copy(alpha = 0.25f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

/** Tap to expand an inline list of options. */
@Composable
fun <T> ChoiceRow(title: String, value: T, options: List<T>, summary: String? = null, label: (T) -> String, onChange: (T) -> Unit) {
    val c = LocalLauncherColors.current
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowText(title, summary, Modifier.weight(1f))
            Text(label(value), color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                for (o in options) {
                    val selected = o == value
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) c.accent.copy(alpha = 0.18f) else Color.Transparent)
                            .clickable {
                                onChange(o)
                                open = false
                            }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label(o), color = c.onGlass, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (selected) Icon(Icons.Filled.Check, null, tint = c.accent, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ActionRow(title: String, summary: String? = null, icon: ImageVector? = null, accent: Boolean = false, onClick: () -> Unit) {
    val c = LocalLauncherColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = c.accent, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(14.dp))
        }
        if (accent) {
            Column(Modifier.weight(1f)) {
                Text(title, color = c.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (summary != null) Text(summary, color = c.onGlassDim, fontSize = 13.sp)
            }
        } else {
            RowText(title, summary, Modifier.weight(1f))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.onGlassDim)
    }
}

@Composable
fun InfoText(text: String) {
    val c = LocalLauncherColors.current
    Text(text, color = c.onGlassDim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
}

private val SWATCHES = listOf(
    0xFF5B8CFF, 0xFF7C5CFF, 0xFFFF5C93, 0xFFFF6B4A, 0xFFFFB020, 0xFF33D17A,
    0xFF18C8C8, 0xFFFFFFFF, 0xFF9AA4B2, 0xFF101114,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorRow(title: String, color: Long, onChange: (Long) -> Unit) {
    val c = LocalLauncherColors.current
    val hsv = remember(color) { FloatArray(3).also { android.graphics.Color.colorToHSV(color.toInt(), it) } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
        Text(title, color = c.onGlass, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (sw in SWATCHES) {
                val selected = sw.toInt() == color.toInt()
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(sw.toInt()))
                        .border(if (selected) 3.dp else 1.dp, if (selected) c.accent else Color.White.copy(alpha = 0.35f), CircleShape)
                        .clickable { onChange(sw) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(CircleShape)
                .background(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 0.8f, 1f) })),
        )
        Slider(
            value = hsv[0],
            onValueChange = { h ->
                val argb = android.graphics.Color.HSVToColor(floatArrayOf(h, hsv[1].coerceAtLeast(0.35f), hsv[2].coerceAtLeast(0.5f)))
                onChange(argb.toLong() and 0xFFFFFFFFL)
            },
            valueRange = 0f..359f,
            colors = SliderDefaults.colors(thumbColor = Color(color.toInt()), activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipsRow(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit) {
    val c = LocalLauncherColors.current
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (o in options) {
            val sel = o == selected
            Text(
                label(o),
                color = if (sel) Color.White else c.onGlass,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (sel) c.accent else c.onGlass.copy(alpha = 0.1f))
                    .clickable { onSelect(o) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

fun pct(v: Float) = "${(v * 100).toInt()}%"
fun ms(v: Float) = "${v.toInt()} ms"
fun dp(v: Float) = "${v.toInt()} dp"
fun x2(v: Float) = String.format(java.util.Locale.US, "%.2f", v)
