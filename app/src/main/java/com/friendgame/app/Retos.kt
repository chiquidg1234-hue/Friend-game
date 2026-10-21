package com.friendgame.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import org.json.JSONObject

/**
 * Retos: alguien elige un sonido y una hora, y a esa hora suena en los celulares de todo el chat
 * a la vez. En «El más rápido» cada uno manda cuánto tardó en detenerlo y gana el más veloz.
 * Cada celular agenda su propia alarma, así suena aunque la app esté cerrada.
 */
object Retos {
    const val SURPRISE = "sorpresa"
    const val FASTEST = "rapido"

    class Kind(val key: String, val emoji: String, val title: String, val text: String)

    val KINDS = listOf(
        Kind(SURPRISE, "⏰", "Sonido sorpresa", "Suena en todos los celulares a la vez, a la hora que elijas."),
        Kind(FASTEST, "⚡", "El más rápido", "Cuando suene, gana quien lo detenga primero."),
    )

    fun kindOf(key: String): Kind = KINDS.firstOrNull { it.key == key } ?: KINDS[0]

    /** «30 s», «5 min», «1 h 30 min». */
    fun delayText(seconds: Long): String {
        val parts = listOfNotNull(
            (seconds / 3600).takeIf { it > 0 }?.let { "$it h" },
            (seconds % 3600 / 60).takeIf { it > 0 }?.let { "$it min" },
            (seconds % 60).takeIf { it > 0 }?.let { "$it s" },
        )
        return parts.joinToString(" ").ifEmpty { "0 s" }
    }

    /** Opciones rápidas de la pantalla, en segundos (también se puede elegir otro tiempo o una hora). */
    val DELAYS_S = listOf(30L, 60L, 5 * 60L, 10 * 60L, 15 * 60L, 30 * 60L, 3600L, 2 * 3600L)
    const val DEFAULT_DELAY_S = 10 * 60L

    const val MIN_DELAY_S = 10L
    const val MAX_DELAY_S = 6 * 3600L
    /** Si el celular estaba apagado a la hora del reto, suena igual hasta un minuto tarde. */
    private const val LATE_GRACE_MS = 60_000L
    /** Tiempo para que lleguen los tiempos de todos antes de cerrar la tabla. */
    private const val RESULTS_WINDOW_MS = 90_000L
    /** Nadie puede tener más de esto esperando a la vez en tu celular (contra el spam). */
    private const val MAX_WAITING_PER_SENDER = 3

    const val ACTION_FIRE = "com.friendgame.app.RETO_FIRE"
    const val ACTION_CLOSE = "com.friendgame.app.RETO_CLOSE"
    const val EXTRA_ID = "reto_id"

    /** Crea un reto propio y devuelve el mensaje para mandar al chat (fuera del hilo de la pantalla). */
    fun create(context: Context, chatKey: String, kind: String, soundKey: String, delaySeconds: Long): JSONObject {
        val id = Codes.random(12)
        val reto = Reto(id, chatKey, kind, soundKey, System.currentTimeMillis() + delaySeconds * 1000, Store.myCode, Store.myName)
        Store.putReto(reto)
        Chats.card(chatKey, ChatMessage.RETO, id, Store.myCode, Store.myName, unread = false)
        schedule(context, reto)
        Points.onRetoCreated()
        val payload = Relay.payload("reto").put("id", id).put("k", kind).put("s", soundKey).put("in", delaySeconds)
        if (Chats.isGroup(chatKey)) payload.put("g", Chats.groupIdOf(chatKey))
        return payload
    }

