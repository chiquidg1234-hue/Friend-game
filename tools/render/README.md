# Ver la app sin emulador

`tools/render/run.sh` compila la app contra `android-all` (SDK 35) y la dibuja con Robolectric usando
los gráficos nativos de Android (Skia), sin SDK ni emulador.

```bash
tools/render/run.sh                  # todo
tools/render/run.sh Phases           # solo las capturas por fase
tools/render/run.sh PetRender Screens
```

Deja en `tools/render/out/`:

- `pets.png`: las 12 mascotas en sus 5 etapas y tristes;
- `frames.png`: cuadros de las animaciones;
- `mascot.png`: Bulla en todos sus estados y cuadro a cuadro;
- `outfits.png`: prendas del armario sobre varias especies;
- `1-sonar.png`, `2-tu.png`, `3-mercado.png`, `4-amigos.png`: pantallas completas con datos de ejemplo.

`Phases` deja las capturas de cada pantalla por fase en `screenshots/fase-1`, `fase-2` y `fase-3`
(ver `screenshots/README.md`): intro, estados de Bulla, Sonar, subir audio, amigos, ajustes, la
pieza de la mascota, los minijuegos, los fondos por categoría y la llegada de un sonido.

Si no hay `kotlinc` instalado, el script baja el compilador de Maven Central
(`kotlin-compiler-embeddable`). Las descargas se revisan por tamaño: si Maven responde con un error
(por ejemplo 429), se vuelve a intentar en vez de seguir con un archivo roto.

Limitaciones: no hay recursos compilados (aapt), así que el tema de la ventana es el de Android
(sale una barra con el nombre de la actividad) y los íconos se leen del XML con `IconShadow`. Las
clases de `androidx.test` que pide Robolectric son de mentira (`stubs/`): Google Maven no siempre
está disponible. Para la prueba de verdad, con toques y red, está `tools/e2e/run.sh`.
