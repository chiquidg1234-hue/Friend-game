package com.friendgame.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

data class Friend(
    val code: String,
    val name: String,
    val state: String,
    val muted: Boolean = false,
) {
    companion object {
        /** Ya se aceptaron mutuamente: se pueden mandar sonidos. */
        const val FRIEND = "friend"
        /** Yo mandé la solicitud y espero respuesta. */
        const val OUTGOING = "outgoing"
        /** Me mandaron una solicitud que no he respondido. */
        const val INCOMING = "incoming"
        /** Bloqueado: se ignora todo lo que mande. */
        const val BLOCKED = "blocked"

        /** Nombre provisional hasta que el amigo acepta y llega su nombre real. */
        const val PLACEHOLDER_NAME = "Amigo nuevo"
    }
}

data class Member(val code: String, val name: String)

/**
 * Grupo: todos sus miembros escuchan en el mismo canal secreto. Quien lo crea (el admin) tiene la
 * clave privada con la que firma los cambios (lista de miembros, sacar a alguien, cerrarlo).
 */
data class Group(
    val id: String,
    val name: String,
    val adminCode: String,
    val adminKey: String,
    val adminSecret: String = "",
    val members: List<Member> = emptyList(),
    val muted: Boolean = false,
    /**
     * Comunidad: grupo grande con chat, ranking y retos. Sus sonidos solo suenan en los retos,
     * para que nadie haga sonar a 40 personas a cada rato.
     */
    val community: Boolean = false,
    val emoji: String = "",
) {
    val isAdmin: Boolean get() = adminSecret.isNotEmpty()

    companion object {
        /** La lista firmada de miembros tiene que caber en un mensaje del servidor (4 KB). */
        const val MAX_MEMBERS = 40
    }
}

/** Un sonido recibido, para el historial. */
data class HistoryEntry(val time: Long, val text: String)

/** Audio propio. [startMs]: desde dónde suena (para usar la parte graciosa de un audio largo). */
data class CustomSound(val id: String, val label: String, val ext: String, val startMs: Int = 0) {
    fun file(context: Context) = CustomAudio.fileFor(context, this)
}

/** Un mensaje del chat con un amigo o un grupo. */
data class ChatMessage(
    val id: String,
    /** Código de quien lo mandó. */
    val from: String,
    val name: String,
    val text: String,
    val time: Long,
    val kind: String = TEXT,
    /** Id del reto o del candado de la tarjeta. */
    val ref: String = "",
) {
    companion object {
        const val TEXT = "text"
        /** Tarjeta de un reto. */
        const val RETO = "reto"
        /** Tarjeta de un candado propuesto. */
        const val LOCK = "lock"
        /** Aviso de la app dentro del chat ("Ana lo detuvo en 0,8 s"). */
        const val INFO = "info"
    }
}

/** Reto: a la hora [at] (reloj de este celular) suena [sound] en todos los celulares del chat. */
data class Reto(
    val id: String,
    /** Chat donde se creó: "f:código" o "g:id". */
    val chat: String,
    val kind: String,
    val sound: String,
    val at: Long,
    val from: String,
    val fromName: String,
    val state: String = WAITING,
    val results: List<RetoResult> = emptyList(),
) {
    companion object {
        const val WAITING = "waiting"
        const val RANG = "rang"
        /** Ya sonó y se cerró la tabla de tiempos. */
        const val DONE = "done"
        /** Llegó tarde o el celular estaba apagado a la hora del reto. */
        const val MISSED = "missed"
        const val CANCELLED = "cancelled"
    }
}

/** Cuánto tardó alguien en detener el sonido de un reto. */
data class RetoResult(val code: String, val name: String, val ms: Long)

/**
 * Candado: acuerdo entre dos amigos. Mientras dura, ninguno de los dos puede detener, silenciar
 * ni pausar los sonidos del otro.
 */
data class Lock(
    val id: String,
    val friend: String,
    val minutes: Int,
    val state: String,
    val created: Long,
    val until: Long = 0,
) {
    val isActive: Boolean get() = state == ACTIVE && until > System.currentTimeMillis()

    companion object {
        /** Lo propuse yo y espero respuesta. */
        const val OFFERED = "offered"
        /** Me lo propusieron y no he respondido. */
        const val ASKED = "asked"
        const val ACTIVE = "active"
        /** Se cumplió hasta el final. */
        const val DONE = "done"
        const val REJECTED = "rejected"
    }
}

