package com.friendgame.app

import android.content.Context
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/**
 * Candado: un acuerdo entre dos amigos. Uno lo propone por unos minutos y el otro lo acepta.
 * Mientras dura, ninguno de los dos puede detener, silenciar, eliminar ni bloquear al otro, ni
 * pausar la recepción. Los límites de siempre siguen: cada sonido dura como mucho 10 segundos y
 * nadie puede mandar más de 4 cada 20 segundos.
 */
object Locks {
    /** Opciones de la pantalla, en minutos. */
    val MINUTES = listOf(5, 10, 15, 30, 60)
    const val DEFAULT_MINUTES = 15
    private const val MAX_MINUTES = 120
    /** Una propuesta sin respuesta vence a los 10 minutos. */
    private const val ASK_VALID_MS = 10 * 60_000L

    fun activeWith(code: String): Lock? = Store.locks().lastOrNull { it.friend == code && it.isActive }

    fun anyActive(): Lock? = Store.locks().filter { it.isActive }.maxByOrNull { it.until }

    fun isPending(lock: Lock): Boolean =
        (lock.state == Lock.OFFERED || lock.state == Lock.ASKED) && System.currentTimeMillis() - lock.created < ASK_VALID_MS

    fun expiresAt(lock: Lock): Long = lock.created + ASK_VALID_MS

    fun pendingWith(code: String): Lock? = Store.locks().lastOrNull { it.friend == code && isPending(it) }

    /** Un sonido que no se puede detener: viene directo del amigo con el que hay candado. */
    fun blocksStop(info: Player.NowPlaying): Boolean =
        info.retoId == null && info.groupId == null && info.fromCode.isNotEmpty() && activeWith(info.fromCode) != null

    /** "18:40" */
    fun untilText(lock: Lock): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lock.until))

    /** "12:05" que faltan. */
    fun remainingText(until: Long): String {
        val left = ((until - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        return "%d:%02d".format(left / 60, left % 60)
    }

    /** Propones un candado. Devuelve el mensaje para el amigo. */
    fun propose(code: String, minutes: Int): JSONObject {
        val lock = Lock(Codes.random(12), code, minutes, Lock.OFFERED, System.currentTimeMillis())
        Store.putLock(lock)
        Chats.card(Chats.friendKey(code), ChatMessage.LOCK, lock.id, Store.myCode, Store.myName, unread = false)
        return Relay.payload("lockreq").put("id", lock.id).put("min", minutes)
    }

    fun onRequest(context: Context, from: String, name: String, payload: JSONObject) {
        val id = payload.optString("id").takeIf { it.matches(Regex("[a-z0-9]{8,24}")) } ?: return
        val minutes = payload.optInt("min")
        if (minutes !in 1..MAX_MINUTES || Store.lock(id) != null) return
        // Solo una propuesta pendiente por amigo: la nueva reemplaza a la anterior.
        pendingWith(from)?.let { old -> Store.updateLock(old.id) { it.copy(state = Lock.REJECTED) } }
        Store.putLock(Lock(id, from, minutes, Lock.ASKED, System.currentTimeMillis()))
        val key = Chats.friendKey(from)
        val unread = key != Chats.visibleKey
        Chats.card(key, ChatMessage.LOCK, id, from, name, unread)
        if (unread) Notifs.lockAsked(context, key, name, minutes)
    }

    /** Aceptas la propuesta: empieza ya. Devuelve el mensaje para el amigo. */
    fun accept(context: Context, id: String): JSONObject? {
        val current = Store.lock(id)?.takeIf { it.state == Lock.ASKED && isPending(it) } ?: return null
        val lock = Store.updateLock(id) {
            it.copy(state = Lock.ACTIVE, until = System.currentTimeMillis() + it.minutes * 60_000L)
        } ?: return null
        startListening(context, lock.friend)
        return Relay.payload("lockacc").put("id", id).put("min", current.minutes)
    }

    fun onAccepted(context: Context, from: String, payload: JSONObject, sentAgoMs: Long) {
        val id = payload.optString("id")
        val current = Store.lock(id)?.takeIf { it.friend == from && it.state == Lock.OFFERED && isPending(it) } ?: return
        val lock = Store.updateLock(id) {
            it.copy(state = Lock.ACTIVE, until = System.currentTimeMillis() + current.minutes * 60_000L - sentAgoMs)
        } ?: return
        startListening(context, from)
        val name = Store.friend(from)?.name ?: "Tu amigo"
        Chats.info(Chats.friendKey(from), "🔒 $name aceptó el candado: hasta las ${untilText(lock)}")
        Notifs.lockStarted(context, Chats.friendKey(from), name, untilText(lock))
    }

    /** El acuerdo vale para los dos: se vuelve a escuchar a ese amigo. */
    private fun startListening(context: Context, code: String) {
        Store.friend(code)?.let { if (it.muted) Store.putFriend(it.copy(muted = false)) }
        if (!Store.receiving) {
            Store.receiving = true
            ListenService.start(context)
        }
    }

    /** Rechazas una propuesta o cancelas la tuya. Devuelve el mensaje para el amigo. */
    fun reject(id: String): JSONObject? {
        Store.updateLock(id) { if (isPending(it)) it.copy(state = Lock.REJECTED) else null } ?: return null
        return Relay.payload("lockno").put("id", id)
    }

    fun onRejected(from: String, payload: JSONObject) {
        val id = payload.optString("id")
        val lock = Store.lock(id)?.takeIf { it.friend == from && isPending(it) } ?: return
        Store.updateLock(id) { it.copy(state = Lock.REJECTED) }
        val name = Store.friend(from)?.name ?: "Tu amigo"
        Chats.info(
            Chats.friendKey(from),
            if (lock.state == Lock.OFFERED) "$name dijo que no al candado" else "$name retiró su propuesta de candado",
        )
    }

    /** Candados que terminaron: se cumplieron y dan puntos. */
    fun finishDue() {
        val now = System.currentTimeMillis()
        Store.locks().filter { it.state == Lock.ACTIVE && it.until <= now }.forEach { lock ->
            Store.updateLock(lock.id) { if (it.state == Lock.ACTIVE) it.copy(state = Lock.DONE) else null } ?: return@forEach
            Points.onLockDone()
            Chats.info(Chats.friendKey(lock.friend), "✅ Candado cumplido. +20 puntos para los dos")
        }
    }
}
