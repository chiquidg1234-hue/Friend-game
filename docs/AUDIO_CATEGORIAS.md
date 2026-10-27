# Categorías de audio: cómo se detectan y por qué así

Cada sonido tiene una categoría (en el código, su «vibe»): 😂 **Risa**, 🎉 **Fiesta**, 😱 **Susto**, 💖 **Amor**, 😈 **Troll** y 🗣️ **Voz**. Le da su color y su ícono en Sonar, el fondo animado que aparece cuando suena (`SceneView`) y cómo reacciona la mascota. Viaja en el `snd` como `v`.

- **Integrados:** traen la categoría de fábrica (`Sounds.all`). Se puede cambiar en las opciones del botón («🏷️ Categoría»).
- **Subidos o grabados:** la pantalla «Subir audio» (`UploadSheet`) los escucha y adivina la categoría con `AudioClassifier`. Si no está segura, lo dice y pide elegirla. La persona siempre puede elegir otra.

## Qué se evaluó

| | YAMNet (TensorFlow Lite) | Clasificador propio (`AudioClassifier.kt`) |
| --- | --- | --- |
| Peso en el APK | ~4 MB el modelo + ~1,5–3 MB por arquitectura el motor TFLite (6–10 MB en el APK universal) | 0 MB (unas 400 líneas de Kotlin) |
| Dependencias | `org.tensorflow:tensorflow-lite` (nativa) | Ninguna. AGENTS.md pide no sumar dependencias. |
| Qué entiende | 521 clases de AudioSet (habla, risa, música, sirena, alarma, grito, pedo…) | 6 categorías, con medidas simples del sonido |
| Precisión en audios reales | Mejor (es un modelo entrenado) | Buena en audios de laboratorio; en audios reales no está medida |
| Velocidad en gama media | ~100–300 ms por 10 s, más la carga del modelo | ~20–60 ms en la computadora; unos 100–300 ms en un Honor X7c |
| Se puede probar aquí | No (no hay SDK de Android ni emulador en este entorno) | Sí: `tools/audio/run.sh`, sin Android |

**Decisión:** el clasificador propio. Cuesta 0 MB, no suma una librería nativa y se puede probar en la computadora. Los errores no rompen nada: la categoría solo cambia colores, fondo y la reacción de la mascota, y la pantalla siempre muestra lo que detectó para que la persona la cambie. Si en celulares reales se equivoca mucho, el siguiente paso es YAMNet: mapear sus clases a las 6 categorías (Speech → voz, Laughter/Giggle → risa, Music → fiesta, Siren/Alarm/Screaming → susto, Bell/Lullaby → amor, Fart/Burping/efectos → troll) y bajar el modelo solo la primera vez, para no pesar en el APK.

## Cómo funciona

`AudioAnalysis` decodifica el audio con `MediaExtractor` y `MediaCodec` (sin permisos). Lo pasa a mono de 16 kHz y analiza como mucho 20 segundos desde donde empieza a sonar. `AudioClassifier` mide:

- volumen medio y silencios;
- si tiene tono (autocorrelación), cuál es, cuánto varía y cuánto salta entre un instante y otro;
- brillo (centroide), ruido (planitud espectral), graves y agudos;
- vaivén del volumen al ritmo de las sílabas (2,5–8 por segundo) y ráfagas parejas («ja-ja-ja»);
- golpes (cambio del espectro) y si se repiten parejo, como un ritmo;
- cuánto tarda en llegar fuerte y si se apaga al final (campanas).

Cada categoría tiene un puntaje con rangos suaves de esas medidas. Por ejemplo, Voz: sílabas, tono de 80–300 Hz que varía, pausas y sin ritmo parejo. Susto: fuerte, agudo, sostenido o con sirena que sube y baja despacio. Troll: ruido, zumbido muy grave o tonos que saltan muy rápido (láser). La seguridad es un softmax de los puntajes. Con menos de **45 %**, o si casi no se oye nada, no elige y pregunta.

La misma decodificación da la **envolvente** (cuánto suena cada 40 ms). Mientras algo suena, `Player.level()` la lee en la posición actual: la mascota mueve la boca, la antena brilla y el fondo late al ritmo del audio.

## Pruebas

```bash
tools/audio/run.sh       # arma los audios de prueba y muestra qué elige y con cuánta seguridad
tools/audio/run.sh -v    # además, las medidas de cada audio
```

Los audios de prueba se generan sin internet (`tools/audio/make_testset.py`): voz y risa con espeak-ng, música, sirenas, alarmas, grito, campanas, ruidos, pedo y láser sintetizados, y los 12 integrados.

Resultado (27 de octubre de 2026): **17 de 17** en los audios de cada categoría. En los integrados acierta 5 de 12: varios son efectos cuya categoría es de sentido, no de sonido (el pato es «risa», el redoble «fiesta»). No importa porque los integrados traen la suya. Son audios de laboratorio: los de WhatsApp o TikTok suenan distinto, así que hay que mirarlo con audios reales de la prueba cerrada.