/** Todo lo que la app recuerda, guardado en el propio celular. */
object Store {
    const val DEFAULT_SERVER = "https://ntfy.sh"
    private const val MAX_SEEN_IDS = 60
    private const val MAX_HISTORY = 15
    private const val MAX_CHAT = 150
    private const val MAX_RETOS = 40
    private const val MAX_LOCKS = 40

    fun interface Listener {
        fun onStoreChanged()
    }

    private lateinit var prefs: SharedPreferences
    /** Los chats van en otro archivo: son lo que más crece. */
    private lateinit var chatPrefs: SharedPreferences
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences("friendgame", Context.MODE_PRIVATE)
            chatPrefs = context.applicationContext.getSharedPreferences("friendgame-chats", Context.MODE_PRIVATE)
            Points.init(context)
            Looks.init(context)
            Pets.init(context)
            Powers.init(context)
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun notifyChanged() {
        main.post { listeners.forEach { it.onStoreChanged() } }
    }

    val myCode: String
        @Synchronized get() {
            prefs.getString("code", null)?.let { return it }
            val code = Codes.newCode()
            prefs.edit().putString("code", code).apply()
            return code
        }

    @Synchronized
    fun resetCode() {
        prefs.edit()
            .putString("code", Codes.newCode())
            .putString("friends", "[]")
            .putString("groups", "[]")
            .putLong("last_event_time", 0)
            .putString("seen_ids", "")
            .putString("retos", "[]")
            .putString("locks", "[]")
            .apply()
        chatPrefs.edit().clear().apply()
        notifyChanged()
    }

    var myName: String
        get() = prefs.getString("name", "") ?: ""
        set(value) {
            prefs.edit().putString("name", value.trim().take(24)).apply()
            notifyChanged()
        }

