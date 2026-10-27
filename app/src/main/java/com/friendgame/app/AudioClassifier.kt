package com.friendgame.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Adivina la categoría de un audio subido o grabado (risa, fiesta, susto, amor, troll o voz) sin
 * internet ni modelos pesados. Mide cosas simples del sonido —volumen, silencios, si tiene tono y
 * cuánto cambia, ritmo de sílabas, golpes regulares, cuánto ruido tiene— y las compara con cómo
 * suele sonar cada categoría. Devuelve qué tan seguro está: si no lo está, la app le pide a la
 * persona que elija. Por qué así y no YAMNet: docs/AUDIO_CATEGORIAS.md.
 *
 * No usa nada de Android: se prueba en la computadora (tools/audio/).
 */
object AudioClassifier {
    /** Todo se analiza a 16 kHz y como mucho estos segundos. */
    const val RATE = 16_000
    const val MAX_SECONDS = 20
    /** Por debajo de esta seguridad, se pregunta. */
    const val MIN_CONFIDENCE = 0.45f

    private const val FRAME = 512
    private const val HOP = 256

    class Features(
        val seconds: Float,
        /** Volumen medio de lo que suena, en dBFS (0 es lo máximo). */
        val loudness: Float,
        val silence: Float,
        /** Parte del sonido que tiene tono (voz, música, pitidos). */
        val voiced: Float,
        /** Tono típico en Hz y cuánto varía (en semitonos). */
        val pitch: Float,
        val pitchSpread: Float,
        /** Salto típico de tono entre un instante y el siguiente (semitonos): bajo en sirenas y notas largas. */
        val pitchJump: Float,
        /** Parte del sonido con tono que se mantiene parejo. */
        val stable: Float,
        val centroid: Float,
        /** 0: tono puro; 1: ruido. */
        val flatness: Float,
        val low: Float,
        val high: Float,
        /** Cuánto del vaivén del volumen es al ritmo de las sílabas (2,5 a 8 por segundo). */
        val syllabic: Float,
        val modPeak: Float,
        /** Ráfagas rápidas y parejas (0 a 1): risas «ja-ja-ja», pitidos. */
        val pulse: Float,
        /** Golpes regulares, como un ritmo de música (0 a 1). */
        val beat: Float,
        val onsets: Float,
        /** Segundos hasta llegar fuerte y cuánto queda al final (las campanas se apagan). */
        val attack: Float,
        val decay: Float,
    ) {
        override fun toString(): String = String.format(
            java.util.Locale.US,
            "s=%.1f loud=%.1f sil=%.2f voiced=%.2f f0=%.0f spread=%.1f jump=%.2f stable=%.2f cent=%.0f flat=%.2f low=%.2f high=%.2f syl=%.2f mod=%.1f pulse=%.2f beat=%.2f ons=%.1f att=%.2f dec=%.2f",
            seconds, loudness, silence, voiced, pitch, pitchSpread, pitchJump, stable, centroid, flatness, low, high, syllabic, modPeak, pulse, beat, onsets, attack, decay,
        )
    }

    class Result(
        /** La categoría (clave de Vibes: "risa", "voz"…) o null si no está seguro. */
        val vibe: String?,
        /** La mejor aunque no esté seguro (para marcarla como sugerencia). */
        val best: String,
        val confidence: Float,
        val scores: List<Pair<String, Float>>,
        val features: Features,
    )

    /** [samples]: mono, de -1 a 1, a [rate] Hz. */
    fun classify(samples: FloatArray, rate: Int): Result {
        val f = features(samples, rate)
        val raw = listOf(
            "voz" to voz(f),
            "risa" to risa(f),
            "fiesta" to fiesta(f),
            "susto" to susto(f),
            "amor" to amor(f),
            "troll" to troll(f),
        )
        // Seguridad: «softmax» de los puntajes (cerca del mejor, se reparte).
        val top = raw.maxOf { it.second }
        val exps = raw.map { it.first to exp((it.second - top) / TEMPERATURE) }
        val total = exps.sumOf { it.second.toDouble() }.toFloat()
        val probs = exps.map { it.first to it.second / total }.sortedByDescending { it.second }
        val best = probs.first()
        val sure = best.second >= MIN_CONFIDENCE && f.seconds >= 0.3f && f.loudness > -50f
        return Result(if (sure) best.first else null, best.first, best.second, probs, f)
    }

