package com.friendgame.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.io.File

/**
 * Reproduce un sonido a la vez. Cualquier sonido se puede detener siempre desde este celular
 * (app, notificación). Además quien lo mandó puede cortarlo antes con su botón de detener.
 */
object Player {
    /** Nadie puede tener sonando tu celular más de esto, aunque el archivo sea más largo. */
    private const val MAX_PLAY_MS = 10_000L

    sealed class Source {
        class Asset(val path: String) : Source()
        class FromFile(val file: File) : Source()
    }

    class NowPlaying(
        val playbackId: String,
        val fromCode: String,
        val fromName: String,
        val label: String,
    ) {
        val isPreview: Boolean get() = fromCode.isEmpty()
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

    fun play(context: Context, source: Source, info: NowPlaying) {
        val app = context.applicationContext
        main.post { playOnMain(app, source, info) }
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

    private fun playOnMain(app: Context, source: Source, info: NowPlaying) {
        stopOnMain()
        appContext = app
        val mp = MediaPlayer()
        attributes = attributesFor(alarm = !info.isPreview && Store.ringInSilent)
        try {
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
        } catch (e: Exception) {
            mp.release()
            abandonFocus(app)
            return
        }
        player = mp
        now = info
        main.postDelayed(timeout, MAX_PLAY_MS)
        Store.notifyChanged()
    }

    private fun stopOnMain() {
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
            if (wasPlaying != null && !wasPlaying.isPreview) Notifs.cancelSound(it)
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
