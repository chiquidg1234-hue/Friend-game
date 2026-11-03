package com.friendgame.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max

/**
 * Armario de la mascota: gorros, lentes y algo para el cuello, como en Pou. Se compran con puntos
 * (gastar no baja el nivel) y algunos se desbloquean subiendo de nivel. Solo se ven en tu celular:
 * no viajan en los mensajes.
 */
object Wardrobe {
    enum class Slot(val title: String, val emoji: String) { HAT("Gorros", "🎩"), EYES("Lentes", "🕶️"), NECK("Cuello", "🎀"), BACK("Espalda", "🎒") }

    class Item(val key: String, val slot: Slot, val emoji: String, val name: String, val cost: Int, val level: Int = 1)

    val ITEMS = listOf(
        Item("fiesta", Slot.HAT, "🥳", "Gorro de fiesta", 0),
        Item("gorra", Slot.HAT, "🧢", "Gorra", 20),
        Item("audifonos", Slot.HAT, "🎧", "Audífonos", 30),
        Item("flores", Slot.HAT, "🌸", "Corona de flores", 35, level = 2),
        Item("vaquero", Slot.HAT, "🤠", "Sombrero vaquero", 40, level = 2),
        Item("mago", Slot.HAT, "🧙", "Sombrero de mago", 60, level = 4),
        Item("lazo", Slot.HAT, "🎀", "Lazo", 15),
        Item("lana", Slot.HAT, "❄️", "Gorro de lana", 25),
        Item("chef", Slot.HAT, "👨‍🍳", "Gorro de chef", 35),
        Item("cuernos", Slot.HAT, "😈", "Cuernitos", 40, level = 2),
        Item("vikingo", Slot.HAT, "🛡️", "Casco vikingo", 70, level = 3),
        Item("corona", Slot.HAT, "👑", "Corona", 120, level = 5),
        Item("nerd", Slot.EYES, "🤓", "Lentes redondos", 15),
        Item("sol", Slot.EYES, "🕶️", "Lentes de sol", 25),
        Item("corazon", Slot.EYES, "😍", "Lentes de corazón", 45, level = 3),
        Item("estrella", Slot.EYES, "🤩", "Lentes de estrella", 35, level = 2),
        Item("visor", Slot.EYES, "🥽", "Visor futurista", 60, level = 3),
        Item("corbatin", Slot.NECK, "🎀", "Corbatín", 0),
        Item("bufanda", Slot.NECK, "🧣", "Bufanda", 25),
        Item("medalla", Slot.NECK, "🏅", "Medalla", 80, level = 5),
        Item("corbata", Slot.NECK, "👔", "Corbata", 30),
        Item("perlas", Slot.NECK, "🦪", "Collar de perlas", 40, level = 2),
        Item("cadena", Slot.NECK, "⛓️", "Cadena de oro", 90, level = 4),
        Item("mochila", Slot.BACK, "🎒", "Mochila", 30),
        Item("capa", Slot.BACK, "🦸", "Capa de héroe", 50),
        Item("murcielago", Slot.BACK, "🦇", "Alas de murciélago", 70, level = 2),
        Item("angel", Slot.BACK, "😇", "Alas de ángel", 80, level = 3),
        Item("cohete", Slot.BACK, "🚀", "Mochila cohete", 120, level = 5),
    )

    /** Lo que tiene puesto (null: nada en ese lugar). */
    class Outfit(val hat: String?, val eyes: String?, val neck: String?, val back: String? = null) {
        companion object {
            val NONE = Outfit(null, null, null)
        }
    }

    enum class Result { OK, NO_POINTS, LOCKED }

    private lateinit var prefs: SharedPreferences

