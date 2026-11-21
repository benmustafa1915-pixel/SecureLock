package com.securelock.app

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
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
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {
    private var authed = false
    private var grace = 0L
    private var backFn: (() -> Unit)? = null
    private var tickR: Runnable? = null
    private var shownMode = 0
    private val h = Handler(Looper.getMainLooper())
    private val green = Color.parseColor("#66BB6A")
    private val red = Color.parseColor("#E57373")
    private val unitNames = listOf("dk", "saat", "gün", "ay")
    private val unitMs = longArrayOf(60000L, 3600000L, 86400000L, 2592000000L)
    private val relockVals = listOf(0, -1, 1, 5, 15)
    private val relockNames = listOf(
        "Uygulamadan çıkınca", "Ekran kapanınca", "1 dakika sonra", "5 dakika sonra", "15 dakika sonra"
    )

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
    }

    override fun onStart() {
        super.onStart()
        UI.useTheme(this)
        if (Store.isSetup(this) && selfBlockInfo() != null) {
            blockScreen()
        } else if (authed) {
            home()
        } else {
            route()
        }
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
        val d = s / 86400
        val rest = String.format(Locale.US, "%d:%02d:%02d", s % 86400 / 3600, s % 3600 / 60, s % 60)
        return if (d > 0) "$d gün $rest" else rest
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
            .setOnCancelListener { no() }
            .show()
    }

    private fun choose(title: String, opts: List<String>, cur: Int, ok: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(opts.toTypedArray(), cur) { d, i ->
                d.dismiss()
                ok(i)
            }
            .show()
    }

    private fun textDialog(title: String, cur: String, ok: (String) -> Unit) {
        val e = EditText(this)
        e.setText(cur)
        e.setSelection(e.text.length)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(e)
            .setPositiveButton("Kaydet") { _, _ ->
                val s = e.text.toString().trim().take(40)
                ok(if (s.isEmpty()) "Hayır." else s)
            }
            .setNegativeButton("Vazgeç", null)
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

    private fun typeName(t: Int): String = when (t) {
        Store.PIN -> "PIN"
        Store.PATTERN -> "desen"
        else -> "parola"
    }

    private fun gate(id: String, title: String, go: () -> Unit) {
        if (Policy.secLocked(this, id)) lockedScreen(title, id) else go()
    }

    private fun durationRow(sel: IntArray): LinearLayout {
        val row = LinearLayout(this)
        val btns = ArrayList<Button>()
        fun paint() {
            for (j in btns.indices) {
                btns[j].background = UI.round(this, if (j == sel[0]) UI.ACC else UI.CARD)
            }
        }
        for (i in 0..3) {
            val b = UI.button(this, unitNames[i], UI.CARD) {
                sel[0] = i
                paint()
            }
            btns.add(b)
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            lp.setMargins(UI.dp(this, 3), 0, UI.dp(this, 3), 0)
            row.addView(b, lp)
        }
        paint()
        return row
    }

    private fun askDuration(title: String, ok: (Long, String) -> Unit) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(UI.dp(this, 20), UI.dp(this, 8), UI.dp(this, 20), UI.dp(this, 4))
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.hint = "Sayı"
        et.setText("1")
        val sel = intArrayOf(1)
        box.addView(et)
        box.addView(durationRow(sel), UI.lp(this, 10))
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Tamam") { _, _ ->
                val n = et.text.toString().toLongOrNull() ?: 0L
                if (n < 1L || n > 99999L) {
                    toast("1 ile 99999 arası bir sayı yaz")
                } else {
                    ok(n * unitMs[sel[0]], n.toString() + " " + unitNames[sel[0]])
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
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
        col.addView(UI.tv(this, "Doğru şifreyi yazınca otomatik açılır.", 13f, UI.DIM), UI.lp(this, 8))
        var done = false
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        et.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val t = s?.toString() ?: ""
                if (!done && t.isNotEmpty() && Store.checkAdmin(this@MainActivity, t)) {
                    done = true
                    authed = true
                    imm.hideSoftInputFromWindow(et.windowToken, 0)
                    home()
                }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        et.setOnEditorActionListener { _, _, _ ->
            if (!done) {
                toast("Yanlış şifre")
                et.setText("")
            }
            true
        }
        show(v, null)
        et.requestFocus()
        h.postDelayed({ imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT) }, 200)
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

    // ---------- kendine blok ekranlari ----------

    private fun selfBlockInfo(): Pair<String, Long>? {
        if (Policy.mode(this) == 2 && !Store.feat(this, "k_enter")) {
            return Pair("Katı Mod sürüyor. Süre bitince açılır.", Policy.remaining(this))
        }
        val l = Clock.left(this, "self")
        if (l > 0L) return Pair("Kendine koyduğun blok sürüyor.", l)
        return null
    }

    private fun blockScreen() {
        val (v, col) = UI.page(this)
        col.gravity = Gravity.CENTER
        col.addView(UI.tv(this, "Secure Lock kilitli", 26f, UI.TXT, true))
        val why = UI.tv(this, "", 15f, UI.DIM)
        val tm = UI.tv(this, "", 32f, UI.TXT, true)
        col.addView(why, UI.lp(this, 14))
        col.addView(tm, UI.lp(this, 10))
        col.addView(UI.button(this, "Çık") { finish() }, UI.lp(this, 24))
        show(v, { finish() })
        val r = object : Runnable {
            override fun run() {
                val info = selfBlockInfo()
                if (info == null) {
                    route()
                    return
                }
                why.text = info.first
                tm.text = fmt(info.second)
                h.postDelayed(this, 1000)
            }
        }
        tickR = r
        r.run()
    }

    private fun lockedScreen(title: String, id: String) {
        val (v, col) = UI.page(this)
        hdr(col, title) { home() }
        col.addView(UI.tv(this, "Bu bölüm şu an kilitli.", 18f, UI.TXT, true), UI.lp(this, 10))
        val why = UI.tv(this, "", 14f, UI.DIM)
        val tm = UI.tv(this, "", 28f, UI.TXT, true)
        col.addView(why, UI.lp(this, 8))
        col.addView(tm, UI.lp(this, 14))
        show(v)
        val r = object : Runnable {
            override fun run() {
                if (!Policy.secLocked(this@MainActivity, id)) {
                    home()
                    return
                }
                val l = Clock.left(this@MainActivity, "sec_$id")
                if (l > 0L) {
                    why.text = "Kendine koyduğun zamanlayıcı sürüyor."
                    tm.text = fmt(l)
                } else {
                    why.text = "Sıkı veya Katı Mod sürüyor."
                    tm.text = fmt(Policy.remaining(this@MainActivity))
                }
                h.postDelayed(this, 1000)
            }
        }
        tickR = r
        r.run()
    }

    private fun selfScreen() {
        val (v, col) = UI.page(this)
        hdr(col, "Kendime blok") { home() }
        col.addView(
            UI.tv(this, "Seçtiğin bölümü zamanlayıcıyla kilitle. Süre bitmeden hiçbir şifreyle açılmaz. Süre eklenebilir, kısaltılamaz.", 14f, UI.DIM),
            UI.lp(this, 4)
        )
        val items = listOf(
            Pair("self", "Secure Lock'a giriş"),
            Pair("sec_apps", "Uygulama listesi"),
            Pair("sec_custom", "Özelleştirme"),
            Pair("sec_pass", "Şifreler"),
            Pair("sec_stats", "İstatistik")
        )
        val states = ArrayList<TextView>()
        for ((key, title) in items) {
            val st = UI.tv(this, "", 13f, UI.DIM)
            states.add(st)
            val r = UI.card(this)
            r.addView(UI.tv(this, title, 16f))
            r.addView(st, UI.lp(this, 2))
            r.isClickable = true
            r.setOnClickListener {
                askDuration("$title: ne kadar kilitlensin?") { ms, txt ->
                    confirm("Geri dönüş yok", "$title $txt boyunca kilitli kalacak. Kısaltılamaz. Devam edilsin mi?") {
                        Clock.add(this, key, ms)
                        if (key == "self") {
                            authed = false
                            finish()
                        } else {
                            selfScreen()
                        }
                    }
                }
            }
            col.addView(r, UI.lp(this, 10))
        }
        show(v)
        val run = object : Runnable {
            override fun run() {
                for (i in items.indices) {
                    val l = Clock.left(this@MainActivity, items[i].first)
                    states[i].text = if (l > 0L) "Kilitli, kalan: " + fmt(l) else "Serbest (dokun, kilit koy)"
                }
                h.postDelayed(this, 1000)
            }
        }
        tickR = run
        run.run()
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
        col.addView(UI.button(this, "Engellenecek uygulamalar (" + Store.blocked(this).size + ")") {
            gate("apps", "Uygulama listesi") { appsScreen() }
        }, UI.lp(this, 8))
        col.addView(UI.button(this, "Kendime blok") { selfScreen() }, UI.lp(this, 8))
        col.addView(UI.button(this, "Özelleştirme") { gate("custom", "Özelleştirme") { customScreen() } }, UI.lp(this, 8))
        col.addView(UI.button(this, "Şifreler") { gate("pass", "Şifreler") { passScreen() } }, UI.lp(this, 8))
        col.addView(UI.button(this, "İstatistik") { gate("stats", "İstatistik") { statsScreen() } }, UI.lp(this, 8))
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

    private fun hasTagFor(mode: Int): Boolean {
        val mask = if (mode == 1) 2 else 6
        return Store.blocked(this).any { (Store.tags(this, it) and mask) != 0 }
    }

    private fun sessionScreen(mode: Int) {
        val (v, col) = UI.page(this)
        hdr(col, if (mode == 1) "Sıkı Mod" else "Katı Mod") { home() }
        val info = if (mode == 1) {
            "Zaman kilidi: süre bitene kadar \"Sıkı\" işaretli uygulamalar hiçbir şifreyle açılmaz. Zamanlayıcı bozulmasın diye Erişilebilirlik ve Tarih ve saat sayfaları engellenir."
        } else {
            "Demir yumruk: Sıkı Mod'un hepsi, ek olarak Cihaz yöneticisi, Secure Lock bilgisi ve kaldırma sayfaları engellenir, ceza sertleşir. Ayrıntıları Özelleştirme'den ayarlarsın."
        }
        col.addView(UI.tv(this, info, 14f, UI.DIM), UI.lp(this, 4))
        if (mode == 2 && !Policy.adminActive(this)) {
            col.addView(
                UI.tv(this, "Cihaz yöneticisi kapalı: Katı Mod başlatılamaz. Kurulum ve izinler ekranından aç.", 14f, red),
                UI.lp(this, 8)
            )
        }
        val num = UI.input(this, "Süre (sayı)", false)
        num.inputType = InputType.TYPE_CLASS_NUMBER
        num.setText("1")
        col.addView(num, UI.lp(this, 16))
        val sel = intArrayOf(1)
        col.addView(durationRow(sel), UI.lp(this, 8))
        val pw = UI.input(this, "Katı Mod şifresi", false)
        col.addView(pw, UI.lp(this, 12))
        col.addView(UI.button(this, "Başlat", if (mode == 1) UI.ACC else UI.RED) {
            val n = num.text.toString().toLongOrNull() ?: 0L
            val unit = sel[0]
            when {
                n < 1L || n > 99999L -> toast("Süre için 1 ile 99999 arası bir sayı yaz")
                !Store.checkStrict(this, pw.text.toString()) -> toast("Katı Mod şifresi yanlış")
                !hasTagFor(mode) -> toast("Bu mod için işaretli uygulama yok. Uygulama listesinden ekle.")
                !accOn() -> toast("Erişilebilirlik izni kapalı: Kurulum ve izinler")
                mode == 2 && !Policy.adminActive(this) -> toast("Katı Mod için Cihaz yöneticisi açık olmalı")
                else -> confirm("Geri dönüş yok", "$n ${unitNames[unit]} boyunca hiçbir şifreyle bozulamaz. Başlatılsın mı?") {
                    Policy.start(this, mode, n * unitMs[unit])
                    if (mode == 2 && !Store.feat(this, "k_enter")) {
                        authed = false
                        finish()
                    } else {
                        home()
                    }
                }
            }
        }, UI.lp(this, 16))
        show(v)
    }

    // ---------- uygulama listesi ----------

    private fun loadApps(showSys: Boolean): List<Pair<String, String>> {
        val pm = packageManager
        val prot = Policy.protectedPkgs(this)
        val res = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        val infos: List<ApplicationInfo> = try {
            pm.getInstalledApplications(0)
        } catch (e: Exception) {
            emptyList<ApplicationInfo>()
        }
        for (ai in infos) {
            val pkg = ai.packageName
            if (prot.contains(pkg) || seen.contains(pkg)) continue
            val launchable = pm.getLaunchIntentForPackage(pkg) != null
            val isSys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!launchable && !(showSys && isSys)) continue
            seen.add(pkg)
            res.add(Pair(pkg, ai.loadLabel(pm).toString()))
        }
        try {
            val ris = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            for (ri in ris) {
                val pkg = ri.activityInfo.packageName
                if (prot.contains(pkg) || seen.contains(pkg)) continue
                seen.add(pkg)
                res.add(Pair(pkg, ri.loadLabel(pm).toString()))
            }
        } catch (e: Exception) {
        }
        return res.sortedBy { it.second.lowercase() }
    }

    private fun subText(pkg: String): String {
        val t = Store.tags(this, pkg)
        if (t == 0) return "Engelsiz"
        val parts = ArrayList<String>()
        if ((t and 1) != 0) parts.add("Normal")
        if ((t and 2) != 0) parts.add("Sıkı")
        if ((t and 4) != 0) parts.add("Katı")
        var s = parts.joinToString(" + ")
        if (Store.hasAppLock(this, pkg)) s += "  |  özel " + typeName(Store.appType(this, pkg))
        return s
    }

    private fun appsScreen() {
        val showSys = Store.feat(this, "sys")
        val apps = loadApps(showSys)
        val keep = Policy.mode(this) != 0 && Store.feat(this, "s_keep")
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(UI.BG)
        root.setPadding(UI.dp(this, 14), UI.dp(this, 20), UI.dp(this, 14), UI.dp(this, 10))
        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(UI.button(this, "Geri") { home() })
        top.addView(UI.tv(this, "  Uygulamalar", 20f, UI.TXT, true))
        root.addView(top)
        val hint = if (keep) {
            "Oturum sürüyor: uygulama eklenebilir, çıkarılamaz."
        } else {
            "Anahtar üç modu birden açar. Uygulamaya basılı tut: modları ve şifre türünü seç."
        }
        root.addView(UI.tv(this, hint, 12f, UI.DIM), UI.lp(this, 6))
        root.addView(UI.switchRow(this, "Sistem uygulamalarını göster", showSys) { on ->
            Store.setFeat(this, "sys", on)
            h.post { appsScreen() }
        }, UI.lp(this, 6))
        val q = UI.input(this, "Ara")
        q.inputType = InputType.TYPE_CLASS_TEXT
        root.addView(q, UI.lp(this, 4))
        val sv = ScrollView(this)
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        sv.addView(list)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        val rows = ArrayList<Pair<View, String>>()
        for ((pkg, name) in apps) {
            val row = UI.card(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val left = LinearLayout(this)
            left.orientation = LinearLayout.VERTICAL
            val subTv = UI.tv(this, subText(pkg), 12f, UI.DIM)
            left.addView(UI.tv(this, name, 16f))
            left.addView(subTv)
            row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            val sw = Switch(this)
            sw.isChecked = Store.tags(this, pkg) != 0
            if (keep && sw.isChecked) sw.isEnabled = false
            sw.setOnCheckedChangeListener { b, on ->
                if (on) {
                    if (Store.tags(this, pkg) == 0) Store.setTags(this, pkg, 7)
                } else if (keep) {
                    b.isChecked = true
                } else {
                    Store.setTags(this, pkg, 0)
                }
                subTv.text = subText(pkg)
            }
            row.addView(sw)
            row.setOnClickListener { if (sw.isEnabled) sw.toggle() }
            row.setOnLongClickListener {
                modesDialog(pkg, name, keep)
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

    private fun modesDialog(pkg: String, name: String, keep: Boolean) {
        val old = Store.tags(this, pkg)
        val st = booleanArrayOf((old and 1) != 0, (old and 2) != 0, (old and 4) != 0)
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMultiChoiceItems(
                arrayOf("Normal kilit (şifreyle açılır)", "Sıkı Mod'da engelle", "Katı Mod'da engelle"), st
            ) { _, i, c -> st[i] = c }
            .setPositiveButton("Kaydet") { _, _ ->
                var t = (if (st[0]) 1 else 0) or (if (st[1]) 2 else 0) or (if (st[2]) 4 else 0)
                if (keep && (t or old) != t) {
                    t = t or old
                    toast("Oturum sürerken mod çıkarılamaz")
                }
                Store.setTags(this, pkg, t)
                appsScreen()
            }
            .setNeutralButton("Özel şifre") { _, _ ->
                if (Policy.secLocked(this, "pass")) toast("Şifreler bölümü kilitli") else appLockPicker(pkg, name)
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun appLockPicker(pkg: String, name: String) {
        val (v, col) = UI.page(this)
        hdr(col, name) { appsScreen() }
        col.addView(
            UI.tv(this, "Bu uygulamaya özel kilit türü. Seçmezsen genel kilit kullanılır.", 14f, UI.DIM),
            UI.lp(this, 4)
        )
        col.addView(UI.button(this, "Genel kilidi kullan") {
            Store.clearAppLock(this, pkg)
            toast("Kaydedildi")
            appsScreen()
        }, UI.lp(this, 16))
        col.addView(UI.button(this, "PIN (4-8 rakam)") {
            askText("PIN belirle", "4 ile 8 rakam", true, 4, { appLockPicker(pkg, name) }) { s ->
                Store.setAppLock(this, pkg, Store.PIN, s)
                toast("Kaydedildi")
                appsScreen()
            }
        }, UI.lp(this, 8))
        col.addView(UI.button(this, "Parola (telefon klavyesi)") {
            askText("Parola belirle", "En az 4 karakter", false, 4, { appLockPicker(pkg, name) }) { s ->
                Store.setAppLock(this, pkg, Store.PASS, s)
                toast("Kaydedildi")
                appsScreen()
            }
        }, UI.lp(this, 8))
        col.addView(UI.button(this, "Desen") {
            askPattern("Desen belirle", { appLockPicker(pkg, name) }) { s ->
                Store.setAppLock(this, pkg, Store.PATTERN, s)
                toast("Kaydedildi")
                appsScreen()
            }
        }, UI.lp(this, 8))
        show(v) { appsScreen() }
    }

    // ---------- ozellestirme ----------

    private fun featRow(key: String, text: String): LinearLayout =
        UI.switchRow(this, text, Store.feat(this, key)) { on -> Store.setFeat(this, key, on) }

    private fun customScreen() {
        val (v, col) = UI.page(this)
        hdr(col, "Özelleştirme") { home() }

        col.addView(UI.label(this, "Görünüm"), UI.lp(this, 12))
        col.addView(UI.rowBtn(this, "Tema rengi", UI.accentNames[Store.theme(this)]) {
            choose("Tema rengi", UI.accentNames, Store.theme(this)) { i ->
                Store.setInt(this, "theme", i)
                UI.useTheme(this)
                customScreen()
            }
        }, UI.lp(this, 6))

        col.addView(UI.label(this, "Hayalet Mod"), UI.lp(this, 18))
        val secs = listOf(3, 5, 10, 15)
        col.addView(UI.rowBtn(this, "Geri sayım süresi", Store.ghostSecs(this).toString() + " saniye") {
            choose("Geri sayım", secs.map { it.toString() + " saniye" }, maxOf(0, secs.indexOf(Store.ghostSecs(this)))) { i ->
                Store.setInt(this, "gsec", secs[i])
                customScreen()
            }
        }, UI.lp(this, 6))
        col.addView(UI.rowBtn(this, "Ekran mesajı", Store.ghostMsg(this)) {
            textDialog("Hayalet ekran mesajı", Store.ghostMsg(this)) { s ->
                Store.setStr(this, "gmsg", s)
                customScreen()
            }
        }, UI.lp(this, 6))
        val strikes = listOf(2, 3, 4, 5)
        col.addView(UI.rowBtn(this, "Ceza için deneme sayısı", Store.strikeN(this).toString() + " deneme (2 dakika içinde)") {
            choose("Kaç denemede ekran kilitlensin?", strikes.map { it.toString() + " deneme" }, maxOf(0, strikes.indexOf(Store.strikeN(this)))) { i ->
                Store.setInt(this, "strike", strikes[i])
                customScreen()
            }
        }, UI.lp(this, 6))

        col.addView(UI.label(this, "Normal Mod"), UI.lp(this, 18))
        val ri = maxOf(0, relockVals.indexOf(Store.relock(this)))
        col.addView(UI.rowBtn(this, "Yeniden kilitleme", relockNames[ri]) {
            choose("Açılan uygulama ne zaman tekrar kilitlensin?", relockNames, ri) { i ->
                Store.setInt(this, "relock", relockVals[i])
                customScreen()
            }
        }, UI.lp(this, 6))

        col.addView(UI.label(this, "Sıkı Mod özellikleri"), UI.lp(this, 18))
        for (t in Feat.siki) col.addView(featRow(t.first, t.second), UI.lp(this, 6))

        col.addView(UI.label(this, "Katı Mod özellikleri"), UI.lp(this, 18))
        for (t in Feat.kati) col.addView(featRow(t.first, t.second), UI.lp(this, 6))

        col.addView(UI.label(this, "Diğer"), UI.lp(this, 18))
        col.addView(featRow("gray", "Oturumda gri tonlama"), UI.lp(this, 6))
        col.addView(UI.tv(this, "Gri tonlama için ayrıca izin gerekir (Kurulum ve izinler).", 12f, UI.DIM), UI.lp(this, 4))
        col.addView(UI.switchRow(this, "Simgeyi ana menüden gizle", Store.feat(this, "hide")) { on ->
            if (on) {
                confirm(
                    "Simge gizlensin mi?",
                    "Simge ana menüden kalkar. Açmak için telefon uygulamasında *#*#7328#*#* tuşla. Önce kodun çalıştığından emin ol. Çalışmazsa Ayarlar > Erişilebilirlik > Secure Lock yolundan girersin.",
                    { h.post { customScreen() } }
                ) {
                    Store.setFeat(this, "hide", true)
                    iconHidden(true)
                }
            } else {
                Store.setFeat(this, "hide", false)
                iconHidden(false)
            }
        }, UI.lp(this, 12))
        show(v)
    }

    // ---------- sifreler ----------

    private fun passScreen() {
        val (v, col) = UI.page(this)
        hdr(col, "Şifreler") { home() }
        col.addView(UI.tv(this, "Kilit türü: " + typeName(Store.lockType(this)), 16f, UI.DIM), UI.lp(this, 10))
        col.addView(UI.button(this, "Kilit şifresini / türünü değiştir") {
            pickLock({ passScreen() }) { t, s ->
                Store.setLock(this, t, s)
                toast("Kaydedildi")
                passScreen()
            }
        }, UI.lp(this, 8))
        col.addView(UI.button(this, "Yönetici şifresini değiştir") {
            askText("Yeni yönetici şifresi", "Kurtarma yöntemi yoktur.", false, 4, { passScreen() }) { s ->
                Store.setAdmin(this, s)
                toast("Kaydedildi")
                passScreen()
            }
        }, UI.lp(this, 8))
        col.addView(UI.button(this, "Katı Mod şifresini değiştir") {
            askText("Yeni Katı Mod şifresi", "Kurtarma yöntemi yoktur.", false, 4, { passScreen() }) { s ->
                Store.setStrict(this, s)
                toast("Kaydedildi")
                passScreen()
            }
        }, UI.lp(this, 8))
        col.addView(
            UI.tv(this, "Uygulamaya özel şifre: Uygulama listesinde uygulamaya basılı tut.", 13f, UI.DIM),
            UI.lp(this, 14)
        )
        show(v)
    }

    // ---------- istatistik ----------

    private fun statsScreen() {
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
        val pmgr = getSystemService(POWER_SERVICE) as PowerManager
        row("Pil kısıtlaması yok", pmgr.isIgnoringBatteryOptimizations(packageName), "", "İzin ver") {
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        row(
            "Cihaz yöneticisi (Katı Mod için)", Policy.adminActive(this),
            "Açıkken Secure Lock Ayarlar'dan kaldırılamaz. Bugjaeger gerekmez.", "Etkinleştir"
        ) {
            open(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Policy.admin(this))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Katı Mod'da Secure Lock'un kaldırılmasını engellemek için gerekli.")
            )
        }
        row(
            "Gri tonlama izni (isteğe bağlı)", Policy.canSecure(this),
            "Sadece gri tonlama için. Bugjaeger kabuğunda çalıştır:\npm grant com.securelock.app android.permission.WRITE_SECURE_SETTINGS",
            null
        ) {}
        if (Policy.adminActive(this)) {
            col.addView(UI.button(this, "Cihaz yöneticisini kapat") {
                if (Policy.mode(this) != 0) {
                    toast("Oturum sürerken kapatılamaz")
                } else {
                    confirm("Cihaz yöneticisi kapatılsın mı?", "Katı Mod başlatılamaz ve uygulama normal şekilde kaldırılabilir olur.") {
                        try {
                            Policy.dpm(this).removeActiveAdmin(Policy.admin(this))
                        } catch (e: Exception) {
                            toast("Olmadı")
                        }
                        perms()
                    }
                }
            }, UI.lp(this, 14))
        }
        show(v)
    }
}
