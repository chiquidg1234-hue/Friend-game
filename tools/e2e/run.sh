#!/usr/bin/env bash
# Prueba de punta a punta en un emulador: instala la app, la configura tocando la pantalla y
# simula a dos amigos con curl contra el servidor ntfy real.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
APK="${APK:-$ROOT/app/build/outputs/apk/github/debug/app-github-debug.apk}"
PKG=com.friendgame.app
SERVER="${SERVER:-https://ntfy.sh}"
OUT="$ROOT/e2e-out"
UI="python3 $HERE/ui.py"
INBOX="python3 $HERE/inbox.py"
TONE="$HERE/tono_20s.ogg"
ADMIN="python3 $HERE/fake_admin.py"
mkdir -p "$OUT"
# Al salir (también antes de tiempo) se cortan los "curl" que escuchan en segundo plano: si no,
# GitHub espera a que terminen y la prueba se queda colgada hasta el límite de tiempo.
trap 'kill $(jobs -p) 2>/dev/null' EXIT
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
# Para lo que depende del hardware del emulador (micrófono): avisa sin contar como fallo.
soft() {
  local desc="$1"
  shift
  if "$@"; then ok "$desc"; else echo "⚠️  $desc (no se pudo comprobar en el emulador de GitHub)"; fi
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
sound_json() { local v=""; [ -n "$6" ] && v=",\"v\":\"$6\""; echo "{\"fg\":1,\"t\":\"snd\",\"from\":\"$1\",\"name\":\"$2\",\"id\":\"$3\",\"s\":\"$4\",\"label\":\"$5\"$v}"; }
# Semana como la cuenta la app (ISO: empieza el lunes), para mandar puntos de "esta semana".
WEEK_ID=$(python3 -c 'import datetime; y, w, _ = datetime.date.today().isocalendar(); print(f"{y}-{w}")')
# Ajustes está dentro de la pestaña Tú.
open_settings() { $UI tab Tú && $UI tap "Ajustes" && $UI wait "Recibir sonidos" 10; }
# Volumen de alarma «actual máximo», leído de dumpsys audio.
alarm_volume() {
  adb shell dumpsys audio | python3 -c '
import re, sys
text = sys.stdin.read()
block = text.split("- STREAM_ALARM:", 1)[-1].split("- STREAM_", 1)[0]
cur = re.search(r"streamVolume:\s*(\d+)", block) or re.search(r"\(speaker\):\s*(\d+)", block)
top = re.search(r"Max:\s*(\d+)", block)
print(cur.group(1) if cur else "?", top.group(1) if top else "?")'
}
# Cierra el teclado si quedó abierto (atrás con el teclado cerrado sacaría de la pantalla).
hide_keyboard() {
  if adb shell dumpsys input_method | grep -q "mInputShown=true"; then adb shell input keyevent KEYCODE_BACK; sleep 1; fi
}
stop_json() { echo "{\"fg\":1,\"t\":\"stop\",\"from\":\"$1\",\"id\":\"$2\"}"; }

echo "== Instalando $APK"
# El escritorio del emulador (Pixel Launcher) a veces deja de responder por falta de CPU y su
# aviso tapa la app. Se ocultan esos avisos del sistema; los cierres de Friend Game se revisan
# igual en el logcat (paso 13).
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1 || true
adb install -r -g "$APK" || { echo "No se pudo instalar"; exit 1; }
adb logcat -c
adb shell am start -n "$PKG/.MainActivity" >/dev/null

echo "== 1. Primer inicio: pide el nombre"
check "Aparece la bienvenida" $UI wait "Hazle sonar el celular" 40
shot "bienvenida"
# Si un aviso del emulador ("no responde") se come lo escrito, se vuelve a escribir.
for attempt in 1 2 3; do
  $UI tapclass android.widget.EditText
  adb shell input text "Robot"
  sleep 2
  if $UI find '^Robot$' >/dev/null; then break; fi
  echo "(el nombre no quedó escrito, intento $attempt)"
done
# Con el teclado abierto la app sube el botón Empezar. Se toca sin deslizar: deslizar encima
# del teclado escribiría letras en el nombre.
check "Botón Empezar (visible sobre el teclado)" $UI tapexact "Empezar"
check "Pide aceptar que suene al máximo por el altavoz" $UI wait "Los sonidos suenan fuerte" 15
shot "volumen"
check "Acepta" $UI tapexact "Acepto"
check "Muestra el saludo con el nombre" $UI wait "Hola, Robot" 15
check "Pestaña Amigos" $UI tab Amigos
check "Muestra el código de amigo" $UI wait "Tu código de amigo" 10
PRETTY=$($UI find '^[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$')
MYCODE=$(echo "$PRETTY" | tr -d '-' | tr 'A-Z' 'a-z')
echo "Código del robot: $PRETTY ($MYCODE)"
[ ${#MYCODE} -eq 16 ] || { bad "No pude leer el código"; exit 1; }
check "Pestaña Sonar" $UI tab Sonar
check "Se conecta al servidor" $UI wait "En línea" 60
shot "inicio"
check "El servicio está en primer plano" sh -c "adb shell dumpsys activity services $PKG | grep -q 'isForeground=true'"

echo "== 2. Ana manda una solicitud y el robot la acepta"
ANA=$(new_code)
curl -s -N "$SERVER/fgame-$ANA/json" > "$OUT/inbox-ana.jsonl" &
ANA_SUB=$!
sleep 2
echo "publicar req: $(publish "$MYCODE" "{\"fg\":1,\"t\":\"req\",\"from\":\"$ANA\",\"name\":\"Ana\"}")"
check "Aparece la solicitud de Ana" $UI wait "Ana quiere ser tu amigo" 30
check "Llega la notificación de solicitud" sh -c "adb shell dumpsys notification --noredact | grep -q 'solicitud de amistad'"
check "La pestaña Amigos avisa la solicitud" $UI wait "Pestaña Amigos, 1 solicitud" 5
shot "solicitud"
check "Pestaña Amigos" $UI tab Amigos
check "Botón Aceptar" $UI tap "Aceptar"
check "A Ana le llega la aceptación" $INBOX "$OUT/inbox-ana.jsonl" acc from="$MYCODE" name=Robot --wait 20
shot "amigos"
check "Pestaña Sonar" $UI tab Sonar
check "Ana aparece como destinataria" $UI see "mandárselo a Ana"
$UI top
shot "sonar"

echo "== 3. Ana manda un sonido integrado (con vibe)"
echo "publicar snd: $(publish "$MYCODE" "$(sound_json "$ANA" Ana p1 sirena Sirena susto)")"
check "Suena la sirena de Ana" $UI playing 20
check "La mascota reconoce la vibe del sonido" $UI wait "Sonido susto" 6
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
check "Se quita la pantalla de 'te llegó un sonido'" $UI absent "Te llegó un sonido" 8

echo "== 5. El robot detiene un sonido desde su propio celular"
publish_file "$MYCODE" "$TONE" "$(sound_json "$ANA" Ana p3 custom 'Otra vez')" >/dev/null
check "Suena otra vez" $UI playing 25
shot "sonando"
# Comprueba la pantalla y toca Detener con la misma lectura: el sonido dura solo 10 s.
check "Muestra «te llegó un sonido», quién lo mandó, y Detener funciona" $UI tapwhen "Detener" 8 "Te llegó un sonido" "de Ana"
check "Se detiene al tocar Detener" $UI silent 3

echo "== 6. Ningún sonido dura más de 10 segundos"
publish_file "$MYCODE" "$TONE" "$(sound_json "$ANA" Ana p4 custom 'Muy largo')" >/dev/null
check "Suena el audio de 20 s" $UI playing 25
START=$(date +%s)
$UI silent 15
ELAPSED=$(( $(date +%s) - START ))
check "Se corta solo a los 10 s ($ELAPSED s)" test "$ELAPSED" -ge 7 -a "$ELAPSED" -le 12

echo "== 6b. Suena con la pantalla apagada, en reposo profundo y en silencio"
adb shell input keyevent KEYCODE_SLEEP
sleep 2
adb shell dumpsys deviceidle force-idle >/dev/null
adb shell cmd audio set-ringer-mode SILENT >/dev/null 2>&1 || true
# Volumen de alarma bajo: el sonido tiene que subirlo al máximo y después devolverlo.
adb shell cmd audio set-volume 4 1 >/dev/null 2>&1 || adb shell media volume --stream 4 --set 1 >/dev/null 2>&1 || true
ANTES=$(alarm_volume)
echo "Volumen de alarma antes: $ANTES"
publish_file "$MYCODE" "$TONE" "$(sound_json "$ANA" Ana p6 custom 'Pantalla apagada')" >/dev/null
check "Suena con la pantalla apagada y el celular en reposo (Doze)" $UI playing 30
check "Usa el volumen de alarma, que suena aunque esté en silencio" sh -c "adb shell dumpsys audio | grep AudioPlaybackConfiguration | grep 'state:started' | grep -q USAGE_ALARM"
SONANDO=$(alarm_volume)
check "Mientras suena, el volumen de alarma está al máximo ($SONANDO)" test "${SONANDO% *}" = "${SONANDO#* }" -a "${SONANDO% *}" != "?"
$UI silent 15
DESPUES=$(alarm_volume)
check "Al terminar, el volumen vuelve a como estaba (${ANTES% *} → ${DESPUES% *})" test "${DESPUES% *}" = "${ANTES% *}"
adb shell cmd audio set-ringer-mode NORMAL >/dev/null 2>&1 || true
adb shell dumpsys deviceidle unforce >/dev/null
adb shell input keyevent KEYCODE_WAKEUP
sleep 1
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 2
check "Queda en el historial de sonidos" $UI see "Pantalla apagada"

echo "== 6d. Probar y revisar el sonido"
check "Abre Ajustes (en la pestaña Tú)" open_settings
shot "ajustes"
check "Botón Probar y revisar el sonido" $UI tap "Probar y revisar el sonido"
check "Suena la sirena de prueba" $UI playing 10
check "Muestra la revisión" $UI wait "Revisión del sonido" 10
shot "revision-sonido"
check "Cierra la revisión" $UI tapexact "Cerrar"
$UI silent 10
$UI tab Sonar

echo "== 7. Un desconocido no puede hacer sonar el celular"
EXTRANO=$(new_code)
publish "$MYCODE" "$(sound_json "$EXTRANO" Extraño x1 bocina Bocina)" >/dev/null
check "No suena el sonido del desconocido" $UI never 8

echo "== 8. El robot le manda un sonido a Ana y lo detiene"
$UI top
check "Toca la Bocina" $UI tap "Bocina"
SENT=$($INBOX "$OUT/inbox-ana.jsonl" snd from="$MYCODE" s=bocina --wait 20)
check "A Ana le llega la bocina" test -n "$SENT"
check "La bocina lleva su vibe (susto)" $INBOX "$OUT/inbox-ana.jsonl" snd from="$MYCODE" s=bocina v=susto --wait 5
SENT_ID=$(echo "$SENT" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' 2>/dev/null)
check "Aparece el botón para detener mi sonido" $UI wait "Detener mi sonido" 8
check "Dice a quién se lo mandaste" $UI wait "Mandaste «Bocina» a Ana" 3
shot "enviado"
check "Toca Detener mi sonido" $UI tap "Detener mi sonido"
check "A Ana le llega el detener" $INBOX "$OUT/inbox-ana.jsonl" stop from="$MYCODE" id="$SENT_ID" --wait 20

echo "== 8b. Recarga: dos toques seguidos mandan un solo sonido"
sleep 3
$UI top
# Los dos toques van seguidos, sin esperar a leer la pantalla: el segundo cae durante la recarga.
check "Toca la Sirena dos veces seguidas" sh -c "python3 - <<'PY'
import sys
sys.path.insert(0, '$HERE')
import ui
node = ui.locate(lambda n: (n.get('text') or '').strip() == 'Sirena')
if node is None: sys.exit(1)
x1, y1, x2, y2 = ui.bounds(node)
x, y = str((x1 + x2) // 2), str((y1 + y2) // 2)
ui.adb('shell', 'input', 'tap', x, y)
ui.adb('shell', 'input', 'tap', x, y)
PY"
check "A Ana le llega la sirena" $INBOX "$OUT/inbox-ana.jsonl" snd from="$MYCODE" s=sirena --wait 20
sleep 5
SIRENAS=$($INBOX "$OUT/inbox-ana.jsonl" snd from="$MYCODE" s=sirena --count)
check "…una sola vez, no dos ($SIRENAS)" test "$SIRENAS" -eq 1

echo "== 9. El robot agrega a Beto con su código"
BETO=$(new_code)
BETO_PRETTY=$(echo "$BETO" | tr 'a-z' 'A-Z' | sed 's/.\{4\}/&-/g; s/-$//')
curl -s -N "$SERVER/fgame-$BETO/json" > "$OUT/inbox-beto.jsonl" &
BETO_SUB=$!
sleep 2
check "Pestaña Amigos" $UI tab Amigos
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

echo "== 9b. Grupos: crear, invitar con QR, sonidos y sacar a alguien"
check "Pestaña Grupos" $UI tab Grupos
check "Botón Crear grupo" $UI tap "Crear grupo"
$UI tapclass android.widget.EditText
adb shell input text "Primos"
check "Botón Crear" $UI tapexact "Crear"
check "Muestra el QR de invitación" $UI wait "Invitar a «Primos»" 10
shot "grupo-qr"
INVITE=$($UI find 'https://github\.com/\S*#g=\S*')
GID=$(echo "$INVITE" | sed -n 's/.*#g=\([a-z0-9]*\).*/\1/p')
echo "Invitación: $INVITE"
check "El QR lleva al grupo ($GID)" test "${#GID}" -eq 16
$UI tapexact "Cerrar"
CARLA=$(new_code)
DANI=$(new_code)
curl -s -N "$SERVER/fgame-g-$GID/json" > "$OUT/inbox-grupo.jsonl" &
G_SUB=$!
curl -s -N "$SERVER/fgame-$CARLA/json" > "$OUT/inbox-carla.jsonl" &
CARLA_SUB=$!
curl -s -N "$SERVER/fgame-$DANI/json" > "$OUT/inbox-dani.jsonl" &
DANI_SUB=$!
sleep 2
publish "g-$GID" "{\"fg\":1,\"t\":\"gjoin\",\"from\":\"$CARLA\",\"name\":\"Carla\",\"g\":\"$GID\"}" >/dev/null
publish "g-$GID" "{\"fg\":1,\"t\":\"gjoin\",\"from\":\"$DANI\",\"name\":\"Dani\",\"g\":\"$GID\"}" >/dev/null
check "El admin manda a todos la lista de miembros firmada" $INBOX "$OUT/inbox-grupo.jsonl" groster from="$MYCODE" --wait 20
check "El grupo muestra 3 miembros" $UI see "3 miembros"
shot "grupos"
check "Avisa que alguien se unió" sh -c "adb shell dumpsys notification --noredact | grep -q 'se unió a'"
publish_file "g-$GID" "$TONE" "$(sound_json "$CARLA" Carla gp1 custom 'Audio de Carla')" >/dev/null
check "Suena lo que Carla manda al grupo" $UI playing 25
check "Dice de qué grupo viene" $UI wait "de Carla · Primos" 6
shot "sonando-grupo"
$UI silent 15
check "Pestaña Sonar" $UI tab Sonar
check "Elige el grupo como destino" $UI tap "Mandar a Primos"
check "Toca la Bocina para el grupo" $UI tap "Bocina"
check "Al grupo le llega la bocina" $INBOX "$OUT/inbox-grupo.jsonl" snd from="$MYCODE" s=bocina g="$GID" --wait 20
check "Pestaña Grupos" $UI tab Grupos
check "Opciones del grupo" $UI tap "Opciones de Primos"
check "Ver miembros" $UI tap "Ver miembros"
shot "grupo-miembros"
check "Elige a Carla" $UI tap "Carla"
check "Confirma sacarla" $UI tapexact "Sí"
check "A Carla le llega que ya no está (firmado)" $INBOX "$OUT/inbox-carla.jsonl" gkick from="$MYCODE" --wait 20
MOVE=$($INBOX "$OUT/inbox-dani.jsonl" gmove from="$MYCODE" --wait 20)
check "A Dani le llega el canal nuevo del grupo" test -n "$MOVE"
NEWGID=$(echo "$MOVE" | python3 -c 'import json,sys; print(json.loads(json.load(sys.stdin)["body"])["ng"])' 2>/dev/null)
check "El grupo muestra 2 miembros" $UI see "2 miembros"
sleep 3
publish_file "g-$GID" "$TONE" "$(sound_json "$CARLA" Carla gp2 custom 'Carla expulsada')" >/dev/null
check "Lo que manda Carla al canal viejo ya no suena" $UI never 8
publish "g-$NEWGID" "$(sound_json "$DANI" Dani gp3 sirena Sirena)" >/dev/null
check "Dani sigue en el grupo y su sonido suena" $UI playing 20
$UI silent 10

echo "== 9c. Unirse al grupo de otra persona con su enlace"
$ADMIN new "$OUT/eva.json" > "$OUT/eva-pub.json"
EVA=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["code"])' "$OUT/eva-pub.json")
G2=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["group"])' "$OUT/eva-pub.json")
EVAKEY=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["key"])' "$OUT/eva-pub.json")
INVITE2="https://github.com/chiquidg1234-hue/Friend-game/releases/latest#g=$G2&n=Familia&a=$EVA&k=$EVAKEY"
curl -s -N "$SERVER/fgame-g-$G2/json" > "$OUT/inbox-familia.jsonl" &
FAM_SUB=$!
sleep 2
$UI tab Grupos
$UI top
check "Botón Pegar enlace" $UI tap "Pegar enlace"
$UI tapclass android.widget.EditText
# Se escribe por partes: un texto muy largo de una vez satura el teclado del emulador.
for part in $(echo "$INVITE2" | fold -w 40); do
  adb shell "input text '$part'"
done
check "El enlace quedó completo en el campo" $UI wait "$EVAKEY" 5
check "Botón Unirme" $UI tapexact "Unirme"
check "Pide confirmar antes de unirse" $UI wait "¿Unirte a «Familia»?" 10
check "Confirma" $UI tapexact "Sí"
check "Avisa al grupo que se unió" $INBOX "$OUT/inbox-familia.jsonl" gjoin from="$MYCODE" --wait 20
ROSTER="{\"g\":\"$G2\",\"name\":\"Familia\",\"members\":[{\"code\":\"$EVA\",\"name\":\"Eva\"},{\"code\":\"$MYCODE\",\"name\":\"Robot\"},{\"code\":\"$DANI\",\"name\":\"Fede\"}]}"
publish "g-$G2" "$($ADMIN sign "$OUT/eva.json" groster "$ROSTER")" >/dev/null
check "Toma la lista de miembros firmada por la admin" $UI see "3 miembros · creado por Eva"
$ADMIN new "$OUT/falsa.json" >/dev/null
FAKE=$($ADMIN sign "$OUT/falsa.json" groster "{\"g\":\"$G2\",\"name\":\"Hackeado\",\"members\":[]}" | python3 -c "import json,sys; d=json.load(sys.stdin); d['from']='$EVA'; print(json.dumps(d))")
publish "g-$G2" "$FAKE" >/dev/null
sleep 5
check "Ignora una lista con firma falsa" sh -c "! $UI see Hackeado"
publish "g-$G2" "$(sound_json "$EVA" Eva fm1 sirena Sirena)" >/dev/null
check "Suena lo que la admin manda al grupo" $UI playing 20
$UI silent 10
publish "$MYCODE" "$($ADMIN sign "$OUT/eva.json" gkick "{\"g\":\"$G2\",\"name\":\"Familia\"}")" >/dev/null
check "La admin lo saca y avisa" sh -c "for i in \$(seq 1 20); do adb shell dumpsys notification --noredact | grep -q 'Ya no estás en el grupo' && exit 0; sleep 1; done; exit 1"
check "El grupo desaparece de la lista" sh -c "! $UI see 'creado por Eva'"

echo "== 9d. Grabar un audio con el micrófono"
check "Pestaña Sonar" $UI tab Sonar
check "Botón Grabar" $UI tap "Grabar"
check "Está grabando" $UI wait "Grabando" 10
sleep 3
check "Toca Listo" $UI tap "Listo"
if $UI wait "¿Cómo se llama este audio?" 10; then
  ok "Pide nombre para la grabación"
  check "Guarda la grabación" $UI tapexact "Guardar"
  check "Aparece en Mis audios" $UI see "Mi grabación"
else
  soft "Pide nombre para la grabación" false
  $UI top
fi

echo "== 9e. Apoyar con una donación"
check "Abre Ajustes" open_settings
check "Tarjeta Apoya Friend Game" $UI tap "Apoya Friend Game"
check "Muestra los montos" $UI wait "Elige cuánto" 10
check "Elige 5 USD" $UI tapexact "5 USD"
check "El botón dice el monto" $UI wait "Donar 5 USD" 5
shot "apoyar"
check "Toca Donar" $UI tap "Donar 5 USD"
check "Sin enlace configurado, avisa que pronto" $UI wait "Gracias por querer apoyar" 10
$UI tapexact "Cerrar"
adb shell input keyevent KEYCODE_BACK
check "Atrás vuelve a Ajustes" $UI wait "Tu celular" 10

echo "== 9f. Chat con Ana"
publish "$MYCODE" "{\"fg\":1,\"t\":\"msg\",\"from\":\"$ANA\",\"name\":\"Ana\",\"id\":\"m1abcdefgh\",\"text\":\"Hola Robot, ¿jugamos?\",\"pts\":120,\"wk\":30,\"wid\":\"$WEEK_ID\",\"lvl\":2}" >/dev/null
check "Llega la notificación del mensaje" sh -c "for i in \$(seq 1 20); do adb shell dumpsys notification --noredact | grep -q 'jugamos' && exit 0; sleep 1; done; exit 1"
$UI tab Sonar
check "La pestaña Chats avisa el mensaje nuevo" $UI wait "Pestaña Chats, 1 mensaje nuevo" 10
check "Pestaña Chats" $UI tab Chats
shot "chats"
check "Abre el chat con Ana" $UI tap "Chat con Ana"
check "Se ve el mensaje de Ana" $UI wait "jugamos" 10
$UI tapclass android.widget.EditText
adb shell input text "Dale"
check "Botón Enviar" $UI tap "Enviar mensaje"
check "A Ana le llega el mensaje" $INBOX "$OUT/inbox-ana.jsonl" msg from="$MYCODE" text=Dale --wait 20
check "El mensaje queda en el chat" $UI wait "Dale" 5
shot "chat"

echo "== 9g. Retos"
publish "$MYCODE" "{\"fg\":1,\"t\":\"reto\",\"from\":\"$ANA\",\"name\":\"Ana\",\"id\":\"r1abcdefgh\",\"k\":\"rapido\",\"s\":\"campana\",\"in\":20}" >/dev/null
check "Aparece el reto de Ana en el chat" $UI wait "Reto: El más rápido" 15
check "Muestra la cuenta regresiva" $UI wait "Suena en" 5
shot "reto"
check "A la hora del reto suena para todos" $UI playing 45
check "Muestra la pantalla del reto y lo detiene" $UI tapwhen "Detener" 8 "Reto · El más rápido"
check "Se detiene" $UI silent 3
check "Le manda su tiempo a Ana" $INBOX "$OUT/inbox-ana.jsonl" retores from="$MYCODE" id=r1abcdefgh --wait 20
publish "$MYCODE" "{\"fg\":1,\"t\":\"retores\",\"from\":\"$ANA\",\"name\":\"Ana\",\"id\":\"r1abcdefgh\",\"ms\":9500}" >/dev/null
check "Anota el tiempo de Ana" $UI wait "Ana lo detuvo en" 15
check "El robot crea un reto" $UI tap "Crear un reto"
check "Elige 1 minuto" $UI tapexact "1 min"
check "Botón Crear reto" $UI tapexact "Crear reto"
check "A Ana le llega el reto" $INBOX "$OUT/inbox-ana.jsonl" reto from="$MYCODE" in=60 --wait 20
check "Botón Cancelar reto" $UI tap "Cancelar reto"
check "Confirma" $UI tapexact "Sí"
check "A Ana le llega que se canceló" $INBOX "$OUT/inbox-ana.jsonl" retono from="$MYCODE" --wait 20
check "Crea otro reto" $UI tap "Crear un reto"
check "Elige otro tiempo" $UI tap "Otro tiempo"
check "Toca las horas" $UI tap "Horas del reto"
adb shell input keyevent KEYCODE_MOVE_END KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL
adb shell input text "1"
check "Toca los minutos" $UI tap "Minutos del reto"
adb shell input keyevent KEYCODE_MOVE_END KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL
adb shell input text "30"
check "Listo" $UI tapexact "Listo"
check "Queda elegido 1 h 30 min" $UI wait "1 h 30 min" 8
shot "reto-tiempo"
check "Botón Crear reto" $UI tapexact "Crear reto"
hide_keyboard
R2=$($INBOX "$OUT/inbox-ana.jsonl" reto from="$MYCODE" in=5400 --wait 20)
check "A Ana le llega el reto en 1 h 30 min (5400 s)" test -n "$R2"
R2ID=$(echo "$R2" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' 2>/dev/null)
check "Botón Cancelar reto" $UI tap "Cancelar reto"
check "Confirma" $UI tapexact "Sí"
check "A Ana le llega que se canceló el de 1 h 30 min" $INBOX "$OUT/inbox-ana.jsonl" retono from="$MYCODE" id="$R2ID" --wait 20

echo "== 9h. Candado con Ana"
publish "$MYCODE" "{\"fg\":1,\"t\":\"lockreq\",\"from\":\"$ANA\",\"name\":\"Ana\",\"id\":\"l1abcdefgh\",\"min\":1}" >/dev/null
check "Aparece la propuesta de candado" $UI wait "Ana propone un candado" 15
shot "candado"
check "Acepta el candado" $UI tapexact "Aceptar"
check "Confirma" $UI tapexact "Sí"
check "A Ana le llega que aceptó" $INBOX "$OUT/inbox-ana.jsonl" lockacc from="$MYCODE" id=l1abcdefgh --wait 20
check "El chat muestra el candado activo" $UI wait "Candado · quedan" 10
publish_file "$MYCODE" "$TONE" "$(sound_json "$ANA" Ana lk1 custom 'Con candado')" >/dev/null
check "Suena el sonido de Ana" $UI playing 25
adb shell input keyevent KEYCODE_BACK
sleep 1
check "Con candado, atrás no lo detiene" $UI playing 1
check "No muestra Detener: muestra el candado" $UI wait "Acordaste un candado con Ana" 6
shot "candado-sonando"
$UI silent 12
check "Al terminar el minuto, el candado se cumple" $UI wait "Candado cumplido" 75
adb shell input keyevent KEYCODE_BACK

echo "== 9i. Comunidades"
check "Pestaña Grupos" $UI tab Grupos
check "Botón Crear comunidad" $UI tap "Crear comunidad"
$UI tapclass android.widget.EditText
adb shell input text "Barrio"
check "Botón Crear" $UI tapexact "Crear"
check "Muestra el QR de la comunidad" $UI wait "Invitar a «Barrio»" 10
CINVITE=$($UI find 'https://github\.com/\S*#g=\S*')
CID=$(echo "$CINVITE" | sed -n 's/.*#g=\([a-z0-9]*\).*/\1/p')
check "La invitación dice que es comunidad" sh -c "echo '$CINVITE' | grep -q 'c=1'"
$UI tapexact "Cerrar"
check "Aparece como comunidad" $UI see "Comunidad · 1 miembro"
publish "g-$CID" "{\"fg\":1,\"t\":\"gjoin\",\"from\":\"$DANI\",\"name\":\"Dani\",\"g\":\"$CID\"}" >/dev/null
sleep 4
publish "g-$CID" "$(sound_json "$DANI" Dani cm1 bocina Bocina)" >/dev/null
check "En una comunidad los sonidos sueltos no suenan" $UI never 8

echo "== 9j. Puntos, logros y ranking"
check "Pestaña Tú" $UI tab Tú
check "Muestra los puntos" $UI wait "puntos" 10
shot "perfil"
check "Ganó el logro de su primer sonido" $UI see "Primer susto: Manda tu primer sonido, conseguido"
check "Ana aparece en el ranking" $UI see "Ana, 30 puntos"
check "Ranking completo" $UI tap "Ver ranking completo"
check "Toca Total" $UI tapexact "Total"
check "Ana tiene 120 puntos en total" $UI wait "Ana, 120 puntos" 5
shot "ranking"
adb shell input keyevent KEYCODE_BACK
check "Cumplió la misión de mandar 3 sonidos" $UI see "Misión: Manda 3 sonidos, completada"
check "Pestaña Amigos" $UI tab Amigos
check "Tiene racha con Ana (se mandaron algo los dos hoy)" $UI see "Racha de 1 día con Ana"
shot "racha"

echo "== 9k. Personalizar un botón, adoptar una mascota y usar un poder"
check "Pestaña Sonar" $UI tab Sonar
check "Mantener presionada la Bocina" $UI longtap "Bocina"
check "Opción Personalizar botón" $UI tap "Personalizar botón"
check "Elige el emoji 🐸" $UI tapexact "🐸"
check "Guarda" $UI tapexact "Guardar"
check "El botón ahora tiene la rana" $UI see "🐸"
shot "boton-personalizado"
check "Pestaña Tú" $UI tab Tú
check "Botón Adoptar" $UI tap "Adoptar"
check "Confirma la adopción" $UI tapexact "Adoptar"
check "Aparece la mascota" $UI see "Huevo"
shot "mascota"

echo "== 9k2. Cuidar a la mascota (tipo Pou) y cambiar el fondo"
check "Abre Mascotas" $UI tap "Cuidar y jugar"
check "Muestra las barras de cuidado" $UI wait "Hambre" 10
check "Tiene la barra de Limpieza" $UI see "Limpieza"
shot "mascotas"
check "Juega con la mascota" $UI tap "Jugar"
check "La colección tiene las especies nuevas" $UI see "Conejo"
check "Ofrece mandar un saludo a un amigo" $UI see "Mandarle un saludo a un amigo"
check "Vuelve de Mascotas" $UI tap "Volver"
check "Elige el fondo Mar" $UI tap "🌊 Mar"
check "Sigue en la pestaña Tú" $UI see "Fondo de la app"

check "Pestaña Sonar" $UI tab Sonar
check "Abre los poderes" $UI tap "Poderes"
check "Ve el poder Eco" $UI wait "Eco · 20 puntos" 10
shot "poderes"
# El segundo botón "Usar" es el de Eco (el primero es Súper sonido).
check "Compra Eco" sh -c "python3 - <<'PY'
import subprocess, sys
sys.path.insert(0, '$HERE')
import ui
nodes = [n for n in ui.nodes() if (n.get('text') or '').strip().lower() == 'usar' and ui.on_screen(n)]
if len(nodes) < 2: sys.exit(1)
ui.tap_node(nodes[1])
PY"
check "Queda listo para el próximo sonido" $UI wait "Eco listo" 10
check "Elige a Ana" $UI tap "Mandar a Ana"
check "Toca la Bocina (ahora la rana)" $UI tap "🐸"
check "A Ana le llega el sonido con el poder Eco" $INBOX "$OUT/inbox-ana.jsonl" snd from="$MYCODE" s=bocina pw=eco --wait 20
check "El sonido lleva el avatar y la mascota" $INBOX "$OUT/inbox-ana.jsonl" snd from="$MYCODE" pw=eco pet=gato:0 --wait 5

echo "== 9l. Sonoteca: Ana comparte un sonido, el robot lo usa y se lo pasa a Beto"
publish_file "$MYCODE" "$TONE" "{\"fg\":1,\"t\":\"sndpub\",\"from\":\"$ANA\",\"name\":\"Ana\",\"id\":\"sp1abcdefgh\",\"label\":\"Tono de Ana\",\"e\":\"🎵\",\"by\":\"$ANA\",\"byn\":\"Ana\"}" >/dev/null
check "Pestaña Sonar" $UI tab Sonar
check "Avisa que Ana compartió un sonido" $UI wait "Ana compartió «Tono de Ana»" 30
check "Abre la Sonoteca" $UI tap "Abrir la Sonoteca"
check "Muestra el sonido de Ana" $UI wait "de Ana" 10
shot "sonoteca"
check "Lo usa" $UI tap "Usar «Tono de Ana»"
check "A Ana le avisa que lo usa" $INBOX "$OUT/inbox-ana.jsonl" sndfan from="$MYCODE" id=sp1abcdefgh k=use --wait 20
check "Opciones del sonido" $UI tap "Opciones de «Tono de Ana»"
check "Pasar a mis amigos" $UI tap "Pasar a mis amigos"
check "Pide confirmar que se puede compartir" $UI tap "Confirmo"
check "Compartir" $UI tapexact "Compartir"
check "A Beto le llega, con el nombre de Ana como autora" $INBOX "$OUT/inbox-beto.jsonl" sndpub from="$MYCODE" by="$ANA" id=sp1abcdefgh --wait 30
adb shell input keyevent KEYCODE_BACK
check "Quedó en Tus audios" $UI see "Tono de Ana"
shot "sonoteca-usado"

echo "== 9m. Escuchar antes de mandar, «como le llega» y devolvérsela"
check "Pestaña Sonar" $UI tab Sonar
ANTES=$($INBOX "$OUT/inbox-ana.jsonl" snd from="$MYCODE" --count)
check "Elige el modo escuchar" $UI tap "Modo escuchar"
check "Avisa que así no se manda" $UI wait "no se los manda a nadie" 5
check "Toca «Tono de Ana»" $UI tap "Tono de Ana"
check "Lo escucha en su propio celular" $UI playing 10
check "Lo detiene" $UI tap "Detener lo que suena"
$UI silent 5
check "Activa «Como le llega a Ana»" $UI tap "Como le llega a Ana"
shot "modo-escuchar"
check "Toca «Tono de Ana» otra vez" $UI tap "Tono de Ana"
check "Lo muestra como le llega a Ana y Detener funciona" $UI tapwhen "Detener" 10 "Así le llega a Ana"
check "Se detiene" $UI silent 4
sleep 3
DESPUES=$($INBOX "$OUT/inbox-ana.jsonl" snd from="$MYCODE" --count)
check "A Ana no le llegó nada al escuchar ($ANTES → $DESPUES)" test "$DESPUES" -eq "$ANTES"
check "Vuelve al modo mandar" $UI tap "Modo mandar"
publish "$MYCODE" "$(sound_json "$ANA" Ana rb1 pato Pato)" >/dev/null
check "Suena el pato de Ana" $UI playing 20
$UI silent 10
$UI top
check "Ofrece devolvérsela" $UI wait "Ana te hizo sonar «Pato»" 10
shot "devolversela"
check "Toca Devolvérsela" $UI tap "Devolvérsela"
check "Toca la Campana para devolvérsela" $UI tap "Campana"
check "A Ana le llega la campana" $INBOX "$OUT/inbox-ana.jsonl" snd from="$MYCODE" s=campana --wait 20
$UI top
check "Ya no ofrece devolvérsela" $UI absent "Ana te hizo sonar «Pato»" 10

echo "== 10. Pausar la recepción"
open_settings
$UI top
check "Toca el interruptor" $UI tapclass android.widget.Switch
check "Queda en pausa" $UI wait "En pausa" 10
check "El servicio se detiene" sh -c "sleep 2; ! adb shell dumpsys activity services $PKG | grep -q ListenService"
shot "pausa"
publish "$MYCODE" "$(sound_json "$ANA" Ana p5 bocina 'En pausa')" >/dev/null
check "En pausa no suena nada" $UI never 6
check "Vuelve a activar" $UI tapclass android.widget.Switch
check "Se reconecta" $UI wait "En línea" 60
check "Lo mandado durante la pausa no suena al reactivar" $UI never 5
shot "final"

echo "== 10b. Términos y privacidad"
open_settings
check "Abre los términos de uso" $UI tap "Términos de uso"
check "Muestra las reglas" $UI wait "Reglas de uso" 10
shot "terminos"
$UI tapexact "Entendido"
check "Abre la política de privacidad" $UI tap "Política de privacidad"
check "Explica qué datos usa" $UI wait "Qué datos usa la app" 10
$UI tapexact "Entendido"
$UI tab Sonar

echo "== 11. Pasar la app a un amigo (Quick Share)"
check "Pestaña Amigos" $UI tab Amigos
check "Botón Pasar la app" $UI tap "Pasar la app"
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
check "La versión vieja pide el nombre" $UI wait "Hazle sonar el celular" 40
$UI tapclass android.widget.EditText
adb shell input text "Robot"
sleep 2
# Esta vez con el ✓/Enter del teclado.
adb shell input keyevent KEYCODE_ENTER
# La versión «vieja» se compila con este mismo código: también pide aceptar el volumen.
check "La versión vieja pide aceptar el volumen" $UI wait "Los sonidos suenan fuerte" 15
check "Acepta" $UI tapexact "Acepto"
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
check "Avisa con una notificación que se actualizó" sh -c "for i in \$(seq 1 20); do adb shell dumpsys notification --noredact | grep -q 'se actualizó a la versión' && exit 0; sleep 1; done; exit 1"
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

kill "$ANA_SUB" "$BETO_SUB" "$G_SUB" "$CARLA_SUB" "$DANI_SUB" "$FAM_SUB" 2>/dev/null
echo
if [ "$FAILS" -eq 0 ]; then
  echo "🎉 Todas las pruebas pasaron"
else
  echo "⚠️  Fallaron $FAILS comprobaciones"
  grep -E "com\.friendgame|friendgame" "$OUT/logcat.txt" | tail -60
fi
exit "$FAILS"
