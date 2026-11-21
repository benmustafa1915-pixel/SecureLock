package com.securelock.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlin.math.hypot

object UI {
    val BG = Color.parseColor("#000000")
    val CARD = Color.parseColor("#17171B")
    val TXT = Color.parseColor("#EDEDED")
    val DIM = Color.parseColor("#8A8A93")
    val RED = Color.parseColor("#B71C1C")
    var ACC: Int = Color.parseColor("#2E7D32")
    val accentNames = listOf("Yeşil", "Mavi", "Mor", "Turuncu", "Kırmızı")
    private val accents = listOf("#2E7D32", "#1565C0", "#6A1B9A", "#E65100", "#B71C1C")

    fun useTheme(c: Context) {
        ACC = Color.parseColor(accents[Store.theme(c)])
    }

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    fun round(c: Context, color: Int, r: Int = 14): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(c, r).toFloat()
        return g
    }

    fun tv(c: Context, s: String, size: Float = 16f, color: Int = TXT, bold: Boolean = false): TextView {
        val t = TextView(c)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    fun label(c: Context, s: String): TextView = tv(c, s, 13f, ACC, true)

    fun button(c: Context, s: String, color: Int = CARD, on: () -> Unit): Button {
        val b = Button(c)
        b.text = s
        b.isAllCaps = false
        b.setTextColor(TXT)
        b.textSize = 16f
        b.minWidth = 0
        b.minimumWidth = 0
        b.background = round(c, color)
        b.stateListAnimator = null
        b.setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14))
        b.setOnClickListener { on() }
        return b
    }

    fun input(c: Context, hint: String, numeric: Boolean = false): EditText {
        val e = EditText(c)
        e.hint = hint
        e.setTextColor(TXT)
        e.setHintTextColor(DIM)
        e.textSize = 18f
        e.inputType = if (numeric) {
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        e.isSingleLine = true
        return e
    }

    fun lp(c: Context, top: Int = 10): LinearLayout.LayoutParams {
        val l = LinearLayout.LayoutParams(-1, -2)
        l.topMargin = dp(c, top)
        return l
    }

    fun card(c: Context): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.VERTICAL
        l.background = round(c, CARD)
        l.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14))
        return l
    }

    fun rowBtn(c: Context, title: String, value: String, on: () -> Unit): LinearLayout {
        val r = card(c)
        r.addView(tv(c, title, 16f))
        if (value.isNotEmpty()) r.addView(tv(c, value, 13f, DIM), lp(c, 2))
        r.isClickable = true
        r.setOnClickListener { on() }
        return r
    }

    fun page(c: Context): Pair<ScrollView, LinearLayout> {
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(c, 18), dp(c, 28), dp(c, 18), dp(c, 28))
        val sv = ScrollView(c)
        sv.setBackgroundColor(BG)
        sv.isFillViewport = true
        sv.addView(col)
        return Pair(sv, col)
    }

    fun switchRow(c: Context, text: String, on: Boolean, change: (Boolean) -> Unit): LinearLayout {
        val r = card(c)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.addView(tv(c, text, 16f), LinearLayout.LayoutParams(0, -2, 1f))
        val s = Switch(c)
        s.isChecked = on
        s.setOnCheckedChangeListener { _, v -> change(v) }
        r.addView(s)
        return r
    }
}

class PatternView(c: Context) : View(c) {
    var onDone: (String) -> Unit = {}
    private val sel = ArrayList<Int>()
    private var tx = 0f
    private var ty = 0f
    private var down = false
    private var err = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w)
        setMeasuredDimension(s, s)
    }

    private fun cx(i: Int): Float = width * (2 * (i % 3) + 1) / 6f
    private fun cy(i: Int): Float = height * (2 * (i / 3) + 1) / 6f

    fun clear() {
        sel.clear()
        down = false
        err = false
        invalidate()
    }

    fun flashError() {
        err = true
        down = false
        invalidate()
        postDelayed({ clear() }, 700)
    }

    private fun hit(x: Float, y: Float) {
        for (i in 0..8) {
            if (!sel.contains(i) && hypot(x - cx(i), y - cy(i)) < width / 8f) {
                sel.add(i)
                break
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sel.clear()
                err = false
                down = true
                hit(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> hit(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                down = false
                if (sel.isNotEmpty()) onDone(sel.joinToString(""))
            }
        }
        tx = e.x
        ty = e.y
        invalidate()
        return true
    }

    override fun onDraw(cv: Canvas) {
        val col = if (err) Color.parseColor("#E57373") else Color.parseColor("#66BB6A")
        p.color = col
        p.strokeWidth = width / 60f
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        for (i in 1 until sel.size) {
            cv.drawLine(cx(sel[i - 1]), cy(sel[i - 1]), cx(sel[i]), cy(sel[i]), p)
        }
        if (down && sel.isNotEmpty()) {
            cv.drawLine(cx(sel.last()), cy(sel.last()), tx, ty, p)
        }
        p.style = Paint.Style.FILL
        for (i in 0..8) {
            val on = sel.contains(i)
            p.color = if (on) col else Color.parseColor("#8A8A93")
            cv.drawCircle(cx(i), cy(i), if (on) width / 22f else width / 40f, p)
        }
    }
}

class BarChart(
    c: Context,
    private val title: String,
    private val labels: List<String>,
    private val vals: List<Int>,
    private val color: Int
) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (resources.displayMetrics.density * 190).toInt())
    }

    override fun onDraw(cv: Canvas) {
        val d = resources.displayMetrics.density
        p.textAlign = Paint.Align.LEFT
        p.color = Color.parseColor("#EDEDED")
        p.textSize = 14 * d
        cv.drawText(title, 0f, 16 * d, p)
        val max = maxOf(1, vals.maxOrNull() ?: 1)
        val n = vals.size
        val top = 36 * d
        val bottom = height - 24 * d
        val slot = width / n.toFloat()
        for (i in 0 until n) {
            val bh = (bottom - top) * vals[i] / max
            p.color = color
            cv.drawRoundRect(i * slot + slot * 0.2f, bottom - bh, i * slot + slot * 0.8f, bottom, 6 * d, 6 * d, p)
            p.color = Color.parseColor("#8A8A93")
            p.textSize = 11 * d
            p.textAlign = Paint.Align.CENTER
            cv.drawText(labels[i], i * slot + slot / 2, height - 6 * d, p)
            cv.drawText(vals[i].toString(), i * slot + slot / 2, bottom - bh - 4 * d, p)
        }
    }
}
