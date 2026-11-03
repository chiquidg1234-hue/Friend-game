package com.friendgame.app

import android.app.Activity
import android.view.View
import android.view.ViewGroup

/** Capturas de la organización nueva: las pestañas y las páginas de Tú (screenshots/fase-5). */
object Extra {
    private fun find(v: View, predicate: (View) -> Boolean): View? {
        if (predicate(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), predicate)?.let { return it }
        return null
    }

    private fun text(v: View): String = (v.contentDescription?.toString() ?: "") + " " + ((v as? android.widget.TextView)?.text?.toString() ?: "")

    fun tap(h: Hojas, a: Activity, what: String) {
        val root = a.window.decorView
        val v = find(root) { it.isShown && it.isClickable && text(it).trim().startsWith(what) }
            ?: find(root) { it.isShown && (it as? android.widget.TextView)?.text?.toString() == what }?.let { t -> generateSequence(t) { it.parent as? View }.firstOrNull { it.isClickable } }
            ?: error("No encontré $what")
        v.performClick()
        h.idle(500)
    }

    fun run(h: Hojas, a: Activity) {
        tap(h, a, "Pestaña Mascota")
        h.screen(a, "09-mascota-sin-adoptar.png")
        // Una mascota con piel, cuarto y muebles cambiados (lo de la tienda, sin gastar puntos).
        Pets.adopt(Pets.species("gato")!!, "Michi")
        a.getSharedPreferences("friendgame-looks", android.content.Context.MODE_PRIVATE).edit().putInt("pet_bonus", 600).apply()
        a.getSharedPreferences("friendgame-petshop", android.content.Context.MODE_PRIVATE).edit()
            .putStringSet("owned", setOf("skin:lila", "room:espacio", "decor:planta", "decor:tele", "decor:sofa", "decor:globos"))
            .putString("skin", "lila").putString("room", "espacio")
            .putString("decor_FLOOR_LEFT", "planta").putString("decor_WALL_RIGHT", "tele").putString("decor_FLOOR_RIGHT", "sofa").putString("decor_WALL_LEFT", "globos")
            .apply()
        h.idle(300)
        tap(h, a, "Pestaña Sonar")
        tap(h, a, "Pestaña Mascota")
        h.screen(a, "10-pestana-mascota.png")
        tap(h, a, "Armario")
        tap(h, a, "🎨 Piel")
        h.screen(a, "20-armario-piel.png")
        tap(h, a, "🎒 Espalda")
        h.screen(a, "21-armario-espalda.png")
        tap(h, a, "Tienda")
        h.screen(a, "22-tienda-comida.png")
        tap(h, a, "🛋️ Muebles")
        h.screen(a, "23-tienda-muebles.png")
        tap(h, a, "Pestaña Amigos")
        h.screen(a, "11-amigos.png")
        tap(h, a, "Sección Grupos")
        h.screen(a, "12-amigos-grupos.png")
        tap(h, a, "Sección Ranking")
        h.screen(a, "13-amigos-ranking.png")
        tap(h, a, "Pestaña Tú")
        h.screen(a, "14-tu.png")
        tap(h, a, "Tienda")
        h.screen(a, "15-tienda.png")
        tap(h, a, "Volver")
        tap(h, a, "Personalizar")
        h.screen(a, "16-personalizar.png")
        tap(h, a, "Volver")
        tap(h, a, "Logros")
        h.screen(a, "17-logros.png")
        tap(h, a, "Volver")
        tap(h, a, "Niveles y puntos")
        h.screen(a, "18-niveles.png")
        tap(h, a, "Volver")
        tap(h, a, "Pestaña Sonar")
        tap(h, a, "Abrir la Sonoteca")
        h.screen(a, "19-sonoteca.png")
    }
}
