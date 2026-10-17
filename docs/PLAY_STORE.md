# Antes de publicar Friend Game en Google Play

Lista de lo que hay que cambiar o preparar, de lo más importante a lo menos. Las políticas de Google cambian seguido: antes de enviar la app, revisa las páginas oficiales enlazadas al final.

## 1. Obligatorio: si no se hace, Google la rechaza

### Apuntar a Android 16 (API 36)
Desde el 31 de agosto de 2026, las apps nuevas y sus actualizaciones tienen que apuntar a Android 16 (API 36). Hoy la app apunta a Android 15 (API 35).

- Subir `compileSdk` y `targetSdk` a 36 en `app/build.gradle.kts`, y el Android Gradle Plugin a una versión que soporte API 36.
- El gesto de atrás predictivo se activa solo. La app ya registra un `OnBackInvokedCallback` para esto (ver `updateBackCallback()` en `MainActivity.kt`), así que solo falta probarlo.
- En pantallas grandes (tablets, plegables), Android 16 ignora el bloqueo de orientación. El escáner de QR (`ScanActivity`) está fijo en vertical: hay que revisar que la cámara se vea bien en horizontal.

### Quitar la actualización propia y la instalación de APKs
Google Play no deja que una app se actualice sola por fuera de Play. También restringe el permiso `REQUEST_INSTALL_PACKAGES` a apps cuya función principal sea instalar paquetes. Para la versión de Play hay que quitar:

- `Updater.kt`, `InstallReceiver` y los permisos `REQUEST_INSTALL_PACKAGES` y `UPDATE_PACKAGES_WITHOUT_USER_ACTION`. Play actualiza la app por su cuenta.
- **Pasar la app** (`ApkProvider.kt`). En su lugar, se comparte el enlace de Play Store.

La forma ordenada es crear dos *flavors* en Gradle: `github` (como está hoy, con APK, actualización propia y donación por enlace) y `play` (sin todo eso).

### Donaciones: con la facturación de Google Play
Play exige su propio sistema de pagos (Google Play Billing) para pagos dentro de la app. Un botón que lleva a PayPal o Ko-fi ya hizo que bajaran apps de Play (le pasó a WireGuard en 2019). En la versión `play`:

- O se usan **productos de compra única** de Play Billing ("Propina 1 USD", "3 USD", "5 USD"), consumibles y sin desbloquear nada. La pantalla **Apoyar** ya tiene esa forma. Google se queda con una comisión, normalmente 15 %.
- O se quita el botón de donar y se deja solo "Pásasela a tus amigos" y "Déjale una reseña".

La versión de GitHub puede seguir con el enlace externo (variable `FG_DONATE_URL`, ver el README).

### Clave de firma nueva y Play App Signing
`signing/friendgame.jks` está en el repo a propósito para la versión de GitHub, así que para Play no sirve: cualquiera podría firmar una app que parezca tuya.

- Crea una **clave de subida** nueva, guárdala fuera del repo (en un gestor de contraseñas y como secreto de GitHub Actions) y activa **Play App Signing**. Así Google guarda la clave final y, si pierdes la de subida, se puede cambiar.
- Play pide el formato **AAB**: hay que compilar con `./gradlew bundlePlayRelease` en vez de `assembleRelease`.

### Política de privacidad (enlace público)
Es obligatoria porque la app usa micrófono y cámara y manda datos por internet. Tiene que explicar:

- Qué se envía: tu nombre visible, tu código, a quién mandas sonidos y tus audios propios.
- Por dónde pasa: por el servidor ntfy (ntfy.sh por defecto). Los audios quedan ahí unas horas como adjunto temporal.
- Qué no se recolecta: ni cuenta, ni número de teléfono, ni contactos, ni ubicación, ni publicidad.
- Cómo borrar todo: **Cambiar mi código** o desinstalar la app.

Se puede publicar gratis como página de GitHub Pages en este mismo repo.

### Formulario de Seguridad de los datos (Data safety)
En Play Console, declara lo mismo que en la política:

- Datos compartidos: nombre y audios, que mandas a otras personas a través de un servidor.
- Que viajan cifrados (HTTPS).
- Que no hay cuenta que borrar.

Si usas ntfy.sh, es un tercero que procesa esos datos: menciónalo.

### Declarar el servicio en primer plano
La app usa un servicio en primer plano de tipo `specialUse` para escuchar sonidos todo el tiempo. Play pide justificarlo en **Play Console → Contenido de la app → Servicios en primer plano**, con una descripción y un video corto que muestre:

- El aviso fijo.
- Que llega un sonido con la pantalla apagada.

Puede que lo rechacen. La alternativa más segura es el punto 3.1 (Firebase Cloud Messaging).

### Clasificación de contenido y público
- Completa el cuestionario **IARC**. Marca que los usuarios **interactúan entre sí** y **comparten contenido** (audios propios).
- **Público objetivo:** si eliges menores de 13, aplica la política de Familias, mucho más estricta. Lo más simple es declararla para **13+**.
- **Contenido generado por usuarios:** Play pide una forma de **denunciar** y de **bloquear**. Bloquear ya está. Falta agregar **Denunciar** en las opciones de un amigo o grupo, que puede ser un correo, y escribir unas reglas de uso cortas.

