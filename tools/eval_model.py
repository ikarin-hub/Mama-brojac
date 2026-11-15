"""Build-time experiment: which way of using the model counts "mama" best?
Speech is synthesized with espeak-ng (several voices/speeds), so this is only an indication."""
import json, subprocess, sys, wave
from vosk import Model, KaldiRecognizer, SetLogLevel

SetLogLevel(-1)
_models = {}
def get_model(d):
    if d not in _models:
        _models[d] = Model(d)
    return _models[d]

POS = [  # (sentence, how many times "mama" is said)
    ("Mama, dođi ovamo.", 1), ("Mama!", 1), ("Mama, mama, gledaj!", 2), ("Gdje je mama?", 1),
    ("Volim te, mama.", 1), ("Mama je kod kuće.", 1), ("Hoću mamu. Mama, mama!", 2),
    ("Mama, hvala ti.", 1), ("Tata i mama idu van.", 1), ("Mamaaa, dođi!", 1),
]
NEG = [
    "Hvala lijepa.", "Dođi ovamo.", "Malo mlijeka, molim.", "Tamo je lama.", "Samo malo.",
    "Nama i vama.", "Jama je duboka.", "Mamut i mamac.", "Mačka spava.", "Tata radi.",
    "Rama je lijepa.", "Ma daj, nemoj.", "Mala je tamo.", "Hvala, hvala.", "Imam malo vode.",
    "Zovem se Ana.", "Gdje je tata?", "Molim te, dođi.", "Lopta je ovdje.", "Pomalo, polako.",
]
VOICES = [("hr", 140), ("hr", 175), ("hr+f3", 150), ("hr+m3", 160)]

# model folder -> words that count as "mama" (free decoding, whole vocabulary)
METHODS = {
    "en small": ("models/en", {"mama"}),
    "en small (+momma/mamma)": ("models/en", {"mama", "momma", "mamma"}),
    "ru small": ("models/ru", {"мама"}),
    "ru small (+forms)": ("models/ru", {"мама", "маму", "мамы", "маме", "мамой", "мамочка", "мамо"}),
    "cs small": ("models/cs", {"máma", "mama"}),
    "cs small (+forms)": ("models/cs", {"máma", "mama", "mámo", "mámu", "mámy", "mámě", "mámou"}),
}
def synth(text, voice, speed, i):
    raw, out = f"s{i}.wav", f"s{i}_16.wav"
    subprocess.run(["espeak-ng", "-v", voice, "-s", str(speed), "-w", raw, text], check=True)
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", raw, "-ac", "1", "-ar", "16000", out], check=True)
    return out

def recognize(path, model_dir):
    rec = KaldiRecognizer(get_model(model_dir), 16000)
    wf = wave.open(path, "rb")
    words = []
    while True:
        d = wf.readframes(4000)
        if not d:
            break
        if rec.AcceptWaveform(d):
            words += json.loads(rec.Result()).get("text", "").split()
    words += json.loads(rec.FinalResult()).get("text", "").split()
    return words

clips, i = [], 0
for v, sp in VOICES:
    for t, n in POS:
        clips.append((synth(t, v, sp, i), n, t)); i += 1
    for t in NEG:
        clips.append((synth(t, v, sp, i), 0, t)); i += 1

for name, (mdir, hits) in METHODS.items():
    said = heard_right = false_hits = 0
    fp_examples = {}
    for path, n, text in clips:
        words = recognize(path, mdir)
        k = sum(1 for w in words if w in hits)
        said += n
        heard_right += min(k, n)
        if k > n:
            false_hits += k - n
            fp_examples[text] = " ".join(words)
    print(f"RESULT | {name:26s} | found {heard_right}/{said} | false hits {false_hits}")
    for t, w in list(fp_examples.items())[:6]:
        print(f"   false: {t!r} -> {w!r}")
