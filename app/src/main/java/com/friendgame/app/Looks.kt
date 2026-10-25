package com.friendgame.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import org.json.JSONException
import org.json.JSONObject

/**
 * Personalización: el emoji, el color y el nombre de cada botón de sonido, tu avatar, el tema
 * de cada chat. Los colores degradados se desbloquean subiendo de nivel. Tu avatar y tu mascota
 * viajan en cada mensaje, así tus amigos los ven.
 */
object Looks {

    /** Un color (o un degradado, si tiene más de uno). */
    class Swatch(val key: String, val name: String, val colors: IntArray, val level: Int = 1) {
        val main: Int get() = colors[0]

        fun drawable(context: Context, radiusDp: Int): GradientDrawable {
            val shades = colors
            return GradientDrawable().apply {
                if (shades.size == 1) setColor(shades[0]) else {
                    colors = shades
                    orientation = GradientDrawable.Orientation.TL_BR
                }
                cornerRadius = context.dp(radiusDp).toFloat()
            }
        }

        fun oval(context: Context): GradientDrawable = drawable(context, 0).apply { shape = GradientDrawable.OVAL }
    }

    val SWATCHES = listOf(
        Swatch("coral", "Coral", intArrayOf(0xFFFF6B6B.toInt())),
        Swatch("naranja", "Naranja", intArrayOf(0xFFFF9F43.toInt())),
        Swatch("amarillo", "Amarillo", intArrayOf(0xFFFECA57.toInt())),
        Swatch("menta", "Menta", intArrayOf(0xFF1DD1A1.toInt())),
        Swatch("azul", "Azul", intArrayOf(0xFF54A0FF.toInt())),
        Swatch("lila", "Lila", intArrayOf(0xFFA29BFE.toInt())),
        Swatch("rosa", "Rosa", intArrayOf(0xFFFF9FF3.toInt())),
        Swatch("celeste", "Celeste", intArrayOf(0xFF48DBFB.toInt())),
        Swatch("lima", "Lima", intArrayOf(0xFFB8E994.toInt())),
        Swatch("nube", "Nube", intArrayOf(0xFFC8D6E5.toInt())),
        Swatch("atardecer", "Atardecer", intArrayOf(0xFFFFB86B.toInt(), 0xFFFF4FA3.toInt()), level = 2),
        Swatch("oceano", "Océano", intArrayOf(0xFF48DBFB.toInt(), 0xFF7B6CFF.toInt()), level = 3),
        Swatch("neon", "Neón", intArrayOf(0xFF3DDC97.toInt(), 0xFF00D2FF.toInt()), level = 4),
        Swatch("fuego", "Fuego", intArrayOf(0xFFFFC93C.toInt(), 0xFFFF3B3B.toInt()), level = 5),
        Swatch("galaxia", "Galaxia", intArrayOf(0xFFA29BFE.toInt(), 0xFFFF4FA3.toInt(), 0xFF48DBFB.toInt()), level = 6),
        Swatch("oro", "Oro", intArrayOf(0xFFFFE9A8.toInt(), 0xFFFFB300.toInt()), level = 8),
    )

    fun swatch(key: String?): Swatch? = SWATCHES.firstOrNull { it.key == key }

    /** Emojis para elegir rápido; también se puede escribir cualquiera. */
    val EMOJIS = listOf(
        "😂", "🤣", "😱", "😈", "👻", "💀", "🤡", "👽", "🤖", "😎", "🥳", "😴",
        "🐸", "🐱", "🐶", "🐔", "🐷", "🐮", "🦁", "🐵", "🦄", "🐉", "🦆", "🦗",
        "🔥", "💥", "⚡", "💣", "🚨", "📢", "📯", "🔔", "🎺", "🥁", "🎸", "🎤",
        "🍕", "🍔", "⚽", "🏀", "🎮", "💩", "❤️", "💜", "⭐", "🌈", "🎉", "👾",
    )

