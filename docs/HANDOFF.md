# Informe para seguir con Friend Game (ChatGPT, Codex u otra IA)

Fecha: 27 de octubre de 2026. Rama principal: `ccr-6689175d-7fdoa9`. Repositorio: https://github.com/chiquidg1234-hue/Friend-game

Primero lee [AGENTS.md](../AGENTS.md): explica la arquitectura, el protocolo, cómo compilar y las reglas que no se pueden romper.

## 1. Estado actual

La app compila en GitHub Actions y se publica sola en Releases (`v1.0.N`). Tiene dos versiones:

- **github**: APK en Releases, se actualiza sola.
- **play**: `.aab` para Google Play, sin actualización propia ni pagos externos.

Apunta a Android 16 (API 36).

### Funciones terminadas

- **Sonidos:**
  - 12 sonidos integrados, más audios propios (grabados o descargados, hasta 1 min y 2 MB).
  - En un audio largo se elige desde dónde suena.
  - Todo sonido dura como mucho 10 s.
  - Suenan con la pantalla apagada y en silencio, **al volumen máximo y por el altavoz principal** (antes, en algunos celulares salían por el auricular de las llamadas si otra app lo dejaba en modo llamada). Al terminar, el volumen de alarma vuelve a como estaba. Se pide aceptarlo al instalar y al actualizar; quien no acepta no recibe sonidos.
  - **Recarga al mandar:** un sonido a la vez, 2,5 s entre uno y otro (barra «Recargando») y máximo 4 cada 20 s al mismo destino.
- **Amigos:** con código, solicitud y aceptación. Se puede silenciar, bloquear o denunciar.
- **Grupos** (todos suenan) y **comunidades** (hasta 40, chat, ranking y retos; los sonidos sueltos no suenan):
  - Invitación con QR o enlace.
  - El admin firma los cambios y puede sacar a alguien (el grupo pasa a un canal nuevo).
- **Chat** por amigo y por grupo, con notificaciones y no leídos.
- **Retos:**
  - **Sonido sorpresa:** suena en todos a la vez a la hora elegida.
  - **El más rápido:** gana quien detiene primero, con tabla de tiempos y puntos.
  - El tiempo se elige con opciones rápidas (30 s a 2 h), «Otro tiempo» (horas y minutos, hasta 6 h) o «A una hora» exacta.
  - Cada celular pone su propia alarma.
- **Candado** entre dos amigos (5–60 min, ajustable): ninguno puede detener, silenciar, pausar, eliminar ni bloquear al otro. Al cumplirlo, los dos ganan puntos.
- **Puntos, niveles y ranking:**
  - Puntos con tope diario; 10 niveles; 12 logros; racha de días.
  - Ranking semanal y total con amigos, y por grupo.
- **Personalización:**
  - Emoji, color y nombre de cada botón de sonido.
  - Tu avatar (lo ven tus amigos).
  - Temas para cada chat.
  - Colores y temas que se desbloquean por nivel.
