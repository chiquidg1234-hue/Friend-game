package com.friendgame.app

import android.net.Uri

/**
 * Donaciones voluntarias. El enlace se pone al compilar con la variable FG_DONATE_URL (en GitHub:
 * Settings → Secrets and variables → Actions → Variables). Si lleva `{amount}`, se cambia por el
 * monto elegido, por ejemplo `https://paypal.me/TU_USUARIO/{amount}USD`; si no, se abre tal cual
 * (Ko-fi, Mercado Pago, Buy Me a Coffee…). Sin enlace, la pantalla avisa que pronto se activan.
 */
object Donations {
    val AMOUNTS = listOf(1, 3, 5)
    const val DEFAULT_AMOUNT = 3

    private val template: String get() = BuildConfig.DONATE_URL.trim()

    val enabled: Boolean get() = template.startsWith("https://")

    /** Si el enlace acepta un monto, la pantalla deja elegirlo. */
    val choosesAmount: Boolean get() = !enabled || "{amount}" in template

    /** [amount] null = "otro": la página de pago deja escribir cuánto. */
    fun link(amount: Int?): Uri {
        val url = if (amount != null) template.replace("{amount}", amount.toString())
        else template.replace(Regex("/?\\{amount}[A-Za-z]*"), "")
        return Uri.parse(url)
    }

    /** Nombre del sitio de pago para mostrarlo ("paypal.me"). */
    fun host(): String = Uri.parse(template.replace("{amount}", "")).host?.removePrefix("www.") ?: ""
}
