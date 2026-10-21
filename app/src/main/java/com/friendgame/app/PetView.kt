package com.friendgame.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.text.TextPaint
import android.view.View
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Mascota en 3D, estilo plastilina (ver el lienzo "Friend Game · Mascotas 3D"): degradados con luz
 * arriba a la izquierda, brillo en la cabeza y sombra en el piso. Se dibuja a mano en una vista
 * propia, en un espacio de 200×200, igual que el diseño.
 *
 * Respira, parpadea y mueve la cola; [eat] y [cheer] la hacen comer o saltar; con hambre se pone
 * triste y de noche duerme. Todo se pinta: ningún texto ni tamaño cambia, así TalkBack y las
 * pruebas pueden leer la pantalla mientras se mueve.
 */
class PetView(context: Context) : View(context) {

    var species: String = "gato"
        set(value) {
            if (field != value) model = null
            field = value
            invalidate()
        }

    /** 0 huevo, 1 bebé, 2 joven, 3 adulto, 4 legendario. */
    var stage: Int = 3
        set(value) {
            field = value.coerceIn(0, 4)
            invalidate()
        }

    var mood: Pets.Mood = Pets.Mood.HAPPY
        set(value) {
            field = value
            invalidate()
        }

    /** Avance hacia la próxima etapa, de 0 a 1: el huevo se agrieta cuando le falta poco. */
    var progress: Float = 0f

    /** false: un solo cuadro quieto (listas, el diálogo para adoptar). */
    var animated: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private enum class Action { NONE, EAT, JOY }

    private var action = Action.NONE
    private var actionStart = 0L
    private var model: PetArt.Model? = null
    private val seed = Random.nextFloat() * 10f
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = 0xFFFFFFFF.toInt()
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Come: le cae su comida favorita, mastica y queda feliz. */
    fun eat() = start(Action.EAT)

    /** Feliz: salta y suelta corazones (al acariciarla, al subir de nivel). */
    fun cheer() = start(Action.JOY)

    private fun start(next: Action) {
        action = next
        actionStart = SystemClock.uptimeMillis()
        invalidate()
    }

    private var asleepCheckedAt = 0L
    private var asleepNow = false

    /** De 22:00 a 7:00 duerme (se revisa la hora cada medio minuto). */
    private fun sleeping(now: Long): Boolean {
        if (now - asleepCheckedAt > 30_000L || asleepCheckedAt == 0L) {
            asleepCheckedAt = now
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            asleepNow = hour >= 22 || hour < 7
        }
        return asleepNow
    }

    override fun onDraw(canvas: Canvas) {
        val m = model ?: PetArt.build(species).also { model = it }
        val now = SystemClock.uptimeMillis()
        val live = animated && Anim.enabled()
        val t = if (live) now / 1000f + seed else 1.2f
        var elapsed = (now - actionStart) / 1000f
        if (action == Action.EAT && elapsed > EAT_S) {
            action = Action.JOY
            actionStart = now
            elapsed = 0f
        }
        if (action == Action.JOY && elapsed > JOY_S) action = Action.NONE
        if (!live) action = Action.NONE
        val asleep = live && action == Action.NONE && stage > 0 && sleeping(now)
        val sad = action == Action.NONE && mood != Pets.Mood.HAPPY

        val size = min(width, height).toFloat()
        if (size <= 0f) return
        canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        canvas.scale(size / 200f, size / 200f)

        // Salto (feliz): dos brincos con un poco de aplastamiento al caer.
        val hop = if (action == Action.JOY) abs(sin(elapsed / JOY_S * 2 * PI.toFloat())) else 0f
        val lift = -24f * hop
        val shadowScale = 1f - 0.3f * hop
        canvas.save()
        canvas.scale(shadowScale, shadowScale, 100f, 183f)
        canvas.drawPath(PetArt.shadow.path, PetArt.shadow.paint)
        canvas.restore()

        if (stage == 0) {
            drawEgg(canvas, m, t, live)
            canvas.restore()
            if (live) postInvalidateDelayed(FRAME_IDLE_MS)
            return
        }

        if (stage == 4) {
            val glow = 1f + 0.03f * sin(t * 2.1f)
            canvas.save()
            canvas.scale(glow, glow, 100f, 110f)
            canvas.drawPath(PetArt.aura.path, PetArt.aura.paint)
            canvas.restore()
        }

        canvas.save()
        val grow = STAGE_SCALE[stage]
        canvas.scale(grow, grow, 100f, 182f)
        canvas.translate(0f, lift / grow)
        val period = when {
            asleep -> 4.2f
            sad -> 3.4f
            else -> 2.6f
        }
        val breath = sin(t * 2 * PI.toFloat() / period) * 0.5f + 0.5f
        val squash = if (action == Action.JOY && hop < 0.15f) 0.05f * (1f - hop / 0.15f) else 0f
        canvas.scale(1f + 0.015f * breath + squash, 1f + 0.035f * breath - squash, 100f, 182f)

        // Cola (o tentáculos): se mueve desde su base.
        if (m.tail.isNotEmpty()) {
            val speed = if (sad || asleep) m.tailPeriod * 2f else m.tailPeriod
            val angle = if (live) sin(t * 2 * PI.toFloat() / speed) * m.tailAngle else 0f
            canvas.save()
            canvas.rotate(angle, m.tailX, m.tailY)
            m.tail.forEach { canvas.drawPath(it.path, it.paint) }
            canvas.restore()
        }
        m.back.forEach { canvas.drawPath(it.path, it.paint) }
        m.front.forEach { canvas.drawPath(it.path, it.paint) }

        val eating = action == Action.EAT
        val happy = action == Action.JOY || (eating && elapsed > 2.0f)
        drawEyes(canvas, m, t, live, when {
            asleep -> Face.CLOSED
            happy || eating -> Face.HAPPY
            sad -> Face.SAD
            else -> Face.OPEN
        })
        m.over.forEach { canvas.drawPath(it.path, it.paint) }
        drawMouth(canvas, m, when {
            eating && elapsed in 0.8f..2.0f -> Mouth.CHEW
            happy -> Mouth.OPEN
            asleep -> Mouth.SMALL
            sad -> Mouth.SAD
            else -> Mouth.NORMAL
        }, elapsed)

        if (stage == 1) {
            canvas.drawPath(PetArt.shell.path, PetArt.shell.paint)
            canvas.drawPath(PetArt.shellEdge.path, PetArt.shellEdge.paint)
        }
        if (stage == 4) PetArt.crown.forEach { canvas.drawPath(it.path, it.paint) }

        // Efectos que se mueven con la mascota.
        if (eating) drawFood(canvas, m, elapsed)
        if (sad && mood == Pets.Mood.SAD && live) drawTear(canvas, m, t)
        canvas.restore()

        if (action == Action.JOY) drawHearts(canvas, elapsed)
        if (asleep) drawZs(canvas, t)
        if (stage == 4 && live) drawSparkles(canvas, t)
        canvas.restore()

        if (live) postInvalidateDelayed(if (action == Action.NONE) FRAME_IDLE_MS else FRAME_ACTION_MS)
    }

