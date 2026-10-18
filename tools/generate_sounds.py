#!/usr/bin/env python3
"""Genera los sonidos integrados de Friend Game (sintetizados, sin licencias de terceros).

Uso:  python3 tools/generate_sounds.py [nombre ...]
Sin nombres genera todos; con nombres, solo esos (así los demás quedan iguales).
Requiere numpy y ffmpeg (con libvorbis). Escribe los .ogg en app/src/main/assets/sounds/.
"""
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np

SR = 44100
OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "sounds")
rng = np.random.default_rng(7)


def t_axis(dur):
    return np.arange(int(SR * dur)) / SR


def env(n, attack=0.01, release=0.05):
    e = np.ones(n)
    a = max(1, int(SR * attack))
    r = max(1, int(SR * release))
    e[:a] = np.linspace(0, 1, a)
    e[-r:] = np.linspace(1, 0, r)
    return e


def saw(phase):
    return 2.0 * (phase - np.floor(phase + 0.5))


def square(phase):
    return np.sign(np.sin(2 * np.pi * phase))


def phase_from_freq(freq):
    return np.cumsum(freq) / SR


def lowpass(x, cutoff):
    # Filtro de un polo, suficiente para suavizar ondas duras.
    a = np.exp(-2 * np.pi * cutoff / SR)
    y = np.zeros_like(x)
    acc = 0.0
    for i, v in enumerate(x):
        acc = (1 - a) * v + a * acc
        y[i] = acc
    return y


def silence(dur):
    return np.zeros(int(SR * dur))


def bocina():
    parts = []
    for dur in (0.18, 0.18, 0.9):
        t = t_axis(dur)
        vib = 1 + 0.01 * np.sin(2 * np.pi * 6 * t)
        x = sum(saw(phase_from_freq(np.full(len(t), f) * vib)) for f in (415, 523, 622))
        parts += [lowpass(x, 3500) * env(len(t), 0.01, 0.06), silence(0.05)]
    return np.concatenate(parts)


def sirena():
    t = t_axis(2.4)
    freq = 750 + 450 * np.sin(2 * np.pi * 0.85 * t - np.pi / 2)
    x = np.sin(2 * np.pi * phase_from_freq(freq)) + 0.3 * saw(phase_from_freq(freq))
    return x * env(len(t), 0.05, 0.2)


def alarma():
    parts = []
    for _ in range(3):
        for _ in range(4):
            t = t_axis(0.07)
            parts += [square(1000 * t) * env(len(t), 0.003, 0.005), silence(0.05)]
        parts.append(silence(0.25))
    return lowpass(np.concatenate(parts), 6000)


def pato():
    parts = []
    for _ in range(2):
        t = t_axis(0.22)
        freq = 520 - 220 * (t / t[-1])
        x = saw(phase_from_freq(freq)) + 0.6 * square(phase_from_freq(freq * 2.01))
        am = np.minimum(1, t / 0.02) * np.exp(-t * 6)
        parts += [lowpass(x * am, 1800), silence(0.12)]
    return np.concatenate(parts)


def redoble():
    t = t_axis(1.4)
    hits = np.zeros(len(t))
    step = int(SR * 0.045)
    for start in range(0, len(t) - step, step):
        n = min(step, len(t) - start)
        hits[start:start + n] += rng.standard_normal(n) * np.exp(-np.arange(n) / (SR * 0.012))
    roll = lowpass(hits, 2500) * np.linspace(0.4, 1.0, len(t))
    tc = t_axis(1.6)
    crash = rng.standard_normal(len(tc)) * np.exp(-tc * 2.5)
    crash = crash - lowpass(crash, 3000)  # se queda con los agudos, tipo platillo
    return np.concatenate([roll, crash * 1.4])


def laser():
    parts = []
    for _ in range(3):
        t = t_axis(0.22)
        freq = 2200 * np.exp(-t * 14) + 180
        parts += [square(phase_from_freq(freq)) * env(len(t), 0.002, 0.03) * 0.7, silence(0.08)]
    return lowpass(np.concatenate(parts), 7000)


