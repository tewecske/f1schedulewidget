package dev.f1schedule

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Per-widget settings, opened from the widget's long-press menu: background colour and opacity. */
class SettingsActivity : Activity() {
    private var id = AppWidgetManager.INVALID_APPWIDGET_ID
    private var rgb = 0
    private var bgAlpha = 0
    private val preview = GradientDrawable()
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var opacity: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        // Before Android 12 this opens when the widget is added; backing out still keeps the widget.
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))

        val bg = RaceWidget.background(this, id)
        rgb = bg and 0xFFFFFF
        bgAlpha = Color.alpha(bg)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 8.dp, 20.dp, 12.dp)
        }

        // Preview of the widget's top lines.
        preview.cornerRadius = 20.dp.toFloat()
        title = TextView(this).apply { text = "United States Grand Prix"; textSize = 16f }
        sub = TextView(this).apply { text = "Practice 1        Fri  7:30 PM"; textSize = 13f }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = preview
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            addView(title)
            addView(sub)
        })

        val hex = EditText(this).apply {
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(7))
            setText(String.format("#%06X", rgb))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable) {
                    val t = s.toString().removePrefix("#")
                    if (t.length == 6) t.toIntOrNull(16)?.let { rgb = it; update() }
                }
            })
        }

        // Preset colours; any other via hex.
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, 16.dp, 0, 4.dp)
            for (c in SWATCHES) addView(View(this@SettingsActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c or 0xFF000000.toInt())
                    setStroke(1.dp, 0x80808080.toInt())
                }
                setOnClickListener { hex.setText(String.format("#%06X", c)) }
            }, LinearLayout.LayoutParams(32.dp, 32.dp).apply { setMargins(4.dp, 0, 4.dp, 0) })
        })

        root.addView(row(label("Colour"), hex))

        opacity = label("")
        root.addView(opacity)
        root.addView(SeekBar(this).apply {
            max = 100
            progress = (bgAlpha * 100 + 127) / 255
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    bgAlpha = (value * 255 + 50) / 100
                    update()
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        })

        root.addView(LinearLayout(this).apply {
            gravity = Gravity.END
            setPadding(0, 12.dp, 0, 0)
            addView(Button(this@SettingsActivity).apply {
                text = "Reset"
                setOnClickListener { save(RaceWidget.DEFAULT_BG) }
            })
            addView(Button(this@SettingsActivity).apply {
                text = "Save"
                setOnClickListener { save((bgAlpha shl 24) or rgb) }
            })
        })

        setContentView(root)
        update()
    }

    private fun update() {
        val argb = (bgAlpha shl 24) or rgb
        preview.setColor(argb)
        val p = RaceWidget.palette(argb)
        title.setTextColor(p.text)
        sub.setTextColor(p.sub)
        opacity.text = "Opacity  ${(bgAlpha * 100 + 127) / 255}%"
    }

    private fun save(argb: Int) {
        RaceWidget.setBackground(this, id, argb)
        RaceWidget.render(this)
        finish()
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, 12.dp, 16.dp, 0)
    }

    private fun row(left: View, right: View) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(left)
        addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private val SWATCHES = intArrayOf(0x000000, 0x141414, 0x3A3A3A, 0x0B1A33, 0x4A0A0A, 0x0D2B1E, 0xFFFFFF)
    }
}