    @Synchronized
    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences("friendgame-looks", Context.MODE_PRIVATE)
        }
    }

    fun item(key: String?): Item? = ITEMS.firstOrNull { it.key == key }

    fun bySlot(slot: Slot): List<Item> = ITEMS.filter { it.slot == slot }

    @Synchronized
    fun outfit(): Outfit {
        if (!::prefs.isInitialized) return Outfit.NONE
        return Outfit(prefs.getString("wear_hat", null), prefs.getString("wear_eyes", null), prefs.getString("wear_neck", null), prefs.getString("wear_back", null))
    }

    @Synchronized
    fun owns(item: Item): Boolean = item.cost == 0 || (prefs.getStringSet("owned", emptySet()) ?: emptySet()).contains(item.key)

    fun wearing(item: Item): Boolean = when (item.slot) {
        Slot.HAT -> outfit().hat == item.key
        Slot.EYES -> outfit().eyes == item.key
        Slot.NECK -> outfit().neck == item.key
        Slot.BACK -> outfit().back == item.key
    }

    /** Compra una prenda con puntos y se la pone. */
    @Synchronized
    fun buy(item: Item): Result {
        if (owns(item)) return Result.OK
        if (Points.level().number < item.level) return Result.LOCKED
        if (!Points.spend(item.cost)) return Result.NO_POINTS
        val owned = HashSet(prefs.getStringSet("owned", emptySet()) ?: emptySet())
        owned.add(item.key)
        prefs.edit().putStringSet("owned", owned).apply()
        put(item)
        return Result.OK
    }

    /** Se pone [item]; si ya lo tenía puesto, se lo saca. */
    @Synchronized
    fun toggle(item: Item) {
        if (!owns(item)) return
        if (wearing(item)) {
            prefs.edit().remove(slotKey(item.slot)).apply()
            Store.notifyChanged()
        } else {
            put(item)
        }
    }

    private fun put(item: Item) {
        prefs.edit().putString(slotKey(item.slot), item.key).apply()
        Store.notifyChanged()
    }

    private fun slotKey(slot: Slot) = when (slot) {
        Slot.HAT -> "wear_hat"
        Slot.EYES -> "wear_eyes"
        Slot.NECK -> "wear_neck"
        Slot.BACK -> "wear_back"
    }

    // ---------------------------------------------------------------- Dibujo (espacio de 200×200)

    private fun fill(color: Int, alpha: Float = 1f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.alpha = (255 * alpha).toInt()
    }

    private fun stroke(color: Int, width: Float, alpha: Float = 1f) = fill(color, alpha).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun vertical(top: Float, bottom: Float, a: Int, b: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, top, 0f, bottom, a, b, Shader.TileMode.CLAMP)
    }

    private class Piece(val path: Path, val paint: Paint)

    private fun svg(d: String) = PetArt.svg(d)

    private fun ovalPath(cx: Float, cy: Float, rx: Float, ry: Float) = Path().apply { addOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), Path.Direction.CW) }

    /** Gorros, armados para una cabeza estándar (arriba en y = 46, centro en x = 100). */
    private val hats: Map<String, List<Piece>> by lazy {
        val gold = 0xFFFFC93C.toInt()
        mapOf(
            "fiesta" to listOf(
                Piece(svg("M80 50 L100 4 L120 50 Z"), vertical(4f, 50f, 0xFFFF7AB8.toInt(), 0xFFB44CFF.toInt())),
                Piece(svg("M86 36 L114 36 M92 22 L108 22"), stroke(gold, 3.2f)),
                Piece(ovalPath(100f, 50f, 22f, 4f), fill(gold)),
                Piece(ovalPath(100f, 4f, 6f, 6f), fill(gold)),
            ),
            "gorra" to listOf(
                Piece(svg("M66 52 Q68 18 100 16 Q132 18 134 52 Z"), vertical(16f, 52f, 0xFF5B8CFF.toInt(), 0xFF2F55D4.toInt())),
                Piece(svg("M100 46 Q140 38 156 52 Q132 58 100 53 Z"), fill(0xFF22409E.toInt())),
                Piece(svg("M100 17 L100 50 M82 22 Q86 36 84 50 M118 22 Q114 36 116 50"), stroke(0xFF2246B8.toInt(), 1.6f, 0.7f)),
                Piece(ovalPath(100f, 17f, 4f, 3f), fill(0xFFFF4FA3.toInt())),
            ),
            "audifonos" to listOf(
                Piece(svg("M52 98 C 50 40, 150 40, 148 98"), stroke(0xFF2A2240.toInt(), 7f)),
                Piece(svg("M40 84 Q40 78 46 78 L56 78 Q62 78 62 84 L62 112 Q62 118 56 118 L46 118 Q40 118 40 112 Z"), vertical(78f, 118f, 0xFFFF7AB8.toInt(), 0xFFD6247A.toInt())),
                Piece(svg("M138 84 Q138 78 144 78 L154 78 Q160 78 160 84 L160 112 Q160 118 154 118 L144 118 Q138 118 138 112 Z"), vertical(78f, 118f, 0xFFFF7AB8.toInt(), 0xFFD6247A.toInt())),
                Piece(svg("M46 88 L46 108 M154 88 L154 108"), stroke(0xFFFFFFFF.toInt(), 2.2f, 0.5f)),
            ),
            "flores" to flowers(),
            "vaquero" to listOf(
                Piece(ovalPath(100f, 50f, 46f, 9f), vertical(41f, 59f, 0xFFC08552.toInt(), 0xFF8A5530.toInt())),
                Piece(svg("M72 50 Q70 20 88 22 Q100 30 112 22 Q130 20 128 50 Z"), vertical(20f, 50f, 0xFFC99060.toInt(), 0xFF8A5530.toInt())),
                Piece(svg("M72 44 L128 44 L128 50 L72 50 Z"), fill(0xFF5A3418.toInt())),
            ),
            "lazo" to listOf(
                Piece(svg("M100 40 L78 28 Q72 40 78 52 Z M100 40 L122 28 Q128 40 122 52 Z"), vertical(26f, 54f, 0xFFFF8CC6.toInt(), 0xFFE0337F.toInt())),
                Piece(ovalPath(100f, 40f, 6f, 6f), fill(0xFFC81E6A.toInt())),
            ),
            "lana" to listOf(
                Piece(svg("M68 52 Q68 14 100 12 Q132 14 132 52 Z"), vertical(12f, 52f, 0xFF74C0FC.toInt(), 0xFF1C7ED6.toInt())),
                Piece(svg("M66 46 L134 46 L134 56 L66 56 Z"), fill(0xFFE7F5FF.toInt())),
                Piece(svg("M78 22 L78 46 M90 15 L90 46 M100 13 L100 46 M110 15 L110 46 M122 22 L122 46"), stroke(0xFFFFFFFF.toInt(), 1.6f, 0.45f)),
                Piece(ovalPath(100f, 9f, 8f, 8f), fill(0xFFFFFFFF.toInt())),
            ),
            "chef" to listOf(
                Piece(svg("M74 54 L74 34 L126 34 L126 54 Z"), fill(0xFFF8F9FA.toInt())),
                Piece(ovalPath(84f, 26f, 14f, 13f), fill(0xFFFFFFFF.toInt())),
                Piece(ovalPath(100f, 18f, 16f, 15f), fill(0xFFFFFFFF.toInt())),
                Piece(ovalPath(116f, 26f, 14f, 13f), fill(0xFFFFFFFF.toInt())),
                Piece(svg("M74 46 L126 46"), stroke(0xFFDEE2E6.toInt(), 2f)),
            ),
            "cuernos" to listOf(
                Piece(svg("M72 52 Q62 26 74 12 Q78 32 88 48 Z"), vertical(12f, 52f, 0xFFFF6B6B.toInt(), 0xFFC92A2A.toInt())),
                Piece(svg("M128 52 Q138 26 126 12 Q122 32 112 48 Z"), vertical(12f, 52f, 0xFFFF6B6B.toInt(), 0xFFC92A2A.toInt())),
            ),
            "vikingo" to listOf(
                Piece(svg("M64 34 Q50 20 54 2 Q62 22 74 30 Z M136 34 Q150 20 146 2 Q138 22 126 30 Z"), vertical(2f, 34f, 0xFFFFF8E1.toInt(), 0xFFE0C08A.toInt())),
                Piece(svg("M66 54 Q66 16 100 14 Q134 16 134 54 Z"), vertical(14f, 54f, 0xFFCED4DA.toInt(), 0xFF6C757D.toInt())),
                Piece(svg("M64 48 L136 48 L136 56 L64 56 Z"), fill(0xFF8A5530.toInt())),
                Piece(svg("M100 16 L100 48"), stroke(0xFF868E96.toInt(), 3f)),
            ),
            "corona" to PetArt.crown.map { Piece(it.path, it.paint) },
            "mago" to listOf(
                Piece(ovalPath(100f, 50f, 36f, 7f), fill(0xFF2F2A8C.toInt())),
                Piece(svg("M76 50 Q92 30 98 -2 Q112 28 124 50 Z"), vertical(-2f, 50f, 0xFF5B5BE8.toInt(), 0xFF2F2A8C.toInt())),
                Piece(Path(PetArt.star).apply { transform(android.graphics.Matrix().apply { setScale(0.55f, 0.55f); postTranslate(98f, 32f) }) }, fill(gold)),
                Piece(Path(PetArt.star).apply { transform(android.graphics.Matrix().apply { setScale(0.32f, 0.32f); postTranslate(110f, 16f) }) }, fill(0xFFFFE9A8.toInt())),
            ),
        )
    }

    private fun flowers(): List<Piece> {
        val list = ArrayList<Piece>()
        val colors = intArrayOf(0xFFFF7AB8.toInt(), 0xFFFFC93C.toInt(), 0xFFA29BFE.toInt(), 0xFF48DBFB.toInt(), 0xFFFF9F43.toInt())
        val spots = listOf(66f to 60f, 81f to 49f, 100f to 45f, 119f to 49f, 134f to 60f)
        spots.forEachIndexed { i, (x, y) ->
            val petals = Path()
            for (k in 0 until 5) {
                val a = k * 2 * Math.PI / 5
                petals.addCircle(x + (5.2 * Math.cos(a)).toFloat(), y + (5.2 * Math.sin(a)).toFloat(), 4.4f, Path.Direction.CW)
            }
            list += Piece(petals, fill(colors[i]))
            list += Piece(ovalPath(x, y, 3f, 3f), fill(0xFFFFF3B0.toInt()))
        }
        list += Piece(svg("M60 64 Q100 38 140 64"), stroke(0xFF51CF66.toInt(), 2.4f))
        return list
    }

    private val darkLens = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0151025.toInt() }
    private val clearLens = fill(0xFFFFFFFF.toInt(), 0.16f)
    private val frame = stroke(0xFF2A2240.toInt(), 2.8f)
    private val shine = stroke(0xFFFFFFFF.toInt(), 2f, 0.55f)
    private val heartLens = fill(0xFFFF3D7F.toInt(), 0.92f)
    private val bow = fill(0xFFFF4F7B.toInt())
    private val bowDark = fill(0xFFC81E50.toInt())
    private val scarf = vertical(0f, 14f, 0xFFFF6B6B.toInt(), 0xFFC92A2A.toInt())
    private val scarfStripe = stroke(0xFFFFFFFF.toInt(), 2.2f, 0.75f)
    private val ribbon = fill(0xFF3B5BDB.toInt())
    private val medal = vertical(0f, 20f, 0xFFFFE9A8.toInt(), 0xFFE89A00.toInt())
    private val medalStar = fill(0xFFFFFFFF.toInt(), 0.85f)
    private val visorLens = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD022D3EE.toInt() }
    private val starLens = fill(0xFFFFC93C.toInt(), 0.9f)
    private val starFrame = stroke(0xFFE08A00.toInt(), 1.6f)
    private val tie = vertical(-4f, 38f, 0xFF4DABF7.toInt(), 0xFF1864AB.toInt())
    private val tieStripe = stroke(0xFFFFFFFF.toInt(), 2f, 0.6f)
    private val pearl = fill(0xFFFFF8F0.toInt())
    private val chain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
        color = 0xFFFFC93C.toInt()
    }
    private val cape = vertical(100f, 180f, 0xFFFF4D4D.toInt(), 0xFFA61E1E.toInt())
    private val capeEdge = stroke(0xFFFFC93C.toInt(), 2.4f)
    private val angelWing = vertical(80f, 150f, 0xFFFFFFFF.toInt(), 0xFFD7E3FF.toInt())
    private val angelLine = stroke(0xFFB8C7EE.toInt(), 1.4f)
    private val batWing = vertical(80f, 150f, 0xFF6E4A9E.toInt(), 0xFF2A1640.toInt())
    private val bag = vertical(100f, 160f, 0xFFFFA94D.toInt(), 0xFFD9480F.toInt())
    private val bagDark = fill(0xFFB3400B.toInt())
    private val bagStrap = stroke(0xFFD9480F.toInt(), 4.2f)
    private val rocket = vertical(100f, 160f, 0xFFE9ECEF.toInt(), 0xFF868E96.toInt())
    private val flame = vertical(150f, 185f, 0xFFFFE066.toInt(), 0xFFFF6B00.toInt())
    private val path = Path()

    /**
     * Lo que va en la espalda (capa, alas, mochila): se dibuja ANTES que el cuerpo, así asoma por
     * los lados y abajo. Las tiras de la mochila van adelante (ver [draw]).
     */
    fun drawBack(canvas: Canvas, m: PetArt.Model, outfit: Outfit) {
        val key = outfit.back ?: return
        val half = m.headHalf
        val top = m.neckY - 10f
        when (key) {
            "capa" -> {
                path.reset()
                path.moveTo(100f - half * 0.55f, top)
                path.quadTo(100f - half * 1.25f, 150f, 100f - half * 1.15f, 178f)
                path.quadTo(100f, 186f, 100f + half * 1.15f, 178f)
                path.quadTo(100f + half * 1.25f, 150f, 100f + half * 0.55f, top)
                path.close()
                canvas.drawPath(path, cape)
                canvas.drawPath(path, capeEdge)
            }
            "angel", "murcielago" -> for (side in intArrayOf(-1, 1)) {
                canvas.save()
                canvas.scale(side.toFloat(), 1f, 100f, 0f)
                val x0 = 100f - half * 0.55f
                path.reset()
                if (key == "angel") {
                    path.moveTo(x0, top + 6f)
                    path.quadTo(x0 - 40f, top - 34f, x0 - 62f, top - 10f)
                    path.quadTo(x0 - 54f, top + 2f, x0 - 60f, top + 14f)
                    path.quadTo(x0 - 46f, top + 22f, x0 - 50f, top + 34f)
                    path.quadTo(x0 - 24f, top + 40f, x0, top + 30f)
                    path.close()
                    canvas.drawPath(path, angelWing)
                    canvas.drawLine(x0 - 10f, top + 10f, x0 - 46f, top - 6f, angelLine)
                    canvas.drawLine(x0 - 10f, top + 18f, x0 - 44f, top + 16f, angelLine)
                } else {
                    path.moveTo(x0, top + 4f)
                    path.lineTo(x0 - 30f, top - 28f)
                    path.lineTo(x0 - 66f, top - 14f)
                    path.quadTo(x0 - 54f, top - 2f, x0 - 58f, top + 12f)
                    path.quadTo(x0 - 44f, top + 4f, x0 - 42f, top + 22f)
                    path.quadTo(x0 - 28f, top + 12f, x0 - 22f, top + 30f)
                    path.lineTo(x0, top + 26f)
                    path.close()
                    canvas.drawPath(path, batWing)
                }
                canvas.restore()
            }
            "mochila" -> {
                canvas.drawRoundRect(100f + half * 0.35f, top + 4f, 100f + half + 14f, top + 52f, 10f, 10f, bag)
                canvas.drawRoundRect(100f + half * 0.55f, top + 26f, 100f + half + 10f, top + 44f, 6f, 6f, bagDark)
            }
            "cohete" -> for (side in intArrayOf(-1, 1)) {
                val cx = 100f + side * (half + 6f)
                canvas.drawRoundRect(cx - 9f, top - 6f, cx + 9f, top + 44f, 9f, 9f, rocket)
                path.reset()
                path.moveTo(cx - 7f, top + 44f)
                path.quadTo(cx, top + 72f, cx + 7f, top + 44f)
                path.close()
                canvas.drawPath(path, flame)
            }
        }
    }

    /** Dibuja la ropa sobre la mascota (ya transformada al espacio de 200×200). */
    fun draw(canvas: Canvas, m: PetArt.Model, outfit: Outfit, big: Boolean) {
        if (outfit.back == "mochila") {
            // Las tiras de la mochila, por delante.
            val half = m.headHalf
            canvas.drawLine(100f - half * 0.45f, m.neckY - 4f, 100f - half * 0.35f, m.neckY + 34f, bagStrap)
            canvas.drawLine(100f + half * 0.45f, m.neckY - 4f, 100f + half * 0.35f, m.neckY + 34f, bagStrap)
        }
        outfit.neck?.let { drawNeck(canvas, m, it) }
        outfit.eyes?.let { drawGlasses(canvas, m, it, big) }
        val hat = outfit.hat?.let { hats[it] } ?: return
        canvas.save()
        canvas.translate(0f, m.headTop - 46f)
        if (outfit.hat == "audifonos") {
            canvas.translate(0f, m.eyes.y - 92f - (m.headTop - 46f))
            canvas.scale(m.headHalf / 47f, 1f, 100f, 0f)
        }
        hat.forEach { canvas.drawPath(it.path, it.paint) }
        canvas.restore()
    }

    private fun drawGlasses(canvas: Canvas, m: PetArt.Model, key: String, big: Boolean) {
        val e = m.eyes
        val r = if (big) (m.rig?.scleraRy ?: 16f) + 3f else max(e.rx, e.ry) * 1.75f
        val y = e.y
        if (key == "visor") {
            // Una banda de vidrio que cruza los dos ojos, con brillo.
            val half = (e.right - e.left) / 2f + r + 4f
            canvas.drawRoundRect(100f - half, y - r * 0.85f, 100f + half, y + r * 0.85f, r * 0.85f, r * 0.85f, visorLens)
            canvas.drawRoundRect(100f - half, y - r * 0.85f, 100f + half, y + r * 0.85f, r * 0.85f, r * 0.85f, frame)
            path.reset()
            path.moveTo(100f - half + r * 0.6f, y - r * 0.4f)
            path.lineTo(100f - half + r * 1.6f, y - r * 0.4f)
            canvas.drawPath(path, shine)
            return
        }
        when (key) {
            "estrella" -> for (x in floatArrayOf(e.left, e.right)) {
                canvas.save()
                canvas.translate(x, y)
                val k = r / 9f
                canvas.scale(k, k)
                canvas.drawPath(PetArt.star, starLens)
                canvas.drawPath(PetArt.star, starFrame)
                canvas.restore()
            }
            "corazon" -> for (x in floatArrayOf(e.left, e.right)) {
                canvas.save()
                canvas.translate(x, y - r * 0.15f)
                val s = r / 13f
                canvas.scale(s, s)
                canvas.drawPath(PetArt.heart, heartLens)
                canvas.restore()
            }
            else -> for (x in floatArrayOf(e.left, e.right)) {
                canvas.drawCircle(x, y, r, if (key == "sol") darkLens else clearLens)
                canvas.drawCircle(x, y, r, frame)
                if (key == "sol") {
                    path.reset()
                    path.moveTo(x - r * 0.55f, y - r * 0.2f)
                    path.lineTo(x - r * 0.2f, y - r * 0.55f)
                    canvas.drawPath(path, shine)
                }
            }
        }
        // Puente entre los dos lentes y patitas hacia los lados.
        path.reset()
        path.moveTo(e.left + r * 0.95f, y - r * 0.2f)
        path.quadTo(100f, y - r * 0.55f, e.right - r * 0.95f, y - r * 0.2f)
        path.moveTo(e.left - r, y - r * 0.15f)
        path.lineTo(e.left - r - 8f, y - r * 0.35f)
        path.moveTo(e.right + r, y - r * 0.15f)
        path.lineTo(e.right + r + 8f, y - r * 0.35f)
        canvas.drawPath(path, frame)
    }

    private fun drawNeck(canvas: Canvas, m: PetArt.Model, key: String) {
        val y = m.neckY
        canvas.save()
        canvas.translate(100f, y)
        when (key) {
            "corbatin" -> {
                path.reset()
                path.moveTo(0f, 0f)
                path.lineTo(-16f, -9f)
                path.quadTo(-19f, 0f, -16f, 9f)
                path.close()
                path.moveTo(0f, 0f)
                path.lineTo(16f, -9f)
                path.quadTo(19f, 0f, 16f, 9f)
                path.close()
                canvas.drawPath(path, bow)
                canvas.drawCircle(0f, 0f, 4.5f, bowDark)
            }
            "bufanda" -> {
                val half = m.headHalf * 0.78f
                canvas.save()
                canvas.translate(0f, -7f)
                canvas.drawRoundRect(-half, 0f, half, 14f, 7f, 7f, scarf)
                canvas.drawRoundRect(10f, 6f, 24f, 34f, 5f, 5f, scarf)
                canvas.drawLine(-half * 0.5f, 3f, -half * 0.5f, 11f, scarfStripe)
                canvas.drawLine(half * 0.4f, 3f, half * 0.4f, 11f, scarfStripe)
                canvas.drawLine(12f, 27f, 22f, 27f, scarfStripe)
                canvas.restore()
            }
            "corbata" -> {
                path.reset()
                path.moveTo(-7f, -4f)
                path.lineTo(7f, -4f)
                path.lineTo(4f, 4f)
                path.lineTo(-4f, 4f)
                path.close()
                path.moveTo(-4f, 4f)
                path.lineTo(4f, 4f)
                path.lineTo(8f, 30f)
                path.lineTo(0f, 38f)
                path.lineTo(-8f, 30f)
                path.close()
                canvas.drawPath(path, tie)
                canvas.drawLine(-5f, 14f, 6f, 10f, tieStripe)
                canvas.drawLine(-6f, 24f, 7f, 20f, tieStripe)
            }
            "perlas", "cadena" -> {
                val half = m.headHalf * 0.72f
                val beads = if (key == "perlas") 9 else 12
                for (i in 0 until beads) {
                    val k = i / (beads - 1f)
                    val bx = -half + 2 * half * k
                    val by = -4f + 12f * (1f - (2 * k - 1f) * (2 * k - 1f))
                    if (key == "perlas") {
                        canvas.drawCircle(bx, by, 4.2f, pearl)
                        canvas.drawCircle(bx - 1.3f, by - 1.3f, 1.3f, medalStar)
                    } else {
                        canvas.drawOval(bx - 3.6f, by - 2.4f, bx + 3.6f, by + 2.4f, chain)
                    }
                }
                if (key == "cadena") {
                    canvas.drawCircle(0f, 18f, 9f, medal)
                    canvas.save()
                    canvas.translate(0f, 18f)
                    canvas.scale(0.5f, 0.5f)
                    canvas.drawPath(PetArt.star, medalStar)
                    canvas.restore()
                }
            }
            "medalla" -> {
                path.reset()
                path.moveTo(-12f, -6f)
                path.lineTo(-3f, 12f)
                path.lineTo(3f, 12f)
                path.lineTo(12f, -6f)
                path.lineTo(5f, -6f)
                path.lineTo(0f, 5f)
                path.lineTo(-5f, -6f)
                path.close()
                canvas.drawPath(path, ribbon)
                canvas.save()
                canvas.translate(0f, 2f)
                canvas.drawCircle(0f, 18f, 10f, medal)
                canvas.translate(0f, 18f)
                canvas.scale(0.55f, 0.55f)
                canvas.drawPath(PetArt.star, medalStar)
                canvas.restore()
            }
        }
        canvas.restore()
    }
}
