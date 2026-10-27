# Guía para agentes (Codex, ChatGPT, Claude) que trabajen en Friend Game

Léela entera antes de cambiar código. El estado del proyecto y lo pendiente están en [docs/HANDOFF.md](docs/HANDOFF.md).

## Qué es

App Android para mandarle sonidos al celular de tus amigos, con:

- chat;
- retos (un sonido que suena a la vez en todos los celulares del chat);
- candado (acuerdo entre dos para no poder detener los sonidos del otro por unos minutos);
- grupos y comunidades;
- puntos, niveles, logros, ranking, mascota, poderes y personalización.

Todo el texto de la app está en español (Chile/Latinoamérica, tuteo).

### Reglas del producto (no romperlas)

- **Consentimiento:** solo suenan los amigos que aceptaste y los grupos a los que te uniste. Quien recibe siempre puede detener, silenciar, bloquear, denunciar y pausar.
- **Única excepción: el candado.** Los dos lo aceptan, dura poco (1–120 min) y «Denunciar» siempre funciona.
- **No agregar formas de que un sonido no se pueda detener sin aceptarlo.** Google Play lo trataría como acoso o falta de control del usuario.
- **Límites:**
  - Cada sonido suena como mucho 10 s (`Player.MAX_PLAY_MS`).
  - **Volumen máximo por el altavoz:** los sonidos de amigos suenan con el volumen de alarma al máximo y por el altavoz principal; al terminar se devuelve el volumen. La persona lo acepta al instalar o actualizar (`Store.loudAccepted`, `LOUD_VERSION`); si no, la recepción queda en pausa. Está en los términos. Si cambian esas condiciones, sube `Store.LOUD_VERSION` para volver a preguntar.
  - Máximo 4 sonidos por persona cada 20 s (`ListenService.allowSound`).
  - Si llega un sonido durante una llamada, no suena.
- **Versión `play`:** no puede tener pagos externos, actualización propia, instalación de APKs ni `USE_EXACT_ALARM`. Ver `app/src/play/AndroidManifest.xml`.

## Stack

- Kotlin 2.1.21 y Android Gradle Plugin 8.11.1; Gradle 8.14.3 con el wrapper; JDK 17.
- `minSdk 26`, `targetSdk`/`compileSdk 36`.
- **Sin AndroidX ni Compose.** Las vistas se arman en código; no hay layouts XML.
- Única dependencia: ZXing core, para los QR.
- Fuentes Fredoka y Nunito en `app/src/main/assets/fonts/`.
- Mensajería por **ntfy.sh**: HTTP POST para publicar y una conexión `GET /<topics>/json` para escuchar. Por defecto el servidor es `https://ntfy.sh`.

## Archivos (`app/src/main/java/com/friendgame/app/`)

