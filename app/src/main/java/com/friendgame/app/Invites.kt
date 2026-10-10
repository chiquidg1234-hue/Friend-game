package com.friendgame.app

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Invitación a un grupo como enlace. Apunta a la página de instalación: si alguien sin la app
 * escanea el QR con la cámara, llega a instalarla. Dentro de la app se lee la parte después de "#".
 */
object Invites {
    private const val BASE = "https://github.com/chiquidg1234-hue/Friend-game/releases/latest"

    class Invite(val groupId: String, val name: String, val adminCode: String, val adminKey: String)

    fun link(group: Group): String =
        "$BASE#g=${group.id}&n=${URLEncoder.encode(group.name, "UTF-8")}&a=${group.adminCode}&k=${group.adminKey}"

    fun parse(text: String?): Invite? {
        val fragment = text?.trim()?.substringAfter('#', "") ?: return null
        if (fragment.isEmpty()) return null
        val params = fragment.split('&').associate { part ->
            part.substringBefore('=') to part.substringAfter('=', "")
        }
        val id = Codes.normalize(params["g"]) ?: return null
        val admin = Codes.normalize(params["a"]) ?: return null
        val key = params["k"]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{40,200}")) } ?: return null
        val name = try {
            URLDecoder.decode(params["n"] ?: "", "UTF-8")
        } catch (_: Exception) {
            ""
        }.trim().take(24).ifEmpty { "Grupo" }
        return Invite(id, name, admin, key)
    }
}
