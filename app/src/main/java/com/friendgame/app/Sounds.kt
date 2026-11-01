package com.friendgame.app

/**
 * Sonidos integrados. Los 12 primeros son originales (tools/generate_sounds.py); los demás son
 * grabaciones reales de BigSoundBank con licencia CC0 (ver tools/sounds/catalogo.json y
 * docs/legal/creditos.html). Cada botón se puede personalizar (ver Looks).
 *
 * [fallback]: un sonido original de la misma categoría que viaja en `s` para que una versión
 * vieja de la app (que no tiene este) igual haga sonar algo; el nuevo va en `x` (ver [wire]).
 */
class Sound(
    val key: String,
    val emoji: String,
    val label: String,
    val color: Int,
    val vibe: String = Vibes.TROLL.key,
    val fallback: String? = null,
) {
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
        // Grabaciones reales (CC0, BigSoundBank).
        Sound("carcajada", "🤣", "Carcajada", Vibes.RISA.color, Vibes.RISA.key, fallback = "pato"),
        Sound("risa_bebe", "👶", "Risa de bebé", Vibes.RISA.color, Vibes.RISA.key, fallback = "pato"),
        Sound("risa_ninos", "🧒", "Niños riendo", Vibes.RISA.color, Vibes.RISA.key, fallback = "pato"),
        Sound("risa_loca", "🤪", "Risa loca", Vibes.RISA.color, Vibes.RISA.key, fallback = "pato"),
        Sound("risa_contagiosa", "😆", "Risa contagiosa", Vibes.RISA.color, Vibes.RISA.key, fallback = "pato"),
        Sound("risa_explosiva", "😹", "Risa explosiva", Vibes.RISA.color, Vibes.RISA.key, fallback = "pato"),
        Sound("cosquillas", "🪶", "Cosquillas", Vibes.RISA.color, Vibes.RISA.key, fallback = "pato"),
        Sound("jajaja", "😂", "Jajaja", Vibes.RISA.color, Vibes.RISA.key, fallback = "pato"),
        Sound("ba_dum_tss", "🥁", "Ba dum tss", Vibes.RISA.color, Vibes.RISA.key, fallback = "pato"),
        Sound("burro", "🫏", "Burro", Vibes.RISA.color, Vibes.RISA.key, fallback = "pato"),
        Sound("aplausos", "👏", "Aplausos", Vibes.FIESTA.color, Vibes.FIESTA.key, fallback = "tada"),
        Sound("ovacion", "🙌", "Ovación", Vibes.FIESTA.color, Vibes.FIESTA.key, fallback = "tada"),
        Sound("espantasuegras", "🥳", "Espantasuegras", Vibes.FIESTA.color, Vibes.FIESTA.key, fallback = "tada"),
        Sound("champana", "🍾", "Champaña", Vibes.FIESTA.color, Vibes.FIESTA.key, fallback = "tada"),
        Sound("fuegos", "🎆", "Fuegos artificiales", Vibes.FIESTA.color, Vibes.FIESTA.key, fallback = "tada"),
        Sound("cumpleanos", "🎂", "Cumpleaños feliz", Vibes.FIESTA.color, Vibes.FIESTA.key, fallback = "tada"),
        Sound("cohetes", "🎇", "Cohetes", Vibes.FIESTA.color, Vibes.FIESTA.key, fallback = "tada"),
        Sound("pito", "📣", "Pito", Vibes.FIESTA.color, Vibes.FIESTA.key, fallback = "tada"),
        Sound("montana_rusa", "🎢", "Montaña rusa", Vibes.SUSTO.color, Vibes.SUSTO.key, fallback = "sirena"),
        Sound("fantasma", "👻", "Fantasma", Vibes.SUSTO.color, Vibes.SUSTO.key, fallback = "sirena"),
        Sound("zombi", "🧟", "Zombi", Vibes.SUSTO.color, Vibes.SUSTO.key, fallback = "sirena"),
        Sound("trueno", "⛈️", "Trueno", Vibes.SUSTO.color, Vibes.SUSTO.key, fallback = "sirena"),
        Sound("puerta_chirria", "🚪", "Puerta que chirría", Vibes.SUSTO.color, Vibes.SUSTO.key, fallback = "sirena"),
        Sound("vidrio_roto", "🪟", "Vidrio roto", Vibes.SUSTO.color, Vibes.SUSTO.key, fallback = "sirena"),
        Sound("globo", "🎈", "Globo que revienta", Vibes.SUSTO.color, Vibes.SUSTO.key, fallback = "sirena"),
        Sound("despertador", "⏰", "Despertador", Vibes.SUSTO.color, Vibes.SUSTO.key, fallback = "sirena"),
        Sound("halloween", "🎃", "Halloween", Vibes.SUSTO.color, Vibes.SUSTO.key, fallback = "sirena"),
        Sound("explosion", "💥", "Explosión", Vibes.SUSTO.color, Vibes.SUSTO.key, fallback = "sirena"),
        Sound("tumba", "⚰️", "Tumba que se abre", Vibes.SUSTO.color, Vibes.SUSTO.key, fallback = "sirena"),
        Sound("petardo", "🧨", "Petardo", Vibes.SUSTO.color, Vibes.SUSTO.key, fallback = "sirena"),
        Sound("besitos", "💋", "Besitos", Vibes.AMOR.color, Vibes.AMOR.key, fallback = "campana"),
        Sound("ronroneo", "🐈", "Ronroneo", Vibes.AMOR.color, Vibes.AMOR.key, fallback = "campana"),
        Sound("latidos", "💓", "Latidos", Vibes.AMOR.color, Vibes.AMOR.key, fallback = "campana"),
        Sound("cajita_musical", "🎶", "Cajita musical", Vibes.AMOR.color, Vibes.AMOR.key, fallback = "campana"),
        Sound("marcha_nupcial", "💍", "Marcha nupcial", Vibes.AMOR.color, Vibes.AMOR.key, fallback = "campana"),
        Sound("campanitas", "✨", "Campanitas", Vibes.AMOR.color, Vibes.AMOR.key, fallback = "campana"),
        Sound("suspiro", "😌", "Suspiro", Vibes.AMOR.color, Vibes.AMOR.key, fallback = "campana"),
        Sound("bebe_feliz", "🍼", "Bebé feliz", Vibes.AMOR.color, Vibes.AMOR.key, fallback = "campana"),
        Sound("pajarito", "🐦", "Pajarito", Vibes.AMOR.color, Vibes.AMOR.key, fallback = "campana"),
        Sound("gatito", "🐱", "Gatito", Vibes.AMOR.color, Vibes.AMOR.key, fallback = "campana"),
        Sound("pedos", "💩", "Pedos", Vibes.TROLL.color, Vibes.TROLL.key, fallback = "pedorreta"),
        Sound("eructo", "🤢", "Eructo", Vibes.TROLL.color, Vibes.TROLL.key, fallback = "pedorreta"),
        Sound("inodoro", "🚽", "Inodoro", Vibes.TROLL.color, Vibes.TROLL.key, fallback = "pedorreta"),
        Sound("ronquido", "😴", "Ronquido", Vibes.TROLL.color, Vibes.TROLL.key, fallback = "pedorreta"),
        Sound("estornudo", "🤧", "Estornudo", Vibes.TROLL.color, Vibes.TROLL.key, fallback = "pedorreta"),
        Sound("gallo", "🐓", "Gallo", Vibes.TROLL.color, Vibes.TROLL.key, fallback = "pedorreta"),
        Sound("cabra", "🐐", "Cabra", Vibes.TROLL.color, Vibes.TROLL.key, fallback = "pedorreta"),
        Sound("vaca", "🐄", "Vaca", Vibes.TROLL.color, Vibes.TROLL.key, fallback = "pedorreta"),
        Sound("chancho", "🐷", "Chancho", Vibes.TROLL.color, Vibes.TROLL.key, fallback = "pedorreta"),
        Sound("gargaras", "🫗", "Gárgaras", Vibes.TROLL.color, Vibes.TROLL.key, fallback = "pedorreta"),
        Sound("diana", "🎺", "¡A despertar!", Vibes.TROLL.color, Vibes.TROLL.key, fallback = "pedorreta"),
        Sound("gong", "🔔", "Gong", Vibes.TROLL.color, Vibes.TROLL.key, fallback = "pedorreta"),
        Sound("perro", "🐕", "Perro", Vibes.TROLL.color, Vibes.TROLL.key, fallback = "pedorreta"),
        Sound("risa_bruja", "🧙", "Risa malvada", Vibes.TROLL.color, Vibes.TROLL.key, fallback = "pedorreta"),
        Sound("jo_jo_jo", "🎅", "¡Jo, jo, jo!", Vibes.VOZ.color, Vibes.VOZ.key, fallback = "boing"),
        Sound("sorpresa", "😮", "¡Oh, sorpresa!", Vibes.VOZ.color, Vibes.VOZ.key, fallback = "boing"),
        Sound("cuenta_regresiva", "⏱️", "Cuenta regresiva", Vibes.VOZ.color, Vibes.VOZ.key, fallback = "boing"),
        Sound("bostezo", "🥱", "Bostezo", Vibes.VOZ.color, Vibes.VOZ.key, fallback = "boing"),
        Sound("tos", "😷", "Tos", Vibes.VOZ.color, Vibes.VOZ.key, fallback = "boing"),
        Sound("silbido", "😗", "Silbido", Vibes.VOZ.color, Vibes.VOZ.key, fallback = "boing"),
        Sound("gallina_imitada", "🐔", "Gallina imitada", Vibes.VOZ.color, Vibes.VOZ.key, fallback = "boing"),
        Sound("miau_imitado", "😼", "Miau imitado", Vibes.VOZ.color, Vibes.VOZ.key, fallback = "boing"),
        Sound("mensaje", "📨", "You have a message", Vibes.VOZ.color, Vibes.VOZ.key, fallback = "boing"),
        Sound("pedo_robot", "🤖", "Pedo robot", Vibes.VOZ.color, Vibes.VOZ.key, fallback = "boing"),
    )

    private val index: Map<String, Sound> by lazy { all.associateBy { it.key } }

    fun byKey(key: String): Sound? = index[key]

    /** Los sonidos de una categoría, en el orden del catálogo. */
    fun ofVibe(vibe: String): List<Sound> = all.filter { it.vibe == vibe }

    /** Cómo viaja un sonido: `s` (lo que entiende cualquier versión) y, si es nuevo, `x`. */
    fun wire(key: String): Pair<String, String?> {
        val sound = byKey(key) ?: return key to null
        return if (sound.fallback != null) sound.fallback to sound.key else key to null
    }

    /** El sonido que llegó: el nuevo (`x`) si esta versión lo tiene; si no, el de `s`. */
    fun fromWire(s: String, x: String?): String = x?.takeIf { byKey(it) != null } ?: s
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