| Archivo | Qué hace |
| --- | --- |
| `MainActivity.kt` | Todas las pantallas. `render()` borra y vuelve a armar la pantalla actual cada vez que cambia algo en `Store` (pestañas `Tab`, páginas en la pila `pages`: `settings`, `support`, `chat:<clave>`, `ranking:<clave>`, `pets`). La caja para escribir del chat (`composer`) y el buscador de Sonar (`searchBar`) se arman una sola vez, para no perder lo escrito. Encima de todo van capas que no se redibujan: el aviso de sonido (`overlay`), «Subir audio» (`UploadSheet`), los minijuegos (`GamesScreen`), la intro y los avisos (`BullaToast`). |
| `Ui.kt` | `Palette` (colores del diseño 2.0 y sus labios 3D), `Fonts`, piezas de interfaz: `button` (con labio, `Chunky`), `card`, `soundCard` (tarjeta grande de sonido con categoría y ▶), `filterChip`, `ProgressPill`, `label`, `title`… También `TimeBar` y `LiveText`. |
| `Store.kt` | Todo lo guardado (SharedPreferences): amigos, grupos, historial, audios propios, chats (archivo aparte), retos y candados. Avisa los cambios con `notifyChanged()`. |
| `ListenService.kt` | Servicio en primer plano. Mantiene la conexión con ntfy, procesa cada mensaje (`handleMessage`, `handleGroupMessage`) y dispara retos atrasados en cada keepalive. |
| `Relay.kt` | Publica en ntfy. `payload(tipo)` arma el mensaje base, con puntos, avatar y mascota; `signed()` arma los mensajes del admin de un grupo, firmados con ECDSA. |
| `Player.kt` | Un sonido a la vez: tope de 10 s, altavoz principal (`setPreferredDevice`) y volumen de alarma al máximo que después devuelve (`restoreVolume`), repetir (`loop`), empezar desde (`startMs`) y `stopByUser` (respeta el candado y anota el tiempo en los retos). |
| `Chats.kt` | Chat por amigo (`f:código`) o por grupo (`g:id`). |
| `Retos.kt` | Retos: crear, recibir, alarmas (AlarmManager exacta, o aproximada si no hay permiso), sonar, tabla de tiempos y `RetoReceiver`. |
| `Locks.kt` | Candado: proponer, aceptar, rechazar, `blocksStop` y `finishDue`. |
| `Points.kt` | Puntos con tope diario, niveles, logros, racha, saldo para gastar y ranking (con los puntos que traen los mensajes de los demás). |
| `Looks.kt` | Personalización (`Looks`: botones, avatar, temas de chat, **fondo de la app** `BACKGROUNDS` —los que tienen `scene` son animados—, `reactiveBackground` y `reduceMotion`), mascota (`Pets`: 12 especies con Bulla primero, 4 barras de cuidado tipo Pou `Care` —hambre, alegría, energía, limpieza— y frases `says()`) y poderes (`Powers`). |
| `PetView.kt` | Mascota 3D dibujada en Canvas (`PetArt`: Bulla y las 11 especies del lienzo «Friend Game · Mascotas 3D»). Está viva como Duo: respira, parpadea, mira a los lados, hace gestos cada ~6 s. Acciones: `cheer`, `dance`, `surprise`, `laugh`, `scare`, `love`, `troll`, `wave`, `chat`, `celebrate`, `shower`, `eat` y `react(categoría)`. `audioLevel` mueve la boca con lo que suena, y `outfit`, `foam`, `dirt`, `sleepy`, `listening`, `glow` cambian su aspecto. Bulla tiene piezas articuladas (`PetArt.Rig`): orejas, brazos, ojos grandes y antena con resorte. |
| `Wardrobe.kt` | Armario: gorros, lentes y cuello que se compran con puntos y se dibujan sobre cualquier especie. |
| `PetRoom.kt` | La pieza de la mascota tipo Pou (página `pets`): barras arriba, la mascota grande y seis piezas. Cocina: arrastrar comida a la boca. Baño: jabón y ducha. Dormir: apagar la luz. Jugar: pelota y minijuegos. Armario y «Háblale». |
| `Parrot.kt` | «Háblale»: graba mientras mantienes el botón y lo repite más agudo (voz de ardilla), sin guardar nada. |
| `Games.kt` | Minijuegos: Atrapa la comida, Salto a las nubes, Bulla dice y Revienta burbujas, con récord y puntos con tope diario (`Points.onGamePlayed`). |
| `Scene.kt` | `SceneView`: el fondo de la app, dibujado. Los de categoría son animados (sol de risa, disco, tormenta, corazones, espiral, ondas). `react` lo abre en círculo desde el sonido, late con `Player.level()` y `release` vuelve al tuyo. |
| `AudioClassifier.kt`, `AudioAnalysis.kt` | Detección de la categoría de un audio (Kotlin puro, sin dependencias; ver `docs/AUDIO_CATEGORIAS.md`). Además, la envolvente para reaccionar al audio (`Player.level`). |
| `UploadSheet.kt` | «Subir audio»: la forma del audio, Bulla que escucha y adivina la categoría, elegir otra, nombre y la parte que suena. |
| `Intro.kt`, `Toasts.kt` | La intro al abrir desde el ícono (`IntroView`), Bulla que asoma al mandar o escuchar (`MascotPeek`) y los avisos con Bulla (`BullaToast`). |
| `Anim.kt` | Animaciones cortas (rebote al tocar, entrada de pantalla, confeti, emoji que vuela) y `PulseRings`. `Anim.enabled()` es falso con «Quitar animaciones» de Android o «Reducir movimiento» de la app. |
| `Engage.kt` | Rachas con cada amigo (`Streaks`), misiones del día con cofre (`Quests`), un recordatorio diario (`Reminders`), «Devolvérsela» (`Replies`), quién está activo (`Presence`) y temporadas con disfraz para la mascota (`Seasons`). Ver `docs/PSICOLOGIA.md`. |
| `Sonoteca.kt` | Sonidos que comparten tus amigos y grupos: publicar, recibir, ❤️, usar, ocultar y la lista de retiro (`docs/legal/retiros.json`). |
| `Groups.kt`, `Invites.kt`, `GroupKeys.kt` | Grupos: crear, unirse, sacar a alguien (cambia el canal), invitaciones y firmas del admin. |
| `Notifs.kt` | Canales y notificaciones. |
| `Receivers.kt` | Al encender el celular y al actualizar la app; botones Detener y Pausar de las notificaciones; resultado del instalador. |
| `Updater.kt`, `ApkProvider.kt` | Solo en la versión github: se actualiza sola y se pasa por Quick Share. |
| `Legal.kt` | Términos y privacidad (los textos están en `docs/legal/` y entran como assets) y denuncias. |
| `CustomAudio.kt` | Importar audios: hasta 1 min y 2 MB, suenan 10 s desde `startMs`. |
| `SoundCheck.kt`, `Sounds.kt`, `Donations.kt`, `Codes.kt`, `Qr.kt`, `ScanActivity.kt` | Revisión del sonido, los 12 sonidos integrados y sus categorías (`Vibes`: risa/fiesta/susto/amor/troll/voz, con color, labio e ícono), enlace para donar, códigos, QR y escáner. |

