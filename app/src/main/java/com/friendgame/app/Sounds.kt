package com.friendgame.app

/** Sonidos integrados (generados con tools/generate_sounds.py). */
class Sound(val key: String, val emoji: String, val label: String, val color: Int) {
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
    )

    fun byKey(key: String): Sound? = all.firstOrNull { it.key == key }
}
