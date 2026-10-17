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
ADMIN="python3 $HERE/fake_admin.py"
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
sound_json() { echo "{\"fg\":1,\"t\":\"snd\",\"from\":\"$1\",\"name\":\"$2\",\"id\":\"$3\",\"s\":\"$4\",\"label\":\"$5\"}"; }
stop_json() { echo "{\"fg\":1,\"t\":\"stop\",\"from\":\"$1\",\"id\":\"$2\"}"; }

echo "== Instalando $APK"
adb install -r -g "$APK" || { echo "No se pudo instalar"; exit 1; }
adb logcat -c
adb shell am start -n "$PKG/.MainActivity" >/dev/null

echo "== 1. Primer inicio: pide el nombre"
check "Aparece la bienvenida" $UI wait "Hazle sonar el celular" 40
shot "bienvenida"
$UI tapclass android.widget.EditText
adb shell input text "Robot"
check "Botón Empezar" $UI tap "Empezar"
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
check "Se quita la pantalla de 'te llegó un sonido'" $UI absent "Te llegó un sonido" 8

echo "== 5. El robot detiene un sonido desde su propio celular"
publish_file "$MYCODE" "$TONE" "$(sound_json "$ANA" Ana p3 custom 'Otra vez')" >/dev/null
check "Suena otra vez" $UI playing 25
check "Muestra la pantalla de 'te llegó un sonido'" $UI wait "Te llegó un sonido" 6
check "Muestra quién lo mandó" $UI wait "de Ana" 3
shot "sonando"
check "Botón Detener" $UI tapexact "Detener"
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
publish_file "$MYCODE" "$TONE" "$(sound_json "$ANA" Ana p6 custom 'Pantalla apagada')" >/dev/null
check "Suena con la pantalla apagada y el celular en reposo (Doze)" $UI playing 30
check "Usa el volumen de alarma, que suena aunque esté en silencio" sh -c "adb shell dumpsys audio | grep AudioPlaybackConfiguration | grep 'state:started' | grep -q USAGE_ALARM"
$UI silent 15
adb shell cmd audio set-ringer-mode NORMAL >/dev/null 2>&1 || true
adb shell dumpsys deviceidle unforce >/dev/null
adb shell input keyevent KEYCODE_WAKEUP
sleep 1
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 2
check "Queda en el historial de sonidos" $UI see "Pantalla apagada"

echo "== 6d. Probar y revisar el sonido"
check "Pestaña Ajustes" $UI tab Ajustes
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
SENT_ID=$(echo "$SENT" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' 2>/dev/null)
check "Aparece el botón para detener mi sonido" $UI wait "Detener mi sonido" 8
check "Dice a quién se lo mandaste" $UI wait "Mandaste «Bocina» a Ana" 3
shot "enviado"
check "Toca Detener mi sonido" $UI tap "Detener mi sonido"
check "A Ana le llega el detener" $INBOX "$OUT/inbox-ana.jsonl" stop from="$MYCODE" id="$SENT_ID" --wait 20

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
check "Pestaña Ajustes" $UI tab Ajustes
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

echo "== 10. Pausar la recepción"
$UI tab Ajustes
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
$UI tap "Empezar"
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