def campana():
    t = t_axis(2.5)
    x = np.zeros(len(t))
    for ratio, amp, decay in ((1.0, 1.0, 1.2), (2.76, 0.5, 2.0), (5.4, 0.25, 3.5), (8.93, 0.12, 5.0)):
        x += amp * np.sin(2 * np.pi * 880 * ratio * t) * np.exp(-t * decay)
    return x * env(len(t), 0.002, 0.1)


def trompeta_triste():
    parts = []
    notes = ((311, 0.38, False), (294, 0.38, False), (277, 0.38, False), (262, 1.2, True))
    for freq, dur, wobble in notes:
        t = t_axis(dur)
        f = np.full(len(t), float(freq))
        if wobble:
            f *= 1 + 0.03 * np.sin(2 * np.pi * 5 * t)
        x = saw(phase_from_freq(f)) + 0.5 * saw(phase_from_freq(f * 2))
        parts += [lowpass(x, 1500) * env(len(t), 0.03, 0.12), silence(0.04)]
    return np.concatenate(parts)


def boing():
    t = t_axis(1.0)
    freq = 220 + 160 * np.exp(-t * 3) * np.sin(2 * np.pi * 9 * t)
    return np.sin(2 * np.pi * phase_from_freq(freq)) * np.exp(-t * 3) * env(len(t), 0.005, 0.05)


def pedorreta():
    t = t_axis(1.1)
    freq = 95 + 25 * np.sin(2 * np.pi * 3 * t) + 12 * lowpass(rng.standard_normal(len(t)), 30)
    x = square(phase_from_freq(freq)) * 0.6 + saw(phase_from_freq(freq * 1.5)) * 0.4
    noise = lowpass(rng.standard_normal(len(t)), 400) * 0.6
    am = np.minimum(1, t / 0.03) * (1 - (t / t[-1]) ** 3)
    return lowpass(x + noise, 900) * am


def tada():
    t1 = t_axis(0.16)
    t2 = t_axis(1.3)
    def chord(t, freqs, decay):
        x = sum(saw(f * t) + 0.5 * np.sin(2 * np.pi * 2 * f * t) for f in freqs)
        return lowpass(x, 4000) * np.exp(-t * decay) * env(len(t), 0.005, 0.08)
    return np.concatenate([chord(t1, (523, 659), 2), silence(0.03), chord(t2, (523, 659, 784, 1047), 1.6)])


def grillos():
    parts = []
    for _ in range(4):
        for _ in range(3):
            t = t_axis(0.035)
            parts += [np.sin(2 * np.pi * 4600 * t) * env(len(t), 0.004, 0.01), silence(0.03)]
        parts.append(silence(0.4))
    return np.concatenate(parts)


# ---- Sonidos que se desbloquean subiendo de nivel ----


def timbre():
    def ding(freq, dur):
        t = t_axis(dur)
        x = np.sin(2 * np.pi * freq * t) + 0.35 * np.sin(2 * np.pi * freq * 3 * t) * np.exp(-t * 4)
        return x * np.exp(-t * 2.2) * env(len(t), 0.003, 0.08)
    return np.concatenate([ding(659, 0.7), ding(523, 1.5)])


def silbato():
    t = t_axis(1.3)
    trill = 1 + 0.06 * np.sign(np.sin(2 * np.pi * 28 * t))
    x = np.sin(2 * np.pi * phase_from_freq(2900 * trill))
    breath = lowpass(rng.standard_normal(len(t)), 5000) * 0.15
    am = np.where((t > 0.42) & (t < 0.52), 0.0, 1.0)
    return (x + breath) * am * env(len(t), 0.02, 0.08)


def moneda():
    parts = []
    for _ in range(2):
        t1 = t_axis(0.07)
        t2 = t_axis(0.38)
        parts += [square(988 * t1) * 0.6, square(1319 * t2) * 0.6 * np.exp(-t2 * 6), silence(0.12)]
    return lowpass(np.concatenate(parts), 8000)


