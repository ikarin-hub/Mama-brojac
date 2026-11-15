package hr.mamabrojac.app

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * "Fast mode": a small speech model that runs on the phone, without internet,
 * listens continuously (no restarts, no beeps) and counts the word "mama".
 *
 * It decodes freely with its whole vocabulary: in build tests on spoken Croatian this
 * found about 9 of 10 "mama"s with very few false hits, while restricting it to the
 * single word "mama" made it hear "mama" in almost everything ("hvala", "ovamo"…).
 */
class VoskEngine(
    private val context: Context,
    private val onWords: (Int) -> Unit,
    private val onReady: () -> Unit,
    private val onFail: (String) -> Unit
) : RecognitionListener {

    companion object {
        private const val ASSET_DIR = "model-en-us"
        private const val SAMPLE_RATE = 16000f

        @Volatile
        private var model: Model? = null
    }

    private var service: SpeechService? = null
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
        try {
            val rec = Recognizer(m, SAMPLE_RATE)
            val s = SpeechService(rec, SAMPLE_RATE)
            service = s
            if (!s.startListening(this)) {
                onFail("mic busy")
                return
            }
            onReady()
        } catch (e: Exception) {
            onFail("start: ${e.message}")
        }
    }

    fun stop() {
        stopped = true
        try {
            service?.stop()
            service?.shutdown()
        } catch (e: Exception) {
        }
        service = null
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

    override fun onPartialResult(hypothesis: String?) {
        if (stopped) return
        // Count from the live guess only once it is stable in two updates in a row
        // (a fraction of a second), so a word that flickers by is not counted.
        val now = countIn(hypothesis, "partial")
        val stable = minOf(now, previousPartial)
        previousPartial = now
        if (stable > countedThisPhrase) {
            onWords(stable - countedThisPhrase)
            countedThisPhrase = stable
        }
    }

    override fun onResult(hypothesis: String?) {
        if (stopped) return
        val n = countIn(hypothesis, "text")
        if (n > countedThisPhrase) onWords(n - countedThisPhrase)
        countedThisPhrase = 0
        previousPartial = 0
    }

    override fun onFinalResult(hypothesis: String?) {
        onResult(hypothesis)
    }

    override fun onError(exception: Exception?) {
        if (!stopped) onFail("error: ${exception?.message}")
    }

    override fun onTimeout() {}
}
