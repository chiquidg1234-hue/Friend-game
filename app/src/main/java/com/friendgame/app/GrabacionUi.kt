package com.friendgame.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.Camera
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.max

/**
 * La cámara en una burbuja redonda, encima de la app, para que salga en la grabación de pantalla
 * (como el recuadro de BeReal, pero a tu modo). Se arrastra para moverla, se toca para cambiar el
 * tamaño y el botón 🔄 voltea entre la cámara frontal y la trasera sin cortar el video.
 *
 * Usa la cámara clásica de Android (como el lector de QR): gira y refleja la imagen sola. Se abre
 * en su propio hilo para no trabar la pantalla, y se cierra cuando la app deja de estar a la vista
 * (Android no deja usar la cámara en segundo plano).
 */
@Suppress("DEPRECATION")
class CameraBubble(context: Context, startFront: Boolean, private val onProblem: (String) -> Unit) :
    FrameLayout(context), TextureView.SurfaceTextureListener {

    /** Cámara frontal (tu cara) o trasera (lo que tienes enfrente). */
    var front = startFront
        private set

    private val sizes = intArrayOf(104, 140, 188)
    private var sizeIndex = 1
    private val lens = FrameLayout(context)
    private val texture = TextureView(context)
    private val placeholder = context.column().apply {
        gravity = Gravity.CENTER
        addView(context.icon(R.drawable.ic_camera, Palette.SUB, 28), LinearLayout.LayoutParams(context.dp(28), context.dp(28)))
        addView(context.label("Abriendo…", 11f, Palette.SUB, bold = true).apply { gravity = Gravity.CENTER }, context.lp(topDp = 4))
    }
    private val flip: View
    private val ring = View(context)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    /** Solo se toca desde el hilo de la cámara. */
    private var camera: Camera? = null
    private var previewW = 0
    private var previewH = 0
    private var degrees = 0
    private var paused = false

    init {
        clipChildren = false
        clipToPadding = false
        lens.background = context.oval(Palette.SURFACE)
        lens.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        lens.clipToOutline = true
        lens.elevation = context.dp(8).toFloat()
        lens.addView(texture, LayoutParams(MATCH, MATCH))
        lens.addView(placeholder, LayoutParams(MATCH, MATCH))
        ring.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(context.dp(3), Palette.TEXT)
        }
        lens.addView(ring, LayoutParams(MATCH, MATCH))
        lens.contentDescription = "Tu cámara en la grabación. Toca para cambiar el tamaño, arrastra para moverla"
        texture.surfaceTextureListener = this
        addView(lens)

        flip = context.iconButton(R.drawable.ic_flip, flipDescription(), Palette.ACCENT, Palette.INK, 40) { toggleCamera() }.apply {
            elevation = context.dp(10).toFloat()
        }
        addView(flip, LayoutParams(context.dp(40), context.dp(40), Gravity.BOTTOM or Gravity.END))
        applySize()
        setupDrag()
    }

    private fun flipDescription() = if (front) "Voltear a la cámara trasera" else "Voltear a la cámara frontal"

    private fun applySize() {
        val size = context.dp(sizes[sizeIndex])
        lens.layoutParams = LayoutParams(size, size, Gravity.TOP or Gravity.START)
        // La burbuja deja lugar para que el botón 🔄 quede montado en su borde.
        val extra = context.dp(12)
        layoutParams?.let {
            it.width = size + extra
            it.height = size + extra
            layoutParams = it
        }
        minimumWidth = size + extra
        minimumHeight = size + extra
    }

    private fun setupDrag() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startTx = 0f
        var startTy = 0f
        var dragging = false
        lens.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startTx = translationX
                    startTy = translationY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) moveTo(startTx + dx, startTy + dy)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        lens.performClick()
                        cycleSize()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        lens.setOnClickListener { }
    }

    /** Mueve la burbuja sin que se salga de la pantalla. */
    private fun moveTo(tx: Float, ty: Float) {
        val parent = parent as? View ?: return
        val minX = -left.toFloat()
        val maxX = (parent.width - right).toFloat()
        val minY = -top.toFloat()
        val maxY = (parent.height - bottom).toFloat()
        translationX = tx.coerceIn(minOf(minX, maxX), maxOf(minX, maxX))
        translationY = ty.coerceIn(minOf(minY, maxY), maxOf(minY, maxY))
    }

    private fun cycleSize() {
        sizeIndex = (sizeIndex + 1) % sizes.size
        applySize()
        post {
            moveTo(translationX, translationY)
            updateTransform()
        }
        if (Anim.enabled()) Anim.pop(lens, 1.06f)
    }

    /** Frontal ↔ trasera. La grabación de pantalla sigue; solo cambia lo que se ve en la burbuja. */
    fun toggleCamera() {
        front = !front
        flip.contentDescription = flipDescription()
        Store.recCamera = if (front) "front" else "back"
        if (Anim.enabled()) {
            lens.animate().rotationY(90f).setDuration(140).withEndAction {
                reopen()
                lens.rotationY = -90f
                lens.animate().rotationY(0f).setDuration(160).setInterpolator(DecelerateInterpolator()).start()
            }.start()
        } else {
            reopen()
        }
        announceForAccessibility(if (front) "Cámara frontal" else "Cámara trasera")
    }

    fun pause() {
        paused = true
        close()
    }

    fun resume() {
        paused = false
        reopen()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (thread == null) {
            val t = HandlerThread("friendgame-bubble").apply { start() }
            thread = t
            handler = Handler(t.looper)
        }
        reopen()
    }

    override fun onDetachedFromWindow() {
        close()
        val t = thread
        thread = null
        handler = null
        t?.quitSafely()
        super.onDetachedFromWindow()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = reopen()

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = updateTransform()

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // Primero se cierra la cámara (en su hilo) y recién después se suelta la imagen.
        val h = handler
        if (h == null) {
            surface.release()
        } else {
            h.post {
                closeNow()
                surface.release()
            }
        }
        return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (placeholder.visibility == View.VISIBLE) placeholder.visibility = View.GONE
    }

    private fun reopen() {
        val surface = texture.surfaceTexture ?: return
        val h = handler ?: return
        if (paused || !isAttachedToWindow) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            showPlaceholder("Sin permiso")
            return
        }
        val rotation = display?.rotation ?: Surface.ROTATION_0
        val wantFront = front
        h.post { openNow(surface, wantFront, rotation) }
    }

    private fun close() {
        handler?.post { closeNow() }
    }

    private fun closeNow() {
        val cam = camera ?: return
        camera = null
        try {
            cam.stopPreview()
        } catch (_: Exception) {
        }
        try {
            cam.release()
        } catch (_: Exception) {
        }
    }

    private fun openNow(surface: SurfaceTexture, wantFront: Boolean, rotation: Int) {
        closeNow()
        try {
            val count = Camera.getNumberOfCameras()
            if (count == 0) throw IllegalStateException("sin cámaras")
            val info = Camera.CameraInfo()
            val facing = if (wantFront) Camera.CameraInfo.CAMERA_FACING_FRONT else Camera.CameraInfo.CAMERA_FACING_BACK
            val id = (0 until count).firstOrNull {
                Camera.getCameraInfo(it, info)
                info.facing == facing
            } ?: 0
            Camera.getCameraInfo(id, info)
            val cam = Camera.open(id)
            val params = cam.parameters
            // Una imagen chica basta para la burbuja (y gasta menos batería mientras se graba).
            params.supportedPreviewSizes.minByOrNull { size ->
                val ratio = size.width.toFloat() / size.height
                abs(size.width * size.height - 640 * 480) + (abs(ratio - 4f / 3f) * 200_000).toInt()
            }?.let { params.setPreviewSize(it.width, it.height) }
            if (Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO in (params.supportedFocusModes ?: emptyList())) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            }
            try {
                cam.parameters = params
            } catch (_: Exception) {
            }
            val screen = when (rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            val result = if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                (360 - (info.orientation + screen) % 360) % 360
            } else {
                (info.orientation - screen + 360) % 360
            }
            cam.setDisplayOrientation(result)
            cam.setPreviewTexture(surface)
            cam.startPreview()
            camera = cam
            val size = cam.parameters.previewSize
            post {
                previewW = size.width
                previewH = size.height
                degrees = result
                updateTransform()
            }
        } catch (_: Exception) {
            closeNow()
            post {
                showPlaceholder("Sin cámara")
                onProblem("No se pudo abrir la cámara. La pantalla se sigue grabando.")
            }
        }
    }

    private fun showPlaceholder(text: String) {
        placeholder.visibility = View.VISIBLE
        (placeholder.getChildAt(1) as? android.widget.TextView)?.text = text
    }

    /** La imagen llena la burbuja sin estirarse (se recorta al centro). */
    private fun updateTransform() {
        val vw = texture.width.toFloat()
        val vh = texture.height.toFloat()
        if (vw <= 0f || vh <= 0f || previewW == 0 || previewH == 0) return
        val sideways = degrees % 180 != 0
        val bw = (if (sideways) previewH else previewW).toFloat()
        val bh = (if (sideways) previewW else previewH).toFloat()
        val scale = max(vw / bw, vh / bh)
        texture.setTransform(Matrix().apply { setScale(bw * scale / vw, bh * scale / vh, vw / 2f, vh / 2f) })
    }
}

