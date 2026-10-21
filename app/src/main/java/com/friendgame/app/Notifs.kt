package com.friendgame.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notifs {
    private const val CH_SERVICE = "listening"
    private const val CH_SOUNDS = "sounds"
    private const val CH_SOCIAL = "friends"
    private const val CH_CHAT = "chat"
    private const val CH_REMIND = "reminders"

    const val ID_SERVICE = 1
    private const val ID_SOUND = 2
    private const val ID_REMIND = 3
    private const val ID_SOCIAL_BASE = 100
    private const val ID_CHAT_BASE = 100_000

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val service = NotificationChannel(CH_SERVICE, "Escuchando (conexión)", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Aviso fijo mientras Friend Game está listo para recibir sonidos."
            setShowBadge(false)
        }
        val sounds = NotificationChannel(CH_SOUNDS, "Sonidos recibidos", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Quién te mandó un sonido, con botón para detenerlo."
            setSound(null, null)
            enableVibration(false)
        }
        val social = NotificationChannel(CH_SOCIAL, "Solicitudes de amistad", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Solicitudes nuevas y solicitudes aceptadas."
        }
        val chat = NotificationChannel(CH_CHAT, "Mensajes, retos y candados", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Mensajes del chat, retos nuevos y propuestas de candado."
        }
        val remind = NotificationChannel(CH_REMIND, "Recordatorios", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A lo más uno al día, de tarde: si tu racha, la de un amigo o tu mascota te necesitan."
        }
        nm.createNotificationChannels(listOf(service, sounds, social, chat, remind))
    }

    /** Pestaña que abre la app al tocar el aviso ("FRIENDS", "GROUPS"). */
    const val EXTRA_TAB = "open_tab"

    /** Chat que abre la app al tocar el aviso ("f:código" o "g:id"). */
    const val EXTRA_CHAT = "open_chat"

    /** Devolvérsela: abre Sonar con ese destino elegido ("f:código" o "g:id"). */
    const val EXTRA_REPLY = "reply_to"

    private fun openAppIntent(context: Context, tab: String? = null, chat: String? = null, reply: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (tab != null) intent.putExtra(EXTRA_TAB, tab)
        if (chat != null) intent.putExtra(EXTRA_CHAT, chat)
        if (reply != null) intent.putExtra(EXTRA_REPLY, reply)
        return PendingIntent.getActivity(
            context, (reply?.let { "reply:$it" } ?: chat ?: tab)?.hashCode() ?: 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, ActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun action(context: Context, title: String, action: String, requestCode: Int): Notification.Action =
        Notification.Action.Builder(null, title, actionIntent(context, action, requestCode)).build()

    fun serviceNotification(context: Context, connected: Boolean): Notification =
        Notification.Builder(context, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_sound)
            .setContentTitle(if (connected) "Listo para recibir sonidos" else "Conectando…")
            .setContentText("Toca para abrir Friend Game")
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(action(context, "Pausar recepción", ActionReceiver.ACTION_PAUSE, 1))
            .build()

    fun updateService(context: Context, connected: Boolean) {
        notify(context, ID_SERVICE, serviceNotification(context, connected))
    }

    /**
     * [canStop] false: hay un candado con quien lo mandó y no se muestra el botón Detener.
     * [replyTo]: a quién devolvérsela (botón «↩️ Devolvérsela», que abre Sonar con ese destino).
     */
    fun soundReceived(context: Context, fromName: String, label: String, canStop: Boolean = true, replyTo: String? = null) {
        val builder = Notification.Builder(context, CH_SOUNDS)
            .setSmallIcon(R.drawable.ic_stat_sound)
            .setContentTitle(if (fromName.startsWith("⚡")) fromName else "🔊 $fromName te mandó un sonido")
            .setContentText(if (canStop) label else "$label · 🔒 candado activo")
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setTimeoutAfter(20_000)
            .setCategory(Notification.CATEGORY_MESSAGE)
        if (canStop) builder.addAction(action(context, "⏹ Detener", ActionReceiver.ACTION_STOP, 2))
        if (replyTo != null) {
            builder.addAction(Notification.Action.Builder(null, "↩️ Devolvérsela", openAppIntent(context, "SOUND", reply = replyTo)).build())
        }
        notify(context, ID_SOUND, builder.build())
    }

    /** Mensajes nuevos de un chat: un aviso por conversación con las últimas líneas. */
    fun chat(context: Context, key: String, title: String, lines: List<String>, count: Int) {
        val style = Notification.InboxStyle()
        lines.forEach { style.addLine(it) }
        val n = Notification.Builder(context, CH_CHAT)
            .setSmallIcon(R.drawable.ic_stat_sound)
            .setContentTitle(title)
            .setContentText(lines.lastOrNull() ?: "")
            .setStyle(style)
            .setNumber(count)
            .setContentIntent(openAppIntent(context, chat = key))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        notify(context, chatId(key), n)
    }

    fun cancelChat(context: Context, key: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(chatId(key))
    }

    private fun chatId(key: String) = ID_CHAT_BASE + (key.hashCode() and 0xFFFF)

    fun lockAsked(context: Context, key: String, name: String, minutes: Int) {
        game(context, key, "🔒 $name te propone un candado de $minutes min", "Si aceptan, ninguno podrá detener los sonidos del otro.")
    }

    fun lockStarted(context: Context, key: String, name: String, until: String) {
        game(context, key, "🔒 Candado con $name activo", "Hasta las $until: ninguno puede detener los sonidos del otro.")
    }

    private fun game(context: Context, key: String, title: String, text: String) {
        val n = Notification.Builder(context, CH_CHAT)
            .setSmallIcon(R.drawable.ic_stat_sound)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(context, chat = key))
            .setAutoCancel(true)
            .build()
        notify(context, chatId(key) + 1, n)
    }

    fun cancelSound(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID_SOUND)
    }

    fun friendRequest(context: Context, code: String, name: String) {
        social(context, code, "👋 $name te mandó una solicitud de amistad", "Ábrela para aceptarla o rechazarla.", "FRIENDS")
    }

    fun requestAccepted(context: Context, code: String, name: String) {
        social(context, code, "🎉 $name aceptó tu solicitud", "Ya se pueden mandar sonidos.", "FRIENDS")
    }

    fun groupJoined(context: Context, name: String, groupName: String) {
        social(context, "join-$groupName", "👥 $name se unió a «$groupName»", "Desde Grupos puedes ver a los miembros o sacar a alguien.", "GROUPS")
    }

    fun groupGone(context: Context, groupId: String, title: String) {
        social(context, "gone-$groupId", "👥 $title", "Ya no recibirás sonidos de ese grupo.", "GROUPS")
    }

    /** El recordatorio del día (ver Reminders). */
    fun reminder(context: Context, title: String, text: String) {
        val n = Notification.Builder(context, CH_REMIND)
            .setSmallIcon(R.drawable.ic_stat_sound)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent(context, "ME"))
            .setAutoCancel(true)
            .build()
        notify(context, ID_REMIND, n)
    }

    /** A un amigo le gustó o usa un sonido que compartiste en la Sonoteca. */
    fun sonoteca(context: Context, id: String, title: String, text: String) {
        social(context, "fan-$id", title, text, "ME")
    }

    /** Tras actualizarse sola, Android cierra la app: este aviso permite volver a abrirla. */
    fun appUpdated(context: Context, versionName: String) {
        social(context, "update", "✅ Friend Game se actualizó a la versión $versionName", "Toca para abrirla.")
    }

    private fun social(context: Context, code: String, title: String, text: String, tab: String? = null) {
        val n = Notification.Builder(context, CH_SOCIAL)
            .setSmallIcon(R.drawable.ic_stat_sound)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent(context, tab))
            .setAutoCancel(true)
            .build()
        notify(context, ID_SOCIAL_BASE + (code.hashCode() and 0xFFFF), n)
    }

    private fun notify(context: Context, id: Int, notification: Notification) {
        try {
            context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
        } catch (_: SecurityException) {
            // Sin permiso de notificaciones: la app sigue funcionando igual.
        }
    }
}
