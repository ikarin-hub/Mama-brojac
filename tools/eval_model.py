"""Build-time experiment: which way of using the model counts "mama" best?
Speech is synthesized with espeak-ng (several voices/speeds), so this is only an indication."""
import json, subprocess, sys, wave
from vosk import Model, KaldiRecognizer, SetLogLevel

SetLogLevel(-1)
model = Model(sys.argv[1])

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

ENGLISH_FILLER = ("a the and to of in is it you that he was for on are as with his they at be this have "
    "from or one had by word but not what all were we when your can said there use an each which she do "
    "how their if will up other about out many then them these so some her would make like him into time "
    "has look two more go see no way could people my than first water been call who oil its now find long "
    "down day did get come made may part ma pa la na no mall mellow follow hollow valley value hall tom "
    "some summer number lama llama drama karma comma ham hum home mom mum mother father papa nana sam "
    "come camera calm palm psalm alarm uh ah oh um hmm").split()

METHODS = {
    "grammar: mama": dict(grammar=["mama", "[unk]"], hits={"mama"}),
    "grammar: mama + filler": dict(grammar=["mama", "[unk]"] + ENGLISH_FILLER, hits={"mama"}),
    "free: mama": dict(grammar=None, hits={"mama"}),
    "free: mama/momma/mamma": dict(grammar=None, hits={"mama", "momma", "mamma"}),
}

def synth(text, voice, speed, i):
    raw, out = f"s{i}.wav", f"s{i}_16.wav"
    subprocess.run(["espeak-ng", "-v", voice, "-s", str(speed), "-w", raw, text], check=True)
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", raw, "-ac", "1", "-ar", "16000", out], check=True)
    return out

def recognize(path, grammar):
    rec = KaldiRecognizer(model, 16000, json.dumps(grammar)) if grammar else KaldiRecognizer(model, 16000)
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

for name, m in METHODS.items():
    said = heard_right = false_hits = 0
    fp_examples = {}
    for path, n, text in clips:
        words = recognize(path, m["grammar"])
        k = sum(1 for w in words if w in m["hits"])
        said += n
        heard_right += min(k, n)
        if k > n:
            false_hits += k - n
            fp_examples[text] = " ".join(words)
    print(f"RESULT | {name:26s} | found {heard_right}/{said} | false hits {false_hits}")
    for t, w in list(fp_examples.items())[:6]:
        print(f"   false: {t!r} -> {w!r}")
