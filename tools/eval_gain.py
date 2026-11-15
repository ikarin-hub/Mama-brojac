"""Build-time experiment: does amplifying quiet speech help the model find "mama"?
Speech (espeak-ng, Croatian) is made quiet as if spoken from further away, a constant
microphone noise floor is added, then each gain is applied the same way the app does it."""
import json, subprocess, sys, wave
import numpy as np
from vosk import Model, KaldiRecognizer, SetLogLevel

SetLogLevel(-1)
model = Model(sys.argv[1])
rng = np.random.default_rng(7)

POS = [("Mama, dođi ovamo.", 1), ("Mama!", 1), ("Mama, mama, gledaj!", 2), ("Gdje je mama?", 1),
       ("Volim te, mama.", 1), ("Mama je kod kuće.", 1), ("Tata i mama idu van.", 1), ("Mama, hvala ti.", 1)]
NEG = ["Hvala lijepa.", "Dođi ovamo.", "Malo mlijeka, molim.", "Tamo je lama.", "Nama i vama.",
       "Mačka spava.", "Tata radi.", "Ma daj, nemoj.", "Mala je tamo.", "Gdje je tata?"]
VOICES = [("hr", 145), ("hr+f3", 155), ("hr+m3", 165)]
LEVELS = {"normal volume": 1.0, "quiet (-20 dB)": 0.1, "very quiet (-30 dB)": 0.032, "far (-38 dB)": 0.0126}
GAINS = [1.0, 2.5, 4.0, 8.0]
NOISE_STD = 25.0  # about -62 dBFS, a typical phone microphone noise floor

def synth(text, voice, speed, i):
    subprocess.run(["espeak-ng", "-v", voice, "-s", str(speed), "-w", f"g{i}.wav", text], check=True)
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", f"g{i}.wav", "-ac", "1", "-ar", "16000",
                    "-f", "s16le", f"g{i}.raw"], check=True)
    return np.fromfile(f"g{i}.raw", dtype=np.int16).astype(np.float32)

def amplify(x, g):  # same as VoskEngine.amplify
    if g <= 1.001:
        return x
    y = x * g
    a = np.abs(y)
    knee, room = 24000.0, 32767.0 - 24000.0
    out = np.where(a <= knee, a, knee + room * np.tanh((a - knee) / room))
    return np.sign(y) * out

def count(samples):
    rec = KaldiRecognizer(model, 16000)
    pcm = np.clip(samples, -32768, 32767).astype(np.int16).tobytes()
    words = []
    for k in range(0, len(pcm), 3200):
        if rec.AcceptWaveform(pcm[k:k + 3200]):
            words += json.loads(rec.Result()).get("text", "").split()
    words += json.loads(rec.FinalResult()).get("text", "").split()
    return words.count("mama")

clips, i = [], 0
for v, sp in VOICES:
    for t, n in POS:
        clips.append((synth(t, v, sp, i), n)); i += 1
    for t in NEG:
        clips.append((synth(t, v, sp, i), 0)); i += 1

said = sum(n for _, n in clips)
for lname, lvl in LEVELS.items():
    for g in GAINS:
        found = false_hits = 0
        for x, n in clips:
            pad = np.zeros(4000, dtype=np.float32)
            quiet = np.concatenate([pad, x * lvl, pad])
            noisy = quiet + rng.normal(0, NOISE_STD, quiet.shape).astype(np.float32)
            k = count(amplify(noisy, g))
            found += min(k, n)
            false_hits += max(0, k - n)
        print(f"GAIN | {lname:20s} | gain {g:>3} | found {found}/{said} | false hits {false_hits}")
