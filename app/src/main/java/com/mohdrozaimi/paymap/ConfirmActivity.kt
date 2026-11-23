package com.mohdrozaimi.paymap

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

/** Data sementara sebelum disimpan. */
data class Draft(
    val amount: Double?,
    val isIncome: Boolean,
    val merchant: String,
    val source: String,
    val lat: Double?,
    val lng: Double?,
    val place: String,
    val time: Long
)

/** Pop up "Semak & Simpan" — muncul selepas bayaran dikesan, atau untuk tambah/edit manual. */
class ConfirmActivity : ComponentActivity() {

    companion object {
        fun intent(ctx: Context, d: Draft, notifId: Int): Intent =
            Intent(ctx, ConfirmActivity::class.java).apply {
                putExtra("hasDraft", true)
                putExtra("amount", d.amount ?: 0.0)
                putExtra("income", d.isIncome)
                putExtra("merchant", d.merchant)
                putExtra("source", d.source)
                putExtra("lat", d.lat ?: Double.NaN)
                putExtra("lng", d.lng ?: Double.NaN)
                putExtra("place", d.place)
                putExtra("time", d.time)
                putExtra("notifId", notifId)
            }

        fun manual(ctx: Context): Intent = Intent(ctx, ConfirmActivity::class.java)

        fun edit(ctx: Context, id: Long): Intent =
            Intent(ctx, ConfirmActivity::class.java).putExtra("editId", id)
    }

    private var editId = -1L
    private var notifId = 0
    private var source = "Manual"
    private var time = System.currentTimeMillis()

    private val amountText = mutableStateOf("")
    private val isIncome = mutableStateOf(false)
    private val merchant = mutableStateOf("")
    private val category = mutableStateOf("Lain-lain")
    private val mode = mutableStateOf("Personal")
    private val note = mutableStateOf("")
    private val lat = mutableStateOf<Double?>(null)
    private val lng = mutableStateOf<Double?>(null)
    private val place = mutableStateOf("")
    private val receipt = mutableStateOf<String?>(null)
    private val locating = mutableStateOf(false)

    private var camFile: File? = null

