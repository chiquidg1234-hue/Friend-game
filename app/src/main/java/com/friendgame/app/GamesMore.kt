package com.friendgame.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Carrera con tu autito: tu mascota maneja por una calle de tres carriles. Desliza (o toca a un
 * lado) para cambiar de carril y desliza hacia arriba (o toca dos veces) para saltar. Las estrellas
 * suman, los diamantes suman más y chocar con conos, barriles o autos quita una vida.
 *
 * Juego original de carriles: la calle, el auto y los obstáculos se dibujan aquí, sin imitar a
 * ningún juego existente.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class CarGame(context: Context, species: String) : GameView(context, species) {
    private class Thing(var lane: Int, var y: Float, val emoji: String, val good: Int, val wide: Boolean = false, var taken: Boolean = false, val color: Int = 0)

    private val things = ArrayList<Thing>()
    private var lane = 1
    private var carX = -1f
    private var speed = 0f
    private var distance = 0f
    private var stars = 0
    private var lives = 3
    private var spawn = 0.8f
    private var jump = 0f
    private var hurt = 0f
    private var scroll = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastTap = 0L
    private val road = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val scenery = listOf("🌳", "🏠", "🌲", "🏪", "🌳", "🏡", "🌻", "🏫")
    private val traffic = intArrayOf(0xFF3B82F6.toInt(), 0xFF22C55E.toInt(), 0xFFF59E0B.toInt(), 0xFFA855F7.toInt(), 0xFFEFEFF5.toInt())

    init {
        // La mascota va sentada en el auto: solo se le ve de los hombros para arriba.
        addView(pet, LayoutParams((78 * d).toInt(), (78 * d).toInt()))
        contentDescription = "Carrera con tu autito: desliza a los lados para cambiar de carril y hacia arriba para saltar"
        setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                }
                MotionEvent.ACTION_UP -> {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    val now = System.currentTimeMillis()
                    when {
                        dy < -40 * d && abs(dy) > abs(dx) -> hop()
                        dx > 30 * d -> steer(1)
                        dx < -30 * d -> steer(-1)
                        now - lastTap < 280 -> hop()
                        else -> steer(if (e.x < width / 2f) -1 else 1)
                    }
                    lastTap = now
                }
            }
            true
        }
    }

    private fun laneX(i: Int): Float {
        val left = width * 0.14f
        val w = width * 0.72f
        return left + w * (i + 0.5f) / 3f
    }

    private fun steer(dir: Int) {
        if (!running) return
        lane = (lane + dir).coerceIn(0, 2)
    }

    private fun hop() {
        if (!running || jump > 0f) return
        jump = 0.7f
        pet.cheer()
    }

    override fun begin() {
        super.begin()
        speed = 360 * d
    }

    override fun step(dt: Float) {
        if (carX < 0f) carX = laneX(lane)
        speed = min(900 * d, speed + 14 * d * dt)
        distance += speed * dt / (40 * d)
        scroll = (scroll + speed * dt) % (80 * d)
        carX += (laneX(lane) - carX) * min(1f, dt * 12f)
        jump = max(0f, jump - dt)
        hurt = max(0f, hurt - dt)
        spawn -= dt
        if (spawn <= 0f) {
            spawn = max(0.42f, 1.1f - distance / 1500f)
            val l = Random.nextInt(3)
            val r = Random.nextFloat()
            things += when {
                r < 0.38f -> Thing(l, -60 * d, "⭐", 1)
                r < 0.45f -> Thing(l, -60 * d, "💎", 5)
                r < 0.62f -> Thing(l, -60 * d, "🚧", 0)
                r < 0.78f -> Thing(l, -60 * d, "🛢️", 0)
                else -> Thing(l, -80 * d, "auto", 0, wide = true, color = traffic[Random.nextInt(traffic.size)])
            }
            // A veces una fila de estrellas para seguir.
            if (Random.nextFloat() < 0.18f) for (k in 1..3) things += Thing(l, -60 * d - k * 70 * d, "⭐", 1)
        }
        val carY = height - 150 * d
        val it = things.iterator()
        while (it.hasNext()) {
            val t = it.next()
            t.y += speed * dt * (if (t.wide) 0.75f else 1f)
            if (!t.taken && t.lane == lane && abs(t.y - carY) < 46 * d) {
                if (t.good > 0) {
                    t.taken = true
                    stars += t.good
                    popText(laneX(t.lane), t.y - 20 * d, "+${t.good}", if (t.good > 1) Palette.BULLA else Palette.YELLOW)
                    burst(laneX(t.lane), t.y, if (t.good > 1) Palette.BULLA else Palette.YELLOW)
                    if (t.good > 1) pet.celebrate()
                } else if (jump <= 0.12f && hurt <= 0f) {
                    t.taken = true
                    lives--
                    hurt = 1.2f
                    pet.scare(0.8f)
                    popText(laneX(t.lane), t.y - 20 * d, "💥", Palette.DANGER_TEXT)
                    burst(laneX(t.lane), t.y, Palette.DANGER, 12)
                    if (lives <= 0) end()
                }
            }
            if (t.y > height + 80 * d || (t.taken && t.good > 0)) it.remove()
        }
        score = stars * 10 + distance.toInt()
        val size = pet.width.toFloat().takeIf { it > 0f } ?: (78 * d)
        val lift = sin(jump / 0.7f * Math.PI.toFloat()) * 46 * d
        pet.translationX = carX - size / 2f
        pet.translationY = carY - size * 0.95f - lift
    }

    override fun paint(canvas: Canvas) {
        // Pasto y calle.
        drawSky(canvas, 0xFF3DDC97.toInt(), 0xFF1FA56B.toInt())
        val left = width * 0.12f
        val right = width * 0.88f
        road.color = 0xFF3A3A4E.toInt()
        canvas.drawRect(left, 0f, right, height.toFloat(), road)
        road.color = 0xFFFFFFFF.toInt()
        canvas.drawRect(left, 0f, left + 4 * d, height.toFloat(), road)
        canvas.drawRect(right - 4 * d, 0f, right, height.toFloat(), road)
        // Líneas de los carriles que avanzan.
        road.color = 0xCCFFC93C.toInt()
        val w = right - left
        for (k in 1..2) {
            val x = left + w * k / 3f
            var y = -80 * d + scroll
            while (y < height) {
                canvas.drawRoundRect(x - 2.5f * d, y, x + 2.5f * d, y + 40 * d, 3 * d, 3 * d, road)
                y += 80 * d
            }
        }
        // Lo de los lados (árboles y casas) pasa más lento.
        text.textSize = 30 * d
        val side = (scroll * 1.0f) % (160 * d)
        var i = 0
        var y = -160 * d + side
        while (y < height + 40 * d) {
            canvas.drawText(scenery[(i + (distance / 4).toInt()) % scenery.size], left / 2f, y, text)
            canvas.drawText(scenery[(i + 3 + (distance / 4).toInt()) % scenery.size], right + (width - right) / 2f, y + 60 * d, text)
            y += 160 * d
            i++
        }
        // Obstáculos y premios.
        things.forEach { t ->
            if (t.wide) {
                drawTraffic(canvas, laneX(t.lane), t.y, t.color)
            } else {
                text.textSize = 38 * d
                canvas.drawText(t.emoji, laneX(t.lane), t.y + 14 * d, text)
            }
        }
        drawCar(canvas)
        hud(canvas, "❤️".repeat(max(0, lives)))
        text.textSize = 16 * d
        text.color = Palette.TEXT
        canvas.drawText("⭐ $stars · ${distance.toInt()} m", width / 2f, 146 * d, text)
    }

    /** Otro auto de la calle, visto desde arriba (va en tu misma dirección, más lento). */
    private fun drawTraffic(canvas: Canvas, x: Float, y: Float, color: Int) {
        road.color = 0x44000000
        canvas.drawRoundRect(x - 30 * d, y - 36 * d, x + 34 * d, y + 50 * d, 18 * d, 18 * d, road)
        road.color = 0xFF1E1E2A.toInt()
        for (wx in floatArrayOf(-30f, 30f)) for (wy in floatArrayOf(-22f, 26f)) {
            canvas.drawRoundRect(x + wx * d - 6 * d, y + wy * d - 10 * d, x + wx * d + 6 * d, y + wy * d + 10 * d, 4 * d, 4 * d, road)
        }
        road.color = color
        canvas.drawRoundRect(x - 30 * d, y - 42 * d, x + 30 * d, y + 44 * d, 18 * d, 18 * d, road)
        // Parabrisas, techo y vidrio de atrás.
        road.color = 0xFF2B3550.toInt()
        canvas.drawRoundRect(x - 22 * d, y - 22 * d, x + 22 * d, y - 6 * d, 8 * d, 8 * d, road)
        canvas.drawRoundRect(x - 20 * d, y + 22 * d, x + 20 * d, y + 32 * d, 6 * d, 6 * d, road)
        road.color = 0x33FFFFFF
        canvas.drawRoundRect(x - 20 * d, y - 4 * d, x + 20 * d, y + 20 * d, 8 * d, 8 * d, road)
        // Focos de adelante y luces rojas de atrás.
        road.color = 0xFFFFF3B0.toInt()
        canvas.drawCircle(x - 18 * d, y - 36 * d, 4 * d, road)
        canvas.drawCircle(x + 18 * d, y - 36 * d, 4 * d, road)
        road.color = 0xFFFF4D5E.toInt()
        canvas.drawRoundRect(x - 26 * d, y + 37 * d, x - 14 * d, y + 42 * d, 2 * d, 2 * d, road)
        canvas.drawRoundRect(x + 14 * d, y + 37 * d, x + 26 * d, y + 42 * d, 2 * d, 2 * d, road)
    }

    /** El autito: cuerpo redondeado con ventana, ruedas y focos; la mascota va dentro. */
    private fun drawCar(canvas: Canvas) {
        val carY = height - 150 * d
        val lift = sin(jump / 0.7f * Math.PI.toFloat()) * 46 * d
        val x = carX
        val y = carY - lift
        if (hurt > 0f && ((hurt * 10).toInt() % 2 == 0)) return
        // Sombra en la calle.
        road.color = 0x55000000
        val shrink = 1f - lift / (120 * d)
        canvas.drawOval(x - 40 * d * shrink, carY + 30 * d, x + 40 * d * shrink, carY + 42 * d, road)
        // Ruedas.
        road.color = 0xFF1E1E2A.toInt()
        for (wx in floatArrayOf(-30f, 30f)) for (wy in floatArrayOf(-14f, 26f)) {
            canvas.drawRoundRect(x + wx * d - 7 * d, y + wy * d - 9 * d, x + wx * d + 7 * d, y + wy * d + 9 * d, 4 * d, 4 * d, road)
        }
        // Cuerpo.
        road.color = Palette.ACCENT
        rect.set(x - 34 * d, y - 30 * d, x + 34 * d, y + 38 * d)
        canvas.drawRoundRect(rect, 20 * d, 20 * d, road)
        road.color = Palette.ACCENT_LIP
        canvas.drawRoundRect(x - 34 * d, y + 24 * d, x + 34 * d, y + 38 * d, 12 * d, 12 * d, road)
        // El hueco donde va sentada la mascota.
        road.color = 0xFF2A1858.toInt()
        canvas.drawRoundRect(x - 24 * d, y - 22 * d, x + 24 * d, y + 6 * d, 12 * d, 12 * d, road)
        // Focos y parachoques.
        road.color = 0xFFFFF3B0.toInt()
        canvas.drawCircle(x - 22 * d, y + 16 * d, 5 * d, road)
        canvas.drawCircle(x + 22 * d, y + 16 * d, 5 * d, road)
        road.color = 0xFFFFC93C.toInt()
        canvas.drawRoundRect(x - 12 * d, y + 12 * d, x + 12 * d, y + 20 * d, 4 * d, 4 * d, road)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // La parte de adelante del auto tapa el cuerpo de la mascota (se ve solo la cabeza).
        if (carX < 0f) return
        val carY = height - 150 * d
        val lift = sin(jump / 0.7f * Math.PI.toFloat()) * 46 * d
        val y = carY - lift
        if (hurt > 0f && ((hurt * 10).toInt() % 2 == 0)) return
        road.color = Palette.ACCENT
        canvas.drawRoundRect(carX - 34 * d, y - 2 * d, carX + 34 * d, y + 30 * d, 14 * d, 14 * d, road)
        road.color = 0xFFFFF3B0.toInt()
        canvas.drawCircle(carX - 22 * d, y + 16 * d, 5 * d, road)
        canvas.drawCircle(carX + 22 * d, y + 16 * d, 5 * d, road)
        road.color = Palette.ACCENT_LIP
        canvas.drawRoundRect(carX - 34 * d, y + 24 * d, carX + 34 * d, y + 38 * d, 12 * d, 12 * d, road)
        road.color = 0xFFFFC93C.toInt()
        canvas.drawRoundRect(carX - 12 * d, y + 12 * d, carX + 12 * d, y + 20 * d, 4 * d, 4 * d, road)
    }
}

