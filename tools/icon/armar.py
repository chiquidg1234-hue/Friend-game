#!/usr/bin/env python3
"""Arma el ícono de la app desde el render de Bulla troll (tools/render/run.sh Icono → out/bulla-troll.png).

  python3 tools/icon/armar.py tools/render/out/bulla-troll.png /tmp/icono app/src/main/res store/icono-512.png

Deja ic_launcher_background/foreground en cada mipmap-*, store/icono-512.png y una vista con las
máscaras (círculo, squircle, redondeado) en <salida>/final.png. Necesita Pillow.
"""
import sys, math
from PIL import Image, ImageDraw, ImageFilter, ImageChops
SRC, OUT = sys.argv[1], sys.argv[2]
N = 432  # xxxhdpi: 108 dp * 4
bulla = Image.open(SRC).convert("RGBA")

def lerp(a, b, t): return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))
def hexc(h): h = h.lstrip('#'); return tuple(int(h[i:i+2], 16) for i in (0, 2, 4)) + (255,)

def background(top, bottom, ring, size=N):
    img = Image.new("RGBA", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            t = min(1, max(0, (x * 0.35 + y * 0.9) / (size * 1.25)))
            px[x, y] = lerp(top, bottom, t)
    # Brillo arriba a la izquierda.
    glow = Image.new("L", (size, size), 0)
    ImageDraw.Draw(glow).ellipse((-size*0.25, -size*0.35, size*0.75, size*0.55), fill=70)
    glow = glow.filter(ImageFilter.GaussianBlur(size * 0.12))
    img = Image.composite(Image.new("RGBA", (size, size), (255, 255, 255, 255)), img, glow)
    # Ondas de sonido que salen de Bulla.
    rings = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(rings)
    cx, cy = size * 0.5, size * 0.56
    for i, r in enumerate([0.36, 0.46, 0.56, 0.66]):
        rr = r * size
        a = max(0, 70 - i * 15)
        d.ellipse((cx - rr, cy - rr, cx + rr, cy + rr), outline=ring[:3] + (a,), width=int(size * 0.022))
    return Image.alpha_composite(img, rings)

def foreground(scale=0.45, face=(216, 240), size=N):
    # [scale] es para el render de 900 px y un lienzo de 432; se ajusta al tamaño real de ambos.
    k = (900 / bulla.width) * (size / N)
    w = int(bulla.width * scale * k)
    b = bulla.resize((w, w), Image.LANCZOS)
    u = w / 900
    fx, fy = 480 * u, 470 * u  # centro de la cara en la imagen grande
    ox, oy = int(face[0] * size / N - fx), int(face[1] * size / N - fy)
    fg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    # Sombra suave detrás.
    sh = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    sh.paste((20, 6, 60, 120), (ox, oy + int(size * 0.02)), b)
    sh = sh.filter(ImageFilter.GaussianBlur(size * 0.025))
    fg = Image.alpha_composite(fg, sh)
    fg.alpha_composite(b, (ox, oy))
    return fg

def mask(kind, size):
    m = Image.new("L", (size * 4, size * 4), 0)
    d = ImageDraw.Draw(m)
    s = size * 4
    if kind == "circle": d.ellipse((0, 0, s - 1, s - 1), fill=255)
    elif kind == "rounded": d.rounded_rectangle((0, 0, s - 1, s - 1), radius=int(s * 0.22), fill=255)
    else:  # squircle
        pts = []
        for i in range(720):
            t = 2 * math.pi * i / 720
            c, si = math.cos(t), math.sin(t)
            x = math.copysign(abs(c) ** (2 / 5), c); y = math.copysign(abs(si) ** (2 / 5), si)
            pts.append(((x + 1) * s / 2, (y + 1) * s / 2))
        d.polygon(pts, fill=255)
    return m.resize((size, size), Image.LANCZOS)

def preview(bg, fg, kind, size):
    full = Image.alpha_composite(bg, fg)
    # Lo visible es el 72/108 del centro.
    c = N * 18 // 108
    vis = full.crop((c, c, N - c, N - c)).resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(vis, (0, 0), mask(kind, size))
    return out


top, bottom, ring = hexc("#FF6FB5"), hexc("#6A24D9"), hexc("#FFFFFF")
bg = background(top, bottom, ring)
fg = foreground(scale=0.48, face=(216, 244))
import os
RES = sys.argv[3]
for dens, px in {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}.items():
    d = os.path.join(RES, "mipmap-" + dens)
    os.makedirs(d, exist_ok=True)
    bg.resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher_background.png"), optimize=True)
    fg.resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher_foreground.png"), optimize=True)
# Ícono de la tienda: lo visible (72 de 108 dp), cuadrado completo (Play pone las esquinas).
BIG = 1296
full = Image.alpha_composite(background(top, bottom, ring, BIG), foreground(scale=0.48, face=(216, 244), size=BIG))
c = BIG * 18 // 108
full.crop((c, c, BIG - c, BIG - c)).resize((512, 512), Image.LANCZOS).convert("RGB").save(sys.argv[4], optimize=True)
sheet = Image.new("RGBA", (3 * 200 + 20, 300), (245, 245, 250, 255))
x = 10
for kind in ("circle", "squircle", "rounded"):
    sheet.alpha_composite(preview(bg, fg, kind, 180), (x, 10))
    sheet.alpha_composite(preview(bg, fg, kind, 48), (x + 66, 210))
    x += 200
sheet.save(os.path.join(OUT, "final.png"))
