package com.securelock.app

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GuardService : AccessibilityService() {

    companion object {
        var inst: GuardService? = null
        private val INFO_W = listOf(
            "kaldır", "zorla durdur", "durmaya zorla", "uninstall", "force stop",
            "devre dışı bırak", "disable", "uygulama bilgileri", "app info",
            "verileri sil", "verileri temizle", "clear data", "clear storage", "depolamayı temizle"
        ).map { norm(it) }
        private val BTN_W = listOf(
            "kaldır", "durmaya zorla", "zorla durdur", "devre dışı bırak", "d. dışı bırak",
            "uninstall", "force stop", "disable"
        ).map { norm(it) }
        private val KALDIR_W = listOf("kaldır", "uninstall").map { norm(it) }
        private val CLEAR_W = listOf(
            "verileri sil", "verileri temizle", "clear data", "delete app data", "depolamayı temizle"
        ).map { norm(it) }
        private val ADMIN_W = listOf(
            "cihaz yönet", "device admin", "devre dışı bırak ve kaldır", "deactivate"
        ).map { norm(it) }
        private val UNIN_Q = listOf(
            "kaldırmak istiyor", "kaldırılsın mı", "want to uninstall", "uninstall this app", "yüklemesini kaldır"
        ).map { norm(it) }
    }

    private val h = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var cover: FrameLayout? = null
    private var coverTop: TextView? = null
    private var coverMsg: TextView? = null
    private var coverNum: TextView? = null
    private var coverSub: TextView? = null
    private var busy = false
    private var current = ""
    private var lastLock = 0L
    private var lastKick = 0L
    private var lastScan = 0L
    private var lastEnforce = 0L
    private var lastSec = 0L
    private var lastLaunch = 0L
    private var cacheAt = 0L
    private var secAt = 0L
    private var dumpPending = false
    private var scanPending = false
    private var lastSig = ""
    private var protSet: Set<String> = emptySet()
    private var launchSet: Set<String> = emptySet()
    private var secCache: List<Sections.Sec> = emptyList()
    private var registered = false
    private val unlockAt = HashMap<String, Long>()
    private val strikes = ArrayList<Long>()
    private val glog = ArrayList<String>()

    // Gri örtüler (Kaldır, Durmaya zorla ... düğmelerinin üstü)
    private val masks = ArrayList<FrameLayout>()
    private var maskSig = ""
    private var maskLoopOn = false

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Store.relock(this@GuardService) <= 0) unlockAt.clear()
            current = ""
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            Policy.refresh(this@GuardService)
            h.postDelayed(this, 15000)
        }
    }

    // Olay gelmese de her yarım saniyede tüm pencerelere bakar (bölünmüş ekran dahil)
    private val scanLoop = object : Runnable {
        override fun run() {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (pm.isInteractive && !busy) scanAll()
            } catch (x: Exception) {
            }
            h.postDelayed(this, 500)
        }
    }

    // Sayfa ya da menü görünürken örtüleri tazeler, görünmüyorsa kaldırır
    private val maskLoop = object : Runnable {
        override fun run() {
            var again = false
            try {
                if (!Policy.uninstallOpen(this@GuardService)) {
                    cache()
                    val rects = ArrayList<Pair<Rect, String>>()
                    for (r in windowRoots { guardable(it) }) {
                        if (isOurPage(r)) findButtons(r, rects, intArrayOf(0), 0, BTN_W)
                    }
                    for (r in windowRoots { launchSet.contains(it) && !guardable(it) }) {
                        val t = smallTexts(r) ?: continue
                        if (popupHasKaldir(t)) findButtons(r, rects, intArrayOf(0), 0, KALDIR_W)
                    }
                    if (rects.isNotEmpty()) {
                        setMasks(rects)
                        again = true
                    }
                }
            } catch (x: Exception) {
            }
            if (again) {
                h.postDelayed(this, 450)
            } else {
                clearMasks()
                maskLoopOn = false
            }
        }
    }

    override fun onServiceConnected() {
        inst = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (!registered) {
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
            registered = true
        }
        h.removeCallbacks(ticker)
        h.post(ticker)
        h.removeCallbacks(scanLoop)
        h.post(scanLoop)
        logG("Bekci basladi")
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        inst = null
        h.removeCallbacksAndMessages(null)
        clearMasks()
        maskLoopOn = false
        hideCover()
        if (registered) {
            try { unregisterReceiver(screenOff) } catch (e: Exception) {}
            registered = false
        }
    }

    fun markUnlocked(p: String) {
        unlockAt[p] = System.currentTimeMillis()
        if (!p.startsWith("pg:") && !p.startsWith("sc:")) current = p
    }

    private fun clearPrefix(pre: String) {
        val iter = unlockAt.keys.iterator()
        while (iter.hasNext()) {
            if (iter.next().startsWith(pre)) iter.remove()
        }
    }

    private fun isUnlocked(key: String): Boolean {
        val t = unlockAt[key] ?: return false
        val rl = Store.relock(this)
        if (rl > 0) {
            if (System.currentTimeMillis() - t < rl * 60000L) return true
            unlockAt.remove(key)
            return false
        }
        return true
    }

    private fun cache() {
        val n = System.currentTimeMillis()
        if (protSet.isEmpty() || n - cacheAt > 60000) {
            protSet = Policy.protectedPkgs(this)
            launchSet = Policy.launchers(this)
            cacheAt = n
        }
    }

    private fun sections(): List<Sections.Sec> {
        val n = System.currentTimeMillis()
        if (n - secAt > 5000) {
            secCache = Sections.all(this)
            secAt = n
        }
        return secCache
    }

    private fun guardable(pkg: String) =
        pkg.contains("settings") || pkg.contains("packageinstaller") || pkg == "com.samsung.android.lool"

    // Koruma anahtarları artık dikkate alınmıyor: koruma hep açık, sadece "Kaldırmaya izin ver" penceresinde kapanır
    @Suppress("UNUSED_PARAMETER")
    private fun protOn(k: String): Boolean = true

    private fun watching(): Boolean = true

    private fun noteGuard() {
        Store.p(this).edit().putLong("last_guard", System.currentTimeMillis()).apply()
    }

    // Koruma günlüğü: Uygulama içi bölümler > Son incelemeyi göster'de görünür
    private fun logG(s: String) {
        try {
            val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            glog.add("$t $s")
            while (glog.size > 40) glog.removeAt(0)
            Store.p(this).edit().putString("dump", "KORUMA GUNLUGU (en yeni altta)\n" + glog.joinToString("\n")).apply()
        } catch (x: Exception) {
        }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        val type = e.eventType

        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (!scanPending) {
                scanPending = true
                h.postDelayed({
                    scanPending = false
                    if (!busy) scanAll()
                }, 120)
            }
            return
        }

        val pkg = e.packageName?.toString() ?: return

        if (type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            longClick(e, pkg)
            return
        }
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        if (pkg == packageName) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val cn = e.className?.toString() ?: ""
                if (!busy && cover != null && cn.endsWith("LockActivity")) {
                    h.postDelayed({
                        if (!busy) {
                            if (isSplit()) kick("Bölünmüş ekran engelli") else hideCover()
                        }
                    }, 250)
                }
            }
            return
        }

        cache()
        if (!dumpPending && Store.p(this).getBoolean("dump_arm", false) &&
            !launchSet.contains(pkg) && !protSet.contains(pkg)
        ) {
            dumpPending = true
            h.postDelayed({ doDump(pkg) }, 8000)
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (busy) return
            val now = System.currentTimeMillis()
            if (!guardable(pkg) && launchSet.contains(pkg)) {
                launcherEvent(e)
                return
            }
            if (pkg == current && !protSet.contains(pkg) && !launchSet.contains(pkg)) {
                if (now - lastEnforce > 800) {
                    lastEnforce = now
                    val b = Policy.blockInfo(this, pkg)
                    if (b.kind >= 2) {
                        enforce(pkg, pkg, b, "", null)
                        return
                    }
                }
                if (now - lastSec > 350) {
                    lastSec = now
                    if (sectionGuard(pkg)) return
                }
            }
            if (guardable(pkg) && now - lastScan > 250) {
                lastScan = now
                pageGuard(pkg, e)
            }
            return
        }

        // Pencere değişti: önce Kaldır pencereleri, sonra ayar sayfaları, en son ana ekran ve uygulama kontrolü
        if (!guardable(pkg)) {
            val cn = (e.className?.toString() ?: "").lowercase(Locale.ROOT)
            if (cn.contains("dialog") && dialogGuard(pkg, e)) return
        }
        if (guardable(pkg)) {
            if (pageGuard(pkg, e)) return
            h.postDelayed({ recheck(pkg) }, 400)
            h.postDelayed({ recheck(pkg) }, 1200)
        } else if (launchSet.contains(pkg)) {
            launcherEvent(e)
        }
        if (launchSet.contains(pkg)) {
            if (Store.relock(this) == 0) unlockAt.clear()
            current = ""
            return
        }
        if (protSet.contains(pkg)) return
        if (pkg != current) {
            if (Store.relock(this) == 0) {
                unlockAt.remove(current)
                clearPrefix("pg:")
                clearPrefix("sc:")
            }
            current = pkg
        }
        val b = Policy.blockInfo(this, pkg)
        if (b.kind >= 2) {
            enforce(pkg, pkg, b, "", null)
            return
        }
        if (b.kind == 1 && !isUnlocked(pkg)) {
            if (isSplit()) kick("Bölünmüş ekran engelli") else lockApp(pkg)
            return
        }
        if (sectionGuard(pkg)) return
        h.postDelayed({ if (!busy && current == pkg) sectionGuard(pkg) }, 600)
    }

    // Ana ekranda Secure Lock simgesine basılı tutulursa Kaldır menüsü yerine blok ekranı
    private fun longClick(e: AccessibilityEvent, pkg: String) {
        if (busy || Policy.uninstallOpen(this)) return
        if (!protOn("p_launch")) return
        cache()
        if (!launchSet.contains(pkg)) return
        val sb = StringBuilder()
        try {
            for (t in e.text) sb.append(t).append(' ')
            val cd = e.contentDescription
            if (cd != null) sb.append(cd).append(' ')
            val s = e.source
            if (s != null) {
                val st = s.text
                if (st != null) sb.append(st).append(' ')
                val sc = s.contentDescription
                if (sc != null) sb.append(sc)
            }
        } catch (x: Exception) {
        }
        if (norm(sb.toString()).contains("secure lock")) kick("Kaldırma engelli")
    }

    private fun recheck(pkg: String) {
        if (!busy) pageGuard(pkg, null)
    }

    // ---------- pencere okuma (bolunmus ekranda dogru pencereyi bulur) ----------

    // Olayı gönderen pencerenin kökü: "seçili pencere" hangisi olursa olsun doğru pencereyi verir
    private fun rootOf(e: AccessibilityEvent): AccessibilityNodeInfo? {
        try {
            var n: AccessibilityNodeInfo = e.source ?: return null
            var i = 0
            while (i < 80) {
                val p = n.parent ?: break
                n = p
                i++
            }
            return n
        } catch (x: Exception) {
            return null
        }
    }

    // Verilen koşulu sağlayan paketlerin tüm pencere kökleri
    private fun windowRoots(pred: (String) -> Boolean): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            val ws = windows
            if (ws != null) {
                for (w in ws) {
                    val r = w.root ?: continue
                    val p = r.packageName?.toString() ?: continue
                    if (p != packageName && pred(p)) out.add(r)
                }
            }
        } catch (x: Exception) {
        }
        return out
    }

    // Paketin tüm pencereleri: olayın penceresi + pencere listesi + seçili pencere
    private fun rootsFor(pkg: String, e: AccessibilityEvent?): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val seen = HashSet<Int>()
        fun add(r: AccessibilityNodeInfo?) {
            if (r == null) return
            try {
                if (r.packageName?.toString() != pkg) return
                if (seen.add(r.windowId)) out.add(r)
            } catch (x: Exception) {
            }
        }
        if (e != null) add(rootOf(e))
        try {
            val ws = windows
            if (ws != null) {
                for (w in ws) add(w.root)
            }
        } catch (x: Exception) {
        }
        if (out.isEmpty()) {
            try {
                add(rootInActiveWindow)
            } catch (x: Exception) {
            }
        }
        return out
    }

    private fun rootFor(pkg: String): AccessibilityNodeInfo? = rootsFor(pkg, null).firstOrNull()

    // O paketin penceresi tam ekran değilse true
    @Suppress("DEPRECATION")
    private fun windowPartial(pkg: String): Boolean {
        try {
            val ws = windows ?: return false
            val dm = DisplayMetrics()
            wm?.defaultDisplay?.getRealMetrics(dm)
            val sw = dm.widthPixels
            val sh = dm.heightPixels
            if (sw <= 0 || sh <= 0) return false
            val r = Rect()
            for (w in ws) {
                val root = w.root ?: continue
                if (root.packageName?.toString() != pkg) continue
                w.getBoundsInScreen(r)
                if (!(r.width() >= sw * 0.9 && r.height() >= sh * 0.85)) return true
            }
        } catch (e: Exception) {
        }
        return false
    }

    // Tüm pencereleri tara: ayar sayfaları ve ana ekran menüsü
    private fun scanAll() {
        if (busy) return
        cache()
        try {
            for (r in windowRoots { launchSet.contains(it) && !guardable(it) }) launcherCheck(r)
            val pk = LinkedHashSet<String>()
            for (r in windowRoots { guardable(it) }) {
                val p = r.packageName?.toString()
                if (p != null) pk.add(p)
            }
            for (p in pk) {
                if (pageGuard(p, null)) return
            }
        } catch (x: Exception) {
        }
    }

    // ---------- gri orttu (Kaldir, Durmaya zorla ... dugmeleri) ----------

    private fun isOurPage(r: AccessibilityNodeInfo): Boolean {
        val t = ArrayList<String>()
        collect(r, t, 0)
        return t.any { it == "secure lock" } && t.any { INFO_W.contains(it) }
    }

    private fun smallTexts(r: AccessibilityNodeInfo): List<String>? {
        val t = ArrayList<String>()
        collectN(r, t, 0, 90)
        return if (t.size >= 90) null else t
    }

    private fun popupHasKaldir(t: List<String>): Boolean = t.any { KALDIR_W.contains(it) }

    private fun launcherEvent(e: AccessibilityEvent) {
        if (maskLoopOn || busy) return
        val now = System.currentTimeMillis()
        if (now - lastLaunch < 300) return
        lastLaunch = now
        val r = rootOf(e) ?: return
        launcherCheck(r)
    }

    // Ana ekran menüsünde Kaldır varsa: Secure Lock'a aitse at, değilse sadece Kaldır satırını gri örtüyle kapat
    private fun launcherCheck(r: AccessibilityNodeInfo) {
        if (maskLoopOn || busy || Policy.uninstallOpen(this)) return
        if (!protOn("p_launch")) return
        val t = smallTexts(r) ?: return
        if (!popupHasKaldir(t)) return
        noteGuard()
        logG("Ana ekran menusu: Kaldir kapatildi")
        if (t.any { it == "secure lock" }) {
            kick("Kaldırma engelli")
            return
        }
        startMask()
    }

    // Herhangi bir uygulamadan çıkan "Secure Lock kaldırılsın mı?" penceresi
    private fun dialogGuard(pkg: String, e: AccessibilityEvent): Boolean {
        if (Policy.uninstallOpen(this)) return false
        val r = rootOf(e) ?: return false
        val t = ArrayList<String>()
        collectN(r, t, 0, 80)
        if (!t.any { it.contains("secure lock") }) return false
        if (!t.any { s -> UNIN_Q.any { q -> s.contains(q) } }) return false
        noteGuard()
        logG("Kaldirma penceresi yakalandi: $pkg")
        performGlobalAction(GLOBAL_ACTION_BACK)
        kick("Kaldırma engelli")
        return true
    }

    private fun clickBounds(n: AccessibilityNodeInfo): Rect {
        var p: AccessibilityNodeInfo = n
        var i = 0
        while (i < 6 && !p.isClickable) {
            val par = p.parent ?: break
            p = par
            i++
        }
        val r = Rect()
        if (p.isClickable) {
            p.getBoundsInScreen(r)
        } else {
            n.getBoundsInScreen(r)
            r.inset(-UI.dp(this, 28), -UI.dp(this, 28))
        }
        return r
    }

    private fun findButtons(
        n: AccessibilityNodeInfo?, out: ArrayList<Pair<Rect, String>>,
        cnt: IntArray, d: Int, words: List<String>
    ) {
        if (n == null || out.size >= 8 || d > 30 || cnt[0] > 500) return
        cnt[0]++
        try {
            if (n.isVisibleToUser) {
                val tx = n.text?.toString() ?: ""
                val cd = n.contentDescription?.toString() ?: ""
                val lab = if (tx.isNotEmpty()) tx else cd
                if (lab.isNotEmpty() && words.contains(norm(lab))) {
                    out.add(Pair(clickBounds(n), lab))
                }
            }
        } catch (x: Exception) {
        }
        for (i in 0 until n.childCount) findButtons(n.getChild(i), out, cnt, d + 1, words)
    }

    private fun setMasks(rects: List<Pair<Rect, String>>) {
        val uniq = LinkedHashMap<String, Pair<Rect, String>>()
        for (p in rects) uniq[p.first.flattenToString()] = p
        val sig = uniq.keys.joinToString("|")
        if (sig == maskSig && masks.isNotEmpty()) return
        clearMasks()
        maskSig = sig
        val w = wm ?: return
        for (p in uniq.values) {
            val r = p.first
            if (r.width() <= 0 || r.height() <= 0) continue
            val fl = FrameLayout(this)
            fl.setBackgroundColor(Color.BLACK)
            val tv = TextView(this)
            tv.text = p.second
            tv.setTextColor(Color.parseColor("#5A5A5A"))
            tv.textSize = 15f
            tv.gravity = Gravity.CENTER
            fl.addView(tv, FrameLayout.LayoutParams(-1, -1))
            val lp = WindowManager.LayoutParams(
                r.width(), r.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = r.left
            lp.y = r.top
            try {
                w.addView(fl, lp)
                masks.add(fl)
            } catch (x: Exception) {
            }
        }
    }

    private fun clearMasks() {
        for (m in masks) {
            try {
                wm?.removeView(m)
            } catch (x: Exception) {
            }
        }
        masks.clear()
        maskSig = ""
    }

    private fun startMask() {
        if (!maskLoopOn) {
            maskLoopOn = true
            maskLoop.run()
        }
    }

    // ---------- uygulama ici bolumler (Shorts, Reels ...) ----------

    private fun sectionGuard(pkg: String): Boolean {
        val list = sections().filter { it.pkg == pkg }
        if (list.isEmpty()) return false
        val root = rootFor(pkg) ?: return false
        for (s in list) {
            val key = "sc:" + s.id
            val b = Policy.blockInfo(this, key)
            if (b.kind == 0) continue
            if (!matchRules(root, Sections.rules(this, s))) continue
            if (b.kind >= 2) {
                enforce(key, null, b, s.name, s)
                return true
            }
            if (b.kind == 1 && !isUnlocked(key)) {
                lockApp(key)
                return true
            }
        }
        return false
    }

    private fun sectionStillOn(s: Sections.Sec): Boolean {
        val root = rootFor(s.pkg) ?: return false
        return matchRules(root, Sections.rules(this, s))
    }

    private fun matchRules(root: AccessibilityNodeInfo, rules: String): Boolean {
        val ids = ArrayList<String>()
        val sel = ArrayList<String>()
        val txt = ArrayList<String>()
        for (ln in rules.split("\n")) {
            val l = ln.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("id:")) {
                val w = l.substring(3).trim().lowercase(Locale.ROOT)
                if (w.isNotEmpty()) ids.add(w)
            } else if (l.startsWith("sec:")) {
                val w = norm(l.substring(4))
                if (w.isNotEmpty()) sel.add(w)
            } else {
                val w = norm(l)
                if (w.isNotEmpty()) txt.add(w)
            }
        }
        if (ids.isEmpty() && sel.isEmpty() && txt.isEmpty()) return false
        return walk(root, ids, sel, txt, intArrayOf(0), 0)
    }

    private fun walk(
        n: AccessibilityNodeInfo?, ids: List<String>, sel: List<String>,
        txt: List<String>, cnt: IntArray, d: Int
    ): Boolean {
        if (n == null || cnt[0] > 700 || d > 40) return false
        cnt[0]++
        try {
            if (n.isVisibleToUser) {
                val id = n.viewIdResourceName
                if (id != null && ids.isNotEmpty()) {
                    val low = id.lowercase(Locale.ROOT)
                    for (f in ids) if (low.contains(f)) return true
                }
                val t = n.text?.toString() ?: ""
                val cd = n.contentDescription?.toString() ?: ""
                if (sel.isNotEmpty() && n.isSelected) {
                    val s = norm("$t $cd")
                    for (w in sel) if (s.contains(w)) return true
                }
                if (txt.isNotEmpty() && (t.isNotEmpty() || cd.isNotEmpty())) {
                    val s = norm("$t $cd")
                    for (w in txt) if (s.contains(w)) return true
                }
            }
        } catch (e: Exception) {
        }
        for (i in 0 until n.childCount) {
            if (walk(n.getChild(i), ids, sel, txt, cnt, d + 1)) return true
        }
        return false
    }

    // Tanıma yardımcısı: o an açık uygulamanın ekran içeriğini kaydeder
    private fun doDump(pkg: String) {
        dumpPending = false
        Store.p(this).edit().putBoolean("dump_arm", false).apply()
        val sb = StringBuilder()
        sb.append("Paket: ").append(pkg).append("\n")
        try {
            val root = rootFor(pkg) ?: rootInActiveWindow
            if (root != null) {
                dumpNode(root, sb, intArrayOf(0), 0)
            } else {
                sb.append("Pencere okunamadı\n")
            }
        } catch (e: Exception) {
            sb.append("Hata: ").append(e.message)
        }
        Store.p(this).edit().putString("dump", sb.toString().take(12000)).apply()
        Toast.makeText(this, "İnceleme kaydedildi", Toast.LENGTH_LONG).show()
    }

    private fun dumpNode(n: AccessibilityNodeInfo?, sb: StringBuilder, cnt: IntArray, d: Int) {
        if (n == null || cnt[0] > 450 || d > 40) return
        cnt[0]++
        try {
            if (n.isVisibleToUser) {
                val id = n.viewIdResourceName
                val t = n.text
                val cd = n.contentDescription
                if (id != null || (t != null && t.isNotEmpty()) || (cd != null && cd.isNotEmpty())) {
                    sb.append(id?.substringAfter(":id/") ?: "-")
                        .append(" | ").append(t ?: "")
                        .append(" | ").append(cd ?: "")
                    if (n.isSelected) sb.append(" | SECILI")
                    sb.append("\n")
                }
            }
        } catch (e: Exception) {
        }
        for (i in 0 until n.childCount) dumpNode(n.getChild(i), sb, cnt, d + 1)
    }

    // ---------- ayarlar sayfalari ve kaldirma korumasi ----------

    private fun pageGuard(pkg: String, e: AccessibilityEvent?): Boolean {
        val mode = Policy.mode(this)
        val kp = Policy.katiProtect(this)
        if (kp && Store.feat(this, "k_allset") && pkg == "com.android.settings") {
            kick("Ayarlar kilitli", pkg)
            return true
        }
        // Ayarlar bölünmüş ekranın bir yarısındaysa kapat
        if (pkg == "com.android.settings" && protOn("p_split") && isSplit() && windowPartial(pkg)) {
            kick("Ayarlar bölünmüş ekranda açılamaz", pkg)
            return true
        }
        val roots = rootsFor(pkg, e)
        if (roots.isEmpty()) return false
        val t = ArrayList<String>()
        for (r in roots) {
            val part = ArrayList<String>()
            collect(r, part, 0)
            t.addAll(part)
        }
        if (t.isEmpty()) return false
        val open = Policy.uninstallOpen(this)
        val oursAny = t.any { it.contains("secure lock") }
        val oursExact = t.any { it == "secure lock" }

        // Günlük: sayfa değiştikçe Ayarlar'ın ilk yazılarını kaydeder (kaçarsa nedenini görmek için)
        val sig = pkg.substringAfterLast('.') + ": " + t.take(6).joinToString(" / ") +
            " | ours=" + oursExact + " win=" + roots.size
        if (sig != lastSig) {
            lastSig = sig
            logG(sig)
        }

        // Kaldırma onay penceresi ("Bu uygulamanın yüklemesini kaldırmak istiyor musunuz?")
        val dlg = t.any { s -> UNIN_Q.any { q -> s.contains(q) } }
        if (!open && oursAny && dlg && protOn("p_uninst")) {
            noteGuard()
            logG("ENGEL: kaldirma penceresi")
            performGlobalAction(GLOBAL_ACTION_BACK)
            kick("Kaldırma engelli", pkg)
            return true
        }

        // Verileri silme sayfası ve penceresi: veriler silinirse tüm korumalar kaybolur
        val clr = t.any { s -> CLEAR_W.any { w -> s.contains(w) } }
        if (!open && oursAny && clr && protOn("p_info")) {
            noteGuard()
            logG("ENGEL: veri silme")
            kick("Veri silme engelli", pkg)
            return true
        }

        if (pkg.contains("packageinstaller")) {
            val uninstall = t.any { it.contains("kaldir") || it.contains("uninstall") }
            if (!open && oursAny && uninstall && protOn("p_uninst")) {
                noteGuard()
                logG("ENGEL: yukleyici penceresi")
                kick("Kaldırma engelli", pkg)
                return true
            }
            return false
        }

        // "Devre dışı bırak ve kaldır" gibi cihaz yöneticisi pencereleri
        if (!open && oursAny && Policy.adminActive(this) && protOn("p_uninst") &&
            t.any { s -> ADMIN_W.any { w -> s.contains(w) } }
        ) {
            noteGuard()
            logG("ENGEL: cihaz yoneticisi penceresi")
            kick("Kaldırma engelli", pkg)
            return true
        }

        // Secure Lock bilgi sayfası (Ayarlar, Cihaz bakımı): düğmeleri örter, sonra atar
        if (oursExact) {
            val hd = t.take(14).any { it == "secure lock" }
            val info = t.any { INFO_W.contains(it) }
            if (!open && (info || (hd && (kp || mode != 0)))) {
                noteGuard()
                logG("ENGEL: Secure Lock sayfasi ($pkg)")
                startMask()
                kick("Secure Lock korumalı", pkg)
                return true
            }
        }

        // Ayar sayfası kilitleri sadece Ayarlar uygulamasında
        if (!pkg.contains("settings")) return false
        val head = t.take(5)
        if (head.take(2).any { it == "ayarlar" || it == "settings" }) return false
        for (pg in Pages.all) {
            if (head.none { pg.t.contains(it) }) continue
            val key = "pg:" + pg.id
            var b = Policy.pageInfo(this, pg.id)
            val ul = Policy.ulockLeft(this)
            // Kaldırma kilidi sürerken erişilebilirliği kapatıp korumayı düşürmek de engelli
            if (b.kind < 2 && ul > 0L && (pg.id == "acc" || pg.id == "date" || pg.id == "gm" || pg.id == "admin")) {
                b = Blk(2, ul)
            }
            if (b.kind >= 2) {
                enforce(key, null, b, pg.name, null)
                return true
            }
            if (b.kind == 1 && !isUnlocked(key)) {
                lockApp(key)
                return true
            }
        }
        return false
    }

    private fun collectN(n: AccessibilityNodeInfo?, out: ArrayList<String>, d: Int, max: Int) {
        if (n == null || out.size >= max || d > 30) return
        val t = n.text
        if (t != null && t.isNotEmpty()) out.add(norm(t.toString()))
        val cd = n.contentDescription
        if (cd != null && cd.isNotEmpty()) out.add(norm(cd.toString()))
        for (i in 0 until n.childCount) collectN(n.getChild(i), out, d + 1, max)
    }

    private fun collect(n: AccessibilityNodeInfo?, out: ArrayList<String>, d: Int) = collectN(n, out, d, 220)

    private fun buzz() {
        if (!Store.feat(this, "vib")) return
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
        }
    }

    // Bölünmüş ekran: ayırıcı pencere varsa ya da iki yarım uygulama penceresi varsa
    @Suppress("DEPRECATION")
    private fun isSplit(): Boolean {
        try {
            val ws = windows
            if (ws == null || ws.isEmpty()) return false
            val dm = DisplayMetrics()
            wm?.defaultDisplay?.getRealMetrics(dm)
            val sw = dm.widthPixels
            val sh = dm.heightPixels
            if (sw <= 0 || sh <= 0) return false
            for (w in ws) {
                if (w.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) return true
            }
            var partial = 0
            val r = Rect()
            for (w in ws) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                w.getBoundsInScreen(r)
                if (r.width() >= sw * 0.9 && r.height() >= sh * 0.85) return false
                if (r.width().toLong() * r.height() >= sw.toLong() * sh * 0.15) partial++
            }
            return partial >= 2
        } catch (e: Exception) {
            return false
        }
    }

    // Geri tuşu zinciri: bölünmüş ekransa önce kapat, sonra arka arkaya Geri, en son Ana ekran
    private fun leave() {
        if (isSplit()) performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        val n = Store.backN(this)
        for (i in 0 until n) {
            h.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 150L + i * 90L)
        }
        h.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 150L + n * 90L + 60L)
    }

    // Bölümden nazikçe çık: iki Geri, hâlâ bölümdeyse tam zincir
    private fun leaveSoft(s: Sections.Sec) {
        performGlobalAction(GLOBAL_ACTION_BACK)
        h.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 300)
        h.postDelayed({ if (sectionStillOn(s)) leave() }, 1000)
    }

    // Korumalı sayfa ya da pencereden at: Geri zinciri + Ana ekran, istenirse o uygulamanın işlemini kapat
    private fun kick(msg: String, killPkg: String? = null) {
        if (busy) return
        val now = System.currentTimeMillis()
        if (now - lastKick < 800) return
        lastKick = now
        busy = true
        logG("ATILDI: $msg")
        Store.addAttempt(this)
        buzz()
        showCover("Korumalı", msg, "", 0, Store.randomLine(this))
        leave()
        if (killPkg != null) {
            h.postDelayed({ kill(killPkg) }, 800)
            h.postDelayed({ kill(killPkg) }, 1600)
        }
        h.postDelayed({
            hideCover()
            busy = false
            current = ""
        }, Store.ssec(this) * 1000L)
    }

    // Sıkı: kalan süre akar. Katı: 3-2-1 geri sayım. Tüm ekranı kaplar, Geri tuşu zinciri çalışır.
    private fun enforce(key: String, killPkg: String?, b: Blk, title: String, sec: Sections.Sec?) {
        if (busy) return
        busy = true
        val now = System.currentTimeMillis()
        Store.addAttempt(this)
        buzz()
        var strike = false
        if (killPkg != null) {
            strikes.add(now)
            strikes.removeAll { now - it > 120000 }
            strike = strikes.size >= Policy.strikeNeed(this, b.kind)
            if (strike) strikes.clear()
        }
        val hard = b.kind == 3
        val secs = if (hard) Store.ghostSecs(this) else Store.ssec(this)
        val top = if (title.isNotEmpty()) title else if (hard) "Katı" else "Sıkı"
        val msg = Store.ghostMsg(this)
        val line = Store.randomLine(this)
        val count = "Bugün " + Store.att(this, 0) + ". deneme"
        val sub = if (line.isEmpty()) count else line + "\n" + count
        val sp = if (hard) 80 else 44
        fun big(i: Int): String = if (hard) (secs - i).toString() else fmtLeft(b.left - i * 1000L)

        showCover(top, msg, big(0), sp, sub)
        if (sec != null) leaveSoft(sec) else leave()
        if (killPkg != null) h.postDelayed({ kill(killPkg) }, 700)
        for (i in 1 until secs) {
            h.postDelayed({
                setCover(top, msg, big(i), sp, sub)
                if (killPkg != null) kill(killPkg)
                if (i == 1) {
                    if (sec == null) leave() else if (sectionStillOn(sec)) leave()
                }
            }, i * 1000L)
        }
        val endStrike = strike
        h.postDelayed({
            if (killPkg != null) kill(killPkg)
            hideCover()
            busy = false
            current = ""
            if (endStrike) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }, secs * 1000L)
    }

    private fun lockApp(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastLock < 700) return
        lastLock = now
        showCover("", "", "", 0, "")
        val i = Intent(this, LockActivity::class.java)
            .putExtra("pkg", pkg)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        try {
            startActivity(i)
        } catch (e: Exception) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
        h.postDelayed({
            if (cover != null && !busy) {
                hideCover()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }, 3000)
    }

    private fun kill(pkg: String) {
        try {
            (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg)
        } catch (e: Exception) {
        }
    }

    private fun showCover(top: String, msg: String, num: String, sp: Int, sub: String) {
        val w = wm ?: return
        if (cover == null) {
            val mp = ViewGroup.LayoutParams.MATCH_PARENT
            val fl = FrameLayout(this)
            fl.setBackgroundColor(Store.coverColor(this))
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER
            val pad = UI.dp(this, 28)
            col.setPadding(pad, pad, pad, pad)
            fun mk(size: Float, color: Int): TextView {
                val t = TextView(this)
                t.setTextColor(color)
                t.textSize = size
                t.gravity = Gravity.CENTER
                col.addView(t, LinearLayout.LayoutParams(-2, -2))
                return t
            }
            val tt = mk(18f, Color.parseColor("#AAAAAA"))
            val tm = mk(30f, Color.WHITE)
            val tn = mk(80f, Color.WHITE)
            val ts = mk(17f, Color.parseColor("#BBBBBB"))
            fl.addView(col, FrameLayout.LayoutParams(mp, mp))
            val lp = WindowManager.LayoutParams(
                mp, mp,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE
            )
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            try {
                w.addView(fl, lp)
                cover = fl
                coverTop = tt
                coverMsg = tm
                coverNum = tn
                coverSub = ts
            } catch (e: Exception) {
            }
        }
        setCover(top, msg, num, sp, sub)
    }

    private fun setCover(top: String, msg: String, num: String, sp: Int, sub: String) {
        coverTop?.text = top
        coverMsg?.text = msg
        coverNum?.text = num
        coverNum?.textSize = sp.toFloat()
        coverNum?.visibility = if (num.isEmpty()) View.GONE else View.VISIBLE
        coverSub?.text = sub
    }

    private fun hideCover() {
        try {
            val c = cover
            if (c != null) wm?.removeView(c)
        } catch (e: Exception) {
        }
        cover = null
        coverTop = null
        coverMsg = null
        coverNum = null
        coverSub = null
    }
}
