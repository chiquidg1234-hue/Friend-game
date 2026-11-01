package com.friendgame.app

import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cifrado de punta a punta entre amigos (lo que va por el canal personal de cada uno).
 *
 * Cada celular tiene un par de claves P-256. La pública viaja en los mensajes sin cifrar
 * (`pk`), y la primera que llega de un amigo es la que vale (no se puede cambiar después). Con
 * ella, ECDH + HKDF-SHA256 dan una clave AES-256 que solo conocen los dos, y cada mensaje va en
 * AES-GCM: el servidor (ntfy) y quien sepa tu código ven solo `{"t":"enc","from":…}` y bytes.
 * Como GCM autentica, nadie más puede hacerse pasar por tu amigo. Los grupos y los adjuntos de
 * audio no van cifrados (ver docs/SEGURIDAD.md).
 *
 * Núcleo en Kotlin puro (java.util.Base64, sin Android) para probarlo contra la versión en
 * Python de la prueba en emulador (tools/e2e/sobre.py).
 */
object Cifrado {
    const val TYPE = "enc"
    /** El mismo número que Relay.PROTOCOL_VERSION (aquí escrito para que el núcleo no dependa de Android). */
    private const val PROTOCOL = 1
    private const val INFO = "friendgame e2e v1"
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /** Tipos que siempre pueden ir sin cifrar: la solicitud y la aceptación (llevan la clave). */
    val PLAIN_OK = setOf("req", "acc")

    /** Devuelve (pública X.509, privada PKCS#8), en base64 url. */
    fun newKeyPair(): Pair<String, String> {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), random)
        val pair = generator.generateKeyPair()
        return encoder.encodeToString(pair.public.encoded) to encoder.encodeToString(pair.private.encoded)
    }

    /** Una clave pública que se ve bien (P-256, bien formada), o null. */
    fun validPublic(text: String?): String? {
        if (text.isNullOrEmpty() || text.length > 200) return null
        return try {
            publicKey(text)
            text
        } catch (_: Exception) {
            null
        }
    }

    private fun publicKey(text: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decoder.decode(text)))

    private fun privateKey(text: String): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(decoder.decode(text)))

    private val keyCache = HashMap<String, ByteArray>()

    /** La clave AES que comparten [myCode] y [friendCode] (la misma en los dos celulares). */
    fun sharedKey(myPrivate: String, friendPublic: String, myCode: String, friendCode: String): ByteArray = synchronized(keyCache) {
        val cacheKey = "$myCode|$friendCode|$friendPublic|${myPrivate.hashCode()}"
        keyCache[cacheKey]?.let { return it }
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey(myPrivate))
        agreement.doPhase(publicKey(friendPublic), true)
        val secret = agreement.generateSecret()
        val salt = listOf(myCode, friendCode).sorted().joinToString("|").toByteArray(Charsets.UTF_8)
        val key = hkdf(salt, secret, INFO.toByteArray(Charsets.UTF_8))
        if (keyCache.size > 64) keyCache.clear()
        keyCache[cacheKey] = key
        key
    }

    /** HKDF-SHA256 (RFC 5869) con una sola vuelta: 32 bytes. */
    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray): ByteArray {
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }
        val prk = extract.doFinal(ikm)
        val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        expand.update(info)
        expand.update(1.toByte())
        return expand.doFinal()
    }

    private fun aad(from: String, to: String) = "fg1|$from|$to".toByteArray(Charsets.UTF_8)

    /** Mete [inner] (el mensaje completo) en un sobre cifrado de [from] para [to]. */
    fun seal(key: ByteArray, from: String, to: String, inner: JSONObject): JSONObject {
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(from, to))
        val sealed = cipher.doFinal(inner.toString().toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("fg", PROTOCOL)
            .put("t", TYPE)
            .put("from", from)
            .put("n", encoder.encodeToString(nonce))
            .put("c", encoder.encodeToString(sealed))
    }

    /**
     * Abre un sobre de [from] para [to]. Devuelve el mensaje de adentro, o null si no es de quien
     * dice ser (la clave no calza, lo alteraron o el de adentro dice otro remitente).
     */
    fun open(key: ByteArray, from: String, to: String, envelope: JSONObject): JSONObject? = try {
        val nonce = decoder.decode(envelope.optString("n"))
        val sealed = decoder.decode(envelope.optString("c"))
        if (nonce.size != NONCE_BYTES || sealed.size > 16_384) {
            null
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad(from, to))
            val inner = JSONObject(String(cipher.doFinal(sealed), Charsets.UTF_8))
            inner.takeIf { it.optString("from") == from && it.optString("t") != TYPE }
        }
    } catch (_: Exception) {
        null
    }
}
