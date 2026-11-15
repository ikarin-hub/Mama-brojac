package hr.mamabrojac.app

import android.os.Handler
import android.os.Looper

/**
 * Fast in-memory channel for the live (not yet final) text.
 * It changes several times per second, so it is kept out of storage
 * and the screen redraws only the "What I heard" box for it.
 */
object Live {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    @Volatile
    var partial: String = ""
        private set

    fun setPartial(text: String) {
        if (text == partial) return
        partial = text
        main.post { listeners.toList().forEach { it() } }
    }

    fun addListener(l: () -> Unit) {
        main.post { listeners += l }
    }

    fun removeListener(l: () -> Unit) {
        main.post { listeners -= l }
    }
}
