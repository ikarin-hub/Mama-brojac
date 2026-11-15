# Mama-brojač (Android)

Aplikacija sluša u pozadini i broji koliko je puta netko rekao „mama”.

- Pritisni **Počni slušati**. Aplikacija nastavlja slušati i kad otvoriš druge aplikacije ili zaključaš ekran.
- Dok sluša, u obavijestima stoji „Mama-brojač” s gumbom **Zaustavi**.
- Za prepoznavanje govora koristi Googleovu uslugu ugrađenu u mobitel (hrvatski, uglavnom treba internet).
- Zvuk se ne sprema, broje se samo prepoznate riječi.

## Gradnja

GitHub Actions (`.github/workflows/build.yml`) izgradi APK pri svakoj promjeni u repozitoriju
i objavi ga pod **Releases** kao `mama-brojac.apk`.
