package com.friendgame.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Html
import android.text.Spanned

/**
 * Términos de uso y política de privacidad. Los textos viven en docs/legal/ (se publican con
 * GitHub Pages para Google Play) y van dentro de la app como assets, para leerlos sin internet.
 */
object Legal {
    private const val PAGES = "https://chiquidg1234-hue.github.io/Friend-game/legal"
    const val PRIVACY_URL = "$PAGES/privacidad.html"
    const val TERMS_URL = "$PAGES/terminos.html"
    const val PRIVACY_ASSET = "privacidad.html"
    const val TERMS_ASSET = "terminos.html"
    const val ISSUES_URL = "https://github.com/chiquidg1234-hue/Friend-game/issues"

    /**
     * Lista de retiro: sonidos de la Sonoteca denunciados (derechos de autor, contenido
     * prohibido) y códigos de quienes reinciden. Se edita en docs/legal/retiros.json y cada
     * celular la baja una vez al día para ocultar y borrar esos sonidos.
     */
    const val TAKEDOWNS_URL = "$PAGES/retiros.json"

    /** Correo para denuncias y consultas (variable FG_CONTACT_EMAIL al compilar). */
    val contactEmail: String get() = BuildConfig.CONTACT_EMAIL.trim()

    fun hasContact(): Boolean = contactEmail.contains("@")

    /** Lee el texto de la app (solo lo que está dentro de <main>) para mostrarlo en un diálogo. */
    fun text(context: Context, asset: String): Spanned {
        val html = try {
            context.assets.open(asset).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            "<p>No se encontró el texto. Puedes leerlo en $PAGES.</p>"
        }
        val body = html.substringAfter("<main>", html).substringBefore("</main>")
        return Html.fromHtml(body, Html.FROM_HTML_MODE_COMPACT)
    }

    /** Correo ya armado para denunciar a alguien (si hay correo de contacto). */
    fun reportIntent(subject: String, body: String): Intent? {
        if (!hasContact()) return null
        return Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(contactEmail))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
    }
}