    private enum class Face { OPEN, HAPPY, SAD, CLOSED }

    private enum class Mouth { NORMAL, OPEN, CHEW, SMALL, SAD }

    private fun drawEgg(canvas: Canvas, m: PetArt.Model, t: Float, live: Boolean) {
        // Cada 2,2 s se tambalea un poco; más fuerte si le falta poco para nacer.
        val phase = (t % 2.2f) / 2.2f
        val strength = if (progress > 0.7f) 9f else 5f
        val wobble = if (live && phase > 0.6f) sin((phase - 0.6f) / 0.4f * 4 * PI.toFloat()) * strength * (1f - phase) * 2.5f else 0f
        canvas.save()
        canvas.rotate(wobble, 100f, 172f)
        canvas.drawPath(PetArt.egg.path, PetArt.egg.paint)
        m.eggSpots.forEach { canvas.drawPath(it.path, it.paint) }
        canvas.drawPath(PetArt.eggShine.path, PetArt.eggShine.paint)
        if (progress > 0.5f) canvas.drawPath(PetArt.crack.path, PetArt.crack.paint)
        canvas.restore()
    }

    private fun drawEyes(canvas: Canvas, m: PetArt.Model, t: Float, live: Boolean, face: Face) {
        val e = m.eyes
        // Parpadeo: cada ~4,4 s se cierran un instante.
        val phase = (t % 4.4f) / 4.4f
        val blink = if (live && face == Face.OPEN && phase > 0.93f) {
            val k = (phase - 0.93f) / 0.07f
            0.08f + 0.92f * abs(1f - 2f * k)
        } else 1f
        for (x in floatArrayOf(e.left, e.right)) {
            val pupilX = x
            if (e.sclera > 0f) {
                // Rana: ojos sobre la cabeza, con blanco.
                canvas.save()
                canvas.scale(1f, if (face == Face.OPEN) blink else 1f, x, e.y - 2f)
                canvas.drawCircle(if (x < 100f) x - 2f else x + 2f, e.y - 2f, e.sclera, m.scleraPaint)
                canvas.restore()
            }
            when (face) {
                Face.OPEN, Face.SAD -> {
                    val ry = if (face == Face.SAD) e.ry * 0.8f else e.ry
                    canvas.save()
                    canvas.scale(1f, blink, pupilX, e.y)
                    paint.shader = null
                    if (e.dark) {
                        paint.color = 0xFF06050A.toInt()
                        canvas.drawOval(pupilX - e.rx, e.y - ry, pupilX + e.rx, e.y + ry, paint)
                    } else {
                        canvas.drawOval(pupilX - e.rx, e.y - ry, pupilX + e.rx, e.y + ry, m.eyePaint(pupilX, e.y, e.rx, ry))
                    }
                    paint.color = 0xFFFFFFFF.toInt()
                    canvas.drawCircle(pupilX - e.rx * 0.33f, e.y - ry * 0.42f, e.rx * 0.4f, paint)
                    canvas.drawCircle(pupilX + e.rx * 0.33f, e.y + ry * 0.42f, e.rx * 0.18f, paint)
                    canvas.restore()
                    if (face == Face.SAD) {
                        // Cejas tristes: el lado de adentro más arriba.
                        line.color = m.ink
                        line.strokeWidth = 2.6f
                        val inner = if (x < 100f) 1f else -1f
                        canvas.drawLine(x - inner * 9f, e.y - e.ry - 1f, x + inner * 7f, e.y - e.ry - 6f, line)
                    }
                }
                Face.HAPPY -> {
                    line.color = 0xFF06050A.toInt()
                    line.strokeWidth = 3.2f
                    path.reset()
                    path.moveTo(pupilX - 7f, e.y + 2f)
                    path.quadTo(pupilX, e.y - 7f, pupilX + 7f, e.y + 2f)
                    canvas.drawPath(path, line)
                }
                Face.CLOSED -> {
                    line.color = 0xFF06050A.toInt()
                    line.strokeWidth = 3.2f
                    path.reset()
                    path.moveTo(pupilX - 7f, e.y - 1f)
                    path.quadTo(pupilX, e.y + 6f, pupilX + 7f, e.y - 1f)
                    canvas.drawPath(path, line)
                }
            }
        }
        if (face == Face.OPEN && blink > 0.5f) m.lashes.forEach { canvas.drawPath(it.path, it.paint) }
    }

