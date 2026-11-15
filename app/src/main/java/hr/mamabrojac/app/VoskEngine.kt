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
 * listens continuously (no restarts, no beeps) and only looks for the word "mama".
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
        // Only "mama" or "something else": keeps it fast and avoids false hits.
        private const val GRAMMAR = "[\"mama\", \"[unk]\"]"

        @Volatile
        private var model: Model? = null
    }

    private var service: SpeechService? = null
    private var stopped = false
    private var countedThisPhrase = 0

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
            val rec = Recognizer(m, SAMPLE_RATE, GRAMMAR)
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
        val n = countIn(hypothesis, "partial")
        if (n > countedThisPhrase) {
            onWords(n - countedThisPhrase)
            countedThisPhrase = n
        }
    }

    override fun onResult(hypothesis: String?) {
        if (stopped) return
        val n = countIn(hypothesis, "text")
        if (n > countedThisPhrase) onWords(n - countedThisPhrase)
        countedThisPhrase = 0
    }

    override fun onFinalResult(hypothesis: String?) {
        onResult(hypothesis)
    }

    override fun onError(exception: Exception?) {
        if (!stopped) onFail("error: ${exception?.message}")
    }

    override fun onTimeout() {}
}
