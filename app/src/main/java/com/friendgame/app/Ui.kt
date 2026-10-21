package com.friendgame.app

import android.content.Context
import android.content.res.AssetManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Colores del rediseño (ver el lienzo "Friend Game · Rediseño"). */
object Palette {
    val BG = 0xFF150C2C.toInt()
    val SURFACE = 0xFF231848.toInt()
    val SURFACE2 = 0xFF30235F.toInt()
    val NAV = 0xFF1D1340.toInt()
    val LINE = 0xFF2E2260.toInt()
    val OVERLAY = 0xFF0E0820.toInt()
    val TEXT = 0xFFFFFFFF.toInt()
    val SUB = 0xFFBDB2E6.toInt()
    val FAINT = 0xFF6F62A8.toInt()
    val INK = 0xFF150C2C.toInt()
    val ACCENT = 0xFFFF4FA3.toInt()
    val ACCENT_TEXT = 0xFFFF7AB8.toInt()
    val ACCENT_SOFT = 0xFF3A1E5C.toInt()
    val BADGE = 0xFF3A2D6E.toInt()
    val YELLOW = 0xFFFFC93C.toInt()
    val OK = 0xFF3DDC97.toInt()
    val DANGER = 0xFFC62F3B.toInt()
    val DANGER_TEXT = 0xFFFF8A93.toInt()
    val CUSTOM = 0xFFFFB86B.toInt()
    val DASHED = 0xFF5B4A9A.toInt()

    /** Para compatibilidad con pantallas sencillas (escáner). */
    val MUTED = SURFACE2

    private val AVATARS = intArrayOf(ACCENT, OK, 0xFF54A0FF.toInt(), 0xFFA29BFE.toInt(), YELLOW, 0xFFFF9F43.toInt())

    /** Color fijo para cada persona o grupo, sacado de su código. */
    fun avatar(key: String): Int = AVATARS[Math.floorMod(key.hashCode(), AVATARS.size)]
}

/**
 * Fredoka para títulos y Nunito para el resto (fuentes libres, OFL, en assets/fonts). Son
 * fuentes variables: cada peso se crea una vez y se guarda.
 */
object Fonts {
    private val cache = HashMap<String, Typeface>()

    fun display(context: Context, weight: Int = 700): Typeface = get(context.assets, "fonts/fredoka.ttf", weight)

    fun body(context: Context, weight: Int = 400): Typeface = get(context.assets, "fonts/nunito.ttf", weight)

    private fun get(assets: AssetManager, path: String, weight: Int): Typeface = synchronized(cache) {
        cache.getOrPut("$path:$weight") {
            try {
                Typeface.Builder(assets, path)
                    .setFontVariationSettings("'wght' $weight")
                    .setWeight(weight)
                    .build() ?: throw IllegalStateException("sin fuente")
            } catch (_: Exception) {
                if (weight >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
    }
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

fun Context.rounded(color: Int, radiusDp: Int = 18, strokeColor: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(2), strokeColor)
    }

fun Context.oval(color: Int, strokeColor: Int? = null): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
    if (strokeColor != null) setStroke(dp(2), strokeColor)
}

fun Context.clickable(background: Drawable, rippleColor: Int = 0x40FFFFFF): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor), background, null)

fun Context.lp(
    width: Int = MATCH,
    height: Int = WRAP,
    topDp: Int = 0,
    weight: Float = 0f,
): LinearLayout.LayoutParams = LinearLayout.LayoutParams(width, height, weight).apply { topMargin = dp(topDp) }

/** Texto normal (Nunito). */
fun Context.label(
    text: CharSequence,
    sizeSp: Float = 15f,
    color: Int = Palette.TEXT,
    bold: Boolean = false,
): TextView = TextView(this).apply {
    this.text = text
    textSize = sizeSp
    setTextColor(color)
    typeface = Fonts.body(context, if (bold) 800 else 400)
    setLineSpacing(0f, 1.1f)
}

/** Título (Fredoka). */
fun Context.title(
    text: CharSequence,
    sizeSp: Float = 20f,
    color: Int = Palette.TEXT,
    weight: Int = 700,
): TextView = TextView(this).apply {
    this.text = text
    textSize = sizeSp
    setTextColor(color)
    typeface = Fonts.display(context, weight)
    setLineSpacing(0f, 1.05f)
}

/** Encabezado pequeño en mayúsculas sobre una sección. */
fun Context.caps(text: String, color: Int = Palette.SUB): TextView =
    label(text.uppercase(), 12f, color, bold = true).apply { letterSpacing = 0.08f }

