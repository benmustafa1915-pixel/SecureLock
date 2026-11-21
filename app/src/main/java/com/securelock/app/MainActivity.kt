package com.securelock.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast

class MainActivity : Activity() {
    private var authed = false
    private var grace = 0L
    private var backFn: (() -> Unit)? = null
    private var tickR: Runnable? = null
    private var shownMode = 0
    private val h = Handler(Looper.getMainLooper())
    private val green = Color.parseColor("#66BB6A")
    private val red = Color.parseColor("#E57373")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
    }

    override fun onStart() {
        super.onStart()
        if (authed) home() else route()
    }

    override fun onStop() {
        super.onStop()
        stopTick()
        if (System.currentTimeMillis() > grace) authed = false
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val f = backFn
        if (f != null) f() else finish()
    }

    // ---------- yardimcilar ----------

    private fun show(v: View, back: (() -> Unit)? = { home() }) {
        stopTick()
        setContentView(v)
        backFn = back
    }

    private fun stopTick() {
        tickR?.let { h.removeCallbacks(it) }
        tickR = null
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    }

    private fun accOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return s.contains(packageName)
    }

    private fun open(i: Intent) {
        grace = System.currentTimeMillis() + 180000
        try {
            startActivity(i)
        } catch (e: Exception) {
            toast("Açılamadı")
        }
    }

    private fun confirm(title: String, msg: String, no: () -> Unit = {}, ok: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("Evet") { _, _ -> ok() }
            .setNegativeButton("Vazgeç") { _, _ -> no() }
            .show()
    }

    private fun hdr(col: LinearLayout, title: String, back: () -> Unit) {
        col.addView(UI.button(this, "Geri") { back() }, LinearLayout.LayoutParams(-2, -2))
        col.addView(UI.tv(this, title, 24f, UI.TXT, true), UI.lp(this, 14))
    }

    private fun iconHidden(on: Boolean) {
        packageManager.setComponentEnabledSetting(
            ComponentName(this, "com.securelock.app.LauncherAlias"),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    // ---------- giris / kurulum ----------

    private fun route() {
        if (Store.isSetup(this)) login() else setup()
    }

    private fun setup() {
        askText("Yönetici şifresi", "Uygulamaya her girişte sorulur. Kurtarma yöntemi yoktur, unutma.", false, 4, null) { admin ->
            askText("Katı Mod şifresi", "Sıkı ve Katı Mod başlatılırken sorulur. Kurtarma yöntemi yoktur.", false, 4, null) { strict ->
                pickLock(null) { t, s ->
                    Store.setAdmin(this, admin)
                    Store.setStrict(this, strict)
                    Store.setLock(this, t, s)
                    authed = true
                    home()
                }
            }
        }
    }

    private fun login() {
        val (v, col) = UI.page(this)
        col.gravity = Gravity.CENTER_VERTICAL
        col.addView(UI.tv(this, "Secure Lock", 28f, UI.TXT, true))
        val et = UI.input(this, "Yönetici şifresi", false)
        et.imeOptions = EditorInfo.IME_ACTION_DONE
        col.addView(et, UI.lp(this, 20))
        val go = {
            if (Store.checkAdmin(this, et.text.toString())) {
                authed = true
                home()
            } else {
                toast("Yanlış şifre")
                et.setText("")
            }
        }
        et.setOnEditorActionListener { _, _, _ -> go(); true }
        col.addView(UI.button(this, "Gir", UI.ACC) { go() }, UI.lp(this, 12))
        show(v, null)
    }

    private fun askText(
        title: String, info: String, numeric: Boolean, minLen: Int,
        back: (() -> Unit)?, ok: (String) -> Unit
    ) {
        val (v, col) = UI.page(this)
        col.addView(UI.tv(this, title, 22f, UI.TXT, true))
        if (info.isNotEmpty()) col.addView(UI.tv(this, info, 14f, UI.DIM), UI.lp(this, 6))
        val a = UI.input(this, "Yeni", numeric)
        val b = UI.input(this, "Tekrar", numeric)
        if (numeric) {
            val f = arrayOf<InputFilter>(InputFilter.LengthFilter(8))
            a.filters = f
            b.filters = f
        }
        col.addView(a, UI.lp(this, 18))
        col.addView(b, UI.lp(this, 8))
        col.addView(UI.button(this, "Devam", UI.ACC) {
            val x = a.text.toString()
            if (x.length < minLen) toast("En az $minLen karakter")
            else if (x != b.text.toString()) toast("İki alan aynı değil")
            else ok(x)
        }, UI.lp(this, 18))
        if (back != null) col.addView(UI.button(this, "Geri") { back() }, UI.lp(this, 8))
        show(v, back)
    }

    private fun askPattern(title: String, back: (() -> Unit)?, ok: (String) -> Unit) {
        val (v, col) = UI.page(this)
        col.addView(UI.tv(this, title, 22f, UI.TXT, true))
        val info = UI.tv(this, "En az 4 nokta birleştir", 14f, UI.DIM)
        col.addView(info, UI.lp(this, 6))
        val pv = PatternView(this)
        var first: String? = null
        pv.onDone = { code ->
            if (code.length < 4) {
                info.text = "En az 4 nokta"
                pv.flashError()
            } else if (first == null) {
                first = code
                info.text = "Aynı deseni tekrar çiz"
                pv.clear()
            } else if (first == code) {
                ok(code)
            } else {
                first = null
                info.text = "Eşleşmedi, baştan çiz"
                pv.flashError()
            }
        }
        col.addView(pv, UI.lp(this, 16))
        if (back != null) col.addView(UI.button(this, "Geri") { back() }, UI.lp(this, 12))
        show(v, back)
    }

    private fun pickLock(back: (() -> Unit)?, ok: (Int, String) -> Unit) {
        val (v, col) = UI.page(this)
        col.addView(UI.tv(this, "Uygulama kilidi türü", 22f, UI.TXT, true))
        col.addView(UI.tv(this, "Engelli uygulama açılırken bu istenir.", 14f, UI.DIM), UI.lp(this, 6))
        col.addView(UI.button(this, "PIN (4-8 rakam)") {
            askText("PIN belirle", "4 ile 8 rakam", true, 4, { pickLock(back, ok) }) { s -> ok(Store.PIN, s) }
        }, UI.lp(this, 18))
        col.addView(UI.button(this, "Parola (telefon klavyesi)") {
            askText("Parola belirle", "En az 4 karakter", false, 4, { pickLock(back, ok) }) { s -> ok(Store.PASS, s) }
        }, UI.lp(this, 8))
        col.addView(UI.button(this, "Desen") {
            askPattern("Desen belirle", { pickLock(back, ok) }) { s -> ok(Store.PATTERN, s) }
        }, UI.lp(this, 8))
        if (back != null) col.addView(UI.button(this, "Geri") { back() }, UI.lp(this, 14))
        show(v, back)
    }

    // ---------- ana ekran ----------

    private fun home() {
        val mode = Policy.mode(this)
        shownMode = mode
        val (v, col) = UI.page(this)
        col.addView(UI.tv(this, "Secure Lock", 26f, UI.TXT, true))
        val card = UI.card(this)
        val st = UI.tv(this, "", 18f, UI.TXT, true)
        val sub = UI.tv(this, "", 14f, UI.DIM)
        card.addView(st)
        card.addView(sub, UI.lp(this, 4))
        col.addView(card, UI.lp(this, 16))
        if (!accOn()) {
            col.addView(UI.tv(this, "Erişilebilirlik izni kapalı: koruma çalışmıyor.", 14f, red), UI.lp(this, 10))
        }
        if (mode == 0) {
            col.addView(UI.button(this, "Sıkı Mod başlat", UI.ACC) { sessionScreen(1) }, UI.lp(this, 14))
            col.addView(UI.button(this, "Katı Mod başlat", UI.RED) { sessionScreen(2) }, UI.lp(this, 8))
        }
        col.addView(UI.button(this, "Engellenecek uygulamalar (" + Store.blocked(this).size + ")") { appsScreen() }, UI.lp(this, 8))
        col.addView(UI.button(this, "İstatistik") { stats() }, UI.lp(this, 8))
        col.addView(UI.button(this, "Ayarlar") { settings() }, UI.lp(this, 8))
        col.addView(UI.button(this, "Kurulum ve izinler") { perms() }, UI.lp(this, 8))
        show(v, null)
        val r = object : Runnable {
            override fun run() {
                val m = Policy.mode(this@MainActivity)
                if (m != shownMode) {
                    home()
                    return
                }
                if (m == 0) {
                    st.text = "Normal Mod"
                    sub.text = "Engelli uygulamalar şifreyle açılır."
                } else {
                    st.text = (if (m == 1) "Sıkı Mod" else "Katı Mod") + " aktif"
                    sub.text = "Kalan süre: " + fmt(Policy.remaining(this@MainActivity)) +
                        "\nSüre bitene kadar geri dönüş yok."
                }
                h.postDelayed(this, 1000)
            }
        }
        tickR = r
        r.run()
    }

    // ---------- Sıkı / Katı Mod ----------

    private fun sessionScreen(mode: Int) {
        val (v, col) = UI.page(this)
        hdr(col, if (mode == 1) "Sıkı Mod" else "Katı Mod") { home() }
        val info = if (mode == 1) {
            "Süre bitene kadar engelli uygulamalar hiçbir şifreyle açılmaz, listeden uygulama çıkarılamaz, ayarlar değişmez."
        } else {
            "Sıkı Mod'un hepsi. Ek olarak Ayarlar uygulaması engellenir; Secure Lock kaldırılamaz ve zorla durdurulamaz; saat, güvenli mod ve fabrika ayarı kilitlenir."
        }
        col.addView(UI.tv(this, info, 14f, UI.DIM), UI.lp(this, 4))
        if (mode == 2 && !Policy.isOwner(this)) {
            col.addView(UI.tv(this, "Device Owner verilmemiş: Katı Mod başlatılamaz. Kurulum ve izinler ekranına bak.", 14f, red), UI.lp(this, 8))
        }
        val min = UI.input(this, "Süre (dakika, 5-1440)", false)
        min.inputType = InputType.TYPE_CLASS_NUMBER
        min.setText("60")
        col.addView(min, UI.lp(this, 16))
        val row = LinearLayout(this)
        for (m in listOf(30, 60, 120, 180, 300)) {
            row.addView(UI.button(this, "$m") { min.setText(m.toString()) }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        col.addView(row, UI.lp(this, 6))
        val pw = UI.input(this, "Katı Mod şifresi", false)
        col.addView(pw, UI.lp(this, 12))
        col.addView(UI.button(this, "Başlat", if (mode == 1) UI.ACC else UI.RED) {
            val m = min.text.toString().toIntOrNull() ?: 0
            when {
                m !in 5..1440 -> toast("Süre 5 ile 1440 dakika arasında olmalı")
                !Store.checkStrict(this, pw.text.toString()) -> toast("Katı Mod şifresi yanlış")
                Store.blocked(this).isEmpty() -> toast("Önce engellenecek uygulama seç")
                !accOn() -> toast("Erişilebilirlik izni kapalı: Kurulum ve izinler")
                mode == 2 && !Policy.isOwner(this) -> toast("Katı Mod için Device Owner gerekli")
                else -> confirm("Geri dönüş yok", "$m dakika boyunca hiçbir şifreyle bozulamaz. Başlatılsın mı?") {
                    Policy.start(this, mode, m)
                    home()
                }
            }
        }, UI.lp(this, 16))
        show(v)
    }

    // ---------- uygulama listesi ----------

    private fun appsScreen() {
        val pm = packageManager
        val prot = Policy.protectedPkgs(this)
        val inSession = Policy.mode(this) != 0
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { Pair(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { !prot.contains(it.first) }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(UI.BG)
        root.setPadding(UI.dp(this, 14), UI.dp(this, 20), UI.dp(this, 14), UI.dp(this, 10))
        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(UI.button(this, "Geri") { home() })
        top.addView(UI.tv(this, "  Uygulamalar", 20f, UI.TXT, true))
        root.addView(top)
        val hint = if (inSession) "Oturum sürüyor: uygulama eklenebilir, çıkarılamaz." else "Uzun basınca o uygulamaya özel parola koyarsın."
        root.addView(UI.tv(this, hint, 12f, UI.DIM), UI.lp(this, 6))
        val q = UI.input(this, "Ara")
        q.inputType = InputType.TYPE_CLASS_TEXT
        root.addView(q, UI.lp(this, 4))
        val sv = ScrollView(this)
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        sv.addView(list)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        val rows = ArrayList<Pair<View, String>>()
        val blockedNow = Store.blocked(this)
        for ((pkg, name) in apps) {
            val row = UI.card(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val special = Store.hasAppPass(this, pkg)
            row.addView(UI.tv(this, name + if (special) "  (özel parola)" else "", 16f), LinearLayout.LayoutParams(0, -2, 1f))
            val sw = Switch(this)
            sw.isChecked = blockedNow.contains(pkg)
            if (inSession && sw.isChecked) sw.isEnabled = false
            sw.setOnCheckedChangeListener { b, on ->
                if (inSession && !on) b.isChecked = true else Store.setBlocked(this, pkg, on)
            }
            row.addView(sw)
            row.setOnLongClickListener {
                if (!inSession) appPassDialog(pkg, name)
                true
            }
            list.addView(row, UI.lp(this, 6))
            rows.add(Pair(row, name.lowercase()))
        }
        q.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val t = (s?.toString() ?: "").lowercase()
                for ((rv, n) in rows) rv.visibility = if (n.contains(t)) View.VISIBLE else View.GONE
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        show(root)
    }

    private fun appPassDialog(pkg: String, name: String) {
        val e = EditText(this)
        e.hint = "Özel parola (boş = genel kilit)"
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage("Bu uygulamaya özel parola. Boş bırakırsan genel kilit kullanılır.")
            .setView(e)
            .setPositiveButton("Kaydet") { _, _ ->
                Store.setAppPass(this, pkg, e.text.toString())
                appsScreen()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ---------- istatistik ----------

    private fun stats() {
        val (v, col) = UI.page(this)
        hdr(col, "İstatistik") { home() }
        col.addView(UI.tv(this, "Zincir: " + Store.streak(this) + " gün", 20f, UI.TXT, true), UI.lp(this, 8))
        col.addView(UI.tv(this, "Her gün en az bir odak oturumu tamamlarsan zincir uzar.", 13f, UI.DIM))
        val days = (6 downTo 0).toList()
        val labels = days.map { Store.label(it) }
        col.addView(BarChart(this, "Odak dakikası (7 gün)", labels, days.map { Store.foc(this, it) }, UI.ACC), UI.lp(this, 14))
        col.addView(BarChart(this, "Engellenen deneme (7 gün)", labels, days.map { Store.att(this, it) }, UI.RED), UI.lp(this, 10))
        var f = 0
        var a = 0
        for (i in 0 until 30) {
            f += Store.foc(this, i)
            a += Store.att(this, i)
        }
        col.addView(UI.tv(this, "Son 30 gün: $f dk odak, $a engellenen deneme", 15f), UI.lp(this, 10))
        show(v)
    }

    // ---------- ayarlar ----------

    private fun settings() {
        val (v, col) = UI.page(this)
        hdr(col, "Ayarlar") { home() }
        if (Policy.mode(this) != 0) {
            col.addView(UI.tv(this, "Oturum sürerken ayarlar değiştirilemez.", 16f, UI.DIM), UI.lp(this, 10))
            show(v)
            return
        }
        val types = arrayOf("PIN", "Parola", "Desen")
        col.addView(UI.tv(this, "Kilit türü: " + types[Store.lockType(this)], 16f, UI.DIM), UI.lp(this, 10))
        col.addView(UI.button(this, "Kilit şifresini / türünü değiştir") {
            pickLock({ settings() }) { t, s ->
                Store.setLock(this, t, s)
                toast("Kaydedildi")
                settings()
            }
        }, UI.lp(this, 8))
        col.addView(UI.button(this, "Yönetici şifresini değiştir") {
            askText("Yeni yönetici şifresi", "Kurtarma yöntemi yoktur.", false, 4, { settings() }) { s ->
                Store.setAdmin(this, s)
                toast("Kaydedildi")
                settings()
            }
        }, UI.lp(this, 8))
        col.addView(UI.button(this, "Katı Mod şifresini değiştir") {
            askText("Yeni Katı Mod şifresi", "Kurtarma yöntemi yoktur.", false, 4, { settings() }) { s ->
                Store.setStrict(this, s)
                toast("Kaydedildi")
                settings()
            }
        }, UI.lp(this, 8))
        col.addView(UI.switchRow(this, "Oturumda gri tonlama", Store.flag(this, "gray")) { on ->
            Store.setFlag(this, "gray", on)
        }, UI.lp(this, 16))
        col.addView(UI.tv(this, "Gri tonlama için ayrıca bir izin gerekir (Kurulum ve izinler).", 13f, UI.DIM), UI.lp(this, 4))
        col.addView(UI.switchRow(this, "Simgeyi gizle", Store.flag(this, "hide")) { on ->
            if (on) {
                confirm(
                    "Simge gizlensin mi?",
                    "Simge ana menüden kalkar. Açmak için telefon uygulamasında *#*#7328#*#* tuşla. Önce kodun çalıştığından emin ol. Çalışmazsa Ayarlar > Erişilebilirlik > Secure Lock üzerinden girersin.",
                    { settings() }
                ) {
                    Store.setFlag(this, "hide", true)
                    iconHidden(true)
                }
            } else {
                Store.setFlag(this, "hide", false)
                iconHidden(false)
            }
        }, UI.lp(this, 12))
        if (Policy.isOwner(this)) {
            col.addView(UI.button(this, "Device Owner'ı bırak", UI.RED) {
                confirm("Device Owner bırakılsın mı?", "Katı Mod koruması çalışmaz, uygulama normal şekilde kaldırılabilir olur.") {
                    try {
                        Policy.dpm(this).clearDeviceOwnerApp(packageName)
                        toast("Bırakıldı")
                    } catch (e: Exception) {
                        toast("Olmadı: " + e.message)
                    }
                    settings()
                }
            }, UI.lp(this, 16))
        }
        show(v)
    }

    // ---------- kurulum ve izinler ----------

    private fun perms() {
        val (v, col) = UI.page(this)
        hdr(col, "Kurulum ve izinler") { home() }
        fun row(title: String, ok: Boolean, info: String, btn: String?, act: () -> Unit) {
            val c = UI.card(this)
            c.addView(UI.tv(this, title + ": " + (if (ok) "Tamam" else "Eksik"), 16f, if (ok) green else red, true))
            if (info.isNotEmpty()) {
                val t = UI.tv(this, info, 13f, UI.DIM)
                t.setTextIsSelectable(true)
                c.addView(t, UI.lp(this, 4))
            }
            if (btn != null && !ok) c.addView(UI.button(this, btn, UI.ACC) { act() }, UI.lp(this, 8))
            col.addView(c, UI.lp(this, 10))
        }
        row(
            "Erişilebilirlik", accOn(),
            "Açılmazsa: Ayarlar > Uygulamalar > Secure Lock > sağ üst üç nokta > Kısıtlı ayarlara izin ver, sonra tekrar dene.",
            "Ayarları aç"
        ) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        row("Üstte görüntüleme", Settings.canDrawOverlays(this), "", "İzin ver") {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        val pmgr = getSystemService(POWER_SERVICE) as PowerManager
        row("Pil kısıtlaması yok", pmgr.isIgnoringBatteryOptimizations(packageName), "", "İzin ver") {
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        row(
            "Device Owner (Katı Mod)", Policy.isOwner(this),
            "Bugjaeger kabuğunda çalıştır:\ndpm set-device-owner com.securelock.app/.AdminReceiver", null
        ) {}
        row(
            "Gri tonlama izni", Policy.canSecure(this),
            "Bugjaeger kabuğunda çalıştır:\npm grant com.securelock.app android.permission.WRITE_SECURE_SETTINGS", null
        ) {}
        show(v)
    }
}