    /** Llegó un reto de otra persona. [sentAgoMs]: hace cuánto lo mandó, según el servidor. */
    fun receive(context: Context, chatKey: String, from: String, name: String, payload: JSONObject, sentAgoMs: Long) {
        val id = payload.optString("id").takeIf { it.matches(Regex("[a-z0-9]{8,24}")) } ?: return
        val kind = payload.optString("k").takeIf { k -> KINDS.any { it.key == k } } ?: return
        val sound = Sounds.byKey(payload.optString("s")) ?: return
        val delay = payload.optLong("in")
        if (delay !in MIN_DELAY_S..MAX_DELAY_S) return
        if (Store.reto(id) != null) return
        if (Store.retos().count { it.from == from && it.state == Reto.WAITING } >= MAX_WAITING_PER_SENDER) return
        val remaining = delay * 1000 - sentAgoMs
        val state = if (remaining < -LATE_GRACE_MS) Reto.MISSED else Reto.WAITING
        val reto = Reto(id, chatKey, kind, sound.key, System.currentTimeMillis() + remaining, from, name, state)
        Store.putReto(reto)
        val unread = chatKey != Chats.visibleKey
        Chats.card(chatKey, ChatMessage.RETO, id, from, name, unread)
        if (state == Reto.WAITING) {
            schedule(context, reto)
            if (unread) Chats.notifyNew(context, chatKey)
        }
    }

    /** Quien lo creó lo cancela antes de que suene. Devuelve el mensaje para el chat. */
    fun cancel(context: Context, id: String): JSONObject? {
        val reto = Store.updateReto(id) { if (it.state == Reto.WAITING && it.from == Store.myCode) it.copy(state = Reto.CANCELLED) else null }
            ?: return null
        cancelAlarm(context, id)
        val payload = Relay.payload("retono").put("id", id)
        if (Chats.isGroup(reto.chat)) payload.put("g", Chats.groupIdOf(reto.chat))
        return payload
    }

    fun onCancelled(context: Context, from: String, payload: JSONObject) {
        val id = payload.optString("id")
        Store.updateReto(id) { if (it.from == from && it.state == Reto.WAITING) it.copy(state = Reto.CANCELLED) else null } ?: return
        cancelAlarm(context, id)
    }

    // ---- Alarmas ----

