package com.friendgame.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import kotlin.math.hypot
import kotlin.math.sin

/**
 * La pieza de la mascota, como en Pou: grande, en el centro, con cuatro barras arriba y piezas
 * abajo. En la cocina le das de comer arrastrando la comida a su boca; en el baño la enjabonas
 * frotándola y la duchas; en la pieza apagas la luz para que duerma; en la sala juega y abre los
 * minijuegos; en el armario la vistes; y «Háblale» repite lo que dices con voz chistosa.
 *
 * Se arma una sola vez y se reusa en cada redibujo (como la caja del chat), así no se cortan las
 * animaciones ni lo que estás arrastrando. [update] la pone al día.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class PetRoom(context: Context, private val host: Host) : LinearLayout(context) {

    interface Host {
        fun toast(text: String)
        fun openGames()
        /** Pide el permiso del micrófono si hace falta y después llama a [then]. */
        fun withMic(then: () -> Unit)
        /** Acariciarla: misión del día y una reacción. */
        fun petted()
    }

    enum class Room(val title: String, val iconRes: Int, val color: Int) {
        COCINA("Cocina", R.drawable.ic_food, 0xFFFF9F43.toInt()),
        BANO("Baño", R.drawable.ic_bath, 0xFF48DBFB.toInt()),
        DORMIR("Dormir", R.drawable.ic_moon, 0xFFA29BFE.toInt()),
        JUGAR("Jugar", R.drawable.ic_gamepad, 0xFF3DDC97.toInt()),
        ARMARIO("Armario", R.drawable.ic_shirt, 0xFFFF7AB8.toInt()),
        HABLALE("Háblale", R.drawable.ic_mic, 0xFFFFC93C.toInt()),
    }

    val pet = PetView(context).apply { contentDescription = "Tu mascota. Tócala para acariciarla" }
    var room = Room.COCINA
        private set
    private val stats = context.row()
    private val stage = FrameLayout(context)
    private val backdrop = RoomBackdrop(context)
    private val bubble = context.label("", 15f, Palette.INK, bold = true)
    private val dark = View(context)
    private val tray = FrameLayout(context)
    private val tabs = context.row()
    private var lightsOff = false
    private var playedBall = false
    private var parrot: Parrot? = null
    private var micLevel = 0f
    private var builtRoom: Room? = null
    private var builtLevel = -1
    private val sleepCare = Runnable {
        if (lightsOff) when (Pets.care(Pets.Care.ENERGIA)) {
            Pets.CareResult.OK -> say("Zzz… ¡energía llena! ⚡")
            Pets.CareResult.FULL -> say("Zzz…")
            Pets.CareResult.NO_POINTS -> Unit
        }
    }

    init {
        orientation = VERTICAL
        addView(stats, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        stage.background = context.rounded(0, 28)
        stage.clipToOutline = true
        stage.addView(backdrop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stage.addView(pet, FrameLayout.LayoutParams(context.dp(236), context.dp(236), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = context.dp(66)
        })
        bubble.apply {
            background = context.lipped(Palette.TEXT, 18, Palette.SUB)
            setPadding(context.dp(14), context.dp(9), context.dp(14), context.dp(12))
            maxLines = 3
            gravity = Gravity.CENTER
        }
        stage.addView(bubble, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = context.dp(12)
            leftMargin = context.dp(24)
            rightMargin = context.dp(24)
        })
        dark.setBackgroundColor(0xB8070414.toInt())
        dark.visibility = GONE
        dark.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        stage.addView(dark, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stage.addView(tray, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(context.dp(10), 0, context.dp(10), context.dp(10))
        })
        addView(stage, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(400)).apply { topMargin = context.dp(12) })
        addView(tabs, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(12) })

        pet.isClickable = true
        pet.setOnClickListener {
            if (lightsOff) {
                say("Shhh… estoy durmiendo 😴")
                return@setOnClickListener
            }
            pet.cheer()
            host.petted()
            say(listOf("¡Jiji, cosquillas!", "¡Te quiero! 💕", "¡Otra vez!", "Mmm, qué rico cariño").random())
        }
        say(Pets.says())
        buildTabs()
    }

    /** Pone al día la mascota, las barras y lo de la pieza. Se llama en cada redibujo. */
    fun update(species: Pets.Species, stageNumber: Int, progress: Float) {
        pet.species = species.key
        pet.stage = stageNumber
        pet.mood = Pets.mood()
        pet.season = Seasons.current()?.key ?: ""
        pet.progress = progress
        pet.outfit = Wardrobe.outfit()
        val clean = Pets.careLevel(Pets.Care.LIMPIEZA)
        pet.dirt = ((55 - clean) / 55f).coerceIn(0f, 1f)
        pet.audioLevel = { if (micLevel > 0f) micLevel else parrot?.level() ?: 0f }
        backdrop.room = room
        buildStats()
        // La bandeja se rearma solo si cambió la pieza o el nivel (precios del armario); si no, sigue igual.
        val level = Points.level().number
        if (builtRoom != room || builtLevel != level || room == Room.ARMARIO) buildTray()
        refreshTabs()
    }

    /** Al salir de la pantalla: prende la luz, corta la voz y la mascota vuelve a mirar sola. */
    fun leave() {
        if (lightsOff) setLights(on = true, quiet = true)
        parrot?.cancel()
        parrot = null
        micLevel = 0f
        pet.listening = false
        pet.expecting = false
        pet.foam = 0f
        pet.lookAround()
    }

    private fun say(text: String) {
        bubble.text = text
        if (Anim.enabled()) {
            bubble.scaleX = 0.85f
            bubble.scaleY = 0.85f
            bubble.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(3f)).start()
        }
    }

    private fun buildStats() {
        stats.removeAllViews()
        Pets.Care.entries.forEach { care ->
            val lvl = Pets.careLevel(care)
            val cell = context.column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                contentDescription = "${care.label}: $lvl de 100"
            }
            val head = context.row().apply { gravity = Gravity.CENTER }
            head.addView(context.label(care.emoji, 14f).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO })
            head.addView(context.caps(care.label).apply {
                textSize = 11f
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(3) })
            cell.addView(head)
            val color = when {
                lvl < 25 -> Palette.DANGER
                lvl < 50 -> Palette.YELLOW
                else -> Palette.OK
            }
            cell.addView(ProgressPill(context, lvl / 100f, color), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(10)).apply { topMargin = context.dp(4) })
            if (stats.childCount > 0) stats.addView(context.space(8))
            stats.addView(cell, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun buildTabs() {
        tabs.removeAllViews()
        Room.entries.forEach { r ->
            val tab = Chunky(context, context.dp(4)).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = context.dp(66)
                setPadding(0, context.dp(6), 0, context.dp(10))
                isClickable = true
                tag = r
                setOnClickListener { go(r) }
            }
            tab.addView(context.icon(r.iconRes, Palette.INK, 22), LayoutParams(context.dp(22), context.dp(22)))
            tab.addView(context.label(r.title, 11f, Palette.INK, bold = true).apply {
                maxLines = 1
                gravity = Gravity.CENTER
            }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(3) })
            if (tabs.childCount > 0) tabs.addView(context.space(6))
            tabs.addView(tab, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun refreshTabs() {
        for (i in 0 until tabs.childCount) {
            val tab = tabs.getChildAt(i) as? Chunky ?: continue
            val r = tab.tag as? Room ?: continue
            val on = r == room
            tab.background = context.chunkyBackground(if (on) r.color else Palette.SURFACE, 16, strokeColor = if (on) null else Palette.LIP)
            tab.isSelected = on
            tab.contentDescription = r.title + if (on) ", elegida" else ""
            val color = if (on) Palette.INK else r.color
            (tab.getChildAt(0) as? android.widget.ImageView)?.imageTintList = android.content.res.ColorStateList.valueOf(color)
            (tab.getChildAt(1) as? TextView)?.setTextColor(if (on) Palette.INK else Palette.TEXT)
        }
    }

    private fun go(next: Room) {
        if (next == room) return
        if (room == Room.DORMIR && lightsOff) setLights(on = true, quiet = true)
        if (room == Room.HABLALE) {
            parrot?.cancel()
            parrot = null
            micLevel = 0f
            pet.listening = false
        }
        room = next
        backdrop.room = next
        pet.foam = if (next == Room.BANO) pet.foam else 0f
        if (Anim.enabled()) {
            backdrop.alpha = 0.4f
            backdrop.animate().alpha(1f).setDuration(240).start()
        }
        say(when (next) {
            Room.COCINA -> "Arrastra la comida hasta mi boca 😋 (${Pets.FEED_COST} pts)"
            Room.BANO -> "Frótame con el jabón y después la ducha 🛁"
            Room.DORMIR -> if (Pets.careLevel(Pets.Care.ENERGIA) < 50) "Tengo sueño… ¿apagas la luz? 🌙" else "Apaga la luz cuando quieras que duerma"
            Room.JUGAR -> "¡Juguemos! Toca la pelota o elige un minijuego 🎮"
            Room.ARMARIO -> "¿Qué me pongo hoy? 👗"
            Room.HABLALE -> "Mantén el botón y háblame: lo repito con mi voz 🎤"
        })
        buildTray()
        refreshTabs()
        pet.wave()
    }

    private fun buildTray() {
        builtRoom = room
        builtLevel = Points.level().number
        tray.removeAllViews()
        val box = context.row().apply {
            gravity = Gravity.CENTER
            background = context.rounded(0xCC150C2C.toInt(), 20)
            setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
        }
        when (room) {
            Room.COCINA -> {
                val species = Pets.current
                val foods = listOfNotNull(species?.food, "🍎", "🍕", "🥕", "🍪").distinct().take(5)
                foods.forEach { food -> box.addView(foodTile(food), LayoutParams(0, context.dp(52), 1f).apply { setMargins(context.dp(3), 0, context.dp(3), 0) }) }
            }
            Room.BANO -> {
                box.addView(soapTile(), LayoutParams(context.dp(64), context.dp(52)))
                box.addView(context.space(10))
                box.addView(context.button("🚿 Ducha", 0xFF48DBFB.toInt(), Palette.INK, heightDp = 52, radiusDp = 16, sizeSp = 15f) { shower() },
                    LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            Room.DORMIR -> box.addView(
                context.button(if (lightsOff) "💡 Prender la luz" else "🌙 Apagar la luz", if (lightsOff) Palette.YELLOW else 0xFFA29BFE.toInt(), Palette.INK, heightDp = 52, radiusDp = 16) {
                    setLights(on = lightsOff, quiet = false)
                },
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            Room.JUGAR -> {
                val ball = context.label("🏀", 30f).apply {
                    gravity = Gravity.CENTER
                    background = context.clickable(context.lipped(Palette.SURFACE2, 16))
                    isClickable = true
                    contentDescription = "Tirarle la pelota"
                    stateListAnimator = Anim.press()
                    setOnClickListener { playBall(this) }
                }
                box.addView(ball, LayoutParams(context.dp(56), context.dp(52)))
                box.addView(context.space(10))
                box.addView(context.button("🎮 Minijuegos", Palette.OK, Palette.INK, heightDp = 52, radiusDp = 16, display = true, sizeSp = 17f) { host.openGames() },
                    LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            Room.ARMARIO -> {
                val scroller = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
                val items = context.row()
                Wardrobe.ITEMS.forEach { item -> items.addView(itemTile(item), LayoutParams(context.dp(70), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(6) }) }
                scroller.addView(items)
                box.addView(scroller, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            Room.HABLALE -> {
                val column = context.column()
                column.addView(micButton(), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                val phrases = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
                val line = context.row()
                PHRASES.forEach { (text, _) ->
                    line.addView(context.label(text, 13f, Palette.TEXT, bold = true).apply {
                        background = context.clickable(context.rounded(Palette.SURFACE2, 999))
                        setPadding(context.dp(12), context.dp(7), context.dp(12), context.dp(7))
                        isClickable = true
                        stateListAnimator = Anim.press()
                        setOnClickListener { answer(text) }
                    }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(6) })
                }
                phrases.addView(line)
                column.addView(phrases, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) })
                box.addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        tray.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ---------------------------------------------------------------- Cocina

    /** Comida que se arrastra hasta la boca (o se toca, para darla directo). */
    private fun foodTile(food: String): View = context.label(food, 28f).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        background = context.lipped(Palette.SURFACE2, 16)
        isClickable = true
        contentDescription = "Darle $food"
        // Tocar (o activar con TalkBack) se la da directo; arrastrar es el juego.
        setOnClickListener { feed(food) }
        var flying: TextView? = null
        var downX = 0f
        var downY = 0f
        var moved = false
        setOnTouchListener { view, e ->
            val at = IntArray(2)
            val base = IntArray(2)
            stage.getLocationOnScreen(base)
            view.getLocationOnScreen(at)
            val x = e.rawX - base[0]
            val y = e.rawY - base[1]
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                    view.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && hypot(e.rawX - downX, e.rawY - downY) > context.dp(8)) {
                        moved = true
                        view.alpha = 0.35f
                        flying = context.label(food, 40f).apply {
                            includeFontPadding = false
                            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                        }
                        stage.addView(flying, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    }
                    flying?.let { f ->
                        f.x = x - f.width / 2f
                        f.y = y - f.height / 2f - context.dp(20)
                        pet.lookAt(x - pet.left, y - pet.top)
                        pet.expecting = pet.nearMouth(x - pet.left, y - pet.top - context.dp(20))
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.alpha = 1f
                    val f = flying
                    flying = null
                    val near = pet.expecting
                    pet.expecting = false
                    pet.lookAround()
                    if (f != null) {
                        if (near && e.actionMasked == MotionEvent.ACTION_UP) {
                            stage.removeView(f)
                            feed(food)
                        } else if (Anim.enabled()) {
                            f.animate().x(at[0] - base[0].toFloat()).y(at[1] - base[1].toFloat()).alpha(0f).setDuration(220)
                                .withEndAction { stage.removeView(f) }.start()
                        } else {
                            stage.removeView(f)
                        }
                    } else if (!moved && e.actionMasked == MotionEvent.ACTION_UP) {
                        view.performClick()
                    }
                }
            }
            true
        }
    }

    private fun feed(food: String) {
        when (Pets.care(Pets.Care.HAMBRE)) {
            Pets.CareResult.OK -> {
                pet.eat(food)
                host.petted()
                say("¡Ñam! $food Qué rico")
            }
            Pets.CareResult.FULL -> {
                pet.surprise()
                say("¡Ya estoy llena! 🙂")
            }
            Pets.CareResult.NO_POINTS -> {
                pet.surprise()
                host.toast("Te faltan puntos: tienes ${Points.balance()}")
            }
        }
    }

    // ---------------------------------------------------------------- Baño

    /** Jabón: se arrastra por encima de la mascota y hace espuma. */
    private fun soapTile(): View = context.label("🧼", 30f).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        background = context.lipped(Palette.SURFACE2, 16)
        isClickable = true
        contentDescription = "Jabón: arrástralo por encima de tu mascota (o tócalo para enjabonarla)"
        setOnClickListener {
            pet.foam = 0.8f
            pet.laugh(1f)
            say("¡Ahora la ducha! 🚿")
        }
        var flying: TextView? = null
        var lastX = 0f
        var lastY = 0f
        var giggled = 0L
        setOnTouchListener { view, e ->
            val base = IntArray(2)
            stage.getLocationOnScreen(base)
            val x = e.rawX - base[0]
            val y = e.rawY - base[1]
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    view.alpha = 0.35f
                    flying = context.label("🧼", 40f).apply {
                        includeFontPadding = false
                        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    stage.addView(flying, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    lastX = x
                    lastY = y
                }
                MotionEvent.ACTION_MOVE -> {
                    flying?.let { f ->
                        f.x = x - f.width / 2f
                        f.y = y - f.height / 2f
                    }
                    val onPet = x in pet.left.toFloat()..pet.right.toFloat() && y in pet.top.toFloat()..pet.bottom.toFloat()
                    if (onPet) {
                        val moved = hypot(x - lastX, y - lastY)
                        pet.foam = pet.foam + moved / (context.dp(900).toFloat())
                        val now = SystemClock.uptimeMillis()
                        if (moved > context.dp(6) && now - giggled > 1600) {
                            giggled = now
                            pet.laugh(1f)
                            if (pet.foam > 0.3f) say("¡Jaja, cosquillas! 🧼")
                        }
                    }
                    lastX = x
                    lastY = y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.alpha = 1f
                    flying?.let { stage.removeView(it) }
                    flying = null
                    if (e.actionMasked == MotionEvent.ACTION_UP && pet.foam < 0.05f && hypot(x - lastX, y - lastY) < context.dp(8)) {
                        // Tocar el jabón (sin arrastrar) la enjabona igual: es lo que hace TalkBack.
                        view.performClick()
                    } else if (pet.foam > 0.25f) {
                        say("¡Ahora la ducha! 🚿")
                    }
                }
            }
            true
        }
    }

    private fun shower() {
        backdrop.showerUntil = SystemClock.uptimeMillis() + 1800
        backdrop.invalidate()
        pet.shower()
        val soapy = pet.foam > 0.25f
        if (Anim.enabled()) {
            val start = pet.foam
            val anim = android.animation.ValueAnimator.ofFloat(start, 0f).setDuration(1200)
            anim.addUpdateListener { pet.foam = it.animatedValue as Float }
            anim.start()
        } else {
            pet.foam = 0f
        }
        if (!soapy) {
            say("¡Brr, qué fría! Pásame el jabón 🧼 primero")
            return
        }
        postDelayed({
            when (Pets.care(Pets.Care.LIMPIEZA)) {
                Pets.CareResult.OK -> {
                    host.petted()
                    say("¡Quedé limpiecita! ✨")
                }
                else -> say("¡Ya estaba limpia! ✨")
            }
        }, 1300)
    }

    // ---------------------------------------------------------------- Pieza

    private fun setLights(on: Boolean, quiet: Boolean) {
        lightsOff = !on
        removeCallbacks(sleepCare)
        pet.sleepy = lightsOff
        if (lightsOff) {
            dark.visibility = VISIBLE
            if (Anim.enabled()) {
                dark.alpha = 0f
                dark.animate().alpha(1f).setDuration(400).start()
            }
            if (!quiet) say("Buenas noches… 🌙")
            postDelayed(sleepCare, 2500)
        } else {
            dark.visibility = GONE
            if (!quiet) {
                pet.cheer()
                say("¡Buenos días! ☀️")
            }
        }
        if (!quiet && room == Room.DORMIR) buildTray()
    }

    // ---------------------------------------------------------------- Sala de juegos

    private fun playBall(ball: View) {
        if (Anim.enabled()) {
            ball.animate().translationY(-context.dp(160).toFloat()).rotationBy(360f).setDuration(320)
                .withEndAction { ball.animate().translationY(0f).setDuration(360).setInterpolator(android.view.animation.BounceInterpolator()).start() }
                .start()
        }
        pet.lookAt(pet.width / 2f, -context.dp(40).toFloat())
        postDelayed({
            pet.lookAround()
            pet.celebrate()
        }, 300)
        if (!playedBall) {
            playedBall = true
            when (Pets.care(Pets.Care.ALEGRIA)) {
                Pets.CareResult.OK -> {
                    host.petted()
                    say("¡Qué divertido! 💖")
                }
                else -> say("¡Otra, otra! 🏀")
            }
        } else {
            say(listOf("¡La atrapé!", "¡Más alto!", "¡Wiii!").random())
        }
    }

    // ---------------------------------------------------------------- Armario

    private fun itemTile(item: Wardrobe.Item): View {
        val owned = Wardrobe.owns(item)
        val wearing = Wardrobe.wearing(item)
        val locked = !owned && Points.level().number < item.level
        val cell = context.column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(context.dp(4), context.dp(6), context.dp(4), context.dp(8))
            background = context.clickable(context.lipped(if (wearing) Palette.ACCENT_SOFT else Palette.SURFACE2, 14, if (wearing) Palette.ACCENT else Palette.LIP))
            isClickable = true
            isSelected = wearing
            alpha = if (locked) 0.55f else 1f
            stateListAnimator = Anim.press()
            contentDescription = item.name + when {
                wearing -> ", puesto. Toca para sacártelo"
                owned -> ", tuyo. Toca para ponértelo"
                locked -> ", se desbloquea en el nivel ${item.level}"
                else -> ", ${item.cost} puntos"
            }
            setOnClickListener { wear(item) }
        }
        cell.addView(context.label(item.emoji, 26f).apply {
            includeFontPadding = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        cell.addView(context.label(
            when {
                wearing -> "✓ Puesto"
                owned -> "Tuyo"
                locked -> "🔒 Nv ${item.level}"
                else -> "${item.cost} pts"
            },
            11f, if (wearing) Palette.ACCENT_TEXT else if (owned) Palette.OK else Palette.YELLOW, bold = true,
        ).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(4) })
        return cell
    }

    private fun wear(item: Wardrobe.Item) {
        if (Wardrobe.owns(item)) {
            val was = Wardrobe.wearing(item)
            Wardrobe.toggle(item)
            pet.outfit = Wardrobe.outfit()
            if (!was) {
                pet.celebrate()
                say("¡Me encanta! ${item.emoji}")
            } else {
                say("Listo, me lo saqué")
            }
            buildTray()
            return
        }
        when (Wardrobe.buy(item)) {
            Wardrobe.Result.OK -> {
                pet.outfit = Wardrobe.outfit()
                pet.celebrate()
                say("¡Gracias! ${item.emoji} ¿Cómo me veo?")
                host.toast("${item.emoji} Compraste ${item.name} (−${item.cost} pts)")
            }
            Wardrobe.Result.NO_POINTS -> host.toast("Te faltan puntos: tienes ${Points.balance()}")
            Wardrobe.Result.LOCKED -> host.toast("🔒 ${item.name} se desbloquea en el nivel ${item.level}")
        }
        buildTray()
    }

    // ---------------------------------------------------------------- Háblale

    private fun micButton(): View = context.button("🎤 Mantén presionado y háblale", 0xFFFFC93C.toInt(), Palette.INK, heightDp = 54, radiusDp = 16, display = true, sizeSp = 16f) {}.apply {
        contentDescription = "Mantén presionado y háblale: repite lo que dices con su voz"
        setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    host.withMic { startListening() }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    parrot?.stop()
                }
            }
            true
        }
    }

    private fun startListening() {
        if (parrot?.busy == true) return
        val p = Parrot(onLevel = { level ->
            micLevel = 0f
            pet.listening = true
            pet.glow = if (level > 0.15f) 0xFF48DBFB.toInt() else 0
        }, onDone = {
            parrot = null
            micLevel = 0f
            pet.listening = false
            pet.glow = 0
            pet.calm()
        })
        if (!p.start()) {
            host.toast("No se pudo usar el micrófono.")
            return
        }
        parrot = p
        pet.listening = true
        say("Te escucho… 👂")
        // Cuando termina de grabar y empieza a repetir, «habla» con la boca al ritmo.
        postDelayed(object : Runnable {
            override fun run() {
                val current = parrot ?: return
                if (current.level() > 0f) {
                    pet.listening = false
                    say("🗣️ …")
                    pet.chat(5f)
                } else if (current.busy) {
                    postDelayed(this, 120)
                }
            }
        }, 200)
    }

    /** Respuestas armadas a frases que tocas (no es una IA). */
    private fun answer(text: String) {
        val reply = PHRASES.firstOrNull { it.first == text }?.second ?: return
        pet.chat(1.6f)
        say(reply)
        when (text) {
            "¿Bailamos?" -> postDelayed({ pet.dance(3f) }, 600)
            "Te quiero" -> postDelayed({ pet.love() }, 600)
            "Cuéntame un chiste" -> postDelayed({ pet.laugh() }, 1800)
            "¡Bu!" -> pet.scare()
        }
    }

    private companion object {
        val PHRASES = listOf(
            "¡Hola!" to "¡Hola! ¿Le mandamos un sonido a alguien? 🔊",
            "Te quiero" to "¡Yo también te quiero! 💕",
            "¿Bailamos?" to "¡Sí! Pon música 🎉",
            "Cuéntame un chiste" to "¿Qué le dice una iguana a su hermana gemela? ¡Somos iguanitas! 😂",
            "¿Tienes hambre?" to "Siempre tengo un huequito para comer 🍩",
            "¡Bu!" to "¡Aaah! ¡Qué susto! 😱",
        )
    }
}

/**
 * El fondo de la pieza: pared, piso, ventana y lo propio de cada una (la heladera, la tina con la
 * ducha, la lámpara, los juguetes, el espejo). Se dibuja; solo se mueve la ducha.
 */
class RoomBackdrop(context: Context) : View(context) {
    var room = PetRoom.Room.COCINA
        set(value) {
            if (field != value) {
                field = value
                shaderFor = -1
                invalidate()
            }
        }
    var showerUntil = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var wall: Shader? = null
    private var shaderFor = -1

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val d = resources.displayMetrics.density
        val (top, bottom, floor) = COLORS.getValue(room)
        if (shaderFor != height) {
            wall = LinearGradient(0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP)
            shaderFor = height
        }
        paint.shader = wall
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        val floorY = h * 0.66f
        paint.color = floor
        canvas.drawRect(0f, floorY, w, h, paint)
        paint.color = 0x22FFFFFF
        canvas.drawRect(0f, floorY, w, floorY + 3 * d, paint)
        // Ventana: de día, cielo; de noche (o en la pieza), luna y estrellas.
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val night = room == PetRoom.Room.DORMIR || hour >= 20 || hour < 7
        val wx = 22 * d
        val wy = 70 * d
        paint.color = 0xFF2A1858.toInt()
        canvas.drawRoundRect(wx - 6 * d, wy - 6 * d, wx + 86 * d, wy + 100 * d, 16 * d, 16 * d, paint)
        paint.color = if (night) 0xFF14224A.toInt() else 0xFF5FB6FF.toInt()
        canvas.drawRoundRect(wx, wy, wx + 80 * d, wy + 94 * d, 12 * d, 12 * d, paint)
        if (night) {
            paint.color = 0xFFFFF3B0.toInt()
            canvas.drawCircle(wx + 54 * d, wy + 28 * d, 12 * d, paint)
            paint.color = 0xFF14224A.toInt()
            canvas.drawCircle(wx + 60 * d, wy + 24 * d, 11 * d, paint)
            paint.color = 0xFFFFFFFF.toInt()
            for ((sx, sy) in listOf(16f to 18f, 30f to 56f, 62f to 70f, 20f to 80f)) canvas.drawCircle(wx + sx * d, wy + sy * d, 1.6f * d, paint)
        } else {
            paint.color = 0xFFFFE08A.toInt()
            canvas.drawCircle(wx + 58 * d, wy + 24 * d, 12 * d, paint)
            paint.color = 0xEEFFFFFF.toInt()
            canvas.drawRoundRect(wx + 8 * d, wy + 58 * d, wx + 48 * d, wy + 72 * d, 7 * d, 7 * d, paint)
        }
        paint.color = 0xFF2A1858.toInt()
        canvas.drawRect(wx + 37 * d, wy, wx + 43 * d, wy + 94 * d, paint)
        when (room) {
            PetRoom.Room.COCINA -> {
                // Heladera a la derecha, con imanes.
                val fx = w - 92 * d
                paint.color = 0xFFE9E4FF.toInt()
                canvas.drawRoundRect(fx, 60 * d, fx + 70 * d, floorY + 4 * d, 14 * d, 14 * d, paint)
                paint.color = 0xFFC9C0F0.toInt()
                canvas.drawRect(fx, 128 * d, fx + 70 * d, 131 * d, paint)
                canvas.drawRoundRect(fx + 56 * d, 86 * d, fx + 61 * d, 116 * d, 3 * d, 3 * d, paint)
                paint.color = 0xFFFF4FA3.toInt()
                canvas.drawCircle(fx + 20 * d, 82 * d, 5 * d, paint)
                paint.color = 0xFF3DDC97.toInt()
                canvas.drawCircle(fx + 34 * d, 92 * d, 4 * d, paint)
            }
            PetRoom.Room.BANO -> {
                // Azulejos, tina y ducha.
                paint.color = 0x14FFFFFF
                val tile = 28 * d
                var y = 0f
                while (y < floorY) {
                    canvas.drawRect(0f, y, w, y + 1.2f * d, paint)
                    y += tile
                }
                var x = 0f
                while (x < w) {
                    canvas.drawRect(x, 0f, x + 1.2f * d, floorY, paint)
                    x += tile
                }
                paint.color = 0xFFF2F7FF.toInt()
                canvas.drawRoundRect(w * 0.16f, floorY - 30 * d, w * 0.84f, floorY + 36 * d, 26 * d, 26 * d, paint)
                paint.color = 0xFFB9E3FF.toInt()
                canvas.drawRoundRect(w * 0.18f, floorY - 26 * d, w * 0.82f, floorY - 10 * d, 12 * d, 12 * d, paint)
                val sx = w * 0.72f
                paint.color = 0xFFB0B8D0.toInt()
                canvas.drawRect(sx - 2 * d, 0f, sx + 2 * d, 52 * d, paint)
                canvas.drawRoundRect(sx - 26 * d, 50 * d, sx + 8 * d, 62 * d, 6 * d, 6 * d, paint)
                if (SystemClock.uptimeMillis() < showerUntil) {
                    val t = SystemClock.uptimeMillis() / 1000f
                    paint.color = 0xCC74C0FC.toInt()
                    for (i in 0 until 16) {
                        val k = ((t * 1.8f) + i * 0.137f) % 1f
                        val dx = sx - 24 * d + (i % 8) * 4 * d - k * 40 * d * ((i % 3) - 1)
                        val dy = 64 * d + k * (floorY - 70 * d)
                        canvas.drawRoundRect(dx - 1.5f * d, dy, dx + 1.5f * d, dy + 12 * d, 2 * d, 2 * d, paint)
                    }
                    postInvalidateDelayed(33)
                }
            }
            PetRoom.Room.DORMIR -> {
                // Lámpara y cama.
                val lx = w - 70 * d
                paint.color = 0xFF2A1858.toInt()
                canvas.drawRect(lx - 2 * d, 90 * d, lx + 2 * d, floorY, paint)
                path.reset()
                path.moveTo(lx - 24 * d, 92 * d)
                path.lineTo(lx + 24 * d, 92 * d)
                path.lineTo(lx + 14 * d, 62 * d)
                path.lineTo(lx - 14 * d, 62 * d)
                path.close()
                paint.color = 0xFFFFC93C.toInt()
                canvas.drawPath(path, paint)
                paint.color = 0x22FFE08A
                path.reset()
                path.moveTo(lx - 24 * d, 92 * d)
                path.lineTo(lx + 24 * d, 92 * d)
                path.lineTo(lx + 70 * d, floorY)
                path.lineTo(lx - 70 * d, floorY)
                path.close()
                canvas.drawPath(path, paint)
                paint.color = 0xFF5A3E9E.toInt()
                canvas.drawRoundRect(w * 0.18f, floorY - 14 * d, w * 0.82f, floorY + 30 * d, 18 * d, 18 * d, paint)
                paint.color = 0xFFE9E4FF.toInt()
                canvas.drawRoundRect(w * 0.2f, floorY - 22 * d, w * 0.36f, floorY - 4 * d, 9 * d, 9 * d, paint)
            }
            PetRoom.Room.JUGAR -> {
                // Banderines y cajas de juguetes.
                val colors = intArrayOf(0xFFFF4FA3.toInt(), 0xFFFFC93C.toInt(), 0xFF48DBFB.toInt(), 0xFF3DDC97.toInt(), 0xFFB44CFF.toInt())
                var x = 0f
                var i = 0
                while (x < w) {
                    path.reset()
                    path.moveTo(x, 34 * d + sin(x / w * 3.14f) * 10 * d)
                    path.lineTo(x + 28 * d, 34 * d + sin((x + 28 * d) / w * 3.14f) * 10 * d)
                    path.lineTo(x + 14 * d, 58 * d + sin((x + 14 * d) / w * 3.14f) * 10 * d)
                    path.close()
                    paint.color = colors[i % colors.size]
                    canvas.drawPath(path, paint)
                    x += 32 * d
                    i++
                }
                paint.color = 0xFF3DDC97.toInt()
                canvas.drawRoundRect(w - 96 * d, floorY - 46 * d, w - 30 * d, floorY + 6 * d, 10 * d, 10 * d, paint)
                paint.color = 0xFFFFC93C.toInt()
                canvas.drawCircle(w - 46 * d, floorY - 58 * d, 14 * d, paint)
            }
            PetRoom.Room.ARMARIO -> {
                // Espejo grande detrás.
                paint.color = 0xFFFFC93C.toInt()
                canvas.drawRoundRect(w / 2f - 96 * d, 40 * d, w / 2f + 96 * d, floorY + 2 * d, 90 * d, 90 * d, paint)
                paint.color = 0xFF8FB8FF.toInt()
                canvas.drawRoundRect(w / 2f - 88 * d, 48 * d, w / 2f + 88 * d, floorY - 4 * d, 84 * d, 84 * d, paint)
                paint.color = 0x55FFFFFF
                canvas.drawRoundRect(w / 2f - 70 * d, 66 * d, w / 2f - 50 * d, 150 * d, 10 * d, 10 * d, paint)
            }
            PetRoom.Room.HABLALE -> {
                // Escenario con micrófono y luces.
                paint.color = 0x33FFC93C
                path.reset()
                path.moveTo(w / 2f - 30 * d, 0f)
                path.lineTo(w / 2f + 30 * d, 0f)
                path.lineTo(w / 2f + 150 * d, floorY)
                path.lineTo(w / 2f - 150 * d, floorY)
                path.close()
                canvas.drawPath(path, paint)
                paint.color = 0xFF2A1858.toInt()
                canvas.drawRect(w - 54 * d, 120 * d, w - 50 * d, floorY, paint)
                paint.color = 0xFF9A8FD0.toInt()
                canvas.drawRoundRect(w - 62 * d, 96 * d, w - 42 * d, 126 * d, 10 * d, 10 * d, paint)
            }
        }
    }

    private companion object {
        val COLORS = mapOf(
            PetRoom.Room.COCINA to Triple(0xFF5A2E6E.toInt(), 0xFF3A1E5C.toInt(), 0xFF7A4BB8.toInt()),
            PetRoom.Room.BANO to Triple(0xFF1E4C7A.toInt(), 0xFF173A66.toInt(), 0xFF2E6AA6.toInt()),
            PetRoom.Room.DORMIR to Triple(0xFF1A1440.toInt(), 0xFF120E30.toInt(), 0xFF2A2060.toInt()),
            PetRoom.Room.JUGAR to Triple(0xFF1E4A3A.toInt(), 0xFF173A30.toInt(), 0xFF2E6A50.toInt()),
            PetRoom.Room.ARMARIO to Triple(0xFF5A1A4A.toInt(), 0xFF3A1236.toInt(), 0xFF7A2E66.toInt()),
            PetRoom.Room.HABLALE to Triple(0xFF3A2275.toInt(), 0xFF2A1858.toInt(), 0xFF4B2C8F.toInt()),
        )
    }
}
