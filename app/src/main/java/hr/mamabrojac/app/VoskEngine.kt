package hr.mamabrojac.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.tanh

/**
 * A small speech model that runs on the phone, without internet, listens continuously
 * (no restarts, no beeps) and counts the word "mama".
 *
 * Two threads: one only records (high audio priority, never waits for anything), the
 * other amplifies and recognizes. If the phone is briefly busy, recorded sound waits
 * in a queue instead of being lost.
 */
class VoskEngine(
    private val context: Context,
    private val maxGain: () -> Float,
    private val onWords: (Int) -> Unit,
    private val onReady: () -> Unit,
    private val onFail: (String) -> Unit
) {

    companion object {
        private const val ASSET_DIR = "model-en-us"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK = 1600 // 0.1 s of sound per step

        @Volatile
        private var model: Model? = null
    }

    /**
     * Automatic gain: quiet speech is raised towards a comfortable level, loud speech is
     * left alone (so it is never distorted). [maxGain] limits how much it may raise.
     */
    class AutoGain {
        private var envelope = 0f
        private var gain = 1f

        fun process(buf: ShortArray, n: Int, maxGain: Float): Float {
            var peak = 0f
            for (i in 0 until n) {
                val a = abs(buf[i].toFloat())
                if (a > peak) peak = a
            }
            // follow peaks at once, forget them slowly (about 1.4 dB per 0.1 s)
            envelope = if (peak > envelope) peak else maxOf(peak, envelope * 0.85f)
            val wanted = (TARGET / maxOf(envelope, 1f)).coerceIn(1f, maxOf(1f, maxGain))
            // turn down at once, turn up gently
            gain = if (wanted < gain) wanted else gain + (wanted - gain) * 0.2f
            if (gain > 1.001f) {
                val room = 32767f - KNEE
                for (i in 0 until n) {
                    val x = buf[i] * gain
                    val a = abs(x)
                    val y = if (a <= KNEE) a else KNEE + room * tanh((a - KNEE) / room)
                    buf[i] = (if (x < 0) -y else y).toInt().toShort()
                }
            }
            return minOf(1f, peak * gain / 32767f)
        }

        companion object {
            const val TARGET = 12000f // about -9 dB below full scale
            const val KNEE = 24000f
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayBlockingQueue<ShortArray>(150) // up to 15 s of sound
    private val endMark = ShortArray(0)

    @Volatile
    private var stopped = false
    private var countedThisPhrase = 0
    private var previousPartial = 0

    fun start() {
        val m = model
        if (m != null) {
            begin(m)
            return
        }
        try {
            // First run after install/update: copy the model out of the app (a few seconds).
            StorageService.unpack(context, ASSET_DIR, "model",
                { loaded: Model ->
                    model = loaded
                    if (!stopped) begin(loaded)
                },
                { e: java.io.IOException ->
                    if (!stopped) onFail("unpack: ${e.message}")
                })
        } catch (e: Exception) {
            onFail("unpack: ${e.message}")
        }
    }

    private fun begin(m: Model) {
        Thread({ record() }, "mama-record").start()
        Thread({ recognize(m) }, "mama-recognize").start()
    }

    fun stop() {
        stopped = true
        queue.offer(endMark)
    }

    /** Recording thread: microphone -> queue. Does nothing else, so it never falls behind. */
    private fun record() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: Exception) {
        }
        var ar: AudioRecord? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            ar = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SAMPLE_RATE * 2 * 2) // 2 s of room inside the recorder
            )
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                post { onFail("mic init") }
                return
            }
            ar.startRecording()
            if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                post { onFail("mic busy") }
                return
            }
            post { onReady() }

            while (!stopped) {
                val buf = ShortArray(CHUNK)
                var filled = 0
                while (filled < CHUNK && !stopped) {
                    val n = ar.read(buf, filled, CHUNK - filled)
                    if (n < 0) {
                        post { onFail("mic read $n") }
                        return
                    }
                    filled += n
                }
                if (filled < CHUNK) break
                if (!queue.offer(buf)) {
                    // recognition is far behind: drop the oldest sound rather than the newest
                    queue.poll()
                    queue.offer(buf)
                }
            }
        } catch (e: Exception) {
            post { onFail("record: ${e.message}") }
        } finally {
            try {
                ar?.stop()
            } catch (e: Exception) {
            }
            try {
                ar?.release()
            } catch (e: Exception) {
            }
            queue.offer(endMark)
        }
    }

    /** Recognition thread: queue -> automatic gain -> model; results go to the main thread. */
    private fun recognize(m: Model) {
        var rec: Recognizer? = null
        try {
            rec = Recognizer(m, SAMPLE_RATE.toFloat())
            val agc = AutoGain()
            var lastPartial = ""
            while (true) {
                val buf = queue.poll(500, TimeUnit.MILLISECONDS)
                if (buf == null) {
                    if (stopped) break
                    continue
                }
                if (buf === endMark || stopped) break
                MicLevel.set(agc.process(buf, buf.size, maxGain()))
                if (rec.acceptWaveForm(buf, buf.size)) {
                    val r = rec.result
                    lastPartial = ""
                    post { handleResult(r) }
                } else {
                    val p = rec.partialResult
                    if (p != lastPartial) {
                        lastPartial = p
                        post { handlePartial(p) }
                    }
                }
            }
        } catch (e: Exception) {
            post { onFail("recognize: ${e.message}") }
        } finally {
            MicLevel.set(0f)
            try {
                rec?.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun post(block: () -> Unit) {
        main.post { if (!stopped) block() }
    }

    private fun countIn(json: String?, key: String): Int {
        if (json.isNullOrEmpty()) return 0
        val text = try {
            JSONObject(json).optString(key, "")
        } catch (e: Exception) {
            ""
        }
        return text.split(' ').count { it == "mama" }
    }

    private fun handlePartial(json: String) {
        // Count from the live guess only once it is stable in two updates in a row
        // (a fraction of a second), so a word that flickers by is not counted.
        val now = countIn(json, "partial")
        val stable = minOf(now, previousPartial)
        previousPartial = now
        if (stable > countedThisPhrase) {
            onWords(stable - countedThisPhrase)
            countedThisPhrase = stable
        }
    }

    private fun handleResult(json: String) {
        val n = countIn(json, "text")
        if (n > countedThisPhrase) onWords(n - countedThisPhrase)
        countedThisPhrase = 0
        previousPartial = 0
    }
}

/** Current microphone level (0..1) for the meter on the screen. */
object MicLevel {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(Float) -> Unit>()

    @Volatile
    var level = 0f
        private set

    fun set(v: Float) {
        level = v
        main.post { listeners.toList().forEach { it(v) } }
    }

    fun addListener(l: (Float) -> Unit) {
        main.post { listeners += l }
    }

    fun removeListener(l: (Float) -> Unit) {
        main.post { listeners -= l }
    }
}
