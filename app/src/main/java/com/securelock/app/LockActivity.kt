package com.securelock.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout

class LockActivity : Activity() {
    private var pkg = ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        pkg = intent.getStringExtra("pkg") ?: ""
        build()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        pkg = intent?.getStringExtra("pkg") ?: ""
        build()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        goHome()
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    private fun ok() {
        GuardService.inst?.markUnlocked(pkg)
        finish()
        overridePendingTransition(0, 0)
    }

    private fun build() {
        val hasApp = Store.hasAppPass(this, pkg)
        val type = if (hasApp) Store.PASS else Store.lockType(this)
        val check: (String) -> Boolean = { s ->
            if (hasApp) Store.checkAppPass(this, pkg, s) else Store.checkLock(this, s)
        }
        val v: View = when (type) {
            Store.PIN -> pinView(check)
            Store.PATTERN -> patternView(check)
            else -> passView(check)
        }
        setContentView(v)
    }

    private fun base(): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.VERTICAL
        r.setBackgroundColor(Color.BLACK)
        r.setPadding(UI.dp(this, 24), UI.dp(this, 48), UI.dp(this, 24), UI.dp(this, 24))
        return r
    }

    private fun pinView(check: (String) -> Boolean): View {
        val len = Store.pinLen(this)
        var buf = ""
        val root = base()
        val dots = UI.tv(this, "", 28f)
        dots.gravity = Gravity.CENTER
        val msg = UI.tv(this, " ", 15f, Color.parseColor("#E57373"))
        msg.gravity = Gravity.CENTER
        fun draw() {
            dots.text = (0 until len).joinToString("  ") { if (it < buf.length) "●" else "○" }
        }
        draw()
        root.addView(dots, LinearLayout.LayoutParams(-1, -2))
        root.addView(msg, LinearLayout.LayoutParams(-1, -2))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "Çık", "0", "Sil")
        val m = UI.dp(this, 5)
        for (r in 0 until 4) {
            val row = LinearLayout(this)
            for (k in 0 until 3) {
                val key = keys[r * 3 + k]
                val b = UI.button(this, key, UI.CARD) {
                    when (key) {
                        "Çık" -> goHome()
                        "Sil" -> {
                            if (buf.isNotEmpty()) buf = buf.dropLast(1)
                            draw()
                        }
                        else -> {
                            if (buf.length < len) buf += key
                            draw()
                            if (buf.length == len) {
                                if (check(buf)) {
                                    ok()
                                } else {
                                    msg.text = "Yanlış"
                                    buf = ""
                                    draw()
                                }
                            }
                        }
                    }
                }
                b.textSize = 28f
                val blp = LinearLayout.LayoutParams(0, -1, 1f)
                blp.setMargins(m, m, m, m)
                row.addView(b, blp)
            }
            root.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return root
    }

    private fun passView(check: (String) -> Boolean): View {
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        val root = base()
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        et.imeOptions = EditorInfo.IME_ACTION_DONE
        et.setTextColor(UI.TXT)
        et.setHintTextColor(UI.DIM)
        et.hint = "Parola"
        et.textSize = 22f
        et.isSingleLine = true
        val msg = UI.tv(this, " ", 15f, Color.parseColor("#E57373"))
        fun submit() {
            if (check(et.text.toString())) {
                ok()
            } else {
                msg.text = "Yanlış"
                et.setText("")
            }
        }
        et.setOnEditorActionListener { _, _, _ -> submit(); true }
        root.addView(et, UI.lp(this, 0))
        root.addView(msg, UI.lp(this, 6))
        root.addView(UI.button(this, "Aç", UI.ACC) { submit() }, UI.lp(this, 12))
        root.addView(UI.button(this, "Ana ekran") { goHome() }, UI.lp(this, 8))
        et.requestFocus()
        return root
    }

    private fun patternView(check: (String) -> Boolean): View {
        val root = base()
        root.gravity = Gravity.CENTER
        val msg = UI.tv(this, " ", 15f, Color.parseColor("#E57373"))
        msg.gravity = Gravity.CENTER
        val pv = PatternView(this)
        pv.onDone = { code ->
            if (code.length >= 4 && check(code)) {
                ok()
            } else {
                msg.text = "Yanlış"
                pv.flashError()
            }
        }
        root.addView(msg, LinearLayout.LayoutParams(-1, -2))
        root.addView(pv, LinearLayout.LayoutParams(-1, -2))
        root.addView(UI.button(this, "Ana ekran") { goHome() }, UI.lp(this, 16))
        return root
    }
}
