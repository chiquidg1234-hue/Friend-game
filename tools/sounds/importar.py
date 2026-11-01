#!/usr/bin/env python3
"""Baja los sonidos de tools/sounds/catalogo.json (BigSoundBank, CC0) y los deja en
app/src/main/assets/sounds/<clave>.ogg, listos para la app.

Para cada uno:
  1. revisa que su página diga «License CC0» (si no, no lo usa);
  2. baja el mp3 (se guarda en ~/.cache/fg-sounds para no bajarlo dos veces);
  3. quita el silencio del principio, lo corta en «max» segundos con un final suave,
     lo deja en mono y con un volumen parecido al de los sonidos de siempre (-11 LUFS);
  4. lo guarda en OGG Vorbis.

Uso:  python3 tools/sounds/importar.py [clave ...]     (sin claves: todos)
Necesita ffmpeg con libvorbis.
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
CACHE = os.path.expanduser("~/.cache/fg-sounds")
UA = {"User-Agent": "Mozilla/5.0 FriendGame-importar"}


def get(url, binary=False):
    for i in range(4):
        try:
            data = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60).read()
            return data if binary else data.decode("utf-8", "replace")
        except Exception:
            time.sleep(2 * (i + 1))
    raise RuntimeError("no se pudo bajar " + url)


def process(item):
    page = get(f"https://bigsoundbank.com/{item['slug']}-s{item['id']}.html")
    text = " ".join(re.sub(r"<[^>]+>", " ", page).split())
    if "License CC0" not in text:
        raise RuntimeError(f"{item['key']}: la página no dice CC0")
    os.makedirs(CACHE, exist_ok=True)
    mp3 = os.path.join(CACHE, item["id"] + ".mp3")
    if not os.path.exists(mp3) or os.path.getsize(mp3) < 2000:
        with open(mp3, "wb") as f:
            f.write(get(f"https://bigsoundbank.com/UPLOAD/mp3/{item['id']}.mp3", binary=True))
    out = os.path.join(OUT, item["key"] + ".ogg")
    top = float(item.get("max", 6))
    fade = min(0.4, top / 6)
    chain = (
        "silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.02,"
        f"atrim=0:{top},asetpts=N/SR/TB,"
        f"afade=t=out:st={top - fade}:d={fade},"
        "loudnorm=I=-11:TP=-1.0:LRA=11,"
        "aresample=44100"
    )
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", mp3, "-ac", "1", "-af", chain,
                    "-c:a", "libvorbis", "-q:a", "3", out], check=True)
    # El final suave quedó en «top» aunque el audio fuera más corto: se corta el silencio del final.
    tmp = out + ".tmp.ogg"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", out, "-af",
                    "areverse,silenceremove=start_periods=1:start_threshold=-55dB:start_silence=0.05,areverse",
                    "-c:a", "libvorbis", "-q:a", "3", tmp], check=True)
    os.replace(tmp, out)
    dur = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", out],
                               capture_output=True, text=True).stdout.strip() or 0)
    return dur, os.path.getsize(out)


def main():
    catalog = json.load(open(os.path.join(HERE, "catalogo.json"), encoding="utf-8"))
    wanted = set(sys.argv[1:])
    total = 0
    for item in catalog["sonidos"]:
        if wanted and item["key"] not in wanted:
            continue
        dur, size = process(item)
        total += size
        print(f"{item['key']:18s} {dur:5.2f} s {size // 1024:4d} KB  ({item['vibe']})")
        time.sleep(0.3)
    print(f"Total: {total // 1024} KB")


if __name__ == "__main__":
    main()
