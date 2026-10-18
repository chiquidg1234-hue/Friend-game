package com.friendgame.app

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Invitación a un grupo como enlace. Apunta a la página de instalación: si alguien sin la app
 * escanea el QR con la cámara, llega a instalarla. Dentro de la app se lee la parte después de "#".
 */
object Invites {
    private const val BASE = "https://github.com/chiquidg1234-hue/Friend-game/releases/latest"

    class Invite(
        val groupId: String,
        val name: String,
        val adminCode: String,
        val adminKey: String,
        val community: Boolean = false,
        val emoji: String = "",
    )

    fun link(group: Group): String {
        val base = "$BASE#g=${group.id}&n=${URLEncoder.encode(group.name, "UTF-8")}&a=${group.adminCode}&k=${group.adminKey}"
        return if (group.community) base + "&c=1&e=" + URLEncoder.encode(group.emoji, "UTF-8") else base
    }

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
        val emoji = try {
            URLDecoder.decode(params["e"] ?: "", "UTF-8")
        } catch (_: Exception) {
            ""
        }.take(8)
        return Invite(id, name, admin, key, params["c"] == "1", emoji)
    }
}
