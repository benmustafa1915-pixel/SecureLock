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
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

fun norm(s: String): String =
    s.lowercase(Locale.ROOT).replace("\u0307", "").replace('ı', 'i').trim()

fun fmtLeft(ms: Long): String {
    val s = maxOf(0L, ms) / 1000
    val d = s / 86400
    val rest = String.format(Locale.US, "%d:%02d:%02d", s % 86400 / 3600, s % 3600 / 60, s % 60)
    return if (d > 0) "$d gün $rest" else rest
}

// kind: 0 serbest, 1 şifre kapısı, 2 Sıkı engel, 3 Katı engel; left: engelin bitmesine kalan süre
class Blk(val kind: Int, val left: Long)

class AdminReceiver : DeviceAdminReceiver()

class EventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Policy.refresh(context)
        val a = intent.action
        if (a == Intent.ACTION_BOOT_COMPLETED || a == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (Policy.rawMode(context) != 0) Policy.schedule(context, Policy.remaining(context))
        }
    }
}

class SecretReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

object Feat {
    val siki = listOf(
        Triple("s_acc", "Erişilebilirlik sayfaları engelli (Sıkı oturum ve Katı Koruma)", true),
        Triple("s_date", "Tarih ve saat ile Genel yönetim engelli (Sıkı oturum ve Katı Koruma)", true),
        Triple("s_lock", "Sıkı oturumda şifreler ve özelleştirme kilitli", true),
        Triple("s_keep", "Sıkı oturumda listeden çıkarma yok", true)
    )
    val kati = listOf(
        Triple("k_admin", "Cihaz yöneticisi sayfası engelli", true),
        Triple("k_strike", "Sert ceza: en fazla 2 denemede ekran kilitlenir", true),
        Triple("k_allset", "Ayarlar uygulaması tamamen engelli", false)
    )
    val prot = listOf(
        Triple("p_info", "Ayarlar'da Secure Lock bilgi sayfası engelli (Kaldır görünmez)", true),
        Triple("p_uninst", "Kaldırma penceresi engelli", true),
        Triple("p_launch", "Ana ekranda simgeye basılı tutunca blok ekranı", true),
        Triple("p_split", "Ayarlar bölünmüş ekranda açılamasın", true)
    )

    fun def(k: String): Boolean {
        for (t in siki) if (t.first == k) return t.third
        for (t in kati) if (t.first == k) return t.third
        for (t in prot) if (t.first == k) return t.third
        return false
    }
}

// Telefonun kendi ayar sayfaları: başlığından tanınır
object Pages {
    class Pg(val id: String, val name: String, tokens: List<String>) {
        val t: List<String> = tokens.map { norm(it) }
    }

    val all: List<Pg> = listOf(
        Pg("acc", "Erişilebilirlik", listOf("erişilebilirlik", "accessibility")),
        Pg("gm", "Genel yönetim", listOf("genel yönetim", "general management")),
        Pg("date", "Tarih ve saat", listOf("tarih ve saat", "date and time", "date & time")),
        Pg(
            "admin", "Cihaz yöneticisi uygulamaları",
            listOf(
                "cihaz yöneticisi uygulamaları", "cihaz yönetim uygulamaları",
                "cihaz yönetici uygulamaları", "device admin apps"
            )
        ),
        Pg("apps", "Uygulamalar", listOf("uygulamalar", "apps")),
        Pg("sec", "Güvenlik ve gizlilik", listOf("güvenlik ve gizlilik", "security and privacy", "güvenlik", "security")),
        Pg("dev", "Geliştirici seçenekleri", listOf("geliştirici seçenekleri", "developer options")),
        Pg("conn", "Bağlantılar", listOf("bağlantılar", "connections")),
        Pg("wifi", "Wi-Fi", listOf("wi-fi", "wifi")),
        Pg("bt", "Bluetooth", listOf("bluetooth")),
        Pg("batt", "Pil ve cihaz bakımı", listOf("pil ve cihaz bakımı", "battery and device care", "pil", "battery")),
        Pg("acct", "Hesaplar ve yedekleme", listOf("hesaplar ve yedekleme", "accounts and backup", "hesaplar", "accounts")),
        Pg("upd", "Yazılım güncelleme", listOf("yazılım güncelleme", "software update")),
        Pg("disp", "Ekran", listOf("ekran", "display")),
        Pg("sound", "Sesler ve titreşim", listOf("sesler ve titreşim", "sounds and vibration")),
        Pg("notif", "Bildirimler", listOf("bildirimler", "notifications")),
        Pg("priv", "Gizlilik", listOf("gizlilik", "privacy")),
        Pg("about", "Telefon hakkında", listOf("telefon hakkında", "about phone")),
        Pg("vpn", "VPN", listOf("vpn"))
    )
}

