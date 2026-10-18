package com.friendgame.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Servicio en primer plano que mantiene abierta la conexión con el servidor y reacciona a los
 * mensajes: solicitudes de amistad, sonidos, señales de "detener" y cambios en los grupos.
 * Escucha el canal personal y el de cada grupo en una sola conexión.
 */
class ListenService : Service() {

    companion object {
        /** Un sonido que llega con más retraso que esto ya no se reproduce. */
        private const val MAX_SOUND_AGE_S = 60L
        private const val RATE_WINDOW_MS = 20_000L
        private const val RATE_MAX_PER_FRIEND = 4
        private const val MAX_DOWNLOAD_BYTES = 2_600_000L

        @Volatile
        var connected = false
            private set

        fun start(context: Context) {
            Store.init(context)
            if (!Store.receiving || Store.myName.isBlank()) return
            try {
                context.startForegroundService(Intent(context, ListenService::class.java))
            } catch (e: Exception) {
                // Android puede negar el arranque en segundo plano; se reintenta al abrir la app.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListenService::class.java))
        }

        fun restart(context: Context) {
            stop(context)
            start(context)
        }
    }

    @Volatile
    private var running = false
    private var worker: Thread? = null

    @Volatile
    private var connection: HttpURLConnection? = null

    /** Hora actual según el servidor (llega en cada evento), para detectar sonidos viejos. */
    private var serverNow = 0L

    /**
     * Hora del servidor al conectar por primera vez en esta sesión. Lo mandado antes (con la
     * recepción en pausa o el celular apagado) no suena; las solicitudes sí se recuperan.
     */
    private var sessionStart = 0L
    private val recentSounds = HashMap<String, ArrayDeque<Long>>()
    private var lastNetwork: Network? = null

    @Volatile
    private var subscribedTopics: List<String> = emptyList()

    @Volatile
    private var reconnectNow = false

    /** Al unirse a un grupo, salir o cambiar su canal, hay que reconectar con la lista nueva. */
    private val storeListener = Store.Listener {
        if (running && currentTopics() != subscribedTopics) reconnect()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = lastNetwork
            lastNetwork = network
            if (previous != null && previous != network) {
                connection?.disconnect()
            }
            if (!connected && sleeping) worker?.interrupt()
        }
    }