    private const val TEMPERATURE = 0.07f

    // ---------------------------------------------------------------- Puntajes (0 a 1)

    /** 1 dentro de [lo, hi]; baja de a poco hasta 0 a [soft] de distancia. */
    private fun band(x: Float, lo: Float, hi: Float, soft: Float): Float = when {
        x in lo..hi -> 1f
        x < lo -> (1f - (lo - x) / soft).coerceIn(0f, 1f)
        else -> (1f - (x - hi) / soft).coerceIn(0f, 1f)
    }

    private fun score(vararg terms: Pair<Float, Float>): Float {
        var sum = 0f
        var weights = 0f
        for ((w, v) in terms) {
            sum += w * v
            if (w > 0f) weights += w
        }
        return (sum / weights).coerceAtLeast(0f)
    }

    private fun voz(f: Features) = score(
        1.2f to band(f.syllabic, 0.4f, 1f, 0.2f),
        1.0f to band(f.voiced, 0.25f, 0.85f, 0.2f),
        1.0f to band(f.pitch, 80f, 300f, 60f),
        1.0f to band(f.centroid, 700f, 3000f, 300f),
        0.8f to band(f.pitchSpread, 1.2f, 9f, 1.2f),
        0.6f to band(f.silence, 0.04f, 0.5f, 0.08f),
        0.6f to band(f.onsets, 1f, 4f, 1f),
        0.4f to band(f.flatness, 0.02f, 0.35f, 0.1f),
        -0.8f to band(f.beat, 0.6f, 1f, 0.15f),
        -0.8f to band(f.pulse, 0.55f, 1f, 0.15f),
        -0.6f to band(f.stable, 0.92f, 1f, 0.05f),
        -0.8f to band(f.pitchJump, 3f, 24f, 1f),
    )

    private fun risa(f: Features) = score(
        1.2f to band(f.onsets, 3.8f, 12f, 1f),
        1.0f to band(f.pulse, 0.35f, 1f, 0.15f),
        0.9f to band(f.pitchSpread, 1.5f, 14f, 1f),
        0.8f to band(f.modPeak, 3.5f, 9f, 1.2f),
        0.8f to band(f.pitch, 140f, 900f, 80f),
        0.6f to band(f.voiced, 0.25f, 0.95f, 0.15f),
        0.5f to band(f.silence, 0.1f, 0.55f, 0.1f),
        -0.8f to band(f.beat, 0.7f, 1f, 0.1f) * band(f.silence, 0f, 0.1f, 0.05f),
        -0.6f to band(f.stable, 0.85f, 1f, 0.1f),
        -0.6f to band(f.pitchJump, 3f, 24f, 1f),
    )

    private fun fiesta(f: Features) = score(
        1.4f to band(f.beat, 0.65f, 1f, 0.2f),
        1.0f to band(f.onsets, 2.5f, 9f, 1.2f),
        1.0f to band(f.silence, 0f, 0.12f, 0.1f),
        0.8f to band(f.seconds, 2.5f, 60f, 1.5f),
        0.8f to band(f.low, 0.25f, 0.95f, 0.15f),
        0.6f to band(f.voiced, 0f, 0.7f, 0.25f),
        -1.2f to band(f.loudness, -60f, -28f, 4f),
        -0.6f to band(f.seconds, 0f, 1.8f, 0.5f),
        -0.6f to band(f.flatness, 0.35f, 1f, 0.1f),
        -0.6f to band(f.stable, 0.85f, 1f, 0.1f) * band(f.voiced, 0.9f, 1f, 0.1f),
    )

