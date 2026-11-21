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
import android.widget.FrameLayout
import android.widget.TextView

class GuardService : AccessibilityService() {

    companion object {
        var inst: GuardService? = null
    }

    private val h = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var cover: FrameLayout? = null
    private var coverTv: TextView? = null
    private var busy = false
    private var current = ""
    private var lastLock = 0L
    private var cacheAt = 0L
    private var protSet: Set<String> = emptySet()
    private var launchSet: Set<String> = emptySet()
    private var registered = false
    private val unlocked = HashSet<String>()
    private val strikes = ArrayList<Long>()

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            unlocked.clear()
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
        unlocked.add(p)
        current = p
    }

    private fun cache() {
        val n = System.currentTimeMillis()
        if (protSet.isEmpty() || n - cacheAt > 60000) {
            protSet = Policy.protectedPkgs(this)
            launchSet = Policy.launchers(this)
            cacheAt = n
        }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) {
            val cn = e.className?.toString() ?: ""
            if (!busy && cover != null && cn.endsWith("LockActivity")) {
                h.postDelayed({ if (!busy) hideCover() }, 150)
            }
            return
        }
        cache()
        if (launchSet.contains(pkg)) {
            unlocked.clear()
            current = ""
            return
        }
        if (protSet.contains(pkg)) return
        if (pkg != current) {
            unlocked.remove(current)
            current = pkg
        }
        val mode = Policy.mode(this)
        val target = Store.blocked(this).contains(pkg) || (mode == 2 && pkg == "com.android.settings")
        if (!target) return
        if (mode != 0) ghost(pkg) else if (!unlocked.contains(pkg)) lockApp(pkg)
    }

    // Hayalet Mod: siyah ekran, 3 saniye geri sayim, uygulamayi oldur, ana ekrana at
    private fun ghost(pkg: String) {
        if (busy) return
        busy = true
        val now = System.currentTimeMillis()
        Store.addAttempt(this)
        strikes.add(now)
        strikes.removeAll { now - it > 120000 }
        val strike = strikes.size >= 3
        if (strike) strikes.clear()
        showCover("3")
        performGlobalAction(GLOBAL_ACTION_HOME)
        h.postDelayed({ kill(pkg) }, 500)
        h.postDelayed({ setCover("2"); kill(pkg) }, 1000)
        h.postDelayed({ setCover("1") }, 2000)
        h.postDelayed({
            kill(pkg)
            hideCover()
            busy = false
            current = ""
            if (strike) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }, 3000)
    }

    private fun lockApp(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastLock < 700) return
        lastLock = now
        showCover("")
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

    private fun showCover(msg: String) {
        val w = wm ?: return
        if (cover == null) {
            val mp = ViewGroup.LayoutParams.MATCH_PARENT
            val fl = FrameLayout(this)
            fl.setBackgroundColor(Color.BLACK)
            val tv = TextView(this)
            tv.setTextColor(Color.WHITE)
            tv.textSize = 72f
            tv.gravity = Gravity.CENTER
            fl.addView(tv, FrameLayout.LayoutParams(mp, mp))
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
                coverTv = tv
            } catch (e: Exception) {
            }
        }
        coverTv?.text = msg
    }

    private fun setCover(msg: String) {
        coverTv?.text = msg
    }

    private fun hideCover() {
        try {
            val c = cover
            if (c != null) wm?.removeView(c)
        } catch (e: Exception) {
        }
        cover = null
        coverTv = null
    }
}
