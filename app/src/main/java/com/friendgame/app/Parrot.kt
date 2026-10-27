package com.friendgame.app

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlin.math.sqrt

/**
 * «Háblale»: mientras mantienes el botón, la mascota te escucha (graba hasta 5 segundos con el
 * micrófono) y al soltar lo repite con voz de ardilla (más agudo y rápido), moviendo la boca al
 * ritmo. Todo queda en tu celular: no se guarda ni se manda a nadie.
 */
class Parrot(private val onLevel: (Float) -> Unit, private val onDone: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var recording = false
    private var track: AudioTrack? = null
    private var envelope = FloatArray(0)
    private var playRate = RATE
    private var loudest = 0f

    val busy: Boolean get() = recording || track != null

    /** Empieza a escuchar (hay que tener el permiso del micrófono). Devuelve false si no se pudo. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (busy) return false
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
        } catch (_: Exception) {
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        recording = true
        Thread {
            val pcm = ShortArray(RATE * MAX_SECONDS)
            var count = 0
            try {
                recorder.startRecording()
                val chunk = ShortArray(RATE / 20)
                while (recording && count < pcm.size) {
                    val n = recorder.read(chunk, 0, minOf(chunk.size, pcm.size - count))
                    if (n <= 0) break
                    chunk.copyInto(pcm, count, 0, n)
                    count += n
                    var sum = 0.0
                    for (i in 0 until n) sum += chunk[i] * chunk[i].toDouble()
                    val level = (sqrt(sum / n) / 6000.0).toFloat().coerceIn(0f, 1f)
                    main.post { onLevel(level) }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    recorder.stop()
                } catch (_: Exception) {
                }
                recorder.release()
            }
            recording = false
            main.post { play(pcm, count) }
        }.start()
        return true
    }

    /** Suelta el botón: deja de escuchar y lo repite. */
    fun stop() {
        recording = false
    }

    /** Volumen de lo que está repitiendo ahora (0 a 1), para la boca de la mascota. */
    fun level(): Float {
        val t = track ?: return 0f
        val frame = try {
            t.playbackHeadPosition
        } catch (_: Exception) {
            return 0f
        }
        val index = (frame.toLong() * 1000 / playRate / ENV_MS).toInt()
        return envelope.getOrElse(index) { 0f }
    }

    private fun play(pcm: ShortArray, count: Int) {
        // Un toquecito sin voz no se repite.
        if (count < RATE / 4) {
            onDone()
            return
        }
        envelope = envelopeOf(pcm, count)
        if (loudest < QUIET) {
            onDone()
            return
        }
        playRate = (RATE * PITCH).toInt()
        val audio = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(playRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(count * 2)
                .build()
        } catch (_: Exception) {
            onDone()
            return
        }
        audio.write(pcm, 0, count)
        audio.notificationMarkerPosition = count
        audio.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack?) = finish()
            override fun onPeriodicNotification(t: AudioTrack?) = Unit
        }, main)
        track = audio
        audio.play()
        // Por si el aviso del final no llega en algún celular.
        main.postDelayed({ finish() }, count * 1000L / playRate + 600)
    }

    private fun finish() {
        val t = track ?: return
        track = null
        try {
            t.stop()
        } catch (_: Exception) {
        }
        t.release()
        onDone()
    }

    /** Corta todo (al salir de la pantalla). */
    fun cancel() {
        recording = false
        finish()
    }

    /** El volumen cada 40 ms, contado en el tiempo de la voz aguda (que dura menos). */
    private fun envelopeOf(pcm: ShortArray, count: Int): FloatArray {
        val window = (RATE * ENV_MS / 1000 * PITCH).toInt()
        val out = FloatArray(count / window + 1)
        var max = 1f
        for (w in out.indices) {
            var sum = 0.0
            val from = w * window
            val to = minOf(count, from + window)
            for (i in from until to) sum += pcm[i] * pcm[i].toDouble()
            out[w] = if (to > from) sqrt(sum / (to - from)).toFloat() else 0f
            if (out[w] > max) max = out[w]
        }
        loudest = max
        for (w in out.indices) out[w] = sqrt(out[w] / max)
        return out
    }

    private companion object {
        const val RATE = 22_050
        const val MAX_SECONDS = 5
        const val ENV_MS = 40
        /** Por debajo de esto (volumen medio de 40 ms) es silencio: no repite nada. */
        const val QUIET = 300f
        /** Más agudo y rápido, como una ardilla. */
        const val PITCH = 1.45f
    }
}
