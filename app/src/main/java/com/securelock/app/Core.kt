package com.securelock.app

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AdminReceiver : DeviceAdminReceiver()

class EventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Policy.refresh(context)
        val a = intent.action
        if (a == Intent.ACTION_BOOT_COMPLETED || a == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (Policy.mode(context) != 0) Policy.schedule(context, Policy.remaining(context))
        }
    }
}

class SecretReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

object Store {
    const val PIN = 0
    const val PASS = 1
    const val PATTERN = 2

    fun p(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("sl", Context.MODE_PRIVATE)

    private fun salt(c: Context): String {
        var s = p(c).getString("salt", null)
        if (s == null) {
            val b = ByteArray(16)
            SecureRandom().nextBytes(b)
            s = b.joinToString("") { "%02x".format(it) }
            p(c).edit().putString("salt", s).apply()
        }
        return s
    }

    private fun hash(c: Context, s: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt(c) + s).toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun isSetup(c: Context) = p(c).contains("admin")
    fun setAdmin(c: Context, s: String) { p(c).edit().putString("admin", hash(c, s)).apply() }
    fun checkAdmin(c: Context, s: String) = p(c).getString("admin", null) == hash(c, s)
    fun setStrict(c: Context, s: String) { p(c).edit().putString("strict", hash(c, s)).apply() }
    fun checkStrict(c: Context, s: String) = p(c).getString("strict", null) == hash(c, s)

    fun lockType(c: Context) = p(c).getInt("ltype", PIN)
    fun pinLen(c: Context) = p(c).getInt("plen", 4)
    fun setLock(c: Context, type: Int, s: String) {
        p(c).edit().putInt("ltype", type).putInt("plen", s.length).putString("lock", hash(c, s)).apply()
    }
    fun checkLock(c: Context, s: String) = p(c).getString("lock", null) == hash(c, s)

    fun hasAppPass(c: Context, pkg: String) = p(c).contains("ap_$pkg")
    fun setAppPass(c: Context, pkg: String, s: String) {
        val e = p(c).edit()
        if (s.isEmpty()) e.remove("ap_$pkg") else e.putString("ap_$pkg", hash(c, s))
        e.apply()
    }
    fun checkAppPass(c: Context, pkg: String, s: String) = p(c).getString("ap_$pkg", null) == hash(c, s)

    fun blocked(c: Context): Set<String> =
        HashSet<String>(p(c).getStringSet("blocked", emptySet()) ?: emptySet<String>())

    fun setBlocked(c: Context, pkg: String, on: Boolean) {
        val s = HashSet<String>(blocked(c))
        if (on) s.add(pkg) else s.remove(pkg)
        p(c).edit().putStringSet("blocked", s).apply()
    }

    fun flag(c: Context, k: String) = p(c).getBoolean("f_$k", false)
    fun setFlag(c: Context, k: String, v: Boolean) { p(c).edit().putBoolean("f_$k", v).apply() }

    private fun cal(off: Int): Calendar {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, -off)
        return c
    }
    private fun key(off: Int): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(cal(off).time)
    fun label(off: Int): String = SimpleDateFormat("EEE", Locale("tr")).format(cal(off).time)

    fun addAttempt(c: Context) {
        val k = "att_" + key(0)
        p(c).edit().putInt(k, p(c).getInt(k, 0) + 1).apply()
    }
    fun addFocus(c: Context, m: Int) {
        val k = "foc_" + key(0)
        p(c).edit().putInt(k, p(c).getInt(k, 0) + m).apply()
    }
    fun att(c: Context, off: Int) = p(c).getInt("att_" + key(off), 0)
    fun foc(c: Context, off: Int) = p(c).getInt("foc_" + key(off), 0)
    fun streak(c: Context): Int {
        var i = if (foc(c, 0) > 0) 0 else 1
        var n = 0
        while (i < 400 && foc(c, i) > 0) { n++; i++ }
        return n
    }
}

object Policy {
    fun admin(c: Context) = ComponentName(c, AdminReceiver::class.java)
    fun dpm(c: Context) = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    fun isOwner(c: Context) = dpm(c).isDeviceOwnerApp(c.packageName)
    fun canSecure(c: Context) =
        c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun rawMode(c: Context) = Store.p(c).getInt("s_mode", 0)

