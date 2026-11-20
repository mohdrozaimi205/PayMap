package com.mohdrozaimi.paymap

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.time.YearMonth

data class Summary(
    val list: List<Txn>,
    val spent: Double,
    val income: Double,
    val byCat: List<Pair<String, Double>>,
    val byPlace: List<Triple<String, Double, Int>>
)

object Statement {

    fun summary(ym: YearMonth, mode: String): Summary {
        val list = Store.txns.value.filter { it.mode == mode && it.yearMonth() == ym }.sortedBy { it.time }
        val exp = list.filter { !it.isIncome }
        val byCat = exp.groupBy { it.category }.map { it.key to it.value.sumOf { t -> t.amount } }
            .sortedByDescending { it.second }
        val byPlace = exp.groupBy { it.merchant.ifBlank { it.place.ifBlank { "Tidak diketahui" } } }
            .map { Triple(it.key, it.value.sumOf { t -> t.amount }, it.value.size) }
            .sortedByDescending { it.second }
        return Summary(list, exp.sumOf { it.amount }, list.filter { it.isIncome }.sumOf { it.amount }, byCat, byPlace)
    }

    fun buildPdf(ctx: Context, ym: YearMonth, mode: String): File {
        val s = summary(ym, mode)
        val doc = PdfDocument()
        val w = 595
        val h = 842
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
        var c = page.canvas

        val green = 0xFF0E5A48.toInt()
        val coral = 0xFFE5643F.toInt()
        val ink = 0xFF1C2622.toInt()
        val sub = 0xFF6B746F.toInt()
        val line = 0xFFE6DFD2.toInt()

        val title = Paint().apply { color = ink; textSize = 22f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val bold = Paint().apply { color = ink; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val norm = Paint().apply { color = ink; textSize = 10f; isAntiAlias = true }
        val small = Paint().apply { color = sub; textSize = 9f; isAntiAlias = true }
        val fill = Paint().apply { isAntiAlias = true }
        val right = Paint(norm).apply { textAlign = Paint.Align.RIGHT }
        val rightBold = Paint(bold).apply { textAlign = Paint.Align.RIGHT }

        // Kepala
        fill.color = green
        c.drawRect(0f, 0f, w.toFloat(), 8f, fill)
        c.drawText("PayMap", 40f, 50f, Paint(title).apply { color = green; textSize = 14f })
        c.drawText("Penyata ${ym.label()}", 40f, 78f, title)
        c.drawText("Akaun: $mode  ·  ${s.list.size} transaksi", 40f, 96f, small)

        // Kotak jumlah
        var y = 118f
        val boxes = listOf(
            Triple("Belanja", rm(s.spent), coral),
            Triple("Masuk", rm(s.income), green),
            Triple("Baki", rm(s.income - s.spent), ink)
        )
        boxes.forEachIndexed { i, b ->
            val x = 40f + i * 175f
            fill.color = 0xFFF4EFE6.toInt()
            c.drawRoundRect(RectF(x, y, x + 160f, y + 56f), 10f, 10f, fill)
            c.drawText(b.first, x + 12f, y + 20f, small)
            c.drawText(b.second, x + 12f, y + 42f, Paint(bold).apply { color = b.third; textSize = 15f })
        }
        y += 84f

        // Ikut kategori
        c.drawText("Ikut kategori", 40f, y, bold)
        y += 14f
        val maxCat = s.byCat.maxOfOrNull { it.second } ?: 1.0
        s.byCat.forEach { (cat, v) ->
            c.drawText(cat, 40f, y + 10f, norm)
            fill.color = line
            c.drawRoundRect(RectF(170f, y + 2f, 470f, y + 12f), 5f, 5f, fill)
            fill.color = coral
            val wBar = (300f * (v / maxCat)).toFloat().coerceAtLeast(4f)
            c.drawRoundRect(RectF(170f, y + 2f, 170f + wBar, y + 12f), 5f, 5f, fill)
            c.drawText(rm(v), 555f, y + 10f, right)
            y += 18f
        }
        if (s.byCat.isEmpty()) {
            c.drawText("Tiada belanja bulan ini.", 40f, y + 10f, small); y += 18f
        }
        y += 16f

        // Tempat teratas
        c.drawText("Ke mana duit pergi", 40f, y, bold)
        y += 16f
        s.byPlace.take(8).forEachIndexed { i, (name, v, n) ->
            c.drawText("${i + 1}.  $name", 40f, y, norm)
            c.drawText("$n kali", 420f, y, small)
            c.drawText(rm(v), 555f, y, right)
            y += 15f
        }
        y += 18f

        // Senarai transaksi
        fun header() {
            c.drawText("Tarikh", 40f, y, bold)
            c.drawText("Butiran", 150f, y, bold)
            c.drawText("Kategori", 345f, y, bold)
            c.drawText("Jumlah", 555f, y, rightBold)
            y += 6f
            fill.color = line
            c.drawRect(40f, y, 555f, y + 1f, fill)
            y += 14f
        }
        c.drawText("Semua transaksi", 40f, y, bold)
        y += 18f
        header()
        s.list.forEach { t ->
            if (y > h - 50f) {
                doc.finishPage(page)
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
                c = page.canvas
                y = 50f
                header()
            }
            c.drawText(fmtShort(t.time), 40f, y, small)
            c.drawText(t.merchant.take(34), 150f, y, norm)
            c.drawText(t.category, 345f, y, small)
            val amt = (if (t.isIncome) "+" else "-") + rm(t.amount)
            c.drawText(amt, 555f, y, Paint(right).apply { color = if (t.isIncome) green else ink })
            if (t.place.isNotBlank()) {
                c.drawText(t.place.take(44), 150f, y + 11f, small)
                y += 11f
            }
            y += 16f
        }
        c.drawText("Dijana oleh PayMap · data disimpan dalam telefon anda", 40f, h - 24f, small)
        doc.finishPage(page)

        val dir = File(ctx.cacheDir, "penyata").apply { mkdirs() }
        val f = File(dir, "PayMap-$mode-$ym.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    private fun uri(ctx: Context, f: File) = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    fun view(ctx: Context, f: File) {
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri(ctx, f), "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "Tiada app pembaca PDF. Cuba Kongsi atau Simpan.", Toast.LENGTH_LONG).show()
        }
    }

    fun share(ctx: Context, f: File, text: String) {
        val i = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri(ctx, f))
            .putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Kongsi penyata").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Simpan ke folder Download/PayMap dalam telefon. */
    fun saveToDownloads(ctx: Context, f: File): Boolean = try {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PayMap")
        }
        val u = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
        if (u == null) false else {
            ctx.contentResolver.openOutputStream(u)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            true
        }
    } catch (e: Exception) {
        false
    }
}