    private fun susto(f: Features) = score(
        1.4f to band(f.loudness, -10f, 0f, 5f),
        1.0f to band(f.pitch, 420f, 3000f, 120f) * band(f.voiced, 0.5f, 1f, 0.2f),
        0.8f to band(f.centroid, 600f, 6000f, 300f),
        0.8f to band(f.stable, 0.6f, 1f, 0.2f),
        0.6f to band(f.attack, 0f, 0.12f, 0.15f),
        0.6f to band(f.pitchSpread, 2f, 14f, 1f) * band(f.pitchJump, 0f, 0.6f, 0.3f),
        0.4f to band(f.flatness, 0f, 0.05f, 0.05f),
        -0.8f to band(f.loudness, -60f, -22f, 5f),
        -0.6f to band(f.decay, 0f, 0.3f, 0.15f),
        -1.0f to band(f.pitchJump, 2.5f, 24f, 1f),
    )

    private fun amor(f: Features) = score(
        1.2f to band(f.loudness, -60f, -22f, 6f),
        1.0f to band(f.flatness, 0f, 0.1f, 0.08f),
        1.0f to band(f.decay, 0f, 0.35f, 0.2f),
        0.6f to band(f.onsets, 0f, 2.5f, 1f),
        0.6f to band(f.centroid, 250f, 2200f, 400f),
        0.4f to band(f.pulse, 0f, 0.4f, 0.2f),
        -0.8f to band(f.loudness, -8f, 0f, 3f),
        -0.6f to band(f.syllabic, 0.45f, 1f, 0.1f) * band(f.onsets, 1.5f, 10f, 1f),
    )

    /** Lo raro: ruido, un zumbido muy grave (pedo) o tonos que saltan muy rápido (láser). */
    private fun troll(f: Features) = score(
        1.6f to maxOf(band(f.flatness, 0.3f, 1f, 0.12f), band(f.centroid, 0f, 260f, 120f), band(f.pitchJump, 3f, 24f, 1.5f)),
        0.6f to band(f.low, 0.6f, 1f, 0.15f),
        0.6f to band(f.seconds, 0f, 3f, 1f),
        0.4f to band(f.voiced, 0f, 0.6f, 0.2f),
        -0.6f to band(f.syllabic, 0.55f, 1f, 0.15f) * band(f.centroid, 700f, 3000f, 300f),
    )

    // ---------------------------------------------------------------- Medidas

    fun features(input: FloatArray, inputRate: Int): Features {
        val x = resample(input, inputRate, RATE, MAX_SECONDS)
        val n = x.size
        val frames = max(0, (n - FRAME) / HOP + 1)
        if (frames < 4) return Features(n / RATE.toFloat(), -90f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
        val window = FloatArray(FRAME) { (0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1))).toFloat() }
        val rms = FloatArray(frames)
        val zcr = FloatArray(frames)
        val centroid = FloatArray(frames)
        val flat = FloatArray(frames)
        val low = FloatArray(frames)
        val high = FloatArray(frames)
        val flux = FloatArray(frames)
        val pitch = FloatArray(frames)
        val re = FloatArray(FRAME)
        val im = FloatArray(FRAME)
        val mag = FloatArray(FRAME / 2 + 1)
        val prev = FloatArray(FRAME / 2 + 1)
        val binHz = RATE.toFloat() / FRAME
        for (k in 0 until frames) {
            val start = k * HOP
            var e = 0.0
            var crossings = 0
            for (i in 0 until FRAME) {
                val v = x[start + i]
                e += v * v
                if (i > 0 && (v >= 0f) != (x[start + i - 1] >= 0f)) crossings++
                re[i] = v * window[i]
                im[i] = 0f
            }
            rms[k] = sqrt(e / FRAME).toFloat()
            zcr[k] = crossings / FRAME.toFloat()
            fft(re, im)
            var total = 0.0
            var weighted = 0.0
            var logSum = 0.0
            var linSum = 0.0
            var bins = 0
            var lowE = 0.0
            var highE = 0.0
            var change = 0.0
            var magSum = 0.0
            for (b in 1..FRAME / 2) {
                val m = sqrt(re[b] * re[b] + im[b] * im[b])
                mag[b] = m
                val p = (m * m).toDouble()
                val hz = b * binHz
                total += p
                weighted += p * hz
                if (hz in 100f..6000f) {
                    logSum += ln(p + 1e-12)
                    linSum += p + 1e-12
                    bins++
                }
                if (hz < 300f) lowE += p
                if (hz > 2500f) highE += p
                change += max(0f, m - prev[b])
                magSum += m
                prev[b] = m
            }
            centroid[k] = if (total > 0) (weighted / total).toFloat() else 0f
            flat[k] = if (bins > 0 && linSum > 0) (exp(logSum / bins) / (linSum / bins)).toFloat() else 1f
            low[k] = if (total > 0) (lowE / total).toFloat() else 0f
            high[k] = if (total > 0) (highE / total).toFloat() else 0f
            flux[k] = if (magSum > 0) (change / magSum).toFloat() else 0f
            // Tono, cada dos cuadros (pesa).
            pitch[k] = if (k % 2 == 0) pitchOf(x, start) else -1f
        }
        for (k in 1 until frames) if (pitch[k] < 0f) pitch[k] = pitch[k - 1]