    fun mode(c: Context): Int {
        refresh(c)
        return rawMode(c)
    }

    fun refresh(c: Context) {
        if (rawMode(c) != 0 && remaining(c) <= 0L) end(c)
    }

    fun remaining(c: Context): Long {
        if (rawMode(c) == 0) return 0L
        val sp = Store.p(c)
        val nowE = SystemClock.elapsedRealtime()
        var endE = sp.getLong("s_end_e", 0L)
        if (nowE < sp.getLong("s_saved_e", 0L)) {
            endE = nowE + maxOf(0L, sp.getLong("s_end_w", 0L) - System.currentTimeMillis())
            sp.edit().putLong("s_end_e", endE).apply()
        }
        sp.edit().putLong("s_saved_e", nowE).apply()
        return maxOf(0L, endE - nowE)
    }

    fun start(c: Context, mode: Int, minutes: Int) {
        val nowE = SystemClock.elapsedRealtime()
        val ms = minutes * 60000L
        Store.p(c).edit()
            .putInt("s_mode", mode)
            .putInt("s_min", minutes)
            .putLong("s_end_e", nowE + ms)
            .putLong("s_saved_e", nowE)
            .putLong("s_end_w", System.currentTimeMillis() + ms)
            .apply()
        setGray(c, true)
        if (mode == 2) strict(c, true)
        schedule(c, ms)
    }

    fun end(c: Context) {
        val mode = rawMode(c)
        if (mode == 0) return
        val min = Store.p(c).getInt("s_min", 0)
        Store.p(c).edit().putInt("s_mode", 0).apply()
        if (mode == 2) strict(c, false)
        setGray(c, false)
        Store.addFocus(c, min)
        try {
            (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi(c))
        } catch (e: Exception) {
        }
    }

    private fun strict(c: Context, on: Boolean) {
        if (!isOwner(c)) return
        try {
            val d = dpm(c)
            val a = admin(c)
            d.setUninstallBlocked(a, c.packageName, on)
            d.setUserControlDisabledPackages(a, if (on) listOf(c.packageName) else emptyList<String>())
            val rs = listOf(
                UserManager.DISALLOW_SAFE_BOOT,
                UserManager.DISALLOW_FACTORY_RESET,
                UserManager.DISALLOW_CONFIG_DATE_TIME
            )
            for (r in rs) {
                if (on) d.addUserRestriction(a, r) else d.clearUserRestriction(a, r)
            }
        } catch (e: Exception) {
        }
    }

    private fun setGray(c: Context, on: Boolean) {
        if (!Store.flag(c, "gray") || !canSecure(c)) return
        try {
            Settings.Secure.putInt(c.contentResolver, "accessibility_display_daltonizer_enabled", if (on) 1 else 0)
            if (on) Settings.Secure.putInt(c.contentResolver, "accessibility_display_daltonizer", 0)
        } catch (e: Exception) {
        }
    }

    private fun pi(c: Context): PendingIntent =
        PendingIntent.getBroadcast(
            c, 7,
            Intent(c, EventReceiver::class.java).setAction("com.securelock.app.END"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun schedule(c: Context, ms: Long) {
        try {
            (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + ms + 1000,
                pi(c)
            )
        } catch (e: Exception) {
        }
    }

    fun launchers(c: Context): Set<String> {
        val s = HashSet<String>()
        s.add("com.sec.android.app.launcher")
        try {
            c.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            ).forEach { s.add(it.activityInfo.packageName) }
        } catch (e: Exception) {
        }
        return s
    }

    // Telefonu tuğlaya çevirmemek için: bunlar asla engellenemez
    fun protectedPkgs(c: Context): Set<String> {
        val s = HashSet<String>(launchers(c))
        s.addAll(
            listOf(
                c.packageName, "android", "com.android.systemui", "com.android.phone",
                "com.android.server.telecom", "com.samsung.android.incallui",
                "com.samsung.android.dialer", "com.google.android.dialer",
                "com.android.emergency", "com.samsung.android.app.telephonyui"
            )
        )
        try {
            c.packageManager.queryIntentActivities(Intent(Intent.ACTION_DIAL, Uri.parse("tel:123")), 0)
                .forEach { s.add(it.activityInfo.packageName) }
            (c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .enabledInputMethodList.forEach { s.add(it.packageName) }
        } catch (e: Exception) {
        }
        return s
    }
}
