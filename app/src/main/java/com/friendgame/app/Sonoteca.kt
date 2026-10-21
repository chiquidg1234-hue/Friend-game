package com.friendgame.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/** Un sonido de la Sonoteca. [author]: quien lo creó; [sharers]: quienes te lo pasaron. */
data class SharedSound(
    val id: String,
    val label: String,
    val emoji: String,
    val ext: String,
    val startMs: Int,
    val author: String,
    val authorName: String,
    val sharers: List<Member>,
    val time: Long,
    /** Lo publicaste tú. */
    val mine: Boolean = false,
    val liked: Boolean = false,
    val used: Boolean = false,
    /** Solo en los tuyos: a cuántos les gustó y cuántos lo usan. */
    val likes: Int = 0,
    val uses: Int = 0,
    /** Solo en los tuyos: quiénes ya dieron ❤️ ("l:código") o lo usaron ("u:código"), para no contar dos veces. */
    val fans: List<String> = emptyList(),
) {
    fun file(context: Context): File = File(Sonoteca.dir(context), "$id.$ext")
}

/**
 * Sonoteca: sonidos que comparten tus amigos y tus grupos, como los sonidos de TikTok pero solo
 * entre gente que aceptaste. Lo publicado viaja por los mismos canales que los sonidos; cada
 * celular guarda una copia. Se puede escuchar, darle ❤️, usarlo (pasa a "Tus audios", con el
 * nombre de quien lo creó) o pasarlo a tus amigos. Siempre se puede ocultar y denunciar.
 *
 * Protocolo: "sndpub" (id, label, e, st, by, byn, g?) con el audio adjunto, y "sndfan" (id, k:
 * "like" o "use") que le avisa al autor.
 */
object Sonoteca {
    private const val MAX_ITEMS = 30
    private const val MAX_BYTES = 2_600_000L
    private val ID = Regex("[a-z0-9]{8,24}")

    private lateinit var prefs: SharedPreferences

