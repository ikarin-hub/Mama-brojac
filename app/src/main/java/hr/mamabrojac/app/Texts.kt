package hr.mamabrojac.app

/** All texts of the app in English (default) and Croatian. */
object Texts {
    private val en = mapOf(
        "subtitle" to "Tap “Start listening” and leave your phone – it keeps counting while you use other apps.",
        "label" to "TIMES SAID “MOM”",
        "start" to "Start listening",
        "stop" to "Stop listening",
        "reset" to "Reset",
        "plus" to "+1 manually",
        "heard_label" to "WHAT I HEARD",
        "heard_hint" to "Spoken words will appear here…",
        "battery" to "Allow running in the background (turn off battery optimization)",
        "footer" to "While listening, “Say mom” shows a notification. No audio is stored.",
        "not_listening" to "Not listening.",
        "starting" to "Starting…",
        "listening" to "Listening…",
        "network" to "No connection to the speech service – retrying…",
        "lang_fallback" to "This language isn't available for speech – using the phone's language.",
        "no_service" to "This phone has no speech recognition service. Install or update the Google app.",
        "fg_fail" to "Can't start listening in the background.",
        "no_perm" to "The app has no microphone permission.",
        "perm_denied" to "Without microphone permission I can't listen. Allow it in the app settings.",
        "notif_title" to "Times said “mom”: %d",
        "notif_text" to "Listening and counting…",
        "notif_stop" to "Stop",
        "channel" to "Listening",
        "channel_desc" to "Shown while Say mom is listening"
    )

    private val hr = mapOf(
        "subtitle" to "Pritisni „Počni slušati” i ostavi mobitel – broji i kad koristiš druge aplikacije.",
        "label" to "REČENO „MAMA”",
        "start" to "Počni slušati",
        "stop" to "Zaustavi slušanje",
        "reset" to "Na nulu",
        "plus" to "+1 ručno",
        "forms" to "Broji i padeže (mame, mami, mamu, mamo, mamom…)",
        "heard_label" to "ŠTO SAM ČUO",
        "heard_hint" to "Ovdje će se pojaviti izgovoreni tekst…",
        "battery" to "Dopusti rad u pozadini (isključi štednju baterije)",
        "footer" to "Dok sluša, u obavijestima stoji „Say mom”. Zvuk se ne sprema.",
        "not_listening" to "Ne slušam.",
        "starting" to "Pokrećem…",
        "listening" to "Slušam…",
        "network" to "Nema veze s uslugom prepoznavanja – pokušavam ponovno…",
        "lang_fallback" to "Ovaj jezik nije dostupan za govor – koristim jezik mobitela.",
        "no_service" to "Na mobitelu nema usluge za prepoznavanje govora. Instaliraj ili ažuriraj aplikaciju Google.",
        "fg_fail" to "Ne mogu pokrenuti slušanje u pozadini.",
        "no_perm" to "Aplikacija nema dozvolu za mikrofon.",
        "perm_denied" to "Bez dozvole za mikrofon ne mogu slušati. Dopusti je u postavkama aplikacije.",
        "notif_title" to "Rečeno „mama”: %d",
        "notif_text" to "Slušam i brojim…",
        "notif_stop" to "Zaustavi",
        "channel" to "Slušanje",
        "channel_desc" to "Prikazuje se dok Say mom sluša"
    )

    /** Text for a key; unknown keys (e.g. old saved messages) are shown as they are. */
    fun s(lang: String, key: String): String =
        (if (lang == "hr") hr[key] else en[key]) ?: en[key] ?: key
}