    /** Cómo se ve un botón de sonido. Lo que es null usa el valor de fábrica. */
    class Look(val emoji: String? = null, val swatch: String? = null, val label: String? = null)

    /** Tema de un chat: fondo y colores de los globos. */
    class Theme(
        val key: String,
        val name: String,
        val background: IntArray,
        val mine: Int,
        val mineText: Int,
        val theirs: Int,
        val theirsText: Int,
        val level: Int = 1,
    )

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val INK = 0xFF150C2C.toInt()

    val THEMES = listOf(
        Theme("noche", "Noche", intArrayOf(0xFF150C2C.toInt()), 0xFFFF4FA3.toInt(), INK, 0xFF231848.toInt(), WHITE),
        Theme("uva", "Uva", intArrayOf(0xFF2A1458.toInt()), 0xFFFFC93C.toInt(), INK, 0xFF3D2475.toInt(), WHITE),
        Theme("bosque", "Bosque", intArrayOf(0xFF0E2A22.toInt()), 0xFF3DDC97.toInt(), INK, 0xFF17423A.toInt(), WHITE),
        Theme("mar", "Mar", intArrayOf(0xFF0B2240.toInt()), 0xFF48DBFB.toInt(), INK, 0xFF16365E.toInt(), WHITE),
        Theme("atardecer", "Atardecer", intArrayOf(0xFF3A1236.toInt(), 0xFF5A2A1A.toInt()), 0xFFFFB86B.toInt(), INK, 0xFF4E2147.toInt(), WHITE, level = 2),
        Theme("chicle", "Chicle", intArrayOf(0xFFFFD6EC.toInt(), 0xFFE3D9FF.toInt()), 0xFFFF4FA3.toInt(), WHITE, WHITE, INK, level = 3),
        Theme("neon", "Neón", intArrayOf(0xFF05121A.toInt(), 0xFF0A2E2A.toInt()), 0xFF00E5A0.toInt(), INK, 0xFF123A44.toInt(), 0xFF9FFFE4.toInt(), level = 4),
        Theme("galaxia", "Galaxia", intArrayOf(0xFF1B0F3B.toInt(), 0xFF3B0F3A.toInt(), 0xFF0F2B4B.toInt()), 0xFFA29BFE.toInt(), INK, 0xFF2C2358.toInt(), WHITE, level = 6),
        Theme("oro", "Oro", intArrayOf(0xFF2B2108.toInt(), 0xFF46350A.toInt()), 0xFFFFC93C.toInt(), INK, 0xFF4A3A12.toInt(), 0xFFFFE9A8.toInt(), level = 8),
    )

    fun theme(key: String?): Theme = THEMES.firstOrNull { it.key == key } ?: THEMES[0]

    /** Avatar y mascota de otra persona, según su último mensaje. */
    class Profile(val emoji: String, val swatch: String, val pet: String)

    private lateinit var prefs: SharedPreferences

