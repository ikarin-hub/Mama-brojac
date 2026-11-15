package hr.mamabrojac.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Foreground service that keeps listening (also when the app is in the background),
 * restarts the phone's speech recognizer after every phrase, and counts "mama".
 */
class CounterService : Service(), RecognitionListener {

    companion object {
        @Volatile
        var running = false
        const val ACTION_STOP = "hr.mamabrojac.app.STOP"
        private const val CHANNEL = "mama_listen"
        private const val NOTIF_ID = 7
    }

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopped = false
    private var useDefaultLanguage = false
    private var countedThisUtterance = 0
    private var failures = 0

    private val countListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Store.COUNT) updateNotification()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Store.prefs(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown(stopService = true)
            return START_NOT_STICKY
        }
        try {
            goForeground()
        } catch (e: Exception) {
            fail("Ne mogu pokrenuti slušanje u pozadini.")
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            stopped = false
            failures = 0
            prefs.registerOnSharedPreferenceChangeListener(countListener)
            prefs.edit().putString(Store.ERROR, "").putLong(Store.TICK, System.currentTimeMillis()).apply()
            acquireWakeLock()
            setStatus("Pokrećem…")
            startListening()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!stopped) shutdown(stopService = false)
        super.onDestroy()
    }

    // ---------------- recognition loop ----------------

    private fun startListening() {
        if (stopped) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            fail("Na mobitelu nema usluge za prepoznavanje govora. Instaliraj ili ažuriraj aplikaciju Google.")
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            if (!useDefaultLanguage) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hr-HR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hr-HR")
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        countedThisUtterance = 0
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            restart(1000, recreate = true)
        }
    }

    private fun restart(delayMs: Long, recreate: Boolean = false) {
        if (stopped) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (recreate) {
                try {
                    recognizer?.destroy()
                } catch (e: Exception) {
                }
                recognizer = null
            }
            startListening()
        }, delayMs)
    }

    private fun handleText(text: String, final: Boolean) {
        // Count as soon as a "mama" shows up in the live guess; never count the same one twice.
        val n = MamaCounter.count(text, prefs.getBoolean(Store.FORMS, false))
        if (n > countedThisUtterance) {
            Store.add(prefs, n - countedThisUtterance)
            countedThisUtterance = n
        }
        if (final) {
            countedThisUtterance = 0
            val edit = prefs.edit().putString(Store.PARTIAL, "")
            if (text.isNotBlank()) {
                val last = ((prefs.getString(Store.LAST, "") ?: "") + " " + text).trim().takeLast(400)
                edit.putString(Store.LAST, last)
            }
            edit.apply()
        } else {
            prefs.edit().putString(Store.PARTIAL, text).apply()
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {
        setStatus("Slušam…")
    }

    override fun onBeginningOfSpeech() {}

    override fun onRmsChanged(rmsdB: Float) {}

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {}

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
        if (text.isNotBlank()) handleText(text, final = false)
    }

    override fun onResults(results: Bundle?) {
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
        handleText(text, final = true)
        failures = 0
        restart(100)
    }

    override fun onError(error: Int) {
        if (stopped) return
        prefs.edit().putString(Store.PARTIAL, "").apply()
        countedThisUtterance = 0
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                // Normal during silence: just listen again.
                setStatus("Slušam…")
                restart(150)
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_CLIENT -> restart(500, recreate = true)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                fail("Aplikacija nema dozvolu za mikrofon.")
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> {
                failures++
                setStatus("Nema veze s uslugom prepoznavanja – pokušavam ponovno…")
                restart(minOf(2000L * failures, 15000L), recreate = true)
            }
            12, 13 -> { // language not supported / not available
                if (!useDefaultLanguage) {
                    useDefaultLanguage = true
                    setStatus("Hrvatski nije dostupan – koristim jezik mobitela.")
                    restart(300, recreate = true)
                } else {
                    failures++
                    restart(minOf(1000L * failures, 10000L), recreate = true)
                }
            }
            else -> {
                failures++
                restart(minOf(1000L * failures, 10000L), recreate = true)
            }
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    // ---------------- service plumbing ----------------

    private fun setStatus(s: String) {
        if (prefs.getString(Store.STATUS, null) != s) prefs.edit().putString(Store.STATUS, s).apply()
    }

    private fun fail(message: String) {
        prefs.edit().putString(Store.ERROR, message).apply()
        shutdown(stopService = true)
    }

    private fun shutdown(stopService: Boolean) {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        recognizer = null
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        wakeLock = null
        if (running) {
            running = false
            try {
                prefs.unregisterOnSharedPreferenceChangeListener(countListener)
            } catch (e: Exception) {
            }
        }
        prefs.edit().putString(Store.PARTIAL, "").putLong(Store.TICK, System.currentTimeMillis()).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (stopService) stopSelf()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MamaBrojac:listen").apply {
            setReferenceCounted(false)
            acquire(12L * 60 * 60 * 1000)
        }
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(CHANNEL, "Slušanje", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Prikazuje se dok Mama-brojač sluša"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, CounterService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val c = prefs.getInt(Store.COUNT, 0)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Rečeno „mama”: $c")
            .setContentText("Slušam i brojim…")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stop), "Zaustavi", stop
                ).build()
            )
            .build()
    }

    private fun updateNotification() {
        if (!running) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification())
    }
}
