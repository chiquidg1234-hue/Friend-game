package com.friendgame.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * La tienda de la mascota, como en Pou: comida que se guarda en el refri, duchas especiales,
 * estilos para su cuarto, muebles para decorarlo, y colores de piel y de ojos (su «avatar»).
 * Todo se compra con puntos de la app, nunca con dinero (ver docs/PSICOLOGIA.md).
 */
object PetShop {
    enum class Result { OK, NO_POINTS, LOCKED }

    /** Comida: llena el hambre; las ricas además le suben la alegría. */
    class Food(val emoji: String, val name: String, val cost: Int, val treat: Boolean = false)

    val FOODS = listOf(
        Food("🍎", "Manzana", 4),
        Food("🥕", "Zanahoria", 4),
        Food("🍌", "Plátano", 5),
        Food("🍉", "Sandía", 6),
        Food("🍪", "Galleta", 6, treat = true),
        Food("🍩", "Dona", 8, treat = true),
        Food("🍦", "Helado", 8, treat = true),
        Food("🍕", "Pizza", 10),
        Food("🌭", "Hot dog", 10),
        Food("🍔", "Hamburguesa", 12),
        Food("🍣", "Sushi", 16),
        Food("🎂", "Torta", 20, treat = true),
    )

    /** Duchas: todas la dejan limpia; las especiales además la ponen feliz y se ven distintas. */
    class Shower(val key: String, val emoji: String, val name: String, val cost: Int, val drops: IntArray, val level: Int = 1)

    val SHOWERS = listOf(
        Shower("", "🚿", "Ducha normal", 0, intArrayOf(0xCC74C0FC.toInt())),
        Shower("arcoiris", "🌈", "Ducha arcoíris", 40, intArrayOf(0xCCFF6B6B.toInt(), 0xCCFFC93C.toInt(), 0xCC3DDC97.toInt(), 0xCC48DBFB.toInt(), 0xCCB44CFF.toInt())),
        Shower("burbujas", "🫧", "Ducha de burbujas", 60, intArrayOf(0xCCFFFFFF.toInt(), 0xCCC4EBFF.toInt())),
        Shower("confeti", "🎉", "Ducha de confeti", 90, intArrayOf(0xEEFF4FA3.toInt(), 0xEEFFC93C.toInt(), 0xEE48DBFB.toInt()), level = 3),
        Shower("oro", "✨", "Ducha de oro", 150, intArrayOf(0xEEFFD447.toInt(), 0xEEFFF1A8.toInt()), level = 5),
    )

    /** Estilo de todo el cuarto: pared (arriba y abajo) y piso. */
    class RoomStyle(val key: String, val emoji: String, val name: String, val cost: Int, val wallTop: Int, val wallBottom: Int, val floor: Int, val level: Int = 1)

    val ROOMS = listOf(
        RoomStyle("", "🌙", "Clásico", 0, 0, 0, 0),
        RoomStyle("playa", "🏖️", "Playa", 60, 0xFF6FD3FF.toInt(), 0xFFB9EEFF.toInt(), 0xFFF5D38A.toInt()),
        RoomStyle("bosque", "🌲", "Bosque", 60, 0xFF2F7A52.toInt(), 0xFF1E5A3C.toInt(), 0xFF7A5232.toInt()),
        RoomStyle("dulces", "🍭", "Dulces", 70, 0xFFFFB3D9.toInt(), 0xFFFF8CC6.toInt(), 0xFFB98CFF.toInt()),
        RoomStyle("espacio", "🚀", "Espacio", 90, 0xFF0B0B2E.toInt(), 0xFF1B1257.toInt(), 0xFF3B2A7A.toInt(), level = 2),
        RoomStyle("neon", "🌃", "Neón", 110, 0xFF1A0B33.toInt(), 0xFF3A0F5C.toInt(), 0xFFFF2E9A.toInt(), level = 3),
        RoomStyle("castillo", "🏰", "Castillo", 150, 0xFF6B6F86.toInt(), 0xFF4A4E66.toInt(), 0xFF8A5A2E.toInt(), level = 5),
    )

    /** Dónde va un mueble en el cuarto (uno por lugar). */
    enum class Place(val title: String) { WALL_LEFT("Pared izquierda"), WALL_RIGHT("Pared derecha"), FLOOR_LEFT("Piso izquierdo"), FLOOR_RIGHT("Piso derecho") }

    class Decor(val key: String, val emoji: String, val name: String, val cost: Int, val place: Place, val level: Int = 1)

