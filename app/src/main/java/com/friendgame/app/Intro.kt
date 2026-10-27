package com.friendgame.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Intro al abrir la app (unos 2 segundos, como una súper app): Bulla cae y aterriza aplastándose,
 * las letras de «Friend Game» caen una tras otra, salen ondas de sonido y emojis, y todo se abre
 * con un zoom hacia la app. Tocar la salta. Sin animaciones ([Anim.enabled]) no se muestra.
 */
class IntroView(context: Context, private val onDone: () -> Unit) : FrameLayout(context) {
    private var finished = false
    private val pet = PetView(context).apply {
        species = "bulla"
        stage = 3
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val letters = ArrayList<View>()
    private val tiles = ArrayList<View>()
    private val tagline = context.label("HAZLE SONAR EL CELULAR A TUS AMIGOS", 13f, Palette.YELLOW, bold = true).apply {
        letterSpacing = 0.08f
        gravity = Gravity.CENTER
    }
    private val rings = PulseRings(context, Palette.ACCENT)

    init {
        isClickable = true
        contentDescription = "Friend Game"
        background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(0xFF5B2BD6.toInt(), 0xFF2A1458.toInt(), Palette.BG)
            gradientRadius = context.resources.displayMetrics.heightPixels * 0.6f
            setGradientCenter(0.5f, 0.42f)
        }
        val d = context.dp(1)
        for ((emoji, color, gravity, angle) in listOf(
            Quad("📯", Palette.YELLOW, Gravity.TOP or Gravity.START, -12f),
            Quad("🦆", Palette.OK, Gravity.TOP or Gravity.END, 10f),
            Quad("🔔", 0xFF54A0FF.toInt(), Gravity.BOTTOM or Gravity.START, 8f),
            Quad("🎉", 0xFFA29BFE.toInt(), Gravity.BOTTOM or Gravity.END, -8f),
        )) {
            val tile = context.label(emoji, 26f).apply {
                this.gravity = Gravity.CENTER
                includeFontPadding = false
                background = context.lipped(color, 18)
                rotation = angle
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleX = 0f
                scaleY = 0f
            }
            tiles += tile
            addView(tile, LayoutParams(56 * d, 60 * d, gravity).apply {
                val top = gravity and Gravity.TOP == Gravity.TOP
                val start = gravity and Gravity.START == Gravity.START
                setMargins(34 * d, if (top) (if (start) 120 else 150) * d else 0, 34 * d, if (top) 0 else (if (start) 170 else 140) * d)
            })
        }
        addView(rings.apply { alpha = 0f }, LayoutParams(320 * d, 320 * d, Gravity.CENTER).apply { bottomMargin = 120 * d })
        val column = context.column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        column.addView(pet, LinearLayout.LayoutParams(220 * d, 220 * d))
        val word = context.row().apply { gravity = Gravity.CENTER }
        "Friend Game".forEach { ch ->
            val letter = context.title(ch.toString(), 46f).apply {
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                alpha = 0f
            }
            letters += letter
            word.addView(letter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        column.addView(word, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 4 * d })
        column.addView(tagline.apply { alpha = 0f }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 10 * d })
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setOnClickListener { finish() }
    }

    private data class Quad(val emoji: String, val color: Int, val gravity: Int, val angle: Float)

    /** Empieza la animación (llamar después de agregarla a la pantalla). */
    fun play() {
        val d = context.dp(1).toFloat()
        // Bulla cae desde arriba y se aplasta al tocar el piso.
        pet.translationY = -520f * d
        pet.pivotY = 220f * d
        pet.animate().translationY(0f).setStartDelay(80).setDuration(520).setInterpolator(AccelerateInterpolator(1.6f))
            .withEndAction {
                val squash = AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(pet, View.SCALE_Y, 0.78f, 1.06f, 1f),
                        ObjectAnimator.ofFloat(pet, View.SCALE_X, 1.18f, 0.96f, 1f),
                    )
                    duration = 420
                    interpolator = DecelerateInterpolator()
                }
                squash.start()
                pet.celebrate()
                rings.animate().alpha(1f).setDuration(300).start()
            }.start()
        letters.forEachIndexed { i, letter ->
            letter.translationY = -60f * d
            letter.animate().alpha(1f).translationY(0f).setStartDelay(520L + i * 45L).setDuration(380)
                .setInterpolator(OvershootInterpolator(3f)).start()
        }
        tiles.forEachIndexed { i, tile ->
            tile.animate().scaleX(1f).scaleY(1f).setStartDelay(700L + i * 90L).setDuration(420)
                .setInterpolator(OvershootInterpolator(2.6f)).start()
        }
        tagline.translationY = 12f * d
        tagline.animate().alpha(1f).translationY(0f).setStartDelay(1100).setDuration(320).start()
        postDelayed({ finish() }, 2300)
    }

    /** Sale con un zoom y deja ver la app. */
    fun finish() {
        if (finished) return
        finished = true
        isClickable = false
        animate().alpha(0f).scaleX(1.15f).scaleY(1.15f).setStartDelay(0).setDuration(380)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                (parent as? ViewGroup)?.removeView(this)
                onDone()
            }.start()
    }
}

/**
 * Bulla asomándose desde abajo, como Duo: aparece al mandar o escuchar un sonido, reacciona a su
 * categoría y, mientras suena, mueve la boca al ritmo. No recibe toques ni la lee TalkBack (es
 * decoración).
 */
class MascotPeek(context: Context) : FrameLayout(context) {
    val pet = PetView(context).apply {
        species = "bulla"
        stage = 3
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val hide = Runnable { slideOut() }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        visibility = GONE
        addView(pet, LayoutParams(context.dp(118), context.dp(118), Gravity.BOTTOM or Gravity.END))
    }

    /**
     * Aparece [seconds] segundos reaccionando a [vibe] (o feliz, si es null). [follow]: mueve la
     * boca con lo que suena ([Player.level]).
     */
    fun show(vibe: String?, seconds: Float, follow: Boolean) {
        removeCallbacks(hide)
        if (!Anim.enabled()) return
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            translationY = context.dp(160).toFloat()
            rotation = -8f
            animate().translationY(0f).rotation(0f).setDuration(420).setInterpolator(OvershootInterpolator(1.5f)).start()
        }
        pet.audioLevel = if (follow) ({ Player.level() }) else null
        pet.glow = vibe?.let { Vibes.byKey(it).color } ?: 0
        if (vibe != null) pet.react(vibe, seconds) else pet.celebrate()
        postDelayed(hide, (seconds * 1000).toLong() + 300)
    }

    fun slideOut() {
        removeCallbacks(hide)
        if (visibility != VISIBLE) return
        animate().translationY(context.dp(160).toFloat()).rotation(8f).setDuration(260).setInterpolator(AccelerateInterpolator())
            .withEndAction {
                visibility = GONE
                pet.audioLevel = null
                pet.glow = 0
                pet.calm()
            }.start()
    }
}