/**
 * Píldora roja de arriba mientras se graba: punto que late, el tiempo (con [LiveText], que se
 * actualiza una vez por segundo sin molestar a TalkBack) y el botón Detener.
 */
class RecordingBar(context: Context, onStop: () -> Unit) : LinearLayout(context) {
    private val dot = View(context)
    private var pulse: ValueAnimator? = null
    private val stop: View

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = context.lipped(0xFF2A1030.toInt(), 22, Palette.DANGER_LIP, 3)
        setPadding(context.dp(14), context.dp(6), context.dp(6), context.dp(9))
        elevation = context.dp(12).toFloat()
        dot.background = context.oval(Palette.DANGER)
        addView(dot, LayoutParams(context.dp(12), context.dp(12)).apply { marginEnd = context.dp(8) })
        addView(context.title("REC", 15f, Palette.DANGER_TEXT), LayoutParams(WRAP, WRAP).apply { marginEnd = context.dp(8) })
        addView(LiveText(context, 15f, Palette.TEXT, bold = true, description = "Tiempo de grabación") {
            when (Grabacion.state) {
                Grabacion.State.STARTING -> "Preparando…"
                Grabacion.State.SAVING -> "Guardando…"
                else -> Grabacion.clock(Grabacion.elapsedMs()) + " / " + Grabacion.clock(Grabacion.MAX_MS.toLong())
            }
        }, LayoutParams(context.dp(104), WRAP).apply { marginEnd = context.dp(6) })
        stop = context.button("Detener", Palette.DANGER, Palette.TEXT, iconRes = R.drawable.ic_stop, heightDp = 38, radiusDp = 16, sizeSp = 14f) {
            onStop()
        }.apply { contentDescription = "Detener y guardar la grabación" }
        addView(stop, LayoutParams(WRAP, WRAP))
    }

    fun refresh() {
        val saving = Grabacion.state == Grabacion.State.SAVING
        stop.isEnabled = !saving
        stop.alpha = if (saving) 0.5f else 1f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Anim.enabled()) {
            pulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
                duration = 700
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        pulse?.cancel()
        pulse = null
        super.onDetachedFromWindow()
    }
}

