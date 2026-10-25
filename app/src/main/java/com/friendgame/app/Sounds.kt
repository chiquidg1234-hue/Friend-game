package com.friendgame.app

/** Sonidos integrados (generados con tools/generate_sounds.py). Cada botón se puede personalizar (ver Looks). */
class Sound(val key: String, val emoji: String, val label: String, val color: Int, val vibe: String = Vibes.TROLL.key) {
    val asset: String get() = "sounds/$key.ogg"
}

object Sounds {
    val all = listOf(
        Sound("bocina", "📯", "Bocina", 0xFFFF6B6B.toInt(), Vibes.SUSTO.key),
        Sound("sirena", "🚨", "Sirena", 0xFFFF9F43.toInt(), Vibes.SUSTO.key),
        Sound("alarma", "⏰", "Alarma", 0xFFFECA57.toInt(), Vibes.SUSTO.key),
        Sound("pato", "🦆", "Pato", 0xFF1DD1A1.toInt(), Vibes.RISA.key),
        Sound("redoble", "🥁", "Redoble", 0xFF54A0FF.toInt(), Vibes.FIESTA.key),
        Sound("laser", "👾", "Láser", 0xFFA29BFE.toInt(), Vibes.TROLL.key),
        Sound("campana", "🔔", "Campana", 0xFFFFD166.toInt(), Vibes.AMOR.key),
        Sound("trompeta", "🎺", "Fracaso", 0xFF48DBFB.toInt(), Vibes.RISA.key),
        Sound("boing", "🤸", "Boing", 0xFFFF9FF3.toInt(), Vibes.RISA.key),
        Sound("pedorreta", "💨", "Pedorreta", 0xFFC8D6E5.toInt(), Vibes.TROLL.key),
        Sound("tada", "🎉", "¡Ta-dá!", 0xFF55EFC4.toInt(), Vibes.FIESTA.key),
        Sound("grillos", "🦗", "Grillos", 0xFFB8E994.toInt(), Vibes.RISA.key),
    )

    fun byKey(key: String): Sound? = all.firstOrNull { it.key == key }
}

/**
 * La «vibe» (emoción) de un sonido: qué tono tiene. La mascota la reconoce, reacciona y su
 * personalidad cambia según la que más mandes. No cambia cómo suena ni el volumen.
 */
class Vibe(val key: String, val emoji: String, val name: String, val trait: String, val reaction: String)

object Vibes {
    val TROLL = Vibe("troll", "😈", "Troll", "Travieso", "¡Jaja, buena broma! 😈")
    val RISA = Vibe("risa", "😂", "Risa", "Chistoso", "¡Qué risa! 😂")
    val FIESTA = Vibe("fiesta", "🎉", "Fiesta", "Fiestero", "¡A bailar! 🎉")
    val AMOR = Vibe("amor", "💖", "Amoroso", "Tierno", "Aww, qué lindo 💖")
    val SUSTO = Vibe("susto", "😱", "Susto", "Asustador", "¡Qué susto! 😱")

    val all = listOf(TROLL, RISA, FIESTA, AMOR, SUSTO)
    val default = TROLL

    fun byKey(key: String?): Vibe = all.firstOrNull { it.key == key } ?: default
}
