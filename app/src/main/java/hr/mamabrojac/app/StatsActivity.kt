package hr.mamabrojac.app

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Statistics screen: summary, chart and full history by day, week or month. */
class StatsActivity : Activity() {

    private var mode = 0 // 0 = days, 1 = weeks, 2 = months
    private lateinit var chart: BarChartView
    private lateinit var chartTitle: TextView
    private lateinit var history: LinearLayout
    private lateinit var empty: TextView
    private lateinit var tabs: List<TextView>

    private val statsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { renderAll() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)
        chart = findViewById(R.id.chart)
        chartTitle = findViewById(R.id.chart_title)
        history = findViewById(R.id.history)
        empty = findViewById(R.id.empty)
        tabs = listOf(
            findViewById<TextView>(R.id.tab_days),
            findViewById<TextView>(R.id.tab_weeks),
            findViewById<TextView>(R.id.tab_months)
        )
        tabs.forEachIndexed { i, t ->
            t.setOnClickListener {
                if (mode != i) {
                    mode = i
                    renderAll()
                }
            }
        }
        findViewById<View>(R.id.back).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        Stats.prefs(this).registerOnSharedPreferenceChangeListener(statsListener)
        renderAll()
    }

    override fun onPause() {
        Stats.prefs(this).unregisterOnSharedPreferenceChangeListener(statsListener)
        super.onPause()
    }

    private fun renderAll() {
        val days = Stats.days(this)
        val today = LocalDate.now()
        val s = Stats.summary(days, today)
        findViewById<TextView>(R.id.s_today).text = s.today.toString()
        findViewById<TextView>(R.id.s_week).text = s.week.toString()
        findViewById<TextView>(R.id.s_month).text = s.month.toString()
        findViewById<TextView>(R.id.s_total).text = s.total.toString()

        val first = days.keys.minOrNull()
        if (first == null) {
            findViewById<TextView>(R.id.s_avg).text = "–"
            findViewById<TextView>(R.id.s_best).text = "–"
            findViewById<TextView>(R.id.s_since).text = "–"
        } else {
            val nDays = ChronoUnit.DAYS.between(first, today) + 1
            val avg = s.total.toDouble() / nDays.coerceAtLeast(1)
            findViewById<TextView>(R.id.s_avg).text = String.format(Locale("hr"), "%.1f", avg)
            val best = days.maxByOrNull { it.value }
            findViewById<TextView>(R.id.s_best).text =
                if (best == null || best.value == 0) "–" else "${dateLong(best.key)} (${best.value})"
            findViewById<TextView>(R.id.s_since).text = dateLong(first)
        }

        tabs.forEachIndexed { i, t ->
            val active = i == mode
            t.setBackgroundResource(if (active) R.drawable.tab_selected else 0)
            t.setTextColor(getColor(if (active) R.color.ink else R.color.muted))
        }

        when (mode) {
            0 -> renderDays(days, today, first)
            1 -> renderWeeks(days, today, first)
            else -> renderMonths(days, today, first)
        }
        empty.visibility = if (first == null) View.VISIBLE else View.GONE
        history.visibility = if (first == null) View.GONE else View.VISIBLE
    }

    private fun renderDays(days: Map<LocalDate, Int>, today: LocalDate, first: LocalDate?) {
        chartTitle.text = "Zadnjih 14 dana"
        val ds = (13 downTo 0).map { today.minusDays(it.toLong()) }
        chart.setData(ds.map { "${it.dayOfMonth}." }, ds.map { days[it] ?: 0 })

        val rows = mutableListOf<Triple<String, Int, Boolean>>()
        if (first != null) {
            var d = today
            while (!d.isBefore(first)) {
                rows += Triple("${Texts.DAYS_SHORT[d.dayOfWeek.value - 1]}, ${dateLong(d)}", days[d] ?: 0, d == today)
                d = d.minusDays(1)
            }
        }
        fillHistory(rows)
    }

    private fun renderWeeks(days: Map<LocalDate, Int>, today: LocalDate, first: LocalDate?) {
        chartTitle.text = "Zadnjih 12 tjedana"
        val weeks = Stats.byWeek(days)
        val thisWeek = Stats.weekStart(today)
        val ws = (11 downTo 0).map { thisWeek.minusWeeks(it.toLong()) }
        chart.setData(ws.map { "${it.dayOfMonth}.${it.monthValue}." }, ws.map { weeks[it] ?: 0 }, showEveryNthLabel = 2)

        val rows = mutableListOf<Triple<String, Int, Boolean>>()
        if (first != null) {
            val firstWeek = Stats.weekStart(first)
            var w = thisWeek
            while (!w.isBefore(firstWeek)) {
                val end = w.plusDays(6)
                rows += Triple("${w.dayOfMonth}.${w.monthValue}. – ${dateLong(end)}", weeks[w] ?: 0, w == thisWeek)
                w = w.minusWeeks(1)
            }
        }
        fillHistory(rows)
    }

    private fun renderMonths(days: Map<LocalDate, Int>, today: LocalDate, first: LocalDate?) {
        chartTitle.text = "Zadnjih 12 mjeseci"
        val months = Stats.byMonth(days)
        val thisMonth = YearMonth.from(today)
        val ms = (11 downTo 0).map { thisMonth.minusMonths(it.toLong()) }
        chart.setData(ms.map { Texts.MONTHS_SHORT[it.monthValue - 1] }, ms.map { months[it] ?: 0 })

        val rows = mutableListOf<Triple<String, Int, Boolean>>()
        if (first != null) {
            val firstMonth = YearMonth.from(first)
            var m = thisMonth
            while (!m.isBefore(firstMonth)) {
                rows += Triple("${Texts.MONTHS[m.monthValue - 1]} ${m.year}.", months[m] ?: 0, m == thisMonth)
                m = m.minusMonths(1)
            }
        }
        fillHistory(rows)
    }

    /** One row per period: label on the left, count on the right; the current period in color. */
    private fun fillHistory(rows: List<Triple<String, Int, Boolean>>) {
        history.removeAllViews()
        val dp = resources.displayMetrics.density
        val max = (rows.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
        rows.forEachIndexed { i, (label, value, current) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
            }
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val l = TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(getColor(if (current) R.color.accent else R.color.ink))
                if (current) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val v = TextView(this).apply {
                text = value.toString()
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(getColor(if (value == 0) R.color.muted else R.color.ink))
            }
            line.addView(l, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(v)
            row.addView(line)

            // thin bar showing the size compared to the biggest period
            val track = LinearLayout(this).apply {
                setBackgroundColor(getColor(R.color.track))
            }
            val fill = View(this).apply {
                setBackgroundColor(getColor(if (current) R.color.accent else R.color.bar_faint))
            }
            track.addView(fill, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, value.toFloat()))
            track.addView(View(this), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (max - value).toFloat()))
            row.addView(track, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (3 * dp).toInt()).apply {
                topMargin = (6 * dp).toInt()
            })

            history.addView(row)
            if (i < rows.size - 1) {
                history.addView(View(this).apply { setBackgroundColor(getColor(R.color.line)) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            }
        }
    }

    private fun dateLong(d: LocalDate) = "${d.dayOfMonth}.${d.monthValue}.${d.year}."
}