    private fun drawMouth(canvas: Canvas, m: PetArt.Model, mouth: Mouth, elapsed: Float) {
        val x = m.mouthX
        val y = m.mouthY
        paint.shader = null
        paint.color = 0xFF5A1A2A.toInt()
        line.color = m.ink
        line.strokeWidth = 2.3f
        when (mouth) {
            Mouth.NORMAL -> m.mouth.forEach { canvas.drawPath(it.path, it.paint) }
            Mouth.OPEN -> {
                path.reset()
                path.moveTo(x - 10f, y - 1f)
                path.quadTo(x, y + 14f, x + 10f, y - 1f)
                path.close()
                canvas.drawPath(path, paint)
            }
            Mouth.CHEW -> {
                val open = 0.2f + 0.8f * abs(sin((elapsed - 0.8f) * 2.6f * PI.toFloat()))
                canvas.drawOval(x - 8f, y + 5f - 6.5f * open, x + 8f, y + 5f + 6.5f * open, paint)
            }
            Mouth.SMALL -> canvas.drawOval(x - 4f, y + 1f, x + 4f, y + 7f, paint)
            Mouth.SAD -> {
                path.reset()
                path.moveTo(x - 8f, y + 6f)
                path.quadTo(x, y - 1f, x + 8f, y + 6f)
                canvas.drawPath(path, line)
            }
        }
    }

    private fun drawFood(canvas: Canvas, m: PetArt.Model, elapsed: Float) {
        // Cae desde arriba hasta la boca, se achica mientras mastica y suelta migas.
        val fall = (elapsed / 0.8f).coerceIn(0f, 1f)
        val shrink = if (elapsed < 0.8f) 1f else (1f - (elapsed - 0.8f) / 1.1f).coerceIn(0f, 1f)
        if (shrink > 0f) {
            val y = -10f + (m.mouthY + 4f + 10f) * (fall * fall)
            text.textSize = 30f * shrink
            text.alpha = 255
            canvas.drawText(m.food, m.mouthX, y + text.textSize * 0.35f, text)
        }
        if (elapsed in 0.9f..2.0f) {
            val k = (elapsed - 0.9f) / 1.1f
            paint.shader = null
            paint.color = m.crumb
            paint.alpha = (255 * (1f - k)).toInt()
            canvas.drawCircle(m.mouthX - 4f - 24f * k, m.mouthY + 8f + 16f * k, 2.6f, paint)
            canvas.drawCircle(m.mouthX + 3f + 22f * k, m.mouthY + 8f + 20f * k, 2.2f, paint)
            canvas.drawCircle(m.mouthX - 8f * k, m.mouthY + 9f + 28f * k, 2f, paint)
            paint.alpha = 255
        }
    }

    private fun drawTear(canvas: Canvas, m: PetArt.Model, t: Float) {
        val k = (t % 2.2f) / 2.2f
        paint.shader = null
        paint.color = 0xFF74C0FC.toInt()
        paint.alpha = (255 * (if (k < 0.2f) k / 0.2f else 1f - (k - 0.2f) / 0.8f)).toInt()
        val x = m.eyes.right + 8f
        val y = m.eyes.y + 8f + 26f * k
        path.reset()
        path.moveTo(x, y)
        path.quadTo(x + 4f, y + 7f, x, y + 10f)
        path.quadTo(x - 4f, y + 7f, x, y)
        canvas.drawPath(path, paint)
        paint.alpha = 255
    }

    private fun drawHearts(canvas: Canvas, elapsed: Float) {
        paint.shader = null
        paint.color = 0xFFFF5FAE.toInt()
        HEARTS.forEach { (x, y, delay) ->
            val k = ((elapsed - delay) / 1.1f)
            if (k <= 0f || k >= 1f) return@forEach
            paint.alpha = (255 * (if (k < 0.2f) k / 0.2f else 1f - (k - 0.2f) / 0.8f)).toInt()
            canvas.save()
            canvas.translate(x, y - 46f * k)
            val s = 0.5f + 0.6f * k
            canvas.scale(s, s)
            canvas.drawPath(PetArt.heart, paint)
            canvas.restore()
        }
        paint.alpha = 255
    }

    private fun drawZs(canvas: Canvas, t: Float) {
        text.color = 0xFFA29BFE.toInt()
        text.typeface = Fonts.display(context, 700)
        for (i in 0 until 3) {
            val k = ((t / 3f) + i / 3f) % 1f
            text.textSize = 14f + 10f * k
            text.alpha = (255 * (if (k < 0.2f) k / 0.2f else 1f - (k - 0.2f) / 0.8f)).toInt()
            canvas.drawText(if (i == 0) "Z" else "z", 132f + 26f * k, 64f - 50f * k, text)
        }
        text.alpha = 255
        text.color = 0xFFFFFFFF.toInt()
    }

    private fun drawSparkles(canvas: Canvas, t: Float) {
        paint.shader = null
        SPARKLES.forEachIndexed { i, (x, y, size) ->
            val k = sin(t * 3.4f + i * 1.7f) * 0.5f + 0.5f
            paint.color = if (i % 2 == 0) 0xFFFFE9A8.toInt() else 0xFFFFFFFF.toInt()
            paint.alpha = (60 + 195 * k).toInt()
            canvas.save()
            canvas.translate(x, y)
            val s = size * (0.6f + 0.5f * k)
            canvas.scale(s, s)
            canvas.drawPath(PetArt.star, paint)
            canvas.restore()
        }
        paint.alpha = 255
    }

    private companion object {
        val STAGE_SCALE = floatArrayOf(1f, 0.7f, 0.85f, 1f, 1f)
        const val EAT_S = 2.6f
        const val JOY_S = 1.4f
        /** Quieta, 20 cuadros por segundo alcanzan para respirar y parpadear; jugando, 30. */
        const val FRAME_IDLE_MS = 50L
        const val FRAME_ACTION_MS = 33L
        val HEARTS = listOf(Triple(40f, 70f, 0f), Triple(160f, 60f, 0.3f), Triple(150f, 120f, 0.55f), Triple(52f, 130f, 0.8f))
        val SPARKLES = listOf(Triple(34f, 72f, 1f), Triple(166f, 58f, 0.8f), Triple(170f, 146f, 0.8f), Triple(28f, 154f, 0.7f))
    }
}

