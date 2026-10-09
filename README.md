# 🔊 Friend Game

Una app Android para mandarle sonidos al celular de tus amigos: una bocina, una sirena, un pato o tus propios audios. Solo te pueden mandar sonidos las personas que tú aceptaste, y siempre puedes detenerlos.

## 📲 Descargar e instalar

**Descarga directa (desde el celular):**
https://github.com/chiquidg1234-hue/Friend-game/releases/latest/download/FriendGame.apk

1. Abre el enlace en tu celular Android (versión 8.0 o más nueva).
2. Cuando termine de bajar, toca el archivo `FriendGame.apk`.
3. Si Android avisa que no permite instalar apps de esa fuente, toca **Configuración** y activa **Permitir de esta fuente**. Vuelve y toca **Instalar**.
4. Abre Friend Game, escribe tu nombre y acepta las notificaciones.

Las versiones nuevas se instalan encima de la anterior sin perder tus amigos ni tus audios.

## 🎮 Cómo se juega

1. **Comparte tu código.** En la pantalla principal tienes un código tipo `ABCD-EFGH-JKMN-PQRS`. El botón **Compartir** lo manda por WhatsApp junto con el enlace de descarga.
2. **Agrega a un amigo.** Toca **➕ Agregar amigo** y pega su código. Le llega una solicitud.
3. **Tu amigo acepta.** Desde ese momento se pueden mandar sonidos entre ustedes.
4. **Manda sonidos.** Elige al amigo y toca un sonido. Si lo mantienes presionado, lo escuchas tú primero.
5. **¿Te pasaste?** Después de mandar un sonido aparece **⏹ Detener mi sonido** durante unos segundos y lo corta en el celular de tu amigo.

### Tus propios audios

En **Mis audios**, toca **⬆️ Subir audio** y elige un archivo:

- Formatos: **MP3, OGG, WAV o M4A** (las notas de voz de WhatsApp también sirven).
- Máximo **8 segundos** y **2 MB**.

Tus audios solo los puedes mandar tú, y solo a amigos que ya te aceptaron.

## 🛡️ Reglas del juego (consentimiento)

Cada quien manda en su propio celular:

- **Nadie te puede mandar sonidos sin que lo aceptes.** Tu app ignora a quien no esté en tu lista de amigos.
- **Siempre puedes detener un sonido**, desde la app o desde el botón **⏹ Detener** de la notificación. Quien lo mandó también puede cortarlo antes, pero no puede impedir que tú lo detengas.
- Un sonido nunca dura más de **10 segundos** en tu celular, aunque el archivo sea más largo.
- **Pausa la recepción** con el interruptor de la pantalla principal o con **Pausar recepción** en la notificación.
- Si mantienes presionado a un amigo puedes **silenciarlo**, **eliminarlo** o **bloquearlo**.
- Si alguien manda más de 4 sonidos en 20 segundos, los demás se ignoran.
- Si estás en una llamada, el sonido no se reproduce: solo te llega el aviso.
- El volumen es el **multimedia** de tu celular. Si lo bajas a cero, no suena.

## 🔋 Para que los sonidos lleguen siempre

La app deja un aviso fijo ("Listo para recibir sonidos") mientras escucha. Algunos celulares cierran las apps en segundo plano para ahorrar batería. Si los sonidos te llegan tarde:

- En la app, entra en **Ajustes → 🔋 Que los sonidos lleguen siempre** y acepta.
- En Xiaomi, Huawei, Samsung u otras marcas, busca también **Ajustes → Batería → Friend Game → Sin restricciones** o activa el **Inicio automático**.

## ⚙️ Cómo funciona por dentro

- App Android nativa en Kotlin, sin librerías externas. Toda la app vive en `app/src/main/java/com/friendgame/app/`.
- Los mensajes viajan por [ntfy.sh](https://ntfy.sh), un servicio de notificaciones gratuito y de código abierto que no pide cuenta. Cada persona escucha en un canal secreto derivado de su código. Por eso solo debes compartir tu código con quien quieras.
- Los audios propios viajan como adjunto temporal en ntfy (se borran del servidor a las pocas horas).
- Se puede usar otro servidor ntfy propio en **Ajustes → 🌐 Servidor**. Tú y tus amigos tienen que usar el mismo.
- Los sonidos integrados se generaron por síntesis con `tools/generate_sounds.py`, sin grabaciones de terceros.

| Archivo | Qué hace |
| --- | --- |
| `MainActivity.kt` | La pantalla: código, amigos, sonidos, audios y ajustes |
| `ListenService.kt` | Servicio que mantiene la conexión y reacciona a los mensajes |
| `Relay.kt` | Envía mensajes y audios al servidor ntfy |
| `Player.kt` | Reproduce un sonido a la vez, con detener y límite de 10 s |
| `Store.kt` | Guarda en el celular tu código, tus amigos y tus audios |
| `CustomAudio.kt` | Revisa formato, tamaño y duración de los audios propios |

## 🛠️ Compilar

Cada `push` a la rama principal compila el APK en GitHub Actions (`.github/workflows/android.yml`) y lo publica en **Releases**. El enlace de descarga de arriba siempre apunta a la última versión.

Para compilar en tu computadora necesitas JDK 17 y el Android SDK:

```bash
./gradlew assembleRelease
# APK en app/build/outputs/apk/release/app-release.apk
```

**Sobre la clave de firma:** `signing/friendgame.jks` está en el repo a propósito. Así cualquier compilación firma igual y las actualizaciones se instalan encima. Para una app entre amigos instalada a mano está bien. Si algún día la publicas en Google Play, crea una clave nueva y guárdala fuera del repo, por ejemplo como secreto de GitHub.
