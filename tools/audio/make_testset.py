#!/usr/bin/env python3
"""Arma audios de prueba para el clasificador de categorías (AudioClassifier.kt).

Sin internet: voz y risa con espeak-ng (español), música, sirenas, campanas y ruidos
sintetizados con numpy, y los 12 sonidos integrados de la app pasados por ffmpeg. Cada archivo
empieza con su categoría esperada: «voz_hola.wav», «fiesta_beat.wav»…

  python3 tools/audio/make_testset.py SALIDA/

Requiere numpy, ffmpeg y espeak-ng. Ojo: son audios de laboratorio; los reales (WhatsApp, TikTok)
suenan distinto. Sirve para no romper lo que ya anda, no para medir la precisión real.
"""
import os
import subprocess
import sys
import wave

import numpy as np

SR = 16000
rng = np.random.default_rng(3)
REPO = os.path.join(os.path.dirname(__file__), "..", "..")


def save(path, x):
    x = np.clip(x, -1, 1)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((x * 32767).astype(np.int16).tobytes())


def t(dur):
    return np.arange(int(SR * dur)) / SR


def espeak(out, text, voice="es", pitch=50, speed=160, amp=150):
    tmp = out + ".raw.wav"
    subprocess.run(["espeak-ng", "-v", voice, "-p", str(pitch), "-s", str(speed), "-a", str(amp), "-w", tmp, text], check=True)
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", tmp, "-ac", "1", "-ar", str(SR), out], check=True)
    os.remove(tmp)


def kick(n):
    tt = np.arange(n) / SR
    return np.sin(2 * np.pi * (50 + 120 * np.exp(-tt * 30)) * tt) * np.exp(-tt * 9)