    private fun pending(context: Context, action: String, id: String): PendingIntent {
        val intent = Intent(context, RetoReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context, (action + id).hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Puntual si Android lo permite; si no, aproximada (y el servicio revisa cada 45 s). */
    fun schedule(context: Context, reto: Reto) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = pending(context, ACTION_FIRE, reto.id)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reto.at, intent)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reto.at, intent)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reto.at, intent)
        }
    }

    private fun cancelAlarm(context: Context, id: String) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(context, ACTION_FIRE, id))
    }

    /** Al reiniciar el celular Android borra las alarmas: se vuelven a poner. */
    fun rescheduleAll(context: Context) {
        Store.retos().filter { it.state == Reto.WAITING }.forEach { schedule(context, it) }
        checkDue(context)
    }

    fun puntualAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    /** Suena lo que ya tocaba y cierra las tablas de tiempos vencidas. */
    fun checkDue(context: Context) {
        val now = System.currentTimeMillis()
        Store.retos().forEach { reto ->
            when {
                reto.state == Reto.WAITING && reto.at <= now -> fire(context, reto.id)
                reto.state == Reto.RANG && reto.at + RESULTS_WINDOW_MS <= now -> close(reto.id)
            }
        }
    }

    // ---- Cuando suena ----

    fun fire(context: Context, id: String) {
        val reto = Store.updateReto(id) { if (it.state == Reto.WAITING) it.copy(state = Reto.RANG) else null } ?: return
        cancelAlarm(context, id)
        if (System.currentTimeMillis() - reto.at > LATE_GRACE_MS) {
            Store.updateReto(id) { it.copy(state = Reto.MISSED) }
            return
        }
        // El cierre de la tabla de tiempos, por si nadie abre la app.
        context.getSystemService(AlarmManager::class.java)
            ?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reto.at + RESULTS_WINDOW_MS, pending(context, ACTION_CLOSE, id))

        val title = Chats.title(reto.chat) ?: return
        val sound = Sounds.byKey(reto.sound) ?: return
        val muted = Chats.group(reto.chat)?.muted == true || Store.friend(reto.from)?.muted == true
        when {
            !Store.receiving -> Store.addHistory("Reto en $title · ${sound.label} (no sonó: la recepción estaba en pausa)")
            muted -> Store.addHistory("Reto en $title · ${sound.label} (no sonó: lo tienes silenciado)")
            context.getSystemService(AudioManager::class.java)?.mode == AudioManager.MODE_IN_CALL ->
                Store.addHistory("Reto en $title · ${sound.label} (no sonó: estabas en una llamada)")
            else -> {
                val group = Chats.group(reto.chat)
                val info = Player.NowPlaying(
                    "reto-$id", reto.from, "Reto en $title", sound.label, sound.key, group?.id, retoId = id,
                    loop = reto.kind == FASTEST,
                )
                Notifs.soundReceived(context, "⚡ Reto en $title", sound.label, canStop = true)
                Player.play(context, Player.Source.Asset(sound.asset), info, incoming = true)
                Store.addHistory("Reto en $title · ${sound.label}")
                Points.onRetoPlayed()
            }
        }
    }

    /** Detuviste el sonido de un reto: en «El más rápido» se manda tu tiempo. */
    fun onStopped(context: Context, id: String, elapsedMs: Long) {
        val reto = Store.reto(id) ?: return
        if (reto.kind != FASTEST || reto.state != Reto.RANG) return
        if (reto.results.any { it.code == Store.myCode }) return
        val ms = elapsedMs.coerceIn(0, Player.MAX_PLAY_MS)
        Store.updateReto(id) { it.copy(results = it.results + RetoResult(Store.myCode, Store.myName, ms)) }
        Chats.info(reto.chat, "Lo detuviste en ${seconds(ms)}")
        val payload = Relay.payload("retores").put("id", id).put("ms", ms)
        if (Chats.isGroup(reto.chat)) payload.put("g", Chats.groupIdOf(reto.chat))
        Thread {
            try {
                Relay.publishTopic(Chats.topic(reto.chat), payload)
            } catch (_: Exception) {
            }
        }.start()
    }

    /** Tiempo de otra persona. Solo vale si llega por el mismo chat del reto y antes de cerrar la tabla. */
    fun onResult(chatKey: String, from: String, name: String, payload: JSONObject) {
        val id = payload.optString("id")
        val ms = payload.optLong("ms", -1)
        if (ms !in 0..Player.MAX_PLAY_MS) return
        val reto = Store.reto(id) ?: return
        if (reto.chat != chatKey || reto.kind != FASTEST) return
        if (reto.state != Reto.WAITING && reto.state != Reto.RANG) return
        if (reto.results.any { it.code == from }) return
        Store.updateReto(id) { it.copy(results = it.results + RetoResult(from, name, ms)) }
        Chats.info(reto.chat, "$name lo detuvo en ${seconds(ms)}")
    }

    /** Cierra la tabla: anuncia al ganador y da los puntos según tu lugar. */
    fun close(id: String) {
        val reto = Store.updateReto(id) { if (it.state == Reto.RANG) it.copy(state = Reto.DONE) else null } ?: return
        if (reto.kind != FASTEST) return
        val ranking = ranking(reto)
        val winner = ranking.firstOrNull() ?: return
        val who = if (winner.code == Store.myCode) "¡Ganaste tú" else "Ganó ${winner.name}"
        Chats.info(reto.chat, "🏆 $who con ${seconds(winner.ms)}!")
        val place = ranking.indexOfFirst { it.code == Store.myCode }
        if (place >= 0) Points.onRetoPlace(place + 1, ranking.size)
    }

    fun ranking(reto: Reto): List<RetoResult> = reto.results.sortedBy { it.ms }

    fun seconds(ms: Long): String = "%.1f s".format(java.util.Locale("es"), ms / 1000.0)
}

/** Alarma de un reto: hace sonar el sonido o cierra la tabla de tiempos. */
class RetoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        val id = intent.getStringExtra(Retos.EXTRA_ID) ?: return
        when (intent.action) {
            Retos.ACTION_FIRE -> {
                // Que el celular no se duerma antes de que empiece a sonar.
                context.getSystemService(PowerManager::class.java)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "friendgame:reto")
                    ?.acquire(30_000L)
                ListenService.start(context)
                Retos.fire(context, id)
            }
            Retos.ACTION_CLOSE -> Retos.close(id)
        }
    }
}
