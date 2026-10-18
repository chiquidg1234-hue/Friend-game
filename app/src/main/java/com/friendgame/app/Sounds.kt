package com.friendgame.app

/**
 * Sonidos integrados (generados con tools/generate_sounds.py). Los de [level] mayor que 1 se
 * desbloquean al subir de nivel; escucharlos siempre se puede, aunque te los mande otro.
 */
class Sound(val key: String, val emoji: String, val label: String, val color: Int, val level: Int = 1) {
    val asset: String get() = "sounds/$key.ogg"
}

object Sounds {
    val all = listOf(
        Sound("bocina", "📯", "Bocina", 0xFFFF6B6B.toInt()),
        Sound("sirena", "🚨", "Sirena", 0xFFFF9F43.toInt()),
        Sound("alarma", "⏰", "Alarma", 0xFFFECA57.toInt()),
        Sound("pato", "🦆", "Pato", 0xFF1DD1A1.toInt()),
        Sound("redoble", "🥁", "Redoble", 0xFF54A0FF.toInt()),
        Sound("laser", "👾", "Láser", 0xFFA29BFE.toInt()),
        Sound("campana", "🔔", "Campana", 0xFFFFD166.toInt()),
        Sound("trompeta", "🎺", "Fracaso", 0xFF48DBFB.toInt()),
        Sound("boing", "🤸", "Boing", 0xFFFF9FF3.toInt()),
        Sound("pedorreta", "💨", "Pedorreta", 0xFFC8D6E5.toInt()),
        Sound("tada", "🎉", "¡Ta-dá!", 0xFF55EFC4.toInt()),
        Sound("grillos", "🦗", "Grillos", 0xFFB8E994.toInt()),
        Sound("timbre", "🛎️", "Timbre", 0xFFFFB86B.toInt(), level = 2),
        Sound("moneda", "🪙", "Moneda", 0xFFFFE066.toInt(), level = 3),
        Sound("silbato", "😤", "Silbato", 0xFF7EE8FA.toInt(), level = 4),
        Sound("gallo", "🐓", "Gallo", 0xFFFF8E72.toInt(), level = 5),
        Sound("risa", "🤣", "Risa", 0xFFB2F7A1.toInt(), level = 6),
        Sound("gong", "🥢", "Gong", 0xFFE2B4FF.toInt(), level = 7),
        Sound("ovni", "🛸", "Ovni", 0xFF8FD3FE.toInt(), level = 8),
        Sound("explosion", "💥", "Explosión", 0xFFFF7A7A.toInt(), level = 9),
    )

    fun byKey(key: String): Sound? = all.firstOrNull { it.key == key }

    /** Los que ya puedes mandar con tu nivel. */
    fun unlocked(level: Int): List<Sound> = all.filter { it.level <= level }
}
