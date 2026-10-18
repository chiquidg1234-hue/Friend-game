package com.friendgame.app

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

/**
 * Mensajería entre celulares a través de un servidor ntfy (por defecto ntfy.sh, gratis y sin cuenta).
 * Cada persona escucha en un "topic" secreto derivado de su código de amigo; solo quien conoce
 * tu código puede escribirte, y tu app solo hace caso a los amigos que tú aceptaste.
 */
object Relay {
    const val PROTOCOL_VERSION = 1

    class HttpError(val code: Int) : IOException("HTTP $code")

    const val GROUP_PREFIX = "fgame-g-"

    fun topicFor(code: String) = "fgame-$code"

    fun groupTopic(groupId: String) = "$GROUP_PREFIX$groupId"

    fun baseUrl(): String = Store.server.trimEnd('/')

    /** Una sola conexión escucha el canal personal y los de todos los grupos. */
    fun subscribeUrl(topics: List<String>, since: Long): URL {
        val query = if (since > 0) "?since=$since" else ""
        return URL("${baseUrl()}/${topics.joinToString(",")}/json$query")
    }

    /**
     * Mensaje base: siempre lleva quién lo manda, para que el otro pueda contestar, y sus puntos,
     * para el ranking.
     */
    fun payload(type: String): JSONObject = Points.attach(
        JSONObject()
            .put("fg", PROTOCOL_VERSION)
            .put("t", type)
            .put("from", Store.myCode)
            .put("name", Store.myName)
    )

    /**
     * Mensaje firmado por el admin de un grupo: los datos van como texto en "body" y la firma en
     * "sig", para que se verifiquen exactamente los mismos bytes que se firmaron.
     */
    fun signed(type: String, body: JSONObject, secret: String): JSONObject {
        val text = body.toString()
        return payload(type).put("body", text).put("sig", GroupKeys.sign(secret, text))
    }

    fun publish(toCode: String, payload: JSONObject) = publishTopic(topicFor(toCode), payload)

    fun publishFile(toCode: String, payload: JSONObject, file: File) = publishFileTopic(topicFor(toCode), payload, file)

    fun publishTopic(topic: String, payload: JSONObject) {
        val conn = URL("${baseUrl()}/$topic").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.setRequestProperty("User-Agent", "FriendGame")
            val body = payload.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            finish(conn)
        } finally {
            conn.disconnect()
        }
    }

    /** Para audios propios: el archivo viaja como adjunto y los datos en el parámetro "message". */
    fun publishFileTopic(topic: String, payload: JSONObject, file: File) {
        val message = URLEncoder.encode(payload.toString(), "UTF-8")
        val filename = "sonido." + file.extension.ifEmpty { "mp3" }
        val url = URL("${baseUrl()}/$topic?filename=$filename&message=$message")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", "FriendGame")
            conn.setFixedLengthStreamingMode(file.length())
            conn.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
            finish(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun finish(conn: HttpURLConnection) {
        val code = conn.responseCode
        if (code !in 200..299) {
            try {
                conn.errorStream?.close()
            } catch (_: IOException) {
            }
            throw HttpError(code)
        }
        conn.inputStream.use { it.readBytes() }
    }

    fun describe(e: Throwable): String = when (e) {
        is HttpError -> when (e.code) {
            413 -> "El audio es muy grande para el servidor."
            429 -> "Demasiados envíos seguidos. Espera un momento y vuelve a intentar."
            507 -> "El servidor está lleno ahora mismo. Intenta más tarde."
            else -> "El servidor respondió con un error (${e.code})."
        }
        is UnknownHostException, is ConnectException, is SocketTimeoutException -> "Sin conexión a internet."
        else -> "No se pudo enviar: ${e.message ?: e.javaClass.simpleName}"
    }
}
