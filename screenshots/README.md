# Capturas del diseño 2.0

Se generan solas con `tools/render/run.sh Phases`: la app real, dibujada con Robolectric y los gráficos nativos de Android (Skia), sin emulador. La barra gris de arriba con el nombre de la actividad es del entorno de prueba (sin recursos compilados), no de la app. El diseño de referencia es el lienzo «Friend Game · Diseño 2.0» de Claude Design (https://claude.ai/artifact/HMqBNoRcDAxTRu2SshHdiP); hay copia en `docs/design/canvas/`.

## Fase 1: intro y mascota

| Archivo | Qué muestra |
| --- | --- |
| `fase-1/01-intro.png` | La intro, en cuatro momentos: Bulla cae y aterriza, las letras caen, emojis y ondas, y la app aparece. |
| `fase-1/02-bulla-estados.png` | Bulla tranquila, feliz, bailando, sorprendida, riendo, asustada, enamorada, traviesa, saludando, hablando, festejando y comiendo. |
| `fase-1/03-bulla-animaciones.png` | Cada estado cuadro a cuadro (0,1 a 2 s), la espuma del baño y la reacción al audio. |
| `fase-1/04-bienvenida.png` | La bienvenida con Bulla saludando. |
| `fase-1/05-envio.png` | Al mandar un sonido: el fondo se abre con su categoría y Bulla asoma; después, el aviso con Bulla. |
| `fase-1/06-vacio-grupos.png` | Una pantalla vacía con Bulla. |

## Fase 2: UI

| Archivo | Qué muestra |
| --- | --- |
| `fase-2/01-sonar.png`, `02-sonar-tarjetas.png` | Sonar: tarjetas grandes con color, ícono y categoría, ▶ para escuchar, filtros y modo escuchar. |
| `fase-2/03-filtro-risa.png`, `04-buscar.png` | Filtro por categoría y buscador. |
| `fase-2/05-subir-escuchando.png`, `06-subir-detectado.png` | Subir audio: Bulla escucha y adivina la categoría. |
| `fase-2/07-amigos.png`, `08-tu.png`, `09-ajustes.png` | Amigos, Tú y Ajustes (con «El fondo reacciona» y «Reducir movimiento»). |
| `fase-2/10…14-mascota-*.png`, `15-mascota-piezas.png` | La pieza de la mascota tipo Pou: cocina, baño con espuma, dormir con la luz apagada, armario y háblale. |
| `fase-2/16-minijuegos.png`…`19-juego-resultado.png` | Minijuegos: la lista, «Atrapa la comida», «Revienta burbujas» y el resultado. |

## Fase 3: fondos y detección de audio

| Archivo | Qué muestra |
| --- | --- |
| `fase-3/01-fondos-por-categoria.png` | Los 6 fondos animados: risa, fiesta, susto, amor, troll y voz. |
| `fase-3/02-recibir-*.png`, `04-recibir-por-categoria.png` | Te llega un sonido: el fondo de su categoría y Bulla reaccionando (se ríe, baila, tiembla, ojos de corazón). |
| `fase-3/03-reaccion-al-llegar.png` | El fondo abriéndose en círculo al llegar el sonido. |
| `fase-3/05-deteccion-de-audio.png` | La detección de la categoría de un audio subido (antes y después). |
| `fase-3/06-elegir-fondo.png`, `07-fondo-disco-elegido.png` | Elegir el fondo a mano, y la app con el fondo «Disco». |