/** Las 8 mascotas, dibujadas con las mismas formas del diseño (viewBox de 200×200). */
object PetArt {
    class Shape(val path: Path, val paint: Paint)

    class Eyes(val left: Float, val right: Float, val y: Float, val rx: Float, val ry: Float, val dark: Boolean = false, val sclera: Float = 0f)

    class Model(
        val tail: List<Shape>,
        val tailX: Float,
        val tailY: Float,
        val tailAngle: Float,
        val tailPeriod: Float,
        val back: List<Shape>,
        val front: List<Shape>,
        val over: List<Shape>,
        val mouth: List<Shape>,
        val lashes: List<Shape>,
        val eyes: Eyes,
        val mouthX: Float,
        val mouthY: Float,
        val ink: Int,
        val food: String,
        val crumb: Int,
        val eggSpots: List<Shape>,
    ) {
        val scleraPaint = fill(0xFFF8F6FC.toInt())
        private val eyeCache = HashMap<Long, Paint>()

        fun eyePaint(x: Float, y: Float, rx: Float, ry: Float): Paint {
            val key = (x.toBits().toLong() shl 32) or (ry.toBits().toLong() and 0xFFFFFFFFL)
            return eyeCache.getOrPut(key) { gradient(RectF(x - rx, y - ry, x + rx, y + ry), EYE_TONE) }
        }
    }

    class Tone(val light: Int, val base: Int, val shade: Int)

    private val EYE_TONE = Tone(0xFF5A4C82.toInt(), 0xFF1F1438.toInt(), 0xFF0B0618.toInt())
    private val GOLD = Tone(0xFFFFF1A8.toInt(), 0xFFFFC93C.toInt(), 0xFFE89A00.toInt())
    private val EGG = Tone(0xFFFFFDF8.toInt(), 0xFFFFEBD4.toInt(), 0xFFD9A774.toInt())

    val shadow = Shape(oval(100f, 183f, 50f, 9f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(100f, 183f, 50f, intArrayOf(0x6B000000, 0x00000000), null, Shader.TileMode.CLAMP)
    }.also { p -> p.shader?.setLocalMatrix(Matrix().apply { setScale(1f, 9f / 50f, 100f, 183f) }) })

