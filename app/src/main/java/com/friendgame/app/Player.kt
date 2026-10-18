package com.friendgame.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.io.File

/**
 * Reproduce un sonido a la vez. Cualquier sonido se puede detener siempre desde este celular
 * (app, notificación). Además quien lo mandó puede cortarlo antes con su botón de detener.
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

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var appContext: Context? = null
    private val timeout = Runnable { stopOnMain() }

    private var attributes: AudioAttributes = attributesFor(alarm = false)

    /**
     * Con "sonar aunque esté en silencio", los sonidos de amigos usan el volumen de alarma, que
     * Android no silencia en modo silencio ni vibración. Escuchar tus propios sonidos usa siempre
     * el volumen multimedia.
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

    /** Escuchar un sonido en tu propio celular antes de mandarlo. */
    fun preview(context: Context, source: Source, label: String) {
        play(context, source, NowPlaying(Codes.random(12), "", "", label))
    }

    fun stop() {
        main.post { stopOnMain() }
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
        // Si el reproductor falla con el volumen de alarma, se intenta otra vez con el multimedia.
        val mp = start(app, source, alarm) ?: (if (alarm) start(app, source, false) else null)
        if (mp == null) {
            if (incoming) {
                Store.lastProblem = "No se pudo reproducir «${info.label}» en este celular."
                Store.notifyChanged()
            }
            return
        }
        if (incoming) Store.lastProblem = ""
        player = mp
        info.startedAt = SystemClock.elapsedRealtime()
        info.durationMs = mp.duration.toLong().takeIf { it > 0 }?.coerceAtMost(MAX_PLAY_MS) ?: MAX_PLAY_MS
        now = info
        android.util.Log.i("FriendGame", "playOnMain now=${info.label} from=${info.fromCode}")
        main.postDelayed(timeout, MAX_PLAY_MS)
        Store.notifyChanged()
    }

    private fun start(app: Context, source: Source, alarm: Boolean): MediaPlayer? {
        val mp = MediaPlayer()
        attributes = attributesFor(alarm)
        return try {
            mp.setAudioAttributes(attributes)
            mp.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK)
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
            mp.prepare()
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
            if (cancelNotification && wasPlaying != null && !wasPlaying.isPreview) Notifs.cancelSound(it)
        }
        if (wasPlaying != null) Store.notifyChanged()
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
