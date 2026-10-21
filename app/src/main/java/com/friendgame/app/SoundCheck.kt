package com.friendgame.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager

/**
 * Revisa qué puede impedir que se oiga un sonido en este celular ("No molestar", una llamada,
 * la batería…) y elige el volumen con el que conviene sonar.
 */
object SoundCheck {
    class Item(val ok: Boolean, val text: String)

    /**
     * Volumen de alarma: Android no lo silencia en modo silencio ni vibración, y [Player] lo sube
     * al máximo mientras suena. Antes de aceptar ese aviso (recién actualizada), si la alarma está
     * en 0 y el multimedia no, se usa el multimedia para que el sonido no se pierda.
     */
    fun useAlarm(context: Context): Boolean {
        if (Store.loudAccepted) return true
        val am = context.getSystemService(AudioManager::class.java) ?: return true
        return !(am.getStreamVolume(AudioManager.STREAM_ALARM) == 0 && am.getStreamVolume(AudioManager.STREAM_MUSIC) > 0)
    }

    private fun streamName(alarm: Boolean) = if (alarm) "alarma" else "multimedia"

    /** Motivo corto por el que el próximo sonido no se oiría, o null si todo bien. */
    fun silentReason(context: Context): String? {
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        if (!Store.loudAccepted) {
            val alarm = useAlarm(context)
            val stream = if (alarm) AudioManager.STREAM_ALARM else AudioManager.STREAM_MUSIC
            if (am.getStreamVolume(stream) == 0) return "el volumen de ${streamName(alarm)} está en 0"
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE) {
            return "«No molestar» está en silencio total"
        }
        return null
    }

    fun items(context: Context): List<Item> {
        val list = mutableListOf<Item>()
        list += when {
            !Store.receiving -> Item(false, "La recepción está en pausa: actívala arriba.")
            ListenService.connected -> Item(true, "Conectado: listo para recibir sonidos.")
            else -> Item(false, "Sin conexión con el servidor: revisa que tengas internet.")
        }

        val am = context.getSystemService(AudioManager::class.java)
        if (am != null) {
            val loud = Store.loudAccepted
            if (loud) {
                list += Item(true, "Suena al volumen máximo, también en silencio. Al terminar, el volumen vuelve a como estaba.")
            } else {
                val alarm = useAlarm(context)
                val stream = if (alarm) AudioManager.STREAM_ALARM else AudioManager.STREAM_MUSIC
                val volume = am.getStreamVolume(stream)
                val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
                val name = streamName(alarm)
                list += when {
                    volume == 0 -> Item(false, "El volumen de $name está en 0: no se va a oír. Súbelo.")
                    volume * 4 <= max -> Item(false, "El volumen de $name está bajo ($volume de $max).")
                    else -> Item(true, "Volumen de $name: $volume de $max.")
                }
                list += Item(false, "Todavía no aceptas el volumen máximo: actívalo en «Recibir sonidos».")
            }
            list += if (am.mode == AudioManager.MODE_IN_CALL) {
                Item(false, "Estás en una llamada: durante las llamadas los sonidos no suenan.")
            } else {
                Item(true, "No estás en una llamada.")
            }
            val external = mutableSetOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
            )
            if (Build.VERSION.SDK_INT >= 31) {
                external += AudioDeviceInfo.TYPE_BLE_HEADSET
                external += AudioDeviceInfo.TYPE_BLE_SPEAKER
            }
            val connected = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in external }
            list += when {
                !connected -> Item(true, "El sonido sale por el altavoz principal del celular.")
                Build.VERSION.SDK_INT >= 28 -> Item(true, "Hay auriculares o un parlante conectado, pero el sonido sale por el altavoz del celular.")
                else -> Item(true, "Hay auriculares o un parlante conectado; con volumen de alarma suena también por el altavoz.")
            }
        }

        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm != null) {
            list += when (nm.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_ALL, NotificationManager.INTERRUPTION_FILTER_UNKNOWN ->
                    Item(true, "«No molestar» está desactivado.")
                NotificationManager.INTERRUPTION_FILTER_NONE ->
                    Item(false, "«No molestar» está en silencio total: no deja sonar nada.")
                else -> Item(useAlarm(context), "«No molestar» está activado: revisa que deje sonar las alarmas.")
            }
        }

        val power = context.getSystemService(PowerManager::class.java)
        list += if (power == null || power.isIgnoringBatteryOptimizations(context.packageName)) {
            Item(true, "La batería no limita a Friend Game.")
        } else {
            Item(false, "El ahorro de batería puede cortar la conexión con la pantalla apagada: toca «Permitir» en la tarjeta de batería.")
        }

        if (Build.VERSION.SDK_INT >= 33) {
            list += if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                Item(true, "Las notificaciones están activadas.")
            } else {
                Item(false, "Las notificaciones están desactivadas: no verás quién te manda cada sonido.")
            }
        }

        Store.lastProblem.takeIf { it.isNotEmpty() }?.let { list += Item(false, it) }
        return list
    }
}