def music(dur=8.0, bpm=120, soft=False):
    x = np.zeros(int(SR * dur))
    beat = 60 / bpm
    chords = [(261.6, 329.6, 392.0), (220.0, 261.6, 329.6), (174.6, 220.0, 261.6), (196.0, 246.9, 293.7)]
    for i in range(int(dur / beat)):
        s = int(i * beat * SR)
        if not soft:
            k = kick(int(0.25 * SR))
            x[s:s + len(k)] += 0.8 * k[: len(x) - s]
            h = rng.normal(0, 1, int(0.05 * SR)) * np.exp(-np.arange(int(0.05 * SR)) / SR * 80)
            hs = s + int(beat / 2 * SR)
            x[hs:hs + len(h)] += 0.15 * h[: max(0, len(x) - hs)]
        c = chords[(i // 4) % 4]
        n = int(beat * SR)
        tt = np.arange(n) / SR
        tone = sum(np.sin(2 * np.pi * f * tt) + 0.3 * np.sin(4 * np.pi * f * tt) for f in c) / 3
        x[s:s + n] += (0.25 if not soft else 0.12) * tone[: len(x) - s] * np.exp(-tt * 2)
    return x / np.max(np.abs(x)) * (0.9 if not soft else 0.1)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "testset"
    os.makedirs(out, exist_ok=True)
    p = lambda name: os.path.join(out, name)

    # Voz: frases normales con distintas voces.
    espeak(p("voz_hola.wav"), "Hola Ana, ¿cómo estás? Te mando este audio para que te rías un rato con lo que pasó hoy en el colegio.")
    espeak(p("voz_mujer.wav"), "Oye, ¿vienes a la fiesta del sábado? Avísame antes del viernes porque tengo que comprar las cosas.", "es+f3", 60)
    espeak(p("voz_rapida.wav"), "Ya po, apúrate que se nos hace tarde y la micro pasa en cinco minutos, no me dejes esperando.", "es-419", 45, 210)
    espeak(p("voz_grave.wav"), "Este es un mensaje muy importante. Por favor, escúchalo con atención hasta el final.", "es+m3", 25, 140)

    # Risa: «ja ja ja» agudos y rápidos.
    espeak(p("risa_jaja.wav"), "ja ja ja ja ja ja ja ja ja ja", "es", 85, 260)
    espeak(p("risa_jeje.wav"), "je je je je je jeee je je je je", "es+f4", 95, 280)
    espeak(p("risa_jiji.wav"), "ji ji ji ji ji ji ji ji ji", "es+f2", 99, 300)

    # Fiesta: música con ritmo.
    save(p("fiesta_beat.wav"), music(8, 124))
    save(p("fiesta_rapida.wav"), music(6, 150))

    # Susto: sirena que sube y baja, alarma de pitidos, grito.
    tt = t(4)
    save(p("susto_sirena.wav"), 0.9 * np.sin(2 * np.pi * np.cumsum(700 + 500 * np.sin(2 * np.pi * 0.5 * tt)) / SR))
    beep = (np.sin(2 * np.pi * 2000 * tt) * ((tt * 4) % 1 < 0.5)) * 0.95
    save(p("susto_alarma.wav"), beep)
    # Grito: tono agudo que tiembla, con armónicos y aire.
    tg = t(2.2)
    f0 = 950 + 60 * np.sin(2 * np.pi * 6 * tg) + 40 * rng.normal(0, 1, len(tg)).cumsum() / np.sqrt(len(tg))
    phase = 2 * np.pi * np.cumsum(f0) / SR
    grito = sum(np.sin(k * phase) / k for k in range(1, 6)) + rng.normal(0, 0.25, len(tg))
    save(p("susto_grito.wav"), 0.85 * grito / np.max(np.abs(grito)) * np.minimum(1, tg * 20))

    # Amor: cajita de música suave y campanitas que se apagan.
    save(p("amor_cajita.wav"), music(6, 70, soft=True))
    bells = np.zeros(int(SR * 5))
    for i, f in enumerate((1046.5, 1318.5, 1568.0, 1318.5)):
        s = int(i * 1.1 * SR)
        n = int(2.5 * SR)
        tb = np.arange(n) / SR
        tone = (np.sin(2 * np.pi * f * tb) + 0.4 * np.sin(2 * np.pi * 2.76 * f * tb)) * np.exp(-tb * 2.5) * 0.12
        bells[s:s + n] += tone[: len(bells) - s]
    save(p("amor_campanitas.wav"), bells)

    # Troll: ruido raro, pedo grave y láser.
    tn = t(2.5)
    noise = rng.normal(0, 0.4, len(tn)) * (0.5 + 0.5 * np.sin(2 * np.pi * 3 * tn))
    save(p("troll_ruido.wav"), noise)
    buzz = np.sign(np.sin(2 * np.pi * np.cumsum(70 + 30 * rng.random(len(tn))) / SR)) * 0.5 + rng.normal(0, 0.2, len(tn))
    lowpass = np.convolve(buzz, np.ones(12) / 12, mode="same")
    save(p("troll_pedo.wav"), lowpass * np.exp(-tn * 0.5))
    tl = t(1.5)
    freq = 3000 * np.exp(-((tl * 6) % 1) * 3)
    save(p("troll_laser.wav"), 0.7 * np.sign(np.sin(2 * np.pi * np.cumsum(freq) / SR)))

    # Los integrados de la app, con la categoría que traen de fábrica.
    builtin = {
        "bocina": "susto", "sirena": "susto", "alarma": "susto", "pato": "risa", "redoble": "fiesta", "laser": "troll",
        "campana": "amor", "trompeta": "risa", "boing": "risa", "pedorreta": "troll", "tada": "fiesta", "grillos": "risa",
    }
    for key, cat in builtin.items():
        src = os.path.join(REPO, "app", "src", "main", "assets", "sounds", key + ".ogg")
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", src, "-ac", "1", "-ar", str(SR), p(f"{cat}_app-{key}.wav")], check=True)
    print("Listo:", len(os.listdir(out)), "audios en", out)


if __name__ == "__main__":
    main()
