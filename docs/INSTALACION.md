# Por qué "se descarga y no pasa nada" y cómo se arregló

Esta nota explica el problema de instalación de Friend Game y lo que se cambió. Sirve igual para cualquier app Android que se reparta como `.apk` fuera de Play Store.

## El problema

Al tocar el enlace del `.apk` desde WhatsApp, desde la app de Claude o desde un lector de QR, Android no abre Chrome normal. Abre una **ventanita del navegador dentro de esa app**: la que tiene una **✕** arriba a la izquierda (se llama *Custom Tab* o navegador integrado).

En esa ventanita:

- El archivo **sí se descarga** y aparece "Descarga completa".
- Pero **el instalador de Android no aparece**. No sale el aviso de "Abrir", o al tocarlo no pasa nada, porque la ventanita no es dueña de la descarga ni tiene permiso para instalar apps.

Por eso parecía que la descarga "se quedaba ahí".

## Qué se cambió

### 1. El enlace ya no lleva directo al `.apk`

Todo lo que se comparte (el botón **Compartir** de la app, el README y el QR) apunta a la **página de instalación**:
https://github.com/chiquidg1234-hue/Friend-game/releases/latest

Esa página es la release de GitHub. El texto lo escribe el workflow `.github/workflows/android.yml` en cada versión, y dice primero:

1. Si ves una ✕ arriba, toca **⋮ → Abrir en Chrome**.
2. Ya en Chrome, toca **Descargar FriendGame.apk**.
3. **Abrir → Instalar** (la primera vez: **Permitir de esta fuente**).

GitHub no deja poner enlaces `intent://` (los que fuerzan abrir Chrome), así que el paso 1 tiene que hacerlo la persona. Es un toque.

### 2. Pasar la app de celular a celular sin navegador

En la app, **📲 Pasar la app a un amigo** comparte el propio `.apk` instalado. Con **Quick Share** o Bluetooth llega como archivo y el otro celular lo instala directo, sin pasar por ningún navegador.

- Código: `app/src/main/java/com/friendgame/app/ApkProvider.kt` (entrega `applicationInfo.sourceDir`, solo lectura) y `shareApp()` en `MainActivity.kt`.
- Manifest: un `<provider>` con `android:exported="false"` y `android:grantUriPermissions="true"`. El permiso de lectura se da solo a la app que elijas al compartir.

### 3. La app se actualiza sola

Así el navegador solo hace falta **una vez**, para la primera instalación.

- `Updater.kt` pide `github.com/.../releases/latest`, que redirige a la etiqueta de la última versión (`v1.0.N`). N es el `versionCode` de esa compilación; si es mayor que el instalado, hay versión nueva. Se usa esa redirección y no la API de GitHub porque la API limita a 60 consultas por hora sin cuenta.
- Descarga el `.apk` y **comprueba** con `getPackageArchiveInfo` que sea Friend Game y la versión esperada.
- Lo instala con `PackageInstaller` (el instalador oficial de Android). La primera vez Android pide confirmar. Desde Android 12, a partir de la segunda actualización puede instalarse sin preguntar, porque la app ya es su propio "instalador".
- `InstallReceiver` (en `Receivers.kt`) recibe la respuesta del instalador y abre la pantalla de confirmación cuando Android la pide.
- Permisos en el manifest: `REQUEST_INSTALL_PACKAGES` y `UPDATE_PACKAGES_WITHOUT_USER_ACTION`.

Para que esto funcione, **todas las versiones tienen que estar firmadas con la misma clave**: `signing/friendgame.jks`. Si cambias la clave, Android rechaza la actualización.

## Probado

La prueba en emulador (`tools/e2e/run.sh`, corre en cada push) instala una versión vieja, comprueba que avisa "Actualizar ahora", toca el botón, confirma en la pantalla de Android y verifica que queda instalada la última versión. También comprueba que **Pasar la app a un amigo** abre el menú de compartir.

## Para aplicarlo a otra app

1. Que el QR y los enlaces lleven a una **página**, no al `.apk`. Esa página debe decir "⋮ → Abrir en Chrome" antes del botón de descarga.
2. Agrega un botón para **compartir el propio APK** (un `ContentProvider` como `ApkProvider.kt` más un `Intent.ACTION_SEND`).
3. Agrega **auto-actualización** con `PackageInstaller` (copia `Updater.kt` e `InstallReceiver`). Publica cada versión con una etiqueta que lleve el `versionCode`.
4. Firma **siempre con la misma clave**.