// Uygulama içi bölümler (YouTube Shorts, Instagram Reels ...): uygulamanın tamamını değil sadece bölümü engeller
// Kural satırları: id:kelime = ekrandaki bileşen adı, sec:kelime = seçili sekme, düz kelime = ekranda görünen yazı
object Sections {
    class Sec(val id: String, val name: String, val pkg: String, val defRules: String)

    private val base = listOf(
        Sec(
            "yt", "YouTube Shorts", "com.google.android.youtube",
            "id:reel_recycler\nid:reel_player_page_container\nid:reel_watch_fragment_root\nsec:shorts"
        ),
        Sec(
            "ig", "Instagram Reels", "com.instagram.android",
            "id:clips_viewer_view_pager\nid:clips_viewer_container\nsec:reels"
        ),
        Sec("fb", "Facebook Reels", "com.facebook.katana", "id:reels_viewer\nsec:reels")
    )

    fun isCustom(id: String) = id.startsWith("u")

    fun all(c: Context): List<Sec> {
        val out = ArrayList<Sec>(base)
        val raw = Store.p(c).getString("cust", "") ?: ""
        for (ln in raw.split("\n")) {
            val f = ln.split("\t")
            if (f.size >= 3 && f[0].isNotEmpty()) out.add(Sec(f[0], f[1], f[2], ""))
        }
        return out
    }

    fun rules(c: Context, s: Sec): String = Store.p(c).getString("sr_" + s.id, null) ?: s.defRules
    fun setRules(c: Context, id: String, r: String) { Store.p(c).edit().putString("sr_$id", r).apply() }
    fun resetRules(c: Context, id: String) { Store.p(c).edit().remove("sr_$id").apply() }

    fun addCustom(c: Context, name: String, pkg: String, rules: String) {
        val id = "u" + System.currentTimeMillis()
        val clean = name.replace("\t", " ").replace("\n", " ").trim()
        val raw = Store.p(c).getString("cust", "") ?: ""
        val line = id + "\t" + clean + "\t" + pkg
        Store.p(c).edit()
            .putString("cust", if (raw.isEmpty()) line else raw + "\n" + line)
            .putString("sr_$id", rules)
            .apply()
    }

    fun removeCustom(c: Context, id: String) {
        val raw = Store.p(c).getString("cust", "") ?: ""
        val kept = raw.split("\n").filter { it.isNotEmpty() && !it.startsWith(id + "\t") }
        Store.p(c).edit().putString("cust", kept.joinToString("\n")).remove("sr_$id").apply()
    }
}