    val aura = Shape(circle(100f, 110f, 92f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(100f, 110f, 92f, intArrayOf(0x8CFFC93C.toInt(), 0x2EFF4FA3, 0x00FF4FA3), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    })

    val egg = Shape(oval(100f, 122f, 40f, 50f), gradient(RectF(60f, 72f, 140f, 172f), EGG))
    val eggShine = Shape(oval(86f, 92f, 10f, 16f, -20f), fill(0xFFFFFFFF.toInt(), 0.6f))
    val crack = Shape(svg("M62 118 L72 124 L80 116 L89 125 L97 118 L107 126 L116 119 L126 125 L138 117"), stroke(0xFFB8763A.toInt(), 2.4f))

    /** La cáscara que el bebé todavía lleva en la cabeza. */
    val shell = Shape(svg("M66 49 Q100 7 134 49 L126 41 L117 53 L109 39 L100 53 L91 39 L83 53 L74 41 Z"), gradient(RectF(66f, 7f, 134f, 53f), EGG))
    val shellEdge = Shape(Path(shell.path), stroke(0xFFE8C9A6.toInt(), 1.5f))

    val crown = listOf(
        Shape(svg("M80 54 L83 30 L92 43 L100 25 L108 43 L117 30 L120 54 Z"), gradient(RectF(80f, 25f, 120f, 54f), GOLD)),
        Shape(svg("M80 54 L83 30 L92 43 L100 25 L108 43 L117 30 L120 54 Z"), stroke(0xFFE89A00.toInt(), 2f)),
        Shape(circle(100f, 46f, 3.4f), fill(0xFFFF4FA3.toInt())),
        Shape(circle(88f, 48f, 2.4f), fill(0xFF48DBFB.toInt())),
        Shape(circle(112f, 48f, 2.4f), fill(0xFF3DDC97.toInt())),
    )

    val heart: Path = svg("M0 -4 C -6 -12, -18 -6, -12 4 L0 16 L12 4 C 18 -6, 6 -12, 0 -4 Z")
    val star: Path = svg("M0 -11 L3 -3 L11 0 L3 3 L0 11 L-3 3 L-11 0 L-3 -3 Z")

    fun build(species: String): Model = when (species) {
        "perro" -> perro()
        "zorro" -> zorro()
        "rana" -> rana()
        "panda" -> panda()
        "pulpo" -> pulpo()
        "dragon" -> dragon()
        "unicornio" -> unicornio()
        else -> gato()
    }

    // ---------------------------------------------------------------- Especies

    private fun gato(): Model {
        val body = Tone(0xFFFFE0B8.toInt(), 0xFFFFA94D.toInt(), 0xFFD9480F.toInt())
        val belly = Tone(0xFFFFFFFF.toInt(), 0xFFFFF4E8.toInt(), 0xFFFFE7CC.toInt())
        val b = Builder()
        b.stroke(svg("M134 160 C 170 158, 176 116, 152 102"), body, 13f)
        val tail = b.take()
        catEars(b, body, 0xFFF08C3A.toInt(), 0xFFFFB3C6.toInt())
        val back = b.take()
        standardBody(b, body, belly)
        b.fill(circle(100f, 88f, 45f), body)
        b.stroke(svg("M90 48 q10 7 20 0 M93 57 q7 5 14 0"), 0xFFE8590C.toInt(), 4f)
        b.fill(oval(100f, 106f, 19f, 13f), belly)
        b.fill(oval(82f, 62f, 13f, 7f, -24f), 0xFFFFFFFF.toInt(), 0.35f)
        val front = b.take()
        cheeks(b, 68f, 132f, 104f)
        b.fill(svg("M95.5 100 h9 l-4.5 5 z"), 0xFFFF6B8B.toInt())
        b.stroke(svg("M76 104 L58 100 M76 109 L58 111 M124 104 L142 100 M124 109 L142 111"), 0xFF5C2B1A.toInt(), 1.6f, 0.45f)
        val over = b.take()
        val ink = 0xFF5C2B1A.toInt()
        wMouth(b, 100f, 107f, ink)
        return Model(
            tail, 134f, 160f, 6f, 2.4f, back, front, over, b.take(), emptyList(),
            Eyes(82f, 118f, 90f, 7.5f, 9.5f), 100f, 107f, ink, "🐟", 0xFF74C0FC.toInt(), spots(body.base),
        )
    }

    private fun perro(): Model {
        val body = Tone(0xFFFBE7CF.toInt(), 0xFFE3B27A.toInt(), 0xFFA86B32.toInt())
        val belly = Tone(0xFFFFFFFF.toInt(), 0xFFFFF7EC.toInt(), 0xFFFFEBD2.toInt())
        val ear = Tone(0xFFB98350.toInt(), 0xFF8A5A2E.toInt(), 0xFF57351A.toInt())
        val b = Builder()
        b.stroke(svg("M134 156 q22 -6 24 -28"), body, 11f)
        val tail = b.take()
        standardBody(b, body, belly)
        b.fill(circle(100f, 88f, 45f), body)
        b.fill(oval(121f, 78f, 15f, 13f), 0xFFB97A3E.toInt(), 0.7f)
        b.fill(oval(100f, 106f, 21f, 15f), belly)
        b.fill(oval(57f, 94f, 13f, 27f, 16f), ear)
        b.fill(oval(143f, 94f, 13f, 27f, -16f), ear)
        b.fill(oval(84f, 60f, 13f, 7f, -24f), 0xFFFFFFFF.toInt(), 0.35f)
        val front = b.take()
        cheeks(b, 70f, 130f, 106f, 0.4f)
        b.fill(oval(100f, 100f, 7.5f, 5.5f), 0xFF2B1D16.toInt())
        b.fill(oval(97.5f, 98.2f, 2.6f, 1.5f), 0xFFFFFFFF.toInt(), 0.6f)
        val over = b.take()
        val ink = 0xFF2B1D16.toInt()
        b.stroke(svg("M100 105 v4 M91 108 q9 8 18 0"), ink, 2.2f)
        b.fill(svg("M96 113 q4 10 8 0 z"), 0xFFFF7FA5.toInt())
        return Model(
            tail, 134f, 156f, 14f, 0.8f, emptyList(), front, over, b.take(), emptyList(),
            Eyes(83f, 117f, 88f, 7.5f, 9.5f), 100f, 108f, ink, "🍖", 0xFFB97A3E.toInt(), spots(body.base),
        )
    }

    private fun zorro(): Model {
        val body = Tone(0xFFFFC59A.toInt(), 0xFFFF7F3F.toInt(), 0xFFC8410A.toInt())
        val belly = Tone(0xFFFFFFFF.toInt(), 0xFFFFF6EE.toInt(), 0xFFFFE9DA.toInt())
        val dark = Tone(0xFF6B4A40.toInt(), 0xFF4A3029.toInt(), 0xFF2A1A16.toInt())
        val b = Builder()
        b.fill(svg("M126 164 C 170 172, 186 130, 162 100 C 162 126, 150 142, 126 148 Z"), body)
        b.fill(oval(164f, 110f, 9f, 13f, 28f), 0xFFFFF8F0.toInt())
        val tail = b.take()
        for (side in listOf(1f, -1f)) {
            val ear = mirror(svg("M58 72 L50 22 L96 50 Z"), side)
            b.fill(ear, body)
            b.stroke(Path(ear), 0xFFE8662A.toInt(), 6f)
            b.fill(mirror(svg("M63 62 L58 36 L84 52 Z"), side), 0xFFFFF3EA.toInt())
            val tip = mirror(svg("M50 22 L52.5 37 L63 30 Z"), side)
            b.fill(tip, 0xFF3A2622.toInt())
            b.stroke(Path(tip), 0xFF3A2622.toInt(), 5f)
        }
        val back = b.take()
        standardBody(b, body, belly, dark)
        b.fill(circle(100f, 88f, 45f), body)
        b.fill(oval(80f, 108f, 21f, 14f), belly)
        b.fill(oval(120f, 108f, 21f, 14f), belly)
        b.fill(oval(83f, 60f, 13f, 7f, -24f), 0xFFFFFFFF.toInt(), 0.35f)
        val front = b.take()
        cheeks(b, 66f, 134f, 102f)
        b.fill(oval(100f, 103f, 6f, 4.5f), 0xFF2A1A16.toInt())
        val over = b.take()
        val ink = 0xFF3A2622.toInt()
        wMouth(b, 100f, 110f, ink)
        return Model(
            tail, 126f, 156f, 7f, 2f, back, front, over, b.take(), emptyList(),
            Eyes(82f, 118f, 88f, 7f, 9f), 100f, 110f, ink, "🍒", 0xFFE03131.toInt(), spots(body.base),
        )
    }

    private fun rana(): Model {
        val body = Tone(0xFFC3F5CB.toInt(), 0xFF5FD17A.toInt(), 0xFF23843A.toInt())
        val belly = Tone(0xFFFFFFFF.toInt(), 0xFFF1FCF2.toInt(), 0xFFDDF7E0.toInt())
        val b = Builder()
        b.fill(oval(100f, 146f, 44f, 32f), body)
        b.fill(oval(62f, 146f, 9f, 14f, 26f), body)
        b.fill(oval(138f, 146f, 9f, 14f, -26f), body)
        b.fill(oval(100f, 152f, 27f, 20f), belly)
        b.fill(oval(76f, 174f, 17f, 7f), body)
        b.fill(oval(124f, 174f, 17f, 7f), body)
        for ((x, y) in listOf(62f to 177f, 72f to 180f, 138f to 177f, 128f to 180f)) b.fill(circle(x, y, 4f), 0xFF4CC266.toInt())
        b.fill(oval(100f, 98f, 52f, 38f), body)
        b.fill(circle(72f, 64f, 19f), body)
        b.fill(circle(128f, 64f, 19f), body)
        b.fill(oval(86f, 76f, 16f, 7f, -18f), 0xFFFFFFFF.toInt(), 0.3f)
        val front = b.take()
        cheeks(b, 66f, 134f, 106f, 0.45f, 8f, 5f)
        b.fill(circle(94f, 96f, 1.8f), 0xFF1E5E2C.toInt())
        b.fill(circle(106f, 96f, 1.8f), 0xFF1E5E2C.toInt())
        val over = b.take()
        val ink = 0xFF1E5E2C.toInt()
        b.stroke(svg("M72 106 Q100 128 128 106"), ink, 3f)
        return Model(
            emptyList(), 0f, 0f, 0f, 1f, emptyList(), front, over, b.take(), emptyList(),
            Eyes(74f, 126f, 66f, 6.5f, 8f, sclera = 13f), 100f, 110f, ink, "🐛", 0xFF94D82D.toInt(), spots(body.base),
        )
    }

    private fun panda(): Model {
        val body = Tone(0xFFFFFFFF.toInt(), 0xFFF1F1F6.toInt(), 0xFFABAFC0.toInt())
        val dark = Tone(0xFF5D5D72.toInt(), 0xFF2D2D3A.toInt(), 0xFF121218.toInt())
        val b = Builder()
        b.fill(circle(61f, 54f, 16f), dark)
        b.fill(circle(139f, 54f, 16f), dark)
        val back = b.take()
        b.fill(oval(100f, 142f, 40f, 34f), body)
        b.fill(oval(66f, 142f, 10f, 15f, 20f), dark)
        b.fill(oval(134f, 142f, 10f, 15f, -20f), dark)
        b.fill(oval(81f, 173f, 14f, 8f), dark)
        b.fill(oval(119f, 173f, 14f, 8f), dark)
        b.fill(circle(100f, 88f, 45f), body)
        b.fill(oval(80f, 91f, 13f, 16.5f, 26f), dark)
        b.fill(oval(120f, 91f, 13f, 16.5f, -26f), dark)
        b.fill(oval(84f, 60f, 13f, 7f, -24f), 0xFFFFFFFF.toInt(), 0.55f)
        val front = b.take()
        cheeks(b, 66f, 134f, 110f)
        b.fill(oval(100f, 104f, 6.5f, 4.5f), 0xFF2D2D3A.toInt())
        val over = b.take()
        val ink = 0xFF2D2D3A.toInt()
        wMouth(b, 100f, 111f, ink)
        return Model(
            emptyList(), 0f, 0f, 0f, 1f, back, front, over, b.take(), emptyList(),
            Eyes(81f, 119f, 91f, 5.5f, 7f, dark = true), 100f, 111f, ink, "🎋", 0xFF51CF66.toInt(), spots(0xFF2D2D3A.toInt()),
        )
    }

    private fun pulpo(): Model {
        val body = Tone(0xFFFFD3E3.toInt(), 0xFFF7709F.toInt(), 0xFFB8235A.toInt())
        val b = Builder()
        b.stroke(
            svg("M66 132 C 60 158, 44 166, 52 178 M80 140 C 78 164, 64 176, 72 184 M94 144 C 94 166, 86 180, 92 188 M106 144 C 106 166, 114 180, 108 188 M120 140 C 122 164, 136 176, 128 184 M134 132 C 140 158, 156 166, 148 178"),
            body, 14f,
        )
        for ((x, y) in listOf(72f to 176f, 92f to 182f, 108f to 182f, 128f to 176f)) b.fill(circle(x, y, 2.2f), 0xFFFFD3E3.toInt())
        val tail = b.take()
        b.fill(oval(100f, 96f, 52f, 54f), body)
        for ((x, y, r) in listOf(Triple(74f, 64f, 6f), Triple(126f, 58f, 4f), Triple(134f, 80f, 5f), Triple(66f, 88f, 3.5f))) {
            b.fill(circle(x, y, r), 0xFFFFB3CC.toInt(), 0.85f)
        }
        b.fill(oval(84f, 62f, 15f, 8f, -24f), 0xFFFFFFFF.toInt(), 0.35f)
        val front = b.take()
        cheeks(b, 66f, 134f, 116f, 0.4f, color = 0xFFFF4F86.toInt())
        val over = b.take()
        val ink = 0xFF7A1037.toInt()
        b.fill(oval(100f, 121f, 5f, 4f), ink)
        return Model(
            tail, 100f, 136f, 3f, 1.6f, emptyList(), front, over, b.take(), emptyList(),
            Eyes(82f, 118f, 102f, 8f, 10f), 100f, 118f, ink, "🦐", 0xFFFF8787.toInt(), spots(body.base),
        )
    }

    private fun dragon(): Model {
        val body = Tone(0xFFDCD0FF.toInt(), 0xFF9775FA.toInt(), 0xFF5F3DC4.toInt())
        val belly = Tone(0xFFFFF9E0.toInt(), 0xFFFFEBA8.toInt(), 0xFFFFDB7A.toInt())
        val wing = Tone(0xFFB197FC.toInt(), 0xFF8461F0.toInt(), 0xFF5F3DC4.toInt())
        val b = Builder()
        b.stroke(svg("M132 164 C 160 172, 178 160, 172 140"), body, 12f)
        b.fill(svg("M172 128 L181 142 L172 146 L163 142 Z"), 0xFF7950F2.toInt())
        val tail = b.take()
        b.fill(svg("M66 122 C 30 96, 22 130, 34 142 C 42 134, 50 142, 60 134 Z"), wing)
        b.fill(svg("M134 122 C 170 96, 178 130, 166 142 C 158 134, 150 142, 140 134 Z"), wing)
        val back = b.take()
        standardBody(b, body, belly)
        b.stroke(svg("M84 142 h32 M86 151 h28 M90 160 h20"), 0xFFE0B84C.toInt(), 2f, 0.7f)
        b.fill(svg("M72 58 C 64 42, 68 30, 77 25 C 75 38, 80 46, 87 52 Z"), GOLD)
        b.fill(svg("M128 58 C 136 42, 132 30, 123 25 C 125 38, 120 46, 113 52 Z"), GOLD)
        b.fill(circle(100f, 88f, 45f), body)
        val spike = svg("M93 46 L100 32 L107 46 Z")
        b.fill(spike, 0xFF7950F2.toInt())
        b.stroke(Path(spike), 0xFF7950F2.toInt(), 3f)
        b.fill(oval(84f, 62f, 13f, 7f, -24f), 0xFFFFFFFF.toInt(), 0.35f)
        val front = b.take()
        cheeks(b, 68f, 132f, 106f)
        b.fill(circle(95f, 102f, 1.8f), 0xFF3B2580.toInt())
        b.fill(circle(105f, 102f, 1.8f), 0xFF3B2580.toInt())
        val over = b.take()
        val ink = 0xFF3B2580.toInt()
        b.stroke(svg("M89 108 q11 9 22 0"), ink, 2.4f)
        b.fill(svg("M103 111 L105 117 L107 110.5 Z"), 0xFFFFFFFF.toInt())
        return Model(
            tail, 132f, 164f, 5f, 2f, back, front, over, b.take(), emptyList(),
            Eyes(82f, 118f, 90f, 7.5f, 9.5f), 100f, 108f, ink, "🌶️", 0xFFFF6B6B.toInt(), spots(body.base),
        )
    }

    private fun unicornio(): Model {
        val body = Tone(0xFFFFFFFF.toInt(), 0xFFFAF2FF.toInt(), 0xFFCDB2E8.toInt())
        val b = Builder()
        b.stroke(svg("M132 160 C 166 162, 176 130, 158 110"), 0xFFFF8FAB.toInt(), 9f)
        b.stroke(svg("M134 152 C 160 152, 168 128, 152 114"), 0xFFB197FC.toInt(), 8f)
        b.stroke(svg("M132 146 C 152 144, 158 128, 148 118"), 0xFF74C0FC.toInt(), 7f)
        val tail = b.take()
        catEars(b, body, 0xFFEBDDF7.toInt(), 0xFFFFB3C6.toInt(), small = true)
        val back = b.take()
        standardBody(b, body, null)
        b.fill(oval(81f, 178f, 12f, 3.5f), 0xFFC9A7EB.toInt())
        b.fill(oval(119f, 178f, 12f, 3.5f), 0xFFC9A7EB.toInt())
        b.fill(circle(100f, 88f, 45f), body)
        b.fill(circle(72f, 54f, 14f), 0xFFFF8FAB.toInt())
        b.fill(circle(61f, 70f, 12f), 0xFFB197FC.toInt())
        b.fill(circle(57f, 88f, 11f), 0xFF74C0FC.toInt())
        b.fill(circle(60f, 105f, 9f), 0xFF8CE99A.toInt())
        b.fill(circle(88f, 48f, 11f), 0xFFB197FC.toInt())
        val horn = svg("M92 50 L100 12 L108 50 Z")
        b.fill(horn, GOLD)
        b.stroke(Path(horn), 0xFFF5B70A.toInt(), 2f)
        b.stroke(svg("M95 41 L105 37 M96.5 32 L104 29 M98 23 L102.5 21"), 0xFFE89A00.toInt(), 2f)
        b.fill(oval(88f, 66f, 12f, 6f, -24f), 0xFFFFFFFF.toInt(), 0.6f)
        val front = b.take()
        cheeks(b, 72f, 132f, 106f, 0.5f)
        b.fill(circle(96f, 104f, 1.6f), 0xFF9A7BB8.toInt())
        b.fill(circle(106f, 104f, 1.6f), 0xFF9A7BB8.toInt())
        val over = b.take()
        b.stroke(svg("M90 82 l4 -4 M124 82 l4 -4"), 0xFF1F1438.toInt(), 2f)
        val lashes = b.take()
        val ink = 0xFF7A4E9E.toInt()
        b.stroke(svg("M93 110 q8 6 16 0"), ink, 2.2f)
        return Model(
            tail, 132f, 152f, 6f, 2.4f, back, front, over, b.take(), lashes,
            Eyes(84f, 118f, 90f, 7.5f, 9.5f), 100f, 110f, ink, "🍓", 0xFFFF6B8B.toInt(), spots(0xFFB197FC.toInt()),
        )
    }

    // ---------------------------------------------------------------- Piezas comunes

    private fun standardBody(b: Builder, body: Tone, belly: Tone?, limbs: Tone = body) {
        b.fill(oval(100f, 142f, 40f, 34f), body)
        b.fill(oval(66f, 142f, 9f, 14f, 20f), limbs)
        b.fill(oval(134f, 142f, 9f, 14f, -20f), limbs)
        if (belly != null) b.fill(oval(100f, 150f, 24f, 20f), belly)
        b.fill(oval(81f, 173f, 14f, 8f), limbs)
        b.fill(oval(119f, 173f, 14f, 8f), limbs)
    }

    private fun catEars(b: Builder, body: Tone, edge: Int, inner: Int, small: Boolean = false) {
        for (side in listOf(1f, -1f)) {
            val ear = mirror(svg(if (small) "M64 66 L62 36 L88 52 Z" else "M60 74 L54 28 L94 50 Z"), side)
            b.fill(ear, body)
            b.stroke(Path(ear), edge, 6f)
            b.fill(mirror(svg(if (small) "M68 60 L67 44 L82 53 Z" else "M65 64 L62 40 L84 52 Z"), side), inner)
        }
    }

    private fun cheeks(b: Builder, left: Float, right: Float, y: Float, alpha: Float = 0.45f, rx: Float = 7.5f, ry: Float = 4.5f, color: Int = 0xFFFF7FA5.toInt()) {
        b.fill(oval(left, y, rx, ry), color, alpha)
        b.fill(oval(right, y, rx, ry), color, alpha)
    }

    private fun wMouth(b: Builder, x: Float, y: Float, ink: Int) {
        val p = Path()
        p.moveTo(x - 9f, y)
        p.quadTo(x - 4.5f, y + 4.5f, x, y)
        p.quadTo(x + 4.5f, y + 4.5f, x + 9f, y)
        b.stroke(p, ink, 2.2f)
    }

    /** Manchas del huevo, del color de la especie. */
    private fun spots(color: Int): List<Shape> = listOf(
        Shape(circle(82f, 104f, 8f), fill(color, 0.85f)),
        Shape(circle(118f, 94f, 6f), fill(color, 0.85f)),
        Shape(circle(112f, 140f, 9f), fill(color, 0.85f)),
        Shape(circle(80f, 148f, 5f), fill(color, 0.85f)),
    )

    private class Builder {
        private val list = ArrayList<Shape>()

        fun fill(path: Path, tone: Tone) {
            val bounds = RectF()
            path.computeBounds(bounds, true)
            list += Shape(path, gradient(bounds, tone))
        }

        fun fill(path: Path, color: Int, alpha: Float = 1f) {
            list += Shape(path, PetArt.fill(color, alpha))
        }

        fun stroke(path: Path, tone: Tone, width: Float) {
            val bounds = RectF()
            path.computeBounds(bounds, true)
            list += Shape(path, gradient(bounds, tone).apply {
                style = Paint.Style.STROKE
                strokeWidth = width
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            })
        }

        fun stroke(path: Path, color: Int, width: Float, alpha: Float = 1f) {
            list += Shape(path, PetArt.stroke(color, width, alpha))
        }

        fun take(): List<Shape> = list.toList().also { list.clear() }
    }

    /** Degradado como el del diseño: luz al 35 %/28 % de la forma, sombra en el borde opuesto. */
    private fun gradient(bounds: RectF, tone: Tone): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val radius = max(bounds.width(), bounds.height()) * 0.78f
        shader = RadialGradient(
            bounds.left + bounds.width() * 0.35f, bounds.top + bounds.height() * 0.28f, max(radius, 1f),
            intArrayOf(tone.light, tone.base, tone.shade), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
    }

    private fun fill(color: Int, alpha: Float = 1f): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.alpha = (255 * alpha).toInt()
    }