    @Synchronized
    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences("friendgame-looks", Context.MODE_PRIVATE)
        }
    }

    private fun json(key: String): JSONObject = try {
        JSONObject(prefs.getString(key, "{}") ?: "{}")
    } catch (_: JSONException) {
        JSONObject()
    }

    /** Un emoji (o pocos caracteres) escrito a mano: corto y sin texto largo. */
    fun cleanEmoji(text: String): String? = text.trim().takeIf { it.isNotEmpty() && it.codePointCount(0, it.length) <= 4 }

    // ---- Botones ----

    /** [key]: la clave del sonido integrado ("bocina") o "c:id" para un audio propio. */
    @Synchronized
    fun button(key: String): Look {
        val o = json("buttons").optJSONObject(key) ?: return Look()
        return Look(o.optString("e").ifEmpty { null }, o.optString("c").ifEmpty { null }, o.optString("l").ifEmpty { null })
    }

    @Synchronized
    fun setButton(key: String, look: Look) {
        val all = json("buttons")
        val o = JSONObject()
        look.emoji?.let { o.put("e", it) }
        look.swatch?.let { o.put("c", it) }
        look.label?.let { o.put("l", it.trim().take(24)) }
        if (o.length() == 0) all.remove(key) else all.put(key, o)
        prefs.edit().putString("buttons", all.toString()).apply()
        Store.notifyChanged()
    }

    // ---- Mi avatar ----

    val myEmoji: String @Synchronized get() = prefs.getString("my_emoji", "") ?: ""

    val mySwatch: String @Synchronized get() = prefs.getString("my_swatch", "amarillo") ?: "amarillo"

    @Synchronized
    fun setMyAvatar(emoji: String, swatch: String) {
        prefs.edit().putString("my_emoji", emoji).putString("my_swatch", swatch).apply()
        Store.notifyChanged()
    }

    // ---- Fondo de la app ----

    /** [colors]: degradé de arriba abajo; siempre oscuro para que el texto blanco se lea. */
    class Background(val key: String, val name: String, val emoji: String, val colors: IntArray)

    val BACKGROUNDS = listOf(
        Background("default", "Noche violeta", "🌌", intArrayOf(0xFF1E1240.toInt(), 0xFF150C2C.toInt(), 0xFF110924.toInt())),
        Background("amor", "Corazones", "💘", intArrayOf(0xFF3A0E2A.toInt(), 0xFF2A0A26.toInt(), 0xFF180616.toInt())),
        Background("mar", "Mar", "🌊", intArrayOf(0xFF0B2A4A.toInt(), 0xFF0A1E3A.toInt(), 0xFF061326.toInt())),
        Background("bosque", "Bosque", "🌲", intArrayOf(0xFF10351F.toInt(), 0xFF0C2417.toInt(), 0xFF07160E.toInt())),
        Background("atardecer", "Atardecer", "🌆", intArrayOf(0xFF3A1A12.toInt(), 0xFF2A1020.toInt(), 0xFF160614.toInt())),
        Background("lava", "Lava", "🔥", intArrayOf(0xFF3A1206.toInt(), 0xFF2A0C06.toInt(), 0xFF160604.toInt())),
    )

    fun background(key: String? = null): Background {
        val k = key ?: (if (::prefs.isInitialized) prefs.getString("bg", "default") else "default")
        return BACKGROUNDS.firstOrNull { it.key == k } ?: BACKGROUNDS.first()
    }

    @Synchronized
    fun setBackground(key: String) {
        prefs.edit().putString("bg", key).apply()
        Store.notifyChanged()
    }

    // ---- Tema de cada chat ----

    @Synchronized
    fun chatTheme(chatKey: String): Theme = theme(json("themes").optString(chatKey))

    @Synchronized
    fun setChatTheme(chatKey: String, theme: Theme) {
        val all = json("themes").put(chatKey, theme.key)
        prefs.edit().putString("themes", all.toString()).apply()
        Store.notifyChanged()
    }

    // ---- Lo que ven los demás ----

    /** Agrega mi avatar y mi mascota a un mensaje que sale. */
    fun attach(payload: JSONObject): JSONObject {
        if (myEmoji.isNotEmpty()) payload.put("av", myEmoji)
        payload.put("ac", mySwatch)
        Pets.shareCode()?.let { payload.put("pet", it) }
        return payload
    }

    @Synchronized
    fun updateProfile(code: String, payload: JSONObject) {
        if (!payload.has("ac")) return
        val emoji = cleanEmoji(payload.optString("av")) ?: ""
        val swatch = swatch(payload.optString("ac"))?.key ?: "amarillo"
        val pet = payload.optString("pet").takeIf { it.matches(Regex("[a-z]{2,12}:[0-4]")) } ?: ""
        val all = json("profiles")
        val old = all.optJSONObject(code)
        if (old != null && old.optString("e") == emoji && old.optString("c") == swatch && old.optString("p") == pet) return
        all.put(code, JSONObject().put("e", emoji).put("c", swatch).put("p", pet))
        prefs.edit().putString("profiles", all.toString()).apply()
        Store.notifyChanged()
    }

    @Synchronized
    fun profile(code: String): Profile? {
        if (code == Store.myCode) return Profile(myEmoji, mySwatch, Pets.shareCode() ?: "")
        val o = json("profiles").optJSONObject(code) ?: return null
        return Profile(o.optString("e"), o.optString("c"), o.optString("p"))
    }

    @Synchronized
    fun clearProfiles() {
        prefs.edit().remove("profiles").apply()
    }
}