/** Memoria: 16 cartas boca abajo; da vuelta dos y si son iguales quedan. 60 segundos. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class MemoryGame(context: Context, species: String) : GameView(context, species) {
    private class Card(val emoji: String, var open: Boolean = false, var done: Boolean = false, var flip: Float = 0f)

    private val faces = listOf("🐱", "🐶", "🦊", "🐸", "🐼", "🐙", "🦄", "🐰", "🐻", "🐥", "🐲", "🍩", "🍕", "⭐", "🎈", "🔊")
    private val cards = ArrayList<Card>()
    private var first = -1
    private var second = -1
    private var wait = 0f
    private var left = 60f
    private var pairs = 0
    private var misses = 0
    private val rect = RectF()

    init {
        addView(pet, LayoutParams((96 * d).toInt(), (96 * d).toInt(), android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL))
        contentDescription = "Memoria: toca dos cartas para darlas vuelta"
        setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP && running) tapAt(e.x, e.y)
            true
        }
    }

    override fun begin() {
        super.begin()
        cards.clear()
        faces.shuffled().take(8).flatMap { listOf(it, it) }.shuffled().forEach { cards += Card(it) }
    }

    private fun grid(): FloatArray {
        val size = min(width - 32 * d, height - 300 * d)
        val cell = size / 4f
        val x0 = (width - size) / 2f
        // Centrada entre el puntaje (arriba) y la mascota (abajo).
        val y0 = max(150 * d, 150 * d + (height - 150 * d - 120 * d - size) / 2f)
        return floatArrayOf(x0, y0, cell)
    }

    private fun tapAt(x: Float, y: Float) {
        if (wait > 0f) return
        val (x0, y0, cell) = grid().let { Triple(it[0], it[1], it[2]) }
        val col = ((x - x0) / cell).toInt()
        val row = ((y - y0) / cell).toInt()
        if (col !in 0..3 || row !in 0..3) return
        val i = row * 4 + col
        val c = cards.getOrNull(i) ?: return
        if (c.open || c.done) return
        c.open = true
        if (first < 0) {
            first = i
        } else {
            second = i
            if (cards[first].emoji == c.emoji) {
                cards[first].done = true
                c.done = true
                pairs++
                score += 10
                popText(x, y, "+10")
                burst(x, y, Palette.YELLOW)
                pet.cheer()
                first = -1
                second = -1
                if (pairs == 8) {
                    score += (left * 2).toInt()
                    end()
                }
            } else {
                misses++
                wait = 0.7f
            }
        }
    }

    override fun step(dt: Float) {
        left -= dt
        if (left <= 0f) {
            left = 0f
            end()
            return
        }
        if (wait > 0f) {
            wait -= dt
            if (wait <= 0f) {
                cards.getOrNull(first)?.open = false
                cards.getOrNull(second)?.open = false
                first = -1
                second = -1
            }
        }
        cards.forEach { c ->
            val target = if (c.open || c.done) 1f else 0f
            c.flip += (target - c.flip) * min(1f, dt * 14f)
        }
    }

    override fun paint(canvas: Canvas) {
        drawSky(canvas, 0xFF2B1B5E.toInt(), 0xFF4B2C8F.toInt())
        if (cards.isEmpty()) return
        val (x0, y0, cell) = grid().let { Triple(it[0], it[1], it[2]) }
        cards.forEachIndexed { i, c ->
            val cx = x0 + (i % 4) * cell + cell / 2f
            val cy = y0 + (i / 4) * cell + cell / 2f
            // Al dar vuelta, la carta se angosta y vuelve a crecer.
            val k = abs(c.flip * 2f - 1f).coerceAtLeast(0.05f)
            val half = cell / 2f - 6 * d
            val lip = 5 * d
            val (face, under) = when {
                c.done -> 0xFF3DDC97.toInt() to 0xFF1FA56B.toInt()
                c.flip > 0.5f -> 0xFFFFFFFF.toInt() to 0xFFC9C3E0.toInt()
                else -> Palette.ACCENT to Palette.ACCENT_LIP
            }
            // Labio 3D abajo y la cara de la carta encima, como los botones de la app.
            paint.color = under
            rect.set(cx - half * k, cy - half + lip, cx + half * k, cy + half)
            canvas.drawRoundRect(rect, 16 * d, 16 * d, paint)
            paint.color = face
            rect.set(cx - half * k, cy - half, cx + half * k, cy + half - lip)
            canvas.drawRoundRect(rect, 16 * d, 16 * d, paint)
            canvas.save()
            canvas.scale(k, 1f, cx, cy)
            if (c.flip > 0.5f) {
                text.textSize = cell * 0.46f
                canvas.drawText(c.emoji, cx, cy + cell * 0.14f, text)
            } else {
                // Brillo arriba y el parlante de Bulla al centro.
                paint.color = 0x2EFFFFFF
                rect.set(cx - half + 8 * d, cy - half + 6 * d, cx + half - 8 * d, cy - half + 18 * d)
                canvas.drawRoundRect(rect, 6 * d, 6 * d, paint)
                text.textSize = cell * 0.3f
                text.alpha = 150
                canvas.drawText("🔊", cx, cy + cell * 0.08f, text)
                text.alpha = 255
            }
            canvas.restore()
        }
        hud(canvas, "⏱ ${left.toInt()} s")
    }
}

/** Topo chistoso: en nueve hoyos asoman topos (suman), topos dorados (más) y bombas (quitan). */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class MoleGame(context: Context, species: String) : GameView(context, species) {
    private class Hole(var emoji: String = "", var up: Float = 0f, var life: Float = 0f, var hit: Boolean = false)

    private val holes = List(9) { Hole() }
    private var left = 30f
    private var spawn = 0.5f

    init {
        addView(pet, LayoutParams((96 * d).toInt(), (96 * d).toInt(), android.view.Gravity.BOTTOM or android.view.Gravity.END).apply {
            marginEnd = (12 * d).toInt()
        })
        contentDescription = "Topo chistoso: toca a los topos cuando asoman"
        setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN && running) tapAt(e.x, e.y)
            true
        }
    }

    private fun center(i: Int): Pair<Float, Float> {
        val cell = min(width - 32 * d, height - 320 * d) / 3f
        val x0 = (width - cell * 3) / 2f
        val y0 = 170 * d
        return (x0 + (i % 3) * cell + cell / 2f) to (y0 + (i / 3) * cell + cell / 2f)
    }

    private fun tapAt(x: Float, y: Float) {
        val cell = min(width - 32 * d, height - 320 * d) / 3f
        holes.forEachIndexed { i, h ->
            val (cx, cy) = center(i)
            if (h.up > 0.4f && !h.hit && abs(x - cx) < cell * 0.45f && abs(y - cy) < cell * 0.45f) {
                h.hit = true
                h.life = min(h.life, 0.25f)
                when (h.emoji) {
                    "💣" -> {
                        score = max(0, score - 5)
                        popText(cx, cy - 30 * d, "-5", Palette.DANGER_TEXT)
                        burst(cx, cy, Palette.DANGER, 12)
                        pet.scare(0.8f)
                    }
                    "🌟" -> {
                        score += 5
                        popText(cx, cy - 30 * d, "+5")
                        burst(cx, cy, Palette.YELLOW, 12)
                        pet.celebrate()
                    }
                    else -> {
                        score += 1
                        popText(cx, cy - 30 * d, "+1", Palette.TEXT)
                        burst(cx, cy, 0xFFFFA94D.toInt())
                        if (score % 5 == 0) pet.cheer()
                    }
                }
            }
        }
    }

    override fun step(dt: Float) {
        left -= dt
        if (left <= 0f) {
            left = 0f
            end()
            return
        }
        spawn -= dt
        if (spawn <= 0f) {
            spawn = max(0.35f, 0.8f - (30f - left) * 0.012f)
            val free = holes.filter { it.life <= 0f }
            if (free.isNotEmpty()) {
                val h = free.random()
                val r = Random.nextFloat()
                h.emoji = when {
                    r < 0.18f -> "💣"
                    r < 0.26f -> "🌟"
                    else -> "🐹"
                }
                h.life = max(0.6f, 1.2f - (30f - left) * 0.015f)
                h.hit = false
            }
        }
        holes.forEach { h ->
            if (h.life > 0f) {
                h.life -= dt
                h.up = min(1f, h.up + dt * 8f)
            } else {
                h.up = max(0f, h.up - dt * 8f)
            }
        }
    }

    override fun paint(canvas: Canvas) {
        drawSky(canvas, 0xFF7ED957.toInt(), 0xFF3B9B3B.toInt())
        // Pasto cortado a franjas.
        paint.color = 0x14FFFFFF
        var band = 0f
        while (band < height) {
            canvas.drawRect(0f, band, width.toFloat(), band + 36 * d, paint)
            band += 72 * d
        }
        val cell = min(width - 32 * d, height - 320 * d) / 3f
        holes.forEachIndexed { i, h ->
            val (cx, cy) = center(i)
            val hy = cy + cell * 0.2f
            // Sombra, montón de tierra y el hoyo oscuro.
            paint.color = 0x33000000
            canvas.drawOval(cx - cell * 0.44f, hy - cell * 0.1f, cx + cell * 0.44f, hy + cell * 0.2f, paint)
            paint.color = 0xFFA0673A.toInt()
            canvas.drawOval(cx - cell * 0.44f, hy - cell * 0.15f, cx + cell * 0.44f, hy + cell * 0.16f, paint)
            paint.color = 0xFFC48552.toInt()
            canvas.drawOval(cx - cell * 0.42f, hy - cell * 0.16f, cx + cell * 0.42f, hy + cell * 0.1f, paint)
            paint.color = 0xFF3A2010.toInt()
            canvas.drawOval(cx - cell * 0.33f, hy - cell * 0.1f, cx + cell * 0.33f, hy + cell * 0.08f, paint)
            // Lo que asoma: sube desde dentro del hoyo y se corta en su borde.
            if (h.up > 0.02f) {
                canvas.save()
                canvas.clipRect(cx - cell / 2f, cy - cell / 2f - 20 * d, cx + cell / 2f, hy)
                text.textSize = cell * 0.5f
                val y = hy + cell * 0.05f + (1f - h.up) * cell * 0.5f
                canvas.drawText(if (h.hit && h.emoji == "🐹") "😵" else h.emoji, cx, y, text)
                canvas.restore()
                // El borde de adelante del hoyo tapa la parte de abajo.
                paint.color = 0xFF3A2010.toInt()
                canvas.drawRect(cx - cell * 0.3f, hy - 1 * d, cx + cell * 0.3f, hy + cell * 0.02f, paint)
            }
        }
        hud(canvas, "⏱ ${left.toInt()} s")
    }
}