        val sorted = rms.sortedArray()
        val p95 = sorted[(sorted.size * 0.95f).toInt().coerceAtMost(sorted.size - 1)]
        val floor = max(0.004f, p95 * 0.1f)
        val active = (0 until frames).filter { rms[it] > floor }
        val silence = 1f - active.size / frames.toFloat()
        fun meanOf(a: FloatArray): Float = if (active.isEmpty()) 0f else active.map { a[it] }.average().toFloat()
        val meanRms = meanOf(rms)
        val loudness = if (meanRms > 0f) 20f * log10(meanRms) else -90f

        // Tono: solo en lo que suena y tiene periodo claro.
        val voicedIdx = active.filter { pitch[it] > 0f }
        val voiced = if (active.isEmpty()) 0f else voicedIdx.size / active.size.toFloat()
        val semis = voicedIdx.map { 12f * log2(pitch[it] / 110f) }
        val pitchHz = if (voicedIdx.isEmpty()) 0f else voicedIdx.map { pitch[it] }.sorted()[voicedIdx.size / 2]
        val spread = if (semis.size < 3) 0f else {
            val m = semis.average().toFloat()
            sqrt(semis.map { (it - m) * (it - m) }.average().toFloat())
        }
        // El tono se mide cada dos cuadros: los saltos se comparan entre cuadros medidos seguidos.
        val measured = voicedIdx.filter { it % 2 == 0 }
        val jumps = ArrayList<Float>()
        for (i in 1 until measured.size) {
            val a = measured[i - 1]
            val b = measured[i]
            if (b - a == 2) jumps += abs(12f * log2(pitch[b] / pitch[a]))
        }
        val pitchJump = if (jumps.isEmpty()) 0f else jumps.sorted()[jumps.size / 2]
        val stable = if (jumps.isEmpty()) 0f else jumps.count { it < 0.6f } / jumps.size.toFloat()

        // Vaivén del volumen: ¿al ritmo de las sílabas (2,5 a 8 por segundo)?
        val frameRate = RATE.toFloat() / HOP
        val env = FloatArray(frames) { rms[it] }
        val envMean = env.average().toFloat()
        for (i in env.indices) env[i] -= envMean
        var syl = 0.0
        var all = 0.0
        var peakHz = 0f
        var peakE = 0.0
        var hz = 0.5f
        while (hz <= 12f) {
            var c = 0.0
            var s = 0.0
            val w = 2 * PI * hz / frameRate
            for (i in env.indices) {
                c += env[i] * cos(w * i)
                s += env[i] * sin(w * i)
            }
            val p = c * c + s * s
            all += p
            if (hz in 2.5f..8f) syl += p
            if (p > peakE) {
                peakE = p
                peakHz = hz
            }
            hz += 0.25f
        }
        val syllabic = if (all > 0) (syl / all).toFloat() else 0f
        // Ráfagas parejas y rápidas (3 a 10 por segundo): «ja-ja-ja», pitidos de alarma.
        var pulse = 0f
        var envZero = 0.0
        for (v in env) envZero += v * v
        if (envZero > 0) {
            for (lag in (0.1f * frameRate).toInt()..min((0.34f * frameRate).toInt(), frames / 2)) {
                var acc = 0.0
                for (i in 0 until frames - lag) acc += env[i] * env[i + lag]
                pulse = max(pulse, (acc / envZero * frames / (frames - lag)).toFloat())
            }
        }

