package com.friendgame.app

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Firmas del admin de un grupo (ECDSA P-256). Solo el celular que creó el grupo tiene la clave
 * privada; los demás verifican con la pública que viene en la invitación. Así nadie más puede
 * sacar gente, cambiar la lista de miembros ni cerrar el grupo.
 */
object GroupKeys {
    private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    /** Devuelve (pública, privada) en base64. */
    fun newKeyPair(): Pair<String, String> {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        return encode(pair.public.encoded) to encode(pair.private.encoded)
    }

    fun sign(secret: String, data: String): String {
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(decode(secret)))
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(key)
        signature.update(data.toByteArray(Charsets.UTF_8))
        return encode(signature.sign())
    }

    fun verify(publicKey: String, data: String, sig: String): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decode(publicKey)))
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initVerify(key)
        signature.update(data.toByteArray(Charsets.UTF_8))
        signature.verify(decode(sig))
    } catch (_: Exception) {
        false
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, FLAGS)

    private fun decode(text: String): ByteArray = Base64.decode(text, FLAGS)
}
