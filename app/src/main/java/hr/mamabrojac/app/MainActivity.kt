package hr.mamabrojac.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var countView: TextView
    private lateinit var statusView: TextView
    private lateinit var heardView: TextView
    private lateinit var toggleBtn: Button
    private lateinit var batteryBtn: Button
    private lateinit var resetBtn: Button
    private lateinit var plusBtn: Button
    private lateinit var langEn: Button
    private lateinit var langHr: Button
    private lateinit var formsSwitch: Switch
    private lateinit var subtitleView: TextView
    private lateinit var labelView: TextView
    private lateinit var heardLabelView: TextView
    private lateinit var footerView: TextView
    private var shownCount = -1

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Store.prefs(this)

        countView = findViewById(R.id.count)
        statusView = findViewById(R.id.status)
        heardView = findViewById(R.id.heard)
        toggleBtn = findViewById(R.id.toggle)
        batteryBtn = findViewById(R.id.battery)
        resetBtn = findViewById(R.id.reset)
        plusBtn = findViewById(R.id.plus)
        langEn = findViewById(R.id.lang_en)
        langHr = findViewById(R.id.lang_hr)
        formsSwitch = findViewById(R.id.forms)
        subtitleView = findViewById(R.id.subtitle)
        labelView = findViewById(R.id.label)
        heardLabelView = findViewById(R.id.heard_label)
        footerView = findViewById(R.id.footer)

        toggleBtn.setOnClickListener { onToggle() }
        resetBtn.setOnClickListener { Store.reset(prefs) }
        plusBtn.setOnClickListener { Store.add(prefs, 1) }
        langEn.setOnClickListener { setLang("en") }
        langHr.setOnClickListener { setLang("hr") }
        formsSwitch.isChecked = prefs.getBoolean(Store.FORMS, false)
        formsSwitch.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(Store.FORMS, on).apply()
        }
        batteryBtn.setOnClickListener { askBattery() }
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        render()
        updateBattery()
    }

    override fun onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onPause()
    }

    private fun setLang(l: String) {
        if (Store.lang(prefs) != l) prefs.edit().putString(Store.LANG, l).apply()
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
        toggleBtn.postDelayed({ render() }, 300)
    }

    private fun render() {
        val l = Store.lang(prefs)

        // Static texts in the chosen language
        subtitleView.text = Texts.s(l, "subtitle")
        labelView.text = Texts.s(l, "label")
        resetBtn.text = Texts.s(l, "reset")
        plusBtn.text = Texts.s(l, "plus")
        heardLabelView.text = Texts.s(l, "heard_label")
        batteryBtn.text = Texts.s(l, "battery")
        footerView.text = Texts.s(l, "footer")
        formsSwitch.text = Texts.s(l, "forms")
        // Case forms only exist in Croatian
        formsSwitch.visibility = if (l == "hr") View.VISIBLE else View.GONE
        styleLang(langEn, l == "en")
        styleLang(langHr, l == "hr")

        val c = prefs.getInt(Store.COUNT, 0)
        if (shownCount in 0 until c) bump()
        shownCount = c
        countView.text = c.toString()

        val running = CounterService.running
        toggleBtn.text = Texts.s(l, if (running) "stop" else "start")
        val err = prefs.getString(Store.ERROR, "") ?: ""
        statusView.text = when {
            running -> Texts.s(l, prefs.getString(Store.STATUS, "listening") ?: "listening")
            err.isNotEmpty() -> Texts.s(l, err)
            else -> Texts.s(l, "not_listening")
        }
        statusView.setTextColor(
            if (!running && err.isNotEmpty()) Color.parseColor("#E4572E") else Color.parseColor("#8A6F63")
        )
        heardView.text = buildHeard(l)
    }

    private fun styleLang(b: Button, active: Boolean) {
        b.setBackgroundResource(if (active) R.drawable.btn_accent else R.drawable.btn_ghost)
        b.setTextColor(if (active) Color.WHITE else Color.parseColor("#2B1D17"))
    }

    private fun buildHeard(l: String): CharSequence {
        val last = prefs.getString(Store.LAST, "") ?: ""
        val partial = prefs.getString(Store.PARTIAL, "") ?: ""
        val sb = SpannableStringBuilder()
        if (last.isEmpty() && partial.isEmpty()) {
            sb.append(Texts.s(l, "heard_hint"))
            sb.setSpan(ForegroundColorSpan(Color.parseColor("#8A6F63")), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return sb
        }
        sb.append(last)
        if (partial.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append(" ")
            val start = sb.length
            sb.append(partial)
            sb.setSpan(ForegroundColorSpan(Color.parseColor("#8A6F63")), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val re = WordCounter.regex(l, prefs.getBoolean(Store.FORMS, false))
        for (m in re.findAll(sb.toString())) {
            val s = m.range.first
            val e = m.range.last + 1
            sb.setSpan(BackgroundColorSpan(Color.parseColor("#FFE08A")), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun bump() {
        countView.animate().cancel()
        countView.scaleX = 1f
        countView.scaleY = 1f
        countView.animate().scaleX(1.25f).scaleY(1.25f).setDuration(120).withEndAction {
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
