package com.example.wifiscanner

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.util.Locale

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    // ألوان الواجهة
    private val cBg = Color.parseColor("#0E1621")
    private val cCard = Color.parseColor("#172231")
    private val cText = Color.parseColor("#E8EEF5")
    private val cSub = Color.parseColor("#8FA3B8")
    private val cAccent = Color.parseColor("#1AA6B7")
    private val cWarn = Color.parseColor("#FFB74D")
    private val cBad = Color.parseColor("#EF5350")
    private val cGood = Color.parseColor("#66BB6A")

    private lateinit var wifi: WifiManager
    private lateinit var cm: ConnectivityManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var info: TextView
    private lateinit var list: ListView
    private lateinit var trackInfo: TextView
    private lateinit var graph: GraphView
    private lateinit var radarSwitch: Switch
    private lateinit var connText: TextView
    private lateinit var favInfo: TextView
    private lateinit var favList: ListView
    private lateinit var pages: List<View>
    private lateinit var tabs: List<TextView>

    private val netAdapter = NetAdapter()
    private val favAdapter = FavAdapter()

    private var results: List<ScanResult> = emptyList()
    private var shown: List<ScanResult> = emptyList()
    private var hiddenOnly = false
    private var autoOn = false
    private var curTab = 0

    private var trackedBssid: String? = null
    private var trackedName: String = ""
    private val history = ArrayList<Int>()
    private var radarOn = false
    private var tone: ToneGenerator? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = refresh()
    }

    // ---------- دورة الحياة ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        prefs = getSharedPreferences("wifi", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }
        root.addView(TextView(this).apply {
            text = "📡 ماسح الواي فاي"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(cText)
            setPadding(dp(16), dp(36), dp(16), dp(8))
        })
        val content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        // ----- الصفحة 0: الشبكات -----
        val p0 = vbox()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bScan = sbtn("فحص")
        val bCh = sbtn("القنوات")
        val bConn = sbtn("اتصال بمخفية")
        val bExp = sbtn("تصدير")
        listOf(bScan, bCh, bConn, bExp).forEach {
            row.addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        val swHidden = sw("عرض المخفية فقط")
        val swAuto = sw("تحديث تلقائي (كل 10 ثوانٍ)")
        info = TextView(this).apply { textSize = 14f; setTextColor(cSub); setPadding(dp(4), dp(6), dp(4), dp(2)) }
        list = ListView(this).apply {
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(8)
            setPadding(0, dp(8), 0, dp(8))
            clipToPadding = false
            adapter = netAdapter
        }
        p0.addView(row); p0.addView(swHidden); p0.addView(swAuto); p0.addView(info)
        p0.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        // ----- الصفحة 1: الرسم والرادار -----
        val p1 = vbox()
        trackInfo = TextView(this).apply {
            textSize = 15f; setTextColor(cText)
            text = "اختر شبكة من القائمة ثم اضغط «تتبع + رادار»"
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        graph = GraphView(this)
        radarSwitch = sw("🔊 رادار صوتي (يتسارع كلما اقتربت)")
        val bStop = sbtn("إيقاف التتبع")
        val hint = TextView(this).apply {
            textSize = 12f; setTextColor(cSub)
            text = "ملاحظة: الشبكة المتصل بها تتحدث إشارتها لحظيًا. أما غيرها فيحدّثها أندرويد عند كل فحص فقط."
            setPadding(dp(4), dp(8), dp(4), 0)
        }
        p1.addView(trackInfo)
        p1.addView(graph, LinearLayout.LayoutParams(-1, dp(220)))
        p1.addView(radarSwitch); p1.addView(bStop); p1.addView(hint)

        // ----- الصفحة 2: اتصالي -----
        val p2 = ScrollView(this)
        connText = TextView(this).apply {
            textSize = 15f; setTextColor(cText); setLineSpacing(0f, 1.3f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        p2.addView(connText)

        // ----- الصفحة 3: المفضلة -----
        val p3 = vbox()
        favInfo = TextView(this).apply { textSize = 14f; setTextColor(cSub); setPadding(dp(4), dp(8), dp(4), dp(4)) }
        favList = ListView(this).apply {
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(8)
            setPadding(0, dp(8), 0, dp(8))
            clipToPadding = false
            adapter = favAdapter
        }
        p3.addView(favInfo); p3.addView(favList, LinearLayout.LayoutParams(-1, 0, 1f))

        pages = listOf(p0, p1, p2, p3)
        pages.forEach { content.addView(it, FrameLayout.LayoutParams(-1, -1)) }

        // ----- شريط التبويبات -----
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(cCard)
        }
        val names = listOf("📶 الشبكات", "📈 الرسم", "🔗 اتصالي", "⭐ المفضلة")
        tabs = names.mapIndexed { i, n ->
            TextView(this).apply {
                text = n; textSize = 13f; gravity = android.view.Gravity.CENTER
                setPadding(0, dp(14), 0, dp(14))
                setOnClickListener { selectTab(i) }
            }
        }
        tabs.forEach { tabBar.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        root.addView(tabBar)
        setContentView(root)

        // ----- الأحداث -----
        bScan.setOnClickListener { scan(false) }
        bCh.setOnClickListener { showChannels() }
        bConn.setOnClickListener { connectDialog(prefs.getString("ssid", "") ?: "") }
        bExp.setOnClickListener { exportCsv() }
        swHidden.setOnCheckedChangeListener { _, on -> hiddenOnly = on; render() }
        swAuto.setOnCheckedChangeListener { _, on ->
            autoOn = on
            handler.removeCallbacks(autoRunnable)
            if (on) handler.post(autoRunnable)
        }
        list.setOnItemClickListener { _, _, pos, _ -> details(shown[pos]) }
        bStop.setOnClickListener { stopTracking() }
        radarSwitch.setOnCheckedChangeListener { _, on -> setRadar(on) }
        favList.setOnItemClickListener { _, _, pos, _ -> favDetails(favAdapter.keys[pos]) }

        selectTab(0)
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        handler.post(sampler)
        scan(true)
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(receiver)
        handler.removeCallbacksAndMessages(null)
        radarSwitch.isChecked = false
    }

    // ---------- المهام الدورية ----------

    private val autoRunnable = object : Runnable {
        override fun run() {
            if (!autoOn) return
            scan(true)
            handler.postDelayed(this, 10_000)
        }
    }

    private val sampler = object : Runnable {
        override fun run() {
            if (trackedBssid != null) {
                currentLevel()?.let {
                    history.add(it)
                    if (history.size > 60) history.removeAt(0)
                }
                graph.data = history.toList()
                graph.invalidate()
                updateTrackInfo()
            }
            if (curTab == 2) renderConnection()
            handler.postDelayed(this, 1000)
        }
    }

    private val beeper = object : Runnable {
        override fun run() {
            if (!radarOn) return
            val lv = currentLevel()
            if (lv == null) { handler.postDelayed(this, 1500); return }
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
            val pct = (lv + 95).coerceIn(0, 65) / 65.0
            handler.postDelayed(this, (1400 - pct * 1250).toLong())
        }
    }

    // ---------- الفحص ----------

    private fun hasPerm() = ActivityCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun scan(silent: Boolean) {
        if (!hasPerm()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return
        }
        if (!wifi.startScan() && !silent) {
            Toast.makeText(this, "الفحص محدود حاليًا، أعرض آخر نتائج", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) scan(false)
    }

    private fun refresh() {
        if (!hasPerm()) return
        results = try { wifi.scanResults } catch (e: SecurityException) { emptyList() }
        render()
        if (curTab == 3) favAdapter.reload()
    }

    private fun render() {
        val sorted = results.sortedByDescending { it.level }
        val hidden = sorted.count { it.SSID.isNullOrEmpty() }
        val weak = sorted.count { isWeak(it.capabilities) }
        info.text = "الإجمالي: ${sorted.size}  |  المخفية: $hidden  |  ⚠️ ضعيفة الأمان: $weak"
        shown = if (hiddenOnly) sorted.filter { it.SSID.isNullOrEmpty() } else sorted
        netAdapter.items = shown
        netAdapter.notifyDataSetChanged()
    }

    // ---------- التتبع والرادار ----------

    private fun currentLevel(): Int? {
        val b = trackedBssid ?: return null
        if (hasPerm()) {
            val ci = try { wifi.connectionInfo } catch (e: Exception) { null }
            if (ci != null && ci.bssid.equals(b, true) && ci.rssi > -127) return ci.rssi
        }
        return results.firstOrNull { it.BSSID.equals(b, true) }?.level
    }

    private fun startTracking(bssid: String, name: String) {
        trackedBssid = bssid
        trackedName = name
        history.clear()
        updateTrackInfo()
    }

    private fun stopTracking() {
        trackedBssid = null
        history.clear()
        radarSwitch.isChecked = false
        graph.data = emptyList(); graph.invalidate()
        trackInfo.text = "اختر شبكة من القائمة ثم اضغط «تتبع + رادار»"
    }

    private fun updateTrackInfo() {
        val b = trackedBssid ?: return
        val lv = currentLevel()
        val freq = results.firstOrNull { it.BSSID.equals(b, true) }?.frequency
        trackInfo.text = if (lv == null) "🎯 $trackedName\nالشبكة غير ظاهرة الآن"
        else "🎯 $trackedName\nالإشارة: $lv dBm (${quality(lv)})" +
            (if (freq != null) "  •  المسافة ${dist(lv, freq)}" else "")
    }

    private fun setRadar(on: Boolean) {
        radarOn = on
        handler.removeCallbacks(beeper)
        tone?.release(); tone = null
        if (on) {
            if (trackedBssid == null) {
                Toast.makeText(this, "تتبّع شبكة أولًا من قائمة الشبكات", Toast.LENGTH_SHORT).show()
                radarSwitch.isChecked = false
                radarOn = false
                return
            }
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            handler.post(beeper)
        }
    }

    // ---------- التفاصيل والمفضلة ----------

    private fun details(r: ScanResult) {
        val name = if (r.SSID.isNullOrEmpty()) "شبكة مخفية" else r.SSID
        var msg = "BSSID: ${r.BSSID}\n" +
            "الإشارة: ${r.level} dBm (${quality(r.level)})\n" +
            "المسافة التقريبية: ${dist(r.level, r.frequency)}\n" +
            "القناة: ${channel(r.frequency)}  (${r.frequency} MHz, ${band(r.frequency)})\n" +
            "الأمان: ${security(r.capabilities)}"
        if (isWeak(r.capabilities)) {
            msg += "\n\n⚠️ تشفير ضعيف أو معدوم: تجنب إرسال بيانات حساسة عبر هذه الشبكة."
        }
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(msg)
            .setPositiveButton("تتبع + رادار") { _, _ -> startTracking(r.BSSID, name); selectTab(1) }
            .setNeutralButton("المفضلة") { _, _ -> favDialog(r.BSSID, name, "") }
            .setNegativeButton("اتصال") { _, _ -> connectDialog(r.SSID ?: prefs.getString("ssid", "") ?: "") }
            .show()
    }

    private fun loadFavs(): JSONObject =
        try { JSONObject(prefs.getString("favs", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    private fun favDialog(bssid: String, name: String, oldNote: String) {
        val note = EditText(this).apply { hint = "ملاحظة (مثل: راوتر الطابق الثاني)"; setText(oldNote) }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(note, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(this).setTitle("حفظ في المفضلة: $name").setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                val favs = loadFavs()
                favs.put(bssid, JSONObject().put("ssid", name).put("note", note.text.toString().trim()))
                prefs.edit().putString("favs", favs.toString()).apply()
                favAdapter.reload()
                Toast.makeText(this, "تم الحفظ ⭐", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun favDetails(bssid: String) {
        val f = loadFavs().optJSONObject(bssid) ?: return
        val name = f.optString("ssid")
        val note = f.optString("note")
        val live = results.firstOrNull { it.BSSID.equals(bssid, true) }
        val msg = "BSSID: $bssid\n" +
            (if (note.isNotEmpty()) "الملاحظة: $note\n" else "") +
            (if (live != null) "الآن: ${live.level} dBm، المسافة ${dist(live.level, live.frequency)}" else "غير ظاهرة حاليًا")
        AlertDialog.Builder(this).setTitle(name).setMessage(msg)
            .setPositiveButton("تتبع + رادار") { _, _ -> startTracking(bssid, name); selectTab(1) }
            .setNeutralButton("تعديل") { _, _ -> favDialog(bssid, name, note) }
            .setNegativeButton("حذف") { _, _ ->
                val favs = loadFavs(); favs.remove(bssid)
                prefs.edit().putString("favs", favs.toString()).apply()
                favAdapter.reload()
            }.show()
    }

    // ---------- القنوات والتصدير ----------

    private fun showChannels() {
        val counts = IntArray(14)
        results.filter { it.frequency in 2400..2500 }.forEach {
            val c = channel(it.frequency)
            if (c in 1..13) counts[c]++
        }
        val sb = StringBuilder("2.4GHz:\n")
        for (c in 1..13) sb.append(String.format(Locale.US, "%2d  %s %d\n", c, "█".repeat(counts[c]), counts[c]))
        val best = listOf(1, 6, 11).minByOrNull { counts[it] }
        sb.append("\nالأقل ازدحامًا بين 1 و6 و11: القناة $best")
        sb.append("\n\nشبكات 5GHz: ${results.count { it.frequency in 4900..5900 }}")
        AlertDialog.Builder(this).setTitle("ازدحام القنوات").setMessage(sb.toString())
            .setPositiveButton("حسنًا", null).show()
    }

    private fun exportCsv() {
        if (results.isEmpty()) {
            Toast.makeText(this, "لا توجد نتائج للتصدير", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder("SSID,BSSID,Signal_dBm,Frequency_MHz,Channel,Security,Distance_est\n")
        results.sortedByDescending { it.level }.forEach {
            val ssid = if (it.SSID.isNullOrEmpty()) "(hidden)" else it.SSID.replace("\"", "\"\"")
            sb.append("\"").append(ssid).append("\",")
                .append(it.BSSID).append(',').append(it.level).append(',')
                .append(it.frequency).append(',').append(channel(it.frequency)).append(',')
                .append(security(it.capabilities)).append(',')
                .append(dist(it.level, it.frequency)).append('\n')
        }
        val f = File(cacheDir, "wifi_scan.csv")
        f.writeText(sb.toString())
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "مشاركة النتائج"))
    }

    // ---------- الاتصال بشبكة مخفية ----------

    private fun connectDialog(prefill: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val ssid = EditText(this).apply { hint = "اسم الشبكة (SSID)"; setText(prefill) }
        val pass = EditText(this).apply {
            hint = "كلمة المرور (فارغة للشبكة المفتوحة)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val save = CheckBox(this).apply { text = "احفظ اسم الشبكة (بدون كلمة المرور)"; isChecked = true }
        box.addView(ssid); box.addView(pass); box.addView(save)

        AlertDialog.Builder(this).setTitle("اتصال بشبكة مخفية").setView(box)
            .setPositiveButton("اتصال") { _, _ ->
                val name = ssid.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                if (save.isChecked) prefs.edit().putString("ssid", name).apply()
                connect(name, pass.text.toString())
            }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun connect(name: String, password: String) {
        val b = WifiNetworkSpecifier.Builder().setSsid(name).setIsHiddenSsid(true)
        if (password.isNotEmpty()) b.setWpa2Passphrase(password)
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(b.build())
            .build()

        netCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
                runOnUiThread { Toast.makeText(this@MainActivity, "تم الاتصال بـ $name ✅", Toast.LENGTH_LONG).show() }
            }
            override fun onUnavailable() {
                runOnUiThread { Toast.makeText(this@MainActivity, "تعذّر الاتصال، تأكد من الاسم وكلمة المرور", Toast.LENGTH_LONG).show() }
            }
        }
        netCallback = cb
        cm.requestNetwork(req, cb, 30_000)
    }

    // ---------- معلومات الاتصال الحالي ----------

    private fun renderConnection() {
        if (!hasPerm()) { connText.text = "امنح إذن الموقع لعرض معلومات الاتصال."; return }
        val ci = try { wifi.connectionInfo } catch (e: Exception) { null }
        if (ci == null || ci.rssi <= -127 || ci.ssid == "<unknown ssid>") {
            connText.text = "غير متصل بشبكة واي فاي (أو خدمة الموقع مغلقة)."
            return
        }
        val dhcp = wifi.dhcpInfo
        val std = if (Build.VERSION.SDK_INT >= 30) when (ci.wifiStandard) {
            4 -> "Wi-Fi 4 (802.11n)"
            5 -> "Wi-Fi 5 (802.11ac)"
            6 -> "Wi-Fi 6 (802.11ax)"
            8 -> "Wi-Fi 7 (802.11be)"
            1 -> "قديم (a/b/g)"
            else -> "غير معروف"
        } else "غير متاح"
        val tx = ci.txLinkSpeedMbps
        val rx = ci.rxLinkSpeedMbps
        connText.text = "📶 ${ci.ssid.trim('"')}\n\n" +
            "BSSID: ${ci.bssid}\n" +
            "الإشارة: ${ci.rssi} dBm (${quality(ci.rssi)})\n" +
            "سرعة الاتصال: ${ci.linkSpeed} Mbps\n" +
            "إرسال / استقبال: ${if (tx > 0) tx else "-"} / ${if (rx > 0) rx else "-"} Mbps\n" +
            "التردد: ${ci.frequency} MHz (${band(ci.frequency)}) • قناة ${channel(ci.frequency)}\n" +
            "المعيار: $std\n" +
            "عنوان IP: ${ip(ci.ipAddress)}\n" +
            "البوابة: ${ip(dhcp.gateway)}\n" +
            "DNS: ${ip(dhcp.dns1)}"
    }

    // ---------- أدوات مساعدة ----------

    private fun selectTab(i: Int) {
        curTab = i
        pages.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { idx, t ->
            t.setTextColor(if (idx == i) cAccent else cSub)
            t.typeface = if (idx == i) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        if (i == 2) renderConnection()
        if (i == 3) favAdapter.reload()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }

    private fun vbox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(4))
    }

    private fun sbtn(t: String) = Button(this).apply {
        text = t; textSize = 12f; isAllCaps = false
        setTextColor(cText)
        background = rounded(Color.parseColor("#22344A"), 12)
    }

    private fun sw(t: String) = Switch(this).apply {
        text = t; setTextColor(cText); setPadding(dp(4), dp(8), dp(4), dp(8))
    }

    private fun channel(f: Int): Int = when {
        f == 2484 -> 14
        f in 2400..2483 -> (f - 2407) / 5
        f in 4900..5900 -> (f - 5000) / 5
        else -> (f - 5950) / 5
    }

    private fun band(f: Int) = when {
        f < 3000 -> "2.4GHz"
        f < 5925 -> "5GHz"
        else -> "6GHz"
    }

    private fun quality(l: Int) = when {
        l >= -55 -> "ممتازة"
        l >= -67 -> "جيدة"
        l >= -78 -> "متوسطة"
        else -> "ضعيفة"
    }

    private fun levelColor(l: Int) = when {
        l >= -60 -> cGood
        l >= -75 -> cWarn
        else -> cBad
    }

    private fun security(c: String) = when {
        c.contains("SAE") && c.contains("PSK") -> "WPA2/WPA3"
        c.contains("SAE") -> "WPA3"
        c.contains("OWE") -> "OWE (مشفرة)"
        c.contains("WPA2") || c.contains("RSN") -> "WPA2"
        c.contains("WPA") -> "WPA"
        c.contains("WEP") -> "WEP"
        else -> "مفتوحة"
    }

    private fun isWeak(c: String): Boolean {
        val s = security(c)
        return s == "مفتوحة" || s == "WEP"
    }

    // تقدير تقريبي جدًا للمسافة من قوة الإشارة (يتأثر بالجدران)
    private fun dist(level: Int, freq: Int): String {
        val d = Math.pow(10.0, (27.55 - 20 * Math.log10(freq.toDouble()) + Math.abs(level)) / 20.0)
        return if (d < 10) String.format(Locale.US, "~%.1f م", d) else String.format(Locale.US, "~%.0f م", d)
    }

    private fun ip(i: Int) =
        "${i and 0xff}.${(i shr 8) and 0xff}.${(i shr 16) and 0xff}.${(i shr 24) and 0xff}"

    private fun buildCard(title: String, l2: String, l3: String, c3: Int, level: Int?): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cCard, 14)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        card.addView(TextView(this).apply { text = title; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(cText) })
        card.addView(TextView(this).apply { text = l2; textSize = 12f; setTextColor(cSub) })
        card.addView(TextView(this).apply { text = l3; textSize = 12f; setTextColor(c3) })
        if (level != null) {
            val pct = (level + 100).coerceIn(0, 70) / 70f
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            bar.addView(View(this).apply { background = rounded(levelColor(level), 3) }, LinearLayout.LayoutParams(0, dp(5), pct))
            bar.addView(View(this).apply { background = rounded(Color.parseColor("#263548"), 3) }, LinearLayout.LayoutParams(0, dp(5), 1f - pct))
            card.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        return card
    }

    // ---------- المحولات ----------

    private inner class NetAdapter : BaseAdapter() {
        var items: List<ScanResult> = emptyList()
        override fun getCount() = items.size
        override fun getItem(p: Int): Any = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, v: View?, parent: ViewGroup?): View {
            val r = items[p]
            val hidden = r.SSID.isNullOrEmpty()
            val title = if (hidden) "🔒 [شبكة مخفية]" else r.SSID
            val l2 = "${r.BSSID}  •  قناة ${channel(r.frequency)}  •  ${band(r.frequency)}"
            val weak = isWeak(r.capabilities)
            val l3 = "${r.level} dBm  •  ${dist(r.level, r.frequency)}  •  " +
                (if (weak) "⚠️ " else "") + security(r.capabilities)
            return buildCard(title, l2, l3, if (weak) cWarn else cSub, r.level)
        }
    }

    private inner class FavAdapter : BaseAdapter() {
        var keys: List<String> = emptyList()
        fun reload() {
            val f = loadFavs()
            keys = f.keys().asSequence().toList()
            favInfo.text = if (keys.isEmpty()) "لا توجد شبكات محفوظة. افتح شبكة من القائمة واختر «المفضلة»."
            else "الشبكات المحفوظة: ${keys.size}"
            notifyDataSetChanged()
        }
        override fun getCount() = keys.size
        override fun getItem(p: Int): Any = keys[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, v: View?, parent: ViewGroup?): View {
            val k = keys[p]
            val f = loadFavs().optJSONObject(k)
            val name = f?.optString("ssid") ?: k
            val note = f?.optString("note") ?: ""
            val live = results.firstOrNull { it.BSSID.equals(k, true) }
            val l3 = if (live != null) "${live.level} dBm  •  ${dist(live.level, live.frequency)}" else "غير ظاهرة حاليًا"
            return buildCard("⭐ $name", if (note.isNotEmpty()) note else k, l3, cSub, live?.level)
        }
    }

    // ---------- الرسم البياني ----------

    private inner class GraphView(c: Context) : View(c) {
        var data: List<Int> = emptyList()
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cAccent; strokeWidth = dp(2).toFloat(); style = Paint.Style.STROKE
        }
        private val grid = Paint().apply { color = Color.parseColor("#263548"); strokeWidth = 2f }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cSub; textSize = dp(10).toFloat() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val padL = dp(34).toFloat()
            val usable = h - dp(8).toFloat()
            fun y(l: Int): Float = h - dp(4).toFloat() - ((l + 100).coerceIn(0, 80) / 80f) * usable
            for (l in listOf(-30, -50, -70, -90)) {
                canvas.drawLine(padL, y(l), w, y(l), grid)
                canvas.drawText("$l", 4f, y(l) + dp(3).toFloat(), txt)
            }
            if (data.size < 2) return
            val path = Path()
            val step = (w - padL) / 59f
            data.forEachIndexed { i, l ->
                val x = padL + i * step
                if (i == 0) path.moveTo(x, y(l)) else path.lineTo(x, y(l))
            }
            canvas.drawPath(path, line)
        }
    }
}
