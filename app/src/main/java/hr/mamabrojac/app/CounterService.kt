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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Foreground service that keeps listening (also when the app is in the background)
 * with the on-device model and counts "mama".
 *
 * It only reads the microphone: it never mutes sound or asks other apps to pause
 * (the old Google recognition did both, which interrupted videos in other apps).
 */
class CounterService : Service() {

    companion object {
        @Volatile
        var running = false
        const val ACTION_STOP = "hr.mamabrojac.app.STOP"
        private const val CHANNEL = "mama_listen"
        private const val NOTIF_ID = 7
    }

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var engine: VoskEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopped = false
    private var failures = 0

    @Volatile
    private var currentGain = 1f

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Store.COUNT -> updateNotification()
            Store.SENS -> currentGain = Store.gain(prefs) // takes effect immediately
        }
    }

    private val retry = Runnable { startEngine() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Store.prefs(this)
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
            startEngine()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!stopped) shutdown(stopService = false)
        super.onDestroy()
    }

    // ---------------- listening ----------------

    private fun startEngine() {
        if (stopped) return
        setStatus("preparing")
        currentGain = Store.gain(prefs)
        val e = VoskEngine(
            this,
            gain = { currentGain },
            onWords = { n -> Store.add(this, n) },
            onReady = {
                if (!stopped) {
                    failures = 0
                    setStatus("listening")
                }
            },
            onFail = { _ ->
                if (!stopped) {
                    // e.g. the microphone is busy (a call): try again a little later
                    stopEngine()
                    failures++
                    setStatus("retry")
                    handler.removeCallbacks(retry)
                    handler.postDelayed(retry, minOf(2000L * failures, 15000L))
                }
            }
        )
        engine = e
        e.start()
    }

    private fun stopEngine() {
        try {
            engine?.stop()
        } catch (e: Exception) {
        }
        engine = null
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
        handler.removeCallbacks(retry)
        stopEngine()
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
        prefs.edit().putLong(Store.TICK, System.currentTimeMillis()).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (stopService) stopSelf()
    }

    /** Older versions muted sounds for a moment; if one was killed mid-way, give the sound back. */
    private fun restoreLeftoverMutes() {
        val saved = prefs.getString(Store.MUTED, "") ?: ""
        if (saved.isBlank()) return
        val am = getSystemService(AudioManager::class.java)
        for (s in saved.split(",").mapNotNull { it.trim().toIntOrNull() }) {
            try {
                am?.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0)
            } catch (e: Exception) {
            }
        }
        prefs.edit().putString(Store.MUTED, "").apply()
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