    val DECOR = listOf(
        Decor("globos", "🎈", "Globos", 20, Place.WALL_LEFT),
        Decor("arcoiris", "🌈", "Arcoíris", 45, Place.WALL_LEFT),
        Decor("estrella", "⭐", "Estrellas", 35, Place.WALL_LEFT),
        Decor("cuadro", "🖼️", "Cuadro", 30, Place.WALL_RIGHT),
        Decor("tele", "📺", "Tele", 60, Place.WALL_RIGHT),
        Decor("reloj", "🕰️", "Reloj", 40, Place.WALL_RIGHT),
        Decor("planta", "🪴", "Planta", 25, Place.FLOOR_LEFT),
        Decor("guitarra", "🎸", "Guitarra", 50, Place.FLOOR_LEFT),
        Decor("pecera", "🐠", "Pecera", 55, Place.FLOOR_LEFT, level = 2),
        Decor("peluche", "🧸", "Peluche", 30, Place.FLOOR_RIGHT),
        Decor("sofa", "🛋️", "Sofá", 70, Place.FLOOR_RIGHT),
        Decor("arcade", "🕹️", "Máquina arcade", 90, Place.FLOOR_RIGHT, level = 3),
        Decor("trofeo", "🏆", "Trofeo gigante", 120, Place.FLOOR_RIGHT, level = 5),
    )

    /** Color de piel (el cuerpo) y de ojos: la parte «avatar» de tu mascota. */
    class Skin(val key: String, val name: String, val tone: PetArt.Tone?, val cost: Int, val level: Int = 1) {
        /** El color que se muestra en la muestra (el del medio). */
        val swatch: Int get() = tone?.base ?: 0
    }

    val SKINS = listOf(
        Skin("", "Original", null, 0),
        Skin("chicle", "Chicle", PetArt.Tone(0xFFFFD6E8.toInt(), 0xFFFF7AB8.toInt(), 0xFFC2367A.toInt()), 30),
        Skin("menta", "Menta", PetArt.Tone(0xFFD3FFEA.toInt(), 0xFF3DDC97.toInt(), 0xFF1A8F5C.toInt()), 30),
        Skin("lila", "Lila", PetArt.Tone(0xFFE9DDFF.toInt(), 0xFFA78BFA.toInt(), 0xFF6D45D6.toInt()), 30),
        Skin("sol", "Sol", PetArt.Tone(0xFFFFF4C2.toInt(), 0xFFFFC93C.toInt(), 0xFFD98E00.toInt()), 30),
        Skin("cielo", "Cielo", PetArt.Tone(0xFFD6F1FF.toInt(), 0xFF48B6F5.toInt(), 0xFF1A6FB8.toInt()), 30),
        Skin("coral", "Coral", PetArt.Tone(0xFFFFDCCF.toInt(), 0xFFFF7F5F.toInt(), 0xFFC4452A.toInt()), 40),
        Skin("nieve", "Nieve", PetArt.Tone(0xFFFFFFFF.toInt(), 0xFFEEF1FA.toInt(), 0xFFAEB6CF.toInt()), 40),
        Skin("noche", "Noche", PetArt.Tone(0xFF7A7EB0.toInt(), 0xFF3C407A.toInt(), 0xFF181A40.toInt()), 50, level = 3),
        Skin("oro", "Oro", PetArt.Tone(0xFFFFF6BF.toInt(), 0xFFFFD447.toInt(), 0xFFC98A00.toInt()), 120, level = 5),
        Skin("galaxia", "Galaxia", PetArt.Tone(0xFFC9A8FF.toInt(), 0xFF7B3FE4.toInt(), 0xFF2A0F6B.toInt()), 150, level = 7),
    )

    val EYES = listOf(
        Skin("", "Oscuros", null, 0),
        Skin("azul", "Azules", PetArt.Tone(0xFF9AD0FF.toInt(), 0xFF2E7CD6.toInt(), 0xFF0F3A73.toInt()), 20),
        Skin("verde", "Verdes", PetArt.Tone(0xFFA8F0C0.toInt(), 0xFF22A35A.toInt(), 0xFF0C5A2E.toInt()), 20),
        Skin("cafe", "Café", PetArt.Tone(0xFFD9A774.toInt(), 0xFF8A5A2E.toInt(), 0xFF4A2C12.toInt()), 20),
        Skin("morado", "Morados", PetArt.Tone(0xFFD0B3FF.toInt(), 0xFF7B3FE4.toInt(), 0xFF3A1478.toInt()), 25),
        Skin("rojo", "Rojos", PetArt.Tone(0xFFFF9B9B.toInt(), 0xFFE03131.toInt(), 0xFF7A1010.toInt()), 30),
        Skin("dorado", "Dorados", PetArt.Tone(0xFFFFF1A8.toInt(), 0xFFFFC93C.toInt(), 0xFFB07800.toInt()), 40, level = 3),
    )

