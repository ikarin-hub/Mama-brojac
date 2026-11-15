package hr.mamabrojac.app

/** Texts shown from code (status, errors, notification, dates). */
object Texts {
    private val hr = mapOf(
        "start" to "Počni slušati",
        "stop" to "Zaustavi slušanje",
        "not_listening" to "Ne slušam",
        "starting" to "Pokrećem…",
        "listening" to "Slušam…",
        "preparing" to "Pripremam…",
        "retry" to "Mikrofon je zauzet – pokušavam ponovno…",
        "fg_fail" to "Ne mogu pokrenuti slušanje u pozadini.",
        "perm_denied" to "Bez dozvole za mikrofon ne mogu slušati. Dopusti je u postavkama aplikacije.",
        "notif_title" to "Rečeno „mama”: %d",
        "notif_text" to "Slušam i brojim…",
        "notif_stop" to "Zaustavi",
        "channel" to "Slušanje",
        "channel_desc" to "Prikazuje se dok Reci mama sluša"
    )

    /** Text for a key; unknown keys (e.g. old saved messages) are shown as they are. */
    fun s(key: String): String = hr[key] ?: key

    val MONTHS = arrayOf(
        "siječanj", "veljača", "ožujak", "travanj", "svibanj", "lipanj",
        "srpanj", "kolovoz", "rujan", "listopad", "studeni", "prosinac"
    )
    val MONTHS_SHORT = arrayOf("sij", "velj", "ožu", "tra", "svi", "lip", "srp", "kol", "ruj", "lis", "stu", "pro")
    val DAYS_SHORT = arrayOf("pon", "uto", "sri", "čet", "pet", "sub", "ned")
}
