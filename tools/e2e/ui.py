#!/usr/bin/env python3
"""Mini automatizador de pantalla vía adb + uiautomator, para la prueba de punta a punta.

  ui.py wait TEXTO [segundos]     espera a que aparezca un texto
  ui.py absent TEXTO [segundos]   espera a que desaparezca un texto
  ui.py tap TEXTO                 toca el elemento con ese texto (hace scroll si hace falta)
  ui.py see TEXTO                 busca un texto en toda la pantalla, haciendo scroll
  ui.py tapexact TEXTO            toca el elemento cuyo texto es exactamente ese (sin distinguir mayúsculas)
  ui.py tapclass CLASE            toca el primer elemento de esa clase (ej. android.widget.Switch)
  ui.py find REGEX                imprime el primer texto que coincide
  ui.py top                       vuelve al inicio de la pantalla
  ui.py dump                      imprime todos los textos visibles
  ui.py playing [segundos]        espera a que la app esté reproduciendo audio
  ui.py silent [segundos]         espera a que la app deje de reproducir audio
  ui.py never [segundos]          comprueba que la app NO reproduce nada durante ese tiempo
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*args, check=True):
    return subprocess.run(["adb", *args], capture_output=True, text=True, check=check).stdout


def screen_size():
    out = adb("shell", "wm", "size")
    w, h = re.findall(r"(\d+)x(\d+)", out)[-1]
    return int(w), int(h)


W, H = screen_size()


def nodes():
    for _ in range(4):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
        raw = adb("shell", "cat", "/sdcard/ui.xml", check=False)
        start = raw.find("<?xml")
        if start >= 0:
            try:
                root = ET.fromstring(raw[start:].strip())
                return list(root.iter("node"))
            except ET.ParseError:
                pass
        time.sleep(1)
    return []


def bounds(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "[0,0][0,0]")))
    return x1, y1, x2, y2


def matches(node, text):
    value = (node.get("text") or "") + " " + (node.get("content-desc") or "")
    return text.lower() in value.lower()


def find(text):
    for node in nodes():
        if matches(node, text):
            return node
    return None


def on_screen(node):
    x1, y1, x2, y2 = bounds(node)
    return x2 > x1 and y2 > y1 and y1 >= 0 and y2 <= H


def swipe_up():
    adb("shell", "input", "swipe", str(W // 2), str(int(H * 0.75)), str(W // 2), str(int(H * 0.35)), "300")
    time.sleep(0.8)


def swipe_down():
    adb("shell", "input", "swipe", str(W // 2), str(int(H * 0.3)), str(W // 2), str(int(H * 0.85)), "250")
    time.sleep(0.6)


def top():
    for _ in range(6):
        swipe_down()


def tap_node(node):
    x1, y1, x2, y2 = bounds(node)
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(1.2)


def locate(predicate):
    """Busca en la pantalla actual; si no está, baja hasta 8 veces y luego vuelve arriba."""
    for attempt in range(9):
        for node in nodes():
            if predicate(node) and on_screen(node):
                return node
        if attempt < 8:
            swipe_up()
    top()
    for node in nodes():
        if predicate(node) and on_screen(node):
            return node
    return None


def app_uid():
    out = adb("shell", "pm", "list", "packages", "-U", "com.friendgame.app")
    m = re.search(r"uid:(\d+)", out)
    return m.group(1) if m else None


def is_playing(uid):
    """Lee el estado de los reproductores de audio de Android (rápido, sin tocar la pantalla)."""
    out = adb("shell", "dumpsys", "audio", check=False)
    for line in out.splitlines():
        if "AudioPlaybackConfiguration" in line and f"u/pid:{uid}/" in line and "state:started" in line:
            return True
    return False


def main():
    cmd = sys.argv[1]
    if cmd == "wait":
        text, timeout = sys.argv[2], float(sys.argv[3]) if len(sys.argv) > 3 else 30
        end = time.time() + timeout
        while time.time() < end:
            if find(text) is not None:
                return 0
            time.sleep(1.5)
        return 1
    if cmd == "absent":
        text, timeout = sys.argv[2], float(sys.argv[3]) if len(sys.argv) > 3 else 15
        end = time.time() + timeout
        while time.time() < end:
            if find(text) is None:
                return 0
            time.sleep(1)
        return 1
    if cmd == "tap":
        node = locate(lambda n: matches(n, sys.argv[2]))
        if node is None:
            return 1
        tap_node(node)
        return 0
    if cmd == "see":
        node = locate(lambda n: matches(n, sys.argv[2]))
        top()
        return 0 if node is not None else 1
    if cmd == "tapexact":
        wanted = sys.argv[2].lower()
        node = locate(lambda n: (n.get("text") or "").strip().lower() == wanted)
        if node is None:
            return 1
        tap_node(node)
        return 0
    if cmd == "tapclass":
        node = locate(lambda n: n.get("class") == sys.argv[2])
        if node is None:
            return 1
        tap_node(node)
        return 0
    if cmd == "find":
        pattern = re.compile(sys.argv[2])
        for node in nodes():
            m = pattern.search(node.get("text") or "")
            if m:
                print(m.group(0))
                return 0
        return 1
    if cmd in ("playing", "silent", "never"):
        uid = app_uid()
        timeout = float(sys.argv[2]) if len(sys.argv) > 2 else 15
        end = time.time() + timeout
        while True:
            playing = is_playing(uid)
            if cmd == "playing" and playing:
                return 0
            if cmd == "silent" and not playing:
                return 0
            if cmd == "never" and playing:
                return 1
            if time.time() >= end:
                return 0 if cmd == "never" else 1
            time.sleep(0.3)
    if cmd == "top":
        top()
        return 0
    if cmd == "dump":
        for node in nodes():
            text = node.get("text") or node.get("content-desc") or ""
            if text:
                print(f"{node.get('class', '').split('.')[-1]:14s} {node.get('bounds')} {text}")
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
