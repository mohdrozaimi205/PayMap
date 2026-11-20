package com.mohdrozaimi.paymap

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** Satu transaksi yang disimpan. */
data class Txn(
    val id: Long,
    val amount: Double,
    val isIncome: Boolean,
    val merchant: String,
    val category: String,
    val note: String,
    val time: Long,
    val lat: Double?,
    val lng: Double?,
    val place: String,
    val source: String,
    val mode: String,
    val receipt: String?
)

/** Simpanan ringkas dalam fon (fail JSON). Semua data kekal dalam telefon sahaja. */
object Store {
    private lateinit var app: Context
    private var ready = false

    val txns = MutableStateFlow<List<Txn>>(emptyList())
    val budgets = MutableStateFlow<Map<String, Double>>(emptyMap())
    val mode = MutableStateFlow("Personal")
    val rawLog = MutableStateFlow<List<String>>(emptyList())

    private val recent = mutableListOf<Pair<String, Long>>()

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        app = ctx.applicationContext
        txns.value = loadTxns()
        budgets.value = loadBudgets()
        val p = prefs()
        mode.value = p.getString("mode", "Personal") ?: "Personal"
        rawLog.value = (p.getString("rawlog", "") ?: "").split(SEP).filter { it.isNotBlank() }
        ready = true
    }

    fun prefs(): SharedPreferences = app.getSharedPreferences("paymap", Context.MODE_PRIVATE)

    private const val SEP = "\u0001"
    private fun txnFile() = File(app.filesDir, "txns.json")
    private fun budgetFile() = File(app.filesDir, "budgets.json")

    private fun loadTxns(): List<Txn> = try {
        val f = txnFile()
        if (!f.exists()) emptyList() else {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Txn(
                    id = o.getLong("id"),
                    amount = o.getDouble("amount"),
                    isIncome = o.optBoolean("income", false),
                    merchant = o.optString("merchant", ""),
                    category = o.optString("category", "Lain-lain"),
                    note = o.optString("note", ""),
                    time = o.getLong("time"),
                    lat = if (o.isNull("lat")) null else o.getDouble("lat"),
                    lng = if (o.isNull("lng")) null else o.getDouble("lng"),
                    place = o.optString("place", ""),
                    source = o.optString("source", "Manual"),
                    mode = o.optString("mode", "Personal"),
                    receipt = if (o.isNull("receipt")) null else o.getString("receipt")
                )
            }.sortedByDescending { it.time }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveTxns() {
        val arr = JSONArray()
        txns.value.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("amount", t.amount)
                put("income", t.isIncome)
                put("merchant", t.merchant)
                put("category", t.category)
                put("note", t.note)
                put("time", t.time)
                put("lat", t.lat ?: JSONObject.NULL)
                put("lng", t.lng ?: JSONObject.NULL)
                put("place", t.place)
                put("source", t.source)
                put("mode", t.mode)
                put("receipt", t.receipt ?: JSONObject.NULL)
            })
        }
        writeSafe(txnFile(), arr.toString())
    }

    private fun loadBudgets(): Map<String, Double> = try {
        val f = budgetFile()
        if (!f.exists()) emptyMap() else {
            val o = JSONObject(f.readText())
            o.keys().asSequence().associateWith { o.getDouble(it) }
        }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun writeSafe(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    @Synchronized
    fun upsert(t: Txn) {
        txns.value = (txns.value.filter { it.id != t.id } + t).sortedByDescending { it.time }
        saveTxns()
    }

    @Synchronized
    fun delete(id: Long) {
        txns.value.firstOrNull { it.id == id }?.receipt?.let { runCatching { File(it).delete() } }
        txns.value = txns.value.filter { it.id != id }
        saveTxns()
    }

    fun get(id: Long): Txn? = txns.value.firstOrNull { it.id == id }

    fun setMode(m: String) {
        mode.value = m
        prefs().edit().putString("mode", m).apply()
    }

    fun budgetKey(mode: String, cat: String) = "$mode|$cat"

    @Synchronized
    fun setBudget(mode: String, cat: String, value: Double?) {
        val m = budgets.value.toMutableMap()
        if (value == null || value <= 0) m.remove(budgetKey(mode, cat)) else m[budgetKey(mode, cat)] = value
        budgets.value = m
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        writeSafe(budgetFile(), o.toString())
    }

    @Synchronized
    fun logRaw(line: String) {
        val stamp = SimpleDateFormat("d/M h:mm a", Locale.US).format(Date())
        val l = (listOf("[$stamp] $line") + rawLog.value).take(40)
        rawLog.value = l
        prefs().edit().putString("rawlog", l.joinToString(SEP)).apply()
    }

    fun clearLog() {
        rawLog.value = emptyList()
        prefs().edit().remove("rawlog").apply()
    }

    private val savedDrafts = mutableSetOf<Int>()
    @Synchronized fun markSaved(id: Int) { if (id > 0) savedDrafts.add(id) }
    @Synchronized fun isSaved(id: Int) = id in savedDrafts

    /** Elak pop up berganda bila app bank hantar notifikasi yang sama dua kali. */
    @Synchronized
    fun isDuplicate(key: String): Boolean {
        val now = System.currentTimeMillis()
        recent.removeAll { now - it.second > 180_000 }
        if (recent.any { it.first == key }) return true
        recent.add(key to now)
        return false
    }
}

// ---------- Utiliti ----------

val BULAN = listOf(
    "Januari", "Februari", "Mac", "April", "Mei", "Jun",
    "Julai", "Ogos", "September", "Oktober", "November", "Disember"
)

fun rm(v: Double): String = String.format(Locale.US, "RM %,.2f", v)

fun YearMonth.label(): String = "${BULAN[monthValue - 1]} $year"

fun Txn.yearMonth(): YearMonth {
    val z = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
    return YearMonth.of(z.year, z.monthValue)
}

fun Txn.dayOfMonth(): Int = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).dayOfMonth

