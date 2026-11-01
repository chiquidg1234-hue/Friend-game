#!/usr/bin/env python3
"""Arma docs/legal/creditos.html (créditos de los sonidos) desde tools/sounds/catalogo.json."""
import html
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
catalog = json.load(open(os.path.join(HERE, "catalogo.json"), encoding="utf-8"))
names = {"risa": "😂 Risa", "fiesta": "🎉 Fiesta", "susto": "😱 Susto", "amor": "💖 Amor", "troll": "😈 Troll", "voz": "🗣️ Voz"}
rows = []
for vibe, title in names.items():
    items = [s for s in catalog["sonidos"] if s["vibe"] == vibe]
    if not items:
        continue
    rows.append(f"<h2>{title}</h2>\n<ul>")
    for s in items:
        url = f"https://bigsoundbank.com/{s['slug']}-s{s['id']}.html"
        rows.append(f'<li>{s["emoji"]} <b>{html.escape(s["label"])}</b> — <a href="{url}">BigSoundBank n.º {s["id"]}</a></li>')
    rows.append("</ul>")
page = f"""<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Friend Game · Créditos de los sonidos</title>
<style>
  body {{ margin: 0; background: #150C2C; color: #F4F0FF; font: 16px/1.6 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }}
  main {{ max-width: 720px; margin: 0 auto; padding: 32px 20px 64px; }}
  h1 {{ font-size: 30px; margin: 0 0 4px; }}
  h2 {{ font-size: 20px; margin: 28px 0 8px; color: #FFC93C; }}
  a {{ color: #FF7AB8; }}
  li {{ margin: 4px 0; }}
</style>
</head>
<body>
<main>
<h1>Créditos de los sonidos</h1>
<p>Los 12 sonidos de siempre (Bocina, Sirena, Alarma, Pato, Redoble, Láser, Campana, Fracaso, Boing, Pedorreta, ¡Ta-dá! y Grillos) son originales de Friend Game.</p>
<p>Los otros {len(catalog["sonidos"])} son grabaciones reales de <a href="https://bigsoundbank.com">BigSoundBank</a>, la sonoteca de <b>Joseph Sardin</b>, publicadas con la licencia <a href="https://creativecommons.org/publicdomain/zero/1.0/deed.es">CC0 1.0 (dominio público)</a>: se pueden usar libremente, incluso en apps, sin pedir permiso. Igual le damos las gracias aquí. En la app se recortaron (como mucho 9 segundos) y se igualó su volumen.</p>
{chr(10).join(rows)}
</main>
</body>
</html>
"""
open(os.path.join(ROOT, "docs", "legal", "creditos.html"), "w", encoding="utf-8").write(page)
print("docs/legal/creditos.html:", len(catalog["sonidos"]), "sonidos")
