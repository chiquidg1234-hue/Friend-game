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
) {
    val isAdmin: Boolean get() = adminSecret.isNotEmpty()
}

/** Un sonido recibido, para el historial. */
data class HistoryEntry(val time: Long, val text: String)

data class CustomSound(val id: String, val label: String, val ext: String) {
    fun file(context: Context) = CustomAudio.fileFor(context, this)
}

/** Todo lo que la app recuerda, guardado en el propio celular. */
object Store {
    const val DEFAULT_SERVER = "https://ntfy.sh"
    private const val MAX_SEEN_IDS = 60
    private const val MAX_HISTORY = 15

    fun interface Listener {
        fun onStoreChanged()
    }

    private lateinit var prefs: SharedPreferences
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences("friendgame", Context.MODE_PRIVATE)
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
            .apply()
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
            )
        }
    }

    fun group(id: String): Group? = groups().firstOrNull { it.id == id }

    @Synchronized
    fun putGroup(group: Group) {
        saveGroups(groups().filter { it.id != group.id } + group)
    }

    /** Cambia el canal del grupo (al sacar a alguien) conservando el resto de los datos. */
    @Synchronized
    fun moveGroup(oldId: String, group: Group) {
        saveGroups(groups().filter { it.id != oldId && it.id != group.id } + group)
    }

    @Synchronized
    fun removeGroup(id: String) {
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
            CustomSound(o.optString("id"), o.optString("label"), o.optString("ext"))
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
        list.forEach { array.put(JSONObject().put("id", it.id).put("label", it.label).put("ext", it.ext)) }
        prefs.edit().putString("custom_sounds", array.toString()).apply()
        notifyChanged()
    }
}