/**
 * Mascota: elige una y crece con los puntos que ganas desde que la adoptaste. Si pasa más de un
 * día sin que la alimentes, le da hambre; alimentarla cuesta puntos.
 */
object Pets {
    /** [food]: su comida favorita (cae cuando la alimentas; ver PetView). */
    class Species(val key: String, val emoji: String, val name: String, val level: Int = 1, val food: String = "🍖")

    val SPECIES = listOf(
        Species("gato", "🐱", "Gato", food = "🐟"),
        Species("perro", "🐶", "Perro", food = "🍖"),
        Species("zorro", "🦊", "Zorro", food = "🍒"),
        Species("rana", "🐸", "Rana", food = "🐛"),
        Species("conejo", "🐰", "Conejo", food = "🥕"),
        Species("pollito", "🐥", "Pollito", food = "🌽"),
        Species("oso", "🐻", "Oso", level = 2, food = "🍯"),
        Species("panda", "🐼", "Panda", level = 2, food = "🎋"),
        Species("pulpo", "🐙", "Pulpo", level = 3, food = "🦐"),
        Species("dragon", "🐉", "Dragón", level = 5, food = "🌶️"),
        Species("unicornio", "🦄", "Unicornio", level = 7, food = "🍓"),
    )

    class Stage(val number: Int, val name: String, val xp: Int)

    val STAGES = listOf(
        Stage(0, "Huevo", 0),
        Stage(1, "Bebé", 30),
        Stage(2, "Joven", 150),
        Stage(3, "Adulto", 500),
        Stage(4, "Legendario", 1200),
    )

    const val FEED_COST = 5
    private const val FEED_XP = 3
    private const val CARE_XP = 2

    enum class Mood(val text: String, val face: String) {
        HAPPY("Feliz", "😊"),
        HUNGRY("Con hambre", "😋"),
        SAD("Triste: te extraña", "🥺"),
    }

    /**
     * Las cuatro barras de cuidado (estilo Pou). Cada una está llena al cuidarla y baja sola con el
     * tiempo ([windowH] horas hasta vaciarse). [button]: el texto del botón para subirla.
     */
    enum class Care(val label: String, val emoji: String, val key: String, val windowH: Int, val button: String, val done: String) {
        HAMBRE("Hambre", "🍗", "pet_fed", 18, "Alimentar", "¡Ñam!"),
        ALEGRIA("Alegría", "💖", "pet_play", 14, "Jugar", "¡Qué divertido!"),
        ENERGIA("Energía", "⚡", "pet_sleep", 20, "Dormir", "Zzz…"),
        LIMPIEZA("Limpieza", "🛁", "pet_bath", 30, "Bañar", "¡Limpia!"),
    }

    enum class CareResult { OK, FULL, NO_POINTS }

    private lateinit var prefs: SharedPreferences

