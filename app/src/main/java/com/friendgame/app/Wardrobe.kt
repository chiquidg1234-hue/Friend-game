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
    enum class Slot(val title: String) { HAT("Gorros"), EYES("Lentes"), NECK("Cuello") }

    class Item(val key: String, val slot: Slot, val emoji: String, val name: String, val cost: Int, val level: Int = 1)

    val ITEMS = listOf(
        Item("fiesta", Slot.HAT, "🥳", "Gorro de fiesta", 0),
        Item("gorra", Slot.HAT, "🧢", "Gorra", 20),
        Item("audifonos", Slot.HAT, "🎧", "Audífonos", 30),
        Item("flores", Slot.HAT, "🌸", "Corona de flores", 35, level = 2),
        Item("vaquero", Slot.HAT, "🤠", "Sombrero vaquero", 40, level = 2),
        Item("mago", Slot.HAT, "🧙", "Sombrero de mago", 60, level = 4),
        Item("nerd", Slot.EYES, "🤓", "Lentes redondos", 15),
        Item("sol", Slot.EYES, "🕶️", "Lentes de sol", 25),
        Item("corazon", Slot.EYES, "😍", "Lentes de corazón", 45, level = 3),
        Item("corbatin", Slot.NECK, "🎀", "Corbatín", 0),
        Item("bufanda", Slot.NECK, "🧣", "Bufanda", 25),
        Item("medalla", Slot.NECK, "🏅", "Medalla", 80, level = 5),
    )

    /** Lo que tiene puesto (null: nada en ese lugar). */
    class Outfit(val hat: String?, val eyes: String?, val neck: String?) {
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
        return Outfit(prefs.getString("wear_hat", null), prefs.getString("wear_eyes", null), prefs.getString("wear_neck", null))
    }

    @Synchronized
    fun owns(item: Item): Boolean = item.cost == 0 || (prefs.getStringSet("owned", emptySet()) ?: emptySet()).contains(item.key)

    fun wearing(item: Item): Boolean = when (item.slot) {
        Slot.HAT -> outfit().hat == item.key
        Slot.EYES -> outfit().eyes == item.key
        Slot.NECK -> outfit().neck == item.key
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
    private val path = Path()

    /** Dibuja la ropa sobre la mascota (ya transformada al espacio de 200×200). */
    fun draw(canvas: Canvas, m: PetArt.Model, outfit: Outfit, big: Boolean) {
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
        when (key) {
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
