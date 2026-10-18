package com.friendgame.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/** Vuelve a escuchar al encender el celular o al actualizar la app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                ListenService.start(context)
                Store.init(context)
                Retos.rescheduleAll(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                ListenService.start(context)
                Retos.rescheduleAll(context)
                Notifs.createChannels(context)
                Notifs.appUpdated(context, Updater.currentVersionName(context))
            }
        }
    }
}

/** Botones de las notificaciones: detener el sonido o pausar la recepción. */
class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STOP = "com.friendgame.app.STOP_SOUND"
        const val ACTION_PAUSE = "com.friendgame.app.PAUSE_RECEIVING"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        when (intent.action) {
            ACTION_STOP -> {
                val now = Player.now
                if (now != null && Locks.blocksStop(now)) {
                    Toast.makeText(context, "🔒 Hay un candado: este sonido no se puede detener", Toast.LENGTH_LONG).show()
                    return
                }
                Player.stopByUser(context)
                Notifs.cancelSound(context)
            }
            ACTION_PAUSE -> {
                val lock = Locks.anyActive()
                if (lock != null) {
                    Toast.makeText(context, "🔒 Hay un candado activo hasta las ${Locks.untilText(lock)}: no se puede pausar", Toast.LENGTH_LONG).show()
                    return
                }
                Player.stop()
                Store.receiving = false
                ListenService.stop(context)
            }
        }
    }
}

/** Respuesta del instalador de Android al actualizar la app desde dentro. */
class InstallReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.friendgame.app.INSTALL_RESULT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android pide confirmación: se abre su pantalla de "¿Actualizar esta app?".
            val confirm = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (_: Exception) {
                }
            }
        }
        Updater.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
