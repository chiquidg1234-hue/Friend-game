# Informe final · 1–2 de noviembre de 2026

Rama: `claude/elegant-carson-a074oh` (aún no está en la rama principal, así que **no se publicó ninguna versión** ni se actualizó ninguna app instalada).

## Resumen

| Pedido | Estado |
| --- | --- |
| Leer `00_SINTESIS.md` y ver qué sirve | ✅ `docs/SINTESIS_APLICADA.md` |
| Instalar Ruflo | ✅ Instalado y probado aparte; no quedó dentro del repo por seguridad (`docs/RUFLO.md`) |
| Grabar bromas: pantalla + cámara tipo BeReal, con volteo | ✅ «Graba tu broma» en Sonar |
| Verificar la seguridad | ✅ Se encontró y arregló un problema grave (`docs/SEGURIDAD.md`) |
| Pulir la UI | ✅ Sonar por categoría, tablets, aviso de cifrado, detalles de accesibilidad |
| Lista para Play Store | ✅ Lo que depende del código; quedan los pasos de la cuenta (abajo) |
| Muchos audios ya hechos en todas las categorías | ✅ 64 grabaciones reales con licencia CC0; ahora son 76 sonidos |
| Prueba en emulador | ✅ **305 comprobaciones bien, 0 fallos, 0 avisos** (Android 15, GitHub Actions) |

## 1. Lo que sirve de `00_SINTESIS.md`

El documento trata de cómo desarmar juegos móviles para entender cómo cobran. Friend Game no tiene anuncios ni compras, así que casi nada aplica. Sí sirven cinco cosas:

- **0 rastreadores:** es un argumento de venta, y ya está en la ficha.
- **La línea ética:** la dificultad va según el progreso del jugador, nunca según lo que gasta.
- **Dificultad adaptativa** en los minijuegos (queda como idea).
- **Configuración remota** con un JSON en GitHub Pages (queda como idea).
- **Revisar nuestro propio APK y su tráfico.**

Lo que no hacemos: descompilar apps de otros.

## 2. Ruflo

- Se instaló `ruflo@3.69.0` y se leyó su script de instalación, que es inofensivo.
- Su `init` se probó en una copia vacía. No quedó en el repo porque:
  - activa 3 plugins de terceros que no fueron revisados y pueden correr comandos en tu computador;
  - usa `ruflo@latest` sin fijar la versión;
  - trae un `CLAUDE.md` que contradice `AGENTS.md`.
- Su escáner de seguridad no lee Kotlin: revisó 9 archivos y no encontró nada.
- **En corto:** para esta app no aportó. La revisión real se hizo a mano.
- Cómo usarlo de forma segura, si lo quieres: `docs/RUFLO.md`.

## 3. Graba tu broma 🎬

En **Sonar → Graba tu broma**:

- **Qué se graba:** la pantalla, con el permiso de Android cada vez, y el micrófono (opcional).
- **La burbuja:** tu cámara va en una burbuja redonda dentro de la app, así sale en el video. Se **arrastra**, se **toca para cambiar el tamaño** (tres tamaños) y el botón **🔄 voltea entre la frontal y la trasera sin cortar la grabación**.
- **Mientras graba:** arriba hay una píldora **REC** con el tiempo (hasta 5 min) y **Detener**. También se detiene desde la notificación o desde el aviso de Android.
- **Al terminar:** el video queda en **Galería → Películas → Friend Game** y se puede **compartir**, **borrar** o **grabar otra**.
- **Cuidados:** antes de empezar, la app pide grabar a otras personas solo si están de acuerdo. No se dan puntos por grabar. Está en los términos y en la política de privacidad.
- **Cómo está hecho:** servicio en primer plano de tipo `mediaProjection|microphone` (`Grabacion.kt`) y la interfaz en `GrabacionUi.kt`.

## 4. Seguridad

**El problema grave:** cualquiera que supiera tu código podía leer tus mensajes en ntfy y hacerse pasar por un amigo.

**El arreglo:** cifrado de punta a punta entre amigos (`Cifrado.kt`), con ECDH P-256, HKDF-SHA256 y AES-256-GCM.

- El servidor solo ve un sobre cifrado.
- Un mensaje sin cifrar a nombre de un amigo que ya cifra se ignora.
- Un sobre repetido no se vuelve a abrir.
- Funciona con amigos que todavía tienen la versión vieja: con ellos se sigue escribiendo como antes.
- Arriba de cada chat dice si va cifrado.

