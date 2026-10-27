#!/usr/bin/env bash
# Dibuja pantallas y mascotas de la app SIN el SDK de Android ni un emulador: compila el Kotlin
# contra android-all y lo corre en Robolectric con los gráficos nativos (Skia de verdad).
# Sirve para revisar cómo se ven las cosas cuando no hay emulador a mano (ver AGENTS.md).
#
#   tools/render/run.sh            → tools/render/out/{pets.png,frames.png,mascot.png,1-sonar.png,...}
#                                     y las capturas por fase en screenshots/ (ver screenshots/README.md)
#   tools/render/run.sh Phases     → solo las pruebas nombradas
#
# Necesita Java 17+, Maven y salida a Maven Central. Lo bajado queda en ~/.cache/fg-render.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
CACHE="${FG_RENDER_CACHE:-$HOME/.cache/fg-render}"
OUT="$HERE/out"
BUILD="$CACHE/build"
mkdir -p "$CACHE/deps" "$OUT"
rm -rf "$BUILD" && mkdir -p "$BUILD/classes" "$BUILD/stubs/com/friendgame/app"
MC=https://repo1.maven.org/maven2

# Baja un archivo; si Maven Central responde 429 (manda una página de error), no lo deja guardado.
fetch() {
  [ -s "$2" ] && [ "$(stat -c %s "$2")" -gt 10000 ] && return 0
  curl -sSL -o "$2" "$1"
  [ "$(stat -c %s "$2")" -gt 10000 ] || { rm -f "$2"; echo "No se pudo bajar $1 (¿Maven Central dijo 429?). Prueba de nuevo en un rato." >&2; exit 1; }
}
# kotlinc: el zip de GitHub Releases; si GitHub no se puede bajar, el compilador de Maven Central.
if [ ! -x "$CACHE/kotlinc/bin/kotlinc" ]; then
  if curl -sSL -f -o "$CACHE/kotlin.zip" https://github.com/JetBrains/kotlin/releases/download/v2.1.21/kotlin-compiler-2.1.21.zip; then
    unzip -q -o "$CACHE/kotlin.zip" -d "$CACHE"
  else
    mkdir -p "$CACHE/kotlinc/bin" "$CACHE/kotlinc/lib"
    printf '%s' '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>x</groupId><artifactId>kc</artifactId><version>1</version><dependencies><dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-compiler-embeddable</artifactId><version>2.1.21</version></dependency></dependencies></project>' > "$CACHE/kotlinc/pom.xml"
    (cd "$CACHE/kotlinc" && mvn -q -B dependency:copy-dependencies -DoutputDirectory="$CACHE/kotlinc/lib")
    L="$CACHE/kotlinc/lib"
    cp "$L"/kotlin-stdlib-2*.jar "$L/kotlin-stdlib.jar"
    cp "$L"/kotlin-reflect-*.jar "$L/kotlin-reflect.jar"
    cp "$L"/kotlin-script-runtime-*.jar "$L/kotlin-script-runtime.jar"
    cat > "$CACHE/kotlinc/bin/kotlinc" <<'K'
#!/usr/bin/env bash
LIB="$(cd "$(dirname "$0")/../lib" && pwd)"
CP=$(ls -1 "$LIB"/*.jar | grep -v -e '/kotlin-stdlib-2' -e '/kotlin-reflect-1' -e '/kotlin-script-runtime-2' | tr '\n' ':')
exec java -Xmx3g -Xss4m -Dfile.encoding=UTF-8 -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -kotlin-home "$LIB/.." "$@"
K
    chmod +x "$CACHE/kotlinc/bin/kotlinc"
  fi
fi
fetch $MC/org/robolectric/android-all/16-robolectric-13921718/android-all-16-robolectric-13921718.jar "$CACHE/android-all.jar"
fetch $MC/com/google/zxing/core/3.5.3/core-3.5.3.jar "$CACHE/zxing.jar"
# Robolectric baja este solo, pero Maven Central a veces responde 429 a sus pedidos: se baja antes.
fetch $MC/org/robolectric/android-all-instrumented/15-robolectric-12650502-i7/android-all-instrumented-15-robolectric-12650502-i7.jar "$CACHE/deps/android-all-instrumented-15-robolectric-12650502-i7.jar"
[ -d "$CACHE/libs" ] || (cd "$HERE" && mvn -q -B dependency:copy-dependencies -DoutputDirectory="$CACHE/libs")

AA="$CACHE/android-all.jar"
# R y BuildConfig de mentira (sin aapt no hay recursos compilados; los íconos los lee IconShadow).
{
  echo "package com.friendgame.app; public final class R { public static final class drawable {"
  i=1; for f in "$REPO"/app/src/main/res/drawable/*.xml; do echo "public static final int $(basename "$f" .xml) = 0x7f01$(printf %04x $i);"; i=$((i+1)); done
  echo "} }"
} > "$BUILD/stubs/com/friendgame/app/R.java"
cat > "$BUILD/stubs/com/friendgame/app/BuildConfig.java" <<'J'
package com.friendgame.app;
public final class BuildConfig { public static final String DONATE_URL = ""; public static final String CONTACT_EMAIL = ""; public static final boolean STORE_BUILD = false; }
J
javac -nowarn -cp "$AA" -d "$BUILD/classes" "$BUILD"/stubs/com/friendgame/app/*.java $(find "$HERE/stubs" -name '*.java')
LIBS=$(ls -1 "$CACHE"/libs/*.jar | tr '\n' ':')
"$CACHE/kotlinc/bin/kotlinc" -nowarn -jvm-target 17 -cp "$AA:$CACHE/zxing.jar:$BUILD/classes:$LIBS" -d "$BUILD/classes" \
  "$REPO"/app/src/main/java/com/friendgame/app/*.kt "$HERE"/src/*.kt
TESTS="${*:-PetRender PetFrames MascotRender Screens Phases}"
for test in $TESTS; do
  java -Drepo="$REPO" -Dout="$OUT" -Dshots="$REPO/screenshots" -Drobolectric.offline=true -Drobolectric.dependency.dir="$CACHE/deps" \
    -cp "$BUILD/classes:$CACHE/kotlinc/lib/kotlin-stdlib.jar:$CACHE/zxing.jar:$LIBS:$AA" org.junit.runner.JUnitCore "com.friendgame.app.$test" | tail -3
done
ls -1 "$OUT"
