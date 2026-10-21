# Psicología del juego: qué usa Friend Game y por qué

Este documento junta los principios de psicología que usan los juegos sociales y los anuncios,
cómo están aplicados en Friend Game, y los límites que no cruzamos. Sirve para decidir qué
agregar después sin romper las reglas de Google Play ni la confianza de los usuarios (13+).

## 1. REA (Reverse Engineer Anything)

[REA](https://github.com/morluto/rea) es una herramienta de código abierto (MIT) que conecta un
agente de IA (Claude Code, Codex…) con herramientas de ingeniería inversa por MCP: con JADX lee el
manifiesto, las clases y los métodos de un APK, sin el código fuente. Se instala con
`npx rea-agents setup` (paquete `rea-agents`, Node 22.19+). Ojo: hay copias falsas del repo; solo
vale `github.com/morluto/rea`, `rea.tools` y el paquete `rea-agents`.

**Cómo la usamos (y cómo no):**

- Su propia licencia la limita a investigación legal, interoperabilidad y **software propio o que
  tengas permiso de examinar**. Descompilar apps de otros (TikTok, Duolingo, Snapchat) para copiar
  cómo funcionan rompe sus términos de uso y puede ser ilegal. No lo hacemos.
- Las ideas de psicología no hace falta sacarlas del código de nadie: están publicadas (libros,
  estudios, análisis de producto). Este documento sale de esas fuentes.
- **Uso que sí sirve:** pasarle REA a **nuestro propio APK** antes de subirlo a Play, para revisar
  permisos, componentes exportados, librerías y que no quede nada que no debería (pedir: «Revisa
  `FriendGame-play.aab` con REA y dime qué permisos, receptores y servicios exporta»).

## 2. El modelo de fondo

- **Fogg (B = MAP):** una acción pasa cuando hay **M**otivación, **A**bilidad (es fácil) y un
  **P**rompt (aviso) en el mismo momento. Por eso cada aviso de la app lleva a una acción de un
  toque (Devolvérsela, Abrir el cofre).
- **Hooked (Nir Eyal):** aviso → acción → premio que varía → inversión (lo que construyes te hace
  volver: tu mascota, tus audios, tus rachas).

## 3. «Si pasa esto, la app hace esto»

Cada regla dice el principio de psicología, dónde está en el código y su límite.

| Si pasa esto… | …la app hace esto | Principio | Código | Límite |
| --- | --- | --- | --- | --- |
| Un amigo te hace sonar | Aviso con «↩️ Devolvérsela» y, en Sonar, «Ana te hizo sonar… ¡Devuélvesela!» por 30 min | Reciprocidad + aviso «caliente» (se puede actuar ya) | `Replies`, `Notifs.soundReceived` | Solo amigos aceptados; se quita al responder |
| Un amigo usa la app (llega cualquier mensaje suyo) | Punto verde y «activo ahora» en su chip y en Amigos | Prueba social, presencia | `Presence` | Solo en tu celular; no se manda a nadie cuándo estás tú |
| Los dos se mandan algo el mismo día | 🔥 racha con ese amigo; ⌛ si hoy falta | Aversión a la pérdida, compromiso | `Streaks` | Perderla no quita nada |
| Te faltan pocos días de racha y no abriste la app | Un recordatorio entre 18:00 y 21:00 | Aviso a tiempo (Fogg) | `Reminders` | 1 al día, nunca de noche, se apaga en Ajustes, no insiste si llevas 7 días sin abrir |
| Faltas un día y tienes protector | La racha sigue 🧊 | Reduce la ansiedad por perder | `Points.onDailyOpen` | Se gana o se compra con puntos, nunca con dinero |
| Empieza el día | 3 misiones nuevas; al cumplir las 3, un cofre | Efecto Zeigarnik (lo incompleto se recuerda), gradiente de meta | `Quests` | El cofre es gratis siempre |
| Abres el cofre | Premio que varía (15–50 pts, protector, poder) | Recompensa variable | `Quests.openChest` | Sin dinero: no es una «caja de botín» pagada |
| Te falta ≤ 20 % para subir de nivel | «¡a 12 pts del nivel 4!» en Sonar | Gradiente de meta, progreso dotado | `renderSound` | — |
| Ganas un reto | Confeti y tu mascota salta | Regla pico-final (se recuerda el mejor momento y el final) | `Points.onRetoPlace` | — |
| Llega una fecha especial (Halloween, Navidad) | Tu mascota se disfraza y hay un logro de temporada, con fecha de término real | Escasez honesta, novedad | `Seasons`, `PetView.season` | Se dice cuándo termina; no hay cuentas regresivas falsas |
| Un amigo usa o le da ❤️ a tu sonido | Aviso «Ana usa tu sonido» y +3 pts | Reciprocidad, reconocimiento | `Sonoteca.onFan` | Una vez por persona y sonido |
| Varios amigos comparten el mismo sonido | Sube a «🔥 Tendencias» | Prueba social | `Sonoteca.trending` | Solo entre tus amigos y grupos |
| Tu mascota no come en un día | Se pone triste, llora y te avisa de tarde | Cuidado (efecto Tamagotchi), inversión | `PetView`, `Reminders` | Nunca muere ni se pierde lo ganado |
| Quieres probar un sonido antes de mandarlo | Modo «Escuchar» y «Como le llega a Ana» | Reduce el miedo a equivocarse (más fácil = más acciones) | `listenTo`, `playTrial` | Solo suena en tu celular |

## 4. Lo que usan los anuncios, aplicado a Friend Game

Los anuncios usan los mismos principios. Así se aplican aquí y en la ficha de Google Play (que es
el «anuncio» de la app):

| Principio | En la app | En la ficha de Play |
| --- | --- | --- |
| **Reciprocidad** (si te dan algo, quieres devolver) | Devolvérsela, ❤️ y usos en la Sonoteca | «Hazle sonar el celular a tus amigos… y prepárate para la venganza» |
| **Prueba social** (si otros lo hacen, debe estar bueno) | Activo ahora, Tendencias, ranking | Capturas con nombres y grupos; cuando haya, «+10.000 descargas» y reseñas |
| **Compromiso y coherencia** (si empezaste, sigues) | Rachas, mascota que crece | «Cuida tu mascota y no cortes la racha» |
| **Aversión a la pérdida** (perder pesa el doble que ganar) | ⌛ en rachas, recordatorio de tarde | No se usa en la ficha |
| **Escasez** (lo limitado se valora más) | Temporadas con fecha real | «Evento de Halloween hasta el 2 de noviembre» en «Novedades» |
| **Gustar y parecerse** (compramos a quien nos cae bien) | Mascotas tiernas, tu avatar, temas | El gráfico con las mascotas 3D |
| **Autoridad** | — | Si aparece en prensa o en una lista de Play, decirlo |
| **Curiosidad / novedad** | Cofre sorpresa, sonidos nuevos en la Sonoteca | Primera línea con una promesa clara y un emoji |
| **Efecto IKEA** (lo que haces tú lo valoras más) | Grabar tu voz, personalizar botones, nombrar la mascota | «Graba tu voz y conviértela en sonido» |

## 5. Líneas rojas (lo que NO hacemos)

Google Play revisa esto, el público incluye adolescentes y varias leyes (Reino Unido, Unión
Europea, varios estados de EE. UU.) limitan los diseños «adictivos» para menores:

- Nada se compra con dinero real: ni puntos, ni cofres, ni poderes, ni protectores.
- Cofres con probabilidades ocultas a cambio de dinero: no.
- Cuentas regresivas falsas, «solo por hoy» inventado, ofertas que vuelven cada día: no.
- Avisos de noche (21:00–18:00), varios al día o que insisten si no abres la app: no.
- Hacer sentir culpa («tu mascota va a morir», «tus amigos te abandonaron»): no.
- Feed infinito o reproducción automática: no.
- Publicidad: la app no tiene anuncios. Si algún día se agregan, que sean opcionales
  («mira un video y gana 10 puntos»), nunca de pantalla completa sin poder cerrarlos, y
  declarados en Play («Contiene anuncios»).

## 6. Próximas ideas (en orden de impacto)

1. **Invitar amigos con premio para los dos** (reciprocidad): al aceptarse, ambos ganan un cofre.
2. **Liga semanal** con ascensos (prueba social + competencia), solo entre amigos.
3. **Reacciones rápidas** al sonido que te llegó (😂 💀 🔥) que le llegan al que lo mandó.
4. **Resumen del domingo**: «Esta semana hiciste sonar 23 veces a tus amigos; Ana te ganó por 4».
5. **Accesorios de la mascota** comprados con puntos (inversión, efecto IKEA).

## Fuentes

- REA: [repositorio](https://github.com/morluto/rea/blob/main/README.md), [explicación](https://www.mindstudio.ai/blog/reverse-engineer-anything-repo)
- [Modelo de Fogg](https://ekene.substack.com/p/the-fogg-behaviour-model) y [diseño basado en conducta](https://blog.marvelapp.com/introduction-to-behavior-based-design/)
- [Psicología de las rachas (Smashing Magazine)](https://smashingmagazine.com/2026/02/designing-streak-system-ux-psychology/) y [la racha de Duolingo](https://japm.substack.com/p/the-psychology-behind-duolingos-most)
- [Hook Model (Amplitude)](https://amplitude.com/blog/the-hook-model.md)
- [Principios de persuasión de Cialdini](https://skills.cat/skills/costrict-plugins-repo/github-trending-wondelai-influence-psychology) y [psicología del marketing](https://skillselion.com/skills/borghei/claude-skills/marketing-psychology)
- [Patrones oscuros a evitar](https://simon-frey.com/links/category/ux/dark-patterns)