fun Context.icon(res: Int, color: Int, sizeDp: Int = 20): ImageView = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(color)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

/**
 * Botón con texto y, si se quiere, un ícono a la izquierda. [display] usa la fuente de títulos
 * (para los botones grandes).
 */
fun Context.button(
    text: CharSequence,
    color: Int = Palette.ACCENT,
    textColor: Int = Palette.INK,
    iconRes: Int? = null,
    heightDp: Int = 52,
    radiusDp: Int = 16,
    display: Boolean = false,
    sizeSp: Float = 15f,
    strokeColor: Int? = null,
    iconColor: Int = textColor,
    onClick: () -> Unit,
): LinearLayout = row().apply {
    gravity = Gravity.CENTER
    minimumHeight = dp(heightDp)
    setPadding(dp(14), dp(6), dp(14), dp(6))
    background = clickable(rounded(color, radiusDp, strokeColor))
    isClickable = true
    isFocusable = true
    stateListAnimator = Anim.press()
    if (iconRes != null) {
        addView(icon(iconRes, iconColor, 18), LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
    }
    val textView = if (display) title(text, sizeSp, textColor) else label(text, sizeSp, textColor, bold = true)
    addView(textView.apply {
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }, LinearLayout.LayoutParams(WRAP, WRAP))
    setOnClickListener { onClick() }
}

/** Botón sencillo (lo usa el escáner de QR). */
fun Context.pill(
    text: CharSequence,
    color: Int = Palette.ACCENT,
    textColor: Int = Palette.TEXT,
    onClick: () -> Unit,
): View = button(text, color, textColor, onClick = onClick)

/** Botón redondo solo con ícono; [description] es lo que lee el lector de pantalla. */
fun Context.iconButton(
    res: Int,
    description: String,
    color: Int = Palette.SURFACE2,
    tint: Int = Palette.TEXT,
    sizeDp: Int = 44,
    onClick: () -> Unit,
): View = android.widget.FrameLayout(this).apply {
    background = clickable(if (color == 0) rounded(0, 14) else oval(color))
    isClickable = true
    isFocusable = true
    stateListAnimator = Anim.press()
    contentDescription = description
    addView(icon(res, tint, 20), android.widget.FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    setOnClickListener { onClick() }
}

/** Círculo con la inicial de una persona. */
fun Context.avatar(name: String, color: Int, textColor: Int = Palette.INK, sizeDp: Int = 40): TextView =
    title(initial(name), sizeDp * 0.42f, textColor).apply {
        gravity = Gravity.CENTER
        background = oval(color)
        includeFontPadding = false
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

fun initial(name: String): String =
    name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

fun Context.row(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.column(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
}

/** Tarjeta: fondo redondeado con relleno. */
fun Context.card(color: Int = Palette.SURFACE, radiusDp: Int = 24, padDp: Int = 16): LinearLayout = column().apply {
    background = rounded(color, radiusDp)
    setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
}

fun Context.divider(color: Int = Palette.SURFACE2): View = View(this).apply {
    setBackgroundColor(color)
}

fun Context.space(widthDp: Int, heightDp: Int = 1): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(heightDp))
}

/** Interruptor con los colores de la app. */
fun Context.toggle(checked: Boolean, description: String, onChange: (Boolean) -> Unit): Switch = Switch(this).apply {
    isChecked = checked
    contentDescription = description
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    thumbTintList = ColorStateList(states, intArrayOf(Palette.ACCENT, Palette.SUB))
    trackTintList = ColorStateList(states, intArrayOf(Palette.ACCENT, Palette.FAINT))
    setOnCheckedChangeListener { _, value -> onChange(value) }
}

/** Mezcla [color] con blanco ([amount] > 0) o con negro ([amount] < 0). */
fun shade(color: Int, amount: Float): Int {
    val target = if (amount > 0) 255 else 0
    val k = kotlin.math.abs(amount)
    fun mix(c: Int) = (c + (target - c) * k).toInt().coerceIn(0, 255)
    return (color and 0xFF000000.toInt()) or (mix((color shr 16) and 0xFF) shl 16) or (mix((color shr 8) and 0xFF) shl 8) or mix(color and 0xFF)
}

/** Fondo de "caramelo": más claro arriba a la izquierda y más oscuro abajo, como las mascotas 3D. */
fun Context.candy(color: Int, radiusDp: Int = 22): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(shade(color, 0.28f), color, shade(color, -0.22f))).apply {
        cornerRadius = dp(radiusDp).toFloat()
    }

/** Botón de sonido: emoji grande y nombre debajo. */
fun Context.soundTile(
    emoji: String,
    text: String,
    color: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    swatch: Looks.Swatch? = null,
): View = column().apply {
    gravity = Gravity.CENTER
    minimumHeight = dp(88)
    setPadding(dp(6), dp(8), dp(6), dp(8))
    background = clickable(swatch?.drawable(context, 22) ?: candy(color, 22), 0x40150C2C)
    isClickable = true
    isFocusable = true
    elevation = dp(3).toFloat()
    stateListAnimator = Anim.press()
    addView(label(emoji, 28f).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, lp())
    addView(title(text, 15f, Palette.INK, 600).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }, lp(topDp = 4))
    setOnClickListener { onClick() }
    setOnLongClickListener {
        onLongClick()
        true
    }
}

