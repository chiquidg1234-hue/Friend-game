#!/usr/bin/env bash
# Prueba el clasificador de categorías de audio (AudioClassifier.kt) en la computadora, sin
# Android: arma los audios de prueba y muestra qué categoría elige y con cuánta seguridad.
#
#   tools/audio/run.sh            → tabla con ✅ bien, ❔ pregunta (y la sugerencia era buena), ❓/❌
#   tools/audio/run.sh -v         → además, las medidas de cada audio
#
# Necesita Java 17+, numpy, ffmpeg y espeak-ng. Usa el kotlinc de tools/render (~/.cache/fg-render).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
CACHE="${FG_RENDER_CACHE:-$HOME/.cache/fg-render}"
OUT="${FG_AUDIO_OUT:-$CACHE/audio}"
mkdir -p "$OUT/set" "$OUT/classes"
python3 "$HERE/make_testset.py" "$OUT/set"
"$CACHE/kotlinc/bin/kotlinc" -nowarn -jvm-target 17 -d "$OUT/classes" \
  "$REPO/app/src/main/java/com/friendgame/app/AudioClassifier.kt" "$HERE/Evaluate.kt" 2>&1 | grep -v JAVA_TOOL || true
java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp "$OUT/classes:$CACHE/kotlinc/lib/kotlin-stdlib.jar" EvaluateKt "$OUT/set" ${1:+verbose} 2>&1 | grep -v JAVA_TOOL