## Protocolo (JSON en el cuerpo de cada mensaje de ntfy)

Base: `{"fg":1, "t":TIPO, "from":código, "name":nombre, "pts":..., "wk":..., "wid":"AAAA-S", "lvl":..., "av":emoji, "ac":color, "pet":"especie:etapa"}`.

| Tipo | Canal | Campos | Qué hace |
| --- | --- | --- | --- |
| `req` / `acc` / `bye` | personal | — | Solicitud de amistad, aceptación, adiós o bloqueo |
| `snd` | personal o grupo | `id`, `s` (clave o `custom` con adjunto), `label`, `g?`, `st?` (ms desde donde suena), `pw?` (`super`/`eco`), `v?` (categoría: `troll`/`risa`/`fiesta`/`amor`/`susto`/`voz`; si falta o no se conoce, la de fábrica del sonido) | Hace sonar |
| `stop` | personal o grupo | `id` | Quien lo mandó corta su propio sonido |
| `msg` | personal o grupo | `id`, `text` (≤500), `g?` | Chat |
| `reto` | personal o grupo | `id`, `k` (`sorpresa`/`rapido`), `s`, `in` (segundos, 10–21600), `g?` | Crea un reto |
| `retores` | igual | `id`, `ms` | El tiempo de alguien en «El más rápido» |
| `retono` | igual | `id` | El creador cancela el reto |
| `lockreq` / `lockacc` / `lockno` | personal | `id`, `min` | Candado: propuesta, aceptación, rechazo o cancelación |
| `pts` | personal o grupo | — | Solo actualiza puntos, avatar y mascota |
| `sndpub` | personal o grupo | `id`, `label`, `e`, `st`, `by`, `byn`, `g?` + adjunto | Comparte un sonido en la Sonoteca (`by`: el autor original) |
| `sndfan` | personal | `id`, `k` (`like`/`use`) | Al autor: a alguien le gustó o usa su sonido |
| `gjoin` / `gleave` | grupo | `g` | Entrar o salir de un grupo |
| `groster`, `gend` | grupo, firmados | `body`, `sig` | Lista de miembros, nombre y tipo (`c`, `e`), o grupo cerrado |
| `gmove`, `gkick` | personal, firmados | `body`, `sig` | El grupo cambia de canal, o te sacaron (`full`: lleno) |

Las demoras se calculan con la hora del servidor (`serverNow - event.time`), no con el reloj de cada celular.

## Cómo compilar y probar

```bash
./gradlew assembleGithubDebug        # APK de prueba
./gradlew assembleGithubRelease      # APK que se publica en GitHub
./gradlew bundlePlayRelease          # .aab para Google Play (firmado si hay FG_UPLOAD_* en el entorno)
```

