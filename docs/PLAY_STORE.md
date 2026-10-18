# Publicar Friend Game en Google Play

Esta guía tiene dos partes:

1. **Qué ya está listo en el código** para cumplir las reglas de Google Play.
2. **Paso a paso** para publicarla, desde crear la cuenta hasta que aparezca en la tienda.

Las políticas de Google cambian seguido: antes de enviar la app, revisa las páginas oficiales enlazadas al final.

## 1. Qué ya está listo

| Requisito de Google Play | Estado |
| --- | --- |
| Apuntar a Android 16 (API 36), obligatorio desde el 31 de agosto de 2026 | ✅ `targetSdk = 36` |
| Sin actualización propia ni instalar APKs (`REQUEST_INSTALL_PACKAGES`) | ✅ La versión `play` los quita |
| Sin pagos externos dentro de la app | ✅ La versión `play` no muestra el botón de donar; ofrece la reseña |
| Sin `USE_EXACT_ALARM` (solo para relojes y calendarios) | ✅ La versión `play` usa `SCHEDULE_EXACT_ALARM` y pide permiso desde Ajustes |
| Política de privacidad pública | ✅ `docs/legal/privacidad.html` (falta activar GitHub Pages, ver el paso 2) |
| Términos de uso | ✅ `docs/legal/terminos.html`, también dentro de la app (Tú → Ajustes) |
| Forma de denunciar y bloquear (contenido de usuarios) | ✅ «Denunciar» en amigos, grupos y mensajes del chat |
| Edad mínima | ✅ 13 años, dicho al empezar y en los términos |
| Formato AAB | ✅ GitHub Actions arma `FriendGame-play.aab` en cada compilación |
| Nombre de paquete propio | ✅ `io.github.chiquidg1234.friendgame` (no se puede cambiar después de publicar) |
| Gesto de atrás predictivo de Android 16 | ✅ Ya registrado |

### Las dos versiones de la app

En `app/build.gradle.kts` hay dos *flavors*:

- **`github`**: la de siempre. Se descarga de GitHub, se actualiza sola, se pasa por Quick Share y deja donar con un enlace (`FG_DONATE_URL`).
- **`play`**: la que se sube a Google Play. No trae nada de lo anterior.

Las dos comparten todo lo demás: chat, retos, candados, grupos, comunidades, puntos, ranking, personalización, mascota y poderes.

### Sobre los poderes y el "sonido que no se puede detener"

Google Play exige que la gente controle su celular y prohíbe apps que faciliten el acoso. Un sonido que el otro no puede detener **sin haberlo aceptado** pondría la app en riesgo de rechazo o de que la bajen. Por eso:

- Los **poderes** (Súper sonido, Eco, Doble puntos) cambian cómo se ve y se siente el sonido, pero quien lo recibe siempre puede detenerlo, silenciar o bloquear.
- La única forma de que un sonido no se pueda detener es el **candado**, que los dos aceptan, dura poco, mantiene el máximo de 10 segundos por sonido y deja denunciar siempre.
- Los puntos no se compran con dinero. Si algún día se venden puntos o poderes, tendría que ser con la facturación de Google Play y declararlo en la ficha.

## 2. Paso a paso para publicar

### Paso 1. Crear la cuenta de desarrollador (una sola vez)

