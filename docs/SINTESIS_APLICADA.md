# Qué sirve de `00_SINTESIS.md` para Friend Game

`00_SINTESIS.md` trata de cómo desarmar juegos móviles (Block Blast, Subway Surfers, Candy Crush) para entender su monetización. Friend Game no tiene anuncios ni compras, así que casi nada de eso se copia. Sí hay cinco cosas que sirven:

| Idea de la síntesis | Cómo la usamos | Estado |
| --- | --- | --- |
| **Exodus Privacy** (rastreadores y permisos por paquete) | Friend Game no trae ningún SDK de anuncios ni de analítica: la única librería es ZXing. Es un argumento de venta: «0 rastreadores». Cuando esté en Play, revisar su ficha en reports.exodus-privacy.eu.org y poner el enlace en `store/ficha.md`. | Dicho en la ficha |
| **DarkPattern.games** (patrones oscuros por juego) | Ya seguimos «Enganche sano» (`docs/PSICOLOGIA.md`): sin compras, sin cuentas regresivas falsas, un recordatorio al día. La síntesis confirma la línea: dificultad según el **progreso** del jugador, nunca según lo que gasta. | Ya cumplido |
| **Dificultad adaptativa** (bandas de Candy Crush) | Los minijuegos (`Games.kt`) pueden subir o bajar la velocidad según tu récord, no según tus puntos. | Idea para después |
| **Configuración remota** (Roblox Configs, la de Subway Surfers) | Sin servidor propio podemos usar un JSON en GitHub Pages, como `docs/legal/retiros.json`: cambiar el servidor de ntfy si se cae o apagar una función con problemas sin publicar otra versión. | Idea para después |
| **Revisar nuestro propio APK** (`android-reverse-engineering-skill`, PCAPdroid, `mobile-mcp`) | Solo sobre nuestra app: ver que el APK no deja claves ni direcciones de más, que el tráfico va solo a ntfy.sh y GitHub (sirve para llenar «Seguridad de los datos» en Play) y probar en un celular real desde Claude Code con `mobile-mcp`. | Revisado a mano (ver `docs/SEGURIDAD.md`) |

## Lo que no hacemos

- **No descompilar apps de otros.** Los términos de King y SYBO lo prohíben, y la síntesis ya lo deja fuera.
- **No instalar skills sin leerlas.** La síntesis avisa que hubo skills con puertas traseras. Pasó lo mismo con Ruflo: lo revisamos antes de dejarlo en el repo (ver `docs/RUFLO.md`).
