package hr.mamabrojac.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import kotlin.math.abs
import kotlin.math.tanh

/**
 * A small speech model that runs on the phone, without internet, listens continuously
 * (no restarts, no beeps) and counts the word "mama".
 *
 * It decodes freely with its whole vocabulary: in build tests on spoken Croatian this
 * found about 9 of 10 "mama"s with very few false hits, while restricting it to the
 * single word "mama" made it hear "mama" in almost everything ("hvala", "ovamo"…).
 *
 * The microphone is read here directly, so the sound can be amplified
 * ("microphone sensitivity") before the model hears it.
 */
class VoskEngine(
    private val context: Context,
    private val gain: () -> Float,
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

        /**
         * Amplifies 16-bit samples in place. Loud peaks are bent smoothly instead of
         * being cut off, so amplified speech does not crackle.
         */
        fun amplify(buf: ShortArray, n: Int, g: Float) {
            if (g <= 1.001f) return
            val knee = 24000f
            val room = 32767f - knee
            for (i in 0 until n) {
                val x = buf[i] * g
                val a = abs(x)
                val y = if (a <= knee) a else knee + room * tanh((a - knee) / room)
                buf[i] = (if (x < 0) -y else y).toInt().toShort()
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var stopped = false
    private var loop: Thread? = null
    private var countedThisPhrase = 0
    private var previousPartial = 0
    private var lastPartialJson = ""

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
        val t = Thread({ listen(m) }, "mama-listen")
        loop = t
        t.start()
    }

    /** Runs on its own thread: microphone -> amplify -> model; results go to the main thread. */
    private fun listen(m: Model) {
        var rec: Recognizer? = null
        var ar: AudioRecord? = null
        try {
            rec = Recognizer(m, SAMPLE_RATE.toFloat())
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            ar = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, CHUNK * 2 * 4)
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

            val buf = ShortArray(CHUNK)
            while (!stopped) {
                val n = ar.read(buf, 0, buf.size)
                if (n < 0) {
                    post { onFail("mic read $n") }
                    return
                }
                if (n == 0) continue
                amplify(buf, n, gain())
                if (rec.acceptWaveForm(buf, n)) {
                    val r = rec.result
                    post { handleResult(r) }
                } else {
                    val p = rec.partialResult
                    if (p != lastPartialJson) {
                        lastPartialJson = p
                        post { handlePartial(p) }
                    }
                }
            }
            val f = rec.finalResult
            post { handleResult(f) }
        } catch (e: Exception) {
            post { onFail("listen: ${e.message}") }
        } finally {
            try {
                ar?.stop()
            } catch (e: Exception) {
            }
            try {
                ar?.release()
            } catch (e: Exception) {
            }
            try {
                rec?.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun post(block: () -> Unit) {
        main.post { if (!stopped) block() }
    }

    fun stop() {
        stopped = true
        loop = null
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
