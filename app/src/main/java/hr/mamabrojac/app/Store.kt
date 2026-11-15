package hr.mamabrojac.app

import android.content.Context
import android.content.SharedPreferences

/** Shared state between the screen and the listening service. */
object Store {
    const val COUNT = "count"
    const val FORMS = "forms"
    const val LAST = "last"
    const val PARTIAL = "partial"
    const val STATUS = "status"
    const val ERROR = "error"
    const val TICK = "tick"

    fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("mama", Context.MODE_PRIVATE)

    fun add(p: SharedPreferences, n: Int) {
        if (n <= 0) return
        p.edit().putInt(COUNT, p.getInt(COUNT, 0) + n).apply()
    }

    fun reset(p: SharedPreferences) {
        p.edit().putInt(COUNT, 0).putString(LAST, "").putString(PARTIAL, "").apply()
    }
}

/** Finds "mama" (also drawn-out "mamaaa"), optionally with Croatian case forms. */
object MamaCounter {
    private val strict =
        Regex("(?<!\\p{L})mam+a+(?!\\p{L})", RegexOption.IGNORE_CASE)
    private val withForms =
        Regex(
            "(?<!\\p{L})mam+(?:a+|e+|i+|u+|o+|om|ama|ica|ice|ici|icu|ico|icom)(?!\\p{L})",
            RegexOption.IGNORE_CASE
        )

    fun regex(forms: Boolean): Regex = if (forms) withForms else strict

    fun count(text: String, forms: Boolean): Int = regex(forms).findAll(text).count()
}
