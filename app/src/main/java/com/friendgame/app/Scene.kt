package com.friendgame.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.text.TextPaint
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Fondo de la app, dibujado (degradados y formas, sin imágenes). Muestra el que elegiste
 * ([Looks.background]) y, cuando suena, mandas o te llega un sonido, el de su categoría ([react]):
 * se abre en círculo desde donde tocaste, late con el volumen ([Player.level]) y al terminar
 * ([release]) vuelve al tuyo con un fundido.
 *
 * Fluido en un celular de gama media: 30 cuadros por segundo, nada se crea al dibujar, y solo
 * se mueve si el fondo es animado o hay una reacción. Sin animaciones ([Anim.enabled]) queda
 * quieto y los cambios son instantáneos.
 */
class SceneView(context: Context) : View(context) {

    /** Fondo de base: una clave de [Looks.BACKGROUNDS]. */
    var base: String = "default"
        set(value) {
            if (field != value) {
                field = value
                baseShader = null
                invalidate()
            }
        }

    /** Volumen de lo que suena (0 a 1); por defecto, [Player.level]. */
    var level: () -> Float = { Player.level() }

    private var overlay: String? = null
    private var overlayStart = 0L
    private var releaseStart = 0L
    private var originX = 0.5f
    private var originY = 0.5f
    private var baseShader: Shader? = null
    private var overlayShader: Shader? = null
    private var overlayShaderKey = ""
    private var shaderH = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val clip = Path()
    private val glows = HashMap<Int, Paint>()
    private val seed = (SystemClock.uptimeMillis() % 1000) / 100f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Lo que se ve ahora: la reacción, o el fondo de base. */
    val showing: String get() = overlay ?: base

    /**
     * Cambia al fondo de la categoría [vibe], abriéndose desde ([x], [y]) en píxeles de esta vista
     * (o desde el centro). Se queda hasta [release].
     */
    fun react(vibe: String, x: Float = width / 2f, y: Float = height / 2f) {
        if (overlay == vibe && releaseStart == 0L) return
        overlay = vibe
        overlayStart = SystemClock.uptimeMillis()
        releaseStart = 0L
        originX = if (width > 0) x / width else 0.5f
        originY = if (height > 0) y / height else 0.5f
        invalidate()
    }

    /** Vuelve al fondo de base con un fundido. */
    fun release() {
        if (overlay == null || releaseStart != 0L) return
        releaseStart = SystemClock.uptimeMillis()
        invalidate()
    }

    /** Vuelve al de base al instante. */
    fun clear() {
        overlay = null
        releaseStart = 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (shaderH != height) {
            shaderH = height
            baseShader = null
            overlayShader = null
        }
        val live = Anim.enabled()
        val now = SystemClock.uptimeMillis()
        val t = if (live) now / 1000f + seed else 2f
        val lvl = if (live) level().coerceIn(0f, 1f) else 0f

        drawScene(canvas, base, true, t, 0f, w, h)
        val over = overlay
        if (over != null) {
            val reveal = if (live) ((now - overlayStart) / REVEAL_MS).coerceIn(0f, 1f) else 1f
            val fade = if (releaseStart == 0L) 1f else if (live) (1f - (now - releaseStart) / FADE_MS).coerceIn(0f, 1f) else 0f
            if (fade <= 0f) {
                overlay = null
                releaseStart = 0L
            } else {
                val ox = originX * w
                val oy = originY * h
                val far = max(max(hypot(ox, oy), hypot(w - ox, oy)), max(hypot(ox, h - oy), hypot(w - ox, h - oy)))
                val eased = 1f - (1f - reveal) * (1f - reveal) * (1f - reveal)
                val save = if (fade < 1f) canvas.saveLayerAlpha(0f, 0f, w, h, (255 * fade).toInt()) else canvas.save()
                if (reveal < 1f) {
                    clip.reset()
                    clip.addCircle(ox, oy, far * eased, Path.Direction.CW)
                    canvas.clipPath(clip)
                }
                drawScene(canvas, over, false, t, lvl, w, h)
                canvas.restoreToCount(save)
                // Anillo que marca el borde mientras se abre.
                if (reveal < 1f && live) {
                    stroke.shader = null
                    stroke.color = Vibes.byKey(over).color
                    stroke.alpha = (200 * (1f - reveal)).toInt()
                    stroke.strokeWidth = context.dp(6).toFloat()
                    canvas.drawCircle(ox, oy, far * eased, stroke)
                    stroke.alpha = 255
                }
            }
        }
        if (live && (overlay != null || animatedBase(base))) postInvalidateDelayed(FRAME_MS)
    }

    private fun animatedBase(key: String) = Looks.background(key).scene != null

