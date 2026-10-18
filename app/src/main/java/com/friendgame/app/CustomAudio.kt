package com.friendgame.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Importa audios propios: MP3, OGG, WAV o M4A de hasta 2 MB. Pueden durar hasta un minuto (un
 * audio descargado, una nota de voz): se elige desde dónde suena y suenan como mucho 10 segundos.
 */
object CustomAudio {
    const val MAX_BYTES = 2_000_000L
    /** Las grabaciones con el micrófono se cortan a los 8 segundos. */
    const val RECORD_MS = 8_000L
    const val MAX_MS = 60_000L
    const val HINT = "MP3, OGG, WAV o M4A · hasta 1 minuto y 2 MB · suena 10 segundos"

    val PICKER_MIME_TYPES = arrayOf(
        "audio/mpeg", "audio/mp3", "audio/ogg", "application/ogg", "audio/opus",
        "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave",
        "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac",
    )

    class ImportError(message: String) : Exception(message)

    class Imported(val sound: CustomSound, val suggestedLabel: String, val durationMs: Long = 0)

    private fun dir(context: Context) = File(context.filesDir, "custom").apply { mkdirs() }

    fun fileFor(context: Context, sound: CustomSound) = File(dir(context), "${sound.id}.${sound.ext}")

    fun delete(context: Context, sound: CustomSound) {
        fileFor(context, sound).delete()
        Store.removeCustomSound(sound.id)
    }

    /** Copia y valida el archivo. No lo guarda en la lista hasta que se elige el nombre. */
    fun import(context: Context, uri: Uri): Imported {
        val resolver = context.contentResolver
        var displayName = "audio"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !c.isNull(nameIndex)) displayName = c.getString(nameIndex)
                val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !c.isNull(sizeIndex)) size = c.getLong(sizeIndex)
            }
        }
        val ext = extensionFor(displayName, resolver.getType(uri) ?: "")
            ?: throw ImportError("Ese formato no sirve. Usa MP3, OGG, WAV o M4A.")
        if (size > MAX_BYTES) throw ImportError("El audio pesa ${megabytes(size)}. El máximo es 2 MB.")

        val sound = CustomSound(Codes.random(10), "", ext)
        val out = fileFor(context, sound)
        try {
            val input = resolver.openInputStream(uri) ?: throw ImportError("No se pudo abrir el archivo.")
            input.use { stream ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw ImportError("El audio pesa más de 2 MB.")
                        output.write(buffer, 0, n)
                    }
                }
            }
        } catch (e: IOException) {
            out.delete()
            throw ImportError("No se pudo leer el archivo.")
        } catch (e: ImportError) {
            out.delete()
            throw e
        }

        val durationMs = durationOf(out)
        if (durationMs == null) {
            out.delete()
            throw ImportError("No se pudo reproducir ese audio. ¿Está dañado?")
        }
        if (durationMs > MAX_MS + 300) {
            out.delete()
            val seconds = String.format(Locale.US, "%.1f", durationMs / 1000.0)
            throw ImportError("Dura $seconds segundos. El máximo es un minuto: recórtalo o elige otro.")
        }
        val label = displayName.substringBeforeLast('.').replace('_', ' ').trim().take(24).ifEmpty { "Mi audio" }
        return Imported(sound, label, durationMs)
    }

    private fun extensionFor(name: String, mime: String): String? {
        when (val ext = name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "mp3", "ogg", "wav", "m4a" -> return ext
            "oga", "opus" -> return "ogg"
            "aac" -> return "m4a"
        }
        return when (mime.lowercase(Locale.US)) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/ogg", "application/ogg", "audio/opus", "audio/vorbis" -> "ogg"
            "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "wav"
            "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac" -> "m4a"
            else -> null
        }
    }

    fun durationOf(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (ms != null && ms > 0) return ms
        } catch (_: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
        val player = MediaPlayer()
        return try {
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.duration.toLong().takeIf { it > 0 }
        } catch (_: Exception) {
            null
        } finally {
            player.release()
        }
    }

    private fun megabytes(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
}