**Otros arreglos:**
- La copia de seguridad ya no incluye los videos.
- La versión de Play ya no pide el permiso de batería que Google restringe.
- El «adiós» al eliminar un amigo se cifra antes de borrar su clave.

**Lo que sigue igual** (y lo dice la política de privacidad):
- los grupos no van cifrados de punta a punta;
- los audios adjuntos tampoco;
- el ranking se puede inflar.

## 5. Sonidos nuevos 🔊

- **64 grabaciones reales de BigSoundBank** (Joseph Sardin), todas con licencia **CC0 (dominio público)**: se pueden usar en una app de Play sin pedir permiso. Por categoría:
  - Risa: 10
  - Fiesta: 8
  - Susto: 12
  - Amor: 10
  - Troll: 14
  - Voz: 10
- **Lo que se dejó fuera a propósito:**
  - alarmas que imitan alertas reales (incendio, alerta nacional);
  - llantos de bebé;
  - melodías con derechos de autor;
  - el «grito Wilhelm», que es de Warner.
- **Cómo se procesaron** (`tools/sounds/importar.py`):
  - se revisa que la página de cada sonido diga CC0;
  - se recorta el silencio y se limita la duración a 9 s;
  - se iguala el volumen al de los sonidos de siempre.
- **Créditos:** en Ajustes → «Créditos de los sonidos».
- **Compatibilidad:** un sonido nuevo viaja con un sonido original de su categoría como respaldo, así a un amigo con la versión vieja igual le suena algo.
- **Sonar:** se ve por categoría (4 de cada una y «Ver los N ›»).
- **Peso:** la app pasa de 2,2 MB a 5,2 MB.

## 6. UI y Play Store

- **Tablets, plegables y Chromebooks:** el contenido va en una columna centrada. El lector de QR gira la imagen si Android 16 ignora el bloqueo vertical.
- **Accesibilidad:** botón de voltear de 48 dp, y la burbuja cambia de tamaño también con TalkBack.
- **Documentos al día:**
  - términos;
  - privacidad;
  - ficha de la tienda (`store/ficha.md`, con «Graba tu broma», los 76 sonidos y «0 rastreadores»);
  - `docs/PLAY_STORE.md`, con las declaraciones nuevas de servicios en primer plano y la seguridad de los datos.

## 7. Cómo se probó

- **Prueba en emulador (Android 15, GitHub Actions):** 305 comprobaciones bien, 0 fallos. Los pasos nuevos:
  - **9o, grabar:** graba, guarda en la galería y borra.
  - **9p, cifrado:** con una amiga simulada.
  - **9q, sonidos nuevos.**
- **Compatibilidad del cifrado:** Kotlin y Python se probaron uno contra el otro. Cada uno abre lo que sella el otro, y los dos rechazan sobres alterados o de otro remitente.
- **Compilación:** las versiones `github` y `play` compilan aquí y en GitHub.
- **Capturas:** con Robolectric, en `screenshots/fase-4/` (grabar la broma) y en las demás fases.

## 8. Lo que no se pudo probar (hazlo en un celular real)

- **La imagen de la cámara en la burbuja.** El emulador no tiene cámara. Revisa que se vea derecha con la frontal y con la trasera, y que voltear funcione.
- **Guardar el video** en Android 8 y 9 (piden permiso de almacenamiento) y en marcas como Xiaomi o Samsung.
- **El cifrado entre dos celulares reales** con la versión nueva. En la prueba, el otro lado es un amigo simulado.

## 9. Lo que tienes que hacer tú

1. **Probar la rama en tu celular.** El APK está en GitHub → Actions → «Compilar APK» → artefacto `FriendGame-apk` de esta rama.
2. **Pasarla a la rama principal** cuando te guste: un Pull Request y *merge*. Eso publica la versión `v1.0.N`, y las apps instaladas se actualizan solas.
3. **Google Play** (ver `docs/PLAY_STORE.md`):
   - cuenta de desarrollador (25 USD);
   - activar GitHub Pages;
   - `FG_CONTACT_EMAIL`;
   - clave de subida;
   - prueba cerrada con 12 personas durante 14 días.
   - En **Contenido de la app** declara los servicios `mediaProjection` y `microphone`, con un video corto de «Graba tu broma».