    /** Dibuja un fondo: el degradado y, si es de una categoría, sus formas animadas. */
    private fun drawScene(canvas: Canvas, key: String, isBase: Boolean, t: Float, lvl: Float, w: Float, h: Float) {
        // [key] es un fondo (base) o una categoría (reacción).
        val bg = if (isBase) Looks.background(key) else Looks.sceneFor(key)
        val scene = bg.scene
        val colors = bg.colors
        val shader = if (isBase) baseShader ?: gradient(colors, h).also { baseShader = it }
        else (if (overlayShaderKey == key) overlayShader else null) ?: gradient(colors, h).also {
            overlayShader = it
            overlayShaderKey = key
        }
        paint.shader = shader
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        when (scene) {
            Vibes.RISA.key -> risa(canvas, t, lvl, w, h)
            Vibes.FIESTA.key -> fiesta(canvas, t, lvl, w, h)
            Vibes.SUSTO.key -> susto(canvas, t, lvl, w, h)
            Vibes.AMOR.key -> amor(canvas, t, lvl, w, h)
            Vibes.TROLL.key -> troll(canvas, t, lvl, w, h)
            Vibes.VOZ.key -> voz(canvas, t, lvl, w, h)
        }
    }

    private fun gradient(colors: IntArray, h: Float): Shader =
        if (colors.size == 1) LinearGradient(0f, 0f, 0f, h, colors[0], colors[0], Shader.TileMode.CLAMP)
        else LinearGradient(0f, 0f, 0f, h, colors, null, Shader.TileMode.CLAMP)

    private fun glow(color: Int): Paint = glows.getOrPut(color) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(0f, 0f, 1f, intArrayOf(color, color and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        }
    }

    /** Una mancha de luz suave de [color] (con su alfa) en (x, y) de radio [r]. */
    private fun drawGlow(canvas: Canvas, color: Int, x: Float, y: Float, r: Float) {
        if (r <= 0f) return
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(r, r)
        canvas.drawCircle(0f, 0f, 1f, glow(color))
        canvas.restore()
    }

    private val d: Float get() = resources.displayMetrics.density

    // ---------------------------------------------------------------- Escenas

    /** Risa: rayos de sol que giran y «JA» que rebotan. */
    private fun risa(canvas: Canvas, t: Float, lvl: Float, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h * 0.38f
        val r = hypot(w, h)
        canvas.save()
        canvas.rotate(t * 8f, cx, cy)
        paint.color = 0x1FFFC93C
        val rays = 14
        for (i in 0 until rays) {
            val a0 = (i * 2 * PI / rays).toFloat()
            val a1 = a0 + (PI / rays).toFloat()
            path.reset()
            path.moveTo(cx, cy)
            path.lineTo(cx + cos(a0) * r, cy + sin(a0) * r)
            path.lineTo(cx + cos(a1) * r, cy + sin(a1) * r)
            path.close()
            canvas.drawPath(path, paint)
        }
        canvas.restore()
        drawGlow(canvas, 0x88FFE08A.toInt(), cx, cy, (110f + 70f * lvl) * d)
        text.typeface = Fonts.display(context, 700)
        for (i in 0 until 5) {
            val x = w * (0.12f + 0.19f * i)
            val y = h * (0.18f + 0.13f * ((i * 37) % 5)) + sin(t * 3f + i) * 10f * d
            val bounce = 1f + 0.25f * lvl + 0.08f * sin(t * 6f + i * 1.3f)
            text.textSize = (18f + (i % 3) * 6f) * d * bounce
            text.color = if (i % 2 == 0) 0x66FFC93C else 0x55FF7AB8
            canvas.save()
            canvas.rotate(if (i % 2 == 0) -14f else 12f, x, y)
            canvas.drawText(if (i % 3 == 1) "JA JA" else "JA", x, y, text)
            canvas.restore()
        }
    }