### Cuenta de desarrollador
- Pago único de 25 USD y verificación de identidad.
- **Cuentas personales nuevas** (creadas después de noviembre de 2023): antes de publicar hay que hacer una **prueba cerrada con al menos 12 personas durante 14 días seguidos**. Junta a 12 amigos con Android desde ya.
- El `applicationId` (`com.friendgame.app`) no se puede cambiar después de publicar. Si quieres uno más tuyo (por ejemplo `com.chiquidg.friendgame`), cámbialo antes. Para Android sería otra app distinta de la versión de GitHub.

## 2. Ficha de la tienda

- **Ícono** de 512×512 PNG. El de la app es un vector: hay que exportarlo.
- **Gráfico de funciones** de 1024×500.
- **Capturas de teléfono**, al menos 2 (mejor 4 a 8). La prueba en emulador ya las genera: Sonar, Te llegó un sonido, Grupos con QR, Amigos, Apoyar.
- **Descripción corta** (80 caracteres). Ejemplo: «Hazle sonar el celular a tus amigos con bocinas, patos y tus propios audios».
- **Descripción larga** (hasta 4000 caracteres): qué hace, consentimiento ("solo te suenan los amigos que aceptes"), grupos con QR, sin cuenta y sin anuncios.
- **Categoría:** Entretenimiento o Social. Correo de contacto obligatorio.
- **Nombre:** busca en Play que "Friend Game" no esté tomado o sea muy parecido a otra app.

## 3. Muy recomendado antes de lanzar

### 3.1 Servidor propio o Firebase Cloud Messaging
Hoy todo pasa por ntfy.sh, un servicio gratuito de terceros:

- Tiene límites por IP.
- Puede bloquear el uso intensivo.
- Si se cae, la app deja de funcionar.

Opciones:
- **Servidor ntfy propio** (barato, pocos cambios): una VPS pequeña y cambiar `Store.DEFAULT_SERVER`.
- **Firebase Cloud Messaging** (lo más "Play friendly"): Google entrega los mensajes aunque la app esté dormida. Así se puede quitar el servicio en primer plano y el aviso fijo, y gasta menos batería. Necesita un proyecto de Firebase y una función en la nube que reenvíe los mensajes. Es el cambio más grande, pero el que mejor escala.

### 3.2 Seguridad de los canales
Hoy cualquiera que conozca tu código puede escribir en tu canal. La app ignora a quien no aceptaste, pero el canal es público en ntfy.sh. Mejoras:

- Usar un servidor con control de acceso (ntfy con usuarios/tokens o el propio backend).
- Firmar los mensajes de amigos como ya se hace con los del admin de un grupo.
- Límite de tamaño y de envíos también del lado del servidor.

### 3.3 Calidad
- **Reporte de cierres:** Play Console ya muestra los cierres básicos (Android vitals). Para más detalle, Firebase Crashlytics u otro servicio, declarado en Seguridad de los datos.
- **Activar R8** (`isMinifyEnabled = true`) en la versión de Play: la app pesa menos y es más difícil de copiar. Hay que probar que nada se rompa.
- **Probar en celulares reales** de marcas que cierran apps en segundo plano (Xiaomi, Samsung, Huawei, Oppo) y en Android 8, 12, 14 y 16. En el emulador no se pueden probar la cámara real (escanear el QR) ni el micrófono.
- **Accesibilidad:** los botones nuevos ya tienen descripciones para TalkBack y tamaño de toque de al menos 44 dp. Falta probarlo con TalkBack y con letra grande.
- **Pantallas grandes:** revisar cómo se ve en tablet y en horizontal. Hoy es una columna de ancho completo; en pantallas anchas conviene limitarla a unos 600 dp centrados.

### 3.4 Idiomas
Todos los textos están en español y escritos dentro del código. Para Play conviene:

- Moverlos a `res/values/strings.xml`.
- Agregar inglés y portugués (Brasil es un mercado Android enorme).
- Traducir también la ficha de la tienda.

## 4. Ideas para después del lanzamiento

- Reacciones: responder un sonido con otro en un toque desde la notificación.
- Paquetes de sonidos nuevos (por temporada: Halloween, Navidad).
- Modo "no molestar" por horario dentro de la app (por ejemplo, de 22:00 a 8:00 no suena nada).
- Widget en la pantalla de inicio para mandar tu sonido favorito sin abrir la app.
- Atajos (mantener presionado el ícono): "Mandar bocina a Ana".
- Copia de seguridad de amigos y grupos al cambiar de celular.
- Invitar amigos por QR, no solo a grupos: tu código como QR en la pestaña Amigos.

## 5. Sobre instalar la app fuera de Play (la versión de GitHub)

Google está empezando a exigir que **todas** las apps instaladas en celulares Android certificados vengan de desarrolladores verificados, aunque se instalen fuera de Play:

- Empezó en septiembre de 2026 en Brasil, Indonesia, Singapur y Tailandia.
- Se extiende a todo el mundo en 2027.

Cuando llegue a tu país, quien instale el APK de GitHub va a ver más pasos o advertencias si no registras la app. Verificar tu identidad como desarrollador (la misma cuenta de Play sirve) y registrar el nombre del paquete evita ese problema.

## Fuentes oficiales para revisar antes de enviar

- Requisito de API de destino: https://developer.android.com/google/play/requirements/target-sdk
- Política de pagos de Google Play: https://support.google.com/googleplay/android-developer/answer/9858738
- Permiso `REQUEST_INSTALL_PACKAGES`: https://support.google.com/googleplay/android-developer/answer/12085295
- Prueba cerrada para cuentas personales nuevas: https://support.google.com/googleplay/android-developer/answer/14151465
