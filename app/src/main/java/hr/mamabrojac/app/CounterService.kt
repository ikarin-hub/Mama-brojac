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
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Foreground service that keeps listening (also when the app is in the background),
 * restarts the phone's speech recognizer after every phrase, and counts the word.
 * The recognizer's start/stop beep is silenced by muting the beep streams only
 * for the short moment between two listening sessions.
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
    private var audio: AudioManager? = null
    private val mutedByUs = mutableSetOf<Int>()
    private var pendingRestart: Runnable? = null
    private var stopped = false
    private var useDefaultLanguage = false
    private var preferOffline = true   // on-device recognition answers faster when the phone has the language
    private var countedThisUtterance = 0
    private var failures = 0
    private var useSegmented = true
    private var segmentedFailures = 0
    private var gotResultThisSession = false
    private var sessionActive = false
    private var lastCallback = 0L

    private val unmuteRunnable = Runnable { unmuteBeep() }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Store.COUNT) updateNotification()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Store.prefs(this)
        audio = getSystemService(AudioManager::class.java)
        restoreLeftoverMutes()
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
            fail("fg_fail")
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            stopped = false
            failures = 0
            prefs.registerOnSharedPreferenceChangeListener(prefsListener)
            prefs.edit().putString(Store.ERROR, "").putLong(Store.TICK, System.currentTimeMillis()).apply()
            acquireWakeLock()
            setStatus("starting")
            muteBeep()
            startListening()
            handler.removeCallbacks(watchdog)
            handler.postDelayed(watchdog, 2000)
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
            fail("no_service")
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
            recognizer = it
        }
        val locale = WordCounter.LOCALE
        val segmented = useSegmented && Build.VERSION.SDK_INT >= 33
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            if (!useDefaultLanguage) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale)
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (segmented) {
                // Android 13+: one long session that hands over results piece by piece,
                // so there is no gap between sentences (phones that can't do it ignore this).
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10L * 60 * 1000)
            } else {
                // Ask for longer listening sessions, so there are fewer restarts (some phones ignore this).
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L)
            }
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        countedThisUtterance = 0
        gotResultThisSession = false
        sessionActive = true
        lastCallback = SystemClock.elapsedRealtime()
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            restart(300, recreate = true)
        }
    }

    private fun restart(delayMs: Long, recreate: Boolean = false) {
        if (stopped) return
        sessionActive = false
        muteBeep()
        pendingRestart?.let { handler.removeCallbacks(it) }
        val task = Runnable {
            pendingRestart = null
            if (recreate) {
                try {
                    recognizer?.destroy()
                } catch (e: Exception) {
                }
                recognizer = null
            }
            startListening()
        }
        pendingRestart = task
        handler.postDelayed(task, delayMs)
    }

    /** The recognizer sometimes hangs without any callback: if it goes silent, start it again. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (stopped) return
            if (sessionActive && SystemClock.elapsedRealtime() - lastCallback > 5000) {
                restart(0, recreate = true)
            }
            handler.postDelayed(this, 2000)
        }
    }

    private fun alive() {
        lastCallback = SystemClock.elapsedRealtime()
    }

    private fun handleText(text: String, final: Boolean) {
        // Count as soon as the word shows up in the live guess; never count the same one twice.
        val n = WordCounter.count(text, prefs.getBoolean(Store.FORMS, false))
        if (n > countedThisUtterance) {
            Store.add(this, n - countedThisUtterance)
            countedThisUtterance = n
        }
        if (final) {
            countedThisUtterance = 0
            Live.setPartial("")
            if (text.isNotBlank()) {
                val last = ((prefs.getString(Store.LAST, "") ?: "") + " " + text).trim().takeLast(400)
                prefs.edit().putString(Store.LAST, last).apply()
            }
        } else {
            Live.setPartial(text)
        }
    }

    private fun firstResult(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    override fun onReadyForSpeech(params: Bundle?) {
        alive()
        failures = 0 // the recognizer started fine, so earlier hiccups no longer slow down restarts
        setStatus("listening")
        // The start beep plays right now; give it a moment, then restore the sound.
        handler.removeCallbacks(unmuteRunnable)
        handler.postDelayed(unmuteRunnable, 700)
    }

    override fun onBeginningOfSpeech() {
        alive()
    }

    override fun onRmsChanged(rmsdB: Float) {
        alive()
    }

    override fun onBufferReceived(buffer: ByteArray?) {
        alive()
    }

    override fun onEndOfSpeech() {
        alive()
        // The recognizer may beep when it stops listening: silence it.
        muteBeep()
    }

    override fun onPartialResults(partialResults: Bundle?) {
        alive()
        val text = firstResult(partialResults)
        if (text.isNotBlank()) handleText(text, final = false)
    }

    override fun onResults(results: Bundle?) {
        alive()
        gotResultThisSession = true
        segmentedFailures = 0
        handleText(firstResult(results), final = true)
        restart(0)
    }

    // Android 13+ segmented session: a finished piece of speech, the session keeps going.
    override fun onSegmentResults(segmentResults: Bundle) {
        alive()
        gotResultThisSession = true
        segmentedFailures = 0
        handleText(firstResult(segmentResults), final = true)
    }

    override fun onEndOfSegmentedSession() {
        alive()
        restart(0)
    }

    override fun onError(error: Int) {
        if (stopped) return
        Live.setPartial("")
        countedThisUtterance = 0
        // If long sessions keep failing on this phone, go back to the classic mode.
        if (useSegmented && !gotResultThisSession &&
            error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        ) {
            segmentedFailures++
            if (segmentedFailures >= 2) useSegmented = false
        }
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                // Normal during silence: just listen again.
                setStatus("listening")
                restart(0)
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_CLIENT -> restart(150, recreate = true)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail("no_perm")
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> {
                failures++
                setStatus("network")
                restart(minOf(1000L * failures, 5000L), recreate = true)
            }
            12, 13 -> { // language not supported / not available
                if (preferOffline) {
                    preferOffline = false
                    restart(50, recreate = true)
                } else if (!useDefaultLanguage) {
                    useDefaultLanguage = true
                    setStatus("lang_fallback")
                    restart(100, recreate = true)
                } else {
                    failures++
                    restart(minOf(300L * failures, 2000L), recreate = true)
                }
            }
            else -> {
                failures++
                restart(minOf(300L * failures, 2000L), recreate = true)
            }
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    // ---------------- silencing the recognizer beep ----------------

    private fun muteBeep() {
        val am = audio ?: return
        val streams = mutableListOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)
        // Only touch the media stream when nothing is playing, so music/videos are never cut.
        if (!am.isMusicActive) streams += AudioManager.STREAM_MUSIC
        var changed = false
        for (s in streams) {
            if (s in mutedByUs) continue
            try {
                if (!am.isStreamMute(s)) {
                    am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
                    mutedByUs += s
                    changed = true
                }
            } catch (e: Exception) {
                // e.g. Do Not Disturb forbids changing this stream – skip it.
            }
        }
        if (changed) saveMutes()
        // Safety net: never leave the sound muted for long.
        handler.removeCallbacks(unmuteRunnable)
        handler.postDelayed(unmuteRunnable, 3000)
    }

    private fun unmuteBeep() {
        handler.removeCallbacks(unmuteRunnable)
        val am = audio ?: return
        for (s in mutedByUs) {
            try {
                am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0)
            } catch (e: Exception) {
            }
        }
        if (mutedByUs.isNotEmpty()) {
            mutedByUs.clear()
            saveMutes()
        }
    }

    private fun saveMutes() {
        prefs.edit().putString(Store.MUTED, mutedByUs.joinToString(",")).apply()
    }

    /** If the app was killed while a stream was muted, give the sound back. */
    private fun restoreLeftoverMutes() {
        val saved = prefs.getString(Store.MUTED, "") ?: ""
        if (saved.isBlank()) return
        val am = audio
        for (s in saved.split(",").mapNotNull { it.trim().toIntOrNull() }) {
            try {
                am?.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0)
            } catch (e: Exception) {
            }
        }
        prefs.edit().putString(Store.MUTED, "").apply()
    }

    // ---------------- service plumbing ----------------

    private fun setStatus(code: String) {
        if (prefs.getString(Store.STATUS, null) != code) prefs.edit().putString(Store.STATUS, code).apply()
    }

    private fun fail(code: String) {
        prefs.edit().putString(Store.ERROR, code).apply()
        shutdown(stopService = true)
    }

    private fun shutdown(stopService: Boolean) {
        stopped = true
        pendingRestart?.let { handler.removeCallbacks(it) }
        pendingRestart = null
        sessionActive = false
        handler.removeCallbacks(watchdog)
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        recognizer = null
        // Give the sound back a little later, in case the recognizer still beeps while closing.
        handler.postDelayed(unmuteRunnable, 500)
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        wakeLock = null
        if (running) {
            running = false
            try {
                prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
            } catch (e: Exception) {
            }
        }
        Live.setPartial("")
        prefs.edit().putLong(Store.TICK, System.currentTimeMillis()).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (stopService) {
            stopSelf()
        } else {
            unmuteBeep()
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReciMama:listen").apply {
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
        val ch = NotificationChannel(CHANNEL, Texts.s("channel"), NotificationManager.IMPORTANCE_LOW).apply {
            description = Texts.s("channel_desc")
            setShowBadge(false)
            setSound(null, null)
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
            .setContentTitle(Texts.s("notif_title").format(c))
            .setContentText(Texts.s("notif_text"))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stop), Texts.s("notif_stop"), stop
                ).build()
            )
            .build()
    }

    private fun updateNotification() {
        if (!running) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification())
    }
}