        // Golpes: picos del cambio del espectro y si se repiten parejo (ritmo).
        val fm = flux.average().toFloat()
        val fsd = sqrt(flux.map { (it - fm) * (it - fm) }.average().toFloat())
        var onsetCount = 0
        var last = -100
        for (k in 1 until frames - 1) {
            if (flux[k] > fm + 1.5f * fsd && flux[k] >= flux[k - 1] && flux[k] >= flux[k + 1] && k - last > frameRate * 0.08f && rms[k] > floor) {
                onsetCount++
                last = k
            }
        }
        val seconds = n / RATE.toFloat()
        val onsets = onsetCount / max(seconds, 0.5f)
        var beat = 0f
        val centered = FloatArray(frames) { flux[it] - fm }
        var zero = 0.0
        for (v in centered) zero += v * v
        if (zero > 0) {
            val minLag = (0.3f * frameRate).toInt()
            val maxLag = min((1.0f * frameRate).toInt(), frames / 2)
            for (lag in minLag..maxLag) {
                var acc = 0.0
                for (i in 0 until frames - lag) acc += centered[i] * centered[i + lag]
                beat = max(beat, (acc / zero * frames / (frames - lag)).toFloat())
            }
        }

        val maxRms = sorted.last()
        val attackIdx = (0 until frames).firstOrNull { rms[it] >= 0.8f * maxRms } ?: 0
        val third = frames / 3
        val first = (0 until third).map { rms[it] }.average().toFloat()
        val lastThird = (frames - third until frames).map { rms[it] }.average().toFloat()
        val decay = if (first > 0f) (lastThird / first).coerceAtMost(3f) else 1f

        return Features(
            seconds, loudness, silence, voiced, pitchHz, spread, pitchJump, stable,
            meanOf(centroid), meanOf(flat), meanOf(low), meanOf(high), syllabic, peakHz, pulse.coerceIn(0f, 1f),
            beat.coerceIn(0f, 1f), onsets, attackIdx / frameRate, decay,
        )
    }

    /** Tono del cuadro por autocorrelación (80 a 1000 Hz), o 0 si no tiene un periodo claro. */
    private fun pitchOf(x: FloatArray, start: Int): Float {
        val minLag = RATE / 1000
        val maxLag = RATE / 80
        val len = FRAME - maxLag - 1
        var energy = 0.0
        for (i in 0 until len) energy += x[start + i] * x[start + i]
        if (energy < 1e-6) return 0f
        var bestLag = -1
        var bestR = 0.0
        val r = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag + 1) {
            var acc = 0.0
            var e2 = 0.0
            for (i in 0 until len) {
                val b = x[start + i + lag]
                acc += x[start + i] * b
                e2 += b * b
            }
            r[lag] = if (e2 > 0) acc / sqrt(energy * e2) else 0.0
        }
        for (lag in minLag + 1..maxLag) if (r[lag] > bestR && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) {
            bestR = r[lag]
            bestLag = lag
        }
        if (bestLag < 0 || bestR < 0.6) return 0f
        // El primer pico casi tan alto evita confundir el tono con su octava de abajo.
        for (lag in minLag + 1 until bestLag) {
            if (r[lag] >= 0.92 * bestR && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) {
                bestLag = lag
                break
            }
        }
        return RATE.toFloat() / bestLag
    }

    /** Pasa a [to] Hz (lineal; alcanza para medir) y corta a [maxSeconds]. */
    fun resample(x: FloatArray, from: Int, to: Int, maxSeconds: Int): FloatArray {
        val limit = min(x.size, from * maxSeconds)
        if (from == to) return x.copyOf(limit)
        val outLen = (limit.toLong() * to / from).toInt()
        val out = FloatArray(outLen)
        val step = from.toDouble() / to
        for (i in 0 until outLen) {
            val pos = i * step
            val a = pos.toInt()
            val frac = (pos - a).toFloat()
            val b = min(a + 1, limit - 1)
            out[i] = x[a] * (1f - frac) + x[b] * frac
        }
        return out
    }

    /** FFT en el lugar (radix 2). */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