    @Synchronized
    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences("friendgame-sonoteca", Context.MODE_PRIVATE)
        }
    }

    fun dir(context: Context): File = File(context.filesDir, "sonoteca").apply { mkdirs() }

    // ---- Lo guardado ----

    @Synchronized
    fun all(): List<SharedSound> {
        val array = try {
            JSONArray(prefs.getString("items", "[]") ?: "[]")
        } catch (_: JSONException) {
            JSONArray()
        }
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            SharedSound(
                id = o.optString("id"),
                label = o.optString("label"),
                emoji = o.optString("e", "🎵"),
                ext = o.optString("x", "mp3"),
                startMs = o.optInt("st"),
                author = o.optString("by"),
                authorName = o.optString("byn"),
                sharers = Store.membersFrom(o.optJSONArray("sh") ?: JSONArray()),
                time = o.optLong("t"),
                mine = o.optBoolean("mine"),
                liked = o.optBoolean("liked"),
                used = o.optBoolean("used"),
                likes = o.optInt("likes"),
                uses = o.optInt("uses"),
                fans = (o.optJSONArray("fans") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) } },
            )
        }
    }

    fun get(id: String): SharedSound? = all().firstOrNull { it.id == id }

    private fun save(list: List<SharedSound>) {
        val array = JSONArray()
        list.forEach { s ->
            array.put(
                JSONObject()
                    .put("id", s.id).put("label", s.label).put("e", s.emoji).put("x", s.ext).put("st", s.startMs)
                    .put("by", s.author).put("byn", s.authorName).put("sh", Store.membersJson(s.sharers)).put("t", s.time)
                    .put("mine", s.mine).put("liked", s.liked).put("used", s.used).put("likes", s.likes).put("uses", s.uses)
                    .put("fans", JSONArray(s.fans))
            )
        }
        prefs.edit().putString("items", array.toString()).apply()
        Store.notifyChanged()
    }

    @Synchronized
    private fun put(item: SharedSound) {
        save(all().filter { it.id != item.id } + item)
    }

    @Synchronized
    private fun update(id: String, change: (SharedSound) -> SharedSound): SharedSound? {
        val current = get(id) ?: return null
        return change(current).also { put(it) }
    }

    /** Los que llegaron y todavía no viste (para el aviso en Sonar). */
    @Synchronized
    fun unseen(): List<SharedSound> {
        val seenAt = prefs.getLong("seen_at", 0)
        return all().filter { !it.mine && it.time > seenAt }
    }

    @Synchronized
    fun markSeen() {
        if (unseen().isEmpty()) return
        prefs.edit().putLong("seen_at", System.currentTimeMillis()).apply()
        Store.notifyChanged()
    }

    /** Tendencias: los que más amigos te pasaron, y entre ellos los más nuevos. */
    fun trending(): List<SharedSound> = all().filter { !it.mine }.sortedWith(compareByDescending<SharedSound> { it.sharers.size }.thenByDescending { it.time })

    fun newest(): List<SharedSound> = all().filter { !it.mine }.sortedByDescending { it.time }

    fun mine(): List<SharedSound> = all().filter { it.mine }.sortedByDescending { it.time }

    // ---- Ocultar ----

    @Synchronized
    fun hide(context: Context, id: String) {
        val item = get(id) ?: return
        item.file(context).delete()
        save(all().filter { it.id != id })
        val hidden = (prefs.getStringSet("hidden_ids", emptySet()) ?: emptySet()) + id
        prefs.edit().putStringSet("hidden_ids", hidden.toList().takeLast(200).toSet()).apply()
    }

    /** No volver a ver nada de esta persona en la Sonoteca. */
    @Synchronized
    fun hideAuthor(context: Context, code: String) {
        all().filter { it.author == code }.forEach { it.file(context).delete() }
        save(all().filter { it.author != code })
        val hidden = (prefs.getStringSet("hidden_authors", emptySet()) ?: emptySet()) + code
        prefs.edit().putStringSet("hidden_authors", hidden).apply()
    }

    private fun isHidden(id: String, author: String): Boolean =
        id in (prefs.getStringSet("hidden_ids", emptySet()) ?: emptySet()) ||
            author in (prefs.getStringSet("hidden_authors", emptySet()) ?: emptySet()) ||
            Store.friend(author)?.state == Friend.BLOCKED

    // ---- Lista de retiro ----

    fun takedownsDue(): Boolean = System.currentTimeMillis() - prefs.getLong("takedowns_at", 0) > 12 * 3600_000L

    /**
     * Baja la lista de retiro (ver Legal.TAKEDOWNS_URL) y oculta y borra lo que diga. Así se
     * cumple un aviso de derechos de autor en todos los celulares, aunque no haya un servidor
     * central con los audios. Hay que llamarlo fuera del hilo de la pantalla; si falla, no pasa nada.
     */
    fun syncTakedowns(context: Context) {
        prefs.edit().putLong("takedowns_at", System.currentTimeMillis()).apply()
        val conn = java.net.URL(Legal.TAKEDOWNS_URL).openConnection() as java.net.HttpURLConnection
        val text = try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "FriendGame")
            if (conn.responseCode != 200) return
            conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
        val list = try {
            JSONObject(text)
        } catch (_: JSONException) {
            return
        }
        val ids = list.optJSONArray("sounds") ?: JSONArray()
        val authors = list.optJSONArray("authors") ?: JSONArray()
        (0 until ids.length()).map { ids.optString(it) }.filter { it.matches(ID) }.forEach { hide(context, it) }
        (0 until authors.length()).mapNotNull { Codes.normalize(authors.optString(it)) }.forEach { hideAuthor(context, it) }
    }

    // ---- Llega algo (hilo del servicio) ----

    /**
     * Un amigo o alguien de tu grupo compartió un sonido. Se baja el audio enseguida (el servidor
     * lo guarda solo unas horas) y se valida igual que un audio propio: hasta 1 minuto.
     */
    fun receive(context: Context, from: String, fromName: String, payload: JSONObject, event: JSONObject) {
        val id = payload.optString("id").takeIf { it.matches(ID) } ?: return
        val author = Codes.normalize(payload.optString("by")) ?: from
        if (author == Store.myCode || isHidden(id, author)) return
        val existing = get(id)
        if (existing != null) {
            // Otro amigo te pasó el mismo sonido: sube en Tendencias.
            if (existing.sharers.none { it.code == from }) {
                update(id) { it.copy(sharers = it.sharers + Member(from, fromName)) }
            }
            return
        }
        val attachment = event.optJSONObject("attachment") ?: return
        val ext = extension(attachment.optString("name")) ?: return
        val label = payload.optString("label").trim().take(24).ifEmpty { "Sonido" }
        val emoji = Looks.cleanEmoji(payload.optString("e")) ?: "🎵"
        val authorName = payload.optString("byn").trim().take(24).ifEmpty { fromName }
        val item = SharedSound(
            id, label, emoji, ext, payload.optInt("st").coerceIn(0, CustomAudio.MAX_MS.toInt()),
            author, authorName, listOf(Member(from, fromName)), System.currentTimeMillis(),
        )
        val file = item.file(context)
        if (!Relay.download(attachment.optString("url"), file, MAX_BYTES)) return
        val duration = CustomAudio.durationOf(file)
        if (duration == null || duration > CustomAudio.MAX_MS + 300) {
            file.delete()
            return
        }
        synchronized(this) {
            if (get(id) != null) return
            put(item)
            prune(context)
        }
    }

    /** Se guardan los 30 más nuevos (los tuyos y los que usaste no se borran). */
    private fun prune(context: Context) {
        val all = all()
        val removable = all.filter { !it.mine && !it.used }.sortedBy { it.time }
        val extra = all.size - MAX_ITEMS
        if (extra <= 0) return
        val drop = removable.take(extra)
        drop.forEach { it.file(context).delete() }
        val ids = drop.map { it.id }.toSet()
        save(all.filter { it.id !in ids })
    }

    private fun extension(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "mp3"
        "ogg", "oga", "opus" -> "ogg"
        "wav" -> "wav"
        "m4a", "aac", "mp4" -> "m4a"
        else -> null
    }

    /** A un amigo le gustó o usa un sonido tuyo. Devuelve el texto para avisar, o null si ya contaba. */
    fun onFan(from: String, name: String, payload: JSONObject): String? {
        val id = payload.optString("id")
        val kind = payload.optString("k").takeIf { it == "like" || it == "use" } ?: return null
        val key = (if (kind == "like") "l:" else "u:") + from
        var text: String? = null
        synchronized(this) {
            val item = get(id)?.takeIf { it.mine && key !in it.fans } ?: return null
            val updated = update(id) {
                if (kind == "like") it.copy(likes = it.likes + 1, fans = it.fans + key)
                else it.copy(uses = it.uses + 1, fans = it.fans + key)
            } ?: return null
            Points.onSoundFan(updated.uses)
            text = if (kind == "like") "❤️ A $name le gustó tu sonido «${item.label}»" else "🎧 $name usa tu sonido «${item.label}»"
        }
        return text
    }

    // ---- Acciones (fuera del hilo de la pantalla) ----

    /** Dónde publicar: el canal de cada amigo y de cada grupo elegido. */
    class Destination(val topic: String, val groupId: String?)

    /**
     * Publica un audio: lo sube una sola vez y a los demás canales manda solo el enlace. Si es
     * tuyo queda en "Tuyos" con sus ❤️ y usos. Devuelve a cuántos canales llegó.
     */
    fun publish(
        context: Context,
        source: File,
        label: String,
        emoji: String,
        startMs: Int,
        destinations: List<Destination>,
        original: SharedSound? = null,
    ): Int {
        if (destinations.isEmpty() || !source.exists()) return 0
        val id = original?.id ?: Codes.random(12)
        val ext = source.extension.lowercase().ifEmpty { "mp3" }
        val author = original?.author ?: Store.myCode
        val authorName = original?.authorName ?: Store.myName
        fun payload(groupId: String?) = Relay.payload("sndpub").put("id", id).put("label", label.take(24)).put("e", emoji)
            .put("st", startMs).put("by", author).put("byn", authorName).apply { if (groupId != null) put("g", groupId) }
        val first = destinations.first()
        val url = Relay.publishFileTopicForUrl(first.topic, payload(first.groupId), source)
        var sent = 1
        destinations.drop(1).forEach { destination ->
            try {
                if (url != null) Relay.publishAttachTopic(destination.topic, payload(destination.groupId), url, "sonido.$ext")
                else Relay.publishFileTopic(destination.topic, payload(destination.groupId), source)
                sent++
            } catch (_: Exception) {
            }
        }
        if (original == null) {
            val item = SharedSound(id, label.take(24), emoji, ext, startMs, author, authorName, emptyList(), System.currentTimeMillis(), mine = true)
            source.copyTo(item.file(context), overwrite = true)
            put(item)
        }
        Points.onSoundShared()
        return sent
    }

    /** ❤️: devuelve el aviso para el autor (si es tu amigo), o null. */
    fun like(id: String): Pair<String, JSONObject>? {
        val item = update(id) { it.copy(liked = true) } ?: return null
        return fanMessage(item, "like")
    }

    /** Lo pasa a "Tus audios" con el nombre de quien lo creó. Devuelve el audio y el aviso para el autor. */
    fun use(context: Context, id: String): Pair<CustomSound, Pair<String, JSONObject>?>? {
        val item = get(id) ?: return null
        val file = item.file(context)
        if (!file.exists()) return null
        val custom = CustomSound(Codes.random(10), item.label, item.ext, item.startMs, credit = item.authorName)
        file.copyTo(custom.file(context), overwrite = true)
        Store.putCustomSound(custom)
        if (item.emoji != "🎵") Looks.setButton("c:${custom.id}", Looks.Look(emoji = item.emoji))
        update(id) { it.copy(used = true) }
        return custom to fanMessage(item, "use")
    }

    private fun fanMessage(item: SharedSound, kind: String): Pair<String, JSONObject>? {
        val author = Store.friend(item.author)?.takeIf { it.state == Friend.FRIEND } ?: return null
        return Relay.topicFor(author.code) to Relay.payload("sndfan").put("id", item.id).put("k", kind)
    }
}