    /** Fiesta: luces de disco que barren, bola de espejos y barras al ritmo. */
    private fun fiesta(canvas: Canvas, t: Float, lvl: Float, w: Float, h: Float) {
        val cx = w / 2f
        val top = -10f * d
        val beams = intArrayOf(0x40FF4FA3, 0x3848DBFB, 0x38FFC93C, 0x35B44CFF)
        for (i in beams.indices) {
            val angle = 90f + sin(t * (0.7f + i * 0.23f) + i * 1.7f) * 45f
            canvas.save()
            canvas.rotate(angle - 90f, cx, top)
            path.reset()
            path.moveTo(cx, top)
            path.lineTo(cx - (70f + 50f * lvl) * d, h * 1.2f)
            path.lineTo(cx + (70f + 50f * lvl) * d, h * 1.2f)
            path.close()
            paint.color = beams[i]
            canvas.drawPath(path, paint)
            canvas.restore()
        }
        drawGlow(canvas, 0xAAFFFFFF.toInt(), cx, 34f * d, (26f + 10f * lvl) * d)
        paint.color = 0xFFA29BFE.toInt()
        canvas.drawCircle(cx, 34f * d, 16f * d, paint)
        paint.color = 0xFFFFFFFF.toInt()
        for (i in 0 until 6) {
            val a = t * 2f + i
            paint.alpha = (120 + 120 * abs(sin(a))).toInt()
            canvas.drawCircle(cx + cos(a) * 9f * d, 34f * d + sin(a * 1.3f) * 9f * d, 2.2f * d, paint)
        }
        paint.alpha = 255
        // Papel picado que cae.
        for (i in 0 until 24) {
            val k = ((t * (0.08f + (i % 5) * 0.015f)) + i * 0.137f) % 1f
            val x = w * ((i * 0.618f) % 1f) + sin(t * 2f + i) * 12f * d
            val y = h * k
            paint.color = CONFETTI[i % CONFETTI.size]
            paint.alpha = 150
            canvas.save()
            canvas.rotate(t * 90f + i * 40f, x, y)
            canvas.drawRect(x - 4f * d, y - 2f * d, x + 4f * d, y + 2f * d, paint)
            canvas.restore()
        }
        paint.alpha = 255
        // Barras abajo, al ritmo.
        val bars = 14
        val barW = w / bars
        for (i in 0 until bars) {
            val k = 0.25f + 0.75f * abs(sin(t * (3f + i * 0.37f) + i)) * (0.35f + 0.65f * max(lvl, 0.3f))
            val bh = k * 90f * d
            paint.color = CONFETTI[i % CONFETTI.size]
            paint.alpha = 110
            canvas.drawRoundRect(i * barW + 3f * d, h - bh, (i + 1) * barW - 3f * d, h + 8f * d, 5f * d, 5f * d, paint)
        }
        paint.alpha = 255
    }

    /** Susto: luz de sirena que gira, relámpagos y un temblor con los golpes fuertes. */
    private fun susto(canvas: Canvas, t: Float, lvl: Float, w: Float, h: Float) {
        val shake = if (lvl > 0.65f) sin(t * 70f) * 4f * d * (lvl - 0.6f) * 2.5f else 0f
        canvas.save()
        canvas.translate(shake, 0f)
        val cx = w / 2f
        val cy = h * 0.32f
        val r = hypot(w, h)
        canvas.save()
        canvas.rotate(t * 160f, cx, cy)
        path.reset()
        path.moveTo(cx, cy)
        path.lineTo(cx + r, cy - r * 0.35f)
        path.lineTo(cx + r, cy + r * 0.35f)
        path.close()
        paint.color = 0x30FF4D4D
        canvas.drawPath(path, paint)
        canvas.restore()
        drawGlow(canvas, 0x99FF2D2D.toInt(), cx, cy, (90f + 80f * lvl) * d)
        // Relámpago cada ~2,4 s en un lugar distinto.
        val cycle = (t / 2.4f).toInt()
        val k = (t % 2.4f) / 2.4f
        if (k < 0.12f) {
            val flash = 1f - k / 0.12f
            paint.color = 0xFFFFFFFF.toInt()
            paint.alpha = (70 * flash).toInt()
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.alpha = 255
            val x0 = w * (0.15f + 0.7f * ((cycle * 0.37f) % 1f))
            stroke.shader = null
            stroke.color = 0xFFFFE08A.toInt()
            stroke.alpha = (255 * flash).toInt()
            stroke.strokeWidth = 4f * d
            stroke.strokeJoin = Paint.Join.ROUND
            path.reset()
            path.moveTo(x0, 0f)
            var y = 0f
            var x = x0
            var i = 0
            while (y < h * 0.55f) {
                y += 38f * d
                x += (if (i % 2 == 0) -22f else 26f) * d
                path.lineTo(x, y)
                i++
            }
            canvas.drawPath(path, stroke)
            stroke.alpha = 255
        }
        canvas.restore()
    }

    /** Amor: corazones que suben, luces suaves y un latido con el volumen. */
    private fun amor(canvas: Canvas, t: Float, lvl: Float, w: Float, h: Float) {
        for (i in 0 until 5) {
            val x = w * ((i * 0.31f + 0.1f) % 1f)
            val y = h * ((i * 0.47f + 0.15f) % 1f)
            drawGlow(canvas, 0x40FF7AB8, x + sin(t * 0.5f + i) * 20f * d, y, (60f + 20f * i) * d)
        }
        val beat = 1f + 0.18f * lvl
        for (i in 0 until 16) {
            val speed = 0.05f + (i % 4) * 0.012f
            val k = ((t * speed) + i * 0.173f) % 1f
            val x = w * ((i * 0.618f + 0.05f) % 1f) + sin(t * 1.5f + i) * 16f * d
            val y = h * (1.05f - 1.15f * k)
            val s = (0.5f + (i % 3) * 0.3f) * d * beat
            paint.color = HEARTS[i % HEARTS.size]
            paint.alpha = (200 * (if (k < 0.1f) k / 0.1f else if (k > 0.85f) (1f - k) / 0.15f else 1f)).toInt()
            canvas.save()
            canvas.translate(x, y)
            canvas.scale(s, s)
            canvas.drawPath(PetArt.heart, paint)
            canvas.restore()
        }
        paint.alpha = 255
    }