    /** El hilo está esperando para reintentar (y no a mitad de una conexión). */
    @Volatile
    private var sleeping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notifs.createChannels(this)
        Store.addListener(storeListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notifs.serviceNotification(this, connected)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifs.ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notifs.ID_SERVICE, notification)
            }
        } catch (_: Exception) {
            // Android 12+ puede negarlo si el sistema reinicia el servicio en segundo plano; se
            // vuelve a iniciar al abrir la app, al reiniciar el celular o al actualizarse.
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Store.receiving) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker?.isAlive != true) {
            running = true
            worker = Thread({ loop() }, "friendgame-listen").also { it.start() }
            try {
                getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(networkCallback)
            } catch (_: Exception) {
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Store.removeListener(storeListener)
        running = false
        connection?.disconnect()
        worker?.interrupt()
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        setConnected(false)
        super.onDestroy()
    }

    private fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        if (running) Notifs.updateService(this, value)
        Store.notifyChanged()
    }

    private fun currentTopics(): List<String> =
        listOf(Relay.topicFor(Store.myCode)) + Store.groups().map { Relay.groupTopic(it.id) }

    private fun reconnect() {
        reconnectNow = true
        val conn = connection
        // Cerrar la conexión es una operación de red: no puede hacerse en el hilo principal.
        Thread {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }.start()
        worker?.interrupt()
    }

    private fun loop() {
        var backoffMs = 2_000L
        while (running) {
            try {
                listenOnce { backoffMs = 2_000L }
            } catch (_: Exception) {
            }
            setConnected(false)
            if (!running) break
            if (reconnectNow) {
                reconnectNow = false
                Thread.interrupted()
                continue
            }
            try {
                sleeping = true
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
            } finally {
                sleeping = false
            }
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        }
    }

    private fun listenOnce(onOpen: () -> Unit) {
        val myCode = Store.myCode
        val topics = currentTopics()
        subscribedTopics = topics
        val conn = Relay.subscribeUrl(topics, Store.lastEventTime).openConnection() as HttpURLConnection
        connection = conn
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 100_000 // ntfy manda un "keepalive" cada 45 s
            conn.setRequestProperty("User-Agent", "FriendGame")
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (running) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val event = try {
                        JSONObject(line)
                    } catch (_: JSONException) {
                        continue
                    }
                    val time = event.optLong("time")
                    if (time > serverNow) serverNow = time
                    when (event.optString("event")) {
                        "open" -> {
                            if (sessionStart == 0L) sessionStart = time
                            setConnected(true)
                            onOpen()
                        }
                        "message" -> handleMessage(myCode, event)
                    }
                    // "open" llega antes de repetir los mensajes guardados: si se usara su hora,
                    // un corte a mitad de la repetición perdería el resto para siempre.
                    if (event.optString("event") != "open" && time > Store.lastEventTime) Store.lastEventTime = time
                    // Cada evento (y el "keepalive" de cada 45 s) sirve de reloj de respaldo por si
                    // Android atrasa la alarma de un reto.
                    Retos.checkDue(this)
                    Locks.finishDue()
                }
            }
        } finally {
            connection = null
            conn.disconnect()
        }
    }

    private fun handleMessage(myCode: String, event: JSONObject) {
        if (!Store.markProcessed(event.optString("id"))) return
        val payload = try {
            JSONObject(event.optString("message"))
        } catch (_: JSONException) {
            return
        }
        if (payload.optInt("fg") != Relay.PROTOCOL_VERSION) return
        val from = Codes.normalize(payload.optString("from")) ?: return
        if (from == myCode) return
        val name = payload.optString("name").trim().take(24).ifEmpty { "Alguien" }
        val topic = event.optString("topic")
        if (topic.startsWith(Relay.GROUP_PREFIX)) {
            val group = Store.group(topic.removePrefix(Relay.GROUP_PREFIX)) ?: return
            handleGroupMessage(group, from, name, payload, event)
            return
        }
        val friend = Store.friend(from)?.takeIf { it.state == Friend.FRIEND }
        if (friend != null) {
            Points.updateScore(from, friend.name, payload)
            Looks.updateProfile(from, payload)
        }
        val key = Chats.friendKey(from)
        when (payload.optString("t")) {
            "req" -> onFriendRequest(from, name)
            "acc" -> onAccepted(from, name)
            "bye" -> onBye(from)
            "snd" -> {
                if (friend == null || friend.muted) return
                onSound(from, friend.name, null, payload, event)
            }
            "stop" -> Player.stopIfMatches(payload.optString("id"), from)
            "gmove" -> onGroupMoved(from, payload)
            "gkick" -> onGroupKicked(from, payload)
            "msg" -> if (friend != null) Chats.receive(this, key, from, friend.name, payload, event.optString("id"), sentAt(event))
            "reto" -> if (friend != null && !friend.muted) Retos.receive(this, key, from, friend.name, payload, sentAgoMs(event))
            "retores" -> if (friend != null) Retos.onResult(key, from, friend.name, payload)
            "retono" -> Retos.onCancelled(this, from, payload)
            "lockreq" -> if (friend != null) Locks.onRequest(this, from, friend.name, payload, sentAgoMs(event))
            "lockacc" -> if (friend != null && !Locks.onAccepted(this, from, payload, sentAgoMs(event))) {
                sendQuietly(from, Relay.payload("lockno").put("id", payload.optString("id")))
            }
            "lockno" -> Locks.onRejected(from, payload)
        }
    }

    /** Hace cuánto se mandó el mensaje, según el reloj del servidor (no depende de la hora de cada celular). */
    private fun sentAgoMs(event: JSONObject): Long = ((serverNow - event.optLong("time")) * 1000).coerceAtLeast(0)

    /** Cuándo se mandó, en el reloj de este celular. */
    private fun sentAt(event: JSONObject): Long = System.currentTimeMillis() - sentAgoMs(event)

    private fun handleGroupMessage(group: Group, from: String, name: String, payload: JSONObject, event: JSONObject) {
        val member = group.members.firstOrNull { it.code == from }
        val sender = member?.name ?: name
        val blocked = Store.friend(from)?.state == Friend.BLOCKED
        if (member != null) {
            Points.updateScore(from, sender, payload)
            Looks.updateProfile(from, payload)
        }
        val key = Chats.groupKey(group.id)
        when (payload.optString("t")) {
            "snd" -> {
                // En una comunidad los sonidos solo suenan en los retos.
                if (group.muted || group.community) return
                val friend = Store.friend(from)
                if (friend != null && (friend.state == Friend.BLOCKED || friend.muted)) return
                onSound(from, sender, group, payload, event)
            }
            "stop" -> Player.stopIfMatches(payload.optString("id"), from)
            // Chat y retos solo de quienes están en la lista de miembros (no de alguien con un enlace viejo).
            "msg" -> if (member != null && !blocked) Chats.receive(this, key, from, sender, payload, event.optString("id"), sentAt(event))
            "reto" -> if (member != null && !blocked && !group.muted) Retos.receive(this, key, from, sender, payload, sentAgoMs(event))
            "retores" -> if (member != null && !blocked) Retos.onResult(key, from, sender, payload)
            "retono" -> Retos.onCancelled(this, from, payload)
            "gjoin" -> {
                val isNew = group.members.none { it.code == from }
                if (isNew && group.isAdmin && group.members.size >= Group.MAX_MEMBERS) {
                    // Lleno: la lista firmada ya no cabe en un mensaje. Se le avisa a quien quiso entrar.
                    val body = JSONObject().put("g", group.id).put("name", group.name).put("full", true)
                    sendQuietly(from, Relay.signed("gkick", body, group.adminSecret))
                    return
                }
                val updated = Store.updateGroup(group.id) { current ->
                    current.copy(members = current.members.filter { it.code != from } + Member(from, name))
                } ?: return
                if (updated.isAdmin) {
                    if (isNew) Notifs.groupJoined(this, name, group.name)
                    try {
                        Groups.publishRoster(updated)
                    } catch (_: Exception) {
                    }
                }
            }
            "gleave" -> Store.updateGroup(group.id) { current -> current.copy(members = current.members.filter { it.code != from }) }
            "groster" -> {
                val body = verifiedBody(group, from, payload) ?: return
                val members = Store.membersFrom(body.optJSONArray("members") ?: JSONArray())
                val name2 = body.optString("name").trim().take(24).ifEmpty { group.name }
                Store.updateGroup(group.id) { current ->
                    current.copy(
                        name = name2, members = members,
                        community = body.optBoolean("c", current.community),
                        emoji = body.optString("e", current.emoji).take(8),
                    )
                }
            }
            "gend" -> {
                verifiedBody(group, from, payload) ?: return
                Store.removeGroup(group.id)
                Notifs.groupGone(this, group.id, "El grupo «${group.name}» se cerró")
            }
        }
    }

    /** Solo vale lo que firmó el admin del grupo, y solo para ese grupo. */
    private fun verifiedBody(group: Group, from: String, payload: JSONObject): JSONObject? {
        if (from != group.adminCode) return null
        val text = payload.optString("body")
        if (!GroupKeys.verify(group.adminKey, text, payload.optString("sig"))) return null
        val body = try {
            JSONObject(text)
        } catch (_: JSONException) {
            return null
        }
        return if (body.optString("g") == group.id) body else null
    }

    private fun groupOf(payload: JSONObject): Group? = try {
        Store.group(JSONObject(payload.optString("body")).optString("g"))
    } catch (_: JSONException) {
        null
    }

    /** El admin sacó a alguien: el grupo sigue en un canal nuevo. */
    private fun onGroupMoved(from: String, payload: JSONObject) {
        val group = groupOf(payload) ?: return
        val body = verifiedBody(group, from, payload) ?: return
        val newId = Codes.normalize(body.optString("ng")) ?: return
        val members = Store.membersFrom(body.optJSONArray("members") ?: JSONArray())
        Store.moveGroup(group.id, group.copy(id = newId, members = members))
    }

    private fun onGroupKicked(from: String, payload: JSONObject) {
        val group = groupOf(payload) ?: return
        val body = verifiedBody(group, from, payload) ?: return
        Store.removeGroup(group.id)
        val what = if (group.community) "la comunidad" else "el grupo"
        Notifs.groupGone(
            this, group.id,
            if (body.optBoolean("full")) "${what.replaceFirstChar { it.uppercase() }} «${group.name}» está lleno (máximo ${Group.MAX_MEMBERS})"
            else "Ya no estás en $what «${group.name}»",
        )
    }

    private fun onFriendRequest(from: String, name: String) {
        val existing = Store.friend(from)
        if (existing == null) {
            Store.putFriend(Friend(from, name, Friend.INCOMING))
            Notifs.friendRequest(this, from, name)
            return
        }
        when (existing.state) {
            // Los dos se agregaron a la vez: ya hay consentimiento de ambos lados.
            Friend.OUTGOING -> {
                val displayName = if (existing.name == Friend.PLACEHOLDER_NAME) name else existing.name
                Store.putFriend(existing.copy(state = Friend.FRIEND, name = displayName))
                sendQuietly(from, Relay.payload("acc"))
                Notifs.requestAccepted(this, from, displayName)
                Points.onNewFriend(from)
            }
            // Ya éramos amigos (quizá perdió el aviso): se le confirma otra vez.
            Friend.FRIEND -> sendQuietly(from, Relay.payload("acc"))
            Friend.INCOMING -> Store.putFriend(existing.copy(name = name))
        }
    }

    private fun onAccepted(from: String, name: String) {
        val existing = Store.friend(from) ?: return
        if (existing.state != Friend.OUTGOING) return
        val displayName = if (existing.name == Friend.PLACEHOLDER_NAME) name else existing.name
        Store.putFriend(existing.copy(state = Friend.FRIEND, name = displayName))
        Notifs.requestAccepted(this, from, displayName)
        Points.onNewFriend(from)
    }

    private fun onBye(from: String) {
        val existing = Store.friend(from) ?: return
        if (existing.state == Friend.BLOCKED) return
        Store.removeFriend(from)
        Player.stopIfFrom(from)
    }

    private fun onSound(from: String, senderName: String, group: Group?, payload: JSONObject, event: JSONObject) {
        val kind = payload.optString("s")
        val label = payload.optString("label").trim().take(30).ifEmpty { Sounds.byKey(kind)?.label ?: "Sonido" }
        // Poderes: solo cambian cómo se ve y se siente; quien recibe lo puede detener igual.
        val power = payload.optString("pw").takeIf { it == Powers.SUPER || it == Powers.ECO } ?: ""
        val startMs = if (kind == "custom") payload.optInt("st").coerceIn(0, CustomAudio.MAX_MS.toInt()) else 0
        val shownName = if (group != null) "$senderName · ${group.name}" else senderName
        val sentAt = event.optLong("time")
        if (sentAt < sessionStart || serverNow - sentAt > MAX_SOUND_AGE_S) {
            // Llegó tarde (la app no estaba conectada): no suena, pero queda anotado.
            Store.addHistory("$shownName · $label (no sonó: llegó cuando la app no estaba conectada)")
            return
        }
        if (!allowSound(from)) return

        val wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "friendgame:sound")
        wakeLock?.acquire(30_000L)
        var played = false
        try {
            val source: Player.Source = if (kind == "custom") {
                val attachment = event.optJSONObject("attachment")
                val file = attachment?.let { download(it.optString("url")) }
                if (file == null) {
                    Store.addHistory("$shownName · $label (no sonó: no se pudo descargar el audio)")
                    return
                }
                Player.Source.FromFile(file)
            } else {
                val sound = Sounds.byKey(kind) ?: return
                Player.Source.Asset(sound.asset)
            }
            Notifs.soundReceived(this, shownName, label, canStop = group != null || Locks.activeWith(from) == null)
            vibrate(strong = power == Powers.SUPER)
            if (isInCall()) {
                Store.addHistory("$shownName · $label (no sonó: estabas en una llamada)")
                return
            }
            val reason = SoundCheck.silentReason(this)
            Store.addHistory(if (reason == null) "$shownName · $label" else "$shownName · $label (puede no haberse oído: $reason)")
            Player.play(
                this, source,
                Player.NowPlaying(
                    payload.optString("id"), from, shownName, label, kind, group?.id,
                    loop = power == Powers.ECO, power = power, startMs = startMs,
                ),
            )
            played = true
            Points.onSoundHeard()
        } finally {
            // Player.play solo encola el trabajo: si ya hay sonido, el wake lock se deja vencer
            // solo para que el celular no se duerma antes de que empiece a sonar.
            if (!played && wakeLock?.isHeld == true) wakeLock.release()
        }
    }

    /** Protección contra spam: máximo 4 sonidos por amigo cada 20 segundos. */
    private fun allowSound(from: String): Boolean {
        val now = System.currentTimeMillis()
        val times = recentSounds.getOrPut(from) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() > RATE_WINDOW_MS) times.removeFirst()
        if (times.size >= RATE_MAX_PER_FRIEND) return false
        times.addLast(now)
        return true
    }

    private fun vibrate(strong: Boolean = false) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
        try {
            val pattern = if (strong) longArrayOf(0, 400, 120, 400, 120, 700) else longArrayOf(0, 150, 100, 150)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {
        }
    }

    /**
     * Solo una llamada telefónica real. Antes también contaba MODE_IN_COMMUNICATION, pero apps
     * como WhatsApp o Discord a veces lo dejan puesto y eso silenciaba todos los sonidos.
     */
    private fun isInCall(): Boolean =
        getSystemService(AudioManager::class.java)?.mode == AudioManager.MODE_IN_CALL

    /** Descarga el audio propio de un amigo, solo desde nuestro servidor y con tamaño limitado. */
    private fun download(url: String): File? {
        if (!url.startsWith(Relay.baseUrl() + "/")) return null
        cacheDir.listFiles { f -> f.name.startsWith("incoming-") }?.forEach { it.delete() }
        val out = File(cacheDir, "incoming-${System.nanoTime()}")
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (_: IOException) {
            return null
        }
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "FriendGame")
            if (conn.responseCode != 200) return null
            var total = 0L
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_DOWNLOAD_BYTES) throw IOException("too big")
                        output.write(buffer, 0, n)
                    }
                }
            }
            return out
        } catch (_: IOException) {
            out.delete()
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun sendQuietly(to: String, payload: JSONObject) {
        try {
            Relay.publish(to, payload)
        } catch (_: Exception) {
        }
    }
}
