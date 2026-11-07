package pk.aidesk.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

object C {
    val Bg = Color(0xFF0B0F17)
    val Panel = Color(0xFF121A26)
    val Panel2 = Color(0xFF182233)
    val Border = Color(0xFF223047)
    val Txt = Color(0xFFE6EDF3)
    val Muted = Color(0xFF8B98A9)
    val Dim = Color(0xFF4B5869)
    val Green = Color(0xFF16C784)
    val Red = Color(0xFFEA3943)
    val Amber = Color(0xFFF0B90B)
    val Blue = Color(0xFF3B82F6)
    val GreenBg = Color(0xFF0F2A1F)
    val RedBg = Color(0xFF3A1820)

    fun side(side: String) = if (side == "LONG") Green else Red
    fun mode(mode: String) = when (mode) { "live" -> Red; "testnet" -> Amber; else -> Blue }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Green, onPrimary = C.Bg, secondary = C.Blue, background = C.Bg, surface = C.Panel,
            onSurface = C.Txt, onBackground = C.Txt, surfaceVariant = C.Panel2, outline = C.Border, error = C.Red,
        ),
        content = content,
    )
}

@Composable
fun T(text: String, size: TextUnit = 13.sp, color: Color = C.Txt, weight: FontWeight = FontWeight.Normal,
      mono: Boolean = false, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier = modifier, maxLines = maxLines,
        style = TextStyle(fontSize = size, color = color, fontWeight = weight, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default))
}

@Composable
fun Card(modifier: Modifier = Modifier, border: Color? = null, bg: Color = C.Panel, faded: Boolean = false,
         onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    var m = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg)
    if (border != null) m = m.border(1.dp, border, RoundedCornerShape(12.dp))
    if (onClick != null) m = m.clickable { onClick() }
    if (faded) m = m.alpha(0.42f)
    Column(m.padding(12.dp), content = content)
}

@Composable
fun Pill(text: String, color: Color, filled: Boolean = false, onClick: (() -> Unit)? = null) {
    var m = Modifier.clip(RoundedCornerShape(50)).background(if (filled) color else C.Panel2)
    if (!filled) m = m.border(1.dp, color, RoundedCornerShape(50))
    if (onClick != null) m = m.clickable { onClick() }
    Box(m.padding(horizontal = 9.dp, vertical = 3.dp)) {
        T(text, 11.sp, if (filled) C.Bg else color, FontWeight.Bold)
    }
}

@Composable
fun Btn(text: String, color: Color, modifier: Modifier = Modifier, enabled: Boolean = true, outline: Boolean = false,
        height: Dp = 48.dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    var m = modifier.fillMaxWidth().height(height).clip(shape)
    m = if (outline) m.border(1.dp, color, shape).background(C.Panel2) else m.background(if (enabled) color else C.Dim)
    if (enabled) m = m.clickable { onClick() }
    Box(m, contentAlignment = Alignment.Center) {
        T(text, 15.sp, if (outline) color else C.Bg, FontWeight.Bold)
    }
}

@Composable
fun SectionTitle(text: String) {
    T(text.uppercase(), 11.sp, C.Blue, FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

@Composable
fun KV(k: String, v: String, color: Color = C.Txt, mono: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        T(k, 13.sp, C.Muted)
        T(v, 13.sp, color, mono = mono)
    }
}

@Composable
fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.Panel2)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.03f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color))
    }
}

@Composable
fun Gap(h: Dp = 8.dp) = Spacer(Modifier.height(h))

@Composable
fun HGap(w: Dp = 8.dp) = Spacer(Modifier.width(w))

fun px(v: Double?): String = v?.let { pk.aidesk.app.core.Engine.fmt(it) } ?: "-"
fun pct0(p: Double) = "${(p * 100).toInt()}%"
fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
fun sgn2(v: Double) = String.format(Locale.US, "%+.2f", v)
