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

class GuardService : AccessibilityService() {

    companion object {
        var inst: GuardService? = null
        private val INFO_W = listOf(
            "kaldır", "zorla durdur", "uninstall", "force stop", "devre dışı bırak", "disable"
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
    private var cacheAt = 0L
    private var protSet: Set<String> = emptySet()
    private var launchSet: Set<String> = emptySet()
    private var registered = false
    private val unlockAt = HashMap<String, Long>()
    private val strikes = ArrayList<Long>()

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

    override fun onServiceConnected() {
        inst = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (!registered) {
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
            registered = true
        }
        h.removeCallbacks(ticker)
        h.post(ticker)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        inst = null
        h.removeCallbacksAndMessages(null)
        hideCover()
        if (registered) {
            try { unregisterReceiver(screenOff) } catch (e: Exception) {}
            registered = false
        }
    }

    fun markUnlocked(p: String) {
        unlockAt[p] = System.currentTimeMillis()
        if (!p.startsWith("pg:")) current = p
    }

    private fun clearPages() {
        val iter = unlockAt.keys.iterator()
        while (iter.hasNext()) {
            if (iter.next().startsWith("pg:")) iter.remove()
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

    private fun guardable(pkg: String) = pkg.contains("settings") || pkg.contains("packageinstaller")

    private fun watching(): Boolean =
        Policy.rawMode(this) != 0 || Store.feat(this, "p_info") || Store.feat(this, "p_uninst") ||
            Store.blocked(this).any { it.startsWith("pg:") } ||
            Pages.all.any { Store.timerLeft(this, "pg:" + it.id) > 0L }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        val type = e.eventType
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
                    h.postDelayed({ if (!busy) hideCover() }, 150)
                }
            }
            return
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (busy) return
            val now = System.currentTimeMillis()
            cache()
            // Engelli uygulamada bir şey yapılırsa yine engel + geri tuşu zinciri
            if (pkg == current && !protSet.contains(pkg) && !launchSet.contains(pkg) && now - lastEnforce > 800) {
                lastEnforce = now
                val b = Policy.blockInfo(this, pkg)
                if (b.kind >= 2) {
                    enforce(pkg, pkg, b, "")
                    return
                }
            }
            if (guardable(pkg) && watching() && now - lastScan > 250) {
                lastScan = now
                pageGuard(pkg)
            }
            return
        }

        cache()
        if (launchSet.contains(pkg)) {
            if (Store.relock(this) == 0) unlockAt.clear()
            current = ""
            return
        }
        if (protSet.contains(pkg)) return
        if (pkg != current) {
            if (Store.relock(this) == 0) {
                unlockAt.remove(current)
                if (current.contains("settings")) clearPages()
            }
            current = pkg
        }
        if (guardable(pkg) && watching()) {
            if (pageGuard(pkg)) return
            h.postDelayed({ recheck(pkg) }, 400)
            h.postDelayed({ recheck(pkg) }, 1200)
        }
        val b = Policy.blockInfo(this, pkg)
        if (b.kind >= 2) {
            enforce(pkg, pkg, b, "")
        } else if (b.kind == 1 && !isUnlocked(pkg)) {
            if (isSplit()) kick("Bölünmüş ekran engelli") else lockApp(pkg)
        }
    }

    // Ana ekranda Secure Lock simgesine basılı tutulursa Kaldır menüsü yerine blok ekranı
    private fun longClick(e: AccessibilityEvent, pkg: String) {
        if (busy || !Store.feat(this, "p_launch") || Policy.uninstallOpen(this)) return
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
        if (!busy && current == pkg && watching()) pageGuard(pkg)
    }

    // Ayarlar sayfalarını başlıklarından tanıyıp içeri sokmaz
    private fun pageGuard(pkg: String): Boolean {
        val mode = Policy.mode(this)
        if (mode == 2 && Store.feat(this, "k_allset") && pkg == "com.android.settings") {
            kick("Ayarlar kilitli")
            return true
        }
        val t = pageTexts(pkg)
        if (t.isEmpty()) return false
        val open = Policy.uninstallOpen(this)

        if (pkg.contains("packageinstaller")) {
            if (!open && Store.feat(this, "p_uninst") && t.any { it.contains("secure lock") }) {
                kick("Kaldırma engelli")
                return true
            }
            return false
        }

        if (t.take(12).any { it == "secure lock" }) {
            val info = t.any { INFO_W.contains(it) }
            if (!open && (mode != 0 || (info && Store.feat(this, "p_info")))) {
                kick("Secure Lock korumalı")
                return true
            }
        }

        val head = t.take(5)
        if (head.take(2).any { it == "ayarlar" || it == "settings" }) return false
        for (pg in Pages.all) {
            if (head.none { pg.t.contains(it) }) continue
            val key = "pg:" + pg.id
            val b = Policy.pageInfo(this, pg.id)
            if (b.kind >= 2) {
                enforce(key, null, b, pg.name)
                return true
            }
            if (b.kind == 1 && !isUnlocked(key)) {
                lockApp(key)
                return true
            }
        }
        return false
    }

    private fun pageTexts(pkg: String): List<String> {
        val out = ArrayList<String>()
        try {
            val root = rootInActiveWindow ?: return out
            if (root.packageName?.toString() != pkg) return out
            collect(root, out, 0)
        } catch (e: Exception) {
        }
        return out
    }

    private fun collect(n: AccessibilityNodeInfo?, out: ArrayList<String>, d: Int) {
        if (n == null || out.size >= 140 || d > 30) return
        val t = n.text
        if (t != null && t.isNotEmpty()) out.add(norm(t.toString()))
        val cd = n.contentDescription
        if (cd != null && cd.isNotEmpty()) out.add(norm(cd.toString()))
        for (i in 0 until n.childCount) collect(n.getChild(i), out, d + 1)
    }

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

    private fun kick(msg: String) {
        if (busy) return
        val now = System.currentTimeMillis()
        if (now - lastKick < 800) return
        lastKick = now
        busy = true
        Store.addAttempt(this)
        buzz()
        showCover("Korumalı", msg, "", 0, Store.randomLine(this))
        leave()
        h.postDelayed({
            hideCover()
            busy = false
            current = ""
        }, Store.ssec(this) * 1000L)
    }

    // Sıkı: kalan süre akar. Katı: 3-2-1 geri sayım. İkisinde de Geri tuşu zinciri çalışır.
    private fun enforce(key: String, killPkg: String?, b: Blk, title: String) {
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
        val top = if (title.isNotEmpty()) title else if (hard) "Katı Mod" else "Sıkı Mod"
        val msg = Store.ghostMsg(this)
        val sub = Store.randomLine(this)
        val sp = if (hard) 72 else 40
        fun big(i: Int): String = if (hard) (secs - i).toString() else fmtLeft(b.left - i * 1000L)

        showCover(top, msg, big(0), sp, sub)
        leave()
        if (killPkg != null) h.postDelayed({ kill(killPkg) }, 700)
        for (i in 1 until secs) {
            h.postDelayed({
                setCover(top, msg, big(i), sp, sub)
                if (killPkg != null) kill(killPkg)
                if (i == 1) leave()
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
            val tt = mk(15f, Color.parseColor("#999999"))
            val tm = mk(24f, Color.WHITE)
            val tn = mk(72f, Color.WHITE)
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