- **Prueba de punta a punta:** `tools/e2e/run.sh`. Corre en GitHub Actions (`.github/workflows/e2e.yml`) con un emulador Android 15. Simula amigos y admins con curl contra ntfy.sh real y maneja la app con `tools/e2e/ui.py` (uiautomator). Cada paso nuevo de la app debería tener su paso ahí.
- **Revisión rápida sin el SDK de Android:** compilar el Kotlin contra un `android.jar` (por ejemplo `android-all` de Robolectric) con un `R`/`BuildConfig` de mentira.
- **Ver cómo se ve, sin emulador:** `tools/render/run.sh` dibuja las mascotas, sus animaciones y las pantallas con Robolectric y gráficos nativos. Deja PNG en `tools/render/out/` y las capturas por fase en `screenshots/`. Úsalo antes de subir cambios visuales.
- **Detección de categorías:** `tools/audio/run.sh` prueba `AudioClassifier` con audios de prueba (necesita espeak-ng y ffmpeg).
- **Diseño:** el lienzo «Friend Game · Diseño 2.0» en Claude Design (https://claude.ai/artifact/HMqBNoRcDAxTRu2SshHdiP; copia en `docs/design/`). Los colores están en `docs/design/tokens.json`, y `node tools/design/check-tokens.mjs` revisa que `Ui.kt` y `Sounds.kt` los usen.

### ⚠️ Antes de hacer push

- Cada push a la rama por defecto (`ccr-6689175d-7fdoa9`) **publica una versión `v1.0.N` en Releases**, y las apps instaladas **se actualizan solas**. Sube solo cambios probados.
- Un push nuevo **cancela la prueba en emulador que esté corriendo**.

## Convenciones

- Textos de la app, comentarios y mensajes de commit en español, cortos y claros.
- **No cambies el texto de una vista varias veces por segundo** (`setText` o tamaños en un loop): TalkBack y uiautomator dejan de poder leer la pantalla. Para cuentas regresivas usa `LiveText`; para barras, `TimeBar`.
- Todo lo que hable con la red va fuera del hilo principal (`runInBackground` en la pantalla, el hilo del servicio o un `Thread`).
- Los cambios de estado de retos y candados van con `Store.updateReto` / `Store.updateLock` (bajo lock), para que no se disparen dos veces.
- Puntos: usa las funciones de `Points` (`onSoundSent`, `onRetoPlace`…), que ya tienen tope diario. Gastar (`Points.spend`) no baja el nivel ni el ranking.
- **Envíos:** un sonido a la vez, con 2,5 s de recarga y como mucho 4 cada 20 s al mismo destino (`MainActivity.sendWait`). No lo quites: evita colas de envíos y sonidos que el otro celular igual descartaría.
- **Animaciones:** cortas y sobre vistas (`Anim`); lo que se mueve todo el rato se dibuja en una vista propia (`PetView`, `SceneView`, `PulseRings`, `TimeBar`, los juegos). Respeta `Anim.enabled()`: la prueba en emulador corre sin animaciones, y es el «prefers-reduced-motion» de Android. Nada se crea dentro de `onDraw`, y lo que está quieto no se redibuja (gama media: Honor X7c).
- **Diseño 2.0 (estilo Duolingo, oscuro):** botones con labio 3D (`button`), tarjetas con labio (`card`), Fredoka y Nunito, colores de `Palette` y de cada categoría (`Vibe.color`/`lip`). Bulla aparece en la intro, las pantallas vacías (`bullaSays`), los avisos, al mandar (`peek`) y al recibir. Los cambios visuales primero en el lienzo y después en el código.
- **Modo escuchar:** en Sonar, «Escuchar» hace que tocar un sonido lo escuches tú (`Player.preview`) y «Como le llega a…» lo hace sonar como a tu amigo (`NowPlaying.trialFor`: pantalla completa, alarma, poder listo) sin mandarlo.
- **Enganche sano:** nada se compra con dinero, perder una racha no quita nada, a lo más un recordatorio al día (18:00–21:00) y se apaga en Ajustes, sin cuentas regresivas falsas. Lo exige Google Play y el público es de 13+.
- **Secretos:**
  - `signing/friendgame.jks` está en el repo a propósito: es solo para la versión github.
  - La clave de subida de Play va en secretos de GitHub (`FG_UPLOAD_KEYSTORE_B64` y compañía). **Nunca en el repo.**
