package com.friendgame.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.io.File

/**
 * Reproduce un sonido a la vez. Cualquier sonido se puede detener siempre desde este celular
 * (app, notificación). Además quien lo mandó puede cortarlo antes con su botón de detener.
 *
 * Los sonidos recibidos salen por el altavoz principal y, si la persona lo aceptó
 * ([Store.loudAccepted]), con el volumen de alarma al máximo; al terminar se devuelve el
 * volumen que tenía.
 */
object Player {
    /** Nadie puede tener sonando tu celular más de esto, aunque el archivo sea más largo. */
    const val MAX_PLAY_MS = 10_000L

    sealed class Source {
        class Asset(val path: String) : Source()
        class FromFile(val file: File) : Source()
    }

    class NowPlaying(
        val playbackId: String,
        val fromCode: String,
        val fromName: String,
        val label: String,
        /** Clave del sonido integrado ("sirena"…) o "custom". */
        val soundKey: String = "",
        val groupId: String? = null,
        /** Si suena por un reto, su id (al detenerlo se manda el tiempo en «El más rápido»). */
        val retoId: String? = null,
        /** Se repite hasta que lo detengan o pasen 10 s (en «El más rápido» hay que alcanzar a tocarlo). */
        val loop: Boolean = false,
        /** Poder con el que lo mandaron ("super", "eco") o "". */
        val power: String = "",
        /** Audio propio: desde qué milisegundo suena (el remitente elige la parte que quiere). */
        val startMs: Int = 0,
        /**
         * Prueba «como le llega»: suena en tu celular igual que en el de [trialFor] (pantalla
         * completa, volumen de alarma, 10 s, poder listo), sin mandarle nada.
         */
        val trialFor: String = "",
        /** Vibe (emoción) del sonido, para que la mascota la reconozca. Ver [Vibes]. */
        val vibe: String = "",
    ) {
        val isPreview: Boolean get() = fromCode.isEmpty()

        /** Cuándo empezó a sonar y cuánto va a durar (para la barra de progreso). */
        var startedAt = 0L
            internal set
        var durationMs = MAX_PLAY_MS
            internal set
    }

    @Volatile
    var now: NowPlaying? = null
        private set

    /** De qué archivo es lo que suena (para su envolvente, ver [AudioAnalysis]). */
    private var nowKey = ""

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var appContext: Context? = null
    private val timeout = Runnable { stopOnMain() }

    private var attributes: AudioAttributes = attributesFor(alarm = false)

