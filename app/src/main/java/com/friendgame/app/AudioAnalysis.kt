package com.friendgame.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Lee el audio de un sonido (sin reproducirlo) con el decodificador de Android, para:
 *
 * - la envolvente: cuánto suena cada 40 ms. Mientras algo suena, la mascota mueve la boca y el
 *   fondo late con ella (ver [Player.level]). Se calcula una vez y queda en memoria.
 * - la categoría de un audio subido o grabado ([AudioClassifier]).
 *
 * No necesita permisos (no usa el micrófono ni el Visualizer). Todo corre fuera del hilo principal.
 */
object AudioAnalysis {
    const val ENV_MS = 40

    /** Envolventes recientes (de 0 a 1), por sonido. */
    private val envelopes = object : LinkedHashMap<String, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean = size > 32
    }
    private val pending = HashSet<String>()

    fun key(source: Player.Source): String = when (source) {
        is Player.Source.Asset -> "a:${source.path}"
        is Player.Source.FromFile -> "f:${source.file.absolutePath}:${source.file.lastModified()}"
    }

    fun envelope(key: String): FloatArray? = synchronized(envelopes) { envelopes[key] }

    /** Calcula la envolvente en otro hilo si todavía no está. */
    fun request(context: Context, source: Player.Source) {
        val key = key(source)
        synchronized(envelopes) {
            if (envelopes.containsKey(key) || !pending.add(key)) return
        }
        val app = context.applicationContext
        Thread {
            val env = try {
                val builder = EnvelopeBuilder()
                decode(app, source, CustomAudio.MAX_MS + 1_000) { chunk, count, rate -> builder.add(chunk, count, rate) }
                builder.finish()
            } catch (_: Exception) {
                null
            }
            synchronized(envelopes) {
                pending.remove(key)
                if (env != null) envelopes[key] = env
            }
        }.start()
    }

    class Analysis(val envelope: FloatArray, val result: AudioClassifier.Result?, val durationMs: Long)

    /**
     * Mide un audio entero (envolvente) y clasifica la parte que va a sonar: desde [startMs], como
     * mucho [AudioClassifier.MAX_SECONDS]. Pesa: llamarlo fuera del hilo principal.
     */
    fun analyze(context: Context, source: Player.Source, startMs: Int = 0): Analysis {
        val builder = EnvelopeBuilder()
        var rate = 0
        val keep = ArrayList<FloatArray>()
        var kept = 0
        var seen = 0L
        decode(context, source, CustomAudio.MAX_MS + 1_000) { chunk, count, r ->
            builder.add(chunk, count, r)
            rate = r
            val startSample = startMs.toLong() * r / 1000
            val limit = AudioClassifier.MAX_SECONDS.toLong() * r
            // Se guarda solo la parte que suena, para clasificarla.
            val from = (startSample - seen).coerceIn(0, count.toLong()).toInt()
            if (from < count && kept < limit) {
                val take = minOf(count - from, (limit - kept).toInt())
                keep += chunk.copyOfRange(from, from + take)
                kept += take
            }
            seen += count
        }
        val samples = FloatArray(kept)
        var i = 0
        for (part in keep) {
            part.copyInto(samples, i)
            i += part.size
        }
        val result = if (rate > 0 && samples.isNotEmpty()) AudioClassifier.classify(samples, rate) else null
        val envelope = builder.finish()
        synchronized(envelopes) { envelopes[key(source)] = envelope }
        return Analysis(envelope, result, if (rate > 0) seen * 1000 / rate else 0L)
    }

    /** Junta el volumen de cada 40 ms y lo deja de 0 a 1 (lo más fuerte cerca de 1). */
    private class EnvelopeBuilder {
        private val values = ArrayList<Float>()
        private var sum = 0.0
        private var count = 0
        private var window = 0

        fun add(chunk: FloatArray, n: Int, rate: Int) {
            if (window == 0) window = (rate * ENV_MS / 1000).coerceAtLeast(1)
            for (i in 0 until n) {
                val v = chunk[i]
                sum += v * v
                count++
                if (count == window) {
                    values += sqrt(sum / count).toFloat()
                    sum = 0.0
                    count = 0
                }
            }
        }

        fun finish(): FloatArray {
            if (count > 0) values += sqrt(sum / count).toFloat()
            if (values.isEmpty()) return FloatArray(0)
            val sorted = values.sorted()
            val top = sorted[(sorted.size * 0.95f).toInt().coerceAtMost(sorted.size - 1)].coerceAtLeast(1e-4f)
            return FloatArray(values.size) { sqrt((values[it] / top).coerceIn(0f, 1f)) }
        }
    }

    /**
     * Decodifica a muestras mono de -1 a 1 y se las pasa a [block] de a pedazos (con cuántas
     * sirven y a qué frecuencia). Para a los [maxMs].
     */
    fun decode(context: Context, source: Player.Source, maxMs: Long, block: (FloatArray, Int, Int) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            when (source) {
                is Player.Source.Asset -> context.assets.openFd(source.path).use { afd ->
                    extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
                is Player.Source.FromFile -> extractor.setDataSource(source.file.absolutePath)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME) ?: return)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var floats = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var decoded = 0L
            var mono = FloatArray(4096)
            var idle = 0
            while (true) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index) ?: break
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = decoder.dequeueOutputBuffer(info, 10_000)
                // Algunos decodificadores no avisan el final: si no sale nada por un segundo, listo.
                if (out == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone && ++idle > 100) break
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = decoder.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    floats = Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (out >= 0) {
                    val buffer = decoder.getOutputBuffer(out)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.order(ByteOrder.nativeOrder())
                        val frames = info.size / (if (floats) 4 else 2) / channels
                        if (mono.size < frames) mono = FloatArray(frames)
                        if (floats) {
                            val fb = buffer.asFloatBuffer()
                            for (i in 0 until frames) {
                                var s = 0f
                                for (c in 0 until channels) s += fb.get(i * channels + c)
                                mono[i] = s / channels
                            }
                        } else {
                            val sb = buffer.asShortBuffer()
                            for (i in 0 until frames) {
                                var s = 0f
                                for (c in 0 until channels) s += sb.get(i * channels + c) / 32768f
                                mono[i] = s / channels
                            }
                        }
                        block(mono, frames, rate)
                        decoded += frames
                    }
                    decoder.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    if (rate > 0 && decoded * 1000 / rate >= maxMs) break
                }
            }
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            extractor.release()
        }
    }
}