fun fmtDateTime(t: Long): String =
    SimpleDateFormat("d MMM yyyy · h:mm a", Locale.US).format(Date(t))

fun fmtShort(t: Long): String =
    SimpleDateFormat("d MMM · h:mm a", Locale.US).format(Date(t))

object Cats {
    val expense = listOf(
        "Makan & Minum", "Barang Runcit", "Minyak & Transport", "Bil & Utiliti",
        "Belanja", "Kesihatan", "Hiburan", "Lain-lain"
    )
    val income = listOf("Gaji", "Pindahan Masuk", "Jualan", "Lain-lain")

    fun guess(merchant: String, isIncome: Boolean): String {
        val m = merchant.lowercase()
        if (isIncome) return when {
            listOf("salary", "gaji", "payroll").any { m.contains(it) } -> "Gaji"
            else -> "Pindahan Masuk"
        }
        return when {
            listOf(
                "coffee", "kopi", "cafe", "restoran", "restaurant", "mamak", "nasi", "kfc", "mcd",
                "mcdonald", "burger", "pizza", "zus", "tealive", "starbucks", "bakery", "food", "makan",
                "warung", "kedai makan", "chicken", "sushi", "mixue", "gigi coffee", "kenangan", "village park"
            ).any { m.contains(it) } -> "Makan & Minum"
            listOf(
                "grocer", "mydin", "lotus", "aeon", "giant", "speedmart", "99 ", "econsave", "mart",
                "tesco", "village grocer", "nsk", "family mart", "familymart", "7-eleven", "7 eleven", "kk super"
            ).any { m.contains(it) } -> "Barang Runcit"
            listOf(
                "petronas", "shell", "petron", "caltex", "bhp", "grab", "touch 'n go", "toll",
                "parking", "letak kereta", "lrt", "mrt", "rapid kl", "plus malaysia", "smartag"
            ).any { m.contains(it) } -> "Minyak & Transport"
            listOf(
                "tnb", "tenaga", "unifi", "maxis", "celcom", "digi", "umobile", "u mobile", "yes ", "astro",
                "syabas", "air selangor", "indah water", "time internet"
            ).any { m.contains(it) } -> "Bil & Utiliti"
            listOf("farmasi", "pharmacy", "klinik", "clinic", "hospital", "guardian", "watson", "caring")
                .any { m.contains(it) } -> "Kesihatan"
            listOf("cinema", "gsc", "tgv", "netflix", "spotify", "game", "steam", "karaoke")
                .any { m.contains(it) } -> "Hiburan"
            listOf("shopee", "lazada", "uniqlo", "mr diy", "mr. diy", "ikea", "h&m", "zara", "padini", "store", "shop")
                .any { m.contains(it) } -> "Belanja"
            else -> "Lain-lain"
        }
    }
}
