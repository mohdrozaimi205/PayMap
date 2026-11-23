package com.mohdrozaimi.paymap

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Maklumat versi terbaru di GitHub Releases. */
data class UpdateInfo(val code: Int, val name: String, val notes: String, val apkUrl: String, val pageUrl: String)

/**
 * Semak GitHub Releases (mohdrozaimi205/PayMap) untuk versi baru.
 * Tag release ialah "v1.0.<nombor binaan>" dan versionCode app = nombor binaan yang sama.
 */
object Updater {
    private const val API = "https://api.github.com/repos/mohdrozaimi205/PayMap/releases/latest"
    private const val PAGE = "https://github.com/mohdrozaimi205/PayMap/releases/latest"

    val available = MutableStateFlow<UpdateInfo?>(null)
    val lastCheck = MutableStateFlow("")
    /** Peratus muat turun (null = tidak sedang muat turun). */
    val progress = MutableStateFlow<Int?>(null)

    /** Semak paling kerap sekali setiap 3 jam (dipanggil oleh pemantau latar). */
    suspend fun checkIfDue(ctx: Context) {
        val p = Store.prefs()
        val now = System.currentTimeMillis()
        if (now - p.getLong("lastUpdateCheck", 0) < 3 * 3600_000L) return
        p.edit().putLong("lastUpdateCheck", now).apply()
        check(ctx)?.let { notifyOnce(ctx, it) }
    }

    fun installedCode(ctx: Context): Int = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        pi.longVersionCode.toInt()
    } catch (e: Exception) {
        0
    }

    fun installedName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    /** Pulangkan info jika ada versi lebih baru, null jika sudah terkini atau gagal. */
    suspend fun check(ctx: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val c = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "PayMap-Android")
            }
            if (c.responseCode != 200) {
                lastCheck.value = "Gagal semak (kod ${c.responseCode})"
                return@withContext null
            }
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            c.disconnect()
            val tag = o.optString("tag_name", "")
            val code = tag.substringAfterLast('.').toIntOrNull() ?: 0
            val assets = o.optJSONArray("assets")
            var apk = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        apk = a.optString("browser_download_url"); break
                    }
                }
            }
            val info = UpdateInfo(
                code = code,
                name = tag.removePrefix("v"),
                notes = o.optString("body", ""),
                apkUrl = apk.ifBlank { PAGE },
                pageUrl = o.optString("html_url", PAGE)
            )
            val newer = code > installedCode(ctx)
            available.value = if (newer) info else null
            lastCheck.value = if (newer) "Versi ${info.name} tersedia" else "Sudah versi terkini"
            if (newer) info else null
        } catch (e: Exception) {
            lastCheck.value = "Tiada internet / gagal semak"
            null
        }
    }

    /** Pelayar sebagai sandaran jika muat turun dalam app gagal. */
    fun openInBrowser(ctx: Context, info: UpdateInfo) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }

    /**
     * Muat turun APK terus dalam PayMap, kemudian buka skrin "Pasang".
     * Kali pertama, Android minta izin "Pasang app tidak diketahui" untuk PayMap.
     */
    suspend fun downloadAndInstall(ctx: Context, info: UpdateInfo) {
        if (progress.value != null) return
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            val i = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${ctx.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    ctx, "Benarkan PayMap pasang kemas kini, kemudian tekan Muat turun semula",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                runCatching { ctx.startActivity(i) }
            }
            return
        }
        progress.value = 0
        val file = java.io.File(ctx.cacheDir, "kemaskini/PayMap-${info.name}.apk")
        val ok = withContext(Dispatchers.IO) {
            try {
                file.parentFile?.mkdirs()
                var url = URL(info.apkUrl)
                var c = url.openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "PayMap-Android")
                // GitHub alih ke pelayan lain; ikut sehingga 5 kali
                var hops = 0
                while (c.responseCode in 300..399 && hops < 5) {
                    url = URL(c.getHeaderField("Location"))
                    c.disconnect()
                    c = url.openConnection() as HttpURLConnection
                    c.setRequestProperty("User-Agent", "PayMap-Android")
                    hops++
                }
                if (c.responseCode != 200) return@withContext false
                val total = c.contentLengthLong
                c.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) progress.value = ((done * 100) / total).toInt()
                        }
                    }
                }
                c.disconnect()
                true
            } catch (e: Exception) {
                false
            }
        }
        progress.value = null
        withContext(Dispatchers.Main) {
            if (!ok) {
                android.widget.Toast.makeText(ctx, "Muat turun gagal — cuba melalui pelayar", android.widget.Toast.LENGTH_LONG).show()
                openInBrowser(ctx, info)
                return@withContext
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(i) }
        }
    }

    /** Notifikasi sekali sahaja bagi setiap versi baru. */
    fun notifyOnce(ctx: Context, info: UpdateInfo) {
        val p = Store.prefs()
        if (p.getInt("notifiedUpdate", 0) >= info.code) return
        val i = Intent(ctx, MainActivity::class.java)
            .putExtra("tab", 0)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, 99, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        Notif.post(
            ctx, 99, Notif.CH_INFO,
            "PayMap ${info.name} tersedia",
            "Versi baru PayMap dah keluar. Tekan, kemudian tekan Kemas kini sekarang.",
            pi
        )
        p.edit().putInt("notifiedUpdate", info.code).apply()
    }
}