    private val pickDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) copyIn(uri)?.let { receipt.value = it }
    }
    private val takePic = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) receipt.value = camFile?.absolutePath
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        loadFrom(intent)
        setContent { PayMapTheme { Sheet() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadFrom(intent)
    }

    private fun loadFrom(i: Intent) {
        mode.value = Store.mode.value
        editId = i.getLongExtra("editId", -1L)
        val existing = if (editId > 0) Store.get(editId) else null
        if (existing != null) {
            amountText.value = String.format(java.util.Locale.US, "%.2f", existing.amount)
            isIncome.value = existing.isIncome
            merchant.value = existing.merchant
            category.value = existing.category
            mode.value = existing.mode
            note.value = existing.note
            lat.value = existing.lat
            lng.value = existing.lng
            place.value = existing.place
            receipt.value = existing.receipt
            source = existing.source
            time = existing.time
            return
        }
        if (i.getBooleanExtra("hasDraft", false)) {
            val a = i.getDoubleExtra("amount", 0.0)
            amountText.value = if (a > 0) String.format(java.util.Locale.US, "%.2f", a) else ""
            isIncome.value = i.getBooleanExtra("income", false)
            merchant.value = i.getStringExtra("merchant") ?: ""
            source = i.getStringExtra("source") ?: "Manual"
            val la = i.getDoubleExtra("lat", Double.NaN)
            val ln = i.getDoubleExtra("lng", Double.NaN)
            lat.value = if (la.isNaN()) null else la
            lng.value = if (ln.isNaN()) null else ln
            place.value = i.getStringExtra("place") ?: ""
            time = i.getLongExtra("time", System.currentTimeMillis())
            notifId = i.getIntExtra("notifId", 0)
            category.value = Cats.guess(merchant.value, isIncome.value)
            if (lat.value == null) refreshLocation()
        } else {
            source = "Manual"
            time = System.currentTimeMillis()
            category.value = "Makan & Minum"
            refreshLocation()
        }
    }

    private fun refreshLocation() {
        if (!LocationHelper.hasPermission(this)) {
            Toast.makeText(this, "Benarkan lokasi dalam Tetapan PayMap", Toast.LENGTH_SHORT).show()
            return
        }
        locating.value = true
        lifecycleScope.launch {
            val loc = LocationHelper.current(this@ConfirmActivity)
            if (loc != null) {
                lat.value = loc.latitude
                lng.value = loc.longitude
                place.value = LocationHelper.placeName(this@ConfirmActivity, loc.latitude, loc.longitude)
            }
            locating.value = false
        }
    }

    private fun copyIn(uri: Uri): String? = try {
        val mime = contentResolver.getType(uri) ?: ""
        val ext = if (mime.contains("pdf")) "pdf" else "jpg"
        val dir = File(filesDir, "receipts").apply { mkdirs() }
        val f = File(dir, "resit_${System.currentTimeMillis()}.$ext")
        contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
        f.absolutePath
    } catch (e: Exception) {
        Toast.makeText(this, "Gagal ambil fail", Toast.LENGTH_SHORT).show()
        null
    }

    private fun startCamera() {
        val dir = File(filesDir, "receipts").apply { mkdirs() }
        val f = File(dir, "resit_${System.currentTimeMillis()}.jpg")
        camFile = f
        try {
            takePic.launch(FileProvider.getUriForFile(this, "$packageName.files", f))
        } catch (e: Exception) {
            Toast.makeText(this, "Kamera tidak dapat dibuka", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openReceipt(path: String) {
        val f = File(path)
        val type = if (path.endsWith(".pdf")) "application/pdf" else "image/*"
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(FileProvider.getUriForFile(this, "$packageName.files", f), type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(i) }
    }

    private fun save() {
        val amt = amountText.value.replace(",", "").trim().toDoubleOrNull()
        if (amt == null || amt <= 0) {
            Toast.makeText(this, "Masukkan jumlah yang betul", Toast.LENGTH_SHORT).show()
            return
        }
        val t = Txn(
            id = if (editId > 0) editId else System.currentTimeMillis(),
            amount = amt,
            isIncome = isIncome.value,
            merchant = merchant.value.trim().ifBlank { if (isIncome.value) "Duit masuk" else "Belanja" },
            category = category.value,
            note = note.value.trim(),
            time = time,
            lat = lat.value,
            lng = lng.value,
            place = place.value,
            source = source,
            mode = mode.value,
            receipt = receipt.value
        )
        Store.upsert(t)
        Store.markSaved(notifId)
        BudgetAlert.check(this, t)
        Notif.cancel(this, notifId)
        Toast.makeText(this, "Disimpan ✓", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun remove() {
        if (editId > 0) Store.delete(editId)
        Toast.makeText(this, "Dipadam", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun dismiss() {
        Notif.cancel(this, notifId)
        finish()
    }

    @Composable
    private fun Sheet() {
        val noRipple = remember { MutableInteractionSource() }
        val noRipple2 = remember { MutableInteractionSource() }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(interactionSource = noRipple, indication = null) { finish() },
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 780.dp)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(C.Bg)
                    .clickable(interactionSource = noRipple2, indication = null) { }
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                // Pemegang
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(36.dp).height(5.dp).clip(RoundedCornerShape(50)).background(Color(0xFFC7C7CC)))
                }
                Spacer(Modifier.height(12.dp))

                val heading = when {
                    editId > 0 -> "Edit transaksi"
                    source == "Manual" -> "Tambah transaksi"
                    else -> "Bayaran dikesan · $source"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(heading, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = C.Ink)
                        Text(fmtDateTime(time), fontSize = 12.sp, color = C.Sub)
                    }
                    TextButton(onClick = { dismiss() }) { Text("Abai", color = C.Sub) }
                }
                Spacer(Modifier.height(14.dp))

                // Belanja / Masuk
                Row(
                    Modifier.clip(RoundedCornerShape(11.dp)).background(C.Fill).padding(2.dp)
                ) {
                    Pill("Belanja", !isIncome.value, C.Coral) {
                        isIncome.value = false
                        if (category.value !in Cats.expense) category.value = Cats.guess(merchant.value, false)
                    }
                    Pill("Duit masuk", isIncome.value, C.Emerald) {
                        isIncome.value = true
                        if (category.value !in Cats.income) category.value = Cats.guess(merchant.value, true)
                    }
                }
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = amountText.value,
                    onValueChange = { v -> amountText.value = v.filter { it.isDigit() || it == '.' || it == ',' } },
                    prefix = { Text("RM ", fontSize = 26.sp, color = C.Sub) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Ink),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = merchant.value,
                    onValueChange = { merchant.value = it },
                    label = { Text(if (isIncome.value) "Daripada" else "Kedai / penerima") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                )

                Spacer(Modifier.height(14.dp))
                Label("Kategori")
                val cats = if (isIncome.value) Cats.income else Cats.expense
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(cats) { c ->
                        ChipSelect(c, category.value == c, catColor(c)) { category.value = c }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Akaun", Modifier.weight(1f))
                    ModeSwitch(mode.value) { mode.value = it }
                }

                Spacer(Modifier.height(14.dp))
                Label("Lokasi")
                LocationBlock()

                Spacer(Modifier.height(14.dp))
                Label("Resit")
                ReceiptBlock()

                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = note.value,
                    onValueChange = { note.value = it },
                    label = { Text("Nota (cth: makan tengah hari)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                )

                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (editId > 0) {
                        OutlinedButton(
                            onClick = { remove() },
                            modifier = Modifier.height(54.dp),
                            shape = RoundedCornerShape(18.dp)
                        ) { Text("Padam", color = C.Coral) }
                    }
                    Button(
                        onClick = { save() },
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = C.Blue)
                    ) { Text("Simpan", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    @Composable
    private fun Label(text: String, modifier: Modifier = Modifier) {
        Text(
            text.uppercase(), modifier = modifier.padding(bottom = 8.dp),
            fontSize = 11.sp, letterSpacing = 1.2.sp, color = C.Sub, fontWeight = FontWeight.SemiBold
        )
    }

    @Composable
    private fun LocationBlock() {
        CardBox {
            Column {
                val la = lat.value
                val ln = lng.value
                if (la != null && ln != null) {
                    OsmMap(
                        pins = listOf(MapPin(la, ln, shortAmount(amountText.value.replace(",", "").toDoubleOrNull() ?: 0.0), merchant.value)),
                        modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp)),
                        interactive = false
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Text(
                    when {
                        locating.value -> "Mencari lokasi…"
                        place.value.isNotBlank() -> "📍 ${place.value}"
                        la != null -> "📍 Lokasi direkod"
                        else -> "Tiada lokasi (cth: bayaran online)"
                    },
                    color = C.Ink, fontSize = 14.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChipSelect("↻ Lokasi semasa", false, C.Emerald) { refreshLocation() }
                    if (la != null) ChipSelect("✕ Buang lokasi", false, C.Coral) {
                        lat.value = null; lng.value = null; place.value = ""
                    }
                }
            }
        }
    }

    @Composable
    private fun ReceiptBlock() {
        val path = receipt.value
        CardBox {
            Column {
                if (path != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val bmp = remember(path) {
                            if (path.endsWith(".pdf")) null else runCatching {
                                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 6 })
                            }.getOrNull()
                        }
                        Box(
                            Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(C.Line)
                                .border(1.dp, C.Line, RoundedCornerShape(12.dp))
                                .clickable { openReceipt(path) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else Text("PDF", fontWeight = FontWeight.Bold, color = C.Coral)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Resit dilampirkan", fontWeight = FontWeight.SemiBold)
                            Text("Tekan gambar untuk lihat", fontSize = 12.sp, color = C.Sub)
                        }
                        TextButton(onClick = { receipt.value = null }) { Text("Buang", color = C.Coral) }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChipSelect("📷 Kamera", false, C.Emerald) { startCamera() }
                        ChipSelect("🖼 Galeri / PDF", false, C.Emerald) {
                            pickDoc.launch(arrayOf("image/*", "application/pdf"))
                        }
                    }
                }
            }
        }
    }
}
