package hr.mamabrojac.app

import android.content.Context
import android.content.SharedPreferences

/** Shared state between the screen and the listening service. */
object Store {
    const val COUNT = "count"
    const val FORMS = "forms"
    const val LAST = "last"
    const val STATUS = "status"   // status code, translated by Texts
    const val ERROR = "error"     // error code, translated by Texts
    const val TICK = "tick"
    const val LANG = "lang"       // "en" (default) or "hr"
    const val MUTED = "muted"     // streams we muted, so they can be restored

    fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("mama", Context.MODE_PRIVATE)

    fun lang(p: SharedPreferences): String =
        if (p.getString(LANG, "en") == "hr") "hr" else "en"

    fun add(p: SharedPreferences, n: Int) {
        if (n <= 0) return
        p.edit().putInt(COUNT, p.getInt(COUNT, 0) + n).apply()
    }

    fun reset(p: SharedPreferences) {
        Live.setPartial("")
        p.edit().putInt(COUNT, 0).putString(LAST, "").apply()
    }
}

/**
 * English: counts mom, mum, mommy, mummy, mama, momma, mamma.
 * Croatian: counts "mama" (also drawn-out "mamaaa"), optionally with case forms.
 */
object WordCounter {
    private val english =
        Regex("(?<!\\p{L})(?:mom|mum|mommy|mummy|mama|momma|mamma)(?!\\p{L})", RegexOption.IGNORE_CASE)
    private val croatian =
        Regex("(?<!\\p{L})mam+a+(?!\\p{L})", RegexOption.IGNORE_CASE)
    private val croatianForms =
        Regex(
            "(?<!\\p{L})mam+(?:a+|e+|i+|u+|o+|om|ama|ica|ice|ici|icu|ico|icom)(?!\\p{L})",
            RegexOption.IGNORE_CASE
        )

    fun regex(lang: String, forms: Boolean): Regex = when {
        lang != "hr" -> english
        forms -> croatianForms
        else -> croatian
    }

    fun count(text: String, lang: String, forms: Boolean): Int =
        regex(lang, forms).findAll(text).count()

    /** Speech recognition language for the app language. */
    fun speechLocale(lang: String): String = if (lang == "hr") "hr-HR" else "en-US"
}
