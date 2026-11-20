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

    /** Muat turun APK melalui pelayar; selepas siap, tekan fail untuk pasang. */
    fun download(ctx: Context, info: UpdateInfo) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }

    /** Notifikasi sekali sahaja bagi setiap versi baru. */
    fun notifyOnce(ctx: Context, info: UpdateInfo) {
        val p = Store.prefs()
        if (p.getInt("notifiedUpdate", 0) >= info.code) return
        val i = Intent(ctx, MainActivity::class.java)
            .putExtra("tab", 3)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, 99, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        Notif.post(
            ctx, 99, Notif.CH_INFO,
            "PayMap ${info.name} tersedia",
            "Versi baru PayMap dah keluar. Tekan untuk muat turun & pasang.",
            pi
        )
        p.edit().putInt("notifiedUpdate", info.code).apply()
    }
}
