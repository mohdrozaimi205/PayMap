package com.mohdrozaimi.paymap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File

/** Palet gaya iOS 18: latar kelabu "grouped", kad putih, warna sistem Apple. */
object C {
    val Bg = Color(0xFFF2F2F7)        // systemGroupedBackground
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF000000)
    val Sub = Color(0xFF8E8E93)       // secondaryLabel
    val Line = Color(0xFFE5E5EA)      // separator
    val Fill = Color(0x1F767680)      // segmented control track
    val Blue = Color(0xFF007AFF)
    val Cyan = Color(0xFF5AC8FA)
    val Green = Color(0xFF34C759)
    val Red = Color(0xFFFF3B30)
    val Orange = Color(0xFFFF9500)
    val Yellow = Color(0xFFFFCC00)
    val Purple = Color(0xFFAF52DE)
    val Indigo = Color(0xFF5856D6)
    val Teal = Color(0xFF30B0C7)
    val Pink = Color(0xFFFF2D55)
    val Gray = Color(0xFF8E8E93)

    // Nama lama (dipetakan ke warna iOS)
    val Emerald = Blue
    val EmeraldLight = Green
    val Mint = Color(0xFFE3F0FF)
    val Coral = Red
    val CoralSoft = Color(0xFFFFEBEA)
    val Gold = Orange
}

private val catPalette = listOf(
    C.Orange, C.Green, C.Blue, C.Yellow, C.Pink, C.Red, C.Purple, C.Gray,
    C.Indigo, C.Teal, C.Cyan
)

/** Ikon emoji untuk setiap kategori (dalam petak bulat gaya iOS). */
fun catIcon(cat: String): String = when (cat) {
    "Makan & Minum" -> "🍔"
    "Barang Runcit" -> "🛒"
    "Minyak & Transport" -> "⛽"
    "Bil & Utiliti" -> "💡"
    "Belanja" -> "🛍️"
    "Kesihatan" -> "💊"
    "Hiburan" -> "🎬"
    "Gaji" -> "💼"
    "Pindahan Masuk" -> "💸"
    "Jualan" -> "🏷️"
    else -> "📦"
}

/** Petak ikon bulat (squircle) berwarna gaya iOS. */
@Composable
fun IosIcon(cat: String, size: Int = 40) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.27f).dp))
            .background(catColor(cat)),
        contentAlignment = Alignment.Center
    ) {
        Text(catIcon(cat), fontSize = (size * 0.48f).sp)
    }
}

/** Bentuk untuk senarai "inset grouped": hanya baris pertama & terakhir bucu bulat. */
fun groupShape(index: Int, count: Int, r: Int = 14): RoundedCornerShape {
    val top = if (index == 0) r.dp else 0.dp
    val bottom = if (index == count - 1) r.dp else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** Tajuk seksyen kecil huruf besar, gaya iOS. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier = modifier.padding(start = 16.dp, top = 6.dp),
        fontSize = 12.sp, color = C.Sub, letterSpacing = 0.4.sp
    )
}

fun catColor(cat: String): Color {
    val all = Cats.expense + Cats.income
    val i = all.indexOf(cat).let { if (it < 0) (cat.hashCode() and 0x7fffffff) else it }
    return catPalette[i % catPalette.size]
}

@Composable
fun PayMapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = C.Blue,
            onPrimary = Color.White,
            secondary = C.Orange,
            background = C.Bg,
            surface = C.Card,
            onSurface = C.Ink,
            onBackground = C.Ink,
            surfaceVariant = C.Line
        ),
        content = content
    )
}

@Composable
fun Pill(text: String, selected: Boolean, color: Color = C.Ink, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text,
            color = if (selected) (if (color == C.Ink) C.Ink else color) else C.Ink.copy(alpha = 0.6f),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 13.sp
        )
    }
}

@Composable
fun ModeSwitch(mode: String, onChange: (String) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(C.Fill)
            .padding(2.dp)
    ) {
        Pill("Personal", mode == "Personal") { onChange("Personal") }
        Pill("Business", mode == "Business") { onChange("Business") }
    }
}

