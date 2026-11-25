package com.securelock.app

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DeviceAdminInfo
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
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
import android.view.ViewGroup
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

class MainActivity : Activity() {
    private var authed = false
    private var grace = 0L
    private var backFn: (() -> Unit)? = null
    private var tickR: Runnable? = null
    private var shownMode = 0
    private var pendingTv: TextView? = null
    private var pendingKey = ""
    private var pendingRefresh: (() -> Unit)? = null
    private val h = Handler(Looper.getMainLooper())
    private val green = Color.parseColor("#66BB6A")
    private val red = Color.parseColor("#E57373")
    private val unitNames = listOf("dk", "saat", "gün", "ay")
    private val unitMs = longArrayOf(60000L, 3600000L, 86400000L, 2592000000L)
    private val relockVals = listOf(0, -1, 1, 5, 15)
    private val relockNames = listOf(
        "Uygulamadan çıkınca", "Ekran kapanınca", "1 dakika sonra", "5 dakika sonra", "15 dakika sonra"
    )
    private val sugNames = listOf(
        "15 dakika", "30 dakika", "1 saat", "2 saat", "4 saat", "8 saat",
        "1 gün", "3 gün", "1 hafta", "1 ay", "Özel süre..."
    )
    private val sugMs = longArrayOf(
        900000L, 1800000L, 3600000L, 7200000L, 14400000L, 28800000L,
        86400000L, 259200000L, 604800000L, 2592000000L, 0L
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
        testResult()
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

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77) {
            toast(if (Policy.adminActive(this)) "Cihaz yöneticisi etkin" else "Cihaz yöneticisi etkinleştirilmedi")
            if (authed) perms()
        }
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

    private fun fmt(ms: Long): String = fmtLeft(ms)

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

