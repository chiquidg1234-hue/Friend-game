package com.friendgame.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Minijuegos con la mascota, como los de Pou. Jugar sube su alegría y da unos pocos puntos (con
 * tope diario, ver [Points.onGamePlayed]); nada se compra con dinero. Cada juego guarda tu récord.
 */
object Games {
    enum class Kind(val key: String, val emoji: String, val title: String, val text: String, val color: Int, val ink: Boolean, val unit: String) {
        CATCH("atrapa", "🍩", "Atrapa la comida", "Mueve a tu mascota y atrapa lo rico. ¡Cuidado con las bombas!", 0xFFFF9F43.toInt(), true, ""),
        JUMP("salto", "☁️", "Salto a las nubes", "Salta de nube en nube: toca a la izquierda o a la derecha para moverte.", 0xFF48DBFB.toInt(), true, " m"),
        SIMON("dice", "🎵", "Bulla dice", "Repite la secuencia de sonidos. Cada ronda, uno más.", 0xFFB44CFF.toInt(), false, " rondas"),
        BUBBLES("burbujas", "🔵", "Revienta burbujas", "Toca las burbujas antes de que se escapen. Tienes 30 segundos.", 0xFF3DDC97.toInt(), true, ""),
    }

    private lateinit var prefs: SharedPreferences

    @Synchronized
    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = context.applicationContext.getSharedPreferences("friendgame-looks", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun best(kind: Kind): Int = if (::prefs.isInitialized) prefs.getInt("game_best_${kind.key}", 0) else 0

    /** Guarda el puntaje; devuelve true si es récord. */
    @Synchronized
    fun record(kind: Kind, score: Int): Boolean {
        if (score <= best(kind)) return false
        prefs.edit().putInt("game_best_${kind.key}", score).apply()
        return true
    }
}

/**
 * Pantalla de minijuegos: la lista y, al elegir uno, el juego a pantalla completa con el resultado.
 * Es una capa encima de la app (como «Subir audio»): no la redibuja el resto.
 */
@SuppressLint("ViewConstructor")
class GamesScreen(context: Context, private val species: String, private val onClose: () -> Unit, private val toast: (String) -> Unit) : FrameLayout(context) {
    private var game: GameView? = null

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(Palette.BG)
        showMenu()
    }

    /** Atrás: del juego vuelve a la lista; de la lista, cierra. */
    fun back() {
        if (game != null) showMenu() else close()
    }

    fun close() {
        game?.stop()
        game = null
        (parent as? ViewGroup)?.removeView(this)
        onClose()
    }

