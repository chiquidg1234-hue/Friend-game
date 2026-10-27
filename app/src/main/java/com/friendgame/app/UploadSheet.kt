package com.friendgame.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale
import kotlin.math.sin

/**
 * Pantalla para subir (o terminar de grabar) un audio, del diseño «Subir audio»: muestra su forma,
 * Bulla lo escucha y adivina la categoría ([AudioClassifier]); si no está segura, pide elegirla.
 * Se elige el nombre y, si es largo, la parte que suena. No se redibuja con el resto de la app (así
 * no se pierde lo escrito): es una capa encima, como el aviso de sonido.
 */
class UploadSheet(
    context: Context,
    private val imported: CustomAudio.Imported,
    heading: String,
    private val onPreview: (Player.Source, String, Int, String) -> Unit,
    private val onSave: (CustomSound, String) -> Unit,
    private val onClose: () -> Unit,
) : FrameLayout(context) {
    private val file = CustomAudio.fileFor(context, imported.sound)
    private val source = Player.Source.FromFile(file)
    private var duration = imported.durationMs
    private var startMs = 0
    private var vibe: String? = null
    private var suggested: String? = null
    private var saved = false
    private val wave = WaveView(context)
    private val pet = PetView(context).apply {
        species = "bulla"
        listening = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        outfit = Wardrobe.outfit()
    }
    private val bubbleTitle = context.label("Escuchando…", 15f, Palette.INK, bold = true)
    private val bubbleText = context.label("Bulla está adivinando de qué categoría es.", 13f, 0xFF4A3F7A.toInt(), bold = true)
    private val choices = ArrayList<Pair<Vibe, LinearLayout>>()
    private val nameInput = EditText(context)
    private val rangeText = context.label("", 13f, Palette.YELLOW, bold = true)
    private val seek = SeekBar(context)
    private val content: LinearLayout

    init {
        isClickable = true
        isFocusable = true
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Palette.BG_HIGH, Palette.BG))
        content = context.column()
        val top = context.row().apply { setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(6)) }
        top.addView(context.iconButton(R.drawable.ic_back, "Volver", Palette.SURFACE) { close() })
        top.addView(context.title(heading, 28f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(12) })
        content.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val body = context.column().apply { setPadding(context.dp(20), context.dp(6), context.dp(20), context.dp(16)) }
        // El archivo, su forma y la parte que suena.
        val fileCard = context.card(Palette.SURFACE, 22, 16)
        val head = context.row()
        head.addView(context.label(imported.suggestedLabel, 15f, bold = true).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val size = file.length()
        head.addView(context.label("${clock(duration)} · ${if (size > 0) "${size / 1000} KB" else ""}", 13f, Palette.SUB, bold = true))
        fileCard.addView(head, context.lp())
        fileCard.addView(wave, context.lp(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(64), topDp = 10))
        fileCard.addView(seek.apply {
            visibility = View.GONE
            contentDescription = "Desde dónde suena"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    startMs = value * 100
                    updateRange()
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit

                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }, context.lp(topDp = 6))
        fileCard.addView(rangeText, context.lp(topDp = 6))
        body.addView(fileCard, context.lp())

        // Bulla escucha y dice lo que cree.
        val listen = context.row().apply { gravity = Gravity.CENTER_VERTICAL }
        listen.addView(pet, LinearLayout.LayoutParams(context.dp(96), context.dp(96)))
        val bubble = context.column().apply {
            background = context.lipped(Palette.TEXT, 18, Palette.SUB)
            setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(14))
        }
        bubble.addView(bubbleTitle, context.lp())
        bubble.addView(bubbleText, context.lp(topDp = 2))
        listen.addView(bubble, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(8) })
        body.addView(listen, context.lp(topDp = 14))

        body.addView(context.caps("Categoría · ¿no es esa? Elige otra"), context.lp(topDp = 14))
        Vibes.all.chunked(3).forEach { chunk ->
            val line = context.row()
            chunk.forEach { v ->
                val option = Chunky(context, context.dp(3)).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    minimumHeight = context.dp(48)
                    setPadding(context.dp(6), 0, context.dp(6), context.dp(4))
                    isClickable = true
                    setOnClickListener { pick(v.key) }
                }
                option.addView(context.icon(v.icon, v.chip, 16), LinearLayout.LayoutParams(context.dp(16), context.dp(16)).apply { marginEnd = context.dp(6) })
                option.addView(context.label(v.label, 14f, v.chip, bold = true))
                choices += v to option
                if (line.childCount > 0) line.addView(context.space(8))
                line.addView(option, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            body.addView(line, context.lp(topDp = 8))
        }
        refreshChoices()

        body.addView(context.caps("¿Cómo se llama este audio?"), context.lp(topDp = 18))
        nameInput.apply {
            setText(imported.suggestedLabel)
            setSingleLine(true)
            textSize = 17f
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.MUTED_TEXT)
            hint = "Nombre"
            typeface = Fonts.body(context, 700)
            background = context.rounded(Palette.SURFACE, 16, Palette.ACCENT)
            setPadding(context.dp(16), 0, context.dp(16), 0)
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(24))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            contentDescription = "Nombre del audio"
        }
        body.addView(nameInput, context.lp(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(52), topDp = 8))
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = context.column().apply { setPadding(context.dp(20), context.dp(8), context.dp(20), context.dp(12)) }
        val actions = context.row()
        actions.addView(context.button("Escuchar", Palette.SURFACE, Palette.TEXT, iconRes = R.drawable.ic_play, heightDp = 56, iconColor = Palette.TEXT) {
            onPreview(source, nameInput.text.toString().ifBlank { imported.suggestedLabel }, startMs, chosenVibe())
            pet.listening = false
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(context.space(10))
        actions.addView(context.button("Guardar", Palette.OK, Palette.INK, heightDp = 56, display = true, sizeSp = 20f) { save() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f))
        bottom.addView(actions, context.lp())
        bottom.addView(context.label(CustomAudio.HINT, 12f, Palette.SUB).apply { gravity = Gravity.CENTER }, context.lp(topDp = 8))
        content.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(content, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        updateRange()
        analyze()
    }

    /** Aparece desde abajo. */
    fun enter() {
        if (!Anim.enabled()) return
        translationY = resources.displayMetrics.heightPixels * 0.25f
        alpha = 0f
        animate().translationY(0f).alpha(1f).setDuration(300).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun chosenVibe(): String = vibe ?: suggested ?: Vibes.default.key

    private fun pick(key: String) {
        vibe = key
        refreshChoices()
        pet.listening = false
        pet.react(key, 1.6f)
        val v = Vibes.byKey(key)
        bubbleTitle.text = "¡Listo, ${v.label}!"
        bubbleText.text = "Cuando suene, el fondo y yo vamos a reaccionar así."
    }

    private fun refreshChoices() {
        val current = vibe ?: suggested
        choices.forEach { (v, view) ->
            val on = v.key == current
            val surely = v.key == vibe
            view.background = context.chunkyBackground(if (on) v.color else Palette.SURFACE, 14, strokeColor = if (on) null else Palette.LIP, lipDp = 3)
            view.alpha = if (on && !surely) 0.85f else 1f
            view.isSelected = on
            view.contentDescription = v.label + when {
                surely -> ", elegida"
                on -> ", sugerida por Bulla"
                else -> ""
            }
            for (i in 0 until view.childCount) {
                when (val child = view.getChildAt(i)) {
                    is TextView -> child.setTextColor(if (on) v.onColor else v.chip)
                    is android.widget.ImageView -> child.imageTintList = android.content.res.ColorStateList.valueOf(if (on) v.onColor else v.chip)
                }
            }
        }
    }

    private fun updateRange() {
        val long = duration > Player.MAX_PLAY_MS + 500
        seek.visibility = if (long) View.VISIBLE else View.GONE
        if (long) seek.max = ((duration - 1000) / 100).toInt()
        wave.window(startMs, duration)
        rangeText.text = if (long) "Suena 10 segundos desde ${clock(startMs.toLong())} · mueve la barra para elegir"
        else "Suena completo (${clock(duration)})"
    }

    private fun analyze() {
        val started = SystemClock.uptimeMillis()
        Thread {
            val analysis = try {
                AudioAnalysis.analyze(context, source)
            } catch (_: Exception) {
                null
            }
            // Que se vea a Bulla escuchando un momento, aunque el análisis sea rápido.
            val wait = (900 - (SystemClock.uptimeMillis() - started)).coerceAtLeast(0)
            postDelayed({ showResult(analysis) }, wait)
        }.start()
    }

    /** Muestra lo que Bulla escuchó (público para poder dibujarlo en tools/render). */
    fun showResult(analysis: AudioAnalysis.Analysis?) {
        if (!isAttachedToWindow) return
        pet.listening = false
        if (analysis != null) {
            wave.envelope = analysis.envelope
            if (analysis.durationMs > 0 && duration <= 0) {
                duration = analysis.durationMs
                updateRange()
            }
        }
        val result = analysis?.result
        if (vibe != null) return
        if (result?.vibe != null) {
            val v = Vibes.byKey(result.vibe)
            suggested = v.key
            vibe = v.key
            bubbleTitle.text = WHAT_I_HEARD[v.key] ?: "¡Lo tengo!"
            bubbleText.text = "Lo puse en ${v.label}. Seguridad: ${(result.confidence * 100).toInt()} %"
            pet.react(v.key, 2f)
        } else {
            suggested = result?.best
            bubbleTitle.text = "No estoy segura…"
            bubbleText.text = if (result != null && result.features.loudness > -50f) "¿Parece ${Vibes.byKey(result.best).label}? Elige la categoría abajo." else "Casi no se oye nada. Elige la categoría abajo."
            pet.surprise()
        }
        refreshChoices()
    }

    private fun save() {
        if (saved) return
        val name = nameInput.text.toString().trim().ifEmpty { imported.suggestedLabel }
        saved = true
        Player.stop()
        onSave(imported.sound.copy(label = name.take(24), startMs = startMs), chosenVibe())
        dismiss()
    }

    /** Cerrar sin guardar: se borra el archivo copiado. */
    fun close() {
        if (!saved) {
            Player.stop()
            file.delete()
        }
        dismiss()
    }

    private fun dismiss() {
        onClose()
        if (!Anim.enabled()) {
            (parent as? ViewGroup)?.removeView(this)
            return
        }
        animate().translationY(height * 0.3f).alpha(0f).setDuration(220).withEndAction { (parent as? ViewGroup)?.removeView(this) }.start()
    }

    private fun clock(ms: Long): String {
        val s = (ms + 500) / 1000
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private companion object {
        val WHAT_I_HEARD = mapOf(
            "risa" to "¡Escuché risas!",
            "voz" to "¡Escuché a alguien hablando!",
            "fiesta" to "¡Esto se baila!",
            "susto" to "¡Ay, qué susto!",
            "amor" to "Aww, qué suave…",
            "troll" to "Jaja, ¡qué ruido más raro!",
        )
    }
}

/**
 * Forma de un audio en barritas (su envolvente) y la parte de 10 segundos que suena, resaltada.
 * Mientras no está la envolvente, barritas que respiran.
 */
class WaveView(context: Context) : View(context) {
    var envelope: FloatArray? = null
        set(value) {
            field = value
            invalidate()
        }
    private var startMs = 0
    private var durationMs = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun window(start: Int, duration: Long) {
        startMs = start
        durationMs = duration
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val bars = 40
        val gap = w / bars
        val barW = gap * 0.55f
        val env = envelope
        val total = durationMs.coerceAtLeast(1)
        val from = startMs.toFloat() / total
        val to = (startMs + Player.MAX_PLAY_MS).toFloat() / total
        if (env != null && durationMs > Player.MAX_PLAY_MS + 500) {
            paint.color = 0x29FFC93C
            canvas.drawRoundRect(from * w, 0f, (to * w).coerceAtMost(w), h, 12f, 12f, paint)
        }
        val t = SystemClock.uptimeMillis() / 1000f
        for (i in 0 until bars) {
            val k = (i + 0.5f) / bars
            val v = if (env != null && env.isNotEmpty()) {
                val a = (k * env.size).toInt().coerceIn(0, env.size - 1)
                env[a]
            } else {
                0.25f + 0.2f * sin(t * 4f + i * 0.6f)
            }
            val bh = (0.12f + 0.88f * v.coerceIn(0f, 1f)) * h
            val inside = env == null || (k >= from && k <= to)
            paint.color = if (inside && env != null) Palette.YELLOW else Palette.FAINT
            val x = i * gap + (gap - barW) / 2f
            canvas.drawRoundRect(x, (h - bh) / 2f, x + barW, (h + bh) / 2f, barW / 2f, barW / 2f, paint)
        }
        if (env == null && Anim.enabled()) postInvalidateDelayed(50)
    }
}