    @Synchronized
    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences("friendgame-looks", Context.MODE_PRIVATE)
        }
    }

    fun species(key: String?): Species? = SPECIES.firstOrNull { it.key == key }

    val current: Species? @Synchronized get() = species(prefs.getString("pet", null))

    val name: String @Synchronized get() = prefs.getString("pet_name", "") ?: ""

    val xp: Int
        @Synchronized get() = (Points.total() - prefs.getInt("pet_base", 0) + prefs.getInt("pet_bonus", 0)).coerceAtLeast(0)

    fun stage(xp: Int = this.xp): Stage = STAGES.last { xp >= it.xp }

    fun next(stage: Stage): Stage? = STAGES.getOrNull(stage.number + 1)

    /** Cuánto le queda de una barra, de 0 a 100 (100 recién cuidada, baja sola con las horas). */
    @Synchronized
    fun careLevel(care: Care): Int {
        val last = prefs.getLong(care.key, 0L)
        if (last == 0L) return 70
        val elapsed = System.currentTimeMillis() - last
        return (100 - elapsed * 100 / (care.windowH * 3600_000L)).toInt().coerceIn(0, 100)
    }

    /** La barra más baja: lo que la mascota necesita ahora. */
    fun mostUrgent(): Care = Care.entries.minByOrNull { careLevel(it) } ?: Care.HAMBRE

    fun mood(): Mood {
        val hunger = careLevel(Care.HAMBRE)
        val lowest = Care.entries.minOf { careLevel(it) }
        return when {
            lowest < 25 -> Mood.SAD
            hunger < 50 -> Mood.HUNGRY
            else -> Mood.HAPPY
        }
    }

    /**
     * Sube una barra al máximo. Alimentar cuesta puntos; jugar, dormir y bañar son gratis. No deja
     * repetir si ya está casi llena, para que no sea un truco de puntos.
     */
    @Synchronized
    fun care(care: Care): CareResult {
        if (careLevel(care) >= 90) return CareResult.FULL
        if (care == Care.HAMBRE && !Points.spend(FEED_COST)) return CareResult.NO_POINTS
        prefs.edit()
            .putLong(care.key, System.currentTimeMillis())
            .putInt("pet_bonus", prefs.getInt("pet_bonus", 0) + if (care == Care.HAMBRE) FEED_XP else CARE_XP)
            .apply()
        Store.notifyChanged()
        return CareResult.OK
    }

    /** Cómo se ve: huevo al principio; con corona al llegar a legendaria. */
    fun emoji(species: Species, stage: Int): String = when (stage) {
        0 -> "🥚"
        4 -> species.emoji + "👑"
        else -> species.emoji
    }

    @Synchronized
    fun adopt(species: Species, petName: String) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString("pet", species.key)
            .putString("pet_name", petName.trim().take(16).ifEmpty { species.name })
            .putInt("pet_base", Points.total())
            .putInt("pet_bonus", 0)
            .putLong("pet_fed", now)
            .putLong("pet_play", now)
            .putLong("pet_sleep", now)
            .putLong("pet_bath", now)
            .apply()
        Store.notifyChanged()
    }

    @Synchronized
    fun rename(petName: String) {
        prefs.edit().putString("pet_name", petName.trim().take(16)).apply()
        Store.notifyChanged()
    }

    /** Devuelve false si no te alcanzan los puntos o ya comió. */
    fun feed(): Boolean = care(Care.HAMBRE) == CareResult.OK

    fun ateRecently(): Boolean = careLevel(Care.HAMBRE) >= 90

    private val REACTIONS = listOf("ronronea", "mueve la cola", "da un saltito", "te mira con amor", "hace un ruidito feliz", "baila")

    fun reaction(): String = REACTIONS.random()

    /**
     * Lo que «dice» la mascota en su burbuja. No es una IA: son frases armadas según cómo está
     * (hambre, energía…), la hora y tu racha. Cambia sola para que se sienta viva.
     */
    @Synchronized
    fun says(): String {
        val nombre = name.ifEmpty { current?.name ?: "tu mascota" }
        val urgent = mostUrgent()
        if (careLevel(urgent) < 35) return when (urgent) {
            Care.HAMBRE -> "Tengo hambre… ¿me das ${current?.food ?: "🍗"}?"
            Care.ALEGRIA -> "¿Jugamos un ratito? 🥺"
            Care.ENERGIA -> "Tengo mucho sueño… 😴"
            Care.LIMPIEZA -> "Me quiero bañar 🛁"
        }
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val happy = listOf(
            "¡Hola! Soy $nombre 💕",
            "¿Le mandamos un sonido a alguien? 😼",
            "¡Me encanta estar contigo!",
            "¿Viste mi nivel? ✨",
            "Cuídame y crezco más grande 🌱",
        )
        return when {
            hour in 6..11 -> "¡Buenos días! ☀️"
            hour >= 22 || hour < 6 -> "Ya casi es hora de dormir… 🌙"
            else -> happy.random()
        }
    }

    /** "gato:2" para que tus amigos vean tu mascota. */
    fun shareCode(): String? = current?.let { "${it.key}:${stage().number}" }

    /** Emoji de la mascota de otra persona a partir de su [code] compartido. */
    fun emojiFor(code: String): String? {
        val species = species(code.substringBefore(':')) ?: return null
        return emoji(species, code.substringAfter(':').toIntOrNull()?.coerceIn(0, 4) ?: 0)
    }
}

