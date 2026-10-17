# 🔊 Friend Game

Una app Android para mandarle sonidos al celular de tus amigos: una bocina, una sirena, un pato o tus propios audios. Solo te pueden mandar sonidos las personas que tú aceptaste, y siempre puedes detenerlos.

## 📲 Instalar

**Abre esta página en Chrome, desde tu celular:**
https://github.com/chiquidg1234-hue/Friend-game/releases/latest

1. Si la abriste desde WhatsApp, un código QR u otra app y arriba ves una **✕**, estás en una ventanita dentro de esa app. Toca **⋮** (arriba a la derecha) → **Abrir en Chrome**. En esa ventanita el archivo se descarga, pero el instalador nunca aparece.
2. Toca **⬇️ Descargar FriendGame.apk**.
3. Toca **Abrir** y luego **Instalar**. La primera vez Android pide permiso: **Configuración** → activa **Permitir de esta fuente** → vuelve atrás.

Después de la primera vez ya no hace falta el navegador:

- **Para pasársela a un amigo:** en la app, **Amigos → Pasar la app** la manda por Quick Share o Bluetooth y se instala directo.
- **Para actualizar:** la app busca versiones nuevas, las descarga sola y muestra **⬆️ Actualizar ahora**. Tus amigos y audios se conservan.

Funciona en Android 8 o más nuevo. Si algo falla al instalar, lee [docs/INSTALACION.md](docs/INSTALACION.md).

## 🎮 Cómo se juega

La app tiene cuatro pestañas abajo: **Sonar**, **Grupos**, **Amigos** y **Ajustes**. La primera vez te pregunta tu nombre.

1. **Comparte tu código.** En **Amigos** está tu código tipo `ABCD-EFGH-JKMN-PQRS`. El botón **Compartir** lo manda por WhatsApp junto con el enlace para instalar.
2. **Agrega a un amigo.** En **Amigos**, toca **Agregar amigo** y pega su código. Le llega una solicitud (la pestaña Amigos le muestra un numerito).
3. **Tu amigo acepta.** Desde ese momento se pueden mandar sonidos entre ustedes.
4. **Manda sonidos.** En **Sonar**, elige a quién en **PARA** y toca un sonido. Si lo mantienes presionado, lo escuchas tú primero.
5. **¿Te pasaste?** Después de mandar un sonido aparece abajo una barra con **Detener** durante unos segundos y lo corta en el celular de tu amigo.

Cuando a ti te llega un sonido y tienes la app abierta, ocupa toda la pantalla con un botón grande de **Detener** y otro para **silenciar** a quien lo mandó.

### Grupos

Un sonido mandado a un grupo le llega a todos a la vez.

1. En **Grupos**, toca **Crear grupo** y ponle nombre. Aparece un **código QR**.
2. Tus amigos lo escanean desde su app con **Escanear QR**. También puedes mandarles el enlace con **Compartir enlace**, y ellos lo pegan en **Pegar enlace**. Si alguien no tiene la app y escanea el QR con la cámara, llega a la página para instalarla.
3. Toca **Mandar sonido** en la tarjeta del grupo (o elígelo en **PARA**, en la pestaña Sonar) y toca un sonido.

El botón **⋯** de cada grupo tiene sus opciones: **Invitar con QR**, **Ver miembros**, **Silenciar** y **Salir**.

Quien crea el grupo lo administra:

- **Sacar a alguien:** en **Ver miembros**, toca a la persona. El grupo pasa a un canal nuevo que solo reciben los que se quedan, así que quien sale ya no puede mandar ni escuchar nada del grupo. El QR anterior deja de servir; para invitar a más gente, muestra el nuevo.
- **Cambiar el nombre** o **cerrar el grupo** para todos.

Estos cambios van firmados con una clave que solo tiene el celular de quien administra, así que nadie más puede hacerlos.

### Tus propios audios

En **Sonar → Tus audios** puedes:

- **Grabar** un audio con el micrófono (se detiene solo a los 8 segundos).
- **Subir** un archivo: MP3, OGG, WAV o M4A (las notas de voz de WhatsApp también sirven), máximo **8 segundos** y **2 MB**.

Mantén presionado uno de tus audios para escucharlo, cambiarle el nombre o borrarlo.

Tus audios solo los puedes mandar tú, a amigos que te aceptaron o a tus grupos.

### Historial

Al final de la pestaña **Sonar**, en **Te llegaron hace poco**, ves quién te mandó cada sonido y hace cuánto.

## 🛡️ Reglas del juego (consentimiento)

Cada quien manda en su propio celular:

- **Nadie te puede mandar sonidos sin que lo aceptes.** Tu app ignora a quien no esté en tu lista de amigos.
- **Siempre puedes detener un sonido**, desde la app o desde el botón **⏹ Detener** de la notificación. Quien lo mandó también puede cortarlo antes, pero no puede impedir que tú lo detengas.
- Un sonido nunca dura más de **10 segundos** en tu celular, aunque el archivo sea más largo.
- **Pausa la recepción** con el interruptor **Recibir sonidos** en Ajustes o con **Pausar recepción** en la notificación.
- Con el botón **⋯** de un amigo puedes **silenciarlo**, **eliminarlo** o **bloquearlo**. A quien bloqueas tampoco le suenan sus sonidos dentro de un grupo.
- Si alguien manda más de 4 sonidos en 20 segundos, los demás se ignoran.
- Si estás en una llamada, el sonido no se reproduce: solo te llega el aviso.
- **Sonar aunque esté en silencio** (en Ajustes) viene activado: los sonidos usan el volumen de **alarma**, que suena aunque el celular esté en silencio o en vibración. Si lo desactivas, usan el volumen multimedia. Lo decide cada quien en su celular.
- Al llegar un sonido, el celular también vibra.
- Si la alarma está en 0 y el multimedia no, el sonido usa el multimedia para no perderse.
- **Probar y revisar el sonido** (en Ajustes) hace sonar una sirena como si te la mandaran y te dice qué puede estar impidiendo que se oiga: volumen en 0, «No molestar», auriculares conectados, ahorro de batería o falta de conexión. En el historial también queda anotado si un sonido no sonó y por qué.
- De un grupo puedes salirte o silenciarlo cuando quieras.

## 🔋 Para que suene con la pantalla apagada

La app deja un aviso fijo ("Listo para recibir sonidos") mientras escucha, y suena aunque la pantalla esté apagada o el celular en reposo. Algunos celulares cierran las apps en segundo plano para ahorrar batería. Si es tu caso:

- La pestaña Sonar muestra el aviso **Para que suene con la pantalla apagada**. Toca **Permitir** (también está en **Ajustes → Pantalla apagada**).
- En Xiaomi, Huawei, Samsung u otras marcas, busca también **Ajustes → Batería → Friend Game → Sin restricciones** o activa el **Inicio automático**.

## 💜 Donaciones

En **Ajustes → Apoya Friend Game** hay una pantalla para donar (1, 3 o 5 USD, u otro monto). Las donaciones son voluntarias y no desbloquean nada.

Para activarla, pon tu enlace de pago en GitHub: **Settings → Secrets and variables → Actions → Variables → New repository variable**, nombre `FG_DONATE_URL`. La siguiente compilación ya lo trae.

- Con monto: `https://paypal.me/TU_USUARIO/{amount}USD` (la app cambia `{amount}` por lo que elijas).
- Sin monto (Ko-fi, Mercado Pago, Buy Me a Coffee…): pega el enlace tal cual y la app solo muestra **Donar**.

Mientras no haya enlace, el botón dice que las donaciones se activan pronto. Para la versión de Google Play el enlace externo no está permitido; ver [docs/PLAY_STORE.md](docs/PLAY_STORE.md).

