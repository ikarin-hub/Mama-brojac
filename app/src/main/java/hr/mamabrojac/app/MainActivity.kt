package hr.mamabrojac.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statsPrefs: SharedPreferences
    private lateinit var countView: TextView
    private lateinit var statusView: TextView
    private lateinit var statusDot: View
    private lateinit var heardView: TextView
    private lateinit var toggle: View
    private lateinit var toggleIcon: ImageView
    private lateinit var toggleText: TextView
    private lateinit var batteryBtn: Button
    private lateinit var formsSwitch: Switch
    private lateinit var todayView: TextView
    private lateinit var weekView: TextView
    private lateinit var totalView: TextView
    private var pulse: ObjectAnimator? = null
    private var shownCount = -1

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { render() }
    }

    private val statsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { renderStats() }
    }

    // Live text changes many times per second: redraw only the text box, at most ~12 times/s.
    private var heardPending = false
    private val liveListener: () -> Unit = {
        if (!heardPending) {
            heardPending = true
            heardView.postDelayed({
                heardPending = false
                heardView.text = buildHeard()
            }, 80)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Store.prefs(this)
        statsPrefs = Stats.prefs(this)

        countView = findViewById(R.id.count)
        statusView = findViewById(R.id.status)
        statusDot = findViewById(R.id.status_dot)
        heardView = findViewById(R.id.heard)
        toggle = findViewById(R.id.toggle)
        toggleIcon = findViewById(R.id.toggle_icon)
        toggleText = findViewById(R.id.toggle_text)
        batteryBtn = findViewById(R.id.battery)
        formsSwitch = findViewById(R.id.forms)
        todayView = findViewById(R.id.today_count)
        weekView = findViewById(R.id.week_count)
        totalView = findViewById(R.id.total_count)

        toggle.setOnClickListener { onToggle() }
        findViewById<View>(R.id.reset).setOnClickListener { confirmReset() }
        for (id in listOf(R.id.stats, R.id.stats_icon, R.id.mini_today, R.id.mini_week, R.id.mini_total)) {
            findViewById<View>(id).setOnClickListener { openStats() }
        }
        formsSwitch.isChecked = prefs.getBoolean(Store.FORMS, false)
        formsSwitch.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(Store.FORMS, on).apply()
        }
        batteryBtn.setOnClickListener { askBattery() }
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        statsPrefs.registerOnSharedPreferenceChangeListener(statsListener)
        Live.addListener(liveListener)
        render()
        renderStats()
        updateBattery()
    }

    override fun onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        statsPrefs.unregisterOnSharedPreferenceChangeListener(statsListener)
        Live.removeListener(liveListener)
        stopPulse()
        super.onPause()
    }

    private fun openStats() {
        startActivity(Intent(this, StatsActivity::class.java))
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Vratiti brojač na nulu?")
            .setMessage("Statistika po danima, tjednima i mjesecima ostaje sačuvana.")
            .setPositiveButton("Na nulu") { _, _ -> Store.reset(prefs) }
            .setNegativeButton("Odustani", null)
            .show()
    }

    private fun onToggle() {
        if (CounterService.running) {
            startService(Intent(this, CounterService::class.java).setAction(CounterService.ACTION_STOP))
            return
        }
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1) else startCounter()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startCounter()
        } else {
            prefs.edit().putString(Store.ERROR, "perm_denied").apply()
        }
    }

    private fun startCounter() {
        prefs.edit().putString(Store.ERROR, "").apply()
        startForegroundService(Intent(this, CounterService::class.java))
        toggle.postDelayed({ render() }, 300)
    }

    private fun render() {
        val c = prefs.getInt(Store.COUNT, 0)
        if (shownCount in 0 until c) bump()
        shownCount = c
        countView.text = c.toString()

        val running = CounterService.running
        toggleText.text = Texts.s(if (running) "stop" else "start")
        toggleIcon.setImageResource(if (running) R.drawable.ic_stop else R.drawable.ic_mic)
        toggle.setBackgroundResource(if (running) R.drawable.btn_stop else R.drawable.btn_accent)

        val err = prefs.getString(Store.ERROR, "") ?: ""
        statusView.text = when {
            running -> Texts.s(prefs.getString(Store.STATUS, "listening") ?: "listening")
            err.isNotEmpty() -> Texts.s(err)
            else -> Texts.s("not_listening")
        }
        if (running) {
            startPulse()
        } else {
            stopPulse()
            statusDot.alpha = 0.45f
        }
        heardView.text = buildHeard()
    }

    private fun renderStats() {
        val s = Stats.summary(Stats.days(this))
        todayView.text = s.today.toString()
        weekView.text = s.week.toString()
        totalView.text = s.total.toString()
    }

    private fun startPulse() {
        if (pulse?.isRunning == true) return
        pulse = ObjectAnimator.ofFloat(statusDot, "alpha", 1f, 0.25f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
    }

    private fun buildHeard(): CharSequence {
        val muted = getColor(R.color.muted)
        val last = prefs.getString(Store.LAST, "") ?: ""
        val partial = Live.partial
        val sb = SpannableStringBuilder()
        if (last.isEmpty() && partial.isEmpty()) {
            sb.append(Texts.s("heard_hint"))
            sb.setSpan(ForegroundColorSpan(muted), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return sb
        }
        sb.append(last)
        if (partial.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append(" ")
            val start = sb.length
            sb.append(partial)
            sb.setSpan(ForegroundColorSpan(muted), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val re = WordCounter.regex(prefs.getBoolean(Store.FORMS, false))
        val hl = getColor(R.color.hl)
        for (m in re.findAll(sb.toString())) {
            val s = m.range.first
            val e = m.range.last + 1
            sb.setSpan(BackgroundColorSpan(hl), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun bump() {
        countView.animate().cancel()
        countView.scaleX = 1f
        countView.scaleY = 1f
        countView.animate().scaleX(1.18f).scaleY(1.18f).setDuration(120).withEndAction {
            countView.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
        }.start()
    }

    private fun updateBattery() {
        val pm = getSystemService(PowerManager::class.java)
        val ignoring = pm?.isIgnoringBatteryOptimizations(packageName) ?: true
        batteryBtn.visibility = if (ignoring) View.GONE else View.VISIBLE
    }

    private fun askBattery() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
            }
        }
    }
}