/**
 * «Graba tu broma»: explica qué se graba, deja elegir la cámara (frontal, trasera o ninguna) y el
 * micrófono, y recuerda grabar solo a quien esté de acuerdo. Es una capa encima de la app.
 */
class RecordStartSheet(
    context: Context,
    private val onStart: (camera: String, mic: Boolean) -> Unit,
    private val onClose: () -> Unit,
) : FrameLayout(context) {
    private var camera = Store.recCamera
    private var mic = Store.recMic
    private val cameraChips = ArrayList<Pair<String, LinearLayout>>()

    init {
        isClickable = true
        isFocusable = true
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Palette.BG_HIGH, Palette.BG))
        val content = context.column()
        val top = context.row().apply { setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(6)) }
        top.addView(context.iconButton(R.drawable.ic_back, "Volver", Palette.SURFACE) { close() })
        top.addView(context.title("Graba tu broma", 28f), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = context.dp(12) })
        content.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        val body = context.column().apply { setPadding(context.dp(20), context.dp(6), context.dp(20), context.dp(16)) }
        val hero = context.row().apply { gravity = Gravity.CENTER_VERTICAL }
        val pet = PetView(context).apply {
            species = "bulla"
            outfit = Wardrobe.outfit()
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        hero.addView(pet, LinearLayout.LayoutParams(context.dp(92), context.dp(92)))
        val bubble = context.column().apply {
            background = context.lipped(Palette.TEXT, 18, Palette.SUB)
            setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(14))
        }
        bubble.addView(context.label("¡Luces, cámara, bulla! 🎬", 15f, Palette.INK, bold = true), context.lp())
        bubble.addView(context.label("Grabo tu pantalla y tu cámara a la vez, como BeReal pero a tu modo.", 13f, 0xFF4A3F7A.toInt(), bold = true), context.lp(topDp = 2))
        hero.addView(bubble, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = context.dp(8) })
        body.addView(hero, context.lp())
        post { if (Anim.enabled()) pet.cheer() }

        val steps = context.card(Palette.SURFACE, 22, 14)
        fun step(iconRes: Int, color: Int, title: String, text: String, first: Boolean = false) {
            val line = context.row().apply { gravity = Gravity.TOP }
            val badge = FrameLayout(context).apply { background = context.oval(color) }
            badge.addView(context.icon(iconRes, Palette.INK, 20), LayoutParams(context.dp(20), context.dp(20), Gravity.CENTER))
            line.addView(badge, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))
            val texts = context.column()
            texts.addView(context.label(title, 15f, bold = true), context.lp())
            texts.addView(context.label(text, 13f, Palette.SUB), context.lp(topDp = 2))
            line.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = context.dp(12) })
            steps.addView(line, context.lp(topDp = if (first) 0 else 14))
        }
        step(R.drawable.ic_video, Palette.ACCENT, "Tu pantalla", "Se graba lo que pasa en el celular: el sonido que mandas, el chat, todo.", first = true)
        step(R.drawable.ic_camera, Palette.BULLA, "Tu cámara en una burbuja", "Arrástrala para moverla y tócala para hacerla más grande o más chica.")
        step(R.drawable.ic_flip, Palette.YELLOW, "Voltea la cámara", "Cambia entre la frontal y la trasera cuando quieras, sin cortar el video.")
        body.addView(steps, context.lp(topDp = 16))

        body.addView(context.caps("Cámara de la burbuja"), context.lp(topDp = 18))
        val chips = context.row()
        listOf("front" to "🤳 Frontal", "back" to "📷 Trasera", "off" to "🚫 Sin cámara").forEach { (key, text) ->
            val chip = Chunky(context, context.dp(3)).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                minimumHeight = context.dp(48)
                setPadding(context.dp(6), 0, context.dp(6), context.dp(4))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    camera = key
                    refreshChips()
                }
            }
            chip.addView(context.label(text, 14f, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            if (chips.childCount > 0) chips.addView(context.space(8))
            chips.addView(chip, LinearLayout.LayoutParams(0, WRAP, 1f))
            cameraChips += key to chip
        }
        body.addView(chips, context.lp(topDp = 8))
        refreshChips()

        val micCard = context.card(Palette.SURFACE, 18, 0)
        val micRow = context.row().apply { setPadding(context.dp(16), context.dp(12), context.dp(12), context.dp(12)) }
        val micTexts = context.column()
        micTexts.addView(context.label("🎙️ Grabar el micrófono", 15f, bold = true), context.lp())
        micTexts.addView(context.label("Para que se oigan tus risas y lo que suena.", 13f, Palette.SUB), context.lp(topDp = 2))
        micRow.addView(micTexts, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = context.dp(10) })
        micRow.addView(context.toggle(mic, "Grabar el micrófono") { mic = it }, LinearLayout.LayoutParams(WRAP, WRAP))
        micCard.addView(micRow, context.lp())
        body.addView(micCard, context.lp(topDp = 12))

        val note = context.row().apply {
            gravity = Gravity.TOP
            background = context.rounded(Palette.ACCENT_SOFT, 16)
            setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
        }
        note.addView(context.icon(R.drawable.ic_shield, Palette.OK, 20), LinearLayout.LayoutParams(context.dp(20), context.dp(20)).apply { marginEnd = context.dp(10) })
        note.addView(context.label(
            "El video queda en tu galería y solo lo ve quien tú quieras. Graba a otras personas solo si están de acuerdo.",
            13f, Palette.TEXT,
        ), LinearLayout.LayoutParams(0, WRAP, 1f))
        body.addView(note, context.lp(topDp = 14))

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(body, ViewGroup.LayoutParams(MATCH, WRAP))
        }
        content.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val bottom = context.column().apply { setPadding(context.dp(20), context.dp(8), context.dp(20), context.dp(12)) }
        bottom.addView(context.button("Empezar a grabar", Palette.DANGER, Palette.TEXT, iconRes = R.drawable.ic_video, heightDp = 58, display = true, sizeSp = 20f) {
            Store.recCamera = camera
            Store.recMic = mic
            onStart(camera, mic)
        }, context.lp())
        bottom.addView(context.label(
            "Android te va a preguntar si dejas grabar la pantalla. Cada video dura hasta ${Grabacion.MAX_MS / 60_000} min.",
            12f, Palette.SUB,
        ).apply { gravity = Gravity.CENTER }, context.lp(topDp = 8))
        content.addView(bottom, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(content, LayoutParams(MATCH, MATCH))
    }

    private fun refreshChips() {
        cameraChips.forEach { (key, chip) ->
            val on = key == camera
            chip.background = context.chunkyBackground(if (on) Palette.BULLA else Palette.SURFACE, 14, strokeColor = if (on) null else Palette.LIP, lipDp = 3)
            chip.isSelected = on
            (chip.getChildAt(0) as? android.widget.TextView)?.setTextColor(if (on) Palette.INK else Palette.TEXT)
            val name = (chip.getChildAt(0) as? android.widget.TextView)?.text ?: ""
            chip.contentDescription = name.toString() + if (on) ", elegida" else ""
        }
    }

    fun enter() {
        if (!Anim.enabled()) return
        translationY = resources.displayMetrics.heightPixels * 0.25f
        alpha = 0f
        animate().translationY(0f).alpha(1f).setDuration(300).setInterpolator(DecelerateInterpolator()).start()
    }

    fun close() {
        (parent as? ViewGroup)?.removeView(this)
        onClose()
    }
}