@Composable
fun ChipSelect(text: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) color else C.Card)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(text, color = if (selected) Color.White else C.Ink, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
fun CardBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(C.Card)
            .padding(16.dp)
    ) { content() }
}

@Composable
fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(50))
            .background(C.Fill)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
    }
}

@Composable
fun TxnRow(
    t: Txn,
    index: Int = 0,
    count: Int = 1,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(groupShape(index, count))
            .background(C.Card)
            .clickable { onClick() }
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IosIcon(t.category, 38)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.merchant, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = C.Ink)
                val bits = listOfNotNull(
                    fmtShort(t.time),
                    if (t.place.isNotBlank()) t.place else null
                )
                Text(bits.joinToString(" · "), fontSize = 13.sp, color = C.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    (if (t.isIncome) "+" else "−") + rm(t.amount),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (t.isIncome) C.Green else C.Ink
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (t.receipt != null) Text("🧾", fontSize = 11.sp)
                    Text(t.source, fontSize = 12.sp, color = C.Sub)
                }
            }
            Spacer(Modifier.width(6.dp))
            Text("›", fontSize = 20.sp, color = Color(0xFFC4C4C7))
        }
        if (index < count - 1) {
            Box(
                Modifier
                    .padding(start = 64.dp)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(C.Line)
            )
        }
    }
}

// ---------------- Peta (OpenStreetMap, percuma) ----------------

data class MapPin(val lat: Double, val lng: Double, val label: String, val title: String)

private fun pinDrawable(mv: MapView, label: String): BitmapDrawable {
    val d = mv.resources.displayMetrics.density
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * d
        typeface = Typeface.DEFAULT_BOLD
        color = android.graphics.Color.WHITE
    }
    val tw = p.measureText(label)
    val w = (tw + 22 * d).toInt()
    val h = (30 * d).toInt()
    val tail = (7 * d).toInt()
    val bmp = Bitmap.createBitmap(w, h + tail, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF007AFF.toInt() }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * d
    }
    val r = RectF(d, d, w - d, h - d)
    c.drawRoundRect(r, h / 2f, h / 2f, bg)
    c.drawRoundRect(r, h / 2f, h / 2f, stroke)
    val path = android.graphics.Path().apply {
        moveTo(w / 2f - 6 * d, h - 2 * d); lineTo(w / 2f, (h + tail).toFloat()); lineTo(w / 2f + 6 * d, h - 2 * d); close()
    }
    c.drawPath(path, bg)
    c.drawText(label, (w - tw) / 2f, h / 2f + 4.3f * d, p)
    return BitmapDrawable(mv.resources, bmp)
}

@Composable
fun OsmMap(pins: List<MapPin>, modifier: Modifier = Modifier, interactive: Boolean = true) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val cfg = Configuration.getInstance()
            cfg.userAgentValue = ctx.packageName
            cfg.osmdroidBasePath = File(ctx.cacheDir, "osm")
            cfg.osmdroidTileCache = File(ctx.cacheDir, "osm/tiles")
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(interactive)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                isClickable = interactive
                isTilesScaledToDpi = true
                controller.setZoom(12.0)
                controller.setCenter(GeoPoint(3.139, 101.6869))
            }
        },
        update = { mv ->
            mv.overlays.clear()
            pins.forEach { pin ->
                val m = Marker(mv)
                m.position = GeoPoint(pin.lat, pin.lng)
                m.title = pin.title
                m.icon = pinDrawable(mv, pin.label)
                m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                mv.overlays.add(m)
            }
            when {
                pins.size == 1 -> {
                    mv.controller.setZoom(16.0)
                    mv.controller.setCenter(GeoPoint(pins[0].lat, pins[0].lng))
                }
                pins.size > 1 -> {
                    val box = BoundingBox.fromGeoPoints(pins.map { GeoPoint(it.lat, it.lng) })
                    mv.post {
                        runCatching { mv.zoomToBoundingBox(box.increaseByScale(1.5f), false, 60) }
                    }
                }
            }
            mv.invalidate()
        },
        onRelease = { it.onDetach() }
    )
}

fun shortAmount(v: Double): String = if (v >= 1000) "RM" + String.format(java.util.Locale.US, "%.1fk", v / 1000) else "RM" + v.toInt()
