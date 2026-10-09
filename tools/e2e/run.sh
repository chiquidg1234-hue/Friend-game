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
echo "publicar snd: $(publish "$MYCODE" "{\"fg\":1,\"t\":\"snd\",\"from\":\"$ANA\",\"name\":\"Ana\",\"id\":\"p1\",\"s\":\"sirena\",\"label\":\"Sirena\"}")"
check "Suena la sirena de Ana" $UI wait "Ana te mandó «Sirena»" 20
shot "sonando"
check "Llega la notificación del sonido" sh -c "adb shell dumpsys notification --noredact | grep -q 'te mandó un sonido'"
check "Termina sola" $UI absent "Ana te mandó «Sirena»" 15

echo "== 4. Ana manda un audio propio de 7,5 s y lo detiene ella"
echo "publicar adjunto: $(publish_file "$MYCODE" "$HERE/tono_largo.ogg" "{\"fg\":1,\"t\":\"snd\",\"from\":\"$ANA\",\"name\":\"Ana\",\"id\":\"p2\",\"s\":\"custom\",\"label\":\"Tono largo\"}")"
check "Suena el audio propio de Ana" $UI wait "Ana te mandó «Tono largo»" 25
shot "audio-propio"
publish "$MYCODE" "{\"fg\":1,\"t\":\"stop\",\"from\":\"$ANA\",\"id\":\"otro\"}" >/dev/null
sleep 1
check "Un detener con otro id no lo corta" $UI wait "Ana te mandó «Tono largo»" 2
publish "$MYCODE" "{\"fg\":1,\"t\":\"stop\",\"from\":\"$ANA\",\"id\":\"p2\"}" >/dev/null
check "El detener de Ana lo corta antes de terminar" $UI absent "Ana te mandó «Tono largo»" 3

echo "== 5. El robot detiene un sonido desde su propio celular"
publish_file "$MYCODE" "$HERE/tono_largo.ogg" "{\"fg\":1,\"t\":\"snd\",\"from\":\"$ANA\",\"name\":\"Ana\",\"id\":\"p3\",\"s\":\"custom\",\"label\":\"Otra vez\"}" >/dev/null
check "Suena otra vez" $UI wait "Ana te mandó «Otra vez»" 25
check "Botón Detener" $UI tap "⏹ Detener"
check "Se detiene al tocar Detener" $UI absent "Ana te mandó «Otra vez»" 3

echo "== 6. Un desconocido no puede hacer sonar el celular"
EXTRANO=$(new_code)
publish "$MYCODE" "{\"fg\":1,\"t\":\"snd\",\"from\":\"$EXTRANO\",\"name\":\"Extraño\",\"id\":\"x1\",\"s\":\"bocina\",\"label\":\"Bocina\"}" >/dev/null
sleep 6
check "No suena el sonido del desconocido" $UI absent "Extraño" 1

echo "== 7. El robot le manda un sonido a Ana y lo detiene"
check "Toca la Bocina" $UI tap "Bocina"
SENT=$($INBOX "$OUT/inbox-ana.jsonl" snd from="$MYCODE" s=bocina --wait 20)
check "A Ana le llega la bocina" test -n "$SENT"
SENT_ID=$(echo "$SENT" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' 2>/dev/null)
$UI top
check "Aparece el botón para detener mi sonido" $UI wait "Detener mi sonido" 10
shot "enviado"
check "Toca Detener mi sonido" $UI tap "Detener mi sonido"
check "A Ana le llega el detener" $INBOX "$OUT/inbox-ana.jsonl" stop from="$MYCODE" id="$SENT_ID" --wait 20

echo "== 8. El robot agrega a Beto con su código"
BETO=$(new_code)
BETO_PRETTY=$(echo "$BETO" | tr 'a-z' 'A-Z' | sed 's/.\{4\}/&-/g; s/-$//')
curl -s -N "$SERVER/fgame-$BETO/json" > "$OUT/inbox-beto.jsonl" &
BETO_SUB=$!
sleep 2
check "Botón Agregar amigo" $UI tap "Agregar amigo"
$UI tapclass android.widget.EditText
adb shell input text "$BETO_PRETTY"
check "Botón Mandar solicitud" $UI tap "Mandar solicitud"
check "A Beto le llega la solicitud" $INBOX "$OUT/inbox-beto.jsonl" req from="$MYCODE" --wait 20
publish "$MYCODE" "{\"fg\":1,\"t\":\"acc\",\"from\":\"$BETO\",\"name\":\"Beto\"}" >/dev/null
$UI top
check "Beto aparece como amigo con su nombre" $UI wait "Beto" 20
shot "beto"

echo "== 9. Pausar la recepción"
check "Toca el interruptor" $UI tapclass android.widget.Switch
check "Queda en pausa" $UI wait "En pausa" 10
publish "$MYCODE" "{\"fg\":1,\"t\":\"snd\",\"from\":\"$ANA\",\"name\":\"Ana\",\"id\":\"p4\",\"s\":\"bocina\",\"label\":\"En pausa\"}" >/dev/null
sleep 6
check "En pausa no suena nada" $UI absent "Ana te mandó «En pausa»" 1
check "Vuelve a activar" $UI tapclass android.widget.Switch
check "Se reconecta" $UI wait "Listo para recibir sonidos" 60
sleep 4
check "Lo mandado durante la pausa no suena al reactivar" $UI absent "Ana te mandó «En pausa»" 1
shot "final"

echo "== 10. Sin cierres inesperados"
adb logcat -d > "$OUT/logcat.txt"
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
  grep -E "friendgame|AndroidRuntime" "$OUT/logcat.txt" | tail -60
fi
exit "$FAILS"
