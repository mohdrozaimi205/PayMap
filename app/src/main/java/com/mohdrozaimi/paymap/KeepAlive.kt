package com.mohdrozaimi.paymap

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.notification.NotificationListenerService

/**
 * Pemantau latar: perkhidmatan "foreground" dengan notifikasi senyap supaya
 * Samsung / Honor tidak matikan PayMap. Tanpa ini, pengesan notifikasi mati
 * di belakang dan bayaran (paywave / QR) terlepas sehingga app dibuka semula.
 */
class KeepAliveService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            rebindListener(this@KeepAliveService)
            main.postDelayed(this, 15 * 60_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        Notif.channels(this)
        val pi = PendingIntent.getActivity(
            this, 5, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, Notif.CH_WATCH)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFF0E5A48.toInt())
            .setContentTitle("PayMap sedang memantau bayaran")
            .setContentText("MAE · TnG · Google Wallet — pop up keluar selepas bayar")
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pi)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ID, n)
            }
            if (!running) Store.logRaw("ℹ pemantau latar aktif")
            running = true
        } catch (e: Exception) {
            Store.logRaw("⚠ pemantau latar gagal: ${e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        main.removeCallbacks(tick)
        main.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(tick)
        super.onDestroy()
    }

    companion object {
        const val ID = 7
        @Volatile var running = false

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java))
            } catch (e: Exception) {
                runCatching { Store.init(ctx); Store.logRaw("⚠ tak dapat mula pemantau: ${e.javaClass.simpleName}") }
            }
        }

        /** Minta Android sambung semula pengesan notifikasi jika terputus. */
        fun rebindListener(ctx: Context) {
            runCatching {
                NotificationListenerService.requestRebind(ComponentName(ctx, PayNotificationListener::class.java))
            }
        }
    }
}

/** Hidupkan pemantau selepas telefon dihidupkan semula atau app dikemas kini. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        KeepAliveService.start(context.applicationContext)
        KeepAliveService.rebindListener(context.applicationContext)
    }
}