    private fun showMenu() {
        game?.stop()
        game = null
        removeAllViews()
        val page = context.column().apply { setPadding(context.dp(20), context.dp(14), context.dp(20), context.dp(24)) }
        val top = context.row()
        top.addView(context.iconButton(R.drawable.ic_back, "Volver", Palette.SURFACE) { close() })
        top.addView(context.title("Minijuegos", 28f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(12) })
        page.addView(top, context.lp())
        val hello = context.row().apply { gravity = Gravity.CENTER_VERTICAL }
        hello.addView(PetView(context).apply {
            this.species = this@GamesScreen.species
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            outfit = Wardrobe.outfit()
            postDelayed({ dance(3f) }, 300)
        }, LinearLayout.LayoutParams(context.dp(84), context.dp(84)))
        hello.addView(context.label("¡Jugar me sube la alegría! Y tú ganas puntos.", 14f, Palette.TEXT, bold = true).apply {
            background = context.rounded(Palette.SURFACE, 18, Palette.LIP)
            setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(8) })
        page.addView(hello, context.lp(topDp = 12))
        Games.Kind.entries.forEach { kind ->
            val ink = if (kind.ink) Palette.INK else Palette.TEXT
            val card = Chunky(context, context.dp(5)).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(19))
                background = context.chunkyBackground(kind.color, 24, lipDp = 5)
                isClickable = true
                contentDescription = "${kind.title}. ${kind.text} Récord: ${Games.best(kind)}${kind.unit}"
                setOnClickListener { start(kind) }
            }
            card.addView(context.label(kind.emoji, 36f).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                background = context.rounded(0x1F150C2C, 20)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(context.dp(66), context.dp(66)))
            val texts = context.column()
            texts.addView(context.title(kind.title, 20f, ink), context.lp())
            texts.addView(context.label(kind.text, 13f, ink, bold = true), context.lp())
            texts.addView(context.label("Récord: ${Games.best(kind)}${kind.unit}", 12f, ink, bold = true).apply { alpha = 0.85f }, context.lp(topDp = 4))
            card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(14) })
            page.addView(card, context.lp(topDp = 14))
        }
        page.addView(context.label("Hasta 30 puntos al día jugando. Sin compras con dinero.", 12f, Palette.SUB).apply { gravity = Gravity.CENTER }, context.lp(topDp = 18))
        addView(ScrollView(context).apply { addView(page) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (Anim.enabled()) Anim.enter((0 until page.childCount).map { page.getChildAt(it) })
    }

    private fun start(kind: Games.Kind) {
        removeAllViews()
        val view = when (kind) {
            Games.Kind.CATCH -> CatchGame(context, species)
            Games.Kind.JUMP -> JumpGame(context, species)
            Games.Kind.SIMON -> SimonGame(context, species)
            Games.Kind.BUBBLES -> BubbleGame(context, species)
        }
        view.onOver = { score -> finished(kind, score) }
        game = view
        addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val bar = context.row().apply { setPadding(context.dp(14), context.dp(12), context.dp(14), 0) }
        bar.addView(context.iconButton(R.drawable.ic_close, "Salir del juego", Palette.SURFACE) { showMenu() })
        bar.addView(context.title(kind.title, 18f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(12) })
        addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        view.begin()
    }

    private fun finished(kind: Games.Kind, score: Int) {
        val best = Games.record(kind, score)
        val earned = if (score > 0) Points.onGamePlayed(score) else 0
        val happy = Pets.current != null && Pets.care(Pets.Care.ALEGRIA) == Pets.CareResult.OK
        val panel = context.column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = context.lipped(Palette.SURFACE, 28)
            setPadding(context.dp(20), context.dp(18), context.dp(20), context.dp(24))
            isClickable = true
        }
        panel.addView(PetView(context).apply {
            this.species = this@GamesScreen.species
            outfit = Wardrobe.outfit()
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            post { if (score > 0) celebrate() else surprise() }
        }, LinearLayout.LayoutParams(context.dp(130), context.dp(130)))
        panel.addView(context.title(if (best && score > 0) "¡Nuevo récord!" else "¡Fin del juego!", 26f), context.lp(topDp = 4).apply { width = ViewGroup.LayoutParams.WRAP_CONTENT })
        panel.addView(context.title("$score${kind.unit}", 44f, kind.color), context.lp().apply { width = ViewGroup.LayoutParams.WRAP_CONTENT })
        val lines = listOfNotNull(
            "Récord: ${Games.best(kind)}${kind.unit}",
            if (earned > 0) "⭐ +$earned puntos" else if (score > 0) "Hoy ya ganaste el máximo de puntos jugando" else null,
            if (happy) "💖 Tu mascota quedó feliz" else null,
        )
        lines.forEach { panel.addView(context.label(it, 14f, Palette.SUB, bold = true).apply { gravity = Gravity.CENTER }, context.lp(topDp = 4)) }
        val actions = context.row()
        actions.addView(context.button("Salir", Palette.SURFACE2, Palette.TEXT) { showMenu() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(context.space(10))
        actions.addView(context.button("Otra vez", kind.color, if (kind.ink) Palette.INK else Palette.TEXT, display = true, sizeSp = 18f) { start(kind) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        panel.addView(actions, context.lp(topDp = 16))
        addView(panel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { setMargins(context.dp(24), 0, context.dp(24), 0) })
        if (Anim.enabled()) {
            panel.scaleX = 0.7f
            panel.scaleY = 0.7f
            panel.alpha = 0f
            panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(360).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
        }
        if (earned > 0) toast("⭐ +$earned puntos por jugar")
    }
}

/**
 * Base de un juego: dibuja a 60 cuadros por segundo mientras está en pantalla, con la mascota
 * como vista hija que se mueve. El puntaje se dibuja (no es un texto que cambia, ver AGENTS.md).
 */
abstract class GameView(context: Context, species: String) : FrameLayout(context) {
    var onOver: (Int) -> Unit = {}
    protected var score = 0
    protected var over = false
    protected var running = false
    private var last = 0L
    protected val d = resources.displayMetrics.density
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Fonts.display(context, 700)
    }
    protected val pet = PetView(context).apply {
        this.species = species
        outfit = Wardrobe.outfit()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        setWillNotDraw(false)
        isClickable = true
    }

    open fun begin() {
        running = true
        last = 0L
        invalidate()
    }

    fun stop() {
        running = false
    }

    protected fun end() {
        if (over) return
        over = true
        running = false
        postDelayed({ onOver(score) }, 500)
    }

    abstract fun step(dt: Float)

    abstract fun paint(canvas: Canvas)

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0f else ((now - last) / 1000f).coerceAtMost(0.05f)
        last = now
        if (running && width > 0) step(dt)
        paint(canvas)
        if (running) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    /** El puntaje grande arriba, y algo a la derecha (vidas, tiempo). */
    protected fun hud(canvas: Canvas, right: String = "") {
        text.color = Palette.TEXT
        text.textSize = 44f * d
        canvas.drawText(score.toString(), width / 2f, 118f * d, text)
        if (right.isNotEmpty()) {
            text.textSize = 20f * d
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(right, width - 18f * d, 118f * d, text)
            text.textAlign = Paint.Align.CENTER
        }
    }
}

/** Atrapa la comida: la mascota abajo sigue tu dedo; lo rico suma, las bombas quitan una vida. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class CatchGame(context: Context, species: String) : GameView(context, species) {
    private class Item(var x: Float, var y: Float, val emoji: String, val points: Int, val speed: Float)

    private val items = ArrayList<Item>()
    private var lives = 3
    private var target = -1f
    private var spawn = 0f
    private var elapsed = 0f
    private var shake = 0f
    private val foods = listOf("🍩", "🍎", "🍕", "🥕", "🍪", "🍓", "🌽")

    init {
        setBackgroundColor(0xFF1E1240.toInt())
        addView(pet, LayoutParams((104 * d).toInt(), (104 * d).toInt()))
        contentDescription = "Atrapa la comida: arrastra el dedo para mover a tu mascota"
        setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) target = e.x
            true
        }
    }

    override fun step(dt: Float) {
        elapsed += dt
        val size = pet.width.toFloat().takeIf { it > 0f } ?: (104 * d)
        if (target < 0f) target = width / 2f
        val x = pet.translationX + size / 2f
        val nx = x + (target - x) * min(1f, dt * 14f)
        pet.translationX = (nx - size / 2f).coerceIn(0f, width - size)
        pet.translationY = height - size - 24 * d
        spawn -= dt
        if (spawn <= 0f) {
            spawn = max(0.35f, 1.05f - elapsed * 0.012f)
            val r = Random.nextFloat()
            val (emoji, points) = when {
                r < 0.2f + min(0.15f, elapsed * 0.004f) -> "💣" to -1
                r < 0.28f -> "⭐" to 3
                else -> foods.random() to 1
            }
            items += Item(24 * d + Random.nextFloat() * (width - 48 * d), 130 * d, emoji, points, (190 + elapsed * 5) * d * (0.85f + Random.nextFloat() * 0.3f))
        }
        val mouthY = pet.translationY + size * 0.45f
        val petX = pet.translationX + size / 2f
        val it = items.iterator()
        while (it.hasNext()) {
            val item = it.next()
            item.y += item.speed * dt
            if (abs(item.y - mouthY) < 26 * d && abs(item.x - petX) < size * 0.42f) {
                it.remove()
                if (item.points < 0) {
                    lives--
                    shake = 0.3f
                    pet.scare(0.8f)
                    if (lives <= 0) end()
                } else {
                    score += item.points
                    if (item.points > 1) pet.celebrate() else if (score % 5 == 0) pet.cheer()
                }
            } else if (item.y > height + 20 * d) {
                it.remove()
            }
        }
        shake = max(0f, shake - dt)
    }

    override fun paint(canvas: Canvas) {
        val off = if (shake > 0f) sin(shake * 80f) * 8f * d else 0f
        canvas.save()
        canvas.translate(off, 0f)
        paint.color = 0xFF2A1858.toInt()
        canvas.drawRect(0f, height - 24 * d, width.toFloat(), height.toFloat(), paint)
        text.textSize = 34f * d
        items.forEach { canvas.drawText(it.emoji, it.x, it.y + 12 * d, text) }
        canvas.restore()
        hud(canvas, "❤️".repeat(max(0, lives)))
    }
}

/** Salto a las nubes: rebota solo en cada nube; tocar a un lado lo mueve. Si cae, termina. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class JumpGame(context: Context, species: String) : GameView(context, species) {
    private class Cloud(var x: Float, var y: Float, val w: Float, var vx: Float)

    private val clouds = ArrayList<Cloud>()
    private var px = 0f
    private var py = 0f
    private var vy = 0f
    private var dir = 0f
    private var camera = 0f
    private var top = 0f
    private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F7FF.toInt() }

    init {
        setBackgroundColor(0xFF1B3A74.toInt())
        addView(pet, LayoutParams((84 * d).toInt(), (84 * d).toInt()))
        contentDescription = "Salto a las nubes: toca a la izquierda o a la derecha para moverte"
        setOnTouchListener { _, e ->
            dir = when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> if (e.x < width / 2f) -1f else 1f
                else -> 0f
            }
            true
        }
    }

    override fun begin() {
        super.begin()
        post {
            clouds.clear()
            px = width / 2f
            py = height - 160 * d
            vy = -JUMP * d
            camera = 0f
            top = py
            var y = height - 90 * d
            clouds += Cloud(width / 2f - 50 * d, y, 100 * d, 0f)
            while (y > -height) {
                y -= 95 * d
                addCloud(y)
            }
        }
    }

    private fun addCloud(y: Float) {
        val w = (70 + Random.nextInt(50)) * d
        val moving = score > 40 && Random.nextFloat() < 0.3f
        clouds += Cloud(Random.nextFloat() * (width - w), y, w, if (moving) (60 + Random.nextInt(60)) * d * (if (Random.nextBoolean()) 1 else -1) else 0f)
    }

    override fun step(dt: Float) {
        if (clouds.isEmpty()) return
        px += dir * 260 * d * dt
        if (px < -20 * d) px = width + 20 * d
        if (px > width + 20 * d) px = -20 * d
        val before = py
        vy += GRAVITY * d * dt
        py += vy * dt
        // Rebota al caer sobre una nube.
        if (vy > 0) {
            clouds.forEach { c ->
                if (before <= c.y && py >= c.y && px > c.x - 10 * d && px < c.x + c.w + 10 * d) {
                    py = c.y
                    vy = -JUMP * d
                    pet.cheer()
                }
            }
        }
        clouds.forEach { c ->
            if (c.vx != 0f) {
                c.x += c.vx * dt
                if (c.x < 0 || c.x + c.w > width) c.vx = -c.vx
            }
        }
        // La cámara sube con la mascota.
        val limit = camera + height * 0.42f
        if (py < limit) camera = py - height * 0.42f
        if (py < top) {
            top = py
            score = ((height - 160 * d - top) / (10 * d)).toInt().coerceAtLeast(0)
        }
        clouds.removeAll { it.y > camera + height + 40 * d }
        var highest = clouds.minOfOrNull { it.y } ?: camera
        while (highest > camera - 100 * d) {
            highest -= (90 + Random.nextInt(30)) * d
            addCloud(highest)
        }
        if (py > camera + height + 60 * d) end()
        val size = pet.width.toFloat().takeIf { it > 0f } ?: (84 * d)
        pet.translationX = px - size / 2f
        pet.translationY = py - camera - size * 0.9f
    }

    override fun paint(canvas: Canvas) {
        clouds.forEach { c ->
            val y = c.y - camera
            canvas.drawRoundRect(c.x, y, c.x + c.w, y + 18 * d, 9 * d, 9 * d, cloudPaint)
            canvas.drawCircle(c.x + c.w * 0.35f, y + 2 * d, 13 * d, cloudPaint)
            canvas.drawCircle(c.x + c.w * 0.62f, y, 16 * d, cloudPaint)
        }
        hud(canvas, "${score} m")
    }

    private companion object {
        const val GRAVITY = 1250f
        const val JUMP = 720f
    }
}

/** Bulla dice: la mascota toca una secuencia de sonidos y hay que repetirla. */
@SuppressLint("ViewConstructor")
class SimonGame(context: Context, species: String) : GameView(context, species) {
    private val pads = ArrayList<View>()
    private val sequence = ArrayList<Int>()
    private var input = 0
    private var showing = false
    private val main = Handler(Looper.getMainLooper())
    private val pool = SoundPool.Builder().setMaxStreams(2)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val sounds = IntArray(4)
    private val vibes = listOf(Vibes.RISA, Vibes.FIESTA, Vibes.AMOR, Vibes.VOZ)
    private val files = listOf("pato", "tada", "campana", "boing")

    init {
        setBackgroundColor(0xFF1E1240.toInt())
        files.forEachIndexed { i, key ->
            sounds[i] = try {
                context.assets.openFd("sounds/$key.ogg").use { pool.load(it, 1) }
            } catch (_: Exception) {
                0
            }
        }
        val column = context.column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        column.addView(pet, LinearLayout.LayoutParams((150 * d).toInt(), (150 * d).toInt()))
        val grid = context.column()
        for (row in 0 until 2) {
            val line = context.row()
            for (col in 0 until 2) {
                val i = row * 2 + col
                val vibe = vibes[i]
                val pad = Chunky(context, (6 * d).toInt()).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = context.chunkyBackground(vibe.color, 28, lipDp = 6)
                    isClickable = true
                    contentDescription = "Botón ${vibe.label}"
                    addView(context.icon(vibe.icon, vibe.onColor, 40), LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
                    setOnClickListener { press(i) }
                }
                pads += pad
                if (col > 0) line.addView(context.space(14))
                line.addView(pad, LinearLayout.LayoutParams(0, (130 * d).toInt(), 1f))
            }
            grid.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (14 * d).toInt() })
        }
        column.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (10 * d).toInt() })
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins((24 * d).toInt(), (120 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())
        })
    }

    override fun begin() {
        super.begin()
        sequence.clear()
        main.postDelayed({ nextRound() }, 700)
    }

    private fun nextRound() {
        if (over || !isAttachedToWindow) return
        sequence += Random.nextInt(4)
        input = 0
        showing = true
        pet.chat(0.5f * sequence.size + 0.5f)
        sequence.forEachIndexed { k, pad ->
            main.postDelayed({ flash(pad) }, 400L + k * 620L)
        }
        main.postDelayed({ showing = false }, 400L + sequence.size * 620L)
    }

    private fun flash(i: Int) {
        if (!isAttachedToWindow) return
        val pad = pads[i]
        pad.isPressed = true
        pad.alpha = 0.6f
        val stream = if (sounds[i] != 0) pool.play(sounds[i], 0.8f, 0.8f, 1, 0, 1f) else 0
        main.postDelayed({
            pad.isPressed = false
            pad.alpha = 1f
            if (stream != 0) pool.stop(stream)
        }, 420)
    }

    private fun press(i: Int) {
        if (showing || over || sequence.isEmpty()) return
        flash(i)
        if (sequence[input] != i) {
            pet.surprise()
            end()
            return
        }
        input++
        if (input == sequence.size) {
            score = sequence.size
            pet.cheer()
            invalidate()
            main.postDelayed({ nextRound() }, 900)
        }
    }

    override fun step(dt: Float) = Unit

    override fun paint(canvas: Canvas) {
        text.color = Palette.SUB
        text.textSize = 15f * d
        canvas.drawText(if (showing) "Escucha y mira…" else if (sequence.isEmpty()) "¡Prepárate!" else "¡Tu turno!", width / 2f, 96f * d, text)
        text.color = Palette.TEXT
        text.textSize = 20f * d
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText("Ronda ${max(1, sequence.size)}", width - 18f * d, 64f * d + 54 * d, text)
        text.textAlign = Paint.Align.CENTER
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacksAndMessages(null)
        pool.release()
        super.onDetachedFromWindow()
    }
}

