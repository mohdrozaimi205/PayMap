package com.mohdrozaimi.paymap

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Kad terapung yang muncul atas app lain sejurus selepas bayaran dikesan.
 * Android 14/15 tidak benarkan app buka skrin dari belakang, tetapi
 * tetingkap "papar atas app lain" dibenarkan — jadi pop up dilukis terus di sini.
 */
object Overlay {
    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var draft: Draft? = null
    private var notifId = 0
    private var placeView: TextView? = null
    private var headView: TextView? = null
    private var merchantView: TextView? = null
    private val autoHide = Runnable { root?.context?.let { hide(it) } }

    private const val INK = 0xFF000000.toInt()
    private const val SUB = 0xFF8E8E93.toInt()
    private const val CARD = 0xFFFFFFFF.toInt()
    private const val LINE = 0xFFE5E5EA.toInt()
    private const val GREEN = 0xFF007AFF.toInt()   // butang utama: biru iOS
    private const val CORAL = 0xFFFF3B30.toInt()

    fun canShow(ctx: Context) = Settings.canDrawOverlays(ctx)

    /** Pulang keterangan hasil (untuk log debug). */
    fun show(ctx: Context, d: Draft, id: Int): String {
        val app = ctx.applicationContext
        if (!canShow(app)) return "✗ GAGAL — izin 'Papar atas app lain' tutup"
        hide(app)
        draft = d
        notifId = id

        val dp = app.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(app).apply {
            this.text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        fun button(label: String, filled: Boolean, onClick: () -> Unit) = TextView(app).apply {
            this.text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (filled) 0xFFFFFFFF.toInt() else INK)
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                if (filled) setColor(GREEN) else { setColor(CARD); setStroke(px(1), LINE) }
            }
            setOnClickListener { onClick() }
        }

        val card = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(14), px(18), px(16))
            background = GradientDrawable().apply {
                setColor(CARD); cornerRadius = 26 * dp; setStroke(px(1), LINE)
            }
            elevation = 14 * dp
        }

        // Baris atas: label + tutup
        val top = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val kind = if (d.isIncome) "Duit masuk" else "Bayaran"
        val hv = text(headLabel(d), 13f, sourceColor(d.source), true)
        headView = hv
        top.addView(hv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(text("✕", 18f, SUB).apply {
            setPadding(px(10), px(2), px(2), px(2))
            setOnClickListener { hide(app) }
        })
        card.addView(top)

        card.addView(text(rm(d.amount ?: 0.0), 30f, INK, true).apply { setPadding(0, px(6), 0, 0) })
        val mv = text(d.merchant, 16f, INK, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        merchantView = mv
        card.addView(mv)
        val pv = text(placeLabel(d), 13f, SUB).apply { setPadding(0, px(2), 0, px(12)) }
        placeView = pv
        card.addView(pv)

        val row = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
        val bp = LinearLayout.LayoutParams(0, px(50), 1f)
        row.addView(button("Edit", false) { openEditor(app) }, bp)
        row.addView(View(app), LinearLayout.LayoutParams(px(10), 1))
        row.addView(button("Simpan", true) { quickSave(app) }, LinearLayout.LayoutParams(0, px(50), 1.4f))
        card.addView(row)

        val frame = FrameLayout(app).apply {
            setPadding(px(12), px(8), px(12), px(8))
            addView(card)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Di bawah skrin supaya tidak ditutup oleh notifikasi MAE / TnG yang turun dari atas
            gravity = Gravity.BOTTOM
            y = px(72)
            windowAnimations = android.R.style.Animation_Toast
        }

        try {
            app.getSystemService(WindowManager::class.java).addView(frame, lp)
            root = frame
            main.removeCallbacks(autoHide)
            main.postDelayed(autoHide, 120_000)
            return "✓ dipaparkan"
        } catch (e: Exception) {
            root = null
            return "✗ GAGAL — ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Kemas kini lokasi bila GPS siap. */
    fun update(d: Draft) {
        if (root == null) return
        draft = d
        placeView?.text = placeLabel(d)
        headView?.text = headLabel(d)
        headView?.setTextColor(sourceColor(d.source))
        merchantView?.text = d.merchant
    }

    private fun headLabel(d: Draft): String {
        val kind = if (d.isIncome) "Duit masuk" else "Bayaran"
        val how = when (d.source) {
            "Google Wallet", "Samsung Wallet" -> "paywave · ${d.source}"
            else -> d.source
        }
        return "● $kind dikesan · $how"
    }

    /** Warna label ikut sumber: Google Wallet biru, MAE kuning, TnG biru gelap. */
    private fun sourceColor(src: String): Int = when (src) {
        "Google Wallet" -> 0xFF1A73E8.toInt()
        "Samsung Wallet" -> 0xFF1428A0.toInt()
        "MAE", "Maybank2u" -> 0xFFE0A800.toInt()
        "TnG eWallet" -> 0xFF005ABB.toInt()
        else -> CORAL
    }

    fun hide(ctx: Context) {
        main.removeCallbacks(autoHide)
        val v = root ?: return
        root = null
        placeView = null
        headView = null
        merchantView = null
        runCatching { ctx.applicationContext.getSystemService(WindowManager::class.java).removeView(v) }
    }

    private fun placeLabel(d: Draft) = when {
        d.place.isNotBlank() -> "📍 ${d.place}"
        d.lat != null -> "📍 Lokasi direkod"
        else -> "📍 Mencari lokasi…"
    }

    private fun openEditor(ctx: Context) {
        val d = draft ?: return
        val i = ConfirmActivity.intent(ctx, d, notifId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
        hide(ctx)
    }

    private fun quickSave(ctx: Context) {
        val d = draft ?: return
        Popup.save(ctx, d, notifId)
        Toast.makeText(ctx, "Disimpan ✓ ${rm(d.amount ?: 0.0)} · ${d.merchant}", Toast.LENGTH_SHORT).show()
        hide(ctx)
    }
}
