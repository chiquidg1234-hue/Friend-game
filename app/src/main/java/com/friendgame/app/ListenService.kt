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
            if (!connected) worker?.interrupt()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notifs.createChannels(this)
        Store.addListener(storeListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notifs.serviceNotification(this, connected)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifs.ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifs.ID_SERVICE, notification)
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
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
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
                    if (time > Store.lastEventTime) Store.lastEventTime = time
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
        when (payload.optString("t")) {
            "req" -> onFriendRequest(from, name)
            "acc" -> onAccepted(from, name)
            "bye" -> onBye(from)
            "snd" -> {
                val friend = Store.friend(from) ?: return
                if (friend.state != Friend.FRIEND || friend.muted) return
                onSound(from, friend.name, null, payload, event)
            }
            "stop" -> Player.stopIfMatches(payload.optString("id"), from)
            "gmove" -> onGroupMoved(from, payload)
            "gkick" -> onGroupKicked(from, payload)
        }
    }

    private fun handleGroupMessage(group: Group, from: String, name: String, payload: JSONObject, event: JSONObject) {
        when (payload.optString("t")) {
            "snd" -> {
                if (group.muted) return
                val sender = group.members.firstOrNull { it.code == from }?.name ?: name
                onSound(from, sender, group, payload, event)
            }
            "stop" -> Player.stopIfMatches(payload.optString("id"), from)
            "gjoin" -> {
                val isNew = group.members.none { it.code == from }
                val updated = group.copy(members = group.members.filter { it.code != from } + Member(from, name))
                Store.putGroup(updated)
                if (updated.isAdmin) {
                    if (isNew) Notifs.groupJoined(this, name, group.name)
                    try {
                        Groups.publishRoster(updated)
                    } catch (_: Exception) {
                    }
                }
            }
            "gleave" -> Store.putGroup(group.copy(members = group.members.filter { it.code != from }))
            "groster" -> {
                val body = verifiedBody(group, from, payload) ?: return
                val members = Store.membersFrom(body.optJSONArray("members") ?: JSONArray())
                val name2 = body.optString("name").trim().take(24).ifEmpty { group.name }
                Store.putGroup(group.copy(name = name2, members = members))
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
        verifiedBody(group, from, payload) ?: return
        Store.removeGroup(group.id)
        Notifs.groupGone(this, group.id, "Ya no estás en el grupo «${group.name}»")
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
    }

    private fun onBye(from: String) {
        val existing = Store.friend(from) ?: return
        if (existing.state == Friend.BLOCKED) return
        Store.removeFriend(from)
        Player.stopIfFrom(from)
    }

    private fun onSound(from: String, senderName: String, group: Group?, payload: JSONObject, event: JSONObject) {
        val sentAt = event.optLong("time")
        if (sentAt < sessionStart || serverNow - sentAt > MAX_SOUND_AGE_S) return
        if (!allowSound(from)) return

        val wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "friendgame:sound")
        wakeLock?.acquire(30_000L)
        try {
            val kind = payload.optString("s")
            val label = payload.optString("label").trim().take(30)
            val source: Player.Source = if (kind == "custom") {
                val attachment = event.optJSONObject("attachment") ?: return
                val file = download(attachment.optString("url")) ?: return
                Player.Source.FromFile(file)
            } else {
                val sound = Sounds.byKey(kind) ?: return
                Player.Source.Asset(sound.asset)
            }
            val shownLabel = label.ifEmpty { Sounds.byKey(kind)?.label ?: "Sonido" }
            val shownName = if (group != null) "$senderName · ${group.name}" else senderName
            Notifs.soundReceived(this, shownName, shownLabel)
            Store.addHistory("$shownName · $shownLabel")
            vibrate()
            if (isInCall()) return
            Player.play(this, source, Player.NowPlaying(payload.optString("id"), from, shownName, shownLabel))
        } finally {
            if (wakeLock?.isHeld == true) wakeLock.release()
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

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
        try {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 150, 100, 150), -1))
        } catch (_: Exception) {
        }
    }

    private fun isInCall(): Boolean {
        val mode = getSystemService(AudioManager::class.java)?.mode ?: return false
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

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