    private lateinit var prefs: SharedPreferences

    @Synchronized
    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences("friendgame-petshop", Context.MODE_PRIVATE)
        }
    }

    private val ready: Boolean get() = ::prefs.isInitialized

    // ---------------------------------------------------------------- Lo que tienes

    @Synchronized
    fun owns(id: String, cost: Int): Boolean = cost == 0 || (ready && (prefs.getStringSet("owned", emptySet()) ?: emptySet()).contains(id))

    /** Compra algo que se queda (ducha, estilo, mueble, color). [id]: «tipo:clave». */
    @Synchronized
    fun buy(id: String, cost: Int, level: Int): Result {
        if (owns(id, cost)) return Result.OK
        if (Points.level().number < level) return Result.LOCKED
        if (!Points.spend(cost)) return Result.NO_POINTS
        val owned = HashSet(prefs.getStringSet("owned", emptySet()) ?: emptySet())
        owned.add(id)
        prefs.edit().putStringSet("owned", owned).apply()
        Store.notifyChanged()
        return Result.OK
    }

    private fun get(key: String): String = if (ready) prefs.getString(key, "") ?: "" else ""

    private fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        Store.notifyChanged()
    }

    var skin: String
        get() = get("skin")
        set(value) = set("skin", value)

    var eyes: String
        get() = get("eyes")
        set(value) = set("eyes", value)

    var shower: String
        get() = get("shower")
        set(value) = set("shower", value)

    var room: String
        get() = get("room")
        set(value) = set("room", value)

    fun skinTone(key: String = skin): PetArt.Tone? = SKINS.firstOrNull { it.key == key }?.tone

    fun eyeTone(key: String = eyes): PetArt.Tone? = EYES.firstOrNull { it.key == key }?.tone

    fun currentShower(): Shower = SHOWERS.firstOrNull { it.key == shower } ?: SHOWERS[0]

    fun currentRoom(): RoomStyle = ROOMS.firstOrNull { it.key == room } ?: ROOMS[0]

    /** Mueble puesto en cada lugar del cuarto. */
    fun placed(place: Place): Decor? = DECOR.firstOrNull { it.key == get("decor_${place.name}") }

    /** Pone un mueble en su lugar (o lo saca si ya estaba). */
    fun toggleDecor(decor: Decor) {
        val key = "decor_${decor.place.name}"
        set(key, if (get(key) == decor.key) "" else decor.key)
    }

    // ---------------------------------------------------------------- El refri

    @Synchronized
    fun stock(): Map<String, Int> {
        if (!ready) return emptyMap()
        val json = try {
            JSONObject(prefs.getString("food", "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        return FOODS.mapNotNull { food -> json.optInt(food.emoji, 0).takeIf { it > 0 }?.let { food.emoji to it } }.toMap()
    }

    @Synchronized
    private fun putStock(stock: Map<String, Int>) {
        val json = JSONObject()
        stock.forEach { (k, v) -> if (v > 0) json.put(k, v) }
        prefs.edit().putString("food", json.toString()).apply()
        Store.notifyChanged()
    }

    /** Compra una porción de comida (queda en el refri). */
    @Synchronized
    fun buyFood(food: Food): Result {
        if (!Points.spend(food.cost)) return Result.NO_POINTS
        val stock = stock().toMutableMap()
        stock[food.emoji] = (stock[food.emoji] ?: 0) + 1
        putStock(stock)
        return Result.OK
    }

    /** Saca una porción del refri. false si no quedaba. */
    @Synchronized
    fun takeFood(emoji: String): Boolean {
        val stock = stock().toMutableMap()
        val left = stock[emoji] ?: 0
        if (left <= 0) return false
        stock[emoji] = left - 1
        putStock(stock)
        return true
    }

    fun food(emoji: String): Food? = FOODS.firstOrNull { it.emoji == emoji }

    /** La primera vez: un poco de comida de regalo para que no empiece con el refri vacío. */
    @Synchronized
    fun welcomeGift() {
        if (!ready || prefs.getBoolean("gift", false)) return
        prefs.edit().putBoolean("gift", true).apply()
        putStock(mapOf("🍎" to 3, "🥕" to 2, "🍪" to 1))
    }
}