    private fun inputDialog(title: String, hint: String, cur: String, ok: (String) -> Unit) {
        val e = EditText(this)
        e.hint = hint
        e.setText(cur)
        e.setSelection(e.text.length)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(e)
            .setPositiveButton("Tamam") { _, _ ->
                val s = e.text.toString().trim()
                if (s.isEmpty()) toast("Boş bırakılamaz") else ok(s)
            }
            .setNegativeButton("Vazgeç", null)
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

    private fun askSecret(title: String, ok: (String) -> Unit) {
        val e = EditText(this)
        e.hint = "Şifre"
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(e)
            .setPositiveButton("Tamam") { _, _ -> ok(e.text.toString()) }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun poolDialog() {
        val e = EditText(this)
        e.setText(Store.p(this).getString("pool", "") ?: "")
        e.hint = "Her satıra bir cümle"
        e.minLines = 4
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        AlertDialog.Builder(this)
            .setTitle("Cümle havuzu")
            .setMessage("Engel ekranlarında bunlardan biri rastgele gösterilir. Her satıra bir cümle yaz.")
            .setView(e)
            .setPositiveButton("Kaydet") { _, _ ->
                Store.setStr(this, "pool", e.text.toString().take(2000))
                customScreen()
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

    // Stay Focused gibi öneri süreleri
    private fun pickDuration(title: String, ok: (Long, String) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(sugNames.toTypedArray()) { _, i ->
                if (i == sugNames.size - 1) askDuration(title, ok) else ok(sugMs[i], sugNames[i])
            }
            .show()
    }

    // ---------- giris / kurulum ----------

    private fun route() {
        if (Store.isSetup(this)) login() else setup()
    }

    private fun setup() {
        askText("Yönetici şifresi", "Uygulamaya her girişte sorulur. Kurtarma yöntemi yoktur, unutma.", false, 4, null) { admin ->
            askText("Katı Mod şifresi", "Katı koyarken ve kaldırırken, Sıkı Mod başlatırken, kaldırma izni verirken sorulur. Kurtarma yöntemi yoktur.", false, 4, null) { strict ->
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
        val l = Clock.left(this, "self")
        if (l > 0L) return Pair("Kendine koyduğun blok sürüyor.", l)
        return null
    }

    private fun blockScreen() {
        pendingTv = null
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
        pendingTv = null
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
                    why.text = "Sıkı Mod oturumu sürüyor."
                    tm.text = fmt(Policy.remaining(this@MainActivity))
                }
                h.postDelayed(this, 1000)
            }
        }
        tickR = r
        r.run()
    }

    private fun selfScreen() {
        pendingTv = null
        val (v, col) = UI.page(this)
        hdr(col, "Kendime blok") { home() }
        col.addView(
            UI.tv(this, "Seçtiğin bölümü zamanlayıcıyla kilitle. Süre bitmeden hiçbir şifreyle açılmaz. Süre eklenebilir, kısaltılamaz.", 14f, UI.DIM),
            UI.lp(this, 4)
        )
        val items = listOf(
            Pair("self", "Secure Lock'a giriş"),
            Pair("sec_apps", "Uygulama listesi"),
            Pair("sec_pages", "Telefon ayar sayfaları"),
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
                pickDuration("$title: ne kadar kilitlensin?") { ms, txt ->
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

    // ---------- Katı (kalici) ----------

    private fun tickPending(): Boolean {
        val tv = pendingTv ?: return false
        if (!Store.katiOn(this, pendingKey)) {
            pendingRefresh?.invoke()
            return true
        }
        tv.text = "Kaldırma bekleniyor, kalan: " + fmt(Store.katiExitLeft(this, pendingKey))
        return false
    }

    private fun katiEnable(key: String, title: String, done: () -> Unit) {
        confirm(
            "Katı: kalıcı",
            "$title kalıcı olacak, süre yok, şifrenle bile açılmaz. Kaldırmak için Katı şifresi ve 24 saat bekleme gerekir. Devam edilsin mi?"
        ) {
            askSecret("Katı Mod şifresi") { s ->
                if (Store.checkStrict(this, s)) {
                    Store.setKati(this, key, true)
                    done()
                } else {
                    toast("Katı Mod şifresi yanlış")
                }
            }
        }
    }

    private fun katiExitControls(box: LinearLayout, key: String, title: String, refresh: () -> Unit) {
        if (Store.katiExitPending(this, key)) {
            val st = UI.tv(this, "Kaldırma bekleniyor, kalan: " + fmt(Store.katiExitLeft(this, key)), 13f, UI.TXT, true)
            box.addView(st, UI.lp(this, 8))
            pendingTv = st
            pendingKey = key
            pendingRefresh = refresh
            box.addView(UI.button(this, "Vazgeç, Katı kalsın") {
                Store.cancelKatiExit(this, key)
                refresh()
            }, UI.lp(this, 8))
        } else {
            box.addView(UI.button(this, "Katı'yı kaldır (24 saat beklemeli)") {
                askSecret("Katı Mod şifresi") { s ->
                    if (Store.checkStrict(this, s)) {
                        confirm("Kaldırma başlasın mı?", "$title 24 saat sonra kalkar. O süre boyunca Katı sürer, istersen vazgeçebilirsin.") {
                            Store.requestKatiExit(this, key)
                            refresh()
                        }
                    } else {
                        toast("Katı Mod şifresi yanlış")
                    }
                }
            }, UI.lp(this, 8))
        }
    }

    private fun katiCard(col: LinearLayout) {
        val on = Store.katiOn(this, "sys")
        val c = UI.card(this)
        c.addView(UI.tv(this, "Katı Koruma: " + (if (on) "Açık" else "Kapalı"), 17f, if (on) green else UI.TXT, true))
        val info = if (on) {
            "Secure Lock kaldırılamaz. Erişilebilirlik, Tarih ve saat ile Cihaz yöneticisi sayfalarına girilemez. Süre yok, kalıcı."
        } else {
            "Açınca Secure Lock kaldırılamaz, Erişilebilirlik ile Tarih ve saat sayfalarına girilemez. Süre yok, kalıcı."
        }
        c.addView(UI.tv(this, info, 13f, UI.DIM), UI.lp(this, 4))
        if (!Policy.adminActive(this)) {
            c.addView(
                UI.tv(this, "Cihaz yöneticisi kapalı. İstersen Kurulum ve izinler'den etkinleştir, ek koruma sağlar.", 12f, UI.DIM),
                UI.lp(this, 6)
            )
        }
        if (on) {
            katiExitControls(c, "sys", "Katı Koruma") { home() }
        } else {
            c.addView(UI.button(this, "Katı Korumayı aç", UI.RED) {
                if (!accOn()) toast("Erişilebilirlik izni kapalı: Kurulum ve izinler") else katiEnable("sys", "Katı Koruma") { home() }
            }, UI.lp(this, 10))
        }
        col.addView(c, UI.lp(this, 10))
    }

    // ---------- Kaldirma Engeli ----------

    private fun uninText(): String {
        val ul = Policy.ulockLeft(this)
        val uo = Clock.left(this, "unin")
        return when {
            Policy.katiProtect(this) -> "Katı Koruma açık: kaldırma kapalı."
            Policy.mode(this) != 0 -> "Sıkı Mod oturumu sürüyor: kaldırma kapalı."
            ul > 0L -> "Kilitli: süre bitene kadar izin verilemez. Kalan: " + fmt(ul)
            uo > 0L -> "Kaldırma izni AÇIK, kalan: " + fmt(uo)
            else -> "Açık: Ayarlar'dan kaldırılamaz. Süre koyarsan süre dolana kadar izin de verilemez."
        }
    }

    private fun uninCard(col: LinearLayout): TextView {
        val c = UI.card(this)
        c.addView(UI.tv(this, "Kaldırma Engeli", 17f, UI.TXT, true))
        val st = UI.tv(this, uninText(), 13f, UI.DIM)
        c.addView(st, UI.lp(this, 4))
        c.addView(UI.button(this, "Süre koy (kilitle)") {
            pickDuration("Kaldırma engeli süresi") { ms, txt ->
                confirm("Geri dönüş yok", "Secure Lock $txt boyunca kaldırılamaz ve kaldırma izni verilemez. Kısaltılamaz. Devam edilsin mi?") {
                    Store.setUninLock(this, ms)
                    home()
                }
            }
        }, UI.lp(this, 10))
        val opened = Policy.uninstallOpen(this)
        if (opened) {
            c.addView(UI.button(this, "Şimdi kaldır", UI.RED) {
                confirm("Secure Lock kaldırılsın mı?", "Tüm korumalar ve ayarlar gider.") {
                    open(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
                }
            }, UI.lp(this, 8))
            c.addView(UI.button(this, "Kaldırma iznini kapat") {
                Clock.clear(this, "unin")
                home()
            }, UI.lp(this, 8))
        } else {
            c.addView(UI.button(this, "Kaldırmaya izin ver", UI.RED) {
                when {
                    Policy.mode(this) != 0 -> toast("Sıkı Mod oturumu sürerken verilemez")
                    Policy.katiProtect(this) -> toast("Katı Koruma açıkken verilemez. Önce Katı Korumayı kaldır (24 saat bekleme).")
                    Policy.ulockLeft(this) > 0L -> toast("Kilit sürüyor, izin verilemez. Kalan: " + fmt(Policy.ulockLeft(this)))
                    else -> askSecret("Katı Mod şifresi") { s ->
                        if (Store.checkStrict(this, s)) {
                            Clock.add(this, "unin", Store.uninMin(this) * 60000L)
                            home()
                        } else {
                            toast("Katı Mod şifresi yanlış")
                        }
                    }
                }
            }, UI.lp(this, 8))
        }
        c.addView(UI.button(this, "Korumayı dene") { protTest() }, UI.lp(this, 8))
        c.addView(
            UI.tv(this, "Korumayı dene: Ayarlar'daki Secure Lock sayfasını açar. Seni geri attıysa korumanın çalıştığını bu ekrana dönünce söylerim.", 12f, UI.DIM),
            UI.lp(this, 6)
        )
        col.addView(c, UI.lp(this, 10))
        return st
    }

    private fun protTest() {
        if (!accOn()) {
            toast("Erişilebilirlik izni kapalı: koruma çalışmaz")
            return
        }
        if (Policy.uninstallOpen(this)) {
            toast("Kaldırma izni açık, koruma kapalı. Önce izni kapat.")
            return
        }
        Store.p(this).edit().putLong("test_at", System.currentTimeMillis()).apply()
        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun testResult() {
        val ta = Store.p(this).getLong("test_at", 0L)
        if (ta == 0L) return
        Store.p(this).edit().remove("test_at").apply()
        if (System.currentTimeMillis() - ta > 600000L) return
        val lg = Store.p(this).getLong("last_guard", 0L)
        toast(
            if (lg >= ta) "Koruma çalışıyor: Ayarlar sayfasından geri atıldın."
            else "Koruma ÇALIŞMADI: sayfa açık kaldı. Ekran görüntüsünü at."
        )
    }

    // ---------- ogeye ozel seviye ----------

    private fun itemBlockedScreen(key: String, title: String) {
        pendingTv = null
        val (v, col) = UI.page(this)
        hdr(col, title) { home() }
        col.addView(UI.tv(this, "Bu öğe şu an kilitli.", 18f, UI.TXT, true), UI.lp(this, 10))
        val why = UI.tv(this, "", 14f, UI.DIM)
        val tm = UI.tv(this, "", 28f, UI.TXT, true)
        col.addView(why, UI.lp(this, 8))
        col.addView(tm, UI.lp(this, 14))
        show(v)
        val r = object : Runnable {
            override fun run() {
                val b = Policy.blockInfo(this@MainActivity, key)
                if (b.kind < 2) {
                    home()
                    return
                }
                if (b.kind == 3) {
                    why.text = "Katı: kalıcı, şifreni bilsen de açılmaz. Kaldırmak için öğeye basılı tut."
                    tm.text = ""
                } else {
                    why.text = "Sıkı: süre dolana kadar açılmaz."
                    tm.text = fmt(b.left)
                }
                h.postDelayed(this, 1000)
            }
        }
        tickR = r
        r.run()
    }

    // Uygulama içi bir düğmeye basınca: Katı/Sıkı ise engel ekranı, Normal ise yönetici şifresi
    private fun guarded(key: String, title: String, go: () -> Unit) {
        val k = "ui:$key"
        val b = Policy.blockInfo(this, k)
        if (b.kind >= 2) {
            itemBlockedScreen(k, title)
        } else if (b.kind == 1) {
            askSecret("$title: yönetici şifresi") { s ->
                if (Store.checkAdmin(this, s)) go() else toast("Yanlış şifre")
            }
        } else {
            go()
        }
    }

    private fun optRow(back: () -> Unit, key: String, title: String, value: String, act: () -> Unit): View {
        val v = UI.rowBtn(this, title, value) { guarded(key, title, act) }
        v.setOnLongClickListener {
            itemScreen("ui:$key", title, 2, back)
            true
        }
        return v
    }

    private fun optBtn(
        back: () -> Unit, key: String, title: String, label: String = title,
        color: Int = UI.CARD, act: () -> Unit
    ): View {
        val b = UI.button(this, label, color) { guarded(key, title, act) }
        b.setOnLongClickListener {
            itemScreen("ui:$key", title, 2, back)
            true
        }
        return b
    }

    private fun optSwitch(
        back: () -> Unit, key: String, text: String, on: Boolean, change: (Boolean) -> Unit
    ): View {
        val r = UI.card(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.addView(UI.tv(this, text, 16f), LinearLayout.LayoutParams(0, -2, 1f))
        val s = Switch(this)
        s.isChecked = on
        var rev = false
        s.setOnCheckedChangeListener { b, nv ->
            if (!rev) {
                val k = "ui:$key"
                val blk = Policy.blockInfo(this, k)
                if (blk.kind >= 2) {
                    rev = true
                    b.isChecked = !nv
                    rev = false
                    itemBlockedScreen(k, text)
                } else if (blk.kind == 1) {
                    rev = true
                    b.isChecked = !nv
                    rev = false
                    askSecret("$text: yönetici şifresi") { p ->
                        if (Store.checkAdmin(this, p)) {
                            change(nv)
                            rev = true
                            b.isChecked = nv
                            rev = false
                        } else {
                            toast("Yanlış şifre")
                        }
                    }
                } else {
                    change(nv)
                }
            }
        }
        val lc = View.OnLongClickListener {
            itemScreen("ui:$key", text, 2, back)
            true
        }
        s.setOnLongClickListener(lc)
        r.setOnLongClickListener(lc)
        r.setOnClickListener { s.toggle() }
        r.addView(s)
        return r
    }

    // kind: 0 uygulama, 1 telefon ayar sayfası, 2 uygulama içi düğme, 3 uygulama içi bölüm
    private fun itemScreen(key: String, title: String, kind: Int, back: () -> Unit) {
        pendingTv = null
        val refresh: () -> Unit = { itemScreen(key, title, kind, back) }
        val (v, col) = UI.page(this)
        hdr(col, title, back)
        val stateTv = UI.tv(this, "", 15f, UI.TXT, true)
        col.addView(stateTv, UI.lp(this, 6))
        col.addView(UI.tv(this, "Bu öğe için bir seviye seç. Diğer öğelerden bağımsızdır.", 12f, UI.DIM), UI.lp(this, 2))

        val katiOn = Store.katiOn(this, key)
        val sLeft = Clock.left(this, "is_$key")

        fun level(name: String, info: String, act: () -> Unit) {
            val c = UI.card(this)
            c.addView(UI.tv(this, name, 16f, UI.TXT, true))
            c.addView(UI.tv(this, info, 12f, UI.DIM), UI.lp(this, 2))
            c.isClickable = true
            c.setOnClickListener { act() }
            col.addView(c, UI.lp(this, 8))
        }

        level("Serbest", "Kısıtlama yok.") {
            if (katiOn) {
                toast("Katı'yı kaldırmak için aşağıdaki düğmeyi kullan")
            } else if (sLeft > 0L) {
                toast("Sıkı süre bitmeden kaldırılamaz")
            } else {
                Store.setTags(this, key, 0)
                refresh()
            }
        }
        level("Normal", "Şifreyle açılır.") {
            if (katiOn) {
                toast("Katı'yı kaldırmak için aşağıdaki düğmeyi kullan")
            } else {
                Store.setTags(this, key, Store.tags(this, key) or 1)
                refresh()
            }
        }
        level("Sıkı (süreli)", "Süre dolana kadar şifre işe yaramaz. Engel ekranında kalan süre akar. Süre bitince kendiliğinden kalkar.") {
            if (katiOn) {
                toast("Zaten Katı")
            } else {
                pickDuration("$title: Sıkı süre") { ms, txt ->
                    confirm("Geri dönüş yok", "$title $txt boyunca engelli kalacak. Kısaltılamaz. Devam edilsin mi?") {
                        Clock.add(this, "is_$key", ms)
                        refresh()
                    }
                }
            }
        }
        level("Katı (kalıcı)", "Süre yok, şifreyle açılmaz. Engel ekranında 3-2-1 geri sayım olur. Kaldırmak için Katı şifresi ve 24 saat bekleme gerekir.") {
            if (katiOn) toast("Zaten Katı") else katiEnable(key, title) { refresh() }
        }

        col.addView(UI.switchRow(this, "Sıkı Mod oturumuna dahil (ders programı)", (Store.tags(this, key) and 2) != 0) { nv ->
            val cur = Store.tags(this, key)
            val keep = Policy.mode(this) != 0 && Store.feat(this, "s_keep")
            if (keep && !nv) {
                toast("Oturum sürerken çıkarılamaz")
                h.post { refresh() }
            } else {
                Store.setTags(this, key, if (nv) (cur or 2) else (cur and 2.inv()))
            }
        }, UI.lp(this, 14))

        if (katiOn) {
            val c = UI.card(this)
            c.addView(UI.tv(this, "Katı kaldırma", 15f, UI.TXT, true))
            katiExitControls(c, key, title, refresh)
            col.addView(c, UI.lp(this, 12))
        }

        if (kind == 0) {
            val cur = if (Store.hasAppLock(this, key)) "özel " + typeName(Store.appType(this, key)) else "genel kilit"
            col.addView(UI.label(this, "Şifre"), UI.lp(this, 18))
            col.addView(UI.rowBtn(this, "Bu uygulamaya özel şifre türü", cur) {
                if (Policy.secLocked(this, "pass")) toast("Şifreler bölümü kilitli") else appLockPicker(key, title)
            }, UI.lp(this, 6))
        }

        if (kind == 3) {
            val s = Sections.all(this).firstOrNull { "sc:" + it.id == key }
            if (s != null) {
                col.addView(UI.label(this, "Bölüm tanıma"), UI.lp(this, 18))
                col.addView(UI.rowBtn(this, "Tanıma kuralları", "Uygulama: " + s.pkg) {
                    rulesDialog(s) { refresh() }
                }, UI.lp(this, 6))
                if (Sections.isCustom(s.id)) {
                    col.addView(UI.button(this, "Bu bölümü sil", UI.RED) {
                        if (Store.katiOn(this, key)) {
                            toast("Katı: önce Katı'yı kaldır")
                        } else {
                            confirm("Bölüm silinsin mi?", "$title listeden kalkar.") {
                                Store.setTags(this, key, 0)
                                Clock.clear(this, "is_$key")
                                Sections.removeCustom(this, s.id)
                                sectionsScreen()
                            }
                        }
                    }, UI.lp(this, 10))
                }
            }
        }

        show(v, back)
        val run = object : Runnable {
            override fun run() {
                if (tickPending()) return
                val b = Policy.blockInfo(this@MainActivity, key)
                stateTv.text = "Şu an: " + when (b.kind) {
                    3 -> "Katı (kalıcı)"
                    2 -> "Sıkı, kalan " + fmt(b.left)
                    1 -> "Normal"
                    else -> "Serbest"
                }
                h.postDelayed(this, 1000)
            }
        }
        tickR = run
        run.run()
    }

    private fun rulesDialog(s: Sections.Sec, back: () -> Unit) {
        val e = EditText(this)
        e.setText(Sections.rules(this, s))
        e.minLines = 5
        e.textSize = 13f
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        AlertDialog.Builder(this)
            .setTitle(s.name + ": tanıma kuralları")
            .setMessage("Her satır bir kural. id:kelime = ekrandaki bileşen adı, sec:kelime = seçili sekme, düz kelime = ekranda görünen yazı.")
            .setView(e)
            .setPositiveButton("Kaydet") { _, _ ->
                Sections.setRules(this, s.id, e.text.toString().take(1500))
                back()
            }
            .setNeutralButton("Varsayılan") { _, _ ->
                Sections.resetRules(this, s.id)
                back()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    // ---------- ana ekran ----------

    private fun home() {
        pendingTv = null
        val mode = Policy.mode(this)
        shownMode = mode
        val back: () -> Unit = { home() }
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
            col.addView(optBtn(back, "siki", "Sıkı Mod başlat", color = UI.ACC) { sessionScreen() }, UI.lp(this, 14))
        }
        katiCard(col)
        val uninTv = uninCard(col)
        val appN = Store.blocked(this).count {
            !it.startsWith("pg:") && !it.startsWith("ui:") && !it.startsWith("sc:")
        }
        val pgN = Store.blocked(this).count { it.startsWith("pg:") }
        col.addView(optBtn(back, "apps", "Engellenecek uygulamalar", "Engellenecek uygulamalar ($appN)") {
            gate("apps", "Uygulama listesi") { appsScreen() }
        }, UI.lp(this, 10))
        col.addView(optBtn(back, "pages", "Telefon ayar sayfaları", "Telefon ayar sayfaları ($pgN)") {
            gate("pages", "Telefon ayar sayfaları") { pagesScreen() }
        }, UI.lp(this, 8))
        col.addView(optBtn(back, "sections", "Uygulama içi bölümler", "Uygulama içi bölümler (Shorts, Reels)") {
            sectionsScreen()
        }, UI.lp(this, 8))
        col.addView(optBtn(back, "self", "Kendime blok") { selfScreen() }, UI.lp(this, 8))
        col.addView(optBtn(back, "custom", "Özelleştirme") {
            gate("custom", "Özelleştirme") { customScreen() }
        }, UI.lp(this, 8))
        col.addView(optBtn(back, "pass", "Şifreler") {
            gate("pass", "Şifreler") { passScreen() }
        }, UI.lp(this, 8))
        col.addView(optBtn(back, "stats", "İstatistik") {
            gate("stats", "İstatistik") { statsScreen() }
        }, UI.lp(this, 8))
        col.addView(optBtn(back, "perms", "Kurulum ve izinler") { perms() }, UI.lp(this, 8))
        col.addView(UI.button(this, "Modlar nasıl çalışır?") { modesInfo() }, UI.lp(this, 8))
        col.addView(
            UI.tv(this, "İpucu: Her düğmeye basılı tutarsan o öğe için ayrı seviye (Normal, Sıkı, Katı) koyabilirsin.", 12f, UI.DIM),
            UI.lp(this, 12)
        )
        show(v, null)
        val r = object : Runnable {
            override fun run() {
                if (tickPending()) return
                val m = Policy.mode(this@MainActivity)
                if (m != shownMode) {
                    home()
                    return
                }
                if (m == 0) {
                    st.text = "Normal Mod"
                    sub.text = "Engelli uygulamalar şifreyle açılır."
                } else {
                    st.text = "Sıkı Mod oturumu aktif"
                    sub.text = "Kalan süre: " + fmt(Policy.remaining(this@MainActivity)) +
                        "\nSüre bitene kadar geri dönüş yok."
                }
                uninTv.text = uninText()
                h.postDelayed(this, 1000)
            }
        }
        tickR = r
        r.run()
    }

    private fun modesInfo() {
        pendingTv = null
        val (v, col) = UI.page(this)
        hdr(col, "Modlar nasıl çalışır?") { home() }
        fun block(title: String, body: String) {
            val c = UI.card(this)
            c.addView(UI.tv(this, title, 17f, UI.TXT, true))
            c.addView(UI.tv(this, body, 14f, UI.DIM), UI.lp(this, 4))
            col.addView(c, UI.lp(this, 12))
        }
        block(
            "Normal: şifre kapısı",
            "Engelli uygulama açılınca şifre ekranı çıkar. Doğru şifre yazınca açılır. Yeniden kilitleme zamanını Özelleştirme'den seçersin."
        )
        block(
            "Sıkı: zaman kilidi",
            "Süre dolana kadar şifre işe yaramaz. Engel ekranında kalan süre akar, arkadaki uygulama Geri tuşu zinciriyle kapatılır. Süre bitince kilit kendiliğinden kalkar. Bir öğeye tek başına koyabilirsin ya da ana ekrandaki Sıkı Mod ile \"ders oturumuna dahil\" tüm öğelere birden."
        )
        block(
            "Katı: kalıcı",
            "Süre yok, şifreyle açılmaz. Engel ekranında 3-2-1 geri sayım olur ve ceza sert (2 denemede ekran kilitlenir). Kaldırmak için Katı şifresi ve 24 saat bekleme gerekir, o arada vazgeçebilirsin."
        )
        block(
            "Kaldırma Engeli",
            "Secure Lock'un Ayarlar'dan kaldırılmasını engeller: Ayarlar'daki bilgi sayfası açılır açılmaz kapanır, simgeye basılı tutunca Kaldır menüsü yerine blok ekranı çıkar. Süre koyarsan süre dolana kadar kaldırma izni de verilemez. Süre bitince kendiliğinden açılmaz: uygulamaya girip \"Kaldırmaya izin ver\" dersin."
        )
        block(
            "Katı Koruma",
            "Kaldırma Engeli'nin kalıcı hâli. Ek olarak Erişilebilirlik, Tarih ve saat ile Cihaz yöneticisi sayfalarına girilemez."
        )
        block(
            "Uygulama içi bölümler",
            "YouTube Shorts, Instagram Reels, Facebook Reels gibi bölümleri uygulamanın tamamını kapatmadan engeller. Bölüme dokun, seviye seç."
        )
        show(v)
    }

    // ---------- Sıkı Mod oturumu (ders programı) ----------

    private fun hasSessionItems(): Boolean =
        Store.blocked(this).any { (Store.tags(this, it) and 2) != 0 }

    private fun sessionScreen() {
        pendingTv = null
        val (v, col) = UI.page(this)
        hdr(col, "Sıkı Mod") { home() }
        col.addView(
            UI.tv(
                this,
                "Zaman kilidi (ders programı): süre bitene kadar \"Sıkı Mod oturumuna dahil\" öğeler hiçbir şifreyle açılmaz. Engel ekranında kalan süre akar. Erişilebilirlik ile Tarih ve saat sayfaları engellenir. Süre bitince kendiliğinden kalkar.",
                14f, UI.DIM
            ),
            UI.lp(this, 4)
        )
        val num = UI.input(this, "Süre (sayı)", false)
        num.inputType = InputType.TYPE_CLASS_NUMBER
        num.setText("1")
        col.addView(num, UI.lp(this, 16))
        val sel = intArrayOf(1)
        col.addView(durationRow(sel), UI.lp(this, 8))
        val pw = UI.input(this, "Katı Mod şifresi", false)
        col.addView(pw, UI.lp(this, 12))
        col.addView(UI.button(this, "Başlat", UI.ACC) {
            val n = num.text.toString().toLongOrNull() ?: 0L
            val unit = sel[0]
            when {
                n < 1L || n > 99999L -> toast("Süre için 1 ile 99999 arası bir sayı yaz")
                !Store.checkStrict(this, pw.text.toString()) -> toast("Katı Mod şifresi yanlış")
                !hasSessionItems() -> toast("Oturuma dahil öğe yok. Önce listeden ekle.")
                !accOn() -> toast("Erişilebilirlik izni kapalı: Kurulum ve izinler")
                else -> confirm("Geri dönüş yok", "$n ${unitNames[unit]} boyunca hiçbir şifreyle bozulamaz. Başlatılsın mı?") {
                    Policy.start(this, n * unitMs[unit])
                    home()
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

    private fun subText(key: String): String {
        if (Store.katiOn(this, key)) {
            return if (Store.katiExitPending(this, key)) "Katı (kaldırma bekleniyor)" else "Katı (kalıcı)"
        }
        val t = Store.tags(this, key)
        val parts = ArrayList<String>()
        if ((t and 1) != 0) parts.add("Normal")
        if ((t and 2) != 0) parts.add("Ders oturumu")
        var s = if (parts.isEmpty()) "Serbest" else parts.joinToString(" + ")
        val ts = Clock.left(this, "is_$key")
        if (ts > 0L) s += "  |  Sıkı: " + fmt(ts)
        if (Store.hasAppLock(this, key)) s += "  |  özel " + typeName(Store.appType(this, key))
        return s
    }

    private fun appsScreen() {
        pendingTv = null
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
            "Anahtar Normal kilit + ders oturumunu açar. Uygulamaya basılı tut: Sıkı, Katı ve özel şifre."
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

        val lockedHdr = UI.label(this, "")
        val lockedBox = LinearLayout(this)
        lockedBox.orientation = LinearLayout.VERTICAL
        val otherHdr = UI.label(this, "Diğer uygulamalar")
        val otherBox = LinearLayout(this)
        otherBox.orientation = LinearLayout.VERTICAL
        list.addView(lockedHdr, UI.lp(this, 10))
        list.addView(lockedBox)
        list.addView(otherHdr, UI.lp(this, 16))
        list.addView(otherBox)

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
            val key = name.lowercase()
            row.tag = key

            fun place(box: LinearLayout) {
                (row.parent as? ViewGroup)?.removeView(row)
                var idx = box.childCount
                for (i in 0 until box.childCount) {
                    val k = box.getChildAt(i).tag as? String ?: ""
                    if (k > key) {
                        idx = i
                        break
                    }
                }
                box.addView(row, idx, UI.lp(this, 6))
            }

            place(if (Store.lockedNow(this, pkg)) lockedBox else otherBox)
            var rev = false
            sw.setOnCheckedChangeListener { b, on ->
                if (!rev) {
                    if (on) {
                        if (Store.tags(this, pkg) == 0) Store.setTags(this, pkg, 3)
                    } else if (keep || Store.katiOn(this, pkg)) {
                        rev = true
                        b.isChecked = true
                        rev = false
                        toast(if (Store.katiOn(this, pkg)) "Katı: önce Katı'yı kaldır" else "Oturum sürerken çıkarılamaz")
                    } else {
                        Store.setTags(this, pkg, 0)
                    }
                    subTv.text = subText(pkg)
                    place(if (Store.lockedNow(this, pkg)) lockedBox else otherBox)
                    lockedHdr.text = "Kilitli uygulamalar (" + lockedBox.childCount + ")"
                }
            }
            row.addView(sw)
            row.setOnClickListener { if (sw.isEnabled) sw.toggle() }
            row.setOnLongClickListener {
                itemScreen(pkg, name, 0) { appsScreen() }
                true
            }
            rows.add(Pair(row, name.lowercase()))
        }
        lockedHdr.text = "Kilitli uygulamalar (" + lockedBox.childCount + ")"
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

    private fun appLockPicker(pkg: String, name: String) {
        val back: () -> Unit = { itemScreen(pkg, name, 0) { appsScreen() } }
        val done: () -> Unit = {
            toast("Kaydedildi")
            itemScreen(pkg, name, 0) { appsScreen() }
        }
        val (v, col) = UI.page(this)
        hdr(col, name, back)
        col.addView(
            UI.tv(this, "Bu uygulamaya özel kilit türü. Seçmezsen genel kilit kullanılır.", 14f, UI.DIM),
            UI.lp(this, 4)
        )
        col.addView(UI.button(this, "Genel kilidi kullan") {
            Store.clearAppLock(this, pkg)
            done()
        }, UI.lp(this, 16))
        col.addView(UI.button(this, "PIN (4-8 rakam)") {
            askText("PIN belirle", "4 ile 8 rakam", true, 4, { appLockPicker(pkg, name) }) { s ->
                Store.setAppLock(this, pkg, Store.PIN, s)
                done()
            }
        }, UI.lp(this, 8))
        col.addView(UI.button(this, "Parola (telefon klavyesi)") {
            askText("Parola belirle", "En az 4 karakter", false, 4, { appLockPicker(pkg, name) }) { s ->
                Store.setAppLock(this, pkg, Store.PASS, s)
                done()
            }
        }, UI.lp(this, 8))
        col.addView(UI.button(this, "Desen") {
            askPattern("Desen belirle", { appLockPicker(pkg, name) }) { s ->
                Store.setAppLock(this, pkg, Store.PATTERN, s)
                done()
            }
        }, UI.lp(this, 8))
        show(v, back)
    }

    // ---------- telefon ayar sayfalari ----------

    private fun pagesScreen() {
        pendingTv = null
        val keep = Policy.mode(this) != 0 && Store.feat(this, "s_keep")
        val (v, col) = UI.page(this)
        hdr(col, "Telefon ayar sayfaları") { home() }
        col.addView(
            UI.tv(
                this,
                "Ayarlar uygulamasının tamamını değil, sadece seçtiğin sayfayı engeller. Anahtar Normal kilit + ders oturumunu açar; Sıkı ve Katı için sayfaya basılı tut. Sıkı oturumda ve Katı Koruma açıkken Erişilebilirlik ile Tarih ve saat zaten otomatik engelli.",
                13f, UI.DIM
            ),
            UI.lp(this, 4)
        )
        for (pg in Pages.all) {
            val key = "pg:" + pg.id
            val row = UI.card(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val left = LinearLayout(this)
            left.orientation = LinearLayout.VERTICAL
            val subTv = UI.tv(this, subText(key), 12f, UI.DIM)
            left.addView(UI.tv(this, pg.name, 16f))
            left.addView(subTv)
            row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            val sw = Switch(this)
            sw.isChecked = Store.tags(this, key) != 0
            if (keep && sw.isChecked) sw.isEnabled = false
            var rev = false
            sw.setOnCheckedChangeListener { b, on ->
                if (!rev) {
                    if (on) {
                        if (Store.tags(this, key) == 0) Store.setTags(this, key, 3)
                    } else if (keep || Store.katiOn(this, key)) {
                        rev = true
                        b.isChecked = true
                        rev = false
                        toast(if (Store.katiOn(this, key)) "Katı: önce Katı'yı kaldır" else "Oturum sürerken çıkarılamaz")
                    } else {
                        Store.setTags(this, key, 0)
                    }
                    subTv.text = subText(key)
                }
            }
            row.addView(sw)
            row.setOnClickListener { if (sw.isEnabled) sw.toggle() }
            row.setOnLongClickListener {
                itemScreen(key, pg.name, 1) { pagesScreen() }
                true
            }
            col.addView(row, UI.lp(this, 6))
        }
        show(v)
    }

    // ---------- uygulama ici bolumler (Shorts, Reels) ----------

    private fun sectionsScreen() {
        pendingTv = null
        val (v, col) = UI.page(this)
        hdr(col, "Uygulama içi bölümler") { home() }
        col.addView(
            UI.tv(
                this,
                "Uygulamanın tamamını değil, sadece içindeki bölümü engeller (örneğin YouTube Shorts). Bir bölüme dokun, seviye seç: Normal, Sıkı ya da Katı. Katı kalıcıdır.",
                13f, UI.DIM
            ),
            UI.lp(this, 4)
        )
        if (!accOn()) {
            col.addView(UI.tv(this, "Erişilebilirlik izni kapalı: bölümler engellenmez.", 13f, red), UI.lp(this, 8))
        }
        for (s in Sections.all(this)) {
            val key = "sc:" + s.id
            col.addView(UI.rowBtn(this, s.name, subText(key)) {
                itemScreen(key, s.name, 3) { sectionsScreen() }
            }, UI.lp(this, 8))
        }
        col.addView(UI.button(this, "Yeni bölüm ekle", UI.ACC) { addSectionFlow() }, UI.lp(this, 16))

        col.addView(UI.label(this, "Tanıma yardımcısı"), UI.lp(this, 18))
        col.addView(
            UI.tv(
                this,
                "Bir bölüm tanınmıyorsa (uygulamalar güncellenince olabilir): İncelemeyi başlat'a bas, Secure Lock'tan çık, 8 saniye içinde o uygulamayı aç ve bölüme gir. Ekranın içeriği kaydedilir. Sonra buraya dönüp göster, kopyala ve bana at.",
                12f, UI.DIM
            ),
            UI.lp(this, 2)
        )
        col.addView(UI.button(this, "İncelemeyi başlat") {
            Store.p(this).edit().putBoolean("dump_arm", true).apply()
            toast("Hazır. Şimdi uygulamayı aç ve bölüme gir.")
        }, UI.lp(this, 8))
        col.addView(UI.button(this, "Son incelemeyi göster") { dumpDialog() }, UI.lp(this, 8))
        show(v)
    }

    private fun addSectionFlow() {
        inputDialog("Bölüm adı", "Örnek: TikTok Keşfet", "") { name ->
            val apps = loadApps(false)
            choose("Hangi uygulamanın bölümü?", apps.map { it.second }, -1) { i ->
                inputDialog("Seçili sekme adı", "Ekranda alt sekmede görünen yazı", "") { word ->
                    Sections.addCustom(this, name, apps[i].first, "sec:$word")
                    sectionsScreen()
                }
            }
        }
    }

    private fun dumpDialog() {
        val txt = Store.p(this).getString("dump", "") ?: ""
        val tv = TextView(this)
        tv.text = if (txt.isEmpty()) "Henüz inceleme yok." else txt
        tv.textSize = 11f
        tv.setTextIsSelectable(true)
        tv.setPadding(UI.dp(this, 16), UI.dp(this, 8), UI.dp(this, 16), UI.dp(this, 8))
        val sv = ScrollView(this)
        sv.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("Son inceleme")
            .setView(sv)
            .setPositiveButton("Kopyala") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("inceleme", txt))
                toast("Kopyalandı")
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    // ---------- ozellestirme ----------

    private fun customScreen() {
        pendingTv = null
        val back: () -> Unit = { customScreen() }
        val (v, col) = UI.page(this)
        hdr(col, "Özelleştirme") { home() }
        col.addView(
            UI.tv(this, "Her satıra basılı tutarak o ayara ayrı seviye koyabilirsin.", 12f, UI.DIM),
            UI.lp(this, 4)
        )

        col.addView(UI.label(this, "Görünüm"), UI.lp(this, 12))
        col.addView(optRow(back, "theme", "Tema rengi", UI.accentNames[Store.theme(this)]) {
            choose("Tema rengi", UI.accentNames, Store.theme(this)) { i ->
                Store.setInt(this, "theme", i)
                UI.useTheme(this)
                customScreen()
            }
        }, UI.lp(this, 6))
        col.addView(optRow(back, "cover", "Engel ekranı rengi", Store.coverNames[Store.cover(this)]) {
            choose("Engel ekranı rengi", Store.coverNames, Store.cover(this)) { i ->
                Store.setInt(this, "cover", i)
                customScreen()
            }
        }, UI.lp(this, 6))

        col.addView(UI.label(this, "Katı engel ekranı"), UI.lp(this, 18))
        val secs = listOf(3, 5, 10, 15)
        col.addView(optRow(back, "gsec", "Geri sayım süresi", Store.ghostSecs(this).toString() + " saniye (3-2-1)") {
            choose("Geri sayım", secs.map { it.toString() + " saniye" }, maxOf(0, secs.indexOf(Store.ghostSecs(this)))) { i ->
                Store.setInt(this, "gsec", secs[i])
                customScreen()
            }
        }, UI.lp(this, 6))
        col.addView(optRow(back, "gmsg", "Ekran mesajı", Store.ghostMsg(this)) {
            textDialog("Engel ekranı mesajı", Store.ghostMsg(this)) { s ->
                Store.setStr(this, "gmsg", s)
                customScreen()
            }
        }, UI.lp(this, 6))
        val strikes = listOf(2, 3, 4, 5)
        col.addView(optRow(back, "strike", "Ceza için deneme sayısı", Store.strikeN(this).toString() + " deneme (2 dakika içinde)") {
            choose("Kaç denemede ekran kilitlensin?", strikes.map { it.toString() + " deneme" }, maxOf(0, strikes.indexOf(Store.strikeN(this)))) { i ->
                Store.setInt(this, "strike", strikes[i])
                customScreen()
            }
        }, UI.lp(this, 6))

        col.addView(UI.label(this, "Sıkı engel ekranı"), UI.lp(this, 18))
        val ss = listOf(3, 5, 8, 12)
        col.addView(optRow(back, "ssec", "Ekranda kalma süresi", Store.ssec(this).toString() + " saniye (kalan süre görünür)") {
            choose("Ekranda kalma süresi", ss.map { it.toString() + " saniye" }, maxOf(0, ss.indexOf(Store.ssec(this)))) { i ->
                Store.setInt(this, "ssec", ss[i])
                customScreen()
            }
        }, UI.lp(this, 6))

        col.addView(UI.label(this, "Tüm engel ekranları"), UI.lp(this, 18))
        val bk = listOf(2, 3, 4, 6, 8)
        col.addView(optRow(back, "backn", "Geri tuşu sayısı", Store.backN(this).toString() + " kez arka arkaya") {
            choose("Geri tuşu sayısı", bk.map { it.toString() + " kez" }, maxOf(0, bk.indexOf(Store.backN(this)))) { i ->
                Store.setInt(this, "backn", bk[i])
                customScreen()
            }
        }, UI.lp(this, 6))
        col.addView(optRow(back, "pool", "Cümle havuzu", Store.pool(this).size.toString() + " cümle, rastgele gösterilir") {
            poolDialog()
        }, UI.lp(this, 6))
        col.addView(optSwitch(back, "f_vib", "Engelde titreşim", Store.feat(this, "vib")) { on ->
            Store.setFeat(this, "vib", on)
        }, UI.lp(this, 6))

        col.addView(UI.label(this, "Normal Mod"), UI.lp(this, 18))
        val ri = maxOf(0, relockVals.indexOf(Store.relock(this)))
        col.addView(optRow(back, "relock", "Yeniden kilitleme", relockNames[ri]) {
            choose("Açılan uygulama ne zaman tekrar kilitlensin?", relockNames, ri) { i ->
                Store.setInt(this, "relock", relockVals[i])
                customScreen()
            }
        }, UI.lp(this, 6))

        col.addView(UI.label(this, "Koruma (kaldırma engeli)"), UI.lp(this, 18))
        col.addView(
            UI.tv(this, "Kaldırma kilidi, Katı Koruma ya da Sıkı oturum sürerken bu anahtarlar kapalı olsa da koruma açık kalır. Kaldırma iznini ve kilidi ana ekrandaki Kaldırma Engeli kartından yönetirsin.", 12f, UI.DIM),
            UI.lp(this, 4)
        )
        for (t in Feat.prot) {
            col.addView(optSwitch(back, "f_" + t.first, t.second, Store.feat(this, t.first)) { on ->
                Store.setFeat(this, t.first, on)
            }, UI.lp(this, 6))
        }
        val um = listOf(5, 10, 30, 60)
        col.addView(optRow(back, "unin_min", "Kaldırma izni süresi", Store.uninMin(this).toString() + " dakika") {
            choose("Kaldırma izni süresi", um.map { it.toString() + " dakika" }, maxOf(0, um.indexOf(Store.uninMin(this)))) { i ->
                Store.setInt(this, "unin_min", um[i])
                customScreen()
            }
        }, UI.lp(this, 6))

        col.addView(UI.label(this, "Sıkı oturum özellikleri"), UI.lp(this, 18))
        for (t in Feat.siki) {
            col.addView(optSwitch(back, "f_" + t.first, t.second, Store.feat(this, t.first)) { on ->
                Store.setFeat(this, t.first, on)
            }, UI.lp(this, 6))
        }

        col.addView(UI.label(this, "Katı özellikleri"), UI.lp(this, 18))
        for (t in Feat.kati) {
            col.addView(optSwitch(back, "f_" + t.first, t.second, Store.feat(this, t.first)) { on ->
                Store.setFeat(this, t.first, on)
            }, UI.lp(this, 6))
        }

        col.addView(UI.label(this, "Diğer"), UI.lp(this, 18))
        col.addView(optSwitch(back, "f_gray", "Oturumda gri tonlama", Store.feat(this, "gray")) { on ->
            Store.setFeat(this, "gray", on)
        }, UI.lp(this, 6))
        col.addView(UI.tv(this, "Gri tonlama için ayrıca izin gerekir (Kurulum ve izinler).", 12f, UI.DIM), UI.lp(this, 4))
        col.addView(optSwitch(back, "f_hide", "Simgeyi ana menüden gizle", Store.feat(this, "hide")) { on ->
            if (on) {
                confirm(
                    "Simge gizlensin mi?",
                    "Simge ana menüden kalkar. Açmak için telefon uygulamasında *#*#7328#*#* tuşla. Önce kodun çalıştığından emin ol. Çalışmazsa Ayarlar > Erişilebilirlik > Yüklü uygulamalar > Secure Lock yolundan girersin. Samsung ana ekranı eski simgeyi bir süre gösterebilir, telefonu yeniden başlatınca kalkar.",
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
        pendingTv = null
        val back: () -> Unit = { passScreen() }
        val (v, col) = UI.page(this)
        hdr(col, "Şifreler") { home() }
        col.addView(UI.tv(this, "Kilit türü: " + typeName(Store.lockType(this)), 16f, UI.DIM), UI.lp(this, 10))
        col.addView(optBtn(back, "p_lock", "Kilit şifresini / türünü değiştir") {
            pickLock({ passScreen() }) { t, s ->
                Store.setLock(this, t, s)
                toast("Kaydedildi")
                passScreen()
            }
        }, UI.lp(this, 8))
        col.addView(optBtn(back, "p_admin", "Yönetici şifresini değiştir") {
            askText("Yeni yönetici şifresi", "Kurtarma yöntemi yoktur.", false, 4, { passScreen() }) { s ->
                Store.setAdmin(this, s)
                toast("Kaydedildi")
                passScreen()
            }
        }, UI.lp(this, 8))
        col.addView(optBtn(back, "p_strict", "Katı Mod şifresini değiştir") {
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
        pendingTv = null
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

    private fun adminDiag(): String {
        return try {
            val ris = packageManager.queryBroadcastReceivers(
                Intent(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED), PackageManager.GET_META_DATA
            )
            val mine = ris.firstOrNull { it.activityInfo.packageName == packageName }
            if (mine == null) {
                "Sistem alıcıyı bulamadı (manifest sorunu)"
            } else {
                try {
                    DeviceAdminInfo(this, mine)
                    "Tanım geçerli"
                } catch (e: Exception) {
                    "Tanım geçersiz: " + e.message
                }
            }
        } catch (e: Exception) {
            "Kontrol edilemedi: " + e.message
        }
    }

    @Suppress("DEPRECATION")
    private fun adminRequest() {
        grace = System.currentTimeMillis() + 180000
        try {
            startActivityForResult(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Policy.admin(this))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Secure Lock'un kaldırılmasını zorlaştırır."),
                77
            )
        } catch (e: Exception) {
            toast("Sistem ekranı açılamadı: " + e.message)
        }
    }

    private fun perms() {
        pendingTv = null
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
            "Tüm korumalar buna bağlı. Açılmazsa: Ayarlar > Uygulamalar > Secure Lock > sağ üst üç nokta > Kısıtlı ayarlara izin ver, sonra tekrar dene. Not: Kaldırma Engeli açıkken bu sayfaya girmek için önce ana ekrandan Kaldırmaya izin ver de. Uygulamayı güncelledikten sonra bir kez kapatıp açman gerekir.",
            "Ayarları aç"
        ) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        val pmgr = getSystemService(POWER_SERVICE) as PowerManager
        row("Pil kısıtlaması yok", pmgr.isIgnoringBatteryOptimizations(packageName), "", "İzin ver") {
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        row(
            "Cihaz yöneticisi (isteğe bağlı)", Policy.adminActive(this),
            "Açıkken Kaldır dersen sistem \"devre dışı bırak ve kaldır\" uyarısı gösterir. Kaldırma Engeli o uyarıyı da yakalar.\nTeşhis: " + adminDiag(),
            "Etkinleştir"
        ) { adminRequest() }
        row(
            "Gri tonlama izni (isteğe bağlı)", Policy.canSecure(this),
            "Sadece gri tonlama için. Bugjaeger kabuğunda çalıştır:\npm grant com.securelock.app android.permission.WRITE_SECURE_SETTINGS",
            null
        ) {}
        if (Policy.adminActive(this)) {
            col.addView(UI.button(this, "Cihaz yöneticisini kapat") {
                if (Policy.mode(this) != 0) {
                    toast("Sıkı Mod oturumu sürerken kapatılamaz")
                } else if (Policy.katiProtect(this)) {
                    toast("Katı Koruma açıkken kapatılamaz")
                } else {
                    confirm("Cihaz yöneticisi kapatılsın mı?", "Ek kaldırma koruması gider.") {
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
