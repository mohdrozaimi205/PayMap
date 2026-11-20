package com.mohdrozaimi.paymap

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class PayMapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notif.channels(this)
        MonthlyWorker.schedule(this)
    }
}

// ---------------- Lokasi ----------------

object LocationHelper {

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val enabled = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        val order = if (Build.VERSION.SDK_INT >= 31) listOf("fused", "gps", "network") else listOf("gps", "network")
        val provider = order.firstOrNull { it in enabled }

        var fresh: Location? = null
        if (provider != null && Build.VERSION.SDK_INT >= 30) {
            fresh = withTimeoutOrNull(9000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val cs = CancellationSignal()
                    cont.invokeOnCancellation { cs.cancel() }
                    try {
                        lm.getCurrentLocation(provider, cs, ctx.mainExecutor) { loc ->
                            if (cont.isActive) cont.resume(loc)
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
        if (fresh != null) return fresh
        return enabled.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    @Suppress("DEPRECATION")
    suspend fun placeName(ctx: Context, lat: Double, lng: Double): String = withContext(Dispatchers.IO) {
        try {
            if (!Geocoder.isPresent()) return@withContext ""
            val a = Geocoder(ctx, Locale("ms", "MY")).getFromLocation(lat, lng, 1)?.firstOrNull()
                ?: return@withContext ""
            listOfNotNull(
                a.subLocality ?: a.thoroughfare,
                a.locality ?: a.subAdminArea
            ).filter { it.isNotBlank() }.distinct().joinToString(", ")
        } catch (e: Exception) {
            ""
        }
    }
}

// ---------------- Notifikasi ----------------

object Notif {
    const val CH_PAY = "pay"
    const val CH_INFO = "info"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PAY, "Bayaran dikesan", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Pop up untuk semak dan simpan bayaran"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_INFO, "Penyata & bajet", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun post(ctx: Context, id: Int, channel: String, title: String, text: String, pi: PendingIntent) {
        if (!canPost(ctx)) return
        val n = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFF0E5A48.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    fun cancel(ctx: Context, id: Int) {
        if (id > 0) ctx.getSystemService(NotificationManager::class.java).cancel(id)
    }

    fun listenerEnabled(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
}

/** Keluarkan pop up semak & simpan. */
object Popup {
    fun newId(): Int = 1000 + (System.currentTimeMillis() % 900000).toInt()

    /** Notifikasi PayMap (sandaran jika kad terapung ditutup). */
    fun notify(ctx: Context, d: Draft, id: Int) {
        val i = ConfirmActivity.intent(ctx, d, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val where = if (d.place.isNotBlank()) " di ${d.place}" else ""
        val kind = if (d.isIncome) "Duit masuk" else "Bayaran"
        Notif.post(
            ctx, id, Notif.CH_PAY,
            "${rm(d.amount ?: 0.0)} · ${d.merchant}",
            "$kind dikesan dari ${d.source}$where. Tekan untuk semak & simpan.",
            pi
        )
    }

    /** Simpan terus tanpa buka borang (butang Simpan pada kad terapung). */
    fun save(ctx: Context, d: Draft, id: Int) {
        val t = Txn(
            id = System.currentTimeMillis(),
            amount = d.amount ?: 0.0,
            isIncome = d.isIncome,
            merchant = d.merchant,
            category = Cats.guess(d.merchant, d.isIncome),
            note = "",
            time = d.time,
            lat = d.lat,
            lng = d.lng,
            place = d.place,
            source = d.source,
            mode = Store.mode.value,
            receipt = null
        )
        Store.upsert(t)
        BudgetAlert.check(ctx, t)
        Notif.cancel(ctx, id)
    }

    /** Papar kad terapung serta-merta, kemudian isi lokasi bila GPS siap. */
    suspend fun fromParsed(ctx: Context, p: Parsed) {
        val id = newId()
        var d = Draft(
            amount = p.amount,
            isIncome = p.isIncome,
            merchant = p.merchant,
            source = p.source,
            lat = null,
            lng = null,
            place = "",
            time = System.currentTimeMillis()
        )
        withContext(Dispatchers.Main) {
            notify(ctx, d, id)
            Overlay.show(ctx, d, id)
        }
        val loc = LocationHelper.current(ctx) ?: return
        val place = LocationHelper.placeName(ctx, loc.latitude, loc.longitude)
        d = d.copy(lat = loc.latitude, lng = loc.longitude, place = place)
        withContext(Dispatchers.Main) {
            notify(ctx, d, id)
            Overlay.update(d)
        }
    }
}

// ---------------- Pendengar notifikasi MAE / TnG ----------------

class PayNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        val n = sbn.notification ?: return
        if (!PayParser.apps.containsKey(pkg)) {
            logOther(pkg, n)
            return
        }
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return

        Store.init(applicationContext)
        val p = PayParser.parse(pkg, title, text)
        val result = if (p == null) "✗ bukan bayaran" else "✓ dikesan ${rm(p.amount)} · ${p.merchant}"
        Store.logRaw("${PayParser.apps[pkg]} | $title | $text\n   → $result")
        if (p == null) return

        val key = "${p.source}|${p.amount}|${p.merchant}"
        if (Store.isDuplicate(key)) return

        scope.launch { Popup.fromParsed(applicationContext, p) }
    }

    /** Rekod notifikasi berjumlah RM dari app lain (cth app bank / SMS) untuk bantu tambah sokongan. */
    private fun logOther(pkg: String, n: Notification) {
        if (pkg == packageName) return
        val chat = listOf("whatsapp", "telegram", "messenger", "instagram", "facebook", "line", "wechat", "discord", "tiktok", "shopee", "lazada")
        if (chat.any { pkg.contains(it) }) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        if (!PayParser.hasAmount("$title $text")) return
        Store.init(applicationContext)
        Store.logRaw("(app belum dipantau: $pkg) | $title | $text")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

// ---------------- Penyata automatik 1hb ----------------

class MonthlyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Store.init(ctx)
        val today = LocalDate.now()
        if (today.dayOfMonth == 1) {
            val last = YearMonth.from(today).minusMonths(1)
            val key = last.toString()
            val p = Store.prefs()
            if (p.getString("lastStatement", "") != key) {
                val all = Store.txns.value.filter { it.yearMonth() == last }
                val spent = all.filter { !it.isIncome }.sumOf { it.amount }
                val inc = all.filter { it.isIncome }.sumOf { it.amount }
                val i = Intent(ctx, MainActivity::class.java)
                    .putExtra("tab", 2)
                    .putExtra("month", key)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val pi = PendingIntent.getActivity(
                    ctx, 77, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                Notif.post(
                    ctx, 77, Notif.CH_INFO,
                    "Penyata ${last.label()} dah siap",
                    "Belanja ${rm(spent)} · Masuk ${rm(inc)} · ${all.size} transaksi. Tekan untuk lihat, simpan atau kongsi.",
                    pi
                )
                p.edit().putString("lastStatement", key).apply()
            }
        }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<MonthlyWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("monthly-statement", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

// ---------------- Amaran bajet ----------------

object BudgetAlert {
    fun check(ctx: Context, t: Txn) {
        if (t.isIncome) return
        val limit = Store.budgets.value[Store.budgetKey(t.mode, t.category)] ?: return
        val ym = t.yearMonth()
        val spent = Store.txns.value
            .filter { !it.isIncome && it.mode == t.mode && it.category == t.category && it.yearMonth() == ym }
            .sumOf { it.amount }
        val pct = spent / limit
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, 88, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        when {
            pct >= 1.0 -> Notif.post(
                ctx, 88, Notif.CH_INFO, "Bajet ${t.category} terlebih!",
                "${rm(spent)} daripada ${rm(limit)} (${t.mode}) bulan ini.", pi
            )
            pct >= 0.8 -> Notif.post(
                ctx, 88, Notif.CH_INFO, "Bajet ${t.category} hampir habis",
                "Dah guna ${(pct * 100).toInt()}% — ${rm(spent)} daripada ${rm(limit)}.", pi
            )
        }
    }
}
