package com.friendgame.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Vuelve a escuchar al encender el celular o al actualizar la app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> ListenService.start(context)
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
                Player.stop()
                Notifs.cancelSound(context)
            }
            ACTION_PAUSE -> {
                Player.stop()
                Store.receiving = false
                ListenService.stop(context)
            }
        }
    }
}