- **Bulla, la mascota de la app** (diseño 2.0): un parlante celeste con orejas, brazos, ojos grandes y antena con resorte. Es la especie de partida y aparece en la intro, en las pantallas vacías, en los avisos, al mandar (asoma por abajo) y al recibir. Está viva como Duo: respira, parpadea, mira a los lados y hace un gesto cada ~6 s. Tiene 12 acciones (feliz, baila, sorprendida, se ríe, se asusta, se enamora, traviesa, saluda, habla, festeja, se ducha, come) y **mueve la boca con lo que suena** (`Player.level`). Los SVG de sus estados están en `docs/design/bulla-*.svg`.
- **Mascota 3D:** 12 especies (Bulla, gato, perro, zorro, rana, conejo, pollito, oso, panda, pulpo, dragón, unicornio; varias se desbloquean por nivel), dibujadas en Canvas con el diseño del lienzo «Friend Game · Mascotas 3D» (https://claude.ai/artifact/VrU56dRP4yBcrdif3oRGd1). Crece con tus puntos (huevo → bebé con cáscara → joven → adulta → legendaria con corona), se pone triste con hambre y duerme de 22:00 a 7:00. Todas hacen las mismas acciones que Bulla.
  - **Pieza de la mascota (tipo Pou, `PetRoom`):** `Tú → Tu mascota → Cuidar y jugar`. Cuatro barras que bajan solas (hambre, alegría, energía, limpieza), la mascota en grande y seis piezas: **Cocina** (arrastrar la comida a la boca; cuesta puntos), **Baño** (frotar el jabón hace espuma, y la ducha), **Dormir** (apagar la luz), **Jugar** (pelota y minijuegos), **Armario** y **Háblale** (mantener el botón: graba y lo repite con voz de ardilla, sin guardar nada; o frases con respuesta). Abajo, la colección de especies y «mandar un saludo a un amigo» (es un mensaje de chat, no un sonido).
  - **Armario (`Wardrobe`):** 12 prendas (gorros, lentes y cuello) que se compran con puntos y se ven sobre cualquier especie.
  - **Minijuegos (`Games`):** Atrapa la comida, Salto a las nubes, Bulla dice (memoria con sonidos) y Revienta burbujas. Guardan el récord, suben la alegría y dan puntos con tope (6 partidas al día).
- **Fondo de la app:** en `Tú` se elige entre 11 fondos. Seis son **animados**, uno por categoría (sol de risa, disco, tormenta, corazones, espiral, ondas), y cinco son degradados (noche violeta, mar, bosque, atardecer, lava). Se dibujan en `SceneView`, a 30 cuadros por segundo y sin crear objetos en cada cuadro.
  - **El fondo reacciona:** al escuchar, mandar o recibir un sonido, el fondo de su categoría se abre en círculo desde donde tocaste, late con el audio y vuelve al tuyo al terminar. Se apaga en Ajustes → «El fondo reacciona a los sonidos».
- **Categorías de los sonidos (`Vibes`):** 😂 Risa, 🎉 Fiesta, 😱 Susto, 💖 Amor, 😈 Troll y 🗣️ Voz, cada una con su color, labio 3D e ícono. Los integrados traen una de fábrica; cualquiera se cambia en las opciones del sonido («🏷️ Categoría»). Viaja en el `snd` como `v`. Al recibir, el aviso muestra la categoría, su fondo, y **la mascota reacciona** (se ríe, baila, tiembla, ojos de corazón…). Su **personalidad** (en la pieza de la mascota) es la categoría que más mandas. No cambia cómo suena ni el volumen.
  - **Detección al subir un audio** (`AudioClassifier`, ver [AUDIO_CATEGORIAS.md](AUDIO_CATEGORIAS.md)): la pantalla «Subir audio» muestra la forma del audio y Bulla lo escucha y adivina la categoría con su seguridad. Con menos de 45 % pregunta. La persona siempre puede elegir otra. Es Kotlin puro, sin modelo ni dependencias.
- **Mercado** (Tú → Mercado): la **Sonoteca** (compartir tus audios con amigos y grupos, ❤️, usarlos con el nombre del autor, pasarlos, ocultar y denunciar; lista de retiro por derechos de autor) y la tienda (poderes y protector de racha).
- **Enganche sano:** misiones del día con cofre sorpresa, racha con cada amigo (🔥), protector de racha y un recordatorio diario (18:00–21:00, se apaga en Ajustes → Avisos).
- **Escuchar antes de mandar:** en Sonar, el selector «🔊 Mandar / 🎧 Escuchar»; con «Como le llega a Ana» suena tal como a tu amigo (pantalla, alarma, poder), solo en tu celular. También en el menú de cada botón.
- **Psicología del juego** (`docs/PSICOLOGIA.md`): «Devolvérsela» en Sonar y en el aviso, punto verde de amigo activo, temporadas con disfraz (Halloween 20 oct – 2 nov, Navidad 18 dic – 1 ene) y logro de temporada, festejo al ganar un reto y «te falta poco» para subir de nivel.
- **Animaciones:** intro al abrir desde el ícono (Bulla cae y rebota, las letras caen, emojis y ondas; se salta tocando), rebote al tocar, entrada de pantallas, confeti en logros y cofres, emoji que vuela al mandar, anillos al recibir, avisos con Bulla (`BullaToast`). Se apagan con «Quitar animaciones» de Android o con Ajustes → «Reducir movimiento».
- **Poderes**, que se compran con puntos: Súper sonido (pantalla dorada), Eco (se repite hasta 10 s) y Doble puntos (en el próximo reto).
- **Legal:**
  - Términos de uso y política de privacidad en `docs/legal/` (también dentro de la app).
  - «Denunciar» bloquea al instante y arma un correo si está `FG_CONTACT_EMAIL`.
  - Aviso de 13+ al empezar.
- **Diseño 2.0 (estilo Duolingo, oscuro):** hecho primero en el lienzo «Friend Game · Diseño 2.0» de Claude Design (https://claude.ai/artifact/HMqBNoRcDAxTRu2SshHdiP; copia en `docs/design/`) con su sistema de diseño (colores en `docs/design/tokens.json`, Fredoka y Nunito, espacios de 4 en 4, botones y tarjetas con labio 3D). 5 pestañas (Sonar, Chats, Grupos, Amigos, Tú).
  - **Sonar:** tarjetas grandes en dos columnas con el color, el ícono y la categoría de cada sonido, ▶ para escucharlo, buscador y filtros por categoría.
  - **Amigos:** tu código en una tarjeta con Copiar y Compartir, las solicitudes arriba y «Pasar la app» con Bulla bailando.

### Pruebas

La prueba automática (`tools/e2e/run.sh`) corre en cada push en un emulador Android 15. Comprueba con amigos y admins simulados por ntfy.sh real:

- **Sonidos:** solicitudes, recibir y detener sonidos, el límite de 10 s, pantalla apagada y Doze, que un desconocido no suena.
- **Grupos:** con QR, sacar a alguien, firmas falsas, comunidades.
- **Lo nuevo:** chat, retos (recibir, sonar, detener, mandar el tiempo, crear, cancelar), candado (aceptar, no se puede detener, se cumple), puntos y ranking, botón personalizado, mascota y poder Eco.
- **Otros:** donación, pausa, términos, Quick Share, que se actualiza sola, sin cierres inesperados.

**Último resultado (rama `ccr-26efbb5d-ww5fqz`, 27 de octubre de 2026): 268 comprobaciones bien, 0 fallos**, con el diseño 2.0. Incluye la recarga al mandar, las misiones del día, la racha con un amigo, la Sonoteca, escuchar antes de mandar, el armario y los minijuegos.

El paso «9n» prueba lo del diseño 2.0: filtros y buscador de Sonar, el armario, los minijuegos y los ajustes de animación.

Además, `tools/render/run.sh` dibuja las mascotas y todas las pantallas sin emulador (ver `tools/render/README.md`); las capturas por fase están en [`screenshots/`](../screenshots/README.md). `tools/audio/run.sh` prueba la detección de categorías y `node tools/design/check-tokens.mjs` que los colores del código sean los del diseño.

No se puede probar en el emulador: la cámara real (escanear el QR) ni el micrófono real. Ese paso avisa sin contar como fallo.

El resultado de cada ejecución está en GitHub → **Actions → Probar en emulador**, y las capturas en https://github.com/chiquidg1234-hue/Friend-game/releases/tag/capturas

## 2. Pendiente, en orden de prioridad

1. **Probar en celulares reales**, con al menos dos personas. Revisar:
   - que la intro, los fondos animados y la pieza de la mascota vayan fluidos en gama media (Honor X7c);
   - **la detección de categorías con audios reales** (de WhatsApp, TikTok, grabados). Si se equivoca mucho, cambiar a YAMNet como dice `docs/AUDIO_CATEGORIAS.md`;
   - «Háblale» (el micrófono no se puede probar en el emulador);
   - retos con la app cerrada;
   - candado entre dos teléfonos;
   - notificaciones de chat;
   - marcas que cierran apps en segundo plano (Xiaomi, Samsung, Huawei, Oppo);
   - Android 8, 12, 14 y 16.
2. **Publicar en Google Play:** seguir [PLAY_STORE.md](PLAY_STORE.md). Los textos y las imágenes de la ficha ya están en `store/`. Lo que hace el dueño:
   - crear la cuenta de desarrollador (25 USD);
   - activar GitHub Pages;
   - poner `FG_CONTACT_EMAIL`;
   - crear la clave de subida;
   - hacer la prueba cerrada con 12 personas durante 14 días.
3. **Capturas para la tienda:** elegir 6–8 de la prueba en emulador (ver `store/ficha.md`).
4. **Seguridad:**
   - El chat no está cifrado de extremo a extremo. Se podría intercambiar una clave al aceptarse como amigos (ECDH) y cifrar con AES-GCM.
   - Los mensajes de amigos no van firmados.
   - El ranking lo informa cada celular, así que se puede inflar.
5. **Servidor:** ntfy.sh es gratis pero tiene límites y puede caerse. Opciones: un ntfy propio, o Firebase Cloud Messaging (permite quitar el servicio en primer plano).
6. **Idiomas:** los textos están en el código. Moverlos a `res/values/strings.xml` y traducir al inglés y al portugués.
7. **Pruebas unitarias** de la lógica (`Points`, `Retos`, `Locks`), que hoy solo se prueba de punta a punta.
8. **Ideas para después:**
   - reacciones a mensajes, stickers;
   - más minijuegos y prendas para el armario;
   - un modo claro (hoy la app es solo oscura);
   - widget en la pantalla de inicio;
   - notas de voz en el chat.

## 3. Cómo pasarle el proyecto a Codex o ChatGPT

### Opción A: Codex en la nube (recomendada)

1. Entra a **chatgpt.com/codex** con tu cuenta de ChatGPT (necesitas un plan que incluya Codex).
2. **Conecta GitHub** y dale acceso al repositorio `chiquidg1234-hue/Friend-game`.
3. Crea un **entorno** para ese repositorio. Codex lee `AGENTS.md` solo. No hace falta instalar el SDK de Android: GitHub Actions compila y prueba cada rama que Codex suba.
4. Escribe la tarea en español y elige la rama `ccr-6689175d-7fdoa9`. Por ejemplo:
   - «Lee AGENTS.md y docs/HANDOFF.md. Agrega reacciones con emoji a los mensajes del chat, con su paso en tools/e2e/run.sh.»
   - «Mueve todos los textos de la app a res/values/strings.xml y agrega la traducción al inglés.»
   - «Revisa el último resultado de la prueba en emulador en GitHub Actions y arregla lo que falló.»
5. Codex abre un **Pull Request**. Antes de aceptarlo (*merge*), mira en la pestaña **Checks** que **Compilar APK** y **Probar en emulador** estén en verde. Al hacer merge a la rama principal se publica una versión nueva y las apps instaladas se actualizan solas.

### Opción B: Codex en tu computadora

```bash
npm install -g @openai/codex
git clone https://github.com/chiquidg1234-hue/Friend-game.git
cd Friend-game
codex
```

Luego pídele lo mismo que en la opción A.

### Opción C: ChatGPT normal

Comparte el enlace del repositorio (es público) o adjunta `AGENTS.md`, `docs/HANDOFF.md` y los archivos que quieras cambiar. Pídele que te devuelva los archivos completos o un parche. Este camino sirve para preguntas y cambios chicos. Para cambios grandes, Codex es mejor porque compila y abre el Pull Request solo.
