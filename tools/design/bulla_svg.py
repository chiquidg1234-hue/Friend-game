#!/usr/bin/env python3
"""Exporta a Bulla (la mascota de Friend Game) como SVG, en sus estados principales.

Usa las mismas formas y colores que PetArt.bulla() en app/.../PetView.kt (espacio de 200×200),
para el lienzo de diseño y la ficha de la tienda:

  python3 tools/design/bulla_svg.py   →   docs/design/bulla-*.svg
"""
import os

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "docs", "design")

BODY = ("#A8ECFF", "#3DBBF5", "#1A72C2")
BELLY = ("#FFFFFF", "#EFFBFF", "#C4EBFF")
FOOT = ("#FFE08A", "#FFB534", "#E07B00")
BALL = ("#FFF6B0", "#FFD43B", "#F08C00")
INK = "#0E2A4A"
BODY_PATH = ("M100 46 C 138 46, 156 78, 157 114 C 158 152, 136 177, 100 177 "
             "C 64 177, 42 152, 43 114 C 44 78, 62 46, 100 46 Z")


class Svg:
    def __init__(self):
        self.defs = []
        self.items = []
        self.n = 0

    def grad(self, x0, y0, x1, y1, tone):
        """Igual que PetArt.gradient: luz al 35 %/28 % de la forma y sombra en el borde."""
        self.n += 1
        w, h = x1 - x0, y1 - y0
        gid = f"g{self.n}"
        self.defs.append(
            f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{x0 + w * .35:.1f}" '
            f'cy="{y0 + h * .28:.1f}" r="{max(w, h) * .78:.1f}">'
            f'<stop offset="0" stop-color="{tone[0]}"/><stop offset=".5" stop-color="{tone[1]}"/>'
            f'<stop offset="1" stop-color="{tone[2]}"/></radialGradient>')
        return f"url(#{gid})"

    def add(self, s):
        self.items.append(s)

    def ellipse(self, cx, cy, rx, ry, fill, rot=0, opacity=1, extra=""):
        tr = f' transform="rotate({rot} {cx} {cy})"' if rot else ""
        op = f' opacity="{opacity}"' if opacity != 1 else ""
        self.add(f'<ellipse cx="{cx}" cy="{cy}" rx="{rx}" ry="{ry}" fill="{fill}"{tr}{op}{extra}/>')

    def toned(self, cx, cy, rx, ry, tone, rot=0):
        # Los límites de una elipse girada, como Path.computeBounds en Android (aproximado).
        import math
        a = math.radians(rot)
        bx = math.sqrt((rx * math.cos(a)) ** 2 + (ry * math.sin(a)) ** 2)
        by = math.sqrt((rx * math.sin(a)) ** 2 + (ry * math.cos(a)) ** 2)
        self.ellipse(cx, cy, rx, ry, self.grad(cx - bx, cy - by, cx + bx, cy + by, tone), rot)

    def text(self, x, y, s, size, color, rot=0):
        tr = f' transform="rotate({rot} {x} {y})"' if rot else ""
        self.add(f'<text x="{x}" y="{y}" font-family="Fredoka, Nunito, sans-serif" font-weight="700" '
                 f'font-size="{size}" fill="{color}" text-anchor="middle"{tr}>{s}</text>')

    def render(self, title):
        return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200" width="400" height="400" '
                f'role="img" aria-label="{title}">\n<title>{title}</title>\n<defs>{"".join(self.defs)}</defs>\n'
                + "\n".join(self.items) + "\n</svg>\n")


HEART = "M0 -4 C -6 -12, -18 -6, -12 4 L0 16 L12 4 C 18 -6, 6 -12, 0 -4 Z"


