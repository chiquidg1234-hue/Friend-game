#!/usr/bin/env bash
# Dibuja pantallas y mascotas de la app SIN el SDK de Android ni un emulador: compila el Kotlin
# contra android-all y lo corre en Robolectric con los gráficos nativos (Skia de verdad).
# Sirve para revisar cómo se ven las cosas cuando no hay emulador a mano (ver AGENTS.md).
#
#   tools/render/run.sh            → tools/render/out/{pets.png,frames.png,1-sonar.png,...}
#
# Necesita Java 17+, Maven y salida a Maven Central y GitHub. Lo bajado queda en ~/.cache/fg-render.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
CACHE="${FG_RENDER_CACHE:-$HOME/.cache/fg-render}"
OUT="$HERE/out"
BUILD="$CACHE/build"
mkdir -p "$CACHE/deps" "$OUT"
rm -rf "$BUILD" && mkdir -p "$BUILD/classes" "$BUILD/stubs/com/friendgame/app"
MC=https://repo1.maven.org/maven2

fetch() { [ -s "$2" ] || curl -sSL -o "$2" "$1"; }
[ -d "$CACHE/kotlinc" ] || { fetch https://github.com/JetBrains/kotlin/releases/download/v2.1.21/kotlin-compiler-2.1.21.zip "$CACHE/kotlin.zip"; unzip -q -o "$CACHE/kotlin.zip" -d "$CACHE"; }
fetch $MC/org/robolectric/android-all/16-robolectric-13921718/android-all-16-robolectric-13921718.jar "$CACHE/android-all.jar"
fetch $MC/com/google/zxing/core/3.5.3/core-3.5.3.jar "$CACHE/zxing.jar"
# Robolectric baja estos solo, pero Maven Central a veces responde 429 a sus pedidos: se bajan antes.
fetch $MC/org/robolectric/android-all-instrumented/14-robolectric-10818077-i7/android-all-instrumented-14-robolectric-10818077-i7.jar "$CACHE/deps/android-all-instrumented-14-robolectric-10818077-i7.jar"
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
for test in PetRender PetFrames Screens; do
  java -Drepo="$REPO" -Dout="$OUT" -Drobolectric.offline=true -Drobolectric.dependency.dir="$CACHE/deps" \
    -cp "$BUILD/classes:$CACHE/kotlinc/lib/kotlin-stdlib.jar:$CACHE/zxing.jar:$LIBS:$AA" org.junit.runner.JUnitCore "com.friendgame.app.$test" | tail -3
done
ls -1 "$OUT"