def gong():
    t = t_axis(3.0)
    x = np.zeros(len(t))
    for ratio, amp, decay in ((1.0, 1.0, 0.8), (1.47, 0.6, 1.1), (2.09, 0.45, 1.5), (2.56, 0.3, 1.9), (3.9, 0.2, 2.6)):
        freq = 98 * ratio * (1 + 0.004 * np.sin(2 * np.pi * 0.7 * t))
        x += amp * np.sin(2 * np.pi * phase_from_freq(freq)) * np.exp(-t * decay)
    swell = np.minimum(1, t / 0.06)
    return x * swell * env(len(t), 0.004, 0.3)


def gallo():
    parts = []
    shape = ((0.12, 600, 700), (0.14, 700, 950), (0.5, 950, 880), (0.35, 880, 500))
    for dur, f0, f1 in shape:
        t = t_axis(dur)
        freq = np.linspace(f0, f1, len(t)) * (1 + 0.02 * np.sin(2 * np.pi * 30 * t))
        x = saw(phase_from_freq(freq)) + 0.5 * square(phase_from_freq(freq * 2))
        parts.append(lowpass(x, 2600) * env(len(t), 0.01, 0.02))
    return np.concatenate(parts) * 0.9


def explosion():
    t = t_axis(2.2)
    noise = rng.standard_normal(len(t))
    rumble = lowpass(lowpass(noise, 180), 180) * 6
    crack = (noise - lowpass(noise, 1500)) * np.exp(-t * 9)
    am = np.minimum(1, t / 0.01) * np.exp(-t * 1.6)
    return (rumble * am + crack * 0.8)


def risa():
    parts = []
    for i in range(6):
        t = t_axis(0.13)
        freq = (330 - i * 14) * (1 + 0.15 * np.exp(-t * 30))
        x = saw(phase_from_freq(freq))
        vowel = lowpass(x, 1100) + 0.4 * (x - lowpass(x, 2400))
        parts += [vowel * np.sin(np.pi * t / t[-1]) ** 0.6, silence(0.06)]
    return np.concatenate(parts)


def ovni():
    t = t_axis(2.4)
    freq = 900 + 500 * np.sin(2 * np.pi * 7 * t) * np.linspace(0.2, 1, len(t)) + 300 * np.sin(2 * np.pi * 0.5 * t)
    x = np.sin(2 * np.pi * phase_from_freq(freq)) + 0.25 * np.sin(2 * np.pi * phase_from_freq(freq * 1.5))
    return x * env(len(t), 0.15, 0.4)


SOUNDS = {
    "bocina": bocina,
    "sirena": sirena,
    "alarma": alarma,
    "pato": pato,
    "redoble": redoble,
    "laser": laser,
    "campana": campana,
    "trompeta": trompeta_triste,
    "boing": boing,
    "pedorreta": pedorreta,
    "tada": tada,
    "grillos": grillos,
    "timbre": timbre,
    "silbato": silbato,
    "moneda": moneda,
    "gong": gong,
    "gallo": gallo,
    "explosion": explosion,
    "risa": risa,
    "ovni": ovni,
}


def write_ogg(name, samples):
    samples = samples / (np.max(np.abs(samples)) + 1e-9) * 0.89
    pcm = (samples * 32767).astype("<i2")
    with tempfile.TemporaryDirectory() as tmp:
        wav_path = os.path.join(tmp, name + ".wav")
        with wave.open(wav_path, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes(pcm.tobytes())
        out = os.path.join(OUT_DIR, name + ".ogg")
        subprocess.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", wav_path,
             "-c:a", "libvorbis", "-q:a", "4", out],
            check=True,
        )
    return out


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    wanted = sys.argv[1:] or list(SOUNDS)
    for name in wanted:
        fn = SOUNDS[name]
        path = write_ogg(name, fn())
        print(f"{name:10s} {os.path.getsize(path) // 1024:4d} KB")


if __name__ == "__main__":
    main()