    private fun stroke(color: Int, width: Float, alpha: Float = 1f): Paint = fill(color, alpha).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun oval(cx: Float, cy: Float, rx: Float, ry: Float, degrees: Float = 0f): Path {
        val p = Path()
        p.addOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), Path.Direction.CW)
        if (degrees != 0f) p.transform(Matrix().apply { setRotate(degrees, cx, cy) })
        return p
    }

    private fun circle(cx: Float, cy: Float, r: Float): Path = oval(cx, cy, r, r)

    /** [side] -1: la misma forma reflejada al otro lado (x → 200 - x). */
    private fun mirror(path: Path, side: Float): Path {
        if (side > 0f) return path
        path.transform(Matrix().apply { setScale(-1f, 1f, 100f, 0f) })
        return path
    }

    /** Lee el atributo "d" de un SVG (M, L, H, V, C, Q, Z, también en minúscula). */
    fun svg(d: String): Path {
        val path = Path()
        val tokens = Regex("[MmLlHhVvCcQqZz]|-?\\d*\\.?\\d+").findAll(d).map { it.value }.toList()
        var i = 0
        var cmd = 'M'
        var x = 0f
        var y = 0f
        var startX = 0f
        var startY = 0f
        fun num(): Float = tokens[i++].toFloat()
        while (i < tokens.size) {
            val token = tokens[i]
            if (token[0].isLetter()) {
                cmd = token[0]
                i++
                if (cmd == 'Z' || cmd == 'z') {
                    path.close()
                    x = startX
                    y = startY
                }
                continue
            }
            when (cmd) {
                'M', 'm' -> {
                    val nx = num()
                    val ny = num()
                    if (cmd == 'm') {
                        x += nx
                        y += ny
                    } else {
                        x = nx
                        y = ny
                    }
                    path.moveTo(x, y)
                    startX = x
                    startY = y
                    cmd = if (cmd == 'm') 'l' else 'L'
                }
                'L' -> {
                    x = num()
                    y = num()
                    path.lineTo(x, y)
                }
                'l' -> {
                    x += num()
                    y += num()
                    path.lineTo(x, y)
                }
                'H' -> {
                    x = num()
                    path.lineTo(x, y)
                }
                'h' -> {
                    x += num()
                    path.lineTo(x, y)
                }
                'V' -> {
                    y = num()
                    path.lineTo(x, y)
                }
                'v' -> {
                    y += num()
                    path.lineTo(x, y)
                }
                'C', 'c' -> {
                    val ox = if (cmd == 'c') x else 0f
                    val oy = if (cmd == 'c') y else 0f
                    val x1 = ox + num()
                    val y1 = oy + num()
                    val x2 = ox + num()
                    val y2 = oy + num()
                    x = ox + num()
                    y = oy + num()
                    path.cubicTo(x1, y1, x2, y2, x, y)
                }
                'Q', 'q' -> {
                    val ox = if (cmd == 'q') x else 0f
                    val oy = if (cmd == 'q') y else 0f
                    val x1 = ox + num()
                    val y1 = oy + num()
                    x = ox + num()
                    y = oy + num()
                    path.quadTo(x1, y1, x, y)
                }
                else -> i++
            }
        }
        return path
    }
}
