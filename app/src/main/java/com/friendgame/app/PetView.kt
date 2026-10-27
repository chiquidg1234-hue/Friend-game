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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Mascota en 3D, estilo plastilina (ver el lienzo "Friend Game · Mascotas 3D"): degradados con luz
 * arriba a la izquierda, brillo en la cabeza y sombra en el piso. Se dibuja a mano en una vista
 * propia, en un espacio de 200×200, igual que el diseño.
 *
 * Está viva como Duo: respira, parpadea, mira a los lados, ladea la cabeza y de vez en cuando
 * saluda o da un saltito. Tiene acciones cortas ([cheer], [dance], [surprise], [laugh], [scare],
 * [love], [troll], [wave], [celebrate], [shower], [eat]) y reacciona a lo que suena con
 * [audioLevel]: mueve la boca y salta al ritmo. Bulla (la mascota de la app) además mueve los
 * brazos, las orejas y la antena, que rebota con resorte.
 *
 * Todo se pinta: ningún texto ni tamaño cambia, así TalkBack y las pruebas pueden leer la pantalla
 * mientras se mueve. Sin animaciones del sistema ([Anim.enabled]) queda quieta.
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

    /** Temporada (ver Seasons): "halloween" (sombrero de bruja y calabaza) o "navidad" (gorro y regalo). */
    var season: String = ""
        set(value) {
            field = value
            invalidate()
        }

    /** false: un solo cuadro quieto (listas, el diálogo para adoptar). */
    var animated: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /** Ropa puesta: gorro, lentes y algo en el cuello (ver [Wardrobe]). */
    var outfit: Wardrobe.Outfit = Wardrobe.Outfit.NONE
        set(value) {
            field = value
            invalidate()
        }

    /** Espuma del baño, de 0 a 1. */
    var foam = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Manchitas de tierra, de 0 a 1 (cuando hace rato que no se baña). */
    var dirt = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Luz apagada en su pieza: se duerme aunque sea de día. */
    var sleepy = false
        set(value) {
            field = value
            invalidate()
        }

    /** Le estás hablando: para las orejas, ladea la cabeza y mira hacia arriba. */
    var listening = false
        set(value) {
            field = value
            invalidate()
        }

    /** Abre la boca esperando la comida que le acercas. */
    var expecting = false
        set(value) {
            field = value
            invalidate()
        }

    /** Volumen de lo que suena ahora (0 a 1): mueve la boca al hablar y salta con cada golpe. */
    var audioLevel: (() -> Float)? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Color con el que brillan la antena y el aura mientras reacciona a un sonido (0: sin brillo). */
    var glow = 0
        set(value) {
            if (field != value) glowPaint = null
            field = value
            invalidate()
        }

    enum class Act(val seconds: Float) {
        NONE(0f), EAT(2.6f), JOY(1.4f), DANCE(4f), SURPRISE(1.6f), LAUGH(2.6f), SCARED(2.2f),
        LOVE(2.6f), TROLL(2.4f), WAVE(1.8f), CHAT(2.6f), CELEBRATE(2f), SHOWER(1.8f),
    }

    /** Lo que está haciendo ahora (NONE si está tranquila). */
    var action = Act.NONE
        private set
    private var actionStart = 0L
    private var actionLength = 0f
    private var food: String? = null
    private var model: PetArt.Model? = null
    private val seed = Random.nextFloat() * 10f
    private val path = Path()
    private val pose = Pose()
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
    private val oval = RectF()
    private var glowPaint: Paint? = null
    private var lookTargetX = 0f
    private var lookTargetY = 0f
    private var lookFixed = false

    // Antena con resorte (Bulla): se queda atrás cuando el cuerpo se mueve y vuelve rebotando.
    private var antX = 0f
    private var antY = 0f
    private var antVX = 0f
    private var antVY = 0f
    private var lastBodyX = 0f
    private var lastBodyY = 0f
    private var lastFrame = 0L

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Come: le cae su comida favorita (u otra que le diste), mastica y queda feliz. */
    fun eat(food: String? = null) {
        this.food = food
        start(Act.EAT)
    }

    /** Feliz: salta y suelta corazones (al acariciarla, al subir de nivel). */
    fun cheer() = start(Act.JOY)

    /** Baila al ritmo, con notas musicales. */
    fun dance(seconds: Float = Act.DANCE.seconds) = start(Act.DANCE, seconds)

    /** Se sorprende: se agacha, salta y abre los ojos (al llegar un sonido). */
    fun surprise() = start(Act.SURPRISE)

    /** Se ríe a carcajadas: «¡JA, JA!». */
    fun laugh(seconds: Float = Act.LAUGH.seconds) = start(Act.LAUGH, seconds)

    /** Se asusta: tiembla, suda y levanta los brazos. */
    fun scare(seconds: Float = Act.SCARED.seconds) = start(Act.SCARED, seconds)

    /** Se enamora: ojos de corazón. */
    fun love(seconds: Float = Act.LOVE.seconds) = start(Act.LOVE, seconds)

    /** Travieso: cuernitos, sonrisa de lado y se frota las manos. */
    fun troll(seconds: Float = Act.TROLL.seconds) = start(Act.TROLL, seconds)

    /** Saluda con la mano (o ladea la cabeza, si no tiene brazos que mover). */
    fun wave() = start(Act.WAVE)

    /** Habla: mueve la boca y las manos (con [audioLevel], al ritmo de lo que suena). */
    fun chat(seconds: Float = Act.CHAT.seconds) = start(Act.CHAT, seconds)

    /** Festeja: dos saltos grandes con estrellas. */
    fun celebrate() = start(Act.CELEBRATE)

    /** Ducha: le cae agua, cierra los ojos y se sacude. */
    fun shower() = start(Act.SHOWER)

    /** Reacciona a la vibe (categoría) de un sonido durante [seconds] (0: lo normal). */
    fun react(vibe: String, seconds: Float = 0f) {
        when (vibe) {
            Vibes.RISA.key -> laugh(seconds)
            Vibes.FIESTA.key -> dance(seconds.takeIf { it > 0f } ?: Act.DANCE.seconds)
            Vibes.SUSTO.key -> scare(seconds)
            Vibes.AMOR.key -> love(seconds)
            Vibes.VOZ.key -> chat(seconds)
            else -> troll(seconds)
        }
    }

    /** Termina lo que está haciendo y vuelve a estar tranquila. */
    fun calm() {
        action = Act.NONE
        invalidate()
    }

    /** Mira hacia un punto de la vista (por ejemplo, la comida que arrastras). */
    fun lookAt(x: Float, y: Float) {
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val px = (x - (width - size) / 2f) / size * 200f
        val py = (y - (height - size) / 2f) / size * 200f
        lookTargetX = ((px - 100f) / 70f).coerceIn(-1f, 1f)
        lookTargetY = ((py - 92f) / 70f).coerceIn(-1f, 1f)
        lookFixed = true
        invalidate()
    }

    /** Vuelve a mirar sola. */
    fun lookAround() {
        lookFixed = false
        invalidate()
    }

    /** ¿El punto (en coordenadas de la vista) está cerca de su boca? Para darle de comer arrastrando. */
    fun nearMouth(x: Float, y: Float): Boolean {
        val m = model ?: return false
        val size = min(width, height).toFloat()
        if (size <= 0f) return false
        val px = (x - (width - size) / 2f) / size * 200f
        val py = (y - (height - size) / 2f) / size * 200f
        return abs(px - m.mouthX) < 46f && abs(py - m.mouthY) < 52f
    }

    private fun start(next: Act, seconds: Float = 0f) {
        action = next
        actionLength = if (seconds > 0f) seconds else next.seconds
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

    private enum class Face { OPEN, HAPPY, SAD, CLOSED, WIDE, HEARTS, SMIRK }

    private enum class Mouth { NORMAL, OPEN, CHEW, SMALL, SAD, O, LAUGH, TALK, SMIRK, WOBBLY }

    private enum class Brows { NONE, UP, SAD, WORRIED, MISCHIEF }

    /** Cómo está el cuerpo en este cuadro. Se reusa en cada cuadro (sin crear objetos al dibujar). */
    private class Pose {
        var lift = 0f
        var tilt = 0f
        var shakeX = 0f
        var sx = 1f
        var sy = 1f
        var face = Face.OPEN
        var mouth = Mouth.NORMAL
        var open = 0f
        var brows = Brows.NONE
        var lookX = 0f
        var lookY = 0f
        /** Brazos: 0 colgando, 90 hacia el lado, 160 arriba; negativo: abrazando la panza. */
        var armL = 0f
        var armR = 0f
        /** Orejas: positivo, paradas; negativo, caídas. */
        var ears = 0f

        fun reset() {
            lift = 0f; tilt = 0f; shakeX = 0f; sx = 1f; sy = 1f
            face = Face.OPEN; mouth = Mouth.NORMAL; open = 0f; brows = Brows.NONE
            lookX = 0f; lookY = 0f; armL = 0f; armR = 0f; ears = 0f
        }
    }

    override fun onDraw(canvas: Canvas) {
        val m = model ?: PetArt.build(species).also { model = it }
        val now = SystemClock.uptimeMillis()
        val live = animated && Anim.enabled()
        val t = if (live) now / 1000f + seed else 1.2f
        var elapsed = (now - actionStart) / 1000f
        if (action == Act.EAT && elapsed > actionLength) {
            start(Act.JOY)
            elapsed = 0f
        }
        if (action != Act.NONE && elapsed > actionLength) action = Act.NONE
        if (!live) action = Act.NONE
        val level = if (live) (audioLevel?.invoke() ?: 0f).coerceIn(0f, 1f) else 0f
        val quiet = level < 0.03f && !listening
        val asleep = action == Act.NONE && stage > 0 && quiet && (sleepy || (live && sleeping(now)))
        val sad = action == Act.NONE && quiet && !asleep && mood != Pets.Mood.HAPPY
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now

        val size = min(width, height).toFloat()
        if (size <= 0f) return
        canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        canvas.scale(size / 200f, size / 200f)

        val p = pose
        computePose(p, m, t, elapsed, level, asleep, sad)

        val hop = (-p.lift / 26f).coerceIn(0f, 1f)
        val shadowScale = 1f - 0.3f * hop
        canvas.save()
        canvas.translate(p.shakeX, 0f)
        canvas.scale(shadowScale, shadowScale, 100f, 183f)
        canvas.drawPath(PetArt.shadow.path, PetArt.shadow.paint)
        canvas.restore()

        if (stage == 0) {
            drawEgg(canvas, m, t, live, elapsed)
            drawSeasonProp(canvas)
            canvas.restore()
            if (live) postInvalidateDelayed(FRAME_IDLE_MS)
            return
        }

        if (glow != 0 && live) drawGlow(canvas, t, level)
        if (stage == 4) {
            val g = 1f + 0.03f * sin(t * 2.1f)
            canvas.save()
            canvas.scale(g, g, 100f, 110f)
            canvas.drawPath(PetArt.aura.path, PetArt.aura.paint)
            canvas.restore()
        }

        canvas.save()
        val grow = STAGE_SCALE[stage] * m.scale
        canvas.scale(grow, grow, 100f, 182f)
        canvas.translate(p.shakeX / grow, p.lift / grow)
        canvas.rotate(p.tilt, 100f, 178f)
        val period = when {
            asleep -> 4.2f
            sad -> 3.4f
            else -> 2.6f
        }
        val breath = if (live) sin(t * 2 * PI.toFloat() / period) * 0.5f + 0.5f else 0.5f
        canvas.scale(p.sx * (1f + 0.015f * breath), p.sy * (1f + 0.035f * breath), 100f, 182f)

        // Cola (o tentáculos): se mueve desde su base; bailando, más rápido.
        if (m.tail.isNotEmpty()) {
            val speed = when {
                action == Act.DANCE -> m.tailPeriod * 0.5f
                sad || asleep -> m.tailPeriod * 2f
                else -> m.tailPeriod
            }
            val angle = if (live) sin(t * 2 * PI.toFloat() / speed) * m.tailAngle else 0f
            canvas.save()
            canvas.rotate(angle, m.tailX, m.tailY)
            m.tail.forEach { canvas.drawPath(it.path, it.paint) }
            canvas.restore()
        }
        val rig = m.rig
        if (rig != null) {
            drawAntenna(canvas, rig, p, dt, live, level)
            drawEar(canvas, rig.earL, rig.earLX, rig.earLY, p.ears)
            drawEar(canvas, rig.earR, rig.earRX, rig.earRY, -p.ears)
        }
        m.back.forEach { canvas.drawPath(it.path, it.paint) }
        m.front.forEach { canvas.drawPath(it.path, it.paint) }
        if (rig != null) {
            drawLimb(canvas, rig.armL, rig.armLX, rig.armLY, p.armL)
            drawLimb(canvas, rig.armR, rig.armRX, rig.armRY, -p.armR)
        }
        if (dirt > 0.05f) drawDirt(canvas)

        if (rig != null) drawBigEyes(canvas, m, rig, p, t, live) else drawEyes(canvas, m, p, t, live)
        drawBrows(canvas, m, p, rig != null)
        m.over.forEach { canvas.drawPath(it.path, it.paint) }
        drawMouth(canvas, m, p, elapsed)

        val hat = outfit.hat
        if (stage == 1) {
            canvas.drawPath(PetArt.shell.path, PetArt.shell.paint)
            canvas.drawPath(PetArt.shellEdge.path, PetArt.shellEdge.paint)
        }
        if (stage == 4 && hat == null) PetArt.crown.forEach { canvas.drawPath(it.path, it.paint) }
        // Disfraz de temporada: solo joven y adulta (el bebé lleva la cáscara y la legendaria, la corona).
        if (stage in 2..3 && species != "unicornio" && hat == null) {
            when (season) {
                "halloween" -> PetArt.witchHat.forEach { canvas.drawPath(it.path, it.paint) }
                "navidad" -> PetArt.santaHat.forEach { canvas.drawPath(it.path, it.paint) }
            }
        }
        if (stage >= 2) Wardrobe.draw(canvas, m, outfit, rig != null)
        if (action == Act.TROLL) drawHorns(canvas, m, elapsed)

        // Efectos que se mueven con la mascota.
        if (foam > 0.02f) drawFoam(canvas, t, live)
        if (action == Act.EAT) drawFood(canvas, m, elapsed)
        if (sad && mood == Pets.Mood.SAD && live) drawTear(canvas, m, t)
        if (action == Act.LAUGH) drawJoyTears(canvas, m, elapsed)
        if (action == Act.SCARED) drawSweat(canvas, m, elapsed)
        canvas.restore()

        drawSeasonProp(canvas)
        when (action) {
            Act.JOY -> drawHearts(canvas, elapsed, 1.1f)
            Act.LOVE -> drawHearts(canvas, elapsed % 1.3f, 1.2f)
            Act.DANCE -> drawNotes(canvas, elapsed)
            Act.SURPRISE -> drawBang(canvas, elapsed)
            Act.LAUGH -> drawHaHa(canvas, elapsed)
            Act.CELEBRATE -> drawBurst(canvas, elapsed)
            Act.SHOWER -> drawRain(canvas, elapsed)
            Act.CHAT -> drawChatter(canvas, elapsed)
            else -> Unit
        }
        if (listening && live) drawListening(canvas, t)
        if (asleep) drawZs(canvas, t)
        if (stage == 4 && live) drawSparkles(canvas, t)
        canvas.restore()

        if (live) {
            val busy = action != Act.NONE || level > 0.01f || foam > 0.02f || listening ||
                (rig != null && (abs(antVX) + abs(antVY) > 2f))
            if (busy) postInvalidateOnAnimation() else postInvalidateDelayed(FRAME_IDLE_MS)
        }
    }

    // ---------------------------------------------------------------- Pose de cada cuadro

    private fun computePose(p: Pose, m: PetArt.Model, t: Float, e: Float, level: Float, asleep: Boolean, sad: Boolean) {
        p.reset()
        val len = actionLength.coerceAtLeast(0.1f)
        val tau = 2 * PI.toFloat()
        when (action) {
            Act.NONE -> when {
                asleep -> {
                    p.face = Face.CLOSED
                    p.mouth = Mouth.SMALL
                    p.ears = -12f
                    p.armL = -8f
                    p.armR = -8f
                }
                listening -> {
                    p.mouth = Mouth.SMALL
                    p.ears = 20f + sin(t * 9f) * 3f
                    p.tilt = 7f + sin(t * 2f) * 2f
                    p.lookX = 0.45f
                    p.lookY = -0.75f
                    p.brows = Brows.UP
                    p.armR = 25f
                }
                sad -> {
                    p.face = Face.SAD
                    p.mouth = Mouth.SAD
                    p.lookY = 0.6f
                    p.ears = -20f
                    p.brows = Brows.SAD
                    p.armL = -6f
                    p.armR = -6f
                }
                else -> idleGesture(p, m, t)
            }
            Act.EAT -> {
                val happy = e > 2.0f
                p.face = Face.HAPPY
                p.mouth = when {
                    e < 0.8f -> Mouth.O
                    e < 2.0f -> Mouth.CHEW
                    else -> Mouth.OPEN
                }
                p.open = 1f
                p.lookY = if (e < 0.8f) -0.9f + e else 0f
                p.armL = if (e < 2.0f) 40f + sin(e * 12f) * 10f else 0f
                p.armR = p.armL
                if (happy) p.ears = 10f
            }
            Act.JOY -> {
                val hop = abs(sin(e / len * tau))
                p.lift = -24f * hop
                if (hop < 0.15f) {
                    val k = 0.06f * (1f - hop / 0.15f)
                    p.sx = 1f + k
                    p.sy = 1f - k
                }
                p.face = Face.HAPPY
                p.mouth = Mouth.OPEN
                p.armL = 140f * hop + 20f
                p.armR = p.armL
                p.ears = 14f * hop
            }
            Act.CELEBRATE -> {
                val hop = abs(sin(e / len * tau))
                p.lift = -26f * hop
                if (hop < 0.2f) {
                    val k = 0.08f * (1f - hop / 0.2f)
                    p.sx = 1f + k
                    p.sy = 1f - k
                } else {
                    p.sx = 1f - 0.04f * hop
                    p.sy = 1f + 0.06f * hop
                }
                p.face = Face.HAPPY
                p.mouth = Mouth.LAUGH
                p.armL = 165f
                p.armR = 165f
                p.ears = 18f
                p.tilt = sin(e * 7f) * 5f
            }
            Act.DANCE -> {
                val beat = e / 0.48f
                val swing = sin(beat * PI.toFloat())
                p.tilt = swing * 11f
                p.shakeX = swing * 7f
                p.lift = -8f * abs(sin(beat * PI.toFloat()))
                if (abs(sin(beat * PI.toFloat())) < 0.18f) {
                    p.sx = 1.05f
                    p.sy = 0.95f
                }
                p.face = Face.HAPPY
                p.mouth = Mouth.OPEN
                p.armL = 70f + 80f * max(0f, swing)
                p.armR = 70f + 80f * max(0f, -swing)
                p.ears = 10f * swing
            }
            Act.SURPRISE -> {
                when {
                    e < 0.14f -> {
                        val k = e / 0.14f
                        p.sx = 1f + 0.09f * k
                        p.sy = 1f - 0.1f * k
                        p.face = Face.OPEN
                    }
                    e < 0.55f -> {
                        val k = (e - 0.14f) / 0.41f
                        p.lift = -22f * sin(k * PI.toFloat())
                        p.sx = 0.93f
                        p.sy = 1.1f
                        p.face = Face.WIDE
                    }
                    else -> {
                        p.face = Face.WIDE
                        val k = ((e - 0.55f) / 0.4f).coerceIn(0f, 1f)
                        p.shakeX = sin(e * 60f) * 1.6f * (1f - k)
                    }
                }
                p.mouth = Mouth.O
                p.open = 1f
                p.brows = Brows.UP
                p.ears = 24f
                p.armL = if (e > 0.14f) 120f else 30f
                p.armR = p.armL
                p.lookY = -0.2f
            }
            Act.LAUGH -> {
                p.face = Face.HAPPY
                p.mouth = Mouth.LAUGH
                p.shakeX = sin(e * 42f) * 2.4f
                p.tilt = -5f + sin(e * 6f) * 6f
                p.lift = -5f * abs(sin(e * 11f))
                p.armL = -24f + sin(e * 22f) * 6f
                p.armR = -24f - sin(e * 22f) * 6f
                p.ears = 8f + sin(e * 22f) * 6f
            }
            Act.SCARED -> {
                p.face = Face.WIDE
                p.mouth = Mouth.WOBBLY
                p.brows = Brows.WORRIED
                p.shakeX = sin(e * 58f) * 2.2f
                p.lift = if (e < 0.3f) -12f * sin(e / 0.3f * PI.toFloat()) else 0f
                p.sx = 0.97f
                p.sy = 1.02f
                p.armL = 150f + sin(e * 40f) * 8f
                p.armR = 150f - sin(e * 40f) * 8f
                p.ears = -24f
                p.lookX = sin(e * 3f) * 0.6f
            }
            Act.LOVE -> {
                p.face = Face.HEARTS
                p.mouth = Mouth.OPEN
                p.tilt = sin(e * 3f) * 7f
                p.lift = -3f * abs(sin(e * 3f))
                p.armL = -30f
                p.armR = -30f
                p.ears = 6f
            }
            Act.TROLL -> {
                p.face = Face.SMIRK
                p.mouth = Mouth.SMIRK
                p.brows = Brows.MISCHIEF
                p.lookX = 0.85f
                p.tilt = 6f
                p.shakeX = sin(e * 30f) * 0.8f
                p.armL = 35f + sin(e * 18f) * 9f
                p.armR = 35f - sin(e * 18f) * 9f
                p.ears = 4f
            }
            Act.WAVE -> {
                p.mouth = Mouth.OPEN
                p.face = if (e > 0.3f) Face.HAPPY else Face.OPEN
                p.tilt = 5f * sin(min(e, 1f) * PI.toFloat())
                p.lift = if (e < 0.35f) -8f * sin(e / 0.35f * PI.toFloat()) else 0f
                p.armR = 135f + sin(e * 15f) * 28f
                p.ears = 8f
                if (m.rig == null) p.tilt = 9f * sin(e * 6f)
            }
            Act.CHAT -> {
                p.mouth = Mouth.TALK
                p.open = 0.25f + 0.75f * abs(sin(e * 9f) * sin(e * 3.3f + 1f))
                p.tilt = sin(e * 2.4f) * 5f
                p.lift = -3f * abs(sin(e * 4.5f))
                p.armR = 60f + sin(e * 5f) * 40f
                p.armL = 30f + sin(e * 4f + 1f) * 30f
                p.ears = sin(e * 9f) * 6f
                p.brows = if (sin(e * 1.7f) > 0.6f) Brows.UP else Brows.NONE
            }
            Act.SHOWER -> {
                p.face = Face.CLOSED
                p.mouth = Mouth.SMALL
                p.ears = -14f
                if (e > 1.15f) {
                    // Se sacude como un perro mojado.
                    p.shakeX = sin(e * 52f) * 3.5f
                    p.tilt = sin(e * 52f) * 4f
                    p.face = Face.HAPPY
                    p.mouth = Mouth.OPEN
                }
            }
        }
        if (level > 0.03f && action != Act.SHOWER) {
            // Reacciona a lo que suena: la boca sigue el volumen y da un saltito con cada golpe.
            if (p.mouth != Mouth.LAUGH) p.mouth = Mouth.TALK
            p.open = max(p.open, level)
            p.lift -= level * 7f
            p.sy *= 1f + level * 0.04f
            p.sx *= 1f - level * 0.02f
            p.ears += level * 12f
        }
        if (expecting) {
            p.face = Face.OPEN
            p.mouth = Mouth.O
            p.open = 1f
            p.brows = Brows.UP
            p.ears = 16f
        }
        if (lookFixed) {
            p.lookX = lookTargetX
            p.lookY = lookTargetY
        }
    }

    /** Tranquila no está quieta: cada ~6 s hace algo chiquito (como Duo en la pantalla de inicio). */
    private fun idleGesture(p: Pose, m: PetArt.Model, t: Float) {
        val cycle = 6.5f
        val n = floor(t / cycle).toInt()
        val g = ((n.toLong() * 2654435761L) ushr 9).toInt() and 7
        val k = t - n * cycle
        p.lookX = sin(t * 0.7f) * 0.25f
        if (k > 1.6f) return
        val q = k / 1.6f
        val bell = sin(q * PI.toFloat())
        when (g) {
            0, 5 -> p.lookX = sin(q * 2 * PI.toFloat()) * 0.9f
            1 -> {
                p.lift = -9f * abs(sin(q * 2 * PI.toFloat()))
                p.mouth = Mouth.OPEN
                p.armL = 30f * bell
                p.armR = 30f * bell
            }
            2 -> if (m.rig != null) {
                p.armR = 120f * bell + sin(q * 22f) * 22f * bell
                p.mouth = Mouth.OPEN
                p.face = if (bell > 0.5f) Face.HAPPY else Face.OPEN
            } else {
                p.lookY = -0.8f * bell
            }
            3 -> p.ears = sin(q * 30f) * 12f * bell
            4 -> {
                p.tilt = 9f * bell
                p.lookX = 0.6f * bell
                p.brows = Brows.UP
            }
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- Piezas

    private fun drawGlow(canvas: Canvas, t: Float, level: Float) {
        val paint = glowPaint ?: Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                100f, 120f, 96f,
                intArrayOf((glow and 0x00FFFFFF) or 0x88000000.toInt(), (glow and 0x00FFFFFF) or 0x22000000, glow and 0x00FFFFFF),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
            )
        }.also { glowPaint = it }
        val s = 0.9f + 0.08f * sin(t * 3f) + 0.25f * level
        canvas.save()
        canvas.scale(s, s, 100f, 120f)
        canvas.drawCircle(100f, 120f, 96f, paint)
        canvas.restore()
    }

    private fun drawEar(canvas: Canvas, shapes: List<PetArt.Shape>, x: Float, y: Float, degrees: Float) {
        canvas.save()
        canvas.rotate(degrees, x, y)
        shapes.forEach { canvas.drawPath(it.path, it.paint) }
        canvas.restore()
    }

    private fun drawLimb(canvas: Canvas, shapes: List<PetArt.Shape>, x: Float, y: Float, degrees: Float) {
        canvas.save()
        canvas.rotate(degrees, x, y)
        shapes.forEach { canvas.drawPath(it.path, it.paint) }
        canvas.restore()
    }

    /** Antena de Bulla: tallo curvo hasta una bolita que se queda atrás y rebota (resorte). */
    private fun drawAntenna(canvas: Canvas, rig: PetArt.Rig, p: Pose, dt: Float, live: Boolean, level: Float) {
        val bodyX = p.shakeX + p.tilt * 1.4f
        val bodyY = p.lift
        if (live && dt > 0f) {
            antX -= bodyX - lastBodyX
            antY -= bodyY - lastBodyY
            val steps = 2
            val h = dt / steps
            repeat(steps) {
                antVX += (-170f * antX - 9f * antVX) * h
                antVY += (-170f * antY - 9f * antVY) * h
                antX += antVX * h
                antY += antVY * h
            }
            antX = antX.coerceIn(-16f, 16f)
            antY = antY.coerceIn(-12f, 12f)
        } else if (!live) {
            antX = 0f
            antY = 0f
        }
        lastBodyX = bodyX
        lastBodyY = bodyY
        val tipX = rig.antennaTipX + antX
        val tipY = rig.antennaTipY + antY - level * 4f
        path.reset()
        path.moveTo(rig.antennaX, rig.antennaY)
        path.quadTo(rig.antennaX + antX * 0.2f, (rig.antennaY + tipY) / 2f, tipX, tipY)
        canvas.drawPath(path, rig.stalk)
        if (glow != 0) {
            paint.shader = null
            paint.color = glow
            paint.alpha = (90 + 120 * level).toInt().coerceAtMost(220)
            canvas.drawCircle(tipX, tipY, rig.ballR * (1.7f + level), paint)
            paint.alpha = 255
        }
        canvas.save()
        canvas.translate(tipX - rig.antennaTipX, tipY - rig.antennaTipY)
        rig.ball.forEach { canvas.drawPath(it.path, it.paint) }
        canvas.restore()
    }

    private fun drawEgg(canvas: Canvas, m: PetArt.Model, t: Float, live: Boolean, elapsed: Float) {
        // Cada 2,2 s se tambalea un poco; más fuerte si le falta poco para nacer o si algo la sorprende.
        val phase = (t % 2.2f) / 2.2f
        val strength = if (progress > 0.7f || action != Act.NONE) 9f else 5f
        var wobble = if (live && phase > 0.6f) sin((phase - 0.6f) / 0.4f * 4 * PI.toFloat()) * strength * (1f - phase) * 2.5f else 0f
        if (live && action != Act.NONE && elapsed < 1f) wobble += sin(elapsed * 30f) * 8f * (1f - elapsed)
        canvas.save()
        canvas.scale(1.18f, 1.18f, 100f, 174f)
        canvas.rotate(wobble, 100f, 172f)
        canvas.drawPath(PetArt.egg.path, PetArt.egg.paint)
        m.eggSpots.forEach { canvas.drawPath(it.path, it.paint) }
        canvas.drawPath(PetArt.eggShine.path, PetArt.eggShine.paint)
        if (progress > 0.5f) canvas.drawPath(PetArt.crack.path, PetArt.crack.paint)
        canvas.restore()
    }

    /** Ojos de las mascotas de siempre (pequeños y oscuros). */
    private fun drawEyes(canvas: Canvas, m: PetArt.Model, p: Pose, t: Float, live: Boolean) {
        val e = m.eyes
        val face = p.face
        val blink = blinkAmount(t, live, face)
        val dx = p.lookX * 2.6f
        val dy = p.lookY * 2f
        for (x in floatArrayOf(e.left, e.right)) {
            if (e.sclera > 0f) {
                // Rana: ojos sobre la cabeza, con blanco.
                canvas.save()
                canvas.scale(1f, if (face == Face.OPEN || face == Face.WIDE) blink else 1f, x, e.y - 2f)
                canvas.drawCircle(if (x < 100f) x - 2f else x + 2f, e.y - 2f, e.sclera * if (face == Face.WIDE) 1.1f else 1f, m.scleraPaint)
                canvas.restore()
            }
            val ex = x + dx
            val ey = e.y + dy
            when (face) {
                Face.OPEN, Face.SAD, Face.WIDE, Face.SMIRK -> {
                    val grow = if (face == Face.WIDE) 1.22f else 1f
                    val rx = e.rx * grow
                    val ry = (if (face == Face.SAD) e.ry * 0.8f else e.ry) * grow
                    canvas.save()
                    if (face == Face.SMIRK) canvas.clipRect(ex - rx - 2f, ey - ry * 0.1f, ex + rx + 2f, ey + ry + 2f)
                    canvas.scale(1f, blink, ex, ey)
                    paint.shader = null
                    if (e.dark) {
                        paint.color = 0xFF06050A.toInt()
                        canvas.drawOval(ex - rx, ey - ry, ex + rx, ey + ry, paint)
                    } else {
                        canvas.drawOval(ex - rx, ey - ry, ex + rx, ey + ry, m.eyePaint(x, e.y, e.rx, e.ry))
                    }
                    paint.color = 0xFFFFFFFF.toInt()
                    val shine = if (face == Face.WIDE) 0.3f else 0.4f
                    canvas.drawCircle(ex - rx * 0.33f, ey - ry * 0.42f, rx * shine, paint)
                    canvas.drawCircle(ex + rx * 0.33f, ey + ry * 0.42f, rx * 0.18f, paint)
                    canvas.restore()
                    if (face == Face.SMIRK) {
                        line.color = m.ink
                        line.strokeWidth = 2.6f
                        canvas.drawLine(ex - rx - 1f, ey - ry * 0.1f, ex + rx + 1f, ey - ry * 0.1f, line)
                    }
                }
                Face.HAPPY -> {
                    // El panda tiene los ojos sobre manchas negras: ahí la línea va en blanco.
                    line.color = if (e.dark) 0xFFFFFFFF.toInt() else 0xFF06050A.toInt()
                    line.strokeWidth = 3.2f
                    path.reset()
                    path.moveTo(x - 7f, e.y + 2f)
                    path.quadTo(x, e.y - 7f, x + 7f, e.y + 2f)
                    canvas.drawPath(path, line)
                }
                Face.CLOSED -> {
                    line.color = if (e.dark) 0xFFFFFFFF.toInt() else 0xFF06050A.toInt()
                    line.strokeWidth = 3.2f
                    path.reset()
                    path.moveTo(x - 7f, e.y - 1f)
                    path.quadTo(x, e.y + 6f, x + 7f, e.y - 1f)
                    canvas.drawPath(path, line)
                }
                Face.HEARTS -> drawHeartEye(canvas, x, e.y, 0.62f, t)
            }
        }
        if ((face == Face.OPEN || face == Face.WIDE) && blink > 0.5f) m.lashes.forEach { canvas.drawPath(it.path, it.paint) }
    }

    /** Ojos grandes de Bulla: blanco con pupila que mira a los lados, como Duo. */
    private fun drawBigEyes(canvas: Canvas, m: PetArt.Model, rig: PetArt.Rig, p: Pose, t: Float, live: Boolean) {
        val e = m.eyes
        val face = p.face
        val blink = blinkAmount(t, live, face)
        for (x in floatArrayOf(e.left, e.right)) {
            when (face) {
                Face.OPEN, Face.SAD, Face.WIDE, Face.SMIRK -> {
                    val grow = if (face == Face.WIDE) 1.12f else 1f
                    val rx = rig.scleraRx * grow
                    val ry = rig.scleraRy * grow * (if (face == Face.SAD) 0.9f else 1f)
                    canvas.save()
                    if (face == Face.SMIRK) canvas.clipRect(x - rx - 3f, e.y - ry * 0.05f, x + rx + 3f, e.y + ry + 3f)
                    canvas.scale(1f, blink, x, e.y)
                    oval.set(x - rx, e.y - ry, x + rx, e.y + ry)
                    canvas.drawOval(oval, rig.sclera)
                    canvas.drawOval(oval, rig.scleraEdge)
                    val pr = rig.pupil * (if (face == Face.WIDE) 0.72f else 1f)
                    val px = x + p.lookX * (rx - pr - 2.5f) * 0.85f
                    val py = e.y + p.lookY * (ry - pr - 2.5f) * 0.85f + (if (face == Face.SAD) 2f else 0f)
                    paint.shader = null
                    paint.color = rig.pupilColor
                    canvas.drawCircle(px, py, pr, paint)
                    paint.color = 0xFFFFFFFF.toInt()
                    canvas.drawCircle(px - pr * 0.35f, py - pr * 0.4f, pr * 0.38f, paint)
                    canvas.drawCircle(px + pr * 0.38f, py + pr * 0.36f, pr * 0.16f, paint)
                    canvas.restore()
                    if (face == Face.SMIRK) {
                        line.color = m.ink
                        line.strokeWidth = 3.4f
                        canvas.drawLine(x - rx - 1f, e.y - ry * 0.05f, x + rx + 1f, e.y - ry * 0.05f, line)
                    }
                }
                Face.HAPPY -> {
                    line.color = m.ink
                    line.strokeWidth = 4.6f
                    path.reset()
                    path.moveTo(x - 10f, e.y + 4f)
                    path.quadTo(x, e.y - 10f, x + 10f, e.y + 4f)
                    canvas.drawPath(path, line)
                }
                Face.CLOSED -> {
                    line.color = m.ink
                    line.strokeWidth = 4.2f
                    path.reset()
                    path.moveTo(x - 10f, e.y)
                    path.quadTo(x, e.y + 8f, x + 10f, e.y)
                    canvas.drawPath(path, line)
                }
                Face.HEARTS -> drawHeartEye(canvas, x, e.y, 1f, t)
            }
        }
    }

    private fun blinkAmount(t: Float, live: Boolean, face: Face): Float {
        if (!live || (face != Face.OPEN && face != Face.SAD)) return 1f
        // Parpadeo: cada ~4,4 s se cierran un instante (a veces, dos veces seguidas).
        val phase = (t % 4.4f) / 4.4f
        return when {
            phase > 0.93f -> 0.08f + 0.92f * abs(1f - 2f * (phase - 0.93f) / 0.07f)
            phase in 0.86f..0.9f && ((t / 4.4f).toInt() % 3 == 0) -> 0.08f + 0.92f * abs(1f - 2f * (phase - 0.86f) / 0.04f)
            else -> 1f
        }
    }

    private fun drawHeartEye(canvas: Canvas, x: Float, y: Float, scale: Float, t: Float) {
        paint.shader = null
        paint.color = 0xFFFF3D7F.toInt()
        val pulse = scale * (1f + 0.12f * sin(t * 10f))
        canvas.save()
        canvas.translate(x, y - 3f * scale)
        canvas.scale(pulse, pulse)
        canvas.drawPath(PetArt.heart, paint)
        paint.color = 0xFFFFFFFF.toInt()
        paint.alpha = 200
        canvas.drawCircle(-6f, -3f, 2.4f, paint)
        paint.alpha = 255
        canvas.restore()
    }

    private fun drawBrows(canvas: Canvas, m: PetArt.Model, p: Pose, big: Boolean) {
        if (p.brows == Brows.NONE && !big) return
        if (p.face == Face.HAPPY && p.brows == Brows.NONE) return
        val e = m.eyes
        val reach = if (big) (m.rig?.scleraRy ?: e.ry) + 5f else e.ry + 4f
        val half = if (big) 10f else 7f
        line.color = m.ink
        line.strokeWidth = if (big) 3.4f else 2.6f
        for (x in floatArrayOf(e.left, e.right)) {
            val inner = if (x < 100f) 1f else -1f
            val top = e.y - reach
            path.reset()
            when (p.brows) {
                Brows.NONE -> {
                    path.moveTo(x - half, top + 1f)
                    path.quadTo(x, top - 3f, x + half, top + 1f)
                }
                Brows.UP -> {
                    path.moveTo(x - half, top - 4f)
                    path.quadTo(x, top - 11f, x + half, top - 4f)
                }
                Brows.SAD, Brows.WORRIED -> {
                    val lift = if (p.brows == Brows.WORRIED) 4f else 0f
                    path.moveTo(x - inner * half, top + 2f - lift)
                    path.lineTo(x + inner * (half - 2f), top - 5f - lift)
                }
                Brows.MISCHIEF -> if (x < 100f) {
                    path.moveTo(x - half, top - 6f)
                    path.quadTo(x, top - 12f, x + half, top - 5f)
                } else {
                    path.moveTo(x - half, top + 4f)
                    path.lineTo(x + half, top - 1f)
                }
            }
            canvas.drawPath(path, line)
        }
    }

    private fun drawMouth(canvas: Canvas, m: PetArt.Model, p: Pose, elapsed: Float) {
        val x = m.mouthX
        val y = m.mouthY
        val big = if (m.rig != null) 1.25f else 1f
        paint.shader = null
        paint.color = 0xFF5A1A2A.toInt()
        line.color = m.ink
        line.strokeWidth = 2.3f * big
        when (p.mouth) {
            Mouth.NORMAL -> m.mouth.forEach { canvas.drawPath(it.path, it.paint) }
            Mouth.OPEN -> {
                path.reset()
                path.moveTo(x - 10f * big, y - 1f)
                path.quadTo(x, y + 14f * big, x + 10f * big, y - 1f)
                path.close()
                canvas.drawPath(path, paint)
                paint.color = 0xFFFF7FA5.toInt()
                canvas.drawOval(x - 4.5f * big, y + 4.5f * big, x + 4.5f * big, y + 7.5f * big, paint)
            }
            Mouth.CHEW -> {
                val open = 0.2f + 0.8f * abs(sin((elapsed - 0.8f) * 2.6f * PI.toFloat()))
                canvas.drawOval(x - 8f * big, y + 5f - 6.5f * open * big, x + 8f * big, y + 5f + 6.5f * open * big, paint)
            }
            Mouth.SMALL -> canvas.drawOval(x - 4f, y + 1f, x + 4f, y + 7f, paint)
            Mouth.SAD -> {
                path.reset()
                path.moveTo(x - 8f * big, y + 6f)
                path.quadTo(x, y - 1f, x + 8f * big, y + 6f)
                canvas.drawPath(path, line)
            }
            Mouth.O -> {
                val r = (3.5f + 3f * p.open) * big
                canvas.drawOval(x - r, y + 2f, x + r, y + 2f + r * 2.2f, paint)
            }
            Mouth.LAUGH -> {
                path.reset()
                path.moveTo(x - 13f * big, y - 2f)
                path.quadTo(x, y + 24f * big, x + 13f * big, y - 2f)
                path.close()
                canvas.drawPath(path, paint)
                paint.color = 0xFFFF7FA5.toInt()
                canvas.drawOval(x - 6f * big, y + 6f * big, x + 6f * big, y + 11f * big, paint)
            }
            Mouth.TALK -> {
                val open = 0.15f + 0.85f * p.open.coerceIn(0f, 1f)
                canvas.drawOval(x - 8f * big, y + 1f, x + 8f * big, y + 1f + 15f * open * big, paint)
                if (open > 0.45f) {
                    paint.color = 0xFFFF7FA5.toInt()
                    canvas.drawOval(x - 4.5f * big, y + 1f + 10f * open * big, x + 4.5f * big, y + 1f + 14f * open * big, paint)
                }
            }
            Mouth.SMIRK -> {
                path.reset()
                path.moveTo(x - 9f * big, y + 3f)
                path.quadTo(x + 2f, y + 7f * big, x + 11f * big, y - 3f)
                canvas.drawPath(path, line)
            }
            Mouth.WOBBLY -> {
                path.reset()
                path.moveTo(x - 10f * big, y + 5f)
                path.lineTo(x - 5f * big, y + 2f)
                path.lineTo(x, y + 5f)
                path.lineTo(x + 5f * big, y + 2f)
                path.lineTo(x + 10f * big, y + 5f)
                canvas.drawPath(path, line)
            }
        }
    }

    /** La calabaza o el regalo junto a la mascota, en el piso. */
    private fun drawSeasonProp(canvas: Canvas) {
        val prop = when (season) {
            "halloween" -> PetArt.pumpkin
            "navidad" -> PetArt.gift
            else -> return
        }
        prop.forEach { canvas.drawPath(it.path, it.paint) }
    }

    private fun drawHorns(canvas: Canvas, m: PetArt.Model, elapsed: Float) {
        val k = (elapsed / 0.25f).coerceIn(0f, 1f)
        val pop = if (k < 1f) k * 1.2f else 1f
        val top = m.headTop
        paint.shader = null
        paint.color = 0xFFE03153.toInt()
        for (side in floatArrayOf(-1f, 1f)) {
            val x = 100f + side * 20f
            canvas.save()
            canvas.translate(x, top + 4f)
            canvas.scale(pop * side, pop)
            path.reset()
            path.moveTo(-7f, 2f)
            path.quadTo(-6f, -12f, 6f, -18f)
            path.quadTo(1f, -8f, 7f, 2f)
            path.close()
            canvas.drawPath(path, paint)
            canvas.restore()
        }
    }

    private fun drawDirt(canvas: Canvas) {
        paint.shader = null
        paint.color = 0xFF7A5230.toInt()
        val count = (DIRT.size * dirt).toInt().coerceIn(1, DIRT.size)
        for (i in 0 until count) {
            val (x, y, r) = DIRT[i]
            paint.alpha = (110 * dirt).toInt() + 40
            canvas.drawOval(x - r, y - r * 0.7f, x + r, y + r * 0.7f, paint)
            canvas.drawCircle(x + r * 0.9f, y + r * 0.5f, r * 0.3f, paint)
        }
        paint.alpha = 255
    }

    /** Espuma del baño: burbujas blancas sobre el cuerpo y unas que suben. */
    private fun drawFoam(canvas: Canvas, t: Float, live: Boolean) {
        val count = (FOAM.size * foam).toInt().coerceIn(1, FOAM.size)
        for (i in 0 until count) {
            val (x, y, r) = FOAM[i]
            val wobble = if (live) 1f + 0.06f * sin(t * 3f + i) else 1f
            paint.shader = null
            paint.color = 0xFFFFFFFF.toInt()
            paint.alpha = 235
            canvas.drawCircle(x, y, r * wobble, paint)
            line.color = 0xFFB9E3FF.toInt()
            line.strokeWidth = 1.2f
            canvas.drawCircle(x, y, r * wobble, line)
            paint.alpha = 255
        }
        if (!live) return
        for (i in 0 until 4) {
            val k = ((t * 0.5f) + i / 4f) % 1f
            val x = 70f + i * 20f + sin(t * 2f + i) * 6f
            val y = 70f - 60f * k
            line.color = 0xFFB9E3FF.toInt()
            line.strokeWidth = 1.4f
            line.alpha = (255 * (1f - k)).toInt()
            canvas.drawCircle(x, y, 3f + 3f * k, line)
            line.alpha = 255
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
            canvas.drawText(food ?: m.food, m.mouthX, y + text.textSize * 0.35f, text)
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
        drawDrop(canvas, m.eyes.right + 8f, m.eyes.y + 8f + 26f * k, if (k < 0.2f) k / 0.2f else 1f - (k - 0.2f) / 0.8f)
    }

    private fun drawJoyTears(canvas: Canvas, m: PetArt.Model, elapsed: Float) {
        val k = (elapsed % 0.7f) / 0.7f
        val alpha = if (k < 0.2f) k / 0.2f else 1f - (k - 0.2f) / 0.8f
        drawDrop(canvas, m.eyes.left - 10f - 10f * k, m.eyes.y + 2f - 6f * k + 22f * k * k, alpha)
        drawDrop(canvas, m.eyes.right + 10f + 10f * k, m.eyes.y + 2f - 6f * k + 22f * k * k, alpha)
    }

    private fun drawSweat(canvas: Canvas, m: PetArt.Model, elapsed: Float) {
        val k = (elapsed % 1.1f) / 1.1f
        drawDrop(canvas, m.eyes.right + 20f, m.headTop + 18f + 20f * k, if (k < 0.15f) k / 0.15f else 1f - (k - 0.15f) / 0.85f, 1.4f)
    }

    private fun drawDrop(canvas: Canvas, x: Float, y: Float, alpha: Float, scale: Float = 1f) {
        paint.shader = null
        paint.color = 0xFF74C0FC.toInt()
        paint.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
        path.reset()
        path.moveTo(x, y)
        path.quadTo(x + 4f * scale, y + 7f * scale, x, y + 10f * scale)
        path.quadTo(x - 4f * scale, y + 7f * scale, x, y)
        canvas.drawPath(path, paint)
        paint.alpha = 255
    }

    private fun drawHearts(canvas: Canvas, elapsed: Float, length: Float) {
        paint.shader = null
        paint.color = 0xFFFF5FAE.toInt()
        HEARTS.forEach { (x, y, delay) ->
            val k = ((elapsed - delay) / length)
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

    /** Notas musicales que suben bailando. */
    private fun drawNotes(canvas: Canvas, elapsed: Float) {
        text.typeface = Fonts.display(context, 700)
        for (i in 0 until 4) {
            val k = ((elapsed / 1.6f) + i / 4f) % 1f
            val x = (if (i % 2 == 0) 30f else 170f) + sin(k * 9f + i) * 8f
            val y = 110f - 90f * k
            text.color = NOTE_COLORS[i % NOTE_COLORS.size]
            text.alpha = (255 * (if (k < 0.15f) k / 0.15f else 1f - (k - 0.15f) / 0.85f)).toInt()
            text.textSize = 22f + 8f * k
            canvas.drawText(if (i % 2 == 0) "♪" else "♫", x, y, text)
        }
        text.alpha = 255
        text.color = 0xFFFFFFFF.toInt()
    }

    /** «!» que salta al sorprenderse. */
    private fun drawBang(canvas: Canvas, elapsed: Float) {
        if (elapsed < 0.12f || elapsed > 1.4f) return
        val k = (elapsed - 0.12f) / 0.25f
        val pop = if (k < 1f) 0.4f + 0.9f * k else 1f + 0.12f * sin((elapsed - 0.37f) * 14f) * max(0f, 1f - (elapsed - 0.37f))
        text.typeface = Fonts.display(context, 700)
        text.textSize = 46f * pop
        text.color = 0xFFFFC93C.toInt()
        text.alpha = if (elapsed > 1.15f) (255 * (1.4f - elapsed) / 0.25f).toInt() else 255
        canvas.save()
        canvas.rotate(12f, 160f, 40f)
        canvas.drawText("!", 160f, 56f, text)
        canvas.restore()
        text.alpha = 255
        text.color = 0xFFFFFFFF.toInt()
    }

    /** «JA» que salen a los lados mientras se ríe. */
    private fun drawHaHa(canvas: Canvas, elapsed: Float) {
        text.typeface = Fonts.display(context, 700)
        for (i in 0 until 3) {
            val start = i * 0.45f
            val k = ((elapsed - start) % 1.35f) / 0.9f
            if (elapsed < start || k > 1f) continue
            val left = i % 2 == 0
            val x = if (left) 36f - 6f * k else 164f + 6f * k
            val y = 70f - 26f * k + i * 6f
            text.color = if (left) 0xFFFFC93C.toInt() else 0xFFFF7AB8.toInt()
            text.alpha = (255 * (if (k < 0.2f) k / 0.2f else 1f - (k - 0.2f) / 0.8f)).toInt()
            text.textSize = 18f + 10f * min(1f, k * 3f)
            canvas.save()
            canvas.rotate(if (left) -14f else 14f, x, y)
            canvas.drawText("JA", x, y, text)
            canvas.restore()
        }
        text.alpha = 255
        text.color = 0xFFFFFFFF.toInt()
    }

    /** Estrellas que salen hacia afuera al festejar. */
    private fun drawBurst(canvas: Canvas, elapsed: Float) {
        val k = (elapsed / 1.3f).coerceIn(0f, 1f)
        if (k >= 1f) return
        paint.shader = null
        for (i in 0 until 10) {
            val angle = i * (2 * PI.toFloat() / 10) + 0.3f
            val r = 30f + 80f * k
            paint.color = NOTE_COLORS[i % NOTE_COLORS.size]
            paint.alpha = (255 * (1f - k)).toInt()
            canvas.save()
            canvas.translate(100f + kotlin.math.cos(angle) * r, 105f + sin(angle) * r * 0.85f)
            val s = 0.6f + 0.5f * (1f - k)
            canvas.scale(s, s)
            canvas.drawPath(PetArt.star, paint)
            canvas.restore()
        }
        paint.alpha = 255
    }

    /** Gotas de la ducha. */
    private fun drawRain(canvas: Canvas, elapsed: Float) {
        if (elapsed > 1.2f) return
        paint.shader = null
        paint.color = 0xFF74C0FC.toInt()
        for (i in 0 until 14) {
            val k = ((elapsed * 1.6f) + i * 0.137f) % 1f
            val x = 46f + (i * 37 % 110)
            val y = -6f + 180f * k
            paint.alpha = 210
            canvas.drawRoundRect(x - 1.6f, y, x + 1.6f, y + 11f, 2f, 2f, paint)
        }
        paint.alpha = 255
    }

    /** Burbujitas de conversación mientras «habla». */
    private fun drawChatter(canvas: Canvas, elapsed: Float) {
        val k = (elapsed % 0.9f) / 0.9f
        paint.shader = null
        paint.color = 0xFFFFFFFF.toInt()
        for (i in 0 until 3) {
            paint.alpha = (255 * (0.3f + 0.7f * abs(sin((k + i / 3f) * PI.toFloat())))).toInt()
            canvas.drawCircle(150f + i * 9f, 52f - i * 4f, 3f, paint)
        }
        paint.alpha = 255
    }

    /** Ondas de sonido junto a la oreja mientras escucha. */
    private fun drawListening(canvas: Canvas, t: Float) {
        line.color = 0xFF48DBFB.toInt()
        line.strokeWidth = 2.6f
        for (i in 0 until 3) {
            val k = ((t * 0.9f) + i / 3f) % 1f
            line.alpha = (255 * (1f - k)).toInt()
            val r = 10f + 18f * k
            oval.set(150f - r, 60f - r, 150f + r, 60f + r)
            canvas.drawArc(oval, -50f, 100f, false, line)
        }
        line.alpha = 255
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
        /** Quieta, 30 cuadros por segundo; haciendo algo, al ritmo de la pantalla. */
        const val FRAME_IDLE_MS = 33L
        val HEARTS = listOf(Triple(40f, 70f, 0f), Triple(160f, 60f, 0.3f), Triple(150f, 120f, 0.55f), Triple(52f, 130f, 0.8f))
        val SPARKLES = listOf(Triple(34f, 72f, 1f), Triple(166f, 58f, 0.8f), Triple(170f, 146f, 0.8f), Triple(28f, 154f, 0.7f))
        val NOTE_COLORS = intArrayOf(0xFFFFC93C.toInt(), 0xFFFF7AB8.toInt(), 0xFF48DBFB.toInt(), 0xFF3DDC97.toInt())
        val FOAM = listOf(
            Triple(84f, 56f, 10f), Triple(110f, 52f, 12f), Triple(96f, 46f, 9f), Triple(126f, 62f, 9f), Triple(70f, 70f, 8f),
            Triple(64f, 120f, 9f), Triple(138f, 118f, 10f), Triple(90f, 146f, 11f), Triple(116f, 150f, 10f), Triple(102f, 132f, 8f),
            Triple(74f, 156f, 8f), Triple(130f, 160f, 8f), Triple(56f, 96f, 7f), Triple(146f, 92f, 7f), Triple(100f, 162f, 9f),
        )
        val DIRT = listOf(Triple(78f, 140f, 7f), Triple(124f, 148f, 6f), Triple(132f, 84f, 5f), Triple(70f, 96f, 5f), Triple(104f, 160f, 5f))
    }
}

/** Las mascotas, dibujadas con las mismas formas del diseño (viewBox de 200×200). */
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
        /** Arriba de la cabeza (gorros), el cuello (corbatín) y medio ancho de la cabeza (audífonos). */
        val headTop: Float = 46f,
        val neckY: Float = 132f,
        val headHalf: Float = 47f,
        /** Tamaño dentro del cuadro: Bulla es más alta (orejas y antena) y deja lugar para saltar. */
        val scale: Float = 1f,
        /** Partes que se mueven aparte (Bulla): orejas, brazos, antena y ojos grandes. */
        val rig: Rig? = null,
    ) {
        val scleraPaint = fill(0xFFF8F6FC.toInt())
        private val eyeCache = HashMap<Long, Paint>()

        fun eyePaint(x: Float, y: Float, rx: Float, ry: Float): Paint {
            val key = (x.toBits().toLong() shl 32) or (ry.toBits().toLong() and 0xFFFFFFFFL)
            return eyeCache.getOrPut(key) { gradient(RectF(x - rx, y - ry, x + rx, y + ry), EYE_TONE) }
        }
    }

    /**
     * Las piezas articuladas de Bulla. Cada una gira desde su punto (x, y): orejas y brazos.
     * La antena va de (antennaX, antennaY) a la bolita en (antennaTipX, antennaTipY).
     */
    class Rig(
        val earL: List<Shape>, val earLX: Float, val earLY: Float,
        val earR: List<Shape>, val earRX: Float, val earRY: Float,
        val armL: List<Shape>, val armLX: Float, val armLY: Float,
        val armR: List<Shape>, val armRX: Float, val armRY: Float,
        val antennaX: Float, val antennaY: Float, val antennaTipX: Float, val antennaTipY: Float,
        val stalk: Paint, val ball: List<Shape>, val ballR: Float,
        val sclera: Paint, val scleraEdge: Paint, val scleraRx: Float, val scleraRy: Float,
        val pupil: Float, val pupilColor: Int,
    )

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

    private val PUMPKIN = Tone(0xFFFFC078.toInt(), 0xFFFF8A1F.toInt(), 0xFFC2410C.toInt())
    private val WITCH = Tone(0xFF8F6AD8.toInt(), 0xFF4B2A8C.toInt(), 0xFF22113F.toInt())
    private val SANTA = Tone(0xFFFF8787.toInt(), 0xFFE03131.toInt(), 0xFF9C1C1C.toInt())

    /** Calabaza con cara iluminada, al lado izquierdo de la mascota. */
    val pumpkin = listOf(
        Shape(oval(34f, 166f, 10f, 13f), gradient(RectF(24f, 153f, 44f, 179f), PUMPKIN)),
        Shape(oval(50f, 166f, 10f, 13f), gradient(RectF(40f, 153f, 60f, 179f), PUMPKIN)),
        Shape(oval(42f, 166f, 10f, 14f), gradient(RectF(32f, 152f, 52f, 180f), PUMPKIN)),
        Shape(svg("M40 151 L44 151 L45 156 L39 156 Z"), fill(0xFF5C940D.toInt())),
        Shape(svg("M34 163 L38 158 L41 164 Z M50 163 L46 158 L43 164 Z"), fill(0xFFFFE066.toInt())),
        Shape(svg("M33 169 L37 172 L41 169 L45 172 L50 169 L46 176 L38 176 Z"), fill(0xFFFFE066.toInt())),
    )

    /** Sombrero de bruja, sobre la cabeza. */
    val witchHat = listOf(
        Shape(oval(100f, 50f, 34f, 7f), gradient(RectF(66f, 43f, 134f, 57f), WITCH)),
        Shape(svg("M78 50 Q96 34 104 4 Q108 30 124 50 Z"), gradient(RectF(78f, 4f, 124f, 50f), WITCH)),
        Shape(svg("M80 44 L122 44 L124 50 L78 50 Z"), fill(0xFFFF9F43.toInt())),
        Shape(svg("M97 43 L105 43 L105 51 L97 51 Z"), stroke(0xFFFFD43B.toInt(), 1.8f)),
    )

    /** Gorro de Navidad, sobre la cabeza. */
    val santaHat = listOf(
        Shape(svg("M74 48 Q90 16 128 20 Q118 32 126 48 Z"), gradient(RectF(74f, 16f, 128f, 48f), SANTA)),
        Shape(oval(100f, 50f, 32f, 7f), fill(0xFFFFFFFF.toInt())),
        Shape(circle(130f, 22f, 7f), fill(0xFFFFFFFF.toInt())),
    )

    /** Regalo, al lado izquierdo de la mascota. */
    val gift = listOf(
        Shape(svg("M28 154 L56 154 L56 180 L28 180 Z"), gradient(RectF(28f, 154f, 56f, 180f), SANTA)),
        Shape(svg("M39 154 L45 154 L45 180 L39 180 Z M28 164 L56 164 L56 169 L28 169 Z"), fill(0xFFFFD43B.toInt())),
        Shape(svg("M42 154 Q32 144 36 150 Q38 154 42 154 Q52 144 48 150 Q46 154 42 154 Z"), fill(0xFFFFD43B.toInt())),
    )

    val heart: Path = svg("M0 -4 C -6 -12, -18 -6, -12 4 L0 16 L12 4 C 18 -6, 6 -12, 0 -4 Z")
    val star: Path = svg("M0 -11 L3 -3 L11 0 L3 3 L0 11 L-3 3 L-11 0 L-3 -3 Z")

    fun build(species: String): Model = when (species) {
        "bulla" -> bulla()
        "perro" -> perro()
        "zorro" -> zorro()
        "rana" -> rana()
        "panda" -> panda()
        "pulpo" -> pulpo()
        "dragon" -> dragon()
        "unicornio" -> unicornio()
        "oso" -> oso()
        "conejo" -> conejo()
        "pollito" -> pollito()
        else -> gato()
    }

    // ---------------------------------------------------------------- Especies

    /**
     * Bulla, la mascota de Friend Game: una bolita celeste con orejas grandes para oír los
     * sonidos, una antena que brilla y ojos grandes que miran a todos lados (ver el lienzo
     * «Friend Game · Diseño 2.0»).
     */
    private fun bulla(): Model {
        val body = Tone(0xFFA8ECFF.toInt(), 0xFF3DBBF5.toInt(), 0xFF1A72C2.toInt())
        val belly = Tone(0xFFFFFFFF.toInt(), 0xFFEFFBFF.toInt(), 0xFFC4EBFF.toInt())
        val foot = Tone(0xFFFFE08A.toInt(), 0xFFFFB534.toInt(), 0xFFE07B00.toInt())
        val inner = 0xFFFF9EC7.toInt()
        val b = Builder()
        b.fill(oval(55f, 42f, 15f, 28f, -28f), body)
        b.fill(oval(56f, 45f, 8f, 18f, -28f), inner, 0.9f)
        val earL = b.take()
        b.fill(oval(145f, 42f, 15f, 28f, 28f), body)
        b.fill(oval(144f, 45f, 8f, 18f, 28f), inner, 0.9f)
        val earR = b.take()
        b.fill(oval(82f, 175f, 15f, 7.5f), foot)
        b.fill(oval(118f, 175f, 15f, 7.5f), foot)
        b.fill(svg("M100 46 C 138 46, 156 78, 157 114 C 158 152, 136 177, 100 177 C 64 177, 42 152, 43 114 C 44 78, 62 46, 100 46 Z"), body)
        b.fill(oval(100f, 150f, 33f, 24f), belly)
        b.fill(oval(76f, 64f, 17f, 8f, -28f), 0xFFFFFFFF.toInt(), 0.45f)
        val front = b.take()
        b.fill(oval(49f, 128f, 10f, 19f, 22f), body)
        b.stroke(oval(49f, 128f, 10f, 19f, 22f), 0xFF1A72C2.toInt(), 1.6f, 0.5f)
        val armL = b.take()
        b.fill(oval(151f, 128f, 10f, 19f, -22f), body)
        b.stroke(oval(151f, 128f, 10f, 19f, -22f), 0xFF1A72C2.toInt(), 1.6f, 0.5f)
        val armR = b.take()
        cheeks(b, 61f, 139f, 114f, 0.55f, 8.5f, 5f)
        val over = b.take()
        b.fill(circle(100f, 16f, 8f), Tone(0xFFFFF6B0.toInt(), 0xFFFFD43B.toInt(), 0xFFF08C00.toInt()))
        b.fill(circle(97.5f, 13.5f, 2.6f), 0xFFFFFFFF.toInt(), 0.85f)
        val ball = b.take()
        val ink = 0xFF0E2A4A.toInt()
        b.stroke(svg("M88 116 q12 11 24 0"), ink, 3.2f)
        val mouth = b.take()
        val rig = Rig(
            earL, 67f, 66f, earR, 133f, 66f,
            armL, 56f, 112f, armR, 144f, 112f,
            100f, 50f, 100f, 16f, stroke(0xFF1A72C2.toInt(), 3.8f), ball, 8f,
            fill(0xFFFBFDFF.toInt()), stroke(0xFF1A72C2.toInt(), 1.4f, 0.35f), 14f, 16f, 7.5f, 0xFF0E1E3A.toInt(),
        )
        return Model(
            emptyList(), 0f, 0f, 0f, 1f, emptyList(), front, over, mouth, emptyList(),
            Eyes(80f, 120f, 92f, 7f, 8f), 100f, 115f, ink, "🍩", 0xFFC98A4B.toInt(), spots(body.base),
            headTop = 50f, neckY = 134f, headHalf = 56f, scale = 0.88f, rig = rig,
        )
    }

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
            headTop = 52f, headHalf = 54f,
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
            headTop = 44f, neckY = 142f, headHalf = 52f,
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

    private fun oso(): Model {
        val body = Tone(0xFFC9A17A.toInt(), 0xFF9B6B43.toInt(), 0xFF5F3C21.toInt())
        val belly = Tone(0xFFF3E0C8.toInt(), 0xFFEBD1AC.toInt(), 0xFFD9B98A.toInt())
        val b = Builder()
        b.fill(circle(64f, 52f, 15f), body)
        b.fill(circle(136f, 52f, 15f), body)
        b.fill(circle(64f, 52f, 8f), belly)
        b.fill(circle(136f, 52f, 8f), belly)
        val back = b.take()
        standardBody(b, body, belly)
        b.fill(circle(100f, 88f, 45f), body)
        b.fill(oval(100f, 104f, 20f, 16f), belly)
        b.fill(oval(84f, 60f, 13f, 7f, -24f), 0xFFFFFFFF.toInt(), 0.3f)
        val front = b.take()
        cheeks(b, 68f, 132f, 108f, 0.4f)
        b.fill(oval(100f, 99f, 7f, 5f), 0xFF3A2414.toInt())
        b.fill(oval(97.5f, 97.5f, 2.4f, 1.4f), 0xFFFFFFFF.toInt(), 0.6f)
        val over = b.take()
        val ink = 0xFF3A2414.toInt()
        b.stroke(svg("M100 104 v4 M92 108 q8 7 16 0"), ink, 2.2f)
        return Model(
            emptyList(), 0f, 0f, 0f, 1f, back, front, over, b.take(), emptyList(),
            Eyes(84f, 116f, 90f, 6.5f, 8.5f), 100f, 108f, ink, "🍯", 0xFFFFC93C.toInt(), spots(body.base),
        )
    }

    private fun conejo(): Model {
        val body = Tone(0xFFFFFFFF.toInt(), 0xFFF3EEFA.toInt(), 0xFFC9C2DE.toInt())
        val belly = Tone(0xFFFFFFFF.toInt(), 0xFFFBF7FF.toInt(), 0xFFEDE6F7.toInt())
        val inner = 0xFFFFB3C6.toInt()
        val b = Builder()
        for (side in listOf(1f, -1f)) {
            b.fill(mirror(oval(78f, 44f, 11f, 34f, 8f), side), body)
            b.fill(mirror(oval(78f, 46f, 5.5f, 24f, 8f), side), inner, 0.8f)
        }
        val back = b.take()
        standardBody(b, body, belly)
        b.fill(circle(100f, 90f, 44f), body)
        b.fill(oval(84f, 62f, 13f, 7f, -24f), 0xFFFFFFFF.toInt(), 0.4f)
        val front = b.take()
        cheeks(b, 70f, 130f, 108f, 0.5f)
        b.fill(svg("M96 102 h8 l-4 4 z"), 0xFFFF6B8B.toInt())
        val over = b.take()
        val ink = 0xFF8A7BA8.toInt()
        b.stroke(svg("M100 106 v3 M92 110 q8 6 16 0"), ink, 2.2f)
        return Model(
            emptyList(), 0f, 0f, 0f, 1f, back, front, over, b.take(), emptyList(),
            Eyes(82f, 118f, 92f, 7f, 9f), 100f, 108f, ink, "🥕", 0xFFFF922B.toInt(), spots(0xFFE9D8F7.toInt()),
        )
    }

    private fun pollito(): Model {
        val body = Tone(0xFFFFF3B0.toInt(), 0xFFFFD43B.toInt(), 0xFFF08C00.toInt())
        val b = Builder()
        b.fill(oval(60f, 122f, 12f, 20f, 18f), body)
        b.fill(oval(140f, 122f, 12f, 20f, -18f), body)
        b.stroke(svg("M88 176 l-6 8 M88 176 v9 M88 176 l6 8 M112 176 l-6 8 M112 176 v9 M112 176 l6 8"), 0xFFF08C00.toInt(), 3f)
        val back = b.take()
        b.fill(oval(100f, 130f, 42f, 38f), body)
        b.fill(circle(100f, 86f, 42f), body)
        b.stroke(svg("M100 46 v-11 M92 48 l-6 -9 M108 48 l6 -9"), body, 4f)
        b.fill(oval(86f, 62f, 12f, 6f, -24f), 0xFFFFFFFF.toInt(), 0.35f)
        val front = b.take()
        cheeks(b, 70f, 130f, 102f, 0.5f)
        b.fill(svg("M94 94 L106 94 L100 102 Z"), 0xFFF76707.toInt())
        val over = b.take()
        val ink = 0xFF8A5000.toInt()
        return Model(
            emptyList(), 0f, 0f, 0f, 1f, back, front, over, b.take(), emptyList(),
            Eyes(86f, 114f, 84f, 6f, 8f), 100f, 96f, ink, "🌽", 0xFFFFD43B.toInt(), spots(body.base),
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
