package dev.f1schedule

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * Background and text colours, and the bottom row. From the widget's long-press menu (pencil) it edits that widget;
 * from the app drawer, which works on every launcher, it edits all widgets.
 */
class SettingsActivity : Activity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    // Background, main, dimmed (finished), highlighted (current/next). Text colours are automatic until edited.
    private val values = IntArray(4)
    private val custom = booleanArrayOf(true, false, false, false)
    private var target = 0
    private var loading = false

    private val preview = GradientDrawable()
    private val tabs = mutableListOf<TextView>()
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var rows: List<Pair<TextView, TextView>> // dimmed, highlighted, normal
    private lateinit var colour: TextView
    private lateinit var hex: EditText
    private lateinit var picker: ColorPicker
    private var picking = false
    private lateinit var opacity: TextView
    private lateinit var seek: SeekBar
    private lateinit var always: CheckBox
    private lateinit var round: RadioButton
    private lateinit var date: RadioButton
    private lateinit var sizeBar: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Not one of our widgets (opened from the app drawer): edit all of them.
        val ours = AppWidgetManager.getInstance(this).getAppWidgetIds(ComponentName(this, RaceWidget::class.java))
        if (widgetId !in ours) widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        setTitle(R.string.settings)
        // Before Android 12 this opens when the widget is added; backing out still keeps the widget.
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        load(RaceWidget.colors(this, widgetId))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 8.dp, 20.dp, 12.dp)
        }

        // Preview of the widget's top lines.
        preview.cornerRadius = 20.dp.toFloat()
        title = text("United States Grand Prix", 16f, medium = true)
        sub = text("Circuit of the Americas", 12f)
        rows = listOf("Practice 1" to "Fri  7:30 PM", "Practice 2" to "Fri  11:00 PM", "Qualifying" to "Sat  11:00 PM")
            .map { (n, t) -> text(n, 13f) to text(t, 13f, medium = true) }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = preview
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            addView(title)
            addView(sub, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 4.dp })
            rows.forEach { (n, t) -> addView(row(n, t)) }
        })

        // What's being edited.
        root.addView(LinearLayout(this).apply {
            setPadding(0, 12.dp, 0, 0)
            listOf("Background", "Main", "Dimmed", "Highlighted").forEachIndexed { i, name ->
                val tab = text(name, 13f).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(2.dp, 0, 2.dp, 0)
                    setOnClickListener { select(i) }
                }
                tabs += tab
                addView(tab, LinearLayout.LayoutParams(0, 36.dp, 1f))
            }
            post(::fitTabs)
        })

        hex = EditText(this).apply {
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(7))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable) {
                    val t = s.toString().removePrefix("#")
                    if (t.length == 6) t.toIntOrNull(16)?.let {
                        if (!picking) picker.setColor(it)
                        edit((values[target] and 0xFF000000.toInt()) or it)
                    }
                }
            })
        }

        picker = ColorPicker(this).apply {
            onPick = {
                picking = true
                hex.setText(String.format("#%06X", it))
                hex.setSelection(hex.length())
                picking = false
            }
        }
        root.addView(picker, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.dp })

        // Preset colours; any other via the picker or hex.
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
            }, LinearLayout.LayoutParams(30.dp, 30.dp).apply { setMargins(3.dp, 0, 3.dp, 0) })
        })

        colour = label("Colour")
        root.addView(row(colour, hex, weight = false))

        opacity = label("")
        root.addView(opacity)
        seek = SeekBar(this).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    edit((values[target] and 0xFFFFFF) or ((value * 255 + 50) / 100 shl 24))
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        root.addView(seek)

        // Bottom row: time switch on the left, round or date on the right.
        val foot = RaceWidget.footer(this, widgetId)
        always = CheckBox(this).apply {
            text = "Always show bottom row"
            isChecked = foot.always
        }
        root.addView(always, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 12.dp })
        round = RadioButton(this).apply { text = "Round"; id = View.generateViewId() }
        date = RadioButton(this).apply { text = "Date"; id = View.generateViewId() }
        root.addView(row(label("Bottom right").apply { setPadding(0, 0, 16.dp, 0) }, RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(round)
            addView(date)
            check(if (foot.date) date.id else round.id)
        }, weight = false))

        // Text size, the same at every widget size: 70-150 % in steps of 5.
        val sizeLabel = label("")
        root.addView(sizeLabel)
        sizeBar = SeekBar(this).apply {
            max = (MAX_TEXT - MIN_TEXT) / 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    sizeLabel.text = "Text size  ${MIN_TEXT + value * 5}%"
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
            progress = (RaceWidget.textSize(this@SettingsActivity, widgetId).coerceIn(MIN_TEXT, MAX_TEXT) - MIN_TEXT) / 5
            sizeLabel.text = "Text size  ${MIN_TEXT + progress * 5}%"
        }
        root.addView(sizeBar)

        root.addView(LinearLayout(this).apply {
            gravity = Gravity.END
            setPadding(0, 12.dp, 0, 0)
            addView(Button(this@SettingsActivity).apply {
                text = "Reset"
                setOnClickListener {
                    load(RaceWidget.Colors(RaceWidget.DEFAULT_BG, null, null, null))
                    select(target)
                    always.isChecked = false
                    round.isChecked = true
                    sizeBar.progress = (100 - MIN_TEXT) / 5
                }
            })
            addView(Button(this@SettingsActivity).apply {
                text = "Save"
                setOnClickListener {
                    RaceWidget.setColors(this@SettingsActivity, widgetId, colors())
                    RaceWidget.setFooter(this@SettingsActivity, widgetId, RaceWidget.Footer(always.isChecked, date.isChecked))
                    RaceWidget.setTextSize(this@SettingsActivity, widgetId, MIN_TEXT + sizeBar.progress * 5)
                    RaceWidget.render(this@SettingsActivity)
                    finish()
                }
            })
        })

        setContentView(ScrollView(this).apply { addView(root) })
        select(0)
    }

    /** One text size for all tabs: the largest at which every label fits in bold (narrow screens, big fonts). */
    private fun fitTabs() {
        val paint = Paint().apply { typeface = Typeface.DEFAULT_BOLD }
        var size = tabs[0].textSize
        val min = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 8f, resources.displayMetrics)
        while (size > min && tabs.any { paint.apply { textSize = size }.measureText(it.text.toString()) > it.width - it.totalPaddingLeft - it.totalPaddingRight }) size -= 1
        tabs.forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_PX, size) }
    }

    private fun load(c: RaceWidget.Colors) {
        values[0] = c.bg
        listOf(c.main, c.past, c.next).forEachIndexed { i, v ->
            custom[i + 1] = v != null
            if (v != null) values[i + 1] = v
        }
    }

    private fun colors() = RaceWidget.Colors(
        values[0],
        values[1].takeIf { custom[1] }, values[2].takeIf { custom[2] }, values[3].takeIf { custom[3] },
    )

    /** Shows [i]'s colour in the editors without counting as an edit. */
    private fun select(i: Int) {
        target = i
        update()
        tabs.forEachIndexed { j, tab ->
            tab.background = if (j == i) GradientDrawable().apply { cornerRadius = 8.dp.toFloat(); setColor(0x33FFFFFF) } else null
            tab.typeface = if (j == i) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        loading = true
        hex.setText(String.format("#%06X", values[i] and 0xFFFFFF))
        hex.setSelection(hex.length())
        seek.progress = (Color.alpha(values[i]) * 100 + 127) / 255
        loading = false
        update()
    }

    private fun edit(argb: Int) {
        if (loading) return
        values[target] = argb
        custom[target] = true
        update()
    }

    private fun update() {
        val p = RaceWidget.palette(colors())
        // Automatic text colours follow the background.
        if (!custom[1]) values[1] = p.text
        if (!custom[2]) values[2] = p.done
        if (!custom[3]) values[3] = p.next
        preview.setColor(p.bg)
        title.setTextColor(p.text)
        sub.setTextColor(p.sub)
        rows[0].first.setTextColor(p.done); rows[0].second.setTextColor(p.done)
        rows[1].first.setTextColor(p.next); rows[1].second.setTextColor(p.next)
        rows[2].first.setTextColor(p.text); rows[2].second.setTextColor(p.sub)
        colour.text = if (custom[target]) "Colour" else "Colour (auto)"
        opacity.text = "Opacity  ${(Color.alpha(values[target]) * 100 + 127) / 255}%"
    }

    private fun text(s: String, size: Float, medium: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, 12.dp, 16.dp, 0)
    }

    private fun row(left: View, right: View, weight: Boolean = true) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        if (weight) {
            addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            addView(right)
        } else {
            addView(left)
            addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val MIN_TEXT = 70
        private const val MAX_TEXT = 150
        private val SWATCHES = intArrayOf(0x000000, 0x3A3A3A, 0x9A9A9A, 0xFFFFFF, 0xFF2800, 0xFFC400, 0x0B1A33, 0x0D2B1E)
    }
}