## ⚙️ Cómo funciona por dentro

- App Android nativa en Kotlin, sin AndroidX. Solo usa [ZXing](https://github.com/zxing/zxing) para los códigos QR y las fuentes Fredoka y Nunito (licencia OFL, en `app/src/main/assets/fonts/`). Toda la app vive en `app/src/main/java/com/friendgame/app/`.
- El diseño de las pantallas está en el lienzo «Friend Game · Rediseño»: fondo morado, rosado para lo principal, amarillo para tu código y grupos, botones grandes.
- Los mensajes viajan por [ntfy.sh](https://ntfy.sh), un servicio de notificaciones gratuito y de código abierto que no pide cuenta. Cada persona escucha en un canal secreto derivado de su código. Por eso solo debes compartir tu código con quien quieras.
- Los audios propios viajan como adjunto temporal en ntfy (se borran del servidor a las pocas horas).
- Se puede usar otro servidor ntfy propio en **Ajustes → 🌐 Servidor**. Tú y tus amigos tienen que usar el mismo.
- Los sonidos integrados se generaron por síntesis con `tools/generate_sounds.py`, sin grabaciones de terceros.

| Archivo | Qué hace |
| --- | --- |
| `MainActivity.kt` | Las pantallas: bienvenida, pestañas Sonar/Grupos/Amigos/Ajustes, «te llegó un sonido» y Apoyar |
| `Ui.kt` | Colores, fuentes y piezas de la interfaz (botones, tarjetas, avatares) |
| `Donations.kt` | Enlace de donación configurable |
| `ListenService.kt` | Servicio que mantiene la conexión y reacciona a los mensajes |
| `Relay.kt` | Envía mensajes y audios al servidor ntfy |
| `Player.kt` | Reproduce un sonido a la vez, con detener y límite de 10 s |
| `Store.kt` | Guarda en el celular tu código, tus amigos y tus audios |
| `CustomAudio.kt` | Revisa formato, tamaño y duración de los audios propios |
| `Updater.kt` | Busca la versión nueva en GitHub, la descarga y la instala |
| `ApkProvider.kt` | Entrega el propio APK para pasarlo por Quick Share |
| `Groups.kt`, `Invites.kt`, `GroupKeys.kt` | Grupos: crear, unirse, sacar a alguien, invitaciones y firmas del admin |
| `Qr.kt`, `ScanActivity.kt` | Dibujar el QR de invitación y leerlo con la cámara |

## 🛠️ Compilar

Cada `push` a la rama principal compila el APK en GitHub Actions (`.github/workflows/android.yml`) y lo publica en **Releases** con la etiqueta `v1.0.N`. N es el número de versión de la app, y así la app sabe cuándo hay una nueva. La página de instalación de arriba siempre apunta a la última versión.

Cada `push` también corre una prueba de punta a punta (`.github/workflows/e2e.yml`, script en `tools/e2e/`). La prueba instala la app en un emulador Android 15 y la usa tocando la pantalla. Simula por ntfy.sh a amigos que mandan solicitudes, sonidos y señales de detener, y a miembros y admins de grupos (con firmas reales). También comprueba que suena con la pantalla apagada y en reposo, que se puede grabar un audio, que se puede pasar la app y que se actualiza sola.

Para compilar en tu computadora necesitas JDK 17 y el Android SDK:

```bash
./gradlew assembleRelease
# APK en app/build/outputs/apk/release/app-release.apk
```

**Antes de publicar en Google Play:** lee [docs/PLAY_STORE.md](docs/PLAY_STORE.md).

**Sobre la clave de firma:** `signing/friendgame.jks` está en el repo a propósito. Así cualquier compilación firma igual y las actualizaciones se instalan encima. Para una app entre amigos instalada a mano está bien. Si algún día la publicas en Google Play, crea una clave nueva y guárdala fuera del repo, por ejemplo como secreto de GitHub.
