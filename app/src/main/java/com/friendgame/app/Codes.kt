package com.friendgame.app

import java.security.SecureRandom

/** Códigos de amigo: 16 caracteres sin letras confusas (0/o, 1/l/i). */
object Codes {
    private const val ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz"
    private const val CODE_LENGTH = 16
    private val random = SecureRandom()

    fun random(length: Int): String =
        buildString(length) { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    fun newCode(): String = random(CODE_LENGTH)

    /** Acepta "ABCD-EFGH-…", con espacios o en minúsculas. Devuelve null si no es válido. */
    fun normalize(input: String?): String? {
        val cleaned = input?.lowercase()?.filterNot { it == '-' || it.isWhitespace() } ?: return null
        if (cleaned.length != CODE_LENGTH || cleaned.any { it !in ALPHABET }) return null
        return cleaned
    }

    fun pretty(code: String): String = code.uppercase().chunked(4).joinToString("-")
}