1. Entra a [play.google.com/console](https://play.google.com/console) con tu cuenta de Google.
2. Elige **cuenta personal**.
3. Paga los **25 USD** (pago único) y verifica tu identidad con tu documento. Puede tardar unos días.
4. Escribe un **correo de contacto** para los usuarios. Ese correo aparece en la ficha de la app.

### Paso 2. Publicar los términos y la privacidad (gratis)

1. En GitHub, abre el repositorio → **Settings** → **Pages**.
2. En **Source** elige **Deploy from a branch**, la rama `ccr-6689175d-7fdoa9` y la carpeta **/docs**. Guarda.
3. En unos minutos quedan publicadas en:
   - https://chiquidg1234-hue.github.io/Friend-game/legal/privacidad.html
   - https://chiquidg1234-hue.github.io/Friend-game/legal/terminos.html

La app ya enlaza a esas direcciones.

### Paso 3. Correo para denuncias

1. GitHub → **Settings** → **Secrets and variables** → **Actions** → pestaña **Variables** → **New repository variable**.
2. Nombre: `FG_CONTACT_EMAIL`. Valor: el mismo correo de contacto del paso 1.

Así, cuando alguien toque «Denunciar», la app arma un correo con los datos para que lo revises.

### Paso 4. Crear la clave de subida (una sola vez)

Google firma la app final con su propia clave (Play App Signing). Tú solo necesitas una **clave de subida**. En una computadora con Java instalado:

```bash
keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

Te pide una contraseña y unos datos (nombre, país). Después:

```bash
base64 -w0 upload.jks > upload.txt      # en Mac: base64 -i upload.jks -o upload.txt
```

En GitHub → **Settings** → **Secrets and variables** → **Actions** → pestaña **Secrets** → **New repository secret**, crea estos cuatro:

| Nombre | Valor |
| --- | --- |
| `FG_UPLOAD_KEYSTORE_B64` | Todo el contenido de `upload.txt` |
| `FG_UPLOAD_STORE_PASSWORD` | La contraseña que elegiste |
| `FG_UPLOAD_KEY_ALIAS` | `upload` |
| `FG_UPLOAD_KEY_PASSWORD` | La misma contraseña |

Guarda `upload.jks` y la contraseña en un lugar seguro (un gestor de contraseñas). **Nunca la subas al repositorio.** Si la pierdes, se puede pedir a Google que la cambie.

### Paso 5. Conseguir el paquete .aab

1. Haz cualquier cambio en la rama (o en GitHub → **Actions** → **Compilar APK** → **Run workflow**).
2. Cuando termine, abre esa ejecución y baja el artefacto **FriendGame-play-aab**. Adentro está `FriendGame-play.aab`, ya firmado con tu clave de subida.

### Paso 6. Crear la app en Play Console

1. **Crear app** → nombre «Friend Game», idioma español, **App**, **Gratis**.
2. Acepta las declaraciones.

### Paso 7. Completar «Contenido de la app»

En el menú **Política → Contenido de la app**:

- **Política de privacidad**: pega la dirección del paso 2.
- **Acceso a la app**: «Todas las funciones están disponibles sin acceso especial» (no hay cuentas).
- **Anuncios**: «No contiene anuncios».
- **Clasificación de contenido**: completa el cuestionario. Marca que los usuarios **pueden interactuar** y **compartir contenido** (chat y audios).
- **Público objetivo**: **13 años o más** (no marques menores de 13: eso activa la política de Familias).
- **Seguridad de los datos**:
  - Se **recopilan y comparten**: nombre (nombre visible), mensajes (chat), audio (audios propios) y otro contenido (puntos, avatar y mascota). Para la **funcionalidad de la app**.
  - Los datos viajan **cifrados en tránsito**.
  - No hay forma de pedir el borrado en un servidor porque no hay cuentas; se borra con **Cambiar mi código** o desinstalando. Indícalo así.
  - Menciona que pasa por **ntfy.sh**, un servicio de terceros.
- **Servicio en primer plano**: la app usa uno de tipo `specialUse` para recibir sonidos en tiempo real. Explica: «Mantiene la conexión para que los sonidos que mandan los amigos aceptados suenen al instante, aunque la pantalla esté apagada». Graba un video corto mostrando el aviso fijo y un sonido que llega con la pantalla apagada.
- **Alarmas exactas**: explica que los retos suenan a la hora exacta en todos los celulares.

### Paso 8. Ficha de la tienda

En **Crecimiento → Presencia en Play Store → Ficha principal**:

- **Descripción breve** (80 caracteres): «Hazle sonar el celular a tus amigos: bocinas, retos, chat y candados».
- **Descripción completa**: qué hace, que solo suenan los amigos que aceptas, grupos con QR, comunidades, retos, candado, puntos y ranking, sin cuenta y sin anuncios.
- **Ícono** 512×512 PNG y **gráfico de funciones** 1024×500.
- **Capturas** (mínimo 2): la prueba en emulador las publica en https://github.com/chiquidg1234-hue/Friend-game/releases/tag/capturas (Sonar, Chats, Reto, Candado, Tú, Ranking).
- **Categoría**: Entretenimiento. **Correo de contacto**.

### Paso 9. Prueba cerrada: 12 personas, 14 días (obligatorio para cuentas personales nuevas)

1. **Pruebas → Prueba cerrada → Crear pista**.
2. Sube `FriendGame-play.aab`, escribe qué hay de nuevo y guarda.
3. En **Testers**, crea una lista con los correos de Gmail de **al menos 12 personas** con Android.
4. Envía la versión a revisión. Cuando la aprueben, pásales el enlace de suscripción a la prueba.
5. Las 12 personas tienen que **instalarla y mantenerse en la prueba 14 días seguidos**. Pídeles que la usen: Google mira que la prueba sea real.

### Paso 10. Pedir acceso a producción y publicar

1. Pasados los 14 días, en el **Panel** aparece **Solicitar acceso a producción**. Responde las preguntas (qué probaste, qué cambiaste con los comentarios).
2. Cuando lo aprueben: **Producción → Crear versión**, sube el `.aab` más nuevo y envíala a revisión.
3. La revisión suele tardar entre unas horas y 7 días. Después la app aparece en Google Play.

### Para cada actualización

1. Haz los cambios y deja que GitHub compile.
2. Baja el nuevo `FriendGame-play.aab` (el número de versión sube solo).
3. Play Console → **Producción → Crear versión** → súbelo.

## 3. Muy recomendado

### 3.1 Servidor propio o Firebase Cloud Messaging
Hoy todo pasa por ntfy.sh, un servicio gratuito de terceros:

- Tiene límites por dirección IP.
- Puede bloquear el uso intensivo.
- Si se cae, la app deja de funcionar.

Opciones:
- **Servidor ntfy propio** (barato y con pocos cambios): una VPS pequeña y cambiar `Store.DEFAULT_SERVER`.
- **Firebase Cloud Messaging**: Google entrega los mensajes aunque la app esté dormida. Permite quitar el servicio en primer plano y gasta menos batería. Es el cambio más grande, pero el que mejor escala.

### 3.2 Seguridad
- Los mensajes no están cifrados de extremo a extremo (está dicho en la política). Se podría cifrar el chat con claves que los amigos intercambian al aceptarse.
- Los puntos del ranking los informa cada celular: alguien que modifique la app podría inflarlos. Para un ranking público haría falta un servidor.

### 3.3 Calidad
- **Probar en celulares reales** de marcas que cierran apps en segundo plano (Xiaomi, Samsung, Huawei, Oppo) y en Android 8, 12, 14 y 16. En el emulador no se pueden probar la cámara real (escanear el QR) ni el micrófono.
- **Accesibilidad**: probar con TalkBack y con letra grande.
- **Pantallas grandes**: hoy es una columna de ancho completo; en tablets conviene limitarla a unos 600 dp.
- **Idiomas**: los textos están en español dentro del código. Para crecer, moverlos a `res/values/strings.xml` y agregar inglés y portugués.

## 4. Sobre instalar la versión de GitHub

Google empezó a exigir que todas las apps instaladas en celulares Android certificados vengan de desarrolladores verificados, aunque se instalen fuera de Play: desde septiembre de 2026 en Brasil, Indonesia, Singapur y Tailandia, y en todo el mundo desde 2027. Con la cuenta de Play Console verificada puedes registrar también el paquete `com.friendgame.app` de la versión de GitHub para que se siga instalando sin advertencias.

## Fuentes oficiales

- Requisito de API de destino: https://developer.android.com/google/play/requirements/target-sdk
- Política de pagos de Google Play: https://support.google.com/googleplay/android-developer/answer/9858738
- Permiso `REQUEST_INSTALL_PACKAGES`: https://support.google.com/googleplay/android-developer/answer/12085295
- Prueba cerrada para cuentas personales nuevas: https://support.google.com/googleplay/android-developer/answer/14151465