/** Revienta burbujas: suben desde abajo; tocarlas suma (las doradas, 3). 30 segundos. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class BubbleGame(context: Context, species: String) : GameView(context, species) {
    private class Bubble(var x: Float, var y: Float, val r: Float, val speed: Float, val gold: Boolean, var pop: Float = -1f)

    private val bubbles = ArrayList<Bubble>()
    private var left = 30f
    private var spawn = 0f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val rect = RectF()

    init {
        setBackgroundColor(0xFF0B2A4A.toInt())
        addView(pet, LayoutParams((110 * d).toInt(), (110 * d).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        contentDescription = "Revienta burbujas: toca las burbujas que suben"
        setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN && running) {
                val hit = bubbles.lastOrNull { it.pop < 0f && abs(e.x - it.x) < it.r * 1.15f && abs(e.y - it.y) < it.r * 1.15f }
                if (hit != null) {
                    hit.pop = 0f
                    score += if (hit.gold) 3 else 1
                    if (hit.gold) pet.celebrate() else if (score % 6 == 0) pet.cheer()
                }
            }
            true
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
            spawn = 0.45f
            val r = (22 + Random.nextInt(20)) * d
            bubbles += Bubble(r + Random.nextFloat() * (width - 2 * r), height + r, r, (110 + Random.nextInt(90) + (30 - left) * 4) * d, Random.nextFloat() < 0.12f)
        }
        val it = bubbles.iterator()
        while (it.hasNext()) {
            val b = it.next()
            if (b.pop >= 0f) {
                b.pop += dt
                if (b.pop > 0.25f) it.remove()
                continue
            }
            b.y -= b.speed * dt
            b.x += sin(b.y / (40 * d)) * 20 * d * dt
            if (b.y < -b.r) it.remove()
        }
    }

    override fun paint(canvas: Canvas) {
        bubbles.forEach { b ->
            if (b.pop >= 0f) {
                val k = b.pop / 0.25f
                ring.color = if (b.gold) Palette.YELLOW else 0xFFB9E3FF.toInt()
                ring.alpha = (255 * (1f - k)).toInt()
                canvas.drawCircle(b.x, b.y, b.r * (1f + k), ring)
                ring.alpha = 255
                return@forEach
            }
            fill.color = if (b.gold) 0x66FFC93C else 0x4448DBFB
            canvas.drawCircle(b.x, b.y, b.r, fill)
            ring.color = if (b.gold) Palette.YELLOW else 0xFFB9E3FF.toInt()
            canvas.drawCircle(b.x, b.y, b.r, ring)
            fill.color = 0xAAFFFFFF.toInt()
            rect.set(b.x - b.r * 0.55f, b.y - b.r * 0.6f, b.x - b.r * 0.15f, b.y - b.r * 0.3f)
            canvas.drawOval(rect, fill)
        }
        hud(canvas, "⏱ ${left.toInt()} s")
    }
}
