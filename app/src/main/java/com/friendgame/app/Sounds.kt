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
 * La «vibe» o categoría de un sonido: qué tono tiene (risa, fiesta, susto…). Le da su color, su
 * ícono, el fondo animado que aparece al sonar (ver [Scene]) y cómo reacciona la mascota; su
 * personalidad cambia según la que más mandes. No cambia cómo suena ni el volumen.
 *
 * [label]: el nombre corto de la categoría (filtros, tarjetas). [color]/[lip]: el color de la
 * tarjeta y su labio 3D; [ink]: texto oscuro encima (si no, blanco); [chip]: el color del texto
 * en un chip sin elegir, que se lee sobre el fondo oscuro.
 */
class Vibe(
    val key: String,
    val emoji: String,
    val name: String,
    val trait: String,
    val reaction: String,
    val label: String,
    val color: Int,
    val lip: Int,
    val chip: Int,
    val ink: Boolean,
    val icon: Int,
) {
    /** Color del texto sobre [color]. */
    val onColor: Int get() = if (ink) Palette.INK else Palette.TEXT
}

object Vibes {
    val TROLL = Vibe("troll", "😈", "Troll", "Travieso", "¡Jaja, buena broma! 😈", "Troll",
        0xFF8CE33A.toInt(), 0xFF4FA00F.toInt(), 0xFFA9EE6A.toInt(), true, R.drawable.ic_cat_troll)
    val RISA = Vibe("risa", "😂", "Risa", "Chistoso", "¡Qué risa! 😂", "Risa",
        0xFFFFC93C.toInt(), 0xFFD99A00.toInt(), 0xFFFFC93C.toInt(), true, R.drawable.ic_cat_risa)
    val FIESTA = Vibe("fiesta", "🎉", "Fiesta", "Fiestero", "¡A bailar! 🎉", "Fiesta",
        0xFFB44CFF.toInt(), 0xFF7A1FC2.toInt(), 0xFFD59BFF.toInt(), false, R.drawable.ic_cat_fiesta)
    val AMOR = Vibe("amor", "💖", "Amoroso", "Tierno", "Aww, qué lindo 💖", "Amor",
        0xFFFF7AB8.toInt(), 0xFFD6247A.toInt(), 0xFFFF9CCB.toInt(), true, R.drawable.ic_cat_amor)
    val SUSTO = Vibe("susto", "😱", "Susto", "Asustador", "¡Qué susto! 😱", "Susto",
        0xFFFF4D4D.toInt(), 0xFFB5232F.toInt(), 0xFFFF8A8A.toInt(), false, R.drawable.ic_cat_susto)
    val VOZ = Vibe("voz", "🗣️", "Voz", "Charlatán", "¡Te escuché! 🗣️", "Voz",
        0xFF48DBFB.toInt(), 0xFF1A9CC2.toInt(), 0xFF7FE6FF.toInt(), true, R.drawable.ic_cat_voz)

    /** En el orden de los filtros. */
    val all = listOf(RISA, FIESTA, SUSTO, AMOR, TROLL, VOZ)
    val default = TROLL

    fun byKey(key: String?): Vibe = all.firstOrNull { it.key == key } ?: default

    /** La que viene en un mensaje, o null si no viene o no la conocemos (versión más nueva). */
    fun known(key: String?): Vibe? = all.firstOrNull { it.key == key }
}
