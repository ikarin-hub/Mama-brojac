"""Build check: does the fast-mode model find "mama" in synthesized Croatian speech?"""
import json, subprocess, sys, wave
from vosk import Model, KaldiRecognizer, SetLogLevel

SetLogLevel(0)  # show Vosk warnings (e.g. a word missing from the vocabulary) in the log
model_dir, wav_in = sys.argv[1], sys.argv[2]
subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", wav_in, "-ac", "1", "-ar", "16000", "test16.wav"], check=True)

model = Model(model_dir)
rec = KaldiRecognizer(model, 16000)  # free decoding, as in the app
wf = wave.open("test16.wav", "rb")
found = 0
while True:
    data = wf.readframes(4000)
    if not data:
        break
    if rec.AcceptWaveform(data):
        text = json.loads(rec.Result()).get("text", "")
        print("phrase:", text)
        found += text.split().count("mama")
text = json.loads(rec.FinalResult()).get("text", "")
print("phrase:", text)
found += text.split().count("mama")
print(f"FOUND mama x{found} (spoken 3 times)")
if not 2 <= found <= 4:
    print("::error::The fast-mode model did not count 'mama' correctly in the test sentence")
    sys.exit(1)
