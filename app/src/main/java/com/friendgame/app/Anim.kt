package com.friendgame.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animaciones cortas. Regla de la app: nada que se repita para siempre sobre vistas con texto
 * (TalkBack y las pruebas dejan de poder leer una pantalla que nunca queda quieta). Lo que dura
 * mucho se dibuja en una vista propia ([Confetti], [PulseRings], [PetView]).
 */
object Anim {
    /** Con "quitar animaciones" en Ajustes de Android, no se anima nada. */
    fun enabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    /** Se achica un poco al presionar y vuelve con rebote. */
    fun press(): StateListAnimator = StateListAnimator().apply {
        val down = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(null, View.SCALE_X, 0.94f),
                ObjectAnimator.ofFloat(null, View.SCALE_Y, 0.94f),
            )
            duration = 90
        }
        val up = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(null, View.SCALE_X, 1f),
                ObjectAnimator.ofFloat(null, View.SCALE_Y, 1f),
            )
            duration = 260
            interpolator = OvershootInterpolator(3f)
        }
        addState(intArrayOf(android.R.attr.state_pressed), down)
        addState(intArrayOf(), up)
    }

    /** Entrada de una pantalla nueva: cada bloque sube un poco y aparece, uno tras otro. */
    fun enter(views: List<View>) {
        if (!enabled()) return
        views.take(10).forEachIndexed { i, view ->
            view.alpha = 0f
            view.translationY = view.resources.displayMetrics.density * 14
            view.animate().alpha(1f).translationY(0f)
                .setStartDelay(i * 35L).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    /** Un "pop": crece y vuelve (al elegir algo, al ganar puntos). */
    fun pop(view: View, amount: Float = 1.12f) {
        if (!enabled()) return
        view.animate().cancel()
        view.scaleX = amount
        view.scaleY = amount
        view.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(4f)).start()
    }

    /** "No": se mueve de lado a lado (tocar un sonido mientras recarga). */
    fun shake(view: View) {
        if (!enabled()) return
        val d = view.resources.displayMetrics.density
        ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, -9 * d, 8 * d, -6 * d, 4 * d, 0f).setDuration(320).start()
    }

    /**
     * Un emoji (y un texto, como "+2") que sale volando desde [from] y se desvanece. Se agrega a
     * [layer] y se quita solo al terminar.
     */
    fun flyUp(layer: ViewGroup, from: View, emoji: String, extra: String? = null) {
        if (!enabled() || !from.isAttachedToWindow) return
        val context = layer.context
        val start = IntArray(2).also { from.getLocationInWindow(it) }
        val base = IntArray(2).also { layer.getLocationInWindow(it) }
        val text = context.label(if (extra != null) "$emoji $extra" else emoji, 26f, Palette.YELLOW, bold = true).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            includeFontPadding = false
        }
        layer.addView(text, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        text.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        text.x = (start[0] - base[0] + from.width / 2 - text.measuredWidth / 2).toFloat()
        text.y = (start[1] - base[1] + from.height / 3 - text.measuredHeight / 2).toFloat()
        val rise = context.dp(110).toFloat()
        text.animate().translationYBy(-rise).alpha(0f).scaleX(1.5f).scaleY(1.5f)
            .setDuration(800).setInterpolator(DecelerateInterpolator())
            .withEndAction { layer.removeView(text) }
            .start()
    }

    /** Confeti sobre toda la pantalla (logros, nivel nuevo, cofre). Se quita solo. */
    fun confetti(layer: ViewGroup) {
        if (!enabled()) return
        val view = Confetti(layer.context)
        layer.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}

/** Papelitos de colores que caen durante 1,8 s. Solo se pinta: no la ve el lector de pantalla. */
class Confetti(context: Context) : View(context) {
    private class Piece(val x: Float, val speed: Float, val drift: Float, val spin: Float, val size: Float, val color: Int, val delay: Float)

    private val colors = intArrayOf(Palette.ACCENT, Palette.YELLOW, Palette.OK, 0xFF54A0FF.toInt(), 0xFFA29BFE.toInt(), 0xFFFF9F43.toInt())
    private val pieces = List(70) {
        Piece(
            x = Random.nextFloat(), speed = 0.7f + Random.nextFloat() * 0.6f, drift = Random.nextFloat() * 2 - 1,
            spin = Random.nextFloat() * 720 - 360, size = 5f + Random.nextFloat() * 6f,
            color = colors[it % colors.size], delay = Random.nextFloat() * 0.25f,
        )
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val startedAt = SystemClock.uptimeMillis()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
    }

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - startedAt) / DURATION
        if (t >= 1f) {
            post { (parent as? ViewGroup)?.removeView(this) }
            return
        }
        val d = resources.displayMetrics.density
        pieces.forEach { p ->
            val local = ((t - p.delay) / (1f - p.delay)).coerceIn(0f, 1f)
            if (local <= 0f) return@forEach
            val y = -20 * d + local * p.speed * height * 1.1f
            val x = p.x * width + sin(local * 6f + p.drift * 3f) * 24 * d * p.drift
            paint.color = p.color
            paint.alpha = (255 * (1f - local * local)).toInt()
            canvas.save()
            canvas.rotate(p.spin * local, x, y)
            canvas.drawRect(x - p.size * d / 2, y - p.size * d / 4, x + p.size * d / 2, y + p.size * d / 4, paint)
            canvas.restore()
        }
        postInvalidateOnAnimation()
    }

    private companion object {
        const val DURATION = 1800f
    }
}

/**
 * Anillos que se expanden detrás del sonido que llegó, mientras suena. Se dibujan (no son vistas
 * que cambian de tamaño), así la pantalla con el botón Detener se puede leer y tocar siempre.
 */
class PulseRings(context: Context, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val startedAt = SystemClock.uptimeMillis()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val max = minOf(width, height) / 2f
        val d = resources.displayMetrics.density
        val t = (SystemClock.uptimeMillis() - startedAt) / 1600f
        paint.strokeWidth = 3 * d
        for (i in 0 until 3) {
            val phase = ((t + i / 3f) % 1f)
            paint.color = color
            paint.alpha = (180 * (1f - phase)).toInt()
            canvas.drawCircle(cx, cy, max * (0.55f + 0.45f * phase), paint)
        }
        // Puntitos que giran alrededor.
        paint.style = Paint.Style.FILL
        for (i in 0 until 6) {
            val angle = t * 2.2f + i * (Math.PI.toFloat() / 3)
            paint.alpha = 200
            canvas.drawCircle(cx + cos(angle) * max * 0.86f, cy + sin(angle) * max * 0.86f, 3.5f * d, paint)
        }
        paint.style = Paint.Style.STROKE
        if (Anim.enabled()) postInvalidateDelayed(40)
    }
}