    /** Troll: espiral hipnótica que gira y estrellitas. */
    private fun troll(canvas: Canvas, t: Float, lvl: Float, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h * 0.42f
        canvas.save()
        canvas.rotate(t * (40f + 80f * lvl), cx, cy)
        stroke.shader = null
        stroke.strokeWidth = 16f * d
        stroke.strokeJoin = Paint.Join.ROUND
        for (arm in 0 until 2) {
            path.reset()
            var a = arm * PI.toFloat()
            var r = 4f * d
            path.moveTo(cx + cos(a) * r, cy + sin(a) * r)
            val maxR = hypot(w, h) * 0.75f
            while (r < maxR) {
                a += 0.18f
                r += 2.6f * d
                path.lineTo(cx + cos(a) * r, cy + sin(a) * r)
            }
            stroke.color = if (arm == 0) 0x388CE33A else 0x30B44CFF
            canvas.drawPath(path, stroke)
        }
        canvas.restore()
        drawGlow(canvas, 0x668CE33A, cx, cy, (70f + 60f * lvl) * d)
        for (i in 0 until 9) {
            val x = w * ((i * 0.381f + 0.07f) % 1f)
            val y = h * ((i * 0.613f + 0.11f) % 1f)
            val k = sin(t * 2.6f + i * 1.9f) * 0.5f + 0.5f
            paint.color = if (i % 2 == 0) 0xFF8CE33A.toInt() else 0xFFD59BFF.toInt()
            paint.alpha = (60 + 160 * k).toInt()
            canvas.save()
            canvas.translate(x, y)
            val s = (0.5f + 0.5f * k) * d
            canvas.scale(s, s)
            canvas.drawPath(PetArt.star, paint)
            canvas.restore()
        }
        paint.alpha = 255
    }

    /** Voz: ondas que siguen la voz y globos de diálogo. */
    private fun voz(canvas: Canvas, t: Float, lvl: Float, w: Float, h: Float) {
        val cy = h * 0.45f
        val colors = intArrayOf(0x9948DBFB.toInt(), 0x667FE6FF, 0x44FFFFFF)
        for (k in 0 until 3) {
            stroke.shader = null
            stroke.color = colors[k]
            stroke.strokeWidth = (4f - k) * d
            stroke.strokeJoin = Paint.Join.ROUND
            val amp = (14f + 70f * lvl) * d * (1f - k * 0.25f)
            path.reset()
            var x = 0f
            val step = 8f * d
            while (x <= w + step) {
                val y = cy + sin(x / w * 6f * PI.toFloat() + t * (3f + k) + k) * amp * (0.6f + 0.4f * sin(x / w * PI.toFloat()))
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += step
            }
            canvas.drawPath(path, stroke)
        }
        drawGlow(canvas, 0x5548DBFB, w / 2f, cy, (120f + 60f * lvl) * d)
        for (i in 0 until 3) {
            val k = ((t * 0.12f) + i / 3f) % 1f
            val x = w * (0.2f + 0.3f * i)
            val y = h * (0.85f - 0.6f * k)
            paint.color = 0xFF48DBFB.toInt()
            paint.alpha = (90 * (if (k < 0.15f) k / 0.15f else 1f - (k - 0.15f) / 0.85f)).toInt()
            canvas.drawRoundRect(x - 26f * d, y - 14f * d, x + 26f * d, y + 14f * d, 14f * d, 14f * d, paint)
            path.reset()
            path.moveTo(x - 8f * d, y + 12f * d)
            path.lineTo(x - 14f * d, y + 22f * d)
            path.lineTo(x + 2f * d, y + 13f * d)
            path.close()
            canvas.drawPath(path, paint)
        }
        paint.alpha = 255
    }

    private companion object {
        const val REVEAL_MS = 450f
        const val FADE_MS = 650f
        const val FRAME_MS = 33L
        val CONFETTI = intArrayOf(0xFFFF4FA3.toInt(), 0xFFFFC93C.toInt(), 0xFF48DBFB.toInt(), 0xFF3DDC97.toInt(), 0xFFB44CFF.toInt())
        val HEARTS = intArrayOf(0xFFFF7AB8.toInt(), 0xFFFF4FA3.toInt(), 0xFFFFB3D6.toInt())
    }
}
