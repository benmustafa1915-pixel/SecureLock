package com.securelock.app

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class GuardService : AccessibilityService() {

    companion object {
        var inst: GuardService? = null
        private val ACC_T = listOf("erişilebilirlik", "accessibility").map { norm(it) }
        private val DATE_T = listOf(
            "tarih ve saat", "date and time", "date & time", "genel yönetim", "general management"
        ).map { norm(it) }
        private val ADMIN_T = listOf(
            "cihaz yöneticisi uygulamaları", "cihaz yönetimi uygulamaları",
            "cihaz yönetim uygulamaları", "cihaz yönetici uygulamaları",
            "device admin apps", "device administrators"
        ).map { norm(it) }
    }

    private val h = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var cover: FrameLayout? = null
    private var coverMsg: TextView? = null
    private var coverNum: TextView? = null
    private var busy = false
    private var current = ""
    private var lastLock = 0L
    private var lastKick = 0L
    private var lastScan = 0L
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
        current = p
    }

    private fun isUnlocked(pkg: String): Boolean {
        val t = unlockAt[pkg] ?: return false
        val rl = Store.relock(this)
        if (rl > 0) {
            if (System.currentTimeMillis() - t < rl * 60000L) return true
            unlockAt.remove(pkg)
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

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        val type = e.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        val pkg = e.packageName?.toString() ?: return

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
            if (busy || Policy.rawMode(this) == 0 || !guardable(pkg)) return
            val now = System.currentTimeMillis()
            if (now - lastScan > 250) {
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
            if (Store.relock(this) == 0) unlockAt.remove(current)
            current = pkg
        }
        val mode = Policy.mode(this)
        if (mode != 0 && guardable(pkg)) {
            if (pageGuard(pkg)) return
            h.postDelayed({ recheck(pkg) }, 400)
        }
        val act = Policy.action(this, pkg)
        if (act == 2) ghost(pkg) else if (act == 1 && !isUnlocked(pkg)) lockApp(pkg)
    }

    private fun recheck(pkg: String) {
        if (!busy && current == pkg && Policy.mode(this) != 0) pageGuard(pkg)
    }

    // Ayarlar sayfalarını başlıklarından tanıyıp içeri sokmaz
    private fun pageGuard(pkg: String): Boolean {
        val mode = Policy.mode(this)
        if (mode == 0) return false
        if (mode == 2 && Store.feat(this, "k_allset") && pkg == "com.android.settings") {
            kick("Ayarlar kilitli")
            return true
        }
        val t = pageTexts(pkg)
        if (t.isEmpty()) return false
        val head = t.take(8)
        val ours = t.any { it.contains("secure lock") }
        var hit = ""
        if (Store.feat(this, "s_acc") && head.any { ACC_T.contains(it) }) hit = "Erişilebilirlik kilitli"
        if (hit.isEmpty() && Store.feat(this, "s_date") && head.any { DATE_T.contains(it) }) hit = "Tarih ve saat kilitli"
        if (hit.isEmpty() && mode == 2 && Store.feat(this, "k_admin") && head.any { ADMIN_T.contains(it) }) {
            hit = "Cihaz yöneticisi kilitli"
        }
        if (hit.isEmpty() && ours) {
            val info = (mode == 2 && Store.feat(this, "k_info")) || Store.feat(this, "s_info")
            val acc = Store.feat(this, "s_acc") &&
                t.any { it.contains("erişilebilirlik") || it.contains("accessibility") }
            if (info || acc) hit = "Secure Lock korumalı"
        }
        if (hit.isEmpty()) return false
        kick(hit)
        return true
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
        if (n == null || out.size >= 90 || d > 30) return
        val t = n.text
        if (t != null && t.isNotEmpty()) out.add(norm(t.toString()))
        val cd = n.contentDescription
        if (cd != null && cd.isNotEmpty()) out.add(norm(cd.toString()))
        for (i in 0 until n.childCount) collect(n.getChild(i), out, d + 1)
    }

    private fun kick(msg: String) {
        if (busy) return
        val now = System.currentTimeMillis()
        if (now - lastKick < 800) return
        lastKick = now
        Store.addAttempt(this)
        showCover(msg, "")
        performGlobalAction(GLOBAL_ACTION_HOME)
        h.postDelayed({ if (!busy) hideCover() }, 1200)
    }

    // Hayalet Mod: siyah ekran, geri sayım, uygulamayı öldür, ana ekrana at
    private fun ghost(pkg: String) {
        if (busy) return
        busy = true
        val now = System.currentTimeMillis()
        Store.addAttempt(this)
        strikes.add(now)
        strikes.removeAll { now - it > 120000 }
        val strike = strikes.size >= Policy.strikeNeed(this)
        if (strike) strikes.clear()
        val n = Store.ghostSecs(this)
        val msg = Store.ghostMsg(this)
        showCover(msg, n.toString())
        performGlobalAction(GLOBAL_ACTION_HOME)
        h.postDelayed({ kill(pkg) }, 400)
        for (i in 1 until n) {
            val left = n - i
            h.postDelayed({ setCover(msg, left.toString()); kill(pkg) }, i * 1000L)
        }
        h.postDelayed({
            kill(pkg)
            hideCover()
            busy = false
            current = ""
            if (strike) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }, n * 1000L)
    }

    private fun lockApp(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastLock < 700) return
        lastLock = now
        showCover("", "")
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

    private fun showCover(msg: String, num: String) {
        val w = wm ?: return
        if (cover == null) {
            val mp = ViewGroup.LayoutParams.MATCH_PARENT
            val fl = FrameLayout(this)
            fl.setBackgroundColor(Color.BLACK)
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER
            val tm = TextView(this)
            tm.setTextColor(Color.WHITE)
            tm.textSize = 24f
            tm.gravity = Gravity.CENTER
            val tn = TextView(this)
            tn.setTextColor(Color.WHITE)
            tn.textSize = 72f
            tn.gravity = Gravity.CENTER
            col.addView(tm, LinearLayout.LayoutParams(-2, -2))
            col.addView(tn, LinearLayout.LayoutParams(-2, -2))
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
                coverMsg = tm
                coverNum = tn
            } catch (e: Exception) {
            }
        }
        setCover(msg, num)
    }

    private fun setCover(msg: String, num: String) {
        coverMsg?.text = msg
        coverNum?.text = num
    }

    private fun hideCover() {
        try {
            val c = cover
            if (c != null) wm?.removeView(c)
        } catch (e: Exception) {
        }
        cover = null
        coverMsg = null
        coverNum = null
    }
}
