package com.friendgame.app

import android.content.Context
import org.json.JSONObject

/**
 * Chat con un amigo ("f:código") o con un grupo ("g:id"). Los mensajes viajan por el mismo canal
 * que los sonidos y se guardan solo en los celulares de quienes conversan.
 */
object Chats {
    const val MAX_TEXT = 500

    /** Chat abierto en pantalla ahora mismo: sus mensajes no avisan ni cuentan como no leídos. */
    @Volatile
    var visibleKey: String? = null

    fun friendKey(code: String) = "f:$code"

    fun groupKey(id: String) = "g:$id"

    fun isGroup(key: String) = key.startsWith("g:")

    fun codeOf(key: String) = key.removePrefix("f:")

    fun groupIdOf(key: String) = key.removePrefix("g:")

    fun group(key: String): Group? = if (isGroup(key)) Store.group(groupIdOf(key)) else null

    fun friend(key: String): Friend? = if (isGroup(key)) null else Store.friend(codeOf(key))?.takeIf { it.state == Friend.FRIEND }

    /** Nombre para mostrar, o null si ya no es tu amigo o saliste del grupo. */
    fun title(key: String): String? = group(key)?.name ?: friend(key)?.name

    fun topic(key: String): String =
        if (isGroup(key)) Relay.groupTopic(groupIdOf(key)) else Relay.topicFor(codeOf(key))

    /** Conversaciones posibles: amigos aceptados y grupos, las más recientes primero. */
    fun keys(): List<String> {
        val all = Store.friends().filter { it.state == Friend.FRIEND }.map { friendKey(it.code) } +
            Store.groups().map { groupKey(it.id) }
        val last = all.associateWith { Store.chat(it).lastOrNull()?.time ?: 0L }
        return all.sortedWith(compareByDescending<String> { last[it] }.thenBy { title(it)?.lowercase() })
    }

    /**
     * Manda un mensaje. Se guarda primero (para verlo enseguida) y si no sale se borra y se lanza
     * el error. Hay que llamarlo fuera del hilo de la pantalla.
     */
    fun send(key: String, text: String) {
        val clean = text.trim().take(MAX_TEXT)
        if (clean.isEmpty()) return
        val id = Codes.random(12)
        val message = ChatMessage(id, Store.myCode, Store.myName, clean, System.currentTimeMillis())
        Store.addChatMessage(key, message, unread = false)
        val payload = Relay.payload("msg").put("id", id).put("text", clean)
        if (isGroup(key)) payload.put("g", groupIdOf(key))
        try {
            Relay.publishTopic(topic(key), payload)
        } catch (e: Exception) {
            Store.removeChatMessage(key, id)
            throw e
        }
        Points.onChatSent()
    }

    /** Mensaje que llegó. [time]: cuándo se mandó, en el reloj de este celular. */
    fun receive(context: Context, key: String, from: String, name: String, payload: JSONObject, eventId: String, time: Long) {
        val text = payload.optString("text").trim().take(MAX_TEXT)
        if (text.isEmpty()) return
        val id = payload.optString("id").takeIf { it.matches(Regex("[a-z0-9]{6,24}")) } ?: eventId
        val unread = key != visibleKey
        val added = Store.addChatMessage(key, ChatMessage(id, from, name, text, time), unread)
        if (added && unread) notifyNew(context, key)
    }

    /** Aviso de la app dentro del chat ("Ana lo detuvo en 0,8 s"). */
    fun info(key: String, text: String, id: String = "i-" + Codes.random(10)) {
        Store.addChatMessage(key, ChatMessage(id, "", "", text, System.currentTimeMillis(), ChatMessage.INFO), unread = false)
    }

    /** Tarjeta de un reto o candado en el chat. */
    fun card(key: String, kind: String, ref: String, from: String, name: String, unread: Boolean) {
        Store.addChatMessage(key, ChatMessage("$kind-$ref", from, name, "", System.currentTimeMillis(), kind, ref), unread)
    }

    /** Texto corto de un mensaje para la lista de chats y las notificaciones. */
    fun preview(message: ChatMessage): String = when (message.kind) {
        ChatMessage.RETO -> "⚡ Reto: " + (Store.reto(message.ref)?.let { Retos.kindOf(it.kind).title } ?: "reto")
        ChatMessage.LOCK -> "🔒 Candado de ${Store.lock(message.ref)?.minutes ?: "?"} min"
        else -> message.text
    }

    fun notifyNew(context: Context, key: String) {
        if (group(key)?.muted == true) return
        val title = title(key) ?: return
        val unread = Store.unreadCounts()[key] ?: 1
        val lines = Store.chat(key).filter { it.from != Store.myCode && it.kind != ChatMessage.INFO }.takeLast(unread.coerceIn(1, 5))
            .map { message -> if (isGroup(key)) "${message.name}: ${preview(message)}" else preview(message) }
        Notifs.chat(context, key, title, lines, unread)
    }
}
