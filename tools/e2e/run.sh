#!/usr/bin/env bash
# Prueba de punta a punta en un emulador: instala la app, la configura tocando la pantalla y
# simula a dos amigos con curl contra el servidor ntfy real.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
APK="${APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
PKG=com.friendgame.app
SERVER="${SERVER:-https://ntfy.sh}"
OUT="$ROOT/e2e-out"
UI="python3 $HERE/ui.py"
INBOX="python3 $HERE/inbox.py"
TONE="$HERE/tono_20s.ogg"
mkdir -p "$OUT"
FAILS=0
STEP=0

shot() {
  STEP=$((STEP + 1))
  adb exec-out screencap -p > "$OUT/$(printf '%02d' $STEP)-$1.png" 2>/dev/null || true
}

ok() { echo "✅ $1"; }
bad() {
  echo "❌ $1"
  FAILS=$((FAILS + 1))
  shot "fallo"
  echo "--- pantalla en este momento:"
  $UI dump | head -60
}
check() {
  local desc="$1"
  shift
  if "$@"; then ok "$desc"; else bad "$desc"; fi
}

new_code() { python3 -c 'import random; a="23456789abcdefghjkmnpqrstuvwxyz"; print("".join(random.choice(a) for _ in range(16)))'; }
publish() { curl -s -o /dev/null -w '%{http_code}' -d "$2" "$SERVER/fgame-$1"; }
publish_file() {
  local msg
  msg=$(python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$3")
  curl -s -o /dev/null -w '%{http_code}' -T "$2" "$SERVER/fgame-$1?filename=sonido.ogg&message=$msg"
}
sound_json() { echo "{\"fg\":1,\"t\":\"snd\",\"from\":\"$1\",\"name\":\"$2\",\"id\":\"$3\",\"s\":\"$4\",\"label\":\"$5\"}"; }
stop_json() { echo "{\"fg\":1,\"t\":\"stop\",\"from\":\"$1\",\"id\":\"$2\"}"; }

echo "== Instalando $APK"
adb install -r -g "$APK" || { echo "No se pudo instalar"; exit 1; }
adb logcat -c
adb shell am start -n "$PKG/.MainActivity" >/dev/null

echo "== 1. Primer inicio: pide el nombre"
check "Aparece la bienvenida" $UI wait "bienvenida" 40
shot "bienvenida"
$UI tapclass android.widget.EditText
adb shell input text "Robot"
check "Botón Listo" $UI tap "Listo"
check "Muestra el saludo con el nombre" $UI wait "Hola, Robot" 15
check "Muestra el código de amigo" $UI wait "Tu código de amigo" 10
PRETTY=$($UI find '^[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$')
MYCODE=$(echo "$PRETTY" | tr -d '-' | tr 'A-Z' 'a-z')
echo "Código del robot: $PRETTY ($MYCODE)"
[ ${#MYCODE} -eq 16 ] || { bad "No pude leer el código"; exit 1; }
check "Se conecta al servidor" $UI wait "Listo para recibir sonidos" 60
shot "inicio"
check "El servicio está en primer plano" sh -c "adb shell dumpsys activity services $PKG | grep -q 'isForeground=true'"

echo "== 2. Ana manda una solicitud y el robot la acepta"
ANA=$(new_code)
curl -s -N "$SERVER/fgame-$ANA/json" > "$OUT/inbox-ana.jsonl" &
ANA_SUB=$!
sleep 2
echo "publicar req: $(publish "$MYCODE" "{\"fg\":1,\"t\":\"req\",\"from\":\"$ANA\",\"name\":\"Ana\"}")"
check "Aparece la solicitud de Ana" $UI wait "Ana te mandó una solicitud" 30
check "Llega la notificación de solicitud" sh -c "adb shell dumpsys notification --noredact | grep -q 'solicitud de amistad'"
shot "solicitud"
check "Botón Aceptar" $UI tap "Aceptar"
check "A Ana le llega la aceptación" $INBOX "$OUT/inbox-ana.jsonl" acc from="$MYCODE" name=Robot --wait 20
check "Ana aparece como destinataria" $UI wait "Toca para mandar a Ana" 10
$UI top
shot "amigos"

echo "== 3. Ana manda un sonido integrado"
echo "publicar snd: $(publish "$MYCODE" "$(sound_json "$ANA" Ana p1 sirena Sirena)")"
check "Suena la sirena de Ana" $UI playing 20
check "Termina sola" $UI silent 10

echo "== 4. Ana manda un audio propio largo y lo detiene ella"
echo "publicar adjunto: $(publish_file "$MYCODE" "$TONE" "$(sound_json "$ANA" Ana p2 custom 'Tono largo')")"
check "Suena el audio propio de Ana" $UI playing 25
START=$(date +%s)
check "Llega la notificación con botón Detener" sh -c "adb shell dumpsys notification --noredact | grep -q 'te mandó un sonido'"
publish "$MYCODE" "$(stop_json "$ANA" otro)" >/dev/null
sleep 1
check "Un detener con otro id no lo corta" $UI playing 1
publish "$MYCODE" "$(stop_json "$ANA" p2)" >/dev/null
check "El detener de Ana lo corta" $UI silent 4
ELAPSED=$(( $(date +%s) - START ))
check "…antes del límite de 10 s ($ELAPSED s después de empezar)" test "$ELAPSED" -lt 8
check "Se quita el aviso de 'sonando'" $UI absent "Ana te mandó «Tono largo»" 8

echo "== 5. El robot detiene un sonido desde su propio celular"
publish_file "$MYCODE" "$TONE" "$(sound_json "$ANA" Ana p3 custom 'Otra vez')" >/dev/null
check "Suena otra vez" $UI playing 25
check "Muestra quién lo mandó" $UI wait "Ana te mandó «Otra vez»" 6
shot "sonando"
check "Botón Detener" $UI tap "⏹ Detener"
check "Se detiene al tocar Detener" $UI silent 3

echo "== 6. Ningún sonido dura más de 10 segundos"
publish_file "$MYCODE" "$TONE" "$(sound_json "$ANA" Ana p4 custom 'Muy largo')" >/dev/null
check "Suena el audio de 20 s" $UI playing 25
START=$(date +%s)
$UI silent 15
ELAPSED=$(( $(date +%s) - START ))
check "Se corta solo a los 10 s ($ELAPSED s)" test "$ELAPSED" -ge 7 -a "$ELAPSED" -le 12

echo "== 7. Un desconocido no puede hacer sonar el celular"
EXTRANO=$(new_code)
publish "$MYCODE" "$(sound_json "$EXTRANO" Extraño x1 bocina Bocina)" >/dev/null
check "No suena el sonido del desconocido" $UI never 8

echo "== 8. El robot le manda un sonido a Ana y lo detiene"
$UI top
check "Toca la Bocina" $UI tap "Bocina"
SENT=$($INBOX "$OUT/inbox-ana.jsonl" snd from="$MYCODE" s=bocina --wait 20)
check "A Ana le llega la bocina" test -n "$SENT"
SENT_ID=$(echo "$SENT" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' 2>/dev/null)
check "Aparece el botón para detener mi sonido" $UI wait "Detener mi sonido" 8
shot "enviado"
check "Toca Detener mi sonido" $UI tap "Detener mi sonido"
check "A Ana le llega el detener" $INBOX "$OUT/inbox-ana.jsonl" stop from="$MYCODE" id="$SENT_ID" --wait 20

echo "== 9. El robot agrega a Beto con su código"
BETO=$(new_code)
BETO_PRETTY=$(echo "$BETO" | tr 'a-z' 'A-Z' | sed 's/.\{4\}/&-/g; s/-$//')
curl -s -N "$SERVER/fgame-$BETO/json" > "$OUT/inbox-beto.jsonl" &
BETO_SUB=$!
sleep 2
check "Botón Agregar amigo" $UI tap "Agregar amigo"
shot "agregar"
$UI tapclass android.widget.EditText
adb shell input text "$BETO_PRETTY"
check "Botón Mandar solicitud" $UI tap "Mandar solicitud"
check "A Beto le llega la solicitud" $INBOX "$OUT/inbox-beto.jsonl" req from="$MYCODE" --wait 20
$UI top
check "Beto aparece como pendiente" $UI see "Esperando que acepte"
publish "$MYCODE" "{\"fg\":1,\"t\":\"acc\",\"from\":\"$BETO\",\"name\":\"Beto\"}" >/dev/null
check "Al aceptar, Beto aparece con su nombre" $UI wait "Beto" 20
shot "beto"

echo "== 10. Pausar la recepción"
$UI top
check "Toca el interruptor" $UI tapclass android.widget.Switch
check "Queda en pausa" $UI wait "En pausa" 10
check "El servicio se detiene" sh -c "sleep 2; ! adb shell dumpsys activity services $PKG | grep -q ListenService"
shot "pausa"
publish "$MYCODE" "$(sound_json "$ANA" Ana p5 bocina 'En pausa')" >/dev/null
check "En pausa no suena nada" $UI never 6
check "Vuelve a activar" $UI tapclass android.widget.Switch
check "Se reconecta" $UI wait "Listo para recibir sonidos" 60
check "Lo mandado durante la pausa no suena al reactivar" $UI never 5
shot "final"

echo "== 11. Pasar la app a un amigo (Quick Share)"
check "Botón Pasar la app" $UI tap "Pasar la app a un amigo"
check "Se abre el menú de compartir con el APK" sh -c "sleep 3; adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | grep -qiE 'chooser|intentresolver'"
shot "pasar-app"
adb shell input keyevent KEYCODE_BACK
sleep 2

echo "== 12. Se actualiza sola desde la app"
LATEST=$(curl -s -o /dev/null -D - "https://github.com/chiquidg1234-hue/Friend-game/releases/latest" | tr -d '\r' | sed -n 's#^[Ll]ocation: .*/tag/v1\.0\.\([0-9]*\)$#\1#p')
echo "Última versión publicada: $LATEST"
adb logcat -d > "$OUT/logcat-1.txt"
adb uninstall "$PKG" >/dev/null
adb install -g "$ROOT/app-debug-v1.apk" >/dev/null
adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow
adb shell am start -n "$PKG/.MainActivity" >/dev/null
check "La versión vieja pide el nombre" $UI wait "bienvenida" 40
$UI tapclass android.widget.EditText
adb shell input text "Robot"
$UI tap "Listo"
check "Avisa que hay una versión nueva" $UI wait "Actualizar ahora" 90
shot "actualizacion"
check "Toca Actualizar ahora" $UI tap "Actualizar ahora"
# Desde Android 12 una app puede actualizarse a sí misma sin preguntar; en versiones viejas
# aparece la pantalla "¿Actualizar esta app?" y hay que confirmar.
UPDATED=no
for i in $(seq 1 30); do
  if adb shell dumpsys package "$PKG" | grep -q "versionCode=$LATEST "; then UPDATED=yes; break; fi
  if $UI tapexact "Update" 2>/dev/null; then echo "(Android pidió confirmar la actualización)"; fi
  sleep 2
done
check "Queda instalada la versión $LATEST" test "$UPDATED" = yes
check "Avisa con una notificación que se actualizó" sh -c "adb shell dumpsys notification --noredact | grep -q 'se actualizó a la versión'"
shot "actualizada"

echo "== 13. Sin cierres inesperados"
adb logcat -d > "$OUT/logcat-2.txt"
cat "$OUT/logcat-1.txt" "$OUT/logcat-2.txt" > "$OUT/logcat.txt"
if grep -q "Process: $PKG" "$OUT/logcat.txt"; then
  bad "La app se cerró por un error"
  grep -B 2 -A 25 "Process: $PKG" "$OUT/logcat.txt" | head -80
else
  ok "Ningún cierre inesperado"
fi

kill "$ANA_SUB" "$BETO_SUB" 2>/dev/null
echo
if [ "$FAILS" -eq 0 ]; then
  echo "🎉 Todas las pruebas pasaron"
else
  echo "⚠️  Fallaron $FAILS comprobaciones"
  grep -E "com\.friendgame|friendgame" "$OUT/logcat.txt" | tail -60
fi
exit "$FAILS"
