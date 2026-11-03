package com.friendgame.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Hoja inferior del diseño 2.0: sube desde abajo, esquinas redondas arriba, un asa, título y
 * contenido (opciones con ícono de color, campos o lo que haga falta). Reemplaza los diálogos
 * básicos de Android, que se veían fuera de lugar en la app.
 *
 * Es un [Dialog], así atrás, tocar afuera, el teclado y TalkBack funcionan solos.
 *
 *     Sheet(this).title("Bocina", "Susto · integrado").header(tarjeta)
 *         .option("▶️", "Escucharlo", Palette.OK) { … }
 *         .option("🗑️", "Borrar", Palette.DANGER, danger = true) { … }
 *         .show()
 */
class Sheet(private val activity: Activity) {
    private val dialog = Dialog(activity)
    private val card = activity.column()
    private val body = activity.column()
    private val scroll = MaxHeightScroll(activity)
    private val footer = activity.column()
    private var onDismiss: (() -> Unit)? = null

    init {
        val handle = View(activity).apply { background = activity.rounded(Palette.LIP, 999) }
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.background = GradientDrawable().apply {
            setColor(Palette.BG_HIGH)
            val r = activity.dp(28).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        card.addView(handle, LinearLayout.LayoutParams(activity.dp(44), activity.dp(5)).apply { topMargin = activity.dp(10) })
        body.setPadding(activity.dp(20), activity.dp(8), activity.dp(20), activity.dp(8))
        scroll.isFillViewport = false
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(body, ViewGroup.LayoutParams(MATCH, WRAP))
        card.addView(scroll, LinearLayout.LayoutParams(MATCH, WRAP))
        footer.setPadding(activity.dp(20), 0, activity.dp(20), activity.dp(14))
        card.addView(footer, LinearLayout.LayoutParams(MATCH, WRAP))
        // Edge-to-edge: que la última opción no quede bajo la barra de gestos.
        card.setOnApplyWindowInsetsListener { v, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.ime()).bottom
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            }
            v.setPadding(0, 0, 0, bottom)
            insets
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(card)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { onDismiss?.invoke() }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.62f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            if (Build.VERSION.SDK_INT >= 30) setDecorFitsSystemWindows(false)
        }
    }

    /** La ventana de la hoja (para las capturas de prueba). */
    val window: Window? get() = dialog.window

    val isShowing: Boolean get() = dialog.isShowing

    /** Título grande (Fredoka) y, si se quiere, una línea de explicación. */
    fun title(text: CharSequence, subtitle: CharSequence? = null): Sheet {
        val box = activity.column()
        box.addView(activity.title(text, 22f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, activity.lp())
        if (!subtitle.isNullOrEmpty()) box.addView(activity.label(subtitle, 14f, Palette.SUB), activity.lp(topDp = 2))
        body.addView(box, activity.lp(topDp = 6))
        return this
    }

    /** Una vista arriba de las opciones (por ejemplo, la tarjeta del sonido). */
    fun header(view: View, topDp: Int = 14): Sheet {
        body.addView(view, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = activity.dp(topDp) })
        return this
    }

    /** Agrega cualquier vista al cuerpo (campos, filas de colores…). */
    fun add(view: View, topDp: Int = 12): Sheet = header(view, topDp)

    /** Un subtítulo en mayúsculas para separar grupos de opciones. */
    fun section(text: String): Sheet = add(activity.caps(text), 18)

    /**
     * Una opción: círculo de color con un emoji, el texto y, si se quiere, una explicación corta.
     * [danger]: en rojo (borrar, bloquear). Al tocarla se cierra la hoja y corre [onClick].
     */
    fun option(
        emoji: String,
        text: CharSequence,
        color: Int = Palette.SURFACE2,
        subtitle: CharSequence? = null,
        danger: Boolean = false,
        keepOpen: Boolean = false,
        onClick: () -> Unit,
    ): Sheet {
        val row = Chunky(activity, activity.dp(3)).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = activity.dp(60)
            setPadding(activity.dp(12), activity.dp(8), activity.dp(14), activity.dp(11))
            background = activity.chunkyBackground(Palette.SURFACE, 18, lipDp = 3)
            isClickable = true
            isFocusable = true
            contentDescription = text.toString() + (subtitle?.let { ". $it" } ?: "")
            setOnClickListener {
                if (!keepOpen) dialog.dismiss()
                onClick()
            }
        }
        val badge = FrameLayout(activity).apply { background = activity.candy(if (danger) Palette.DANGER else color, 14) }
        badge.addView(activity.label(emoji, 20f).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        row.addView(badge, LinearLayout.LayoutParams(activity.dp(42), activity.dp(42)))
        val texts = activity.column()
        texts.addView(activity.label(text, 16f, if (danger) Palette.DANGER_TEXT else Palette.TEXT, bold = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, activity.lp())
        if (!subtitle.isNullOrEmpty()) texts.addView(activity.label(subtitle, 13f, Palette.SUB).apply { maxLines = 2 }, activity.lp(topDp = 1))
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = activity.dp(12) })
        row.addView(activity.icon(R.drawable.ic_chevron, Palette.FAINT, 16), LinearLayout.LayoutParams(activity.dp(16), activity.dp(16)))
        body.addView(row, activity.lp(topDp = 8))
        return this
    }

    /** Botones fijos abajo (fuera del scroll): el principal a la derecha, el otro a la izquierda. */
    fun buttons(
        primary: String,
        primaryColor: Int = Palette.ACCENT,
        secondary: String? = "Cancelar",
        onSecondary: () -> Unit = {},
        closeOnPrimary: Boolean = true,
        onPrimary: () -> Unit,
    ): Sheet {
        val line = activity.row()
        if (secondary != null) {
            line.addView(activity.button(secondary, Palette.SURFACE, Palette.TEXT, heightDp = 52) {
                dialog.dismiss()
                onSecondary()
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            line.addView(activity.space(10))
        }
        val ink = if (primaryColor == Palette.DANGER || primaryColor == Palette.SURFACE || primaryColor == Palette.SURFACE2) Palette.TEXT else Palette.INK
        line.addView(activity.button(primary, primaryColor, ink, heightDp = 52, display = true, sizeSp = 17f) {
            if (closeOnPrimary) dialog.dismiss()
            onPrimary()
        }, LinearLayout.LayoutParams(0, WRAP, if (secondary != null) 1.4f else 1f))
        footer.addView(line, activity.lp(topDp = 10))
        return this
    }

    /** Un solo botón para cerrar (avisos). */
    fun closeButton(text: String = "Entendido"): Sheet {
        footer.addView(activity.button(text, Palette.SURFACE2, Palette.TEXT, heightDp = 50) { dialog.dismiss() }, activity.lp(topDp = 10))
        return this
    }

    /** No se cierra tocando afuera ni con atrás (por ejemplo, mientras graba). */
    fun notCancelable(): Sheet {
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        return this
    }

    fun onDismiss(action: () -> Unit): Sheet {
        onDismiss = action
        return this
    }

    fun show(): Sheet {
        if (activity.isFinishing || activity.isDestroyed) return this
        // Un poco de aire al final del contenido.
        body.addView(View(activity), LinearLayout.LayoutParams(MATCH, activity.dp(4)))
        dialog.show()
        return this
    }

    fun dismiss() = dialog.dismiss()
}

/** Scroll que crece con su contenido hasta el 82 % de la pantalla (para hojas con muchas opciones). */
class MaxHeightScroll(context: android.content.Context) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val max = (resources.displayMetrics.heightPixels * 0.82f).toInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
    }
}

/** Texto plano para la cabecera de una hoja (por ejemplo, la explicación de un aviso). */
fun Activity.sheetText(text: CharSequence, color: Int = Palette.TEXT, sizeSp: Float = 15f): TextView =
    label(text, sizeSp, color).apply { setLineSpacing(0f, 1.15f) }
