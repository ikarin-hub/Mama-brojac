# Reci mama (Android)

Aplikacija sluša u pozadini i broji koliko je puta netko rekao „mama”.

- Pritisni **Počni slušati**. Aplikacija nastavlja slušati i kad koristiš druge aplikacije ili zaključaš ekran.
- Dok sluša, u obavijestima stoji „Reci mama” s brojem i gumbom **Zaustavi**.
- Broji „mama” (i „mamaaa”), a po želji i padeže (mame, mami, mamu, mamo, mamom…).
- **Statistika** se trajno pamti po danima: danas, ovaj tjedan, ovaj mjesec, ukupno, graf i cijela povijest po danima, tjednima i mjesecima. „Na nulu” vraća samo glavni brojač, statistika ostaje.
- **Brzi način** (zadano): mali model na samom mobitelu (Vosk) neprekidno sluša i broji „mama” – bez interneta, bez pauza i bez zvuka. Model se preuzima pri gradnji.
- **Standardni način**: Googleovo prepoznavanje govora ugrađeno u mobitel (podržava i padeže). Koristi se i automatski ako brzi način na mobitelu ne radi.
- Zvuk se ne sprema i ne šalje.
- Svijetla i tamna tema prate postavku mobitela.

## Gradnja

GitHub Actions (`.github/workflows/build.yml`) izgradi APK pri svakoj promjeni
i objavi ga pod **Releases** kao `reci-mama.apk`.
