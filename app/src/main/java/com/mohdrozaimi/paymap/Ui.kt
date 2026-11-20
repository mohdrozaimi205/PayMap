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

/** Palet "Peta Kertas": krim hangat, hijau zamrud, pin karang. */
object C {
    val Bg = Color(0xFFF4EFE6)
    val Card = Color(0xFFFFFCF6)
    val Ink = Color(0xFF1C2622)
    val Sub = Color(0xFF6B746F)
    val Line = Color(0xFFE6DFD2)
    val Emerald = Color(0xFF0E5A48)
    val EmeraldLight = Color(0xFF1F8A6E)
    val Mint = Color(0xFFDCEDE4)
    val Coral = Color(0xFFE5643F)
    val CoralSoft = Color(0xFFFBE3DA)
    val Gold = Color(0xFFD9A441)
}

private val catPalette = listOf(
    Color(0xFFE5643F), Color(0xFF0E5A48), Color(0xFFD9A441), Color(0xFF3F6FB5),
    Color(0xFF8E5BB5), Color(0xFF2E9C8F), Color(0xFFB5475B), Color(0xFF7A7F6E)
)

fun catColor(cat: String): Color {
    val all = Cats.expense + Cats.income
    val i = all.indexOf(cat).let { if (it < 0) (cat.hashCode() and 0x7fffffff) else it }
    return catPalette[i % catPalette.size]
}

@Composable
fun PayMapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = C.Emerald,
            onPrimary = Color.White,
            secondary = C.Coral,
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
            .clip(RoundedCornerShape(50))
            .background(if (selected) color else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            color = if (selected) Color.White else C.Sub,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 13.sp
        )
    }
}

@Composable
fun ModeSwitch(mode: String, onChange: (String) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(C.Line)
            .padding(3.dp)
    ) {
        Pill("Personal", mode == "Personal") { onChange("Personal") }
        Pill("Business", mode == "Business") { onChange("Business") }
    }
}

@Composable
fun ChipSelect(text: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) color.copy(alpha = 0.16f) else C.Card)
            .border(1.dp, if (selected) color else C.Line, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(text, color = if (selected) color else C.Ink, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
fun CardBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(C.Card)
            .border(1.dp, C.Line, RoundedCornerShape(24.dp))
            .padding(18.dp)
    ) { content() }
}

@Composable
fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(50))
            .background(C.Line)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(8.dp)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
    }
}

@Composable
fun TxnRow(t: Txn, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val col = catColor(t.category)
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(col.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                t.merchant.trim().firstOrNull()?.uppercase() ?: "?",
                color = col, fontWeight = FontWeight.Bold, fontSize = 18.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.merchant, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = C.Ink)
            val bits = listOfNotNull(
                t.category,
                fmtShort(t.time),
                if (t.place.isNotBlank()) "📍 " + t.place else null
            )
            Text(bits.joinToString(" · "), fontSize = 12.sp, color = C.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                (if (t.isIncome) "+" else "−") + rm(t.amount),
                fontWeight = FontWeight.Bold,
                color = if (t.isIncome) C.EmeraldLight else C.Ink
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (t.receipt != null) Text("🧾", fontSize = 11.sp)
                Text(t.source, fontSize = 11.sp, color = C.Sub)
            }
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
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE5643F.toInt() }
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
