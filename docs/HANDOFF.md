# Informe para seguir con Friend Game (ChatGPT, Codex u otra IA)

Fecha: 21 de octubre de 2026. Rama principal: `ccr-6689175d-7fdoa9`. Repositorio: https://github.com/chiquidg1234-hue/Friend-game

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
  - Suenan con la pantalla apagada y en silencio (volumen de alarma, si la persona lo elige).
  - **Recarga al mandar:** un sonido a la vez, 2,5 s entre uno y otro (barra «Recargando») y máximo 4 cada 20 s al mismo destino.
- **Amigos:** con código, solicitud y aceptación. Se puede silenciar, bloquear o denunciar.
- **Grupos** (todos suenan) y **comunidades** (hasta 40, chat, ranking y retos; los sonidos sueltos no suenan):
  - Invitación con QR o enlace.
  - El admin firma los cambios y puede sacar a alguien (el grupo pasa a un canal nuevo).
- **Chat** por amigo y por grupo, con notificaciones y no leídos.
- **Retos:**
  - **Sonido sorpresa:** suena en todos a la vez a la hora elegida.
  - **El más rápido:** gana quien detiene primero, con tabla de tiempos y puntos.
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
- **Mascota 3D:** 8 especies (4 se desbloquean por nivel), dibujadas en Canvas con el diseño del lienzo «Friend Game · Mascotas 3D» (https://claude.ai/artifact/VrU56dRP4yBcrdif3oRGd1). Crece con tus puntos (huevo → bebé con cáscara → joven → adulta → legendaria con corona). Respira, parpadea, mueve la cola, come su comida favorita, salta y suelta corazones, se pone triste con hambre y duerme de 22:00 a 7:00.
- **Mercado** (Tú → Mercado): la **Sonoteca** (compartir tus audios con amigos y grupos, ❤️, usarlos con el nombre del autor, pasarlos, ocultar y denunciar; lista de retiro por derechos de autor) y la tienda (poderes y protector de racha).
- **Enganche sano:** misiones del día con cofre sorpresa, racha con cada amigo (🔥), protector de racha y un recordatorio diario (18:00–21:00, se apaga en Ajustes → Avisos).
- **Animaciones:** rebote al tocar, entrada de pantallas, confeti en logros y cofres, emoji que vuela al mandar, anillos al recibir, botones de sonido en 3D.
- **Poderes**, que se compran con puntos: Súper sonido (pantalla dorada), Eco (se repite hasta 10 s) y Doble puntos (en el próximo reto).
- **Legal:**
  - Términos de uso y política de privacidad en `docs/legal/` (también dentro de la app).
  - «Denunciar» bloquea al instante y arma un correo si está `FG_CONTACT_EMAIL`.
  - Aviso de 13+ al empezar.
- **Diseño:** fondo morado, rosado y amarillo, fuentes Fredoka y Nunito, 5 pestañas (Sonar, Chats, Grupos, Amigos, Tú).

### Pruebas

La prueba automática (`tools/e2e/run.sh`) corre en cada push en un emulador Android 15. Comprueba con amigos y admins simulados por ntfy.sh real:

- **Sonidos:** solicitudes, recibir y detener sonidos, el límite de 10 s, pantalla apagada y Doze, que un desconocido no suena.
- **Grupos:** con QR, sacar a alguien, firmas falsas, comunidades.
- **Lo nuevo:** chat, retos (recibir, sonar, detener, mandar el tiempo, crear, cancelar), candado (aceptar, no se puede detener, se cumple), puntos y ranking, botón personalizado, mascota y poder Eco.
- **Otros:** donación, pausa, términos, Quick Share, que se actualiza sola, sin cierres inesperados.

**Último resultado (rama `ccr-5646872f-1z0big`, 21 de octubre de 2026): 199 comprobaciones bien, 0 fallos.** Incluye la recarga al mandar, la misión del día, la racha con un amigo y la Sonoteca (recibir, usar y pasar un sonido).

Además, `tools/render/run.sh` dibuja las mascotas y las pantallas principales sin emulador (ver `tools/render/README.md`).

No se puede probar en el emulador: la cámara real (escanear el QR) ni el micrófono real. Ese paso avisa sin contar como fallo.

El resultado de cada ejecución está en GitHub → **Actions → Probar en emulador**, y las capturas en https://github.com/chiquidg1234-hue/Friend-game/releases/tag/capturas

## 2. Pendiente, en orden de prioridad

1. **Probar en celulares reales**, con al menos dos personas. Revisar:
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
   - accesorios para la mascota (gorros, lentes) comprados con puntos;
   - temporadas (Halloween, Navidad) con temas y logros;
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