object Clock {
    private fun boot(c: Context): Int = try {
        Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (e: Exception) {
        -1
    }

    fun left(c: Context, n: String): Long {
        val sp = Store.p(c)
        val kw = "t_${n}_w"
        if (!sp.contains(kw)) return 0L
        val nowE = SystemClock.elapsedRealtime()
        var endE = sp.getLong("t_${n}_e", 0L)
        val savedE = sp.getLong("t_${n}_s", 0L)
        val b = boot(c)
        val rebooted = (b != -1 && b != sp.getInt("t_${n}_b", -1)) || nowE < savedE
        val ed = sp.edit()
        var dirty = false
        if (rebooted) {
            endE = nowE + maxOf(0L, sp.getLong(kw, 0L) - System.currentTimeMillis())
            ed.putLong("t_${n}_e", endE).putLong("t_${n}_s", nowE).putInt("t_${n}_b", b)
            dirty = true
        } else if (nowE - savedE > 15000L) {
            ed.putLong("t_${n}_s", nowE)
            dirty = true
        }
        val r = endE - nowE
        if (r <= 0L) {
            clear(c, n)
            return 0L
        }
        if (dirty) ed.apply()
        return r
    }

    fun add(c: Context, n: String, ms: Long) {
        val total = left(c, n) + ms
        val nowE = SystemClock.elapsedRealtime()
        Store.p(c).edit()
            .putLong("t_${n}_e", nowE + total)
            .putLong("t_${n}_s", nowE)
            .putLong("t_${n}_w", System.currentTimeMillis() + total)
            .putInt("t_${n}_b", boot(c))
            .apply()
    }

    fun clear(c: Context, n: String) {
        Store.p(c).edit()
            .remove("t_${n}_w").remove("t_${n}_e").remove("t_${n}_s").remove("t_${n}_b")
            .apply()
    }
}

object Store {
    const val PIN = 0
    const val PASS = 1
    const val PATTERN = 2

    val coverNames = listOf("Siyah", "Koyu kırmızı", "Koyu mavi", "Koyu yeşil")
    private val coverHex = listOf("#000000", "#3B0A0A", "#0A1A3B", "#0A2E14")

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

    fun hasAppLock(c: Context, pkg: String) = p(c).contains("ap_$pkg")
    fun appType(c: Context, pkg: String) = p(c).getInt("apt_$pkg", PASS)
    fun setAppLock(c: Context, pkg: String, type: Int, s: String) {
        p(c).edit().putString("ap_$pkg", hash(c, s)).putInt("apt_$pkg", type).putInt("apl_$pkg", s.length).apply()
    }
    fun clearAppLock(c: Context, pkg: String) {
        p(c).edit().remove("ap_$pkg").remove("apt_$pkg").remove("apl_$pkg").apply()
    }
    fun typeFor(c: Context, pkg: String) = if (hasAppLock(c, pkg)) appType(c, pkg) else lockType(c)
    fun lenFor(c: Context, pkg: String) = if (hasAppLock(c, pkg)) p(c).getInt("apl_$pkg", 4) else pinLen(c)
    fun checkFor(c: Context, pkg: String, s: String) =
        if (hasAppLock(c, pkg)) p(c).getString("ap_$pkg", null) == hash(c, s) else checkLock(c, s)

    fun blocked(c: Context): Set<String> =
        HashSet<String>(p(c).getStringSet("blocked", emptySet()) ?: emptySet<String>())

    // 1 = Normal kilit, 2 = Sıkı Mod oturumuna (ders programı) dahil
    // Anahtar: uygulama paketi, "pg:..." (ayar sayfası), "sc:..." (uygulama içi bölüm), "ui:..." (uygulama içi düğme)
    fun tags(c: Context, pkg: String): Int {
        val s = p(c).getStringSet("blocked", null) ?: return 0
        if (!s.contains(pkg)) return 0
        return p(c).getInt("tag_$pkg", 3) and 3
    }

    fun setTags(c: Context, pkg: String, t: Int) {
        val m = t and 3
        val s = HashSet<String>(blocked(c))
        val e = p(c).edit()
        if (m == 0) {
            s.remove(pkg)
            e.remove("tag_$pkg")
        } else {
            s.add(pkg)
            e.putInt("tag_$pkg", m)
        }
        e.putStringSet("blocked", s).apply()
    }

    // Sıkı: öğeye özel süreli blok
    fun timerLeft(c: Context, key: String): Long = Clock.left(c, "is_$key")

