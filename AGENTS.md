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
| `MainActivity.kt` | Todas las pantallas. `render()` borra y vuelve a armar la pantalla actual cada vez que cambia algo en `Store` (pestañas `Tab`, páginas en la pila `pages`: `settings`, `support`, `chat:<clave>`, `ranking:<clave>`). La caja para escribir del chat (`composer`) se arma una sola vez, para no perder lo escrito. |
| `Ui.kt` | `Palette` (colores), `Fonts`, piezas de interfaz (`button`, `card`, `label`, `title`, `soundTile`…), `TimeBar` y `LiveText`. |
| `Store.kt` | Todo lo guardado (SharedPreferences): amigos, grupos, historial, audios propios, chats (archivo aparte), retos y candados. Avisa los cambios con `notifyChanged()`. |
| `ListenService.kt` | Servicio en primer plano. Mantiene la conexión con ntfy, procesa cada mensaje (`handleMessage`, `handleGroupMessage`) y dispara retos atrasados en cada keepalive. |
| `Relay.kt` | Publica en ntfy. `payload(tipo)` arma el mensaje base, con puntos, avatar y mascota; `signed()` arma los mensajes del admin de un grupo, firmados con ECDSA. |
| `Player.kt` | Un sonido a la vez: tope de 10 s, volumen de alarma o multimedia, repetir (`loop`), empezar desde (`startMs`) y `stopByUser` (respeta el candado y anota el tiempo en los retos). |
| `Chats.kt` | Chat por amigo (`f:código`) o por grupo (`g:id`). |
| `Retos.kt` | Retos: crear, recibir, alarmas (AlarmManager exacta, o aproximada si no hay permiso), sonar, tabla de tiempos y `RetoReceiver`. |
| `Locks.kt` | Candado: proponer, aceptar, rechazar, `blocksStop` y `finishDue`. |
| `Points.kt` | Puntos con tope diario, niveles, logros, racha, saldo para gastar y ranking (con los puntos que traen los mensajes de los demás). |
| `Looks.kt` | Personalización (`Looks`: botones, avatar, temas de chat), mascota (`Pets`) y poderes (`Powers`). |
| `Groups.kt`, `Invites.kt`, `GroupKeys.kt` | Grupos: crear, unirse, sacar a alguien (cambia el canal), invitaciones y firmas del admin. |
| `Notifs.kt` | Canales y notificaciones. |
| `Receivers.kt` | Al encender el celular y al actualizar la app; botones Detener y Pausar de las notificaciones; resultado del instalador. |
| `Updater.kt`, `ApkProvider.kt` | Solo en la versión github: se actualiza sola y se pasa por Quick Share. |
| `Legal.kt` | Términos y privacidad (los textos están en `docs/legal/` y entran como assets) y denuncias. |
| `CustomAudio.kt` | Importar audios: hasta 1 min y 2 MB, suenan 10 s desde `startMs`. |
| `SoundCheck.kt`, `Sounds.kt`, `Donations.kt`, `Codes.kt`, `Qr.kt`, `ScanActivity.kt` | Revisión del sonido, los 12 sonidos integrados, enlace para donar, códigos, QR y escáner. |

## Protocolo (JSON en el cuerpo de cada mensaje de ntfy)

Base: `{"fg":1, "t":TIPO, "from":código, "name":nombre, "pts":..., "wk":..., "wid":"AAAA-S", "lvl":..., "av":emoji, "ac":color, "pet":"especie:etapa"}`.

| Tipo | Canal | Campos | Qué hace |
| --- | --- | --- | --- |
| `req` / `acc` / `bye` | personal | — | Solicitud de amistad, aceptación, adiós o bloqueo |
| `snd` | personal o grupo | `id`, `s` (clave o `custom` con adjunto), `label`, `g?`, `st?` (ms desde donde suena), `pw?` (`super`/`eco`) | Hace sonar |
| `stop` | personal o grupo | `id` | Quien lo mandó corta su propio sonido |
| `msg` | personal o grupo | `id`, `text` (≤500), `g?` | Chat |
| `reto` | personal o grupo | `id`, `k` (`sorpresa`/`rapido`), `s`, `in` (segundos, 10–21600), `g?` | Crea un reto |
| `retores` | igual | `id`, `ms` | El tiempo de alguien en «El más rápido» |
| `retono` | igual | `id` | El creador cancela el reto |
| `lockreq` / `lockacc` / `lockno` | personal | `id`, `min` | Candado: propuesta, aceptación, rechazo o cancelación |
| `pts` | personal o grupo | — | Solo actualiza puntos, avatar y mascota |
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

### ⚠️ Antes de hacer push

- Cada push a la rama por defecto (`ccr-6689175d-7fdoa9`) **publica una versión `v1.0.N` en Releases**, y las apps instaladas **se actualizan solas**. Sube solo cambios probados.
- Un push nuevo **cancela la prueba en emulador que esté corriendo**.

## Convenciones

- Textos de la app, comentarios y mensajes de commit en español, cortos y claros.
- **No cambies el texto de una vista varias veces por segundo** (`setText` o tamaños en un loop): TalkBack y uiautomator dejan de poder leer la pantalla. Para cuentas regresivas usa `LiveText`; para barras, `TimeBar`.
- Todo lo que hable con la red va fuera del hilo principal (`runInBackground` en la pantalla, el hilo del servicio o un `Thread`).
- Los cambios de estado de retos y candados van con `Store.updateReto` / `Store.updateLock` (bajo lock), para que no se disparen dos veces.
- Puntos: usa las funciones de `Points` (`onSoundSent`, `onRetoPlace`…), que ya tienen tope diario. Gastar (`Points.spend`) no baja el nivel ni el ranking.
- **Secretos:**
  - `signing/friendgame.jks` está en el repo a propósito: es solo para la versión github.
  - La clave de subida de Play va en secretos de GitHub (`FG_UPLOAD_KEYSTORE_B64` y compañía). **Nunca en el repo.**