def bulla(state):
    s = Svg()
    s.defs.append('<radialGradient id="sh" cx=".5" cy=".5" r=".5"><stop offset="0" stop-color="#000" stop-opacity=".42"/>'
                  '<stop offset="1" stop-color="#000" stop-opacity="0"/></radialGradient>')
    s.add('<ellipse cx="100" cy="183" rx="50" ry="9" fill="url(#sh)"/>')
    tilt = {"baile": 10}.get(state, 0)
    lift = {"feliz": -14, "sorpresa": -10}.get(state, 0)
    ears = {"sorpresa": 24, "feliz": 14, "baile": 8, "risa": 8}.get(state, 0)
    arm_l, arm_r = {"feliz": (150, 150), "baile": (150, 70), "sorpresa": (120, 120), "risa": (-24, -24)}.get(state, (0, 0))
    s.add(f'<g transform="translate(0 {lift}) rotate({tilt} 100 178) translate(100 182) scale(0.88) translate(-100 -182)">')
    # Antena (detrás del cuerpo)
    s.add(f'<path d="M100 50 Q100 33 100 16" stroke="{BODY[2]}" stroke-width="3.8" stroke-linecap="round" fill="none"/>')
    s.add('<circle cx="100" cy="16" r="8" fill="%s"/>' % s.grad(92, 8, 108, 24, BALL))
    s.add('<circle cx="97.5" cy="13.5" r="2.6" fill="#fff" opacity=".85"/>')
    # Orejas
    s.add(f'<g transform="rotate({ears} 67 66)">')
    s.toned(55, 42, 15, 28, BODY, -28)
    s.ellipse(56, 45, 8, 18, "#FF9EC7", -28, .9)
    s.add('</g>')
    s.add(f'<g transform="rotate({-ears} 133 66)">')
    s.toned(145, 42, 15, 28, BODY, 28)
    s.ellipse(144, 45, 8, 18, "#FF9EC7", 28, .9)
    s.add('</g>')
    # Patas, cuerpo, panza y brillo
    s.toned(82, 175, 15, 7.5, FOOT)
    s.toned(118, 175, 15, 7.5, FOOT)
    s.add(f'<path d="{BODY_PATH}" fill="{s.grad(43, 46, 157, 177, BODY)}"/>')
    s.toned(100, 150, 33, 24, BELLY)
    s.ellipse(76, 64, 17, 8, "#fff", -28, .45)
    # Brazos
    s.add(f'<g transform="rotate({arm_l} 56 112)">')
    s.toned(49, 128, 10, 19, BODY, 22)
    s.ellipse(49, 128, 10, 19, "none", 22, 1, f' stroke="{BODY[2]}" stroke-opacity=".5" stroke-width="1.6"')
    s.add('</g>')
    s.add(f'<g transform="rotate({-arm_r} 144 112)">')
    s.toned(151, 128, 10, 19, BODY, -22)
    s.ellipse(151, 128, 10, 19, "none", -22, 1, f' stroke="{BODY[2]}" stroke-opacity=".5" stroke-width="1.6"')
    s.add('</g>')
    # Ojos
    for x in (80, 120):
        if state in ("feliz", "baile", "risa"):
            s.add(f'<path d="M{x - 10} 96 Q{x} 82 {x + 10} 96" stroke="{INK}" stroke-width="4.6" stroke-linecap="round" fill="none"/>')
        else:
            grow, pr = (1.12, 5.4) if state == "sorpresa" else (1, 7.5)
            s.add(f'<ellipse cx="{x}" cy="92" rx="{14 * grow:.1f}" ry="{16 * grow:.1f}" fill="#FBFDFF" stroke="{BODY[2]}" stroke-opacity=".35" stroke-width="1.4"/>')
            s.add(f'<circle cx="{x}" cy="92" r="{pr}" fill="#0E1E3A"/>')
            s.add(f'<circle cx="{x - pr * .35:.1f}" cy="{92 - pr * .4:.1f}" r="{pr * .38:.1f}" fill="#fff"/>')
            s.add(f'<circle cx="{x + pr * .38:.1f}" cy="{92 + pr * .36:.1f}" r="{pr * .16:.1f}" fill="#fff"/>')
        top = 92 - 21 - (4 if state == "sorpresa" else 0)
        if state != "risa":
            lift_b = -7 if state == "sorpresa" else -4
            s.add(f'<path d="M{x - 10} {top + 1} Q{x} {top + lift_b} {x + 10} {top + 1}" stroke="{INK}" stroke-width="3.4" stroke-linecap="round" fill="none"/>')
    # Mejillas
    s.ellipse(61, 114, 8.5, 5, "#FF7FA5", 0, .55)
    s.ellipse(139, 114, 8.5, 5, "#FF7FA5", 0, .55)
    # Boca
    if state == "idle":
        s.add(f'<path d="M88 116 q12 11 24 0" stroke="{INK}" stroke-width="3.2" stroke-linecap="round" fill="none"/>')
    elif state == "sorpresa":
        s.add('<ellipse cx="100" cy="125" rx="7.5" ry="9" fill="#5A1A2A"/>')
    elif state == "risa":
        s.add('<path d="M84 113 Q100 145 116 113 Z" fill="#5A1A2A"/><ellipse cx="100" cy="127" rx="7.5" ry="3.5" fill="#FF7FA5"/>')
    else:
        s.add('<path d="M87.5 114 Q100 131.5 112.5 114 Z" fill="#5A1A2A"/><ellipse cx="100" cy="122" rx="5.6" ry="2" fill="#FF7FA5"/>')
    s.add('</g>')
    # Efectos
    if state == "feliz":
        for x, y, k in ((38, 66, .8), (162, 56, .7), (152, 120, .55)):
            s.add(f'<path d="{HEART}" fill="#FF5FAE" transform="translate({x} {y}) scale({k})"/>')
    if state == "baile":
        s.text(30, 70, "♪", 26, "#FFC93C", -10)
        s.text(172, 52, "♫", 24, "#FF7AB8", 10)
        s.text(166, 112, "♪", 20, "#48DBFB", 8)
    if state == "sorpresa":
        s.text(160, 52, "!", 46, "#FFC93C", 12)
    if state == "risa":
        s.text(34, 62, "JA", 22, "#FFC93C", -14)
        s.text(166, 50, "JA", 26, "#FF7AB8", 14)
    return s.render(f"Bulla · {state}")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for state in ("idle", "feliz", "baile", "sorpresa", "risa"):
        with open(os.path.join(OUT, f"bulla-{state}.svg"), "w", encoding="utf-8") as f:
            f.write(bulla(state))
    print("SVG en", os.path.abspath(OUT))
