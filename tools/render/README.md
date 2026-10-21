# Ver la app sin emulador

`tools/render/run.sh` compila la app contra `android-all` y la dibuja con Robolectric usando los
gráficos nativos de Android (Skia), sin SDK ni emulador. Deja en `tools/render/out/`:

- `pets.png`: las 8 mascotas en sus 5 etapas y tristes;
- `frames.png`: cuadros de las animaciones (comer, saltar, llorar);
- `1-sonar.png`, `2-tu.png`, `3-mercado.png`, `4-amigos.png`: pantallas completas con datos de ejemplo.

Limitaciones: no hay recursos compilados (aapt), así que el tema de la ventana es el de Android
(sale una barra con el nombre de la actividad) y los íconos se leen del XML con `IconShadow`. Las
clases de `androidx.test` que pide Robolectric son de mentira (`stubs/`): Google Maven no siempre
está disponible. Para la prueba de verdad, con toques y red, está `tools/e2e/run.sh`.