/** Casilla con borde punteado para agregar algo (grabar o subir un audio). */
fun Context.addTile(iconRes: Int, text: String, onClick: () -> Unit): View = column().apply {
    gravity = Gravity.CENTER
    minimumHeight = dp(88)
    setPadding(dp(6), dp(8), dp(6), dp(8))
    val border = GradientDrawable().apply {
        cornerRadius = dp(22).toFloat()
        setColor(0)
        setStroke(dp(2), Palette.DASHED, dp(6).toFloat(), dp(5).toFloat())
    }
    background = clickable(border)
    isClickable = true
    isFocusable = true
    stateListAnimator = Anim.press()
    addView(icon(iconRes, Palette.ACCENT_TEXT, 26), LinearLayout.LayoutParams(dp(26), dp(26)))
    addView(title(text, 15f, Palette.TEXT, 600).apply { gravity = Gravity.CENTER }, lp(topDp = 6))
    setOnClickListener { onClick() }
}

/**
 * Barra que se llena sola desde [startedAt] (reloj elapsedRealtime) durante [durationMs]. Solo se
 * vuelve a pintar: cambiar el tamaño de una vista varias veces por segundo no deja que TalkBack ni
 * las pruebas lean la pantalla, porque nunca queda quieta.
 */
class TimeBar(
    context: Context,
    private val trackColor: Int,
    private val fillColor: Int,
    private val startedAt: Long,
    private val durationMs: Long,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = trackColor
        canvas.drawRoundRect(rect, radius, radius, paint)
        val fraction = ((SystemClock.elapsedRealtime() - startedAt).toFloat() / durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
        if (fraction > 0f) {
            rect.right = width * fraction
            paint.color = fillColor
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        // 10 veces por segundo basta para que se vea fluida y pesa mucho menos que cada cuadro.
        if (fraction < 1f) postInvalidateDelayed(100)
    }
}

/**
 * Texto que cambia solo cada segundo (cuentas regresivas). Se pinta sin cambiar el texto de una
 * vista, por la misma razón que [TimeBar]; TalkBack lee [description], que no cambia.
 */
class LiveText(
    context: Context,
    sizeSp: Float,
    color: Int,
    bold: Boolean,
    description: String,
    private val center: Boolean = false,
    private val text: () -> String,
) : View(context) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, context.resources.displayMetrics)
        typeface = Fonts.body(context, if (bold) 800 else 400)
    }
    private var shown = text()
    private var layout: StaticLayout? = null
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            postDelayed(this, 1000)
        }
    }

    init {
        contentDescription = description
    }

    private fun build(width: Int): StaticLayout =
        StaticLayout.Builder.obtain(shown, 0, shown.length, paint, width.coerceAtLeast(1))
            .setAlignment(if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.1f)
            .build()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        shown = text()
        val natural = paint.measureText(shown).toInt() + paddingLeft + paddingRight + 1
        val width = when (MeasureSpec.getMode(widthSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthSpec)
            MeasureSpec.AT_MOST -> minOf(natural, MeasureSpec.getSize(widthSpec))
            else -> natural
        }
        val built = build(width - paddingLeft - paddingRight)
        layout = built
        setMeasuredDimension(width, built.height + paddingTop + paddingBottom)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        layout?.draw(canvas)
        canvas.restore()
    }

    private fun refresh() {
        val value = text()
        if (value == shown || width == 0) return
        shown = value
        val built = build(width - paddingLeft - paddingRight)
        val resized = built.height != layout?.height
        layout = built
        if (resized) requestLayout()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(tick, 1000)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }
}