    var server: String
        get() = prefs.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) {
            prefs.edit().putString("server", value.trim().trimEnd('/').ifEmpty { DEFAULT_SERVER })
                .putLong("last_event_time", 0).apply()
            notifyChanged()
        }

    /** El destinatario decide: si está apagado no se recibe nada. */
    var receiving: Boolean
        get() = prefs.getBoolean("receiving", true)
        set(value) {
            prefs.edit().putBoolean("receiving", value).apply()
            notifyChanged()
        }

    /**
     * Si está activado, los sonidos usan el volumen de alarma: suenan aunque el celular esté en
     * silencio o vibración. Lo decide quien recibe, en su propio celular.
     */
    var ringInSilent: Boolean
        get() = prefs.getBoolean("ring_in_silent", true)
        set(value) {
            prefs.edit().putBoolean("ring_in_silent", value).apply()
            notifyChanged()
        }

    /** Último problema al reproducir un sonido (se muestra en la pantalla para entender qué pasó). */
    @Volatile
    var lastProblem: String = ""
        set(value) {
            field = value
            notifyChanged()
        }

    /** Hora (del servidor) del último evento visto, para recuperar lo perdido al reconectar. */
    var lastEventTime: Long
        get() = prefs.getLong("last_event_time", 0)
        set(value) {
            prefs.edit().putLong("last_event_time", value).apply()
        }

    /** Cuándo se buscó por última vez una versión nueva de la app. */
    var lastUpdateCheck: Long
        get() = prefs.getLong("last_update_check", 0)
        set(value) {
            prefs.edit().putLong("last_update_check", value).apply()
        }

    /** Devuelve false si el mensaje ya se había procesado (evita repetir sonidos). */
    @Synchronized
    fun markProcessed(id: String): Boolean {
        if (id.isEmpty()) return true
        val seen = (prefs.getString("seen_ids", "") ?: "").split(',').filter { it.isNotEmpty() }
        if (id in seen) return false
        val updated = (seen + id).takeLast(MAX_SEEN_IDS)
        prefs.edit().putString("seen_ids", updated.joinToString(",")).apply()
        return true
    }

    // ---- Amigos ----

    @Synchronized
    fun friends(): List<Friend> {
        val array = JSONArray(prefs.getString("friends", "[]") ?: "[]")
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            Friend(
                code = o.optString("code"),
                name = o.optString("name"),
                state = o.optString("state", Friend.FRIEND),
                muted = o.optBoolean("muted", false),
            )
        }
    }

    fun friend(code: String): Friend? = friends().firstOrNull { it.code == code }

    @Synchronized
    fun putFriend(friend: Friend) {
        saveFriends(friends().filter { it.code != friend.code } + friend)
    }

    @Synchronized
    fun removeFriend(code: String) {
        deleteChat("f:$code")
        saveLocks(locks().filter { it.friend != code })
        saveFriends(friends().filter { it.code != code })
    }

    private fun saveFriends(list: List<Friend>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("code", it.code)
                    .put("name", it.name)
                    .put("state", it.state)
                    .put("muted", it.muted)
            )
        }
        prefs.edit().putString("friends", array.toString()).apply()
        notifyChanged()
    }

    // ---- Grupos ----

    @Synchronized
    fun groups(): List<Group> {
        val array = JSONArray(prefs.getString("groups", "[]") ?: "[]")
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val members = o.optJSONArray("members") ?: JSONArray()
            Group(
                id = o.optString("id"),
                name = o.optString("name"),
                adminCode = o.optString("admin"),
                adminKey = o.optString("key"),
                adminSecret = o.optString("secret"),
                members = membersFrom(members),
                muted = o.optBoolean("muted", false),
                community = o.optBoolean("community", false),
                emoji = o.optString("emoji"),
            )
        }
    }

    fun group(id: String): Group? = groups().firstOrNull { it.id == id }

    @Synchronized
    fun putGroup(group: Group) {
        saveGroups(groups().filter { it.id != group.id } + group)
    }

    /**
     * Cambia un grupo partiendo de la copia guardada más nueva; si ya no está (lo dejaste o
     * cambió de canal mientras tanto), no lo vuelve a agregar.
     */
    @Synchronized
    fun updateGroup(id: String, change: (Group) -> Group): Group? {
        val current = group(id) ?: return null
        return change(current).also { putGroup(it) }
    }

    /** Cambia el canal del grupo (al sacar a alguien) conservando el resto de los datos. */
    @Synchronized
    fun moveGroup(oldId: String, group: Group) {
        moveChat("g:$oldId", "g:${group.id}")
        saveRetos(retos().map { if (it.chat == "g:$oldId") it.copy(chat = "g:${group.id}") else it })
        saveGroups(groups().filter { it.id != oldId && it.id != group.id } + group)
    }

    @Synchronized
    fun removeGroup(id: String) {
        deleteChat("g:$id")
        saveRetos(retos().filter { it.chat != "g:$id" })
        saveGroups(groups().filter { it.id != id })
    }

    fun membersFrom(array: JSONArray): List<Member> = (0 until array.length()).mapNotNull { i ->
        val m = array.optJSONObject(i) ?: return@mapNotNull null
        val code = Codes.normalize(m.optString("code")) ?: return@mapNotNull null
        Member(code, m.optString("name").trim().take(24).ifEmpty { "Alguien" })
    }.distinctBy { it.code }

    fun membersJson(members: List<Member>): JSONArray {
        val array = JSONArray()
        members.forEach { array.put(JSONObject().put("code", it.code).put("name", it.name)) }
        return array
    }

    private fun saveGroups(list: List<Group>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("admin", it.adminCode)
                    .put("key", it.adminKey)
                    .put("secret", it.adminSecret)
                    .put("members", membersJson(it.members))
                    .put("muted", it.muted)
                    .put("community", it.community)
                    .put("emoji", it.emoji)
            )
        }
        prefs.edit().putString("groups", array.toString()).apply()
        notifyChanged()
    }

    // ---- Historial de sonidos recibidos ----

    @Synchronized
    fun history(): List<HistoryEntry> {
        val array = JSONArray(prefs.getString("history", "[]") ?: "[]")
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            HistoryEntry(o.optLong("time"), o.optString("text"))
        }
    }

    @Synchronized
    fun addHistory(text: String) {
        val updated = (listOf(HistoryEntry(System.currentTimeMillis(), text)) + history()).take(MAX_HISTORY)
        val array = JSONArray()
        updated.forEach { array.put(JSONObject().put("time", it.time).put("text", it.text)) }
        prefs.edit().putString("history", array.toString()).apply()
        notifyChanged()
    }

    // ---- Audios propios ----

    @Synchronized
    fun customSounds(): List<CustomSound> {
        val array = JSONArray(prefs.getString("custom_sounds", "[]") ?: "[]")
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            CustomSound(o.optString("id"), o.optString("label"), o.optString("ext"), o.optInt("st"))
        }
    }

    @Synchronized
    fun putCustomSound(sound: CustomSound) {
        val existing = customSounds()
        val updated = if (existing.any { it.id == sound.id }) {
            existing.map { if (it.id == sound.id) sound else it }
        } else {
            existing + sound
        }
        saveCustomSounds(updated)
    }

    @Synchronized
    fun removeCustomSound(id: String) {
        saveCustomSounds(customSounds().filter { it.id != id })
    }

    private fun saveCustomSounds(list: List<CustomSound>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("id", it.id).put("label", it.label).put("ext", it.ext).put("st", it.startMs)) }
        prefs.edit().putString("custom_sounds", array.toString()).apply()
        notifyChanged()
    }

    // ---- Chats ----

    @Synchronized
    fun chat(key: String): List<ChatMessage> {
        val array = JSONArray(chatPrefs.getString("chat:$key", "[]") ?: "[]")
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            ChatMessage(
                id = o.optString("id"),
                from = o.optString("from"),
                name = o.optString("name"),
                text = o.optString("text"),
                time = o.optLong("time"),
                kind = o.optString("kind", ChatMessage.TEXT),
                ref = o.optString("ref"),
            )
        }
    }

    /** Devuelve false si ya estaba (llegó repetido). [unread]: suma uno a los no leídos. */
    @Synchronized
    fun addChatMessage(key: String, message: ChatMessage, unread: Boolean): Boolean {
        val current = chat(key)
        if (current.any { it.id == message.id }) return false
        val updated = (current + message).sortedBy { it.time }.takeLast(MAX_CHAT)
        val edit = chatPrefs.edit().putString("chat:$key", chatJson(updated).toString())
        if (unread) {
            val counts = unreadJson()
            counts.put(key, counts.optInt(key) + 1)
            edit.putString("unread", counts.toString())
        }
        edit.apply()
        notifyChanged()
        return true
    }

    @Synchronized
    fun removeChatMessage(key: String, id: String) {
        chatPrefs.edit().putString("chat:$key", chatJson(chat(key).filter { it.id != id }).toString()).apply()
        notifyChanged()
    }

    @Synchronized
    fun unreadCounts(): Map<String, Int> {
        val counts = unreadJson()
        return counts.keys().asSequence().associateWith { counts.optInt(it) }.filterValues { it > 0 }
    }

    @Synchronized
    fun markChatRead(key: String) {
        val counts = unreadJson()
        if (counts.optInt(key) == 0) return
        counts.remove(key)
        chatPrefs.edit().putString("unread", counts.toString()).apply()
        notifyChanged()
    }

    @Synchronized
    private fun deleteChat(key: String) {
        val counts = unreadJson()
        counts.remove(key)
        chatPrefs.edit().remove("chat:$key").putString("unread", counts.toString()).apply()
    }

    /** El grupo cambió de canal (sacaron a alguien): su chat lo sigue. */
    @Synchronized
    private fun moveChat(oldKey: String, newKey: String) {
        val messages = chatPrefs.getString("chat:$oldKey", null) ?: return
        val counts = unreadJson()
        val unread = counts.optInt(oldKey)
        counts.remove(oldKey)
        if (unread > 0) counts.put(newKey, unread)
        chatPrefs.edit().remove("chat:$oldKey").putString("chat:$newKey", messages)
            .putString("unread", counts.toString()).apply()
    }

    private fun unreadJson(): JSONObject = try {
        JSONObject(chatPrefs.getString("unread", "{}") ?: "{}")
    } catch (_: org.json.JSONException) {
        JSONObject()
    }

    private fun chatJson(list: List<ChatMessage>): JSONArray {
        val array = JSONArray()
        list.forEach {
            val o = JSONObject()
                .put("id", it.id)
                .put("from", it.from)
                .put("name", it.name)
                .put("text", it.text)
                .put("time", it.time)
            if (it.kind != ChatMessage.TEXT) o.put("kind", it.kind)
            if (it.ref.isNotEmpty()) o.put("ref", it.ref)
            array.put(o)
        }
        return array
    }

    // ---- Retos ----

    @Synchronized
    fun retos(): List<Reto> {
        val array = JSONArray(prefs.getString("retos", "[]") ?: "[]")
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val results = o.optJSONArray("results") ?: JSONArray()
            Reto(
                id = o.optString("id"),
                chat = o.optString("chat"),
                kind = o.optString("kind"),
                sound = o.optString("sound"),
                at = o.optLong("at"),
                from = o.optString("from"),
                fromName = o.optString("fromName"),
                state = o.optString("state", Reto.WAITING),
                results = (0 until results.length()).mapNotNull { j ->
                    val r = results.optJSONObject(j) ?: return@mapNotNull null
                    RetoResult(r.optString("code"), r.optString("name"), r.optLong("ms"))
                },
            )
        }
    }

    fun reto(id: String): Reto? = retos().firstOrNull { it.id == id }

    @Synchronized
    fun putReto(reto: Reto) {
        saveRetos(retos().filter { it.id != reto.id } + reto)
    }

    /** Cambia un reto partiendo de la copia guardada; null si ya no está. */
    @Synchronized
    fun updateReto(id: String, change: (Reto) -> Reto?): Reto? {
        val current = reto(id) ?: return null
        val updated = change(current) ?: return null
        putReto(updated)
        return updated
    }

    private fun saveRetos(list: List<Reto>) {
        // Se guardan los más nuevos; los que todavía no suenan, siempre.
        val keep = list.sortedBy { it.at }.let { all ->
            val extra = all.size - MAX_RETOS
            if (extra <= 0) all else {
                val drop = all.filter { it.state != Reto.WAITING }.take(extra).map { it.id }.toSet()
                all.filter { it.id !in drop }
            }
        }
        val array = JSONArray()
        keep.forEach { reto ->
            val results = JSONArray()
            reto.results.forEach { results.put(JSONObject().put("code", it.code).put("name", it.name).put("ms", it.ms)) }
            array.put(
                JSONObject()
                    .put("id", reto.id)
                    .put("chat", reto.chat)
                    .put("kind", reto.kind)
                    .put("sound", reto.sound)
                    .put("at", reto.at)
                    .put("from", reto.from)
                    .put("fromName", reto.fromName)
                    .put("state", reto.state)
                    .put("results", results)
            )
        }
        prefs.edit().putString("retos", array.toString()).apply()
        notifyChanged()
    }

    // ---- Candados ----

    @Synchronized
    fun locks(): List<Lock> {
        val array = JSONArray(prefs.getString("locks", "[]") ?: "[]")
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            Lock(
                id = o.optString("id"),
                friend = o.optString("friend"),
                minutes = o.optInt("minutes"),
                state = o.optString("state"),
                created = o.optLong("created"),
                until = o.optLong("until"),
            )
        }
    }

    fun lock(id: String): Lock? = locks().firstOrNull { it.id == id }

    @Synchronized
    fun putLock(lock: Lock) {
        saveLocks(locks().filter { it.id != lock.id } + lock)
    }

    @Synchronized
    fun updateLock(id: String, change: (Lock) -> Lock?): Lock? {
        val current = lock(id) ?: return null
        val updated = change(current) ?: return null
        putLock(updated)
        return updated
    }

    private fun saveLocks(list: List<Lock>) {
        val array = JSONArray()
        // Los activos nunca se borran; si hay demasiados, se van primero los terminados más viejos.
        val sorted = list.sortedBy { it.created }
        val extra = sorted.size - MAX_LOCKS
        val drop = if (extra <= 0) emptySet() else sorted.filter { !it.isActive && it.state != Lock.ASKED && it.state != Lock.OFFERED }
            .take(extra).map { it.id }.toSet()
        sorted.filter { it.id !in drop }.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("friend", it.friend)
                    .put("minutes", it.minutes)
                    .put("state", it.state)
                    .put("created", it.created)
                    .put("until", it.until)
            )
        }
        prefs.edit().putString("locks", array.toString()).apply()
        notifyChanged()
    }
}