    /**
     * Los sonidos de amigos usan el volumen de alarma, que Android no silencia en modo silencio ni
     * vibración. Escuchar tus propios sonidos usa el volumen multimedia, sin tocarlo.
     */
    private fun attributesFor(alarm: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** [incoming]: suena como un sonido recibido (con el volumen que corresponda), no como vista previa. */
    fun play(context: Context, source: Source, info: NowPlaying, incoming: Boolean = !info.isPreview) {
        val app = context.applicationContext
        main.post { playOnMain(app, source, info, incoming) }
    }

    /** Escuchar un sonido en tu propio celular antes de mandarlo. [vibe]: su categoría (el fondo y la mascota reaccionan). */
    fun preview(context: Context, source: Source, label: String, startMs: Int = 0, vibe: String = "") {
        play(context, source, NowPlaying(Codes.random(12), "", "", label, startMs = startMs, vibe = vibe))
    }

    /**
     * Cuánto suena ahora, de 0 a 1 (la envolvente en la posición actual). La mascota y el fondo
     * reaccionan con esto. Mientras no está la envolvente, un vaivén suave. Solo en el hilo principal.
     */
    fun level(): Float {
        val info = now ?: return 0f
        val mp = player ?: return 0f
        val position = try {
            mp.currentPosition
        } catch (_: IllegalStateException) {
            return 0f
        }
        val env = AudioAnalysis.envelope(nowKey)
        if (env == null || env.isEmpty()) {
            val t = (SystemClock.uptimeMillis() - info.startedAt) / 1000f
            return 0.35f + 0.25f * kotlin.math.sin(t * 9f) * kotlin.math.sin(t * 2.3f)
        }
        return env.getOrElse(position / AudioAnalysis.ENV_MS) { 0f }
    }

    fun stop() {
        main.post { stopOnMain() }
    }

    /**
     * La persona tocó Detener (en la app o en la notificación). No hace nada si hay un candado
     * con quien lo mandó. En un reto, cuenta cuánto tardó.
     */
    fun stopByUser(context: Context) {
        val app = context.applicationContext
        main.post {
            val current = now ?: return@post
            if (Locks.blocksStop(current)) return@post
            val elapsed = SystemClock.elapsedRealtime() - current.startedAt
            stopOnMain()
            current.retoId?.let { Retos.onStopped(app, it, elapsed) }
        }
    }

    /** Señal de "detener" del remitente: solo corta su propio sonido, nunca otro. */
    fun stopIfMatches(playbackId: String, fromCode: String) {
        main.post {
            val current = now ?: return@post
            if (playbackId.isNotEmpty() && current.playbackId == playbackId && current.fromCode == fromCode) {
                stopOnMain()
            }
        }
    }

    fun stopIfFrom(fromCode: String) {
        main.post { if (now?.fromCode == fromCode) stopOnMain() }
    }

    private fun playOnMain(app: Context, source: Source, info: NowPlaying, incoming: Boolean) {
        // El aviso del sonido nuevo ya está puesto (comparten el mismo aviso): no se quita.
        stopOnMain(cancelNotification = false)
        appContext = app
        val alarm = incoming && SoundCheck.useAlarm(app)
        if (alarm && Store.loudAccepted) raiseVolume(app)
        // Si el reproductor falla con el volumen de alarma, se intenta otra vez con el multimedia.
        val mp = start(app, source, alarm, info, incoming) ?: (if (alarm) start(app, source, false, info, incoming) else null)
        if (mp == null) {
            restoreVolume(app)
            if (incoming) {
                Store.lastProblem = "No se pudo reproducir «${info.label}» en este celular."
                Store.notifyChanged()
            }
            return
        }
        if (incoming) Store.lastProblem = ""
        player = mp
        nowKey = AudioAnalysis.key(source)
        AudioAnalysis.request(app, source)
        info.startedAt = SystemClock.elapsedRealtime()
        info.durationMs = if (info.loop) MAX_PLAY_MS
        else (mp.duration.toLong() - info.startMs).takeIf { it > 0 }?.coerceAtMost(MAX_PLAY_MS) ?: MAX_PLAY_MS
        now = info
        main.postDelayed(timeout, MAX_PLAY_MS)
        Store.notifyChanged()
    }

    private fun start(app: Context, source: Source, alarm: Boolean, info: NowPlaying, incoming: Boolean): MediaPlayer? {
        val mp = MediaPlayer()
        attributes = attributesFor(alarm)
        return try {
            mp.setAudioAttributes(attributes)
            mp.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK)
            // Si otra app dejó el celular en modo llamada (WhatsApp, Meet…), Android manda el sonido
            // al auricular de las llamadas y casi no se oye: se pide el altavoz principal.
            if (incoming && Build.VERSION.SDK_INT >= 28) speaker(app)?.let { mp.setPreferredDevice(it) }
            when (source) {
                is Source.Asset -> app.assets.openFd(source.path).use { afd ->
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
                is Source.FromFile -> mp.setDataSource(source.file.absolutePath)
            }
            mp.setOnCompletionListener { finished -> if (finished === player) stopOnMain() }
            mp.setOnErrorListener { failed, _, _ ->
                if (failed === player) stopOnMain()
                true
            }
            mp.isLooping = info.loop
            mp.prepare()
            if (info.startMs > 0 && info.startMs < mp.duration - 300) mp.seekTo(info.startMs)
            requestFocus(app)
            mp.start()
            mp
        } catch (_: Exception) {
            mp.release()
            abandonFocus(app)
            null
        }
    }

    private fun stopOnMain(cancelNotification: Boolean = true) {
        main.removeCallbacks(timeout)
        val mp = player
        val wasPlaying = now
        player = null
        now = null
        if (mp != null) {
            try {
                mp.stop()
            } catch (_: IllegalStateException) {
            }
            mp.release()
        }
        appContext?.let {
            abandonFocus(it)
            restoreVolume(it)
            if (cancelNotification && wasPlaying != null && !wasPlaying.isPreview) Notifs.cancelSound(it)
        }
        if (wasPlaying != null) Store.notifyChanged()
    }

    private fun speaker(context: Context): AudioDeviceInfo? =
        context.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    /** Sube el volumen de alarma al máximo y guarda el que había (también en disco, por si la app se cierra). */
    private fun raiseVolume(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        try {
            if (Store.alarmVolumeToRestore < 0) Store.alarmVolumeToRestore = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) {
            // Algunos celulares no dejan cambiarlo (por ejemplo, con «No molestar»): suena con el que hay.
        }
    }

    /**
     * Devuelve el volumen de alarma que había antes del sonido. Se llama al terminar cada sonido y al
     * arrancar el servicio, por si la app se cerró con algo sonando.
     */
    fun restoreVolume(context: Context) {
        if (now != null) return
        val saved = Store.alarmVolumeToRestore
        if (saved < 0) return
        Store.alarmVolumeToRestore = -1
        try {
            context.getSystemService(AudioManager::class.java)?.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0)
        } catch (_: Exception) {
        }
    }

    private fun requestFocus(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        am.requestAudioFocus(request)
        focusRequest = request
    }

    private fun abandonFocus(context: Context) {
        val request = focusRequest ?: return
        context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request)
        focusRequest = null
    }
}
