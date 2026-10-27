// Prueba el clasificador de categorías con WAV de 16 bits (ver tools/audio/run.sh).
// El nombre de cada archivo empieza con la categoría esperada: «risa_jaja.wav».
import com.friendgame.app.AudioClassifier
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun readWav(file: File): Pair<FloatArray, Int> {
    val bytes = file.readBytes()
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var rate = 16000
    var channels = 1
    var pos = 12
    while (pos + 8 <= bytes.size) {
        val id = String(bytes, pos, 4)
        val size = buf.getInt(pos + 4)
        if (id == "fmt ") {
            channels = buf.getShort(pos + 10).toInt()
            rate = buf.getInt(pos + 12)
        } else if (id == "data") {
            val count = minOf(size, bytes.size - pos - 8) / 2 / channels
            val out = FloatArray(count) { i ->
                var sum = 0f
                for (c in 0 until channels) sum += buf.getShort(pos + 8 + (i * channels + c) * 2) / 32768f
                sum / channels
            }
            return out to rate
        }
        pos += 8 + size + (size and 1)
    }
    error("WAV sin datos: $file")
}

fun main(args: Array<String>) {
    val files = File(args[0]).listFiles { f -> f.name.endsWith(".wav") }!!.sortedBy { it.name }
    var right = 0
    var asked = 0
    var wrong = 0
    for (f in files) {
        val expected = f.name.substringBefore('_')
        val (samples, rate) = readWav(f)
        val started = System.nanoTime()
        val r = AudioClassifier.classify(samples, rate)
        val ms = (System.nanoTime() - started) / 1_000_000
        val mark = when {
            r.vibe == expected -> { right++; "✅" }
            r.vibe == null -> { asked++; if (r.best == expected) "❔" else "❓" }
            else -> { wrong++; "❌" }
        }
        val top = r.scores.take(3).joinToString(" ") { "%s %.2f".format(it.first, it.second) }
        println("$mark ${f.name.padEnd(28)} → ${(r.vibe ?: "¿${r.best}?").padEnd(8)} $top  (${ms} ms)")
        if (args.size > 1) println("     ${r.features}")
    }
    println("\nBien: $right · Pregunta: $asked · Mal: $wrong · de ${files.size}")
}
