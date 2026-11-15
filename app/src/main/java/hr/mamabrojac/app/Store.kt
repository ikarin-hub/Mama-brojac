package hr.mamabrojac.app

import android.content.Context
import android.content.SharedPreferences
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** Shared state between the screen and the listening service. */
object Store {
    const val COUNT = "count"
    const val LAST = "last"
    const val STATUS = "status"   // status code, translated by Texts
    const val ERROR = "error"     // error code, translated by Texts
    const val TICK = "tick"
    const val MUTED = "muted"     // streams we muted, so they can be restored
    const val SENS = "sensitivity" // microphone sensitivity: 0 normal, 1 high (default), 2 highest

    /**
     * Amplification for each sensitivity level. Chosen from a build test with quiet
     * Croatian speech: very quiet speech went from 9/27 to 23/27 "mama"s found at 2.5x,
     * distant speech was only found at 8x (9/27), and normal speech was not harmed.
     */
    val GAINS = floatArrayOf(1.0f, 2.5f, 8.0f)

    fun gain(p: SharedPreferences): Float = GAINS[p.getInt(SENS, 1).coerceIn(0, GAINS.size - 1)]

    fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("mama", Context.MODE_PRIVATE)

    /** Adds to the main counter and to today's statistics. */
    fun add(c: Context, n: Int) {
        if (n <= 0) return
        val p = prefs(c)
        p.edit().putInt(COUNT, p.getInt(COUNT, 0) + n).apply()
        Stats.add(c, n)
    }

    /** Resets the main counter only; the statistics stay. */
    fun reset(p: SharedPreferences) {
        p.edit().putInt(COUNT, 0).putString(LAST, "").apply()
    }
}

/**
 * Statistics kept forever: one entry per day ("2026-11-15" -> 37).
 * Weeks (Monday–Sunday), months and the total are added up from the days.
 */
object Stats {
    fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("stats", Context.MODE_PRIVATE)

    fun add(c: Context, n: Int) {
        if (n <= 0) return
        val p = prefs(c)
        val key = LocalDate.now().toString()
        p.edit().putInt(key, p.getInt(key, 0) + n).apply()
    }

    fun days(c: Context): Map<LocalDate, Int> {
        val out = HashMap<LocalDate, Int>()
        for ((k, v) in prefs(c).all) {
            val d = try {
                LocalDate.parse(k)
            } catch (e: Exception) {
                null
            } ?: continue
            if (v is Int) out[d] = v
        }
        return out
    }

    fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun byWeek(days: Map<LocalDate, Int>): Map<LocalDate, Int> {
        val out = HashMap<LocalDate, Int>()
        for ((d, v) in days) {
            val w = weekStart(d)
            out[w] = (out[w] ?: 0) + v
        }
        return out
    }

    fun byMonth(days: Map<LocalDate, Int>): Map<YearMonth, Int> {
        val out = HashMap<YearMonth, Int>()
        for ((d, v) in days) {
            val m = YearMonth.from(d)
            out[m] = (out[m] ?: 0) + v
        }
        return out
    }

    data class Summary(val today: Int, val week: Int, val month: Int, val total: Int)

    fun summary(days: Map<LocalDate, Int>, today: LocalDate = LocalDate.now()): Summary {
        val ws = weekStart(today)
        val ym = YearMonth.from(today)
        var week = 0
        var month = 0
        var total = 0
        for ((d, v) in days) {
            total += v
            if (!d.isBefore(ws) && !d.isAfter(today)) week += v
            if (YearMonth.from(d) == ym) month += v
        }
        return Summary(days[today] ?: 0, week, month, total)
    }
}
