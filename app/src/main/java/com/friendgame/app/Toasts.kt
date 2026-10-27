package com.friendgame.app

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Avisos cortos dentro de la app, con Bulla asomando (en vez del Toast del sistema): entran desde
 * arriba con un rebote y se van solos. No reciben toques: lo de abajo se puede tocar igual. No
 * los lee el árbol de accesibilidad (no tapan nada para TalkBack ni para las pruebas); el texto
 * se anuncia aparte.
 */
class BullaToast(private val root: FrameLayout) {
    private var box: LinearLayout? = null
    private var pet: PetView? = null
    private var text: TextView? = null
    private val hide = Runnable { dismiss() }

    /** Borde de arriba que ocupa el sistema (barra de estado). */
    var topInset = 0

    fun show(message: String) {
        val context = root.context
        val view = box ?: build().also { box = it }
        text?.text = message
        root.announceForAccessibility(message)
        view.removeCallbacks(hide)
        view.animate().cancel()
        if (view.parent == null) {
            root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
                setMargins(context.dp(16), topInset + context.dp(10), context.dp(16), 0)
            })
        } else {
            (view.layoutParams as? FrameLayout.LayoutParams)?.topMargin = topInset + context.dp(10)
            view.bringToFront()
        }
        val sad = message.startsWith("⏳") || message.startsWith("No ") || message.startsWith("Te faltan") || message.startsWith("Sin ")
        pet?.apply { if (sad) surprise() else cheer() }
        if (Anim.enabled()) {
            view.alpha = 0f
            view.translationY = -context.dp(70).toFloat()
            view.scaleX = 0.9f
            view.scaleY = 0.9f
            view.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(320).setInterpolator(OvershootInterpolator(1.6f)).start()
        } else {
            view.alpha = 1f
            view.translationY = 0f
        }
        view.postDelayed(hide, (2600L + message.length * 30L).coerceAtMost(5000L))
    }

    fun dismiss() {
        val view = box ?: return
        view.removeCallbacks(hide)
        if (view.parent == null) return
        if (!Anim.enabled()) {
            root.removeView(view)
            return
        }
        view.animate().alpha(0f).translationY(-root.context.dp(40).toFloat()).setDuration(200)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { root.removeView(view) }
            .start()
    }

    private fun build(): LinearLayout {
        val context = root.context
        return context.row().apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            background = context.lipped(Palette.TEXT, 20, Palette.SUB)
            setPadding(context.dp(10), context.dp(6), context.dp(16), context.dp(10))
            elevation = context.dp(6).toFloat()
            val face = PetView(context).apply {
                species = "bulla"
                stage = 3
            }
            pet = face
            addView(face, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
            val message = context.label("", 15f, Palette.INK, bold = true).apply { maxLines = 3 }
            text = message
            addView(message, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(8) })
        }
    }
}