/**
 * Después de grabar: el video se repite en un cuadro, y se puede compartir, borrar o grabar otro.
 * Bulla festeja. El video ya está en la galería (o en la app, si no hubo permiso).
 */
class ClipSheet(
    context: Context,
    private val clip: Grabacion.Clip,
    private val onShare: () -> Unit,
    private val onDelete: () -> Unit,
    private val onAgain: () -> Unit,
    private val onClose: () -> Unit,
) : FrameLayout(context), TextureView.SurfaceTextureListener {
    private val box = FrameLayout(context)
    private val video = TextureView(context)
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var videoW = 0
    private var videoH = 0
    private val playHint = context.label("▶", 44f, Palette.TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        visibility = View.GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        isClickable = true
        isFocusable = true
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Palette.BG_HIGH, Palette.BG))
        val content = context.column()
        val top = context.row().apply { setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(6)) }
        top.addView(context.iconButton(R.drawable.ic_close, "Cerrar", Palette.SURFACE) { close() })
        top.addView(context.title("¡Quedó grabada! 🎬", 26f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = context.dp(12) })
        content.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        box.background = context.rounded(0xFF000000.toInt(), 24)
        box.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, context.dp(24).toFloat())
        }
        box.clipToOutline = true
        box.addView(video, LayoutParams(MATCH, MATCH, Gravity.CENTER))
        box.addView(playHint, LayoutParams(MATCH, MATCH))
        box.contentDescription = "Tu video. Toca para pausar o seguir"
        box.setOnClickListener { togglePlay() }
        video.surfaceTextureListener = this
        val boxWrap = FrameLayout(context).apply { setPadding(context.dp(20), context.dp(8), context.dp(20), context.dp(8)) }
        boxWrap.addView(box, LayoutParams(MATCH, MATCH))
        content.addView(boxWrap, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val info = context.row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(20), context.dp(4), context.dp(20), 0)
        }
        val pet = PetView(context).apply {
            species = "bulla"
            outfit = Wardrobe.outfit()
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        info.addView(pet, LinearLayout.LayoutParams(context.dp(56), context.dp(56)))
        val texts = context.column()
        texts.addView(context.label("Dura ${Grabacion.clock(clip.durationMs)}", 15f, bold = true), context.lp())
        texts.addView(context.label(
            if (clip.inGallery) "Guardado en tu galería › Películas › ${Grabacion.FOLDER}" else "Guardado en Friend Game: compártelo para tenerlo en otra app",
            13f, Palette.SUB,
        ), context.lp(topDp = 2))
        info.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = context.dp(10) })
        content.addView(info, LinearLayout.LayoutParams(MATCH, WRAP))
        post { if (Anim.enabled()) pet.celebrate() }

        val bottom = context.column().apply { setPadding(context.dp(20), context.dp(10), context.dp(20), context.dp(12)) }
        bottom.addView(context.button("Compartir", Palette.ACCENT, Palette.INK, iconRes = R.drawable.ic_share, heightDp = 56, display = true, sizeSp = 20f) { onShare() }, context.lp())
        val row = context.row()
        row.addView(context.button("Borrar", Palette.SURFACE, Palette.DANGER_TEXT, heightDp = 48, sizeSp = 14f, iconColor = Palette.DANGER_TEXT) {
            onDelete()
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(context.space(8))
        row.addView(context.button("Grabar otra", Palette.SURFACE, Palette.TEXT, heightDp = 48, sizeSp = 14f) {
            close()
            onAgain()
        }, LinearLayout.LayoutParams(0, WRAP, 1.3f))
        row.addView(context.space(8))
        row.addView(context.button("Listo", Palette.OK, Palette.INK, heightDp = 48, sizeSp = 14f) { close() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        bottom.addView(row, context.lp(topDp = 10))
        content.addView(bottom, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(content, LayoutParams(MATCH, MATCH))
    }

    fun enter() {
        if (!Anim.enabled()) return
        scaleX = 0.92f
        scaleY = 0.92f
        alpha = 0f
        animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun togglePlay() {
        val mp = player ?: return
        try {
            if (mp.isPlaying) {
                mp.pause()
                playHint.visibility = View.VISIBLE
            } else {
                mp.start()
                playHint.visibility = View.GONE
            }
        } catch (_: Exception) {
        }
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val s = Surface(st)
        surface = s
        try {
            val mp = MediaPlayer()
            player = mp
            mp.setDataSource(context, clip.uri)
            mp.setSurface(s)
            mp.isLooping = true
            mp.setOnVideoSizeChangedListener { _, w, h ->
                videoW = w
                videoH = h
                fit()
            }
            mp.setOnPreparedListener { it.start() }
            mp.setOnErrorListener { _, _, _ ->
                playHint.text = "No se pudo mostrar el video"
                playHint.textSize = 15f
                playHint.visibility = View.VISIBLE
                true
            }
            mp.prepareAsync()
        } catch (_: Exception) {
            playHint.text = "No se pudo mostrar el video"
            playHint.textSize = 15f
            playHint.visibility = View.VISIBLE
        }
    }

    /** El video entero dentro del cuadro, sin estirarse. */
    private fun fit() {
        val bw = box.width
        val bh = box.height
        if (videoW <= 0 || videoH <= 0 || bw <= 0 || bh <= 0) return
        val scale = minOf(bw.toFloat() / videoW, bh.toFloat() / videoH)
        video.layoutParams = LayoutParams((videoW * scale).toInt(), (videoH * scale).toInt(), Gravity.CENTER)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { fit() }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    private fun release() {
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        surface?.release()
        surface = null
    }

    fun pause() {
        try {
            player?.takeIf { it.isPlaying }?.pause()
            playHint.visibility = if (player != null) View.VISIBLE else playHint.visibility
        } catch (_: Exception) {
        }
    }

    fun close() {
        release()
        (parent as? ViewGroup)?.removeView(this)
        onClose()
    }
}
