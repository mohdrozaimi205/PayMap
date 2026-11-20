package com.mohdrozaimi.paymap

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.YearMonth

class MainActivity : ComponentActivity() {

    private val tab = mutableIntStateOf(0)
    private val statementMonth = mutableStateOf(YearMonth.now())
    private val resumeTick = mutableIntStateOf(0)

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        resumeTick.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        handle(intent)
        if (!Store.prefs().getBoolean("askedOnce", false)) {
            Store.prefs().edit().putBoolean("askedOnce", true).apply()
            val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            askPerms.launch(perms.toTypedArray())
        }
        setContent { PayMapTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    private fun handle(i: Intent?) {
        if (i == null) return
        val t = i.getIntExtra("tab", -1)
        if (t >= 0) tab.intValue = t
        i.getStringExtra("month")?.let { m -> runCatching { statementMonth.value = YearMonth.parse(m) } }
    }

    fun requestLocation() {
        askPerms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    fun requestBackgroundLocation() {
        if (!LocationHelper.hasPermission(this)) {
            requestLocation(); return
        }
        askPerms.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) askPerms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }

    @Composable
    private fun Root() {
        val current = tab.intValue
        Scaffold(
            containerColor = C.Bg,
            bottomBar = {
                NavigationBar(containerColor = C.Card, tonalElevation = 0.dp) {
                    val items = listOf(
                        Triple("Utama", Icons.Filled.Home, 0),
                        Triple("Peta", Icons.Filled.LocationOn, 1),
                        Triple("Penyata", Icons.Filled.DateRange, 2),
                        Triple("Tetapan", Icons.Filled.Settings, 3)
                    )
                    items.forEach { (label, icon, idx) ->
                        NavigationBarItem(
                            selected = current == idx,
                            onClick = { tab.intValue = idx },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = C.Emerald,
                                selectedTextColor = C.Emerald,
                                indicatorColor = C.Mint,
                                unselectedIconColor = C.Sub,
                                unselectedTextColor = C.Sub
                            )
                        )
                    }
                }
            },
            floatingActionButton = {
                if (current == 0) {
                    FloatingActionButton(
                        onClick = { startActivity(ConfirmActivity.manual(this)) },
                        containerColor = C.Coral,
                        contentColor = Color.White,
                        shape = RoundedCornerShape(20.dp)
                    ) { Icon(Icons.Filled.Add, contentDescription = "Tambah") }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (current) {
                    0 -> HomeScreen()
                    1 -> MapScreen()
                    2 -> StatementScreen()
                    else -> SettingsScreen()
                }
            }
        }
    }

    // ======================= UTAMA =======================

    @Composable
    private fun HomeScreen() {
        val all by Store.txns.collectAsState()
        val mode by Store.mode.collectAsState()
        val budgets by Store.budgets.collectAsState()
        @Suppress("UNUSED_VARIABLE") val tick = resumeTick.intValue
        val listenerOn = Notif.listenerEnabled(this)

        val ym = YearMonth.now()
        val mine = all.filter { it.mode == mode }
        val month = mine.filter { it.yearMonth() == ym }
        val spent = month.filter { !it.isIncome }.sumOf { it.amount }
        val income = month.filter { it.isIncome }.sumOf { it.amount }

        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("PayMap", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = C.Emerald)
                        Text(greeting(), fontSize = 13.sp, color = C.Sub)
                    }
                    ModeSwitch(mode) { Store.setMode(it) }
                }
            }
            if (!listenerOn) {
                item {
                    WarnCard(
                        "Hidupkan pengesan bayaran",
                        "Benarkan PayMap baca notifikasi MAE & TnG supaya pop up keluar selepas bayar."
                    ) { tab.intValue = 3 }
                }
            }
            item { HeroCard(ym, spent, income, month.size) }
            item { BudgetCard(mode, month, budgets) }
            item {
                Text("Terkini", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = C.Ink, modifier = Modifier.padding(top = 4.dp))
            }
            if (mine.isEmpty()) {
                item {
                    CardBox {
                        Column {
                            Text("Belum ada transaksi", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Buat bayaran guna MAE atau TnG — PayMap akan tanya untuk simpan. Atau tekan + untuk tambah sendiri.",
                                fontSize = 13.sp, color = C.Sub
                            )
                        }
                    }
                }
            } else {
                items(mine.take(40), key = { it.id }) { t ->
                    TxnRow(t) { startActivity(ConfirmActivity.edit(this@MainActivity, t.id)) }
                }
            }
        }
    }

    private fun greeting(): String {
        val h = LocalTime.now().hour
        return when {
            h < 12 -> "Selamat pagi"
            h < 15 -> "Selamat tengah hari"
            h < 19 -> "Selamat petang"
            else -> "Selamat malam"
        }
    }

    @Composable
    private fun WarnCard(title: String, body: String, onClick: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(C.CoralSoft)
                .clickable { onClick() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(C.Coral))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = C.Ink)
                Text(body, fontSize = 12.sp, color = C.Sub)
            }
            Text("›", fontSize = 24.sp, color = C.Coral)
        }
    }

    @Composable
    private fun HeroCard(ym: YearMonth, spent: Double, income: Double, count: Int) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(30.dp))
                .background(Brush.linearGradient(listOf(C.Emerald, C.EmeraldLight)))
                .padding(22.dp)
        ) {
            // corak garis kontur peta
            Canvas(Modifier.matchParentSize()) {
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
                for (i in 1..5) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.07f),
                        radius = size.minDimension * 0.18f * i,
                        center = Offset(size.width * 0.95f, size.height * 0.05f),
                        style = stroke
                    )
                }
            }
            Column {
                Text("Belanja ${ym.label()}", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text(rm(spent), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(16.dp))
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("Masuk", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        Text(rm(income), color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Baki", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        Text(rm(income - spent), color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Transaksi", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        Text("$count", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    @Composable
    private fun BudgetCard(mode: String, month: List<Txn>, budgets: Map<String, Double>) {
        val mine = budgets.filterKeys { it.startsWith("$mode|") }
        CardBox {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bajet bulan ini", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("Ubah", color = C.Emerald, fontSize = 13.sp, modifier = Modifier.clickable { tab.intValue = 3 })
                }
                Spacer(Modifier.height(8.dp))
                if (mine.isEmpty()) {
                    Text("Belum ada bajet. Tetapkan had belanja ikut kategori di Tetapan.", fontSize = 13.sp, color = C.Sub)
                } else {
                    mine.forEach { (key, limit) ->
                        val cat = key.substringAfter("|")
                        val used = month.filter { !it.isIncome && it.category == cat }.sumOf { it.amount }
                        val f = (used / limit).toFloat()
                        val col = when {
                            f >= 1f -> C.Coral
                            f >= 0.8f -> C.Gold
                            else -> C.EmeraldLight
                        }
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Row {
                                Text(cat, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                Text("${rm(used)} / ${rm(limit)}", fontSize = 12.sp, color = if (f >= 1f) C.Coral else C.Sub)
                            }
                            Spacer(Modifier.height(5.dp))
                            Bar(f, col)
                        }
                    }
                }
            }
        }
    }

    // ======================= PETA =======================

    @Composable
    private fun MapScreen() {
        val all by Store.txns.collectAsState()
        val mode by Store.mode.collectAsState()
        var ym by remember { mutableStateOf(YearMonth.now()) }
        val exp = all.filter { it.mode == mode && !it.isIncome && it.yearMonth() == ym }
        val groups = exp.groupBy { it.merchant.ifBlank { it.place } }
            .map { (name, list) ->
                val withLoc = list.firstOrNull { it.lat != null && it.lng != null }
                Triple(name, list, withLoc)
            }
            .sortedByDescending { it.second.sumOf { t -> t.amount } }
        val pins = groups.mapNotNull { (name, list, loc) ->
            val la = loc?.lat
            val ln = loc?.lng
            if (la == null || ln == null) null
            else MapPin(la, ln, shortAmount(list.sumOf { it.amount }), name)
        }

        Box(Modifier.fillMaxSize()) {
            OsmMap(pins, Modifier.fillMaxSize())

            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(C.Card)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { ym = ym.minusMonths(1) }) { Text("‹", fontSize = 20.sp, color = C.Ink) }
                Text(ym.label(), fontWeight = FontWeight.SemiBold, color = C.Ink)
                TextButton(onClick = { ym = ym.plusMonths(1) }) { Text("›", fontSize = 20.sp, color = C.Ink) }
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(14.dp)
                    .fillMaxWidth()
                    .heightIn(max = 330.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(C.Card)
                    .padding(18.dp)
            ) {
                Text("Ke mana duit pergi", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = C.Ink)
                Text(
                    "${groups.size} tempat · ${rm(exp.sumOf { it.amount })}",
                    fontSize = 13.sp, color = C.Sub
                )
                Spacer(Modifier.height(8.dp))
                if (groups.isEmpty()) {
                    Text("Tiada belanja untuk bulan ini.", fontSize = 13.sp, color = C.Sub)
                }
                LazyColumn {
                    itemsIndexed(groups) { i, (name, list, _) ->
                        Row(Modifier.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(if (i == 0) C.Coral else C.Line),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("${i + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (i == 0) Color.White else C.Ink)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${list.size} kali", fontSize = 12.sp, color = C.Sub)
                            }
                            Text(rm(list.sumOf { it.amount }), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // ======================= PENYATA =======================

    @Composable
    private fun StatementScreen() {
        val all by Store.txns.collectAsState()
        val mode by Store.mode.collectAsState()
        val ym = statementMonth.value
        val s = remember(all, mode, ym) { Statement.summary(ym, mode) }
        val scope = rememberCoroutineScope()
        val ctx = LocalContext.current

        fun withPdf(action: (java.io.File) -> Unit) {
            scope.launch {
                val f = withContext(Dispatchers.IO) { Statement.buildPdf(ctx, ym, mode) }
                action(f)
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Penyata", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = C.Ink)
                        Text("Akaun $mode", fontSize = 13.sp, color = C.Sub)
                    }
                    ModeSwitch(mode) { Store.setMode(it) }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Card).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { statementMonth.value = ym.minusMonths(1) }) { Text("‹ Sebelum", color = C.Emerald) }
                    Text(ym.label(), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), color = C.Ink,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    TextButton(onClick = { statementMonth.value = ym.plusMonths(1) }) { Text("Seterus ›", color = C.Emerald) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatBox("Belanja", rm(s.spent), C.Coral, Modifier.weight(1f))
                    StatBox("Masuk", rm(s.income), C.EmeraldLight, Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { withPdf { Statement.view(ctx, it) } },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = C.Emerald)
                    ) { Text("Lihat") }
                    Button(
                        onClick = {
                            withPdf {
                                val ok = Statement.saveToDownloads(ctx, it)
                                Toast.makeText(
                                    ctx,
                                    if (ok) "Disimpan ke Download/PayMap" else "Gagal simpan",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = C.Ink)
                    ) { Text("Simpan") }
                    Button(
                        onClick = {
                            withPdf {
                                Statement.share(
                                    ctx, it,
                                    "Penyata PayMap ${ym.label()} ($mode): belanja ${rm(s.spent)}, masuk ${rm(s.income)}."
                                )
                            }
                        },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = C.Coral)
                    ) { Text("Kongsi") }
                }
            }
            item {
                CardBox {
                    Column {
                        Text("Belanja harian", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        DailyChart(ym, s.list)
                    }
                }
            }
            item {
                CardBox {
                    Column {
                        Text("Ikut kategori", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        if (s.byCat.isEmpty()) Text("Tiada belanja.", fontSize = 13.sp, color = C.Sub)
                        val max = s.byCat.maxOfOrNull { it.second } ?: 1.0
                        s.byCat.forEach { (cat, v) ->
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(catColor(cat)))
                                    Spacer(Modifier.width(8.dp))
                                    Text(cat, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    val pct = if (s.spent > 0) (v / s.spent * 100).toInt() else 0
                                    Text("${rm(v)} · $pct%", fontSize = 12.sp, color = C.Sub)
                                }
                                Spacer(Modifier.height(5.dp))
                                Bar((v / max).toFloat(), catColor(cat))
                            }
                        }
                    }
                }
            }
            item { Text("Transaksi (${s.list.size})", fontWeight = FontWeight.Bold, fontSize = 17.sp) }
            items(s.list.reversed(), key = { it.id }) { t ->
                TxnRow(t) { startActivity(ConfirmActivity.edit(this@MainActivity, t.id)) }
            }
        }
    }

    @Composable
    private fun StatBox(title: String, value: String, color: Color, modifier: Modifier) {
        Column(
            modifier.clip(RoundedCornerShape(22.dp)).background(C.Card).padding(16.dp)
        ) {
            Text(title, fontSize = 12.sp, color = C.Sub)
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = color)
        }
    }

    @Composable
    private fun DailyChart(ym: YearMonth, list: List<Txn>) {
        val days = ym.lengthOfMonth()
        val perDay = DoubleArray(days)
        list.filter { !it.isIncome }.forEach { perDay[it.dayOfMonth() - 1] += it.amount }
        val max = perDay.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val gap = 3f
            val bw = (size.width - gap * (days - 1)) / days
            for (d in 0 until days) {
                val h = (perDay[d] / max).toFloat() * size.height
                val x = d * (bw + gap)
                drawRoundRect(
                    color = C.Line,
                    topLeft = Offset(x, 0f),
                    size = Size(bw, size.height),
                    cornerRadius = CornerRadius(bw / 2, bw / 2)
                )
                if (h > 0f) {
                    drawRoundRect(
                        color = if (perDay[d] == max) C.Coral else C.EmeraldLight,
                        topLeft = Offset(x, size.height - h),
                        size = Size(bw, h),
                        cornerRadius = CornerRadius(bw / 2, bw / 2)
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("1", fontSize = 11.sp, color = C.Sub, modifier = Modifier.weight(1f))
            Text("15", fontSize = 11.sp, color = C.Sub, modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("$days", fontSize = 11.sp, color = C.Sub, modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }

    // ======================= TETAPAN =======================

    @Composable
    private fun SettingsScreen() {
        @Suppress("UNUSED_VARIABLE") val tick = resumeTick.intValue
        val mode by Store.mode.collectAsState()
        val budgets by Store.budgets.collectAsState()
        val log by Store.rawLog.collectAsState()
        val scope = rememberCoroutineScope()
        val ctx = LocalContext.current
        var showLog by remember { mutableStateOf(false) }

        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Text("Tetapan", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = C.Ink) }

            item {
                CardBox {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Kebenaran", fontWeight = FontWeight.Bold)
                        Text("Hidupkan semua supaya pop up keluar automatik selepas bayar.", fontSize = 12.sp, color = C.Sub)
                        Spacer(Modifier.height(6.dp))
                        PermRow("1. Baca notifikasi MAE & TnG", "Wajib — untuk kesan bayaran", Notif.listenerEnabled(ctx)) {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                        PermRow("2. Lokasi", "Untuk tanda tempat bayaran", LocationHelper.hasPermission(ctx)) { requestLocation() }
                        PermRow("3. Lokasi sepanjang masa", "Pilih \"Benarkan sepanjang masa\"", LocationHelper.hasBackground(ctx)) {
                            requestBackgroundLocation()
                        }
                        PermRow("4. Pemberitahuan", "Untuk pop up, bajet & penyata 1hb", Notif.canPost(ctx)) {
                            requestNotifications()
                            if (Build.VERSION.SDK_INT < 33) openAppSettings()
                        }
                        PermRow("5. Papar atas app lain", "Pilihan — pop up terus keluar tanpa tekan notifikasi", Settings.canDrawOverlays(ctx)) {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Jika nombor 1 tak boleh dihidupkan (\"Tetapan terhad\"): tekan butang di bawah → ⋮ (atas kanan) → \"Benarkan tetapan terhad\", kemudian cuba semula.",
                            fontSize = 12.sp, color = C.Sub
                        )
                        TextButton(onClick = { openAppSettings() }) { Text("Buka Info App PayMap", color = C.Emerald) }
                    }
                }
            }

            item {
                CardBox {
                    Column {
                        Text("Uji pop up", fontWeight = FontWeight.Bold)
                        Text("Cuba tanpa buat bayaran sebenar.", fontSize = 12.sp, color = C.Sub)
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                val p = PayParser.parseText(
                                    "MAE", "Payment Successful",
                                    "You've made a DuitNow QR payment of RM12.50 to ZUS COFFEE SURIA KLCC."
                                )
                                if (p != null) scope.launch(Dispatchers.IO) { Popup.fromParsed(ctx.applicationContext, p) }
                                Toast.makeText(ctx, "Pop up uji dihantar…", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = C.Coral)
                        ) { Text("Hantar bayaran uji RM12.50") }
                    }
                }
            }

            item {
                CardBox {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Bajet bulanan", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            ModeSwitch(mode) { Store.setMode(it) }
                        }
                        Text("Kosongkan untuk tiada had. Amaran keluar bila guna 80% dan bila terlebih.", fontSize = 12.sp, color = C.Sub)
                        Spacer(Modifier.height(8.dp))
                        Cats.expense.forEach { cat ->
                            BudgetField(mode, cat, budgets[Store.budgetKey(mode, cat)])
                        }
                    }
                }
            }

            item {
                CardBox {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Log notifikasi", fontWeight = FontWeight.Bold)
                                Text("Notifikasi MAE/TnG yang dibaca. Screenshot ini jika ada yang tak dikesan.", fontSize = 12.sp, color = C.Sub)
                            }
                            TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Tutup" else "Buka", color = C.Emerald) }
                        }
                        if (showLog) {
                            if (log.isEmpty()) Text("Kosong.", fontSize = 12.sp, color = C.Sub)
                            log.forEach { line ->
                                Text(line, fontSize = 11.sp, color = C.Ink, modifier = Modifier.padding(vertical = 4.dp))
                            }
                            if (log.isNotEmpty()) TextButton(onClick = { Store.clearLog() }) { Text("Kosongkan log", color = C.Coral) }
                        }
                    }
                }
            }

            item {
                Text(
                    "PayMap 1.0 · semua data disimpan dalam telefon ini sahaja. Peta © OpenStreetMap.",
                    fontSize = 11.sp, color = C.Sub, modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    @Composable
    private fun PermRow(title: String, desc: String, ok: Boolean, onClick: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(enabled = !ok) { onClick() }
                .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(desc, fontSize = 12.sp, color = C.Sub)
            }
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (ok) C.Mint else C.CoralSoft)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    if (ok) "✓ Aktif" else "Hidupkan",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (ok) C.Emerald else C.Coral
                )
            }
        }
    }

    @Composable
    private fun BudgetField(mode: String, cat: String, current: Double?) {
        var text by remember(mode, cat) {
            mutableStateOf(current?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "")
        }
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(catColor(cat)))
            Spacer(Modifier.width(10.dp))
            Text(cat, modifier = Modifier.weight(1f), fontSize = 14.sp)
            OutlinedTextField(
                value = text,
                onValueChange = { v ->
                    text = v.filter { it.isDigit() || it == '.' }
                    Store.setBudget(mode, cat, text.toDoubleOrNull())
                },
                prefix = { Text("RM ") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(130.dp),
                shape = RoundedCornerShape(14.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
            )
        }
    }
}
