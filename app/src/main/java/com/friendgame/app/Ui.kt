package com.friendgame.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

object Palette {
    val BG = 0xFF1B1035.toInt()
    val CARD = 0xFF2A1B54.toInt()
    val CARD_HIGHLIGHT = 0xFF3B2A72.toInt()
    val TEXT = 0xFFFFFFFF.toInt()
    val SUB = 0xFFC9BFF0.toInt()
    val INK = 0xFF1B1035.toInt()
    val ACCENT = 0xFFFF4FA3.toInt()
    val YELLOW = 0xFFFFC93C.toInt()
    val OK = 0xFF2EC27E.toInt()
    val DANGER = 0xFFE5484D.toInt()
    val MUTED = 0xFF4A3B7A.toInt()
    val CUSTOM = 0xFFFFB86B.toInt()
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

fun Context.clickable(background: Drawable): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), background, null)

fun Context.lp(
    width: Int = MATCH,
    height: Int = WRAP,
    topDp: Int = 0,
    weight: Float = 0f,
): LinearLayout.LayoutParams = LinearLayout.LayoutParams(width, height, weight).apply { topMargin = dp(topDp) }

fun Context.label(
    text: CharSequence,
    sizeSp: Float = 15f,
    color: Int = Palette.TEXT,
    bold: Boolean = false,
): TextView = TextView(this).apply {
    this.text = text
    textSize = sizeSp
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
    setLineSpacing(0f, 1.1f)
}

fun Context.pill(
    text: CharSequence,
    color: Int = Palette.ACCENT,
    textColor: Int = Palette.TEXT,
    onClick: () -> Unit,
): TextView = label(text, 15f, textColor, bold = true).apply {
    gravity = Gravity.CENTER
    setPadding(dp(14), dp(12), dp(14), dp(12))
    background = clickable(rounded(color, 24))
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
}

fun Context.row(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.column(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
}

/** Botón de sonido: emoji grande y nombre debajo. */
fun Context.soundTile(
    emoji: String,
    text: String,
    color: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
): View = column().apply {
    gravity = Gravity.CENTER
    minimumHeight = dp(88)
    setPadding(dp(6), dp(10), dp(6), dp(10))
    background = clickable(rounded(color, 20))
    isClickable = true
    isFocusable = true
    addView(label(emoji, 28f).apply { gravity = Gravity.CENTER }, lp())
    addView(label(text, 13f, Palette.INK, bold = true).apply {
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }, lp(topDp = 2))
    setOnClickListener { onClick() }
    setOnLongClickListener {
        onLongClick()
        true
    }
}