/**
 * Poderes que se compran con puntos. Ninguno le quita el control a quien recibe: siempre puede
 * detener el sonido, silenciar o bloquear (la única excepción es un candado que los dos aceptaron).
 */
object Powers {
    class Power(val key: String, val emoji: String, val name: String, val text: String, val cost: Int)

    const val SUPER = "super"
    const val ECO = "eco"
    const val DOUBLE = "doble"

    val ALL = listOf(
        Power(SUPER, "🌟", "Súper sonido", "Tu próximo sonido llega con una pantalla dorada especial y una vibración más fuerte.", 30),
        Power(ECO, "🔁", "Eco", "Tu próximo sonido se repite hasta que lo detengan (máximo 10 segundos).", 20),
        Power(DOUBLE, "✖️", "Doble puntos", "Si ganas tu próximo reto de «El más rápido», ganas el doble de puntos.", 40),
    )

    fun byKey(key: String?): Power? = ALL.firstOrNull { it.key == key }

    private lateinit var prefs: SharedPreferences

    @Synchronized
    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences("friendgame-looks", Context.MODE_PRIVATE)
        }
    }

    /** Poder listo para el próximo sonido (Súper sonido o Eco). */
    val armed: Power? @Synchronized get() = byKey(prefs.getString("power_armed", null))

    val doubleReady: Boolean @Synchronized get() = prefs.getBoolean("power_double", false)

    /** Compra un poder. Devuelve false si no alcanzan los puntos o ya hay uno listo. */
    @Synchronized
    fun buy(power: Power): Boolean {
        if (power.key == DOUBLE) {
            if (doubleReady || !Points.spend(power.cost)) return false
            prefs.edit().putBoolean("power_double", true).apply()
        } else {
            if (armed != null || !Points.spend(power.cost)) return false
            prefs.edit().putString("power_armed", power.key).apply()
        }
        Store.notifyChanged()
        return true
    }

    /** Un poder de regalo (el cofre del día). Devuelve false si ya hay uno listo. */
    @Synchronized
    fun gift(key: String): Boolean {
        if (armed != null || byKey(key) == null || key == DOUBLE) return false
        prefs.edit().putString("power_armed", key).apply()
        Store.notifyChanged()
        return true
    }

    /** Devuelve los puntos de un poder que todavía no se usó. */
    @Synchronized
    fun cancel(power: Power) {
        val had = if (power.key == DOUBLE) doubleReady else armed?.key == power.key
        if (!had) return
        if (power.key == DOUBLE) prefs.edit().putBoolean("power_double", false).apply()
        else prefs.edit().remove("power_armed").apply()
        Points.refund(power.cost)
        Store.notifyChanged()
    }

    /** Se usa el poder listo en el sonido que sale. */
    @Synchronized
    fun take(): Power? {
        val power = armed ?: return null
        prefs.edit().remove("power_armed").apply()
        Store.notifyChanged()
        return power
    }

    /** Si el sonido no salió, el poder vuelve a quedar listo. */
    @Synchronized
    fun restore(power: Power) {
        prefs.edit().putString("power_armed", power.key).apply()
        Store.notifyChanged()
    }

    @Synchronized
    fun takeDouble(): Boolean {
        if (!doubleReady) return false
        prefs.edit().putBoolean("power_double", false).apply()
        return true
    }
}
