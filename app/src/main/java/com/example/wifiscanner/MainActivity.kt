package com.example.wifiscanner

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var wifi: WifiManager
    private lateinit var cm: ConnectivityManager
    private lateinit var prefs: SharedPreferences
    private lateinit var list: ListView
    private lateinit var info: TextView
    private lateinit var track: TextView

    private var results: List<ScanResult> = emptyList()
    private var shown: List<ScanResult> = emptyList()
    private var hiddenOnly = false
    private var trackedBssid: String? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        prefs = getSharedPreferences("wifi", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 16)
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scanBtn = Button(this).apply { text = "فحص" }
        val chBtn = Button(this).apply { text = "القنوات" }
        val connBtn = Button(this).apply { text = "اتصال بمخفية" }
        row1.addView(scanBtn, LinearLayout.LayoutParams(0, -2, 1f))
        row1.addView(chBtn, LinearLayout.LayoutParams(0, -2, 1f))
        row1.addView(connBtn, LinearLayout.LayoutParams(0, -2, 1f))

        val sw = Switch(this).apply { text = "عرض المخفية فقط" }
        info = TextView(this).apply { textSize = 15f; setPadding(0, 12, 0, 4) }
        track = TextView(this).apply { textSize = 15f; setPadding(0, 4, 0, 8) }
        list = ListView(this)

        root.addView(row1); root.addView(sw); root.addView(info); root.addView(track); root.addView(list)
        setContentView(root)

        scanBtn.setOnClickListener { scan() }
        chBtn.setOnClickListener { showChannels() }
        connBtn.setOnClickListener { connectDialog(prefs.getString("ssid", "") ?: "") }
        sw.setOnCheckedChangeListener { _, on -> hiddenOnly = on; render() }
        list.setOnItemClickListener { _, _, pos, _ -> details(shown[pos]) }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        scan()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(receiver)
    }

    private fun hasPerm() = ActivityCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun scan() {
        if (!hasPerm()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return
        }
        if (!wifi.startScan()) Toast.makeText(this, "الفحص محدود حاليًا، أعرض آخر نتائج", Toast.LENGTH_SHORT).show()
        refresh()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) scan()
    }

    private fun refresh() {
        if (!hasPerm()) return
        results = try { wifi.scanResults } catch (e: SecurityException) { emptyList() }
        render()
    }

    private fun render() {
        val sorted = results.sortedByDescending { it.level }
        val hidden = sorted.count { it.SSID.isNullOrEmpty() }
        info.text = "الإجمالي: ${sorted.size}  |  المخفية: $hidden"
        shown = if (hiddenOnly) sorted.filter { it.SSID.isNullOrEmpty() } else sorted

        val rows = shown.map {
            val name = if (it.SSID.isNullOrEmpty()) "🔒 [مخفية]" else it.SSID
            "${bars(it.level)}  $name\n${it.BSSID}  •  قناة ${channel(it.frequency)}  •  ${it.level} dBm"
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)

        val t = results.firstOrNull { it.BSSID == trackedBssid }
        track.text = when {
            trackedBssid == null -> ""
            t == null -> "🎯 التتبع: الشبكة غير ظاهرة الآن"
            else -> "🎯 التتبع: ${bars(t.level)} ${t.level} dBm (${quality(t.level)})"
        }
    }

    private fun details(r: ScanResult) {
        val name = if (r.SSID.isNullOrEmpty()) "شبكة مخفية" else r.SSID
        val msg = "BSSID: ${r.BSSID}\n" +
            "الإشارة: ${r.level} dBm (${quality(r.level)})\n" +
            "القناة: ${channel(r.frequency)}  (${r.frequency} MHz, ${band(r.frequency)})\n" +
            "الأمان: ${security(r.capabilities)}\n" +
            "القدرات: ${r.capabilities}"
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(msg)
            .setPositiveButton("تتبع الإشارة") { _, _ -> trackedBssid = r.BSSID; render() }
            .setNeutralButton("اتصال") { _, _ -> connectDialog(r.SSID ?: prefs.getString("ssid", "") ?: "") }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun showChannels() {
        val counts = IntArray(14)
        results.filter { it.frequency in 2400..2500 }.forEach {
            val c = channel(it.frequency)
            if (c in 1..13) counts[c]++
        }
        val sb = StringBuilder("2.4GHz:\n")
        for (c in 1..13) sb.append(String.format("%2d  %s %d\n", c, "█".repeat(counts[c]), counts[c]))
        val best = listOf(1, 6, 11).minByOrNull { counts[it] }
        sb.append("\nالأقل ازدحامًا بين 1 و6 و11: القناة $best")
        val g5 = results.count { it.frequency in 4900..5900 }
        sb.append("\n\nشبكات 5GHz: $g5")
        AlertDialog.Builder(this).setTitle("ازدحام القنوات").setMessage(sb.toString())
            .setPositiveButton("حسنًا", null).show()
    }

    private fun connectDialog(prefill: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
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

    private fun bars(l: Int) = when {
        l >= -55 -> "▂▄▆█"
        l >= -67 -> "▂▄▆_"
        l >= -78 -> "▂▄__"
        l >= -88 -> "▂___"
        else -> "____"
    }

    private fun quality(l: Int) = when {
        l >= -55 -> "ممتازة"
        l >= -67 -> "جيدة"
        l >= -78 -> "متوسطة"
        else -> "ضعيفة"
    }

    private fun security(c: String) = when {
        c.contains("SAE") -> "WPA3"
        c.contains("WPA2") -> "WPA2"
        c.contains("WPA") -> "WPA"
        c.contains("WEP") -> "WEP"
        else -> "مفتوحة"
    }
}
