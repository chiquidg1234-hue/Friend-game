package com.friendgame.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity(), Store.Listener {

    private companion object {
        const val REQ_PICK_AUDIO = 10
        const val REQ_NOTIFICATIONS = 11
        const val REQ_SCAN = 12
        const val REQ_MIC = 13

        /** Tiempo durante el que se muestra el botón para detener el sonido que mandaste. */
        const val STOP_WINDOW_MS = 15_000L
        const val SEND_COOLDOWN_MS = 1_200L
        /**
         * Página para instalar, no el .apk directo: abierto desde WhatsApp o un lector de QR, el
         * .apk se descarga en una ventanita del navegador y no aparece el instalador. La página
         * explica cómo pasar a Chrome y ahí tiene el enlace de descarga.
         */
        const val INSTALL_PAGE = "https://github.com/chiquidg1234-hue/Friend-game/releases/latest"

        const val STATE_TAB = "tab"
        const val STATE_TARGET = "target"
        const val STATE_SUPPORT = "support"
    }

    private enum class Tab(val title: String, val iconRes: Int) {
        SOUND("Sonar", R.drawable.ic_nav_sound),
        GROUPS("Grupos", R.drawable.ic_nav_groups),
        FRIENDS("Amigos", R.drawable.ic_nav_friends),
        SETTINGS("Ajustes", R.drawable.ic_nav_settings),
    }

    private class Sent(val topic: String, val toName: String, val playbackId: String, val label: String)

    /** A quién va un sonido: un amigo ("f:código") o un grupo ("g:id"). */
    private class Target(val key: String, val name: String, val topic: String, val groupId: String?)

    private lateinit var root: FrameLayout
    private lateinit var shell: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var bars: LinearLayout
    private lateinit var nav: LinearLayout
    private lateinit var overlay: FrameLayout
    private var welcome: View? = null

    private val ui = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private var tab = Tab.SOUND
    private var supportOpen = false
    private var donationAmount: Int? = Donations.DEFAULT_AMOUNT
    private var selectedTarget: String? = null
    private var lastSent: Sent? = null
    private var lastSendAt = 0L
    private var renderPosted = false
    private var renderedScreen = ""
    private var systemBars = IntArray(4)
    private var imeBottom = 0
    private var progressFill: View? = null
    private var cancelRecording: (() -> Unit)? = null
    /** OnBackInvokedCallback (Android 13+); se guarda como Any para no cargar la clase antes. */
    private var backCallback: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Notifs.createChannels(this)
        goEdgeToEdge()
        if (savedInstanceState != null) {
            tab = Tab.entries.getOrNull(savedInstanceState.getInt(STATE_TAB)) ?: Tab.SOUND
            selectedTarget = savedInstanceState.getString(STATE_TARGET)
            supportOpen = savedInstanceState.getBoolean(STATE_SUPPORT)
        }
        openTabFrom(intent)

        root = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }
        buildShell()
        root.setOnApplyWindowInsetsListener { _, insets ->
            readInsets(insets)
            applyInsets()
            insets
        }
        setContentView(root)

        if (Store.myName.isBlank()) showWelcome() else onReady()
    }

    private fun onReady() {
        ListenService.start(this)
        askNotificationPermission()
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (openTabFrom(intent)) render()
    }

    /** Los avisos de amistad o de grupos abren directo su pestaña. */
    private fun openTabFrom(intent: Intent?): Boolean {
        val name = intent?.getStringExtra(Notifs.EXTRA_TAB) ?: return false
        intent.removeExtra(Notifs.EXTRA_TAB)
        tab = Tab.entries.firstOrNull { it.name == name } ?: return false
        supportOpen = false
        return true
    }

    override fun onStart() {
        super.onStart()
        Store.addListener(this)
        render()
        Updater.check(this)
    }

    override fun onResume() {
        super.onResume()
        if (Store.myName.isNotBlank()) ListenService.start(this)
    }

    override fun onStop() {
        Store.removeListener(this)
        ui.removeCallbacks(progressTick)
        // Android no deja usar el micrófono en segundo plano: la grabación se cancela.
        cancelRecording?.invoke()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tab.ordinal)
        outState.putString(STATE_TARGET, selectedTarget)
        outState.putBoolean(STATE_SUPPORT, supportOpen)
    }

    /** Girar la pantalla o cambiar el tamaño no recrea la app (ver el manifiesto): se redibuja. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        renderedScreen = ""
        render()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        io.shutdown()
        super.onDestroy()
    }

    override fun onStoreChanged() {
        if (renderPosted) return
        renderPosted = true
        ui.post {
            renderPosted = false
            render()
        }
    }

    /** Atrás: corta el sonido que llegó, cierra "Apoyar" o vuelve a la pestaña Sonar. */
    private fun handleBack(): Boolean {
        when {
            welcome != null -> return false
            overlay.visibility == View.VISIBLE -> Player.stop()
            supportOpen -> closeSupport()
            tab != Tab.SOUND -> openTab(Tab.SOUND)
            else -> return false
        }
        return true
    }

    @Deprecated("Se usa mientras la app no active el gesto de atrás predictivo")
    override fun onBackPressed() {
        if (!handleBack()) super.onBackPressed()
    }

    /**
     * Con el gesto de atrás predictivo (obligatorio al apuntar a Android 16) ya no se llama a
     * onBackPressed: se registra un callback solo mientras haya adónde volver.
     */
    private fun updateBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return
        val needed = welcome == null && (overlay.visibility == View.VISIBLE || supportOpen || tab != Tab.SOUND)
        if (needed && backCallback == null) {
            val callback = OnBackInvokedCallback { handleBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        } else if (!needed) {
            (backCallback as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            backCallback = null
        }
    }

    // ------------------------------------------------------------------ Estructura

    /** Contenido con scroll, barras de "detener" y la navegación de abajo; encima, el aviso de sonido. */
    private fun buildShell() {
        scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        content = column()
        scroll.addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
        bars = column()
        nav = row().apply {
            setBackgroundColor(Palette.NAV)
            gravity = Gravity.TOP
        }
        shell = column()
        shell.addView(scroll, lp(MATCH, 0, weight = 1f))
        shell.addView(bars, lp())
        shell.addView(divider(Palette.LINE), lp(MATCH, dp(1)))
        shell.addView(nav, lp())
        overlay = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Palette.OVERLAY)
            isClickable = true
            isFocusable = true
        }
        root.addView(shell, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun readInsets(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            systemBars = intArrayOf(bars.left, bars.top, bars.right, bars.bottom)
            imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
        } else {
            @Suppress("DEPRECATION")
            systemBars = intArrayOf(
                insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
            )
            imeBottom = 0
        }
    }

    private fun applyInsets() {
        val (left, top, right, bottom) = systemBars
        shell.setPadding(left, top, right, 0)
        nav.setPadding(dp(8), dp(8), dp(8), dp(12) + bottom)
        overlay.setPadding(left, top, right, bottom)
        welcome?.setPadding(left, top, right, maxOf(bottom, imeBottom))
    }

    // ------------------------------------------------------------------ Pantalla

    private fun render() {
        if (welcome != null) return
        val screen = if (supportOpen) "support" else tab.name
        val sameScreen = screen == renderedScreen
        val y = scroll.scrollY
        content.removeAllViews()
        content.setPadding(dp(20), dp(20), dp(20), dp(28))
        if (supportOpen) {
            renderSupport()
        } else {
            when (tab) {
                Tab.SOUND -> renderSound()
                Tab.GROUPS -> renderGroups()
                Tab.FRIENDS -> renderFriends()
                Tab.SETTINGS -> renderSettings()
            }
        }
        renderBars()
        renderNav()
        renderOverlay()
        renderedScreen = screen
        if (sameScreen) scroll.post { scroll.scrollTo(0, y) } else scroll.scrollTo(0, 0)
        updateBackCallback()
    }

    private fun openTab(target: Tab) {
        if (target == tab && !supportOpen) {
            scroll.smoothScrollTo(0, 0)
            return
        }
        supportOpen = false
        tab = target
        render()
    }

    private fun openSupport() {
        supportOpen = true
        tab = Tab.SETTINGS
        render()
    }

    private fun closeSupport() {
        supportOpen = false
        render()
    }

    private fun add(view: View, topDp: Int = 0) {
        content.addView(view, lp(topDp = topDp))
    }

    private fun screenTitle(text: String, subtitle: String? = null) {
        add(title(text, 30f))
        if (subtitle != null) add(label(subtitle, 14f, Palette.SUB), 2)
    }

    /** Título de sección con una ayuda chiquita a la derecha. */
    private fun sectionTitle(text: String, hint: String? = null): View = row().apply {
        gravity = Gravity.BOTTOM
        addView(title(text, 20f, weight = 600), lp(0, WRAP, weight = 1f))
        if (hint != null) addView(label(hint, 12f, Palette.SUB), LinearLayout.LayoutParams(WRAP, WRAP))
    }

    private fun grid(tiles: List<View>, columns: Int = 3) {
        tiles.chunked(columns).forEach { group ->
            val line = row()
            group.forEachIndexed { i, tile ->
                if (i > 0) line.addView(space(10))
                line.addView(tile, lp(0, WRAP, weight = 1f))
            }
            repeat(columns - group.size) {
                line.addView(space(10))
                line.addView(View(this), lp(0, 1, weight = 1f))
            }
            add(line, 10)
        }
    }

    /** Aviso corto con una acción a la derecha. */
    private fun alert(lead: View, titleText: String, text: String, action: String, actionColor: Int, onAction: () -> Unit) {
        val box = row().apply {
            background = rounded(Palette.SURFACE, 20)
            setPadding(dp(14), dp(12), dp(12), dp(12))
        }
        box.addView(lead)
        val texts = column()
        texts.addView(label(titleText, 15f, bold = true), lp())
        texts.addView(label(text, 13f, Palette.SUB), lp(topDp = 2))
        box.addView(texts, lp(0, WRAP, weight = 1f).apply {
            marginStart = dp(12)
            marginEnd = dp(10)
        })
        box.addView(
            button(action, actionColor, Palette.INK, heightDp = 40, radiusDp = 999, sizeSp = 14f, onClick = onAction),
            LinearLayout.LayoutParams(WRAP, WRAP),
        )
        add(box, 12)
    }

    private fun currentProblem(): String =
        Store.lastProblem.ifEmpty { SoundCheck.silentReason(this) ?: "" }.replaceFirstChar { it.uppercase() }

    private fun batteryExempt(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true

    private fun notificationsAllowed(): Boolean =
        getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: true

    private fun addFooter() {
        add(
            label(
                "Cada quien manda en su propio celular: siempre puedes detener un sonido, " +
                    "silenciar o bloquear a alguien, o pausar la recepción.",
                12f, Palette.SUB,
            ).apply { gravity = Gravity.CENTER },
            24,
        )
    }

    // ------------------------------------------------------------------ Pestaña Sonar

    private fun renderSound() {
        val friends = Store.friends()
        val groups = Store.groups()

        val header = row()
        header.addView(avatar(Store.myName, Palette.YELLOW, sizeDp = 44))
        val hello = column()
        hello.addView(title("Hola, ${Store.myName}", 22f, weight = 600).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp())
        hello.addView(label("¿A quién le das un susto hoy?", 13f, Palette.SUB), lp())
        header.addView(hello, lp(0, WRAP, weight = 1f).apply {
            marginStart = dp(12)
            marginEnd = dp(8)
        })
        header.addView(statusPill())
        add(header)

        addUpdateCard()
        val incoming = friends.filter { it.state == Friend.INCOMING }
        incoming.firstOrNull()?.let { first ->
            val more = if (incoming.size > 1) " (y ${incoming.size - 1} más)" else ""
            alert(
                avatar(first.name, Palette.avatar(first.code)),
                "${first.name} quiere ser tu amigo$more", "Acepta para que se puedan mandar sonidos.",
                "Ver", Palette.ACCENT,
            ) { openTab(Tab.FRIENDS) }
        }
        if (Store.receiving) {
            val problem = currentProblem()
            if (problem.isNotEmpty()) {
                alert(icon(R.drawable.ic_warning, Palette.YELLOW, 24), "Los sonidos no se van a oír", problem, "Revisar", Palette.YELLOW) {
                    checkSound()
                }
            }
            if (!batteryExempt()) {
                alert(
                    icon(R.drawable.ic_battery, Palette.OK, 24), "Para que suene con la pantalla apagada",
                    "Deja que Friend Game siga escuchando aunque no la estés usando.", "Permitir", Palette.OK,
                ) { askBatteryExemption() }
            }
        }

        val all = targets(friends.filter { it.state == Friend.FRIEND }, groups)
        if (all.none { it.key == selectedTarget }) selectedTarget = all.firstOrNull()?.key
        val target = all.firstOrNull { it.key == selectedTarget }
        if (target == null) addNoTargets() else addTargetChips(all, target)

        add(sectionTitle("Sonidos", "Mantén presionado para oírlo tú"), 22)
        grid(Sounds.all.map { sound ->
            soundTile(sound.emoji, sound.label, sound.color,
                onClick = {
                    if (target == null) Player.preview(this, Player.Source.Asset(sound.asset), sound.label)
                    else sendSound(target, sound.key, sound.label, null)
                },
                onLongClick = { Player.preview(this, Player.Source.Asset(sound.asset), sound.label) },
            )
        })

        add(sectionTitle("Tus audios"), 22)
        val mine = Store.customSounds().map { custom ->
            val source = Player.Source.FromFile(custom.file(this))
            soundTile("🎤", custom.label, Palette.CUSTOM,
                onClick = {
                    if (target == null) Player.preview(this, source, custom.label)
                    else sendSound(target, "custom", custom.label, custom)
                },
                onLongClick = { customOptions(custom) },
            )
        } + addTile(R.drawable.ic_mic, "Grabar") { recordAudio() } +
            addTile(R.drawable.ic_upload, "Subir") { pickAudio() }
        grid(mine)
        val hint = if (Store.customSounds().isEmpty()) "Graba tu voz o sube un audio: ${CustomAudio.HINT}."
        else "${CustomAudio.HINT}. Mantén presionado uno tuyo para escucharlo, cambiarle el nombre o borrarlo."
        add(label(hint, 12f, Palette.SUB), 8)

        addHistory()
        addFooter()
    }

    private fun statusPill(): View {
        val (text, color) = when {
            !Store.receiving -> "En pausa" to Palette.SUB
            ListenService.connected -> "En línea" to Palette.OK
            else -> "Conectando…" to Palette.YELLOW
        }
        return row().apply {
            setPadding(dp(10), dp(6), dp(12), dp(6))
            background = clickable(rounded(Palette.SURFACE, 999))
            isClickable = true
            contentDescription = "Estado: $text. Abre los ajustes de recepción"
            addView(View(context).apply { background = oval(color) }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
            addView(label(text, 12f, color, bold = true))
            setOnClickListener { openTab(Tab.SETTINGS) }
        }
    }

    private fun addUpdateCard() {
        val release = Updater.release ?: return
        val state = Updater.state
        if (state == Updater.State.IDLE || state == Updater.State.CHECKING) return
        val box = card(Palette.SURFACE2, 20)
        box.addView(title("Versión nueva: ${release.versionName}", 18f, weight = 600), lp())
        box.addView(label(Updater.message, 13f, Palette.SUB), lp(topDp = 4))
        when (state) {
            Updater.State.READY -> box.addView(
                button("Actualizar ahora", Palette.OK, iconRes = R.drawable.ic_upload, heightDp = 48) { Updater.install(this) },
                lp(topDp = 10),
            )
            Updater.State.FAILED -> box.addView(
                button("Reintentar", Palette.SURFACE, Palette.TEXT, heightDp = 48) { Updater.check(this, force = true) },
                lp(topDp = 10),
            )
            else -> Unit
        }
        add(box, 14)
    }

    private fun targets(friends: List<Friend>, groups: List<Group>): List<Target> =
        friends.map { Target("f:${it.code}", it.name, Relay.topicFor(it.code), null) } +
            groups.map { Target("g:${it.id}", it.name, Relay.groupTopic(it.id), it.id) }

    private fun addNoTargets() {
        val box = card()
        box.addView(title("Todavía no tienes a quién mandarle", 18f, weight = 600), lp())
        box.addView(
            label("Agrega a un amigo con su código o únete a un grupo. Mientras, toca un sonido para escucharlo tú.", 13f, Palette.SUB),
            lp(topDp = 4),
        )
        val actions = row()
        actions.addView(button("Agregar amigo", heightDp = 44, radiusDp = 14, sizeSp = 14f) {
            openTab(Tab.FRIENDS)
            showAddFriend()
        }, lp(0, WRAP, weight = 1f))
        actions.addView(space(8))
        actions.addView(button("Unirme a un grupo", Palette.SURFACE2, Palette.TEXT, heightDp = 44, radiusDp = 14, sizeSp = 14f) {
            openTab(Tab.GROUPS)
        }, lp(0, WRAP, weight = 1f))
        box.addView(actions, lp(topDp = 12))
        add(box, 20)
    }

    private fun addTargetChips(all: List<Target>, target: Target) {
        add(caps("Para"), 20)
        val chips = row()
        var selectedChip: View? = null
        all.forEach { option ->
            val selected = option.key == target.key
            val chip = row().apply {
                setPadding(dp(6), dp(6), dp(16), dp(6))
                background = clickable(rounded(if (selected) Palette.ACCENT else Palette.SURFACE, 999))
                isClickable = true
                contentDescription = "Mandar a ${option.name}" + if (selected) ", elegido" else ""
                val bubble = if (selected) Palette.INK else Palette.SURFACE2
                val fg = if (selected) Palette.ACCENT else Palette.SUB
                if (option.groupId != null) {
                    val icon = FrameLayout(context).apply { background = oval(bubble) }
                    icon.addView(icon(R.drawable.ic_nav_groups, fg, 18), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                    addView(icon, LinearLayout.LayoutParams(dp(32), dp(32)))
                } else {
                    addView(avatar(option.name, bubble, fg, 32))
                }
                addView(
                    label(option.name, 15f, if (selected) Palette.INK else Palette.TEXT, bold = true).apply { maxLines = 1 },
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) },
                )
                setOnClickListener {
                    selectedTarget = option.key
                    render()
                }
            }
            if (selected) selectedChip = chip
            chips.addView(chip, LinearLayout.LayoutParams(WRAP, dp(44)).apply { marginEnd = dp(8) })
        }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }
        add(scroller, 8)
        // Que el elegido quede a la vista aunque haya muchos.
        selectedChip?.let { chip -> scroller.post { scroller.scrollTo(maxOf(0, chip.left - dp(40)), 0) } }
        val who = if (target.groupId != null) "al grupo ${target.name}" else "a ${target.name}"
        add(label("Toca un sonido para mandárselo $who.", 13f, Palette.SUB), 8)
    }

    private fun addHistory() {
        val history = Store.history()
        if (history.isEmpty()) return
        add(sectionTitle("Te llegaron hace poco"), 24)
        val box = card(padDp = 0)
        val now = System.currentTimeMillis()
        history.take(5).forEachIndexed { i, entry ->
            if (i > 0) box.addView(divider(), dividerLp())
            val line = row().apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
            line.addView(icon(R.drawable.ic_history, Palette.SUB, 18))
            val texts = column()
            texts.addView(label(entry.text, 14f, bold = true), lp())
            texts.addView(
                label(DateUtils.getRelativeTimeSpanString(entry.time, now, DateUtils.MINUTE_IN_MILLIS), 12f, Palette.SUB),
                lp(),
            )
            line.addView(texts, lp(0, WRAP, weight = 1f).apply { marginStart = dp(12) })
            box.addView(line, lp())
        }
        add(box, 10)
    }

    /** Efecto al tocar una fila, sin salirse de ella. */
    private fun rowRipple(): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), null, ColorDrawable(Color.WHITE))

    private fun dividerLp(): LinearLayout.LayoutParams = lp(MATCH, dp(1)).apply {
        marginStart = dp(16)
        marginEnd = dp(16)
    }

    // ------------------------------------------------------------------ Pestaña Grupos

    private fun renderGroups() {
        val groups = Store.groups()
        screenTitle("Grupos", "Un sonido le llega a todos a la vez.")
        if (groups.isEmpty()) {
            val box = card()
            box.addView(title("Arma tu primer grupo", 18f, weight = 600), lp())
            box.addView(
                label(
                    "Crea uno para tu familia o tus amigos e invítalos con un código QR. " +
                        "Si te invitaron, escanea su QR o pega el enlace.",
                    13f, Palette.SUB,
                ),
                lp(topDp = 4),
            )
            add(box, 16)
        }
        groups.forEach { addGroupCard(it) }
        add(
            button(
                "Crear grupo", Palette.YELLOW, Palette.INK, iconRes = R.drawable.ic_plus,
                heightDp = 56, radiusDp = 18, display = true, sizeSp = 18f,
            ) { createGroup() },
            18,
        )
        val joins = row()
        joins.addView(
            button("Escanear QR", Palette.SURFACE, Palette.TEXT, iconRes = R.drawable.ic_scan, sizeSp = 14f, iconColor = Palette.ACCENT_TEXT) {
                scanInvite()
            },
            lp(0, WRAP, weight = 1f),
        )
        joins.addView(space(8))
        joins.addView(
            button("Pegar enlace", Palette.SURFACE, Palette.TEXT, iconRes = R.drawable.ic_link, sizeSp = 14f, iconColor = Palette.ACCENT_TEXT) {
                pasteInvite()
            },
            lp(0, WRAP, weight = 1f),
        )
        add(joins, 8)
    }

    private fun addGroupCard(group: Group) {
        val box = card()
        val head = row()
        val badge = FrameLayout(this).apply {
            background = rounded(if (group.isAdmin) Palette.YELLOW else Palette.avatar(group.id), 16)
        }
        badge.addView(icon(R.drawable.ic_nav_groups, Palette.INK, 26), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        head.addView(badge, LinearLayout.LayoutParams(dp(48), dp(48)))
        val texts = column()
        texts.addView(title(group.name, 19f, weight = 600).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp())
        val count = group.members.size
        val adminName = group.members.firstOrNull { it.code == group.adminCode }?.name
        val subtitle = buildString {
            append("$count ${if (count == 1) "miembro" else "miembros"}")
            if (!group.isAdmin && adminName != null) append(" · creado por $adminName")
            if (group.muted) append(" · silenciado")
        }
        texts.addView(label(subtitle, 13f, Palette.SUB), lp())
        head.addView(texts, lp(0, WRAP, weight = 1f).apply {
            marginStart = dp(12)
            marginEnd = dp(6)
        })
        if (group.isAdmin) {
            head.addView(label("Admin", 12f, Palette.YELLOW, bold = true).apply {
                background = rounded(Palette.BADGE, 999)
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(4) })
        }
        head.addView(iconButton(R.drawable.ic_more, "Opciones de ${group.name}", 0, Palette.SUB) { groupOptions(group) })
        box.addView(head, lp())

        val people = row()
        group.members.take(5).forEachIndexed { i, member ->
            val face = avatar(member.name, Palette.avatar(member.code), sizeDp = 34).apply {
                background = oval(Palette.avatar(member.code), Palette.SURFACE)
            }
            people.addView(face, LinearLayout.LayoutParams(dp(34), dp(34)).apply { if (i > 0) marginStart = -dp(10) })
        }
        val names = group.members.sortedBy { it.code != Store.myCode }.map { if (it.code == Store.myCode) "Tú" else it.name }
        people.addView(label(joinNames(names), 13f, Palette.SUB).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, lp(0, WRAP, weight = 1f).apply { marginStart = dp(10) })
        box.addView(people, lp(topDp = 14))

        val actions = row()
        actions.addView(button("Mandar sonido", heightDp = 44, radiusDp = 14, sizeSp = 14f) {
            selectedTarget = "g:${group.id}"
            openTab(Tab.SOUND)
        }, lp(0, WRAP, weight = 1f))
        actions.addView(space(8))
        actions.addView(
            button("Invitar", Palette.SURFACE2, Palette.TEXT, iconRes = R.drawable.ic_qr, heightDp = 44, radiusDp = 14, sizeSp = 14f) {
                showInvite(group)
            },
            lp(0, WRAP, weight = 1f),
        )
        box.addView(actions, lp(topDp = 14))
        add(box, 14)
    }

    private fun joinNames(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " y " + names.last()
    }

    // ------------------------------------------------------------------ Pestaña Amigos

    private fun renderFriends() {
        val friends = Store.friends()
        screenTitle("Amigos")

        val codeCard = card(Palette.YELLOW, 24, 18)
        codeCard.addView(caps("Tu código de amigo", Palette.INK), lp())
        codeCard.addView(title(Codes.pretty(Store.myCode), 26f, Palette.INK).apply {
            letterSpacing = 0.04f
            maxLines = 1
            setTextIsSelectable(true)
            setAutoSizeTextTypeUniformWithConfiguration(14, 26, 1, TypedValue.COMPLEX_UNIT_SP)
        }, lp(MATCH, dp(38), topDp = 6))
        codeCard.addView(label("Pásaselo solo a quien quieras que te pueda mandar sonidos.", 13f, Palette.INK), lp(topDp = 2))
        val codeButtons = row()
        codeButtons.addView(
            button("Compartir", Palette.INK, Palette.TEXT, iconRes = R.drawable.ic_share, heightDp = 44, radiusDp = 14, sizeSp = 14f) {
                shareCode()
            },
            lp(0, WRAP, weight = 1f),
        )
        codeButtons.addView(space(8))
        codeButtons.addView(
            button(
                "Copiar", Palette.YELLOW, Palette.INK, iconRes = R.drawable.ic_copy, heightDp = 44, radiusDp = 14,
                sizeSp = 14f, strokeColor = Palette.INK,
            ) { copyCode() },
            lp(0, WRAP, weight = 1f),
        )
        codeCard.addView(codeButtons, lp(topDp = 12))
        add(codeCard, 16)

        friends.filter { it.state == Friend.INCOMING }.forEach { friend ->
            val box = card()
            val head = row()
            head.addView(avatar(friend.name, Palette.avatar(friend.code), sizeDp = 44))
            val texts = column()
            texts.addView(label("${friend.name} quiere ser tu amigo", 16f, bold = true), lp())
            texts.addView(label("Si aceptas, se podrán mandar sonidos.", 13f, Palette.SUB), lp())
            head.addView(texts, lp(0, WRAP, weight = 1f).apply { marginStart = dp(12) })
            box.addView(head, lp())
            val actions = row()
            actions.addView(button("Aceptar", Palette.OK, Palette.INK, heightDp = 44, radiusDp = 14, sizeSp = 14f) {
                acceptRequest(friend)
            }, lp(0, WRAP, weight = 1f))
            actions.addView(space(8))
            actions.addView(button("Rechazar", Palette.SURFACE2, Palette.TEXT, heightDp = 44, radiusDp = 14, sizeSp = 14f) {
                Store.removeFriend(friend.code)
            }, lp(0, WRAP, weight = 1f))
            actions.addView(space(8))
            actions.addView(button("Bloquear", Palette.SURFACE2, Palette.DANGER_TEXT, heightDp = 44, radiusDp = 14, sizeSp = 14f) {
                block(friend)
            }, lp(0, WRAP, weight = 1f))
            box.addView(actions, lp(topDp = 12))
            add(box, 12)
        }

        val order = listOf(Friend.FRIEND, Friend.OUTGOING, Friend.BLOCKED)
        val others = friends.filter { it.state != Friend.INCOMING }.sortedBy { order.indexOf(it.state) }
        add(caps("Tus amigos · ${others.count { it.state == Friend.FRIEND }}"), 20)
        if (others.isEmpty()) {
            add(label("Todavía no tienes amigos aquí. Pásale tu código a alguien o agrega el suyo.", 14f, Palette.SUB), 8)
        } else {
            val list = card(padDp = 0)
            others.forEachIndexed { i, friend ->
                if (i > 0) list.addView(divider(), dividerLp())
                list.addView(friendRow(friend), lp())
            }
            add(list, 8)
        }

        val actions = row()
        actions.addView(button("Agregar amigo", iconRes = R.drawable.ic_plus) { showAddFriend() }, lp(0, WRAP, weight = 1f))
        actions.addView(space(8))
        actions.addView(
            button("Pasar la app", Palette.SURFACE, Palette.TEXT, iconRes = R.drawable.ic_share, iconColor = Palette.OK) { shareApp() },
            lp(0, WRAP, weight = 1f),
        )
        add(actions, 16)
        add(
            label("«Pasar la app»: se la mandas por Quick Share o Bluetooth y la instala sin navegador.", 12f, Palette.SUB)
                .apply { gravity = Gravity.CENTER },
            8,
        )
    }

    private fun friendRow(friend: Friend): View {
        val line = row().apply {
            setPadding(dp(16), dp(10), dp(6), dp(10))
            background = rowRipple()
            isClickable = true
        }
        val active = friend.state == Friend.FRIEND
        line.addView(
            avatar(friend.name, if (active) Palette.avatar(friend.code) else Palette.SURFACE2, if (active) Palette.INK else Palette.SUB, 40),
        )
        val texts = column()
        texts.addView(label(friend.name, 16f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp())
        val subtitle = when (friend.state) {
            Friend.FRIEND -> if (friend.muted) "Silenciado: no te suenan sus sonidos" else "Toca para mandarle un sonido"
            Friend.OUTGOING -> "Esperando que acepte tu solicitud"
            else -> "Bloqueado"
        }
        texts.addView(label(subtitle, 13f, Palette.SUB), lp())
        line.addView(texts, lp(0, WRAP, weight = 1f).apply {
            marginStart = dp(12)
            marginEnd = dp(4)
        })
        line.addView(iconButton(R.drawable.ic_more, "Opciones de ${friend.name}", 0, Palette.SUB) { friendOptions(friend) })
        line.setOnClickListener {
            if (active) {
                selectedTarget = "f:${friend.code}"
                openTab(Tab.SOUND)
            } else {
                friendOptions(friend)
            }
        }
        line.setOnLongClickListener {
            friendOptions(friend)
            true
        }
        return line
    }

    // ------------------------------------------------------------------ Pestaña Ajustes

    private fun renderSettings() {
        screenTitle("Ajustes")
        add(caps("Cuando te mandan un sonido"), 18)
        val box = card(padDp = 0)
        val (status, statusColor) = when {
            !Store.receiving -> "En pausa: nadie te puede hacer sonar" to Palette.SUB
            ListenService.connected -> "En línea · listo para recibir" to Palette.OK
            else -> "Conectando…" to Palette.YELLOW
        }
        box.addView(switchRow("Recibir sonidos", status, statusColor, Store.receiving) { checked ->
            Store.receiving = checked
            if (checked) {
                ListenService.start(this)
            } else {
                Player.stop()
                ListenService.stop(this)
            }
        }, lp())
        box.addView(divider(), dividerLp())
        box.addView(
            switchRow(
                "Sonar aunque esté en silencio",
                if (Store.ringInSilent) "Usa el volumen de alarma: suena en silencio y en vibración"
                else "Usa el volumen multimedia: en silencio puede no oírse",
                Palette.SUB, Store.ringInSilent,
            ) { Store.ringInSilent = it },
            lp(),
        )
        val problem = if (Store.receiving) currentProblem() else ""
        if (problem.isNotEmpty()) {
            val warning = row().apply { setPadding(dp(16), dp(4), dp(16), dp(10)) }
            warning.addView(icon(R.drawable.ic_warning, Palette.YELLOW, 20))
            warning.addView(
                label("$problem: los sonidos no se van a oír.", 13f, Palette.YELLOW, bold = true),
                lp(0, WRAP, weight = 1f).apply { marginStart = dp(10) },
            )
            box.addView(warning, lp())
        }
        box.addView(
            button(
                "Probar y revisar el sonido", Palette.SURFACE2, Palette.TEXT, iconRes = R.drawable.ic_nav_sound,
                heightDp = 48, radiusDp = 14, iconColor = Palette.ACCENT_TEXT,
            ) { checkSound() },
            lp().apply { setMargins(dp(16), dp(4), dp(16), dp(16)) },
        )
        add(box, 8)

        val support = row().apply {
            background = clickable(rounded(Palette.ACCENT, 24), 0x40150C2C)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
            setOnClickListener { openSupport() }
        }
        val heart = FrameLayout(this).apply { background = rounded(Palette.INK, 16) }
        heart.addView(icon(R.drawable.ic_heart, Palette.ACCENT, 24), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        support.addView(heart, LinearLayout.LayoutParams(dp(48), dp(48)))
        val supportTexts = column()
        supportTexts.addView(title("Apoya Friend Game", 18f, Palette.INK), lp())
        supportTexts.addView(label("Es gratis y sin anuncios. Ayuda a mantenerla.", 13f, Palette.INK, bold = true), lp())
        support.addView(supportTexts, lp(0, WRAP, weight = 1f).apply {
            marginStart = dp(14)
            marginEnd = dp(8)
        })
        support.addView(icon(R.drawable.ic_chevron, Palette.INK, 18))
        add(support, 14)

        add(caps("Tu celular"), 20)
        val rows = card(padDp = 0)
        fun item(name: String, value: String, valueColor: Int, onClick: () -> Unit) {
            if (rows.childCount > 0) rows.addView(divider(), dividerLp())
            val line = row().apply {
                setPadding(dp(16), dp(14), dp(14), dp(14))
                background = rowRipple()
                isClickable = true
                setOnClickListener { onClick() }
            }
            line.addView(label(name, 15f, bold = true), lp(0, WRAP, weight = 1f))
            line.addView(label(value, 13f, valueColor, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(170)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
            line.addView(icon(R.drawable.ic_chevron, Palette.FAINT, 16))
            rows.addView(line, lp())
        }
        item("Tu nombre", Store.myName, Palette.SUB) { askName() }
        val exempt = batteryExempt()
        item("Pantalla apagada", if (exempt) "Permitido" else "Permitir", if (exempt) Palette.OK else Palette.YELLOW) {
            if (exempt) info("Pantalla apagada", "Listo: Friend Game puede seguir escuchando con la pantalla apagada.")
            else askBatteryExemption()
        }
        val notifications = notificationsAllowed()
        item("Notificaciones", if (notifications) "Activadas" else "Activar", if (notifications) Palette.OK else Palette.YELLOW) {
            if (notifications || Build.VERSION.SDK_INT < 33) openNotificationSettings() else askNotificationPermission()
        }
        val version = Updater.currentVersionName(this)
        val (versionText, versionColor) = when {
            Updater.state == Updater.State.CHECKING -> "Buscando…" to Palette.SUB
            Updater.release != null -> "$version · hay una nueva" to Palette.YELLOW
            else -> "$version · buscar" to Palette.SUB
        }
        item("Versión", versionText, versionColor) { Updater.check(this, force = true) }
        item("Servidor", Uri.parse(Store.server).host ?: Store.server, Palette.SUB) { editServer() }
        add(rows, 8)
        if (Updater.message.isNotEmpty() && Updater.release == null) add(label(Updater.message, 13f, Palette.SUB), 8)

        add(button("Cambiar mi código (borra amigos y grupos)", Palette.BG, Palette.DANGER_TEXT, heightDp = 48, sizeSp = 14f) {
            confirmResetCode()
        }, 12)
        addFooter()
    }

    private fun switchRow(name: String, status: String, statusColor: Int, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val line = row().apply { setPadding(dp(16), dp(14), dp(12), dp(14)) }
        val texts = column()
        texts.addView(label(name, 16f, bold = true), lp())
        texts.addView(label(status, 13f, statusColor, bold = statusColor != Palette.SUB), lp())
        line.addView(texts, lp(0, WRAP, weight = 1f).apply { marginEnd = dp(10) })
        line.addView(toggle(checked, name, onChange), LinearLayout.LayoutParams(WRAP, WRAP))
        return line
    }

    // ------------------------------------------------------------------ Apoyar (donaciones)

    private fun renderSupport() {
        val top = row()
        top.addView(iconButton(R.drawable.ic_back, "Volver a Ajustes", Palette.SURFACE) { closeSupport() })
        add(top)

        val hero = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val heart = FrameLayout(this).apply { background = rounded(Palette.ACCENT, 40) }
        heart.addView(icon(R.drawable.ic_heart, Palette.INK, 60), FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER))
        hero.addView(heart, LinearLayout.LayoutParams(dp(112), dp(112)))
        hero.addView(title("Apoya Friend Game", 30f).apply { gravity = Gravity.CENTER }, lp(topDp = 14))
        hero.addView(
            label(
                "Friend Game es gratis, sin anuncios y no vende tus datos. Si te saca risas, " +
                    "una donación ayuda a mantenerla y a agregar sonidos nuevos.",
                15f, Palette.SUB,
            ).apply { gravity = Gravity.CENTER },
            lp(topDp = 8),
        )
        add(hero, 4)

        if (Donations.choosesAmount) {
            add(caps("Elige cuánto"), 22)
            val amounts = row()
            (Donations.AMOUNTS + listOf<Int?>(null)).forEachIndexed { i, amount ->
                if (i > 0) amounts.addView(space(8))
                val selected = amount == donationAmount
                val text = if (amount != null) "$amount USD" else "Otro"
                val option = button(
                    text, if (selected) Palette.YELLOW else Palette.SURFACE, if (selected) Palette.INK else Palette.TEXT,
                    heightDp = 56, display = true, sizeSp = 17f,
                ) {
                    donationAmount = amount
                    render()
                }
                option.isSelected = selected
                amounts.addView(option, lp(0, WRAP, weight = 1f))
            }
            add(amounts, 8)
        }
        val amount = if (Donations.choosesAmount) donationAmount else null
        add(
            button(
                if (amount != null) "Donar $amount USD" else "Donar", iconRes = R.drawable.ic_heart,
                heightDp = 56, radiusDp = 18, display = true, sizeSp = 18f,
            ) { donate(amount) },
            14,
        )
        add(
            label(
                if (Donations.enabled) "Se abre ${Donations.host()} para pagar de forma segura." else "Las donaciones se activan muy pronto.",
                12f, Palette.SUB,
            ).apply { gravity = Gravity.CENTER },
            8,
        )

        val other = card()
        other.addView(title("Otras formas de ayudar", 17f, weight = 600), lp())
        fun way(iconRes: Int, color: Int, text: String, onClick: (() -> Unit)?) {
            val line = row().apply {
                setPadding(0, dp(8), 0, dp(8))
                if (onClick != null) {
                    background = rowRipple()
                    isClickable = true
                    setOnClickListener { onClick() }
                }
            }
            line.addView(icon(iconRes, color, 20))
            line.addView(label(text, 15f, bold = true), lp(0, WRAP, weight = 1f).apply { marginStart = dp(12) })
            other.addView(line, lp(topDp = 4))
        }
        way(R.drawable.ic_share, Palette.OK, "Pásasela a tus amigos") { shareApp() }
        way(R.drawable.ic_star, Palette.YELLOW, "Déjale una reseña cuando esté en Play Store", null)
        add(other, 20)
        add(
            label("Las donaciones son voluntarias y no desbloquean nada: la app es igual para todos.", 12f, Palette.SUB)
                .apply { gravity = Gravity.CENTER },
            18,
        )
    }

    private fun donate(amount: Int?) {
        if (!Donations.enabled) {
            AlertDialog.Builder(this)
                .setTitle("¡Gracias por querer apoyar! 💜")
                .setMessage("Las donaciones se activan muy pronto. Mientras, la mejor ayuda es pasarle la app a tus amigos.")
                .setPositiveButton("Pasar la app") { _, _ -> shareApp() }
                .setNegativeButton("Cerrar", null)
                .show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Donations.link(amount)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            toast("No encontré un navegador para abrir la página de donación.")
        }
    }

    // ------------------------------------------------------------------ Barras y navegación

    /** Encima de la navegación: detener lo que suena en tu celular o lo que acabas de mandar. */
    private fun renderBars() {
        bars.removeAllViews()
        val now = Player.now
        if (now != null && now.isPreview) {
            addBar("Sonando: ${now.label}", "Lo estás escuchando tú", "Detener lo que suena") { Player.stop() }
        }
        lastSent?.let { sent ->
            addBar("Mandaste «${sent.label}» a ${sent.toName}", "¿Te pasaste? Puedes cortarlo", "Detener mi sonido") {
                stopMySound(sent)
            }
        }
        bars.visibility = if (bars.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun addBar(titleText: String, subtitle: String, stopDescription: String, onStop: () -> Unit) {
        val bar = row().apply {
            background = rounded(Palette.SURFACE2, 20)
            setPadding(dp(16), dp(10), dp(10), dp(10))
        }
        val texts = column()
        texts.addView(label(titleText, 15f, bold = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, lp())
        texts.addView(label(subtitle, 12f, Palette.SUB), lp())
        bar.addView(texts, lp(0, WRAP, weight = 1f).apply { marginEnd = dp(10) })
        val stop = button("Detener", Palette.DANGER, Palette.TEXT, iconRes = R.drawable.ic_stop, heightDp = 44, radiusDp = 999, sizeSp = 14f, onClick = onStop)
        stop.contentDescription = stopDescription
        bar.addView(stop, LinearLayout.LayoutParams(WRAP, WRAP))
        bars.addView(bar, lp().apply { setMargins(dp(12), dp(6), dp(12), dp(8)) })
    }

    private fun renderNav() {
        nav.removeAllViews()
        val requests = Store.friends().count { it.state == Friend.INCOMING }
        Tab.entries.forEach { item ->
            val selected = item == tab
            val cell = column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(2), 0, dp(2))
                background = RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), null, null)
                isClickable = true
                isSelected = selected
                contentDescription = "Pestaña ${item.title}" +
                    if (item == Tab.FRIENDS && requests > 0) ", $requests ${if (requests == 1) "solicitud" else "solicitudes"}" else ""
                setOnClickListener { openTab(item) }
            }
            val pill = FrameLayout(this).apply { if (selected) background = rounded(Palette.ACCENT_SOFT, 16) }
            pill.addView(
                icon(item.iconRes, if (selected) Palette.ACCENT_TEXT else Palette.SUB, 22),
                FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER),
            )
            if (item == Tab.FRIENDS && requests > 0) {
                val badge = label(if (requests > 9) "9+" else requests.toString(), 10f, Palette.INK, bold = true).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    background = oval(Palette.YELLOW)
                }
                pill.addView(badge, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply { marginEnd = dp(8) })
            }
            cell.addView(pill, LinearLayout.LayoutParams(dp(56), dp(32)))
            cell.addView(label(item.title, 12f, if (selected) Palette.ACCENT_TEXT else Palette.SUB).apply {
                typeface = Fonts.body(context, if (selected) 800 else 700)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
            nav.addView(cell, lp(0, WRAP, weight = 1f))
        }
    }

    // ------------------------------------------------------------------ Te llegó un sonido

    /** Mientras suena algo que te mandaron, ocupa toda la pantalla con el botón para detenerlo. */
    private fun renderOverlay() {
        ui.removeCallbacks(progressTick)
        progressFill = null
        overlay.removeAllViews()
        val now = Player.now
        if (now == null || now.isPreview) {
            overlay.visibility = View.GONE
            shell.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            return
        }
        overlay.visibility = View.VISIBLE
        shell.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        val sound = Sounds.byKey(now.soundKey)
        val friend = Store.friend(now.fromCode)
        val sender = friend?.name ?: now.fromName.substringBefore(" · ")
        val page = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
        }
        page.addView(View(this), lp(MATCH, 0, weight = 1f))
        page.addView(caps("Te llegó un sonido").apply { textSize = 13f }, LinearLayout.LayoutParams(WRAP, WRAP))

        val stage = FrameLayout(this)
        stage.addView(View(this).apply { background = oval(0, Palette.LINE) }, FrameLayout.LayoutParams(dp(260), dp(260), Gravity.CENTER))
        stage.addView(View(this).apply { background = oval(0, Palette.BADGE) }, FrameLayout.LayoutParams(dp(206), dp(206), Gravity.CENTER))
        val tile = label(sound?.emoji ?: "🎤", 72f).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = rounded(sound?.color ?: Palette.CUSTOM, 44)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        stage.addView(tile, FrameLayout.LayoutParams(dp(150), dp(150), Gravity.CENTER))
        page.addView(stage, LinearLayout.LayoutParams(dp(260), dp(260)))

        page.addView(title(now.label, 36f).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, lp())
        val from = row().apply {
            background = rounded(Palette.SURFACE, 999)
            setPadding(dp(8), dp(8), dp(16), dp(8))
        }
        from.addView(avatar(sender, Palette.avatar(now.fromCode), sizeDp = 32))
        from.addView(label("de ${now.fromName}", 16f, bold = true), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) })
        page.addView(from, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(12) })

        page.addView(View(this), lp(MATCH, dp(32), weight = 1f))
        page.addView(
            button("Detener", Palette.DANGER, Palette.TEXT, iconRes = R.drawable.ic_stop, heightDp = 64, radiusDp = 22, display = true, sizeSp = 22f) {
                Player.stop()
            },
            lp(),
        )
        val muteText = if (now.groupId != null) "Silenciar el grupo" else "Silenciar a $sender"
        page.addView(button(muteText, Palette.SURFACE, Palette.TEXT, iconRes = R.drawable.ic_mute, heightDp = 52, radiusDp = 18) {
            muteSender(now)
        }, lp(topDp = 12))
        page.addView(
            label("Se corta solo a los 10 segundos. Tú decides, siempre.", 13f, Palette.SUB).apply { gravity = Gravity.CENTER },
            lp(topDp = 12),
        )
        val track = FrameLayout(this).apply { background = rounded(Palette.LINE, 3) }
        val fill = View(this).apply { background = rounded(Palette.ACCENT, 3) }
        track.addView(fill, FrameLayout.LayoutParams(0, MATCH))
        page.addView(track, lp(MATCH, dp(6), topDp = 24))
        progressFill = fill

        val pageScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(page, ViewGroup.LayoutParams(MATCH, WRAP))
        }
        overlay.addView(pageScroll, FrameLayout.LayoutParams(MATCH, MATCH))
        ui.post(progressTick)
    }

    private val progressTick = object : Runnable {
        override fun run() {
            val fill = progressFill ?: return
            val now = Player.now ?: return
            val track = fill.parent as? View ?: return
            val elapsed = SystemClock.elapsedRealtime() - now.startedAt
            val fraction = (elapsed.toFloat() / now.durationMs).coerceIn(0f, 1f)
            val params = fill.layoutParams
            params.width = (track.width * fraction).toInt()
            fill.layoutParams = params
            ui.postDelayed(this, 50)
        }
    }

    private fun muteSender(now: Player.NowPlaying) {
        val groupId = now.groupId
        if (groupId != null) {
            Store.group(groupId)?.let { Store.putGroup(it.copy(muted = true)) }
        } else {
            Store.friend(now.fromCode)?.let { Store.putFriend(it.copy(muted = true)) }
        }
        Player.stop()
        toast("Silenciado. Para volver a oírlo, búscalo en sus opciones.")
    }

    // ------------------------------------------------------------------ Bienvenida

    private fun showWelcome() {
        shell.visibility = View.GONE
        val view = buildWelcome()
        welcome = view
        root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        applyInsets()
    }

    private fun finishWelcome(name: String) {
        Store.myName = name
        welcome?.let {
            hideKeyboard(it)
            root.removeView(it)
        }
        welcome = null
        shell.visibility = View.VISIBLE
        onReady()
    }

    private fun buildWelcome(): View {
        val page = column().apply { setPadding(dp(24), dp(24), dp(24), dp(24)) }

        val art = FrameLayout(this)
        fun floating(emoji: String, color: Int, sizeDp: Int, gravity: Int, marginXDp: Int, marginYDp: Int, angle: Float) {
            val tile = label(emoji, sizeDp * 0.42f).apply {
                this.gravity = Gravity.CENTER
                includeFontPadding = false
                background = rounded(color, sizeDp * 3 / 8)
                rotation = angle
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            art.addView(tile, FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), gravity).apply {
                setMargins(dp(marginXDp), dp(marginYDp), dp(marginXDp), dp(marginYDp))
            })
        }
        floating("📯", Palette.YELLOW, 64, Gravity.TOP or Gravity.START, 12, 10, -10f)
        floating("🦆", Palette.OK, 58, Gravity.TOP or Gravity.END, 10, 24, 9f)
        floating("🔔", 0xFF54A0FF.toInt(), 56, Gravity.BOTTOM or Gravity.START, 34, 2, 8f)
        floating("🎉", 0xFFA29BFE.toInt(), 52, Gravity.BOTTOM or Gravity.END, 28, 10, -8f)
        val logo = FrameLayout(this).apply { background = rounded(Palette.ACCENT, 44) }
        logo.addView(icon(R.drawable.ic_nav_sound, Palette.INK, 72), FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
        art.addView(logo, FrameLayout.LayoutParams(dp(132), dp(132), Gravity.CENTER))
        page.addView(art, lp(MATCH, dp(230)))

        page.addView(title("Hazle sonar el celular a tus amigos", 32f).apply { gravity = Gravity.CENTER }, lp(topDp = 16))
        page.addView(
            label("Una bocina, un pato, una sirena… les llega al instante, aunque tengan la pantalla apagada.", 15f, Palette.SUB)
                .apply { gravity = Gravity.CENTER },
            lp(topDp = 8),
        )

        page.addView(caps("¿Cómo te llamas?"), lp(topDp = 24))
        val input = EditText(this).apply {
            hint = "Tu nombre"
            setSingleLine(true)
            textSize = 18f
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.FAINT)
            typeface = Fonts.body(context, 700)
            background = rounded(Palette.SURFACE, 18, Palette.ACCENT)
            setPadding(dp(18), 0, dp(18), 0)
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(24))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        page.addView(input, lp(MATCH, dp(56), topDp = 8))
        page.addView(label("Es lo que verán tus amigos cuando les suene algo tuyo.", 13f, Palette.SUB), lp(topDp = 8))

        val promise = row().apply {
            background = rounded(Palette.SURFACE, 20)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        promise.addView(icon(R.drawable.ic_shield, Palette.OK, 22))
        val promiseTexts = column()
        promiseTexts.addView(label("Cada quien manda en su celular.", 13f, bold = true), lp())
        promiseTexts.addView(
            label("Solo te suenan los amigos que aceptes y siempre puedes detener, silenciar o bloquear.", 13f, Palette.SUB),
            lp(),
        )
        promise.addView(promiseTexts, lp(0, WRAP, weight = 1f).apply { marginStart = dp(12) })
        page.addView(promise, lp(topDp = 18))

        page.addView(View(this), lp(MATCH, 0, weight = 1f))
        val start = {
            val name = input.text.toString().trim()
            if (name.isEmpty()) input.error = "Escribe tu nombre" else finishWelcome(name)
        }
        page.addView(button("Empezar", heightDp = 58, radiusDp = 20, display = true, sizeSp = 20f) { start() }, lp(topDp = 20))
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                start()
                true
            } else {
                false
            }
        }
        page.addView(
            label("Sin cuenta, sin número de teléfono y sin anuncios.", 12f, Palette.SUB).apply { gravity = Gravity.CENTER },
            lp(topDp = 10),
        )

        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Palette.BG)
            addView(page, ViewGroup.LayoutParams(MATCH, WRAP))
        }
    }

    private fun hideKeyboard(view: View) {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    // ------------------------------------------------------------------ Acciones

    /** Hace sonar una sirena igual que un sonido recibido y muestra qué podría estar impidiéndolo. */
    private fun checkSound() {
        val sirena = Sounds.byKey("sirena") ?: return
        Player.play(
            this, Player.Source.Asset(sirena.asset),
            Player.NowPlaying(Codes.random(12), "", "Prueba", sirena.label), incoming = true,
        )
        val items = SoundCheck.items(this)
        val text = items.joinToString("\n\n") { (if (it.ok) "✅ " else "⚠️ ") + it.text }
        val allOk = items.all { it.ok }
        AlertDialog.Builder(this)
            .setTitle(if (allOk) "Revisión del sonido: todo bien" else "Revisión del sonido")
            .setMessage(
                "Debería estar sonando una sirena, igual que cuando te mandan un sonido.\n\n$text" +
                    if (allOk) "" else "\n\nArregla lo marcado con ⚠️ y vuelve a probar."
            )
            .setPositiveButton("Ajustar volumen") { _, _ -> openVolume() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun openVolume() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startActivity(Intent(Settings.Panel.ACTION_VOLUME))
                return
            }
        } catch (_: Exception) {
        }
        val stream = if (SoundCheck.useAlarm(this)) AudioManager.STREAM_ALARM else AudioManager.STREAM_MUSIC
        getSystemService(AudioManager::class.java)
            ?.adjustStreamVolume(stream, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
    }

    private fun openNotificationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (_: Exception) {
            toast("Búscalo en Ajustes → Notificaciones → Friend Game.")
        }
    }

    private fun copyCode() {
        copyText("Código Friend Game", Codes.pretty(Store.myCode))
        toast("Código copiado")
    }

    private fun copyText(labelText: String, text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(labelText, text))
    }

    /** Comparte el propio APK: con Quick Share llega al otro celular y se instala sin navegador. */
    private fun shareApp() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType(ApkProvider.MIME)
            .putExtra(Intent.EXTRA_STREAM, ApkProvider.URI)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(ApkProvider.FILE_NAME, ApkProvider.URI)
        toast("Elige Quick Share y el celular de tu amigo")
        try {
            startActivity(Intent.createChooser(intent, "Pasar Friend Game"))
        } catch (_: ActivityNotFoundException) {
            toast("No encontré cómo compartir archivos en este celular.")
        }
    }

    private fun shareCode() {
        val text = "¡Juguemos Friend Game! 🔊 Agrégame con mi código: ${Codes.pretty(Store.myCode)}\n" +
            "Instala la app (Android, ábrelo en Chrome): $INSTALL_PAGE"
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(intent, "Compartir mi código"))
    }

    private fun sendSound(target: Target, kind: String, label: String, custom: CustomSound?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSendAt < SEND_COOLDOWN_MS) return
        lastSendAt = now
        val playbackId = Codes.random(12)
        val payload = Relay.payload("snd").put("id", playbackId).put("s", kind).put("label", label)
        target.groupId?.let { payload.put("g", it) }
        val file = custom?.file(this)
        if (file != null && !file.exists()) {
            toast("No encuentro ese audio, súbelo otra vez.")
            return
        }
        val toName = if (target.groupId != null) "el grupo ${target.name}" else target.name
        runInBackground({
            if (file != null) Relay.publishFileTopic(target.topic, payload, file) else Relay.publishTopic(target.topic, payload)
        }) {
            val sent = Sent(target.topic, toName, playbackId, label)
            lastSent = sent
            render()
            ui.postDelayed({
                if (lastSent === sent) {
                    lastSent = null
                    render()
                }
            }, STOP_WINDOW_MS)
        }
    }

    private fun stopMySound(sent: Sent) {
        lastSent = null
        render()
        val payload = Relay.payload("stop").put("id", sent.playbackId)
        runInBackground({ Relay.publishTopic(sent.topic, payload) }) { toast("Listo, se detuvo tu sonido") }
    }

    private fun customOptions(custom: CustomSound) {
        val options = listOf<Pair<String, () -> Unit>>(
            "▶️ Escucharlo" to { Player.preview(this, Player.Source.FromFile(custom.file(this)), custom.label) },
            "✏️ Cambiar nombre" to { renameCustom(custom) },
            "🗑 Borrar" to { deleteCustom(custom) },
        )
        AlertDialog.Builder(this)
            .setTitle(custom.label)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    // ------------------------------------------------------------------ Grupos

    private fun createGroup() {
        textDialog("Crear grupo", "¿Cómo se llama? Lo verán todos los que se unan.", "", "Nombre del grupo", "Crear") { name ->
            val group = Groups.create(name)
            selectedTarget = "g:${group.id}"
            showInvite(group)
            true
        }
    }

    private fun showInvite(group: Group) {
        val link = Invites.link(group)
        val size = dp(220)
        val image = ImageView(this).apply {
            setImageBitmap(Qr.bitmap(link, size))
            background = rounded(0xFFFFFFFF.toInt(), 24)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "Código QR para unirse a ${group.name}"
        }
        val body = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(8), dp(22), dp(8))
            addView(image, LinearLayout.LayoutParams(size + dp(24), size + dp(24)))
            addView(
                label(
                    "Que lo escanee desde Friend Game → Grupos → «Escanear QR». " +
                        "Si no tiene la app, al escanearlo con la cámara le abre la página para instalarla.",
                    14f,
                ).apply { gravity = Gravity.CENTER },
                lp(topDp = 14),
            )
            addView(label(link, 10f, Palette.SUB).apply { setTextIsSelectable(true) }, lp(topDp = 8))
            if (group.isAdmin) {
                addView(label("Si sacas a alguien, este QR deja de servir y se crea uno nuevo.", 12f, Palette.SUB), lp(topDp = 8))
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Invitar a «${group.name}»")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Compartir enlace") { _, _ -> shareInvite(group) }
            .setNeutralButton("Copiar") { _, _ ->
                copyText("Invitación Friend Game", link)
                toast("Enlace copiado")
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun shareInvite(group: Group) {
        val text = "Únete a mi grupo «${group.name}» en Friend Game 🔊\n" +
            "En la app: Grupos → «Pegar enlace» y pega esto:\n${Invites.link(group)}\n" +
            "¿No tienes la app? Abre el enlace en Chrome para instalarla."
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(intent, "Invitar al grupo"))
    }

    private fun scanInvite() {
        try {
            startActivityForResult(Intent(this, ScanActivity::class.java), REQ_SCAN)
        } catch (_: ActivityNotFoundException) {
            pasteInvite()
        }
    }

    private fun pasteInvite() {
        val input = editText(clipboardText()?.takeIf { "#g=" in it } ?: "", "Pega aquí el enlace de invitación").apply {
            // Es un enlace: sin autocorrector, que lo rompería.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(600))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Unirme con un enlace")
            .setMessage("Pega el enlace de invitación que te mandaron.")
            .setView(padded(input))
            .setPositiveButton("Unirme", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val invite = Invites.parse(input.text.toString())
                if (invite == null) {
                    input.error = "Ese enlace no es una invitación de Friend Game."
                } else {
                    dialog.dismiss()
                    confirmJoin(invite)
                }
            }
        }
        dialog.show()
    }

    private fun confirmJoin(invite: Invites.Invite) {
        if (Store.group(invite.groupId) != null) {
            toast("Ya estás en «${invite.name}»")
            return
        }
        confirm(
            "¿Unirte a «${invite.name}»?",
            "Las personas del grupo podrán mandarte sonidos. Puedes silenciarlo o salirte cuando quieras.",
        ) {
            val group = Groups.join(invite)
            selectedTarget = "g:${group.id}"
            tab = Tab.GROUPS
            supportOpen = false
            render()
            runInBackground({ Groups.announceJoin(group) }) { toast("Te uniste a «${group.name}»") }
        }
    }

    private fun groupOptions(group: Group) {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        options += "📱 Invitar con QR" to { showInvite(group) }
        options += "👥 Ver miembros" to { showMembers(group) }
        options += (if (group.muted) "🔊 Volver a recibir sus sonidos" else "🔇 Silenciar el grupo") to {
            Store.putGroup(group.copy(muted = !group.muted))
        }
        if (group.isAdmin) {
            options += "✏️ Cambiar nombre" to {
                textDialog("Cambiar nombre", null, group.name, "Nombre del grupo", "Guardar") { name ->
                    runInBackground({ Groups.rename(group, name) }) {}
                    true
                }
            }
            options += "🗑 Cerrar el grupo" to {
                confirm("¿Cerrar «${group.name}»?", "El grupo desaparece para todos.") {
                    runInBackground({ Groups.close(group) }) { toast("Grupo cerrado") }
                }
            }
        } else {
            options += "🚪 Salir del grupo" to {
                confirm("¿Salir de «${group.name}»?", "Ya no recibirás sus sonidos. Para volver necesitas otra invitación.") {
                    runInBackground({ Groups.leave(group) }) { toast("Saliste de «${group.name}»") }
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(group.name)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showMembers(group: Group) {
        val members = group.members.sortedBy { it.code != group.adminCode }
        val labels = members.map { member ->
            member.name +
                (if (member.code == group.adminCode) " · admin" else "") +
                (if (member.code == Store.myCode) " (tú)" else "")
        }
        AlertDialog.Builder(this)
            .setTitle(if (group.isAdmin) "Miembros · toca a alguien para sacarlo" else "Miembros de «${group.name}»")
            .setItems(labels.toTypedArray()) { _, which ->
                val member = members[which]
                if (group.isAdmin && member.code != Store.myCode) confirmKick(group, member)
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun confirmKick(group: Group, member: Member) {
        confirm(
            "¿Sacar a ${member.name} del grupo?",
            "Ya no podrá mandar ni recibir sonidos del grupo. El QR anterior deja de servir: para invitar a más gente, muestra el nuevo.",
        ) {
            runInBackground({ Groups.remove(group, member) }) { toast("${member.name} ya no está en «${group.name}»") }
        }
    }

    // ------------------------------------------------------------------ Amigos

    private fun acceptRequest(friend: Friend) {
        Store.putFriend(friend.copy(state = Friend.FRIEND))
        selectedTarget = "f:${friend.code}"
        val payload = Relay.payload("acc")
        runInBackground({ Relay.publish(friend.code, payload) }) {
            toast("🎉 Ahora tú y ${friend.name} se pueden mandar sonidos")
        }
    }

    private fun block(friend: Friend) {
        Store.putFriend(friend.copy(state = Friend.BLOCKED, muted = true))
        Player.stopIfFrom(friend.code)
        val payload = Relay.payload("bye")
        runInBackground({ Relay.publish(friend.code, payload) }) {}
    }

    private fun removeFriend(friend: Friend) {
        Store.removeFriend(friend.code)
        Player.stopIfFrom(friend.code)
        if (friend.state == Friend.BLOCKED) return
        val payload = Relay.payload("bye")
        runInBackground({ Relay.publish(friend.code, payload) }) {}
    }

    private fun sendRequest(code: String) {
        val payload = Relay.payload("req")
        runInBackground({ Relay.publish(code, payload) }) {
            toast("📨 Solicitud enviada. Cuando la acepte, se podrán mandar sonidos.")
        }
    }

    private fun friendOptions(friend: Friend) {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        when (friend.state) {
            Friend.FRIEND -> {
                options += (if (friend.muted) "🔊 Volver a recibir sus sonidos" else "🔇 Silenciar sus sonidos") to {
                    Store.putFriend(friend.copy(muted = !friend.muted))
                    if (!friend.muted) Player.stopIfFrom(friend.code)
                }
                options += "✏️ Cambiar nombre" to { renameFriend(friend) }
                options += "👋 Eliminar amigo" to {
                    confirm("¿Eliminar a ${friend.name}?", "Ya no se podrán mandar sonidos hasta que se vuelvan a agregar.") {
                        removeFriend(friend)
                    }
                }
                options += "🚫 Bloquear" to { block(friend) }
            }
            Friend.OUTGOING -> {
                options += "📨 Reenviar solicitud" to { sendRequest(friend.code) }
                options += "✖️ Cancelar solicitud" to { removeFriend(friend) }
            }
            Friend.INCOMING -> {
                options += "✅ Aceptar" to { acceptRequest(friend) }
                options += "✖️ Rechazar" to { Store.removeFriend(friend.code) }
                options += "🚫 Bloquear" to { block(friend) }
            }
            Friend.BLOCKED -> options += "✅ Desbloquear (quitar de la lista)" to { Store.removeFriend(friend.code) }
        }
        AlertDialog.Builder(this)
            .setTitle(friend.name)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun renameFriend(friend: Friend) {
        textDialog("Cambiar nombre", null, friend.name, "Nombre", "Guardar") { name ->
            Store.putFriend(friend.copy(name = name))
            true
        }
    }

    private fun showAddFriend() {
        val codeInput = editText("", "Su código: ABCD-EFGH-…").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(24))
        }
        val nameInput = editText("", "Su nombre (opcional)").apply { filters = arrayOf<InputFilter>(InputFilter.LengthFilter(24)) }
        clipboardCode()?.let { codeInput.setText(Codes.pretty(it)) }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Agregar amigo")
            .setMessage("Escribe o pega el código que te pasó. Le llegará una solicitud y, cuando la acepte, se podrán mandar sonidos.")
            .setView(padded(codeInput, nameInput))
            .setPositiveButton("Mandar solicitud", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = Codes.normalize(codeInput.text.toString())
                when {
                    code == null -> codeInput.error = "Ese código no es válido: son 16 letras y números."
                    code == Store.myCode -> codeInput.error = "Ese es tu propio código 😄"
                    else -> {
                        addFriend(code, nameInput.text.toString().trim())
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun addFriend(code: String, typedName: String) {
        val existing = Store.friend(code)
        if (existing != null && existing.state == Friend.FRIEND) {
            toast("Ya son amigos 🙂")
            return
        }
        if (existing != null && existing.state == Friend.INCOMING) {
            acceptRequest(existing)
            return
        }
        val previousName = if (existing != null && existing.state == Friend.OUTGOING) existing.name else null
        val name = typedName.ifEmpty { previousName ?: Friend.PLACEHOLDER_NAME }
        Store.putFriend(Friend(code, name, Friend.OUTGOING))
        sendRequest(code)
    }

    private fun clipboardText(): String? = try {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else null
    } catch (_: Exception) {
        null
    }

    private fun clipboardCode(): String? = try {
        val text = clipboardText()
        val found = text?.let { Regex("[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}").find(it)?.value }
        Codes.normalize(found)?.takeIf { it != Store.myCode }
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------------ Audios propios

    private fun pickAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, CustomAudio.PICKER_MIME_TYPES)
        try {
            startActivityForResult(intent, REQ_PICK_AUDIO)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("audio/*"), REQ_PICK_AUDIO)
            } catch (_: ActivityNotFoundException) {
                toast("No encontré cómo elegir archivos en este celular.")
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCAN && resultCode == RESULT_OK) {
            val invite = Invites.parse(data?.getStringExtra(ScanActivity.EXTRA_TEXT))
            if (invite == null) info("Ese QR no es de Friend Game", "Pide que te muestren el QR desde Grupos → Invitar.")
            else confirmJoin(invite)
            return
        }
        if (requestCode != REQ_PICK_AUDIO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        toast("Revisando el audio…")
        runInBackground({
            val imported = try {
                CustomAudio.import(this, uri)
            } catch (e: CustomAudio.ImportError) {
                ui.post { info("No se pudo agregar", e.message ?: "") }
                return@runInBackground
            } catch (_: Exception) {
                ui.post { info("No se pudo agregar", "No se pudo leer ese archivo.") }
                return@runInBackground
            }
            ui.post { nameNewAudio(imported) }
        }) {}
    }

    private fun nameNewAudio(imported: CustomAudio.Imported) {
        if (isFinishing || isDestroyed) {
            CustomAudio.fileFor(this, imported.sound).delete()
            return
        }
        val saved = booleanArrayOf(false)
        val dialog = textDialog("¿Cómo se llama este audio?", CustomAudio.HINT, imported.suggestedLabel, "Nombre", "Guardar") { name ->
            Store.putCustomSound(imported.sound.copy(label = name))
            saved[0] = true
            toast("🎤 Audio agregado: ya puedes mandarlo")
            true
        }
        dialog.setOnDismissListener {
            if (!saved[0]) CustomAudio.fileFor(this, imported.sound).delete()
        }
    }

    private fun renameCustom(custom: CustomSound) {
        textDialog("Cambiar nombre", null, custom.label, "Nombre", "Guardar") { name ->
            Store.putCustomSound(custom.copy(label = name))
            true
        }
    }

    private fun deleteCustom(custom: CustomSound) {
        confirm("¿Borrar «${custom.label}»?", null) { CustomAudio.delete(this, custom) }
    }

    private fun askName() {
        textDialog("Cambiar mi nombre", "Así te verán tus amigos.", Store.myName, "Tu nombre", "Listo") { name ->
            Store.myName = name
            true
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) recordAudio()
            else toast("Sin permiso de micrófono no se puede grabar. Puedes subir un archivo.")
        }
        render()
    }

    /** Graba hasta 8 segundos con el micrófono y lo guarda como audio propio. */
    private fun recordAudio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (cancelRecording != null) return
        val sound = CustomSound(Codes.random(10), "", "m4a")
        val file = sound.file(this)
        val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(96_000)
            recorder.setAudioSamplingRate(44_100)
            recorder.setMaxDuration(CustomAudio.MAX_MS.toInt())
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
        } catch (_: Exception) {
            recorder.release()
            file.delete()
            toast("No se pudo usar el micrófono.")
            return
        }
        val startedAt = SystemClock.elapsedRealtime()
        val status = title("Grabando… 0 s", 22f, weight = 600)
        var finished = false
        lateinit var dialog: AlertDialog
        val tick = object : Runnable {
            override fun run() {
                if (finished) return
                val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
                status.text = "Grabando… $seconds s de 8"
                ui.postDelayed(this, 250)
            }
        }
        fun finish(keep: Boolean) {
            if (finished) return
            finished = true
            cancelRecording = null
            ui.removeCallbacks(tick)
            val ok = try {
                recorder.stop()
                true
            } catch (_: RuntimeException) {
                false // se detuvo antes de grabar algo
            }
            recorder.release()
            if (dialog.isShowing) dialog.dismiss()
            if (keep && ok && file.length() > 0) {
                nameNewAudio(CustomAudio.Imported(sound, "Mi grabación"))
            } else {
                file.delete()
                if (keep) toast("No se grabó nada. Revisa que el micrófono funcione y vuelve a intentarlo.")
            }
        }
        recorder.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) ui.post { finish(keep = true) }
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("Grabar audio")
            .setView(padded(status, label("Se detiene sola a los 8 segundos.", 13f, Palette.SUB)))
            .setCancelable(false)
            .setPositiveButton("⏹ Listo", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { finish(keep = true) }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { finish(keep = false) }
        }
        cancelRecording = { finish(keep = false) }
        dialog.show()
        ui.post(tick)
    }

    private fun askBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                toast("Búscalo en Ajustes → Batería → Friend Game → Sin restricciones.")
            }
        }
    }

    private fun editServer() {
        textDialog(
            "Servidor",
            "Avanzado. Tú y tus amigos deben usar el mismo servidor ntfy. Por defecto: ${Store.DEFAULT_SERVER}",
            Store.server, "https://…", "Guardar",
        ) { value ->
            if (!value.startsWith("https://")) {
                toast("Tiene que empezar con https://")
                false
            } else {
                Store.server = value
                ListenService.restart(this)
                true
            }
        }
    }

    private fun confirmResetCode() {
        confirm(
            "¿Cambiar tu código?",
            "Se borrarán tus amigos y grupos, y quien tenga tu código viejo ya no podrá escribirte. Útil si tu código llegó a alguien que no querías.",
        ) {
            // Los avisos se arman antes de cambiar el código: llevan el código viejo, que es el
            // que conocen tus amigos y los grupos.
            val goodbyes = Store.friends()
                .filter { it.state == Friend.FRIEND || it.state == Friend.OUTGOING }
                .map { Relay.topicFor(it.code) to Relay.payload("bye") } +
                Store.groups().map { Groups.goodbye(it) }
            Player.stop()
            Store.resetCode()
            selectedTarget = null
            ListenService.restart(this)
            runInBackground({
                goodbyes.forEach { (topic, payload) ->
                    try {
                        Relay.publishTopic(topic, payload)
                    } catch (_: Exception) {
                    }
                }
            }) {}
            toast("Listo: tienes un código nuevo")
        }
    }

    // ------------------------------------------------------------------ Utilidades

    /** Corre [work] en segundo plano; si sale bien, [done] en la pantalla; si falla, avisa. */
    private fun runInBackground(work: () -> Unit, done: () -> Unit) {
        try {
            io.execute {
                try {
                    work()
                    ui.post(done)
                } catch (e: Exception) {
                    ui.post { toast(Relay.describe(e)) }
                }
            }
        } catch (_: Exception) {
            // La pantalla se cerró mientras tanto.
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun info(title: String, message: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Entendido", null).show()
    }

    private fun confirm(title: String, message: String?, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Sí") { _, _ -> onYes() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun editText(value: String, hint: String): EditText = EditText(this).apply {
        setText(value)
        this.hint = hint
        setSingleLine(true)
        setSelection(text.length)
    }

    private fun padded(vararg views: View): View = column().apply {
        setPadding(dp(22), dp(8), dp(22), 0)
        views.forEach { addView(it, lp()) }
    }

    /** Diálogo con un campo de texto; [onSave] devuelve true para cerrarlo. */
    private fun textDialog(
        title: String,
        message: String?,
        value: String,
        hint: String,
        okText: String,
        onSave: (String) -> Boolean,
    ): AlertDialog {
        val input = editText(value, hint).apply {
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(if (hint.startsWith("http")) 200 else 24))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(padded(input))
            .setPositiveButton(okText, null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    input.error = "Escribe algo"
                } else if (onSave(text)) {
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
        return dialog
    }

    private fun goEdgeToEdge() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }
}