    // Katı: kalıcı. Kaldırmak için Katı şifresi + 24 saat bekleme
    fun katiSet(c: Context): Set<String> =
        HashSet<String>(p(c).getStringSet("kati", emptySet()) ?: emptySet<String>())

    fun setKati(c: Context, key: String, on: Boolean) {
        val s = HashSet<String>(katiSet(c))
        if (on) s.add(key) else s.remove(key)
        p(c).edit().putStringSet("kati", s).remove("kxs_$key").apply()
        Clock.clear(c, "kx_$key")
    }

    fun katiOn(c: Context, key: String): Boolean {
        val s = p(c).getStringSet("kati", null) ?: return false
        if (!s.contains(key)) return false
        if (p(c).getBoolean("kxs_$key", false) && Clock.left(c, "kx_$key") <= 0L) {
            setKati(c, key, false)
            return false
        }
        return true
    }

    fun katiExitPending(c: Context, key: String): Boolean =
        katiOn(c, key) && p(c).getBoolean("kxs_$key", false)

    fun katiExitLeft(c: Context, key: String): Long = Clock.left(c, "kx_$key")

    fun requestKatiExit(c: Context, key: String) {
        p(c).edit().putBoolean("kxs_$key", true).apply()
        Clock.clear(c, "kx_$key")
        Clock.add(c, "kx_$key", 86400000L)
    }

    fun cancelKatiExit(c: Context, key: String) {
        p(c).edit().remove("kxs_$key").apply()
        Clock.clear(c, "kx_$key")
    }

    fun lockedNow(c: Context, key: String): Boolean =
        katiOn(c, key) || tags(c, key) != 0 || timerLeft(c, key) > 0L

    // Kaldırma kilidi: süre dolana kadar kaldırma izni verilemez, kısaltılamaz
    fun setUninLock(c: Context, ms: Long) {
        Clock.add(c, "ulock", ms)
        Clock.clear(c, "unin")
    }

    fun feat(c: Context, k: String) = p(c).getBoolean("f_$k", Feat.def(k))
    fun setFeat(c: Context, k: String, v: Boolean) { p(c).edit().putBoolean("f_$k", v).apply() }
    fun setInt(c: Context, k: String, v: Int) { p(c).edit().putInt(k, v).apply() }
    fun setStr(c: Context, k: String, v: String) { p(c).edit().putString(k, v).apply() }

    fun theme(c: Context) = p(c).getInt("theme", 0).coerceIn(0, 4)
    fun ghostSecs(c: Context) = p(c).getInt("gsec", 3)
    fun ghostMsg(c: Context): String = p(c).getString("gmsg", "Hayır.") ?: "Hayır."
    fun strikeN(c: Context) = p(c).getInt("strike", 3)
    fun relock(c: Context) = p(c).getInt("relock", 0)
    fun uninMin(c: Context) = p(c).getInt("unin_min", 10)
    fun ssec(c: Context) = p(c).getInt("ssec", 5)
    fun backN(c: Context) = p(c).getInt("backn", 4)
    fun cover(c: Context) = p(c).getInt("cover", 0).coerceIn(0, 3)
    fun coverColor(c: Context): Int = Color.parseColor(coverHex[cover(c)])

