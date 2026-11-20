package com.mohdrozaimi.paymap

/** Hasil bacaan satu notifikasi bayaran. */
data class Parsed(
    val amount: Double,
    val isIncome: Boolean,
    val merchant: String,
    val source: String
)

/**
 * Baca notifikasi dari MAE dan TnG eWallet, ambil jumlah RM dan nama kedai.
 * Format notifikasi berubah ikut versi app, jadi corak di sini sengaja longgar.
 * Notifikasi mentah disimpan dalam "Log notifikasi" (Tetapan) untuk dibaiki kemudian.
 */
object PayParser {

    val apps = mapOf(
        "com.maybank2u.life" to "MAE",
        "com.maybank2u.m2umobile" to "Maybank2u",
        "my.com.tngdigital.ewallet" to "TnG eWallet",
        "com.google.android.apps.walletnfcrel" to "Google Wallet",
        "com.samsung.android.spay" to "Samsung Wallet",
        "com.samsung.android.samsungpay.gear" to "Samsung Wallet"
    )

    private val wallets = setOf("Google Wallet", "Samsung Wallet")

    private val amountRe = Regex(
        """(?:RM|MYR)\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE
    )

    private const val STOP =
        """(?=\s+(?:on|pada|for|untuk|via|using|melalui|is|was|has|have|had|telah|with|dengan|ref|reference|successfully|berjaya)\b|[.!|\n]|\s+-\s|\s*\(|\s*$)"""

    private val toRe = Regex(
        """(?:\bto|\bkepada|\bat|\bdi|@)\s+(.{2,60}?)$STOP""",
        RegexOption.IGNORE_CASE
    )
    private val fromRe = Regex(
        """(?:\bfrom|\bdaripada|\bdari)\s+(.{2,60}?)$STOP""",
        RegexOption.IGNORE_CASE
    )

    private val txnWords = listOf(
        "payment", "paid", "pay ", "bayar", "pembayaran", "transfer", "pindahan", "received",
        "receive", "terima", "successful", "berjaya", "debited", "credited", "spent", "purchase",
        "transaction", "transaksi", "duitnow", "scan", "qr", "tap"
    )
    private val failWords = listOf(
        "fail", "gagal", "unsuccessful", "tidak berjaya", "declined", "ditolak", "insufficient", "tidak mencukupi"
    )
    private val promoWords = listOf(
        "cashback", "promo", "voucher", "stand a chance", "offer", "tawaran", "limited time",
        "reload bonus", "win ", "menang", "free ", "percuma", "deal", "jimat"
    )
    private val sureWords = listOf(
        "successful", "berjaya", "you have paid", "you've paid", "anda telah", "you have received",
        "you've received", "telah diterima", "debited", "credited"
    )
    private val incomeWords = listOf(
        "received", "receive", "terima", "diterima", "credited", "dikreditkan", "masuk",
        "refund", "incoming", "cash in", "you've got", "you got"
    )
    private val paidWords = listOf(
        "paid", "payment to", "pay to", "bayar", "pembayaran kepada", "debited", "spent",
        "purchase", "transfer to", "pindahan kepada", "payment of", "dibayar"
    )
    private val badMerchants = setOf(
        "you", "anda", "your", "akaun", "account", "your account", "akaun anda", "the", "mae", "tng"
    )

    fun hasAmount(s: String): Boolean = amountRe.containsMatchIn(s)

    fun parse(pkg: String, title: String, text: String): Parsed? {
        val source = apps[pkg] ?: return null
        return parseText(source, title, text)
    }

    fun parseText(source: String, title: String, text: String): Parsed? {
        val full = "$title\n$text"
        val low = full.lowercase()

        val m = amountRe.find(full) ?: return null
        val amount = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        if (amount <= 0.0) return null

        if (failWords.any { low.contains(it) }) return null

        // Google / Samsung Wallet: notifikasi tap biasanya "Nama Kedai" + "RM12.50 dengan Visa ••1234".
        if (source in wallets) {
            val skip = listOf("added", "ditambah", "ready", "sedia", "verify", "sahkan", "expire", "tamat", "promo", "offer")
            if (skip.any { low.contains(it) } && sureWords.none { low.contains(it) }) return null
            val refund = listOf("refund", "pulangan", "credited", "dikreditkan").any { low.contains(it) }
            val name = title.trim().takeIf {
                it.isNotBlank() && !it.contains("wallet", ignoreCase = true) &&
                    !it.contains("google pay", ignoreCase = true) && !it.contains("samsung pay", ignoreCase = true) &&
                    amountRe.find(it) == null
            }?.let { pretty(it.take(40)) }
                ?: extractMerchant(text, refund)
                ?: "Bayaran $source"
            return Parsed(amount, refund, name, source)
        }

        if (txnWords.none { low.contains(it) }) return null
        val sure = sureWords.any { low.contains(it) }
        if (!sure && promoWords.any { low.contains(it) }) return null

        val income = incomeWords.any { low.contains(it) } && paidWords.none { low.contains(it) }

        val merchant = extractMerchant(text, income)
            ?: extractMerchant(full, income)
            ?: titleAsMerchant(title)
            ?: "Bayaran $source"

        return Parsed(amount, income, merchant, source)
    }

    private fun extractMerchant(s: String, income: Boolean): String? {
        val re = if (income) fromRe else toRe
        for (r in re.findAll(s)) {
            val c = clean(r.groupValues[1]) ?: continue
            return c
        }
        // cuba arah sebaliknya sebagai sandaran
        val alt = if (income) toRe else fromRe
        for (r in alt.findAll(s)) {
            val c = clean(r.groupValues[1]) ?: continue
            return c
        }
        return null
    }

    private fun clean(raw: String): String? {
        val c = raw.trim().trimEnd(',', ';', ':', '-').trim()
        if (c.length < 2) return null
        if (c.first().isDigit()) return null
        if (c.startsWith("RM", ignoreCase = true) || c.startsWith("MYR", ignoreCase = true)) return null
        if (c.lowercase() in badMerchants) return null
        return pretty(c.take(40))
    }

    private fun titleAsMerchant(title: String): String? {
        val t = title.trim()
        if (t.isBlank()) return null
        val l = t.lowercase()
        val generic = listOf(
            "payment", "successful", "berjaya", "transaction", "transaksi", "mae", "tng", "touch",
            "maybank", "notification", "pemberitahuan", "ewallet", "e-wallet", "received", "duitnow"
        )
        if (generic.any { l.contains(it) }) return null
        return pretty(t.take(40))
    }

    /** "ZUS COFFEE SURIA KLCC" -> "Zus Coffee Suria Klcc" supaya lebih kemas. */
    private fun pretty(s: String): String {
        val letters = s.filter { it.isLetter() }
        if (letters.isEmpty() || letters.any { it.isLowerCase() }) return s
        return s.lowercase().split(" ").joinToString(" ") { w ->
            if (w in setOf("klcc", "kl", "pj", "sdn", "bhd", "mbs", "kk", "tng", "mae", "ioi", "aeon", "kfc", "mcd"))
                w.uppercase()
            else w.replaceFirstChar { it.uppercase() }
        }
    }
}
