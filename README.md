# Say mom (Android)

An app that listens in the background and counts how many times someone says "mom".

- Tap **Start listening**. It keeps listening while you use other apps or the screen is locked.
- While listening, a "Say mom" notification shows the count and a **Stop** button.
- English (default): listens to English and counts mom, mum, mommy, mummy, mama, momma.
- Hrvatski (EN/HR button at the top): listens to Croatian and counts "mama", optionally with case forms.
- Speech is recognized by the phone's built-in Google speech service. No audio is stored.
- The recognizer's beep is muted for the short moment it restarts between phrases.
- Prefers on-device recognition (faster); on Android 13+ uses one long continuous session instead of restarting after every sentence; a watchdog restarts the recognizer if it ever stalls.

## Build

GitHub Actions (`.github/workflows/build.yml`) builds the APK on every push
and publishes it under **Releases** as `say-mom.apk`.

---
Copyright © Occhiblu – ivan.karin@gmail.com
