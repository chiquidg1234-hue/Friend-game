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

    const val ID_SERVICE = 1
    private const val ID_SOUND = 2
    private const val ID_SOCIAL_BASE = 100

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
        nm.createNotificationChannels(listOf(service, sounds, social))
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, 0, intent,
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

    fun soundReceived(context: Context, fromName: String, label: String) {
        val n = Notification.Builder(context, CH_SOUNDS)
            .setSmallIcon(R.drawable.ic_stat_sound)
            .setContentTitle("🔊 $fromName te mandó un sonido")
            .setContentText(label)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setTimeoutAfter(20_000)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .addAction(action(context, "⏹ Detener", ActionReceiver.ACTION_STOP, 2))
            .build()
        notify(context, ID_SOUND, n)
    }

    fun cancelSound(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID_SOUND)
    }

    fun friendRequest(context: Context, code: String, name: String) {
        social(context, code, "👋 $name te mandó una solicitud de amistad", "Ábrela para aceptarla o rechazarla.")
    }

    fun requestAccepted(context: Context, code: String, name: String) {
        social(context, code, "🎉 $name aceptó tu solicitud", "Ya se pueden mandar sonidos.")
    }

    fun groupJoined(context: Context, name: String, groupName: String) {
        social(context, "join-$groupName", "👥 $name se unió a «$groupName»", "Desde Grupos puedes ver a los miembros o sacar a alguien.")
    }

    fun groupGone(context: Context, groupId: String, title: String) {
        social(context, "gone-$groupId", "👥 $title", "Ya no recibirás sonidos de ese grupo.")
    }

    /** Tras actualizarse sola, Android cierra la app: este aviso permite volver a abrirla. */
    fun appUpdated(context: Context, versionName: String) {
        social(context, "update", "✅ Friend Game se actualizó a la versión $versionName", "Toca para abrirla.")
    }

    private fun social(context: Context, code: String, title: String, text: String) {
        val n = Notification.Builder(context, CH_SOCIAL)
            .setSmallIcon(R.drawable.ic_stat_sound)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent(context))
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