    fun pool(c: Context): List<String> =
        (p(c).getString("pool", "") ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    fun randomLine(c: Context): String {
        val l = pool(c)
        if (l.isEmpty()) return ""
        return l[SecureRandom().nextInt(l.size)]
    }

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
    fun adminActive(c: Context): Boolean = try {
        dpm(c).isAdminActive(admin(c))
    } catch (e: Exception) {
        false
    }
    fun canSecure(c: Context) =
        c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun rawMode(c: Context) = Store.p(c).getInt("s_mode", 0)

    // 0 = Normal, 1 = Sıkı oturum (ders programı)
    fun mode(c: Context): Int {
        if (rawMode(c) == 0) return 0
        refresh(c)
        return if (rawMode(c) != 0) 1 else 0
    }

    fun refresh(c: Context) {
        if (rawMode(c) != 0 && remaining(c) <= 0L) end(c)
    }

    fun remaining(c: Context): Long = Clock.left(c, "sess")

    fun start(c: Context, ms: Long) {
        Clock.clear(c, "unin")
        Store.p(c).edit().putInt("s_mode", 1).putLong("s_ms", ms).apply()
        Clock.clear(c, "sess")
        Clock.add(c, "sess", ms)
        setGray(c, true)
        schedule(c, ms)
    }

    fun end(c: Context) {
        if (rawMode(c) == 0) return
        val min = (Store.p(c).getLong("s_ms", 0L) / 60000L).toInt()
        Store.p(c).edit().putInt("s_mode", 0).apply()
        Clock.clear(c, "sess")
        setGray(c, false)
        Store.addFocus(c, minOf(min, 1440))
        try {
            (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi(c))
        } catch (e: Exception) {
        }
    }

    // Katı Koruma: kaldırma, cihaz yöneticisi, erişilebilirlik ve tarih sayfaları kalıcı engelli
    fun katiProtect(c: Context): Boolean = Store.katiOn(c, "sys")

    fun ulockLeft(c: Context): Long = Clock.left(c, "ulock")

    // Koruma anahtarı kapalı olsa bile: kilit, Katı Koruma ya da Sıkı oturum sürerken koruma açıktır
    fun protOn(c: Context, k: String): Boolean =
        Store.feat(c, k) || katiProtect(c) || ulockLeft(c) > 0L || mode(c) != 0

    // Kaldırma izni penceresi: sadece oturum, Katı Koruma ve kilit yokken, süre içinde açıktır
    fun uninstallOpen(c: Context): Boolean =
        mode(c) == 0 && !katiProtect(c) && ulockLeft(c) <= 0L && Clock.left(c, "unin") > 0L

    // Bir öğenin (uygulama, ayar sayfası, bölüm, uygulama içi düğme) şu anki durumu
    fun blockInfo(c: Context, key: String): Blk {
        if (Store.katiOn(c, key)) return Blk(3, 0L)
        val ks = Clock.left(c, "is_$key")
        val t = Store.tags(c, key)
        val m = mode(c)
        var kind = 0
        var left = 0L
        if (ks > 0L) {
            kind = 2
            left = ks
        }
        if (m == 1 && (t and 2) != 0) {
            kind = 2
            left = maxOf(left, remaining(c))
        }
        if (kind == 0 && (t and 1) != 0) kind = 1
        return Blk(kind, left)
    }

    // Telefon ayar sayfaları: hazır kurallar + sayfaya konan seviye
    fun pageInfo(c: Context, id: String): Blk {
        val b = blockInfo(c, "pg:$id")
        if (b.kind == 3) return b
        val m = mode(c)
        val kp = katiProtect(c)
        var forced = false
        if ((m >= 1 || kp) && Store.feat(c, "s_acc") && id == "acc") forced = true
        if ((m >= 1 || kp) && Store.feat(c, "s_date") && (id == "date" || id == "gm")) forced = true
        if (kp && Store.feat(c, "k_admin") && id == "admin") forced = true
        if (!forced) return b
        val left = if (m != 0) maxOf(b.left, remaining(c)) else b.left
        return Blk(if (kp) 3 else 2, left)
    }

    fun secLocked(c: Context, id: String): Boolean {
        if (Clock.left(c, "sec_$id") > 0L) return true
        return mode(c) >= 1 && Store.feat(c, "s_lock") && (id == "custom" || id == "pass")
    }

    fun strikeNeed(c: Context, kind: Int): Int {
        val n = Store.strikeN(c)
        return if (kind == 3 && Store.feat(c, "k_strike")) minOf(n, 2) else n
    }

    private fun setGray(c: Context, on: Boolean) {
        if (!Store.feat(c, "gray") || !canSecure(c)) return
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
