# Revisión de seguridad (1 de noviembre de 2026)

Se revisó el código a mano (los escáneres de Ruflo no leen Kotlin, ver `docs/RUFLO.md`): el manifiesto, lo que llega por la red, los archivos, los permisos y lo que pide Google Play.

## Lo que se arregló

| Problema | Riesgo | Arreglo |
| --- | --- | --- |
| **Cualquiera que supiera tu código podía leer tus mensajes.** El canal de ntfy es tu código, y ntfy deja escuchar cualquier canal. | Chat, sonidos, retos y candados a la vista de quien tuviera tu código (por ejemplo, si lo publicaste). | **Cifrado de punta a punta entre amigos** (`Cifrado.kt`): ECDH P-256 → HKDF-SHA256 → AES-256-GCM. El servidor ve solo `{"t":"enc","from":…}`. |
| **Los mensajes de amigos no iban firmados.** Un amigo que supiera el código de otro podía hacerse pasar por él. | Sonidos o mensajes falsos «de Ana». | AES-GCM autentica: solo tu amigo puede armar un sobre que abra. Desde el primer mensaje cifrado de un amigo, todo lo que llegue sin cifrar a su nombre se ignora (salvo `req`/`acc`). |
| Un sobre cifrado copiado se podía volver a publicar. | Repetir un sonido o un mensaje. | Cada sobre tiene un nonce al azar; los últimos 400 se recuerdan y uno repetido no se abre. |
| Al eliminar a un amigo o cambiar de código, el «adiós» salía después de borrar la clave. | El amigo cifrado lo ignoraba y seguía viéndote como amigo. | El adiós se cifra antes de borrar (`removeFriend`, `confirmResetCode`). |
| La copia de seguridad incluía todo. | Videos de bromas pesados dentro de la copia. | `backup_rules.xml` y `data_extraction_rules.xml` dejan fuera `files/clips/`. |
| La versión de Play pedía `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. | Google limita ese permiso a pocos casos y puede rechazar la app. | La versión `play` no lo declara: abre la lista de Android para que la persona elija Friend Game. |

## Cómo funciona el cifrado

1. Cada celular crea un par de claves P-256 la primera vez (`Store.myKeys`). La privada no sale del celular. Con «Cambiar mi código» se crean claves nuevas.
2. La pública (`pk`) viaja en todo mensaje sin cifrar a un canal personal (la solicitud, la aceptación, los puntos) y en cada sobre cifrado.
3. **La primera clave que llega de un amigo es la que vale** (TOFU): después no se puede cambiar. Para empezar de cero con alguien, se elimina y se vuelve a agregar.
4. Clave de cada par de amigos: `HKDF-SHA256(sal = los dos códigos ordenados, ECDH, "friendgame e2e v1")`. Cada mensaje va en AES-GCM con un nonce de 12 bytes y, como dato autenticado, `fg1|de|para`.
5. Compatibilidad: a un amigo con una versión antigua (que nunca mandó su clave) se le sigue escribiendo sin cifrar, como antes.

La versión en Python (`tools/e2e/sobre.py`) es la misma. La prueba en emulador (paso 9p) comprueba que:

- la app manda su clave al aceptar;
- abre un mensaje cifrado;
- ignora el mismo sobre repetido y un mensaje sin cifrar a nombre de la amiga;
- lo que contesta sale cifrado.

## Lo que sigue igual (y está dicho en la política de privacidad)

- **Grupos y comunidades** no van cifrados de punta a punta: el canal es el id del grupo, que conoce quien tenga la invitación. Los cambios del admin sí van firmados (ECDSA).
- **Los audios adjuntos** (audios propios y Sonoteca) viajan como archivo en ntfy, sin cifrar. Solo el mensaje que los acompaña va cifrado.
- **El ranking** lo informa cada celular: alguien que modifique la app puede inflar sus puntos.
- **TOFU:** si alguien que sabe tu código y el de tu amigo te escribe a nombre de tu amigo *antes* que él (después de actualizar), su clave quedaría como la de tu amigo. Se arregla eliminando y volviendo a agregar al amigo.

## Revisado y sin cambios

- **Componentes exportados:** solo `MainActivity` (el ícono) y `BootReceiver`, que escucha avisos que solo manda el sistema. Los demás (`ListenService`, `GrabacionService`, `ClipProvider`, receptores) no se exportan.
- **PendingIntent:** todos `FLAG_IMMUTABLE`.
- **Adjuntos:** se bajan solo desde el servidor configurado y con tope de tamaño (`Relay.download`).
- **Textos que llegan:** los nombres se cortan a 24 caracteres y los mensajes a 500; los ids se validan con expresiones regulares.
- **Red:** solo HTTPS (Android bloquea HTTP sin cifrar al apuntar a la API 28 o más).
- **`ClipProvider`:** solo lectura, solo archivos `broma-*.mp4` de dos carpetas, y solo para la app que recibe el permiso al compartir.
- **Claves en el repo:** `signing/friendgame.jks` es a propósito (solo la versión github). La de Play va en secretos de GitHub.
- **Registros:** la app no escribe en el log de Android (`Log.*`).
