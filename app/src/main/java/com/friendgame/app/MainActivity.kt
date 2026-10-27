package com.friendgame.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
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
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity(), Store.Listener {

    private companion object {
        const val REQ_PICK_AUDIO = 10
        const val REQ_NOTIFICATIONS = 11
        const val REQ_SCAN = 12
        const val REQ_MIC = 13
        const val REQ_TALK = 14

        /** Tiempo durante el que se muestra el botón para detener el sonido que mandaste. */
        const val STOP_WINDOW_MS = 15_000L
        /** Recarga entre un sonido y el siguiente (se ve como una barra que se llena). */
        const val SEND_COOLDOWN_MS = 2_500L
        /** Igual que quien recibe (ListenService): más de 4 sonidos cada 20 s no suenan, así que ni se mandan. */
        const val SEND_WINDOW_MS = 20_000L
        const val SEND_MAX_PER_WINDOW = 4
        /**
         * Página para instalar, no el .apk directo: abierto desde WhatsApp o un lector de QR, el
         * .apk se descarga en una ventanita del navegador y no aparece el instalador. La página
         * explica cómo pasar a Chrome y ahí tiene el enlace de descarga.
         */
        const val INSTALL_PAGE = "https://github.com/chiquidg1234-hue/Friend-game/releases/latest"

        const val STATE_TAB = "tab"
        const val STATE_TARGET = "target"
        const val STATE_PAGES = "pages"

        /** Páginas que se abren encima de una pestaña (con atrás se vuelve). */
        const val PAGE_SETTINGS = "settings"
        const val PAGE_SUPPORT = "support"
        const val PAGE_CHAT = "chat:"
        const val PAGE_RANKING = "ranking:"
        const val PAGE_MARKET = "market"
        const val PAGE_PETS = "pets"

        /** Filtros de Sonar: todos, una categoría (clave de Vibes) o solo los tuyos. */
        const val FILTER_ALL = "todos"
        const val FILTER_MINE = "mios"

        /** La intro sale una vez cada vez que se abre la app (no al girar la pantalla). */
        var introShown = false

        /** Emojis para elegir al crear una comunidad. */
        val COMMUNITY_EMOJIS = listOf("🎮", "⚽", "🎵", "🏫", "🏠", "🔥", "😂", "🐶", "🍕", "🎨")
    }

    private enum class Tab(val title: String, val iconRes: Int) {
        SOUND("Sonar", R.drawable.ic_nav_sound),
        CHATS("Chats", R.drawable.ic_nav_chat),
        GROUPS("Grupos", R.drawable.ic_nav_groups),
        FRIENDS("Amigos", R.drawable.ic_nav_friends),
        ME("Tú", R.drawable.ic_trophy),
    }

    private class Sent(val topic: String, val toName: String, val playbackId: String, val label: String)

    /** A quién va un sonido: un amigo ("f:código") o un grupo ("g:id"). */
    private class Target(val key: String, val name: String, val topic: String, val groupId: String?)

    private lateinit var root: FrameLayout
    private lateinit var shell: LinearLayout
    /** Encabezado fijo de algunas páginas (el chat), arriba del contenido con scroll. */
    private lateinit var topBar: LinearLayout
    private lateinit var scroll: ScrollView
    /** Para escribir en el chat: se arma una sola vez para no perder lo escrito al redibujar. */
    private lateinit var composer: LinearLayout
    private lateinit var composerInput: EditText
    private lateinit var content: LinearLayout
    private lateinit var bars: LinearLayout
    private lateinit var nav: LinearLayout
    private lateinit var navDivider: View
    private lateinit var overlay: FrameLayout
    /** Fondo animado detrás de todo (ver SceneView): cambia según la categoría de lo que suena. */
    private lateinit var scene: SceneView
    /** Fondo y Bulla del aviso «te llegó un sonido». */
    private val overlayScene by lazy { SceneView(this) }
    private val overlayPet by lazy { PetView(this).apply { species = "bulla" } }
    private lateinit var bullaToast: BullaToast
    /** Bulla que asoma abajo al mandar o escuchar un sonido. */
    private lateinit var peek: MascotPeek
    /** Bulla en Sonar y en las pantallas vacías: una sola que se mueve de pantalla en pantalla. */
    private val guide by lazy { PetView(this).apply { species = "bulla" } }
    /** El sonido al que ya reaccionó el fondo (para no repetir la animación en cada redibujo). */
    private var reactedPlayback = ""
    /** Buscador de Sonar: fijo arriba (como la caja del chat) para no perder lo escrito. */
    private lateinit var searchBar: LinearLayout
    private lateinit var searchInput: EditText
    private var searching = false
    private var soundQuery = ""
    private var soundFilter = FILTER_ALL
    private var welcome: View? = null
    private var loudDialog: AlertDialog? = null

    private val ui = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private var tab = Tab.SOUND
    private val pages = ArrayList<String>()
    private val page: String? get() = pages.lastOrNull()
    private val chatKey: String? get() = page?.takeIf { it.startsWith(PAGE_CHAT) }?.removePrefix(PAGE_CHAT)
    private var rankingWeekly = true
    /** Último mensaje del chat dibujado: si cambia, se baja a lo nuevo. */
    private var chatTail = ""
    private var lastChatTail = ""
    /** La pantalla está a la vista (entre onStart y onStop). */
    private var started = false
    /** Próximo momento en que algo vence y hay que redibujar. */
    private var nextDeadline = Long.MAX_VALUE
    private var lastSendTextAt = 0L
    private var donationAmount: Int? = Donations.DEFAULT_AMOUNT
    private var selectedTarget: String? = null
    private var lastSent: Sent? = null
    /** Un sonido saliendo: hasta que termine no sale otro (no se arma una fila de envíos). */
    private var sending = false
    private var cooldownFrom = 0L
    private var cooldownUntil = 0L
    /** Envíos recientes a cada destino (reloj elapsedRealtime), para el límite de 4 cada 20 s. */
    private val recentSends = HashMap<String, ArrayDeque<Long>>()
    private var lastCooldownNotice = 0L
    private val cooldownOver = Runnable { if (started) render() }
    /** Pestaña dibujada la última vez (para animar el cambio). */
    private var renderedTab: Tab? = null
    /** Sonoteca: 0 tendencias, 1 nuevos, 2 tuyos. */
    private var sonotecaFilter = 0
    /** Modo escuchar: tocar un sonido lo escuchas tú, sin mandarlo. */
    private var listenMode = false
    /** En modo escuchar: suena como le llega a tu amigo (pantalla completa, volumen de alarma, poder listo). */
    private var listenAsFriend = false
    /** Título "Sonidos" de la última vez que se dibujó Sonar (para bajar hasta ahí al devolvérsela). */
    private var soundsAnchor: View? = null
    /** La mascota se reusa entre redibujos para no cortar sus animaciones (comer, saltar). */
    private val petView by lazy { PetView(this) }
    private var renderPosted = false
    private var renderedScreen = ""
    private var systemBars = IntArray(4)
    private var imeBottom = 0
    private var cancelRecording: (() -> Unit)? = null
    /** OnBackInvokedCallback (Android 13+); se guarda como Any para no cargar la clase antes. */
    private var backCallback: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Antes de leer el intent (openTabFrom le saca los extras): ¿se abrió desde el ícono?
        val fromLauncher = intent?.action == Intent.ACTION_MAIN && intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true
        Store.init(this)
        Notifs.createChannels(this)
        goEdgeToEdge()
        if (savedInstanceState != null) {
            tab = Tab.entries.getOrNull(savedInstanceState.getInt(STATE_TAB)) ?: Tab.SOUND
            selectedTarget = savedInstanceState.getString(STATE_TARGET)
            savedInstanceState.getStringArrayList(STATE_PAGES)?.let { pages.addAll(it) }
        }
        openTabFrom(intent)

        root = FrameLayout(this)
        scene = SceneView(this)
        root.addView(scene, FrameLayout.LayoutParams(MATCH, MATCH))
        applyBackground()
        buildShell()
        root.setOnApplyWindowInsetsListener { _, insets ->
            readInsets(insets)
            applyInsets()
            insets
        }
        setContentView(root)

        if (Store.myName.isBlank()) showWelcome() else onReady()
        // La intro, solo al abrir la app desde el ícono (no al volver de un aviso ni al girar).
        if (savedInstanceState == null && !introShown && Anim.enabled() && fromLauncher) {
            introShown = true
            val view = IntroView(this) { updateBackCallback() }
            root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
            view.post { view.play() }
        }
        introShown = true
    }

    /** Fondo de la app según lo que eligió la persona (Looks.background); se dibuja animado en [scene]. */
    private fun applyBackground() {
        if (!::scene.isInitialized) return
        scene.base = Looks.background().key
    }

    private fun onReady() {
        ListenService.start(this)
        render()
        if (Store.loudAnswered) askNotificationPermission() else askLoud { askNotificationPermission() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (openTabFrom(intent)) render()
    }

    /** Los avisos de amistad o de grupos abren directo su pestaña; los del chat, la conversación. */
    private fun openTabFrom(intent: Intent?): Boolean {
        val reply = intent?.getStringExtra(Notifs.EXTRA_REPLY)
        if (reply != null) {
            intent.removeExtra(Notifs.EXTRA_REPLY)
            intent.removeExtra(Notifs.EXTRA_TAB)
            prepareReply(reply)
            return true
        }
        val chat = intent?.getStringExtra(Notifs.EXTRA_CHAT)
        if (chat != null) {
            intent.removeExtra(Notifs.EXTRA_CHAT)
            tab = Tab.CHATS
            pages.clear()
            pages.add(PAGE_CHAT + chat)
            return true
        }
        val name = intent?.getStringExtra(Notifs.EXTRA_TAB) ?: return false
        intent.removeExtra(Notifs.EXTRA_TAB)
        tab = Tab.entries.firstOrNull { it.name == name } ?: return false
        pages.clear()
        return true
    }

    override fun onStart() {
        super.onStart()
        started = true
        Store.addListener(this)
        if (Store.myName.isNotBlank()) {
            Points.onDailyOpen()
            Retos.checkDue(this)
            Locks.finishDue()
            broadcastPoints()
        }
        render()
        ui.post(clock)
        // Tras una actualización con la app abierta en segundo plano, se pregunta al volver.
        if (Store.myName.isNotBlank() && welcome == null && !Store.loudAnswered) askLoud()
        if (!BuildConfig.STORE_BUILD) Updater.check(this)
        if (Sonoteca.takedownsDue()) {
            val app = applicationContext
            Thread {
                try {
                    Sonoteca.syncTakedowns(app)
                } catch (_: Exception) {
                }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Store.myName.isNotBlank()) ListenService.start(this)
    }

    override fun onStop() {
        started = false
        Store.removeListener(this)
        ui.removeCallbacks(clock)
        Chats.visibleKey = null
        // Android no deja usar el micrófono en segundo plano: la grabación se cancela.
        cancelRecording?.invoke()
        if (petRoomBuilt) petRoom.leave()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tab.ordinal)
        outState.putString(STATE_TARGET, selectedTarget)
        outState.putStringArrayList(STATE_PAGES, pages)
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

    /** Atrás: corta el sonido que llegó, cierra la página abierta o vuelve a la pestaña Sonar. */
    private fun handleBack(): Boolean {
        when {
            welcome != null -> return false
            // Con candado el sonido no se corta, pero atrás tampoco saca de la pantalla.
            overlay.visibility == View.VISIBLE -> Player.stopByUser(this)
            upload != null -> upload?.close()
            games != null -> games?.back()
            searching && pages.isEmpty() && tab == Tab.SOUND -> closeSearch()
            pages.isNotEmpty() -> closePage()
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
        val needed = welcome == null && (overlay.visibility == View.VISIBLE || upload != null || games != null || searching || pages.isNotEmpty() || tab != Tab.SOUND)
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
        topBar = column().apply { visibility = View.GONE }
        buildComposer()
        buildSearchBar()
        bars = column()
        nav = row().apply {
            setBackgroundColor(Palette.NAV)
            gravity = Gravity.TOP
        }
        shell = column()
        shell.addView(topBar, lp())
        shell.addView(searchBar, lp())
        shell.addView(scroll, lp(MATCH, 0, weight = 1f))
        shell.addView(composer, lp())
        shell.addView(bars, lp())
        navDivider = divider(Palette.LINE)
        shell.addView(navDivider, lp(MATCH, dp(1)))
        shell.addView(nav, lp())
        overlay = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Palette.OVERLAY)
            isClickable = true
            isFocusable = true
        }
        root.addView(shell, FrameLayout.LayoutParams(MATCH, MATCH))
        peek = MascotPeek(this)
        root.addView(peek, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { marginEnd = dp(10) })
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        bullaToast = BullaToast(root)
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
        // En el chat no hay navegación: la caja para escribir queda sobre el teclado.
        composer.setPadding(dp(12), dp(8), dp(12), dp(10) + maxOf(bottom, imeBottom))
        if (chatKey != null && imeBottom > 0) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        overlay.setPadding(left, top, right, bottom)
        upload?.setPadding(left, top, right, maxOf(bottom, imeBottom))
        games?.setPadding(left, top, right, bottom)
        bullaToast.topInset = top
        // Bulla asoma justo arriba de la barra de abajo.
        (peek.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            val above = if (nav.visibility == View.VISIBLE) nav.height else composer.height
            if (params.bottomMargin != above + bars.height) {
                params.bottomMargin = above + bars.height
                peek.layoutParams = params
            }
        }
        welcome?.let { page ->
            page.setPadding(left, top, right, maxOf(bottom, imeBottom))
            // Con el teclado abierto, que se vean el nombre y el botón Empezar.
            if (imeBottom > 0 && page is ScrollView) page.post { page.smoothScrollTo(0, page.getChildAt(0)?.height ?: 0) }
        }
    }

    // ------------------------------------------------------------------ Pantalla

    private fun render() {
        if (welcome != null) return
        val current = page
        // Si el chat o el grupo de la página ya no existe (te eliminaron, saliste), se cierra.
        val chat = chatKey
        if (chat != null && Chats.title(chat) == null) {
            pages.removeAt(pages.size - 1)
            render()
            return
        }
        val screen = current ?: tab.name
        val sameScreen = screen == renderedScreen
        screenIsNew = !sameScreen
        if (!sameScreen && renderedScreen == PAGE_PETS && petRoomBuilt) petRoom.leave()
        val y = scroll.scrollY
        content.removeAllViews()
        topBar.removeAllViews()
        nextDeadline = Long.MAX_VALUE
        content.setPadding(dp(20), dp(20), dp(20), dp(28))
        content.background = null
        applyBackground()
        // Solo cuenta como "a la vista" si la app está abierta: si no, sus mensajes deben avisar.
        Chats.visibleKey = if (started) chat else null
        when {
            current == null -> when (tab) {
                Tab.SOUND -> renderSound()
                Tab.CHATS -> renderChats()
                Tab.GROUPS -> renderGroups()
                Tab.FRIENDS -> renderFriends()
                Tab.ME -> renderMe()
            }
            current == PAGE_SETTINGS -> renderSettings()
            current == PAGE_SUPPORT -> renderSupport()
            current == PAGE_MARKET -> renderMarket()
            current == PAGE_PETS -> renderPets()
            chat != null -> renderChat(chat)
            current.startsWith(PAGE_RANKING) -> renderRanking(current.removePrefix(PAGE_RANKING))
        }
        topBar.visibility = if (topBar.childCount == 0) View.GONE else View.VISIBLE
        composer.visibility = if (chat != null) View.VISIBLE else View.GONE
        searchBar.visibility = if (searching && current == null && tab == Tab.SOUND) View.VISIBLE else View.GONE
        reactToPlayback()
        renderBars()
        renderNav()
        renderOverlay()
        renderedScreen = screen
        if (chat != null) {
            // En el chat se baja a lo último cuando llega algo nuevo o al abrirlo.
            if (!sameScreen || chatTail != lastChatTail) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            else scroll.post { scroll.scrollTo(0, y) }
            lastChatTail = chatTail
        } else if (sameScreen) {
            scroll.post { scroll.scrollTo(0, y) }
        } else {
            scroll.scrollTo(0, 0)
            // Pantalla nueva: los bloques entran uno tras otro.
            Anim.enter((0 until content.childCount).map { content.getChildAt(it) })
        }
        applyInsets()
        updateBackCallback()
        celebrate()
    }

    /** La pantalla que se dibuja ahora es nueva (no un redibujo de la misma). */
    private var screenIsNew = true

    /** Bulla asomó por lo que suena (y se va cuando termine). */
    private var peekForPlayback = false

    /** De dónde sale la próxima reacción del fondo (el sonido que tocaste), en píxeles del fondo. */
    private var originX = -1f
    private var originY = -1f

    private fun rememberOrigin(view: View) {
        if (!view.isAttachedToWindow || !::scene.isInitialized) return
        val at = IntArray(2).also { view.getLocationInWindow(it) }
        val base = IntArray(2).also { scene.getLocationInWindow(it) }
        originX = (at[0] - base[0] + view.width / 2).toFloat()
        originY = (at[1] - base[1] + view.height / 2).toFloat()
    }

    /** La categoría de lo que suena (la que trae, o la de fábrica del sonido). */
    private fun vibeOf(now: Player.NowPlaying): String = now.vibe.ifEmpty { Sounds.byKey(now.soundKey)?.vibe ?: "" }

    /**
     * Cuando empieza a sonar algo, el fondo cambia a su categoría (abriéndose desde el sonido) y
     * al terminar vuelve al tuyo. Se puede apagar en Ajustes.
     */
    private fun reactToPlayback() {
        val now = Player.now
        if (now != null) {
            val vibe = vibeOf(now)
            if (reactedPlayback != now.playbackId && vibe.isNotEmpty() && Looks.reactiveBackground) {
                reactedPlayback = now.playbackId
                if (originX >= 0f) scene.react(vibe, originX, originY) else scene.react(vibe)
                originX = -1f
            }
        } else {
            if (reactedPlayback.isNotEmpty()) {
                reactedPlayback = ""
                scene.release()
            }
            if (peekForPlayback) {
                peekForPlayback = false
                peek.slideOut()
            }
        }
    }

    /** Escuchar en tu celular: el fondo cambia a su categoría y Bulla asoma moviendo la boca. */
    private fun previewSound(source: Player.Source, name: String, startMs: Int, vibe: String, from: View?) {
        if (from != null) {
            Anim.pop(from, 1.05f)
            rememberOrigin(from)
        }
        Player.preview(this, source, name, startMs, vibe)
        peekForPlayback = true
        peek.show(vibe, 10.5f, follow = true)
    }

    // ------------------------------------------------------------------ Buscador de Sonar

    private val searchRender = Runnable { if (started) render() }

    private fun buildSearchBar() {
        searchInput = EditText(this).apply {
            hint = "Buscar un sonido"
            setSingleLine(true)
            textSize = 16f
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.MUTED_TEXT)
            typeface = Fonts.body(context, 700)
            background = null
            setPadding(0, 0, 0, 0)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            contentDescription = "Buscar un sonido"
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(text: android.text.Editable?) {
                    val query = text?.toString()?.trim() ?: ""
                    if (query == soundQuery) return
                    soundQuery = query
                    ui.removeCallbacks(searchRender)
                    ui.postDelayed(searchRender, 150)
                }
            })
            setOnEditorActionListener { view, _, _ ->
                hideKeyboard(view)
                true
            }
        }
        searchBar = row().apply {
            visibility = View.GONE
            setPadding(dp(16), dp(10), dp(16), dp(4))
        }
        val box = row().apply {
            background = rounded(Palette.SURFACE, 16, Palette.LIP)
            setPadding(dp(14), 0, dp(4), 0)
        }
        box.addView(icon(R.drawable.ic_search, Palette.SUB, 18))
        box.addView(searchInput, lp(0, dp(48), weight = 1f).apply { marginStart = dp(10) })
        box.addView(iconButton(R.drawable.ic_close, "Cerrar el buscador", 0, Palette.SUB, 40) { closeSearch() })
        searchBar.addView(box, lp())
    }

    private fun openSearch() {
        searching = true
        render()
        searchInput.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        searching = false
        soundQuery = ""
        searchInput.setText("")
        hideKeyboard(searchInput)
        render()
    }

    /** Filtros de Sonar: todos, cada categoría y los tuyos. */
    private fun filterRow(): View {
        val (scroller, chips) = chipRow()
        fun add(key: String, text: String, color: Int, onColor: Int, textColor: Int, iconRes: Int?) {
            chips.addView(filterChip(text, soundFilter == key, color, onColor, textColor, iconRes) {
                if (soundFilter != key) {
                    soundFilter = key
                    render()
                }
            }, LinearLayout.LayoutParams(WRAP, dp(40)).apply { marginEnd = dp(8) })
        }
        add(FILTER_ALL, "Todos", Palette.TEXT, Palette.INK, Palette.TEXT, null)
        Vibes.all.forEach { add(it.key, it.label, it.color, it.onColor, it.chip, it.icon) }
        add(FILTER_MINE, "Tuyos", Palette.CUSTOM, Palette.INK, Palette.CUSTOM, R.drawable.ic_mic)
        return scroller
    }

    /** Bulla con un globo de diálogo (en Sonar y en las pantallas vacías). */
    private fun bullaSays(text: String, sizeDp: Int = 68): View {
        (guide.parent as? ViewGroup)?.removeView(guide)
        guide.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        guide.outfit = Wardrobe.outfit()
        val line = row().apply { gravity = Gravity.BOTTOM }
        line.addView(guide, LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)))
        val bubble = label(text, 14f, Palette.TEXT, bold = true).apply {
            background = rounded(Palette.SURFACE, 18, Palette.LIP)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        line.addView(bubble, lp(0, WRAP, weight = 1f).apply {
            marginStart = dp(6)
            bottomMargin = dp(sizeDp / 5)
        })
        if (screenIsNew) guide.post { guide.wave() }
        return line
    }

    private fun openTab(target: Tab) {
        if (target == tab && pages.isEmpty()) {
            scroll.smoothScrollTo(0, 0)
            return
        }
        pages.clear()
        tab = target
        render()
    }

    private fun openPage(name: String) {
        if (page != name) pages.add(name)
        render()
    }

    private fun closePage() {
        if (pages.isNotEmpty()) pages.removeAt(pages.size - 1)
        hideKeyboard(composerInput)
        render()
    }

    private fun openChat(key: String) {
        if (Chats.title(key) == null) return
        if (tab != Tab.CHATS && pages.isEmpty()) tab = Tab.CHATS
        openPage(PAGE_CHAT + key)
    }

    private fun openSupport() = openPage(PAGE_SUPPORT)

    private fun closeSupport() = closePage()

    /** Logros, subidas de nivel, misiones: un aviso corto que no interrumpe, y confeti si es grande. */
    private fun celebrate() {
        val big = Points.hasBigCelebration()
        val list = Points.takeCelebrations()
        list.forEach { toast(it) }
        if (big && started) {
            Anim.confetti(root)
            if (petView.isAttachedToWindow) petView.cheer()
        }
    }

    /** Cada segundo revisa si algo venció (un reto, un candado) para redibujar. */
    private val clock = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() >= nextDeadline) {
                nextDeadline = Long.MAX_VALUE
                Retos.checkDue(this@MainActivity)
                Locks.finishDue()
                render()
            }
            ui.postDelayed(this, 1000)
        }
    }

    private fun liveText(
        sizeSp: Float,
        color: Int,
        description: String,
        bold: Boolean = true,
        center: Boolean = false,
        text: () -> String,
    ): View = LiveText(this, sizeSp, color, bold, description, center, text)

    private fun deadline(at: Long) {
        if (at in (System.currentTimeMillis() + 1)..<nextDeadline) nextDeadline = at
    }

    /** Manda mis puntos a amigos y grupos de vez en cuando, para que el ranking esté al día. */
    private fun broadcastPoints() {
        if (!Points.broadcastDue()) return
        val topics = Store.friends().filter { it.state == Friend.FRIEND }.map { Relay.topicFor(it.code) } +
            Store.groups().map { Relay.groupTopic(it.id) }
        if (topics.isEmpty()) return
        Points.lastBroadcast = System.currentTimeMillis()
        val payload = Relay.payload("pts")
        runInBackground({
            topics.forEach { topic ->
                try {
                    Relay.publishTopic(topic, payload)
                } catch (_: Exception) {
                }
            }
        }) {}
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
    private fun alert(lead: View, titleText: String, text: String, action: String, actionColor: Int, onAction: () -> Unit) =
        banner(lead, titleText, label(text, 13f, Palette.SUB), action, actionColor, onAction)

    private fun banner(lead: View, titleText: String, body: View, action: String, actionColor: Int, onAction: () -> Unit) {
        val box = row().apply {
            background = rounded(Palette.SURFACE, 20)
            setPadding(dp(14), dp(12), dp(12), dp(12))
        }
        box.addView(lead)
        val texts = column()
        texts.addView(label(titleText, 15f, bold = true), lp())
        texts.addView(body, lp(topDp = 2))
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
        header.addView(personAvatar(Store.myCode, Store.myName, 44).apply { setOnClickListener { openTab(Tab.ME) } })
        val hello = column()
        hello.addView(title("Hola, ${Store.myName}", 22f, weight = 600).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp())
        val level = Points.level()
        val nextLevel = Points.next(level)
        // Gradiente de meta: cerca de la meta se avanza más rápido, así que se avisa cuando falta poco.
        val close = nextLevel != null && Points.progress() >= 0.8f
        val levelText = if (close && nextLevel != null) "Nivel ${level.number} · ¡a ${nextLevel.min - Points.total()} pts del nivel ${nextLevel.number}!"
        else "Nivel ${level.number} · ${level.name} · ${Points.total()} pts"
        hello.addView(label(levelText, 13f, Palette.YELLOW, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            contentDescription = "Nivel ${level.number}, ${level.name}, ${Points.total()} puntos. Abre tu perfil"
            setOnClickListener { openTab(Tab.ME) }
        }, lp())
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
        // Avisos para jugar: como mucho dos a la vez, para que los sonidos queden a la vista.
        var nudges = 0
        Replies.pending()?.let { back ->
            val minutes = (System.currentTimeMillis() - back.time) / 60_000L
            val ago = if (minutes < 1) "Hace un momento" else "Hace $minutes min"
            alert(
                label("↩️", 22f).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                "${back.name} te hizo sonar «${back.label}»", "$ago. ¡Devuélvesela!",
                "Devolvérsela", Palette.YELLOW,
            ) { replyTo(back.key) }
            nudges++
        }
        val shared = Sonoteca.unseen()
        shared.maxByOrNull { it.time }?.let { first ->
            val who = first.sharers.firstOrNull()?.name ?: first.authorName
            val more = if (shared.size > 1) " y ${shared.size - 1} más" else ""
            alert(
                icon(R.drawable.ic_sonoteca, Palette.ACCENT_TEXT, 24), "$who compartió «${first.label}»$more",
                "Escúchalo y úsalo desde la Sonoteca.", "Ver", Palette.ACCENT,
            ) { openPage(PAGE_MARKET) }
            nudges++
        }
        Seasons.current()?.takeIf { !Seasons.seen(it) && nudges < 2 }?.let { season ->
            alert(
                label(season.emoji, 22f).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                "${season.name} · hasta ${season.until}", season.text, "Ver", Palette.CUSTOM,
            ) {
                Seasons.markSeen(season)
                openTab(Tab.ME)
            }
        }
        addGameBanners()
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

        val all = targets(friends.filter { it.state == Friend.FRIEND }, groups.filter { !it.community })
        if (all.none { it.key == selectedTarget }) selectedTarget = all.firstOrNull()?.key
        val target = all.firstOrNull { it.key == selectedTarget }
        if (target == null) addNoTargets() else addTargetChips(all, target)

        if (target != null) {
            val extras = row()
            extras.addView(
                button(
                    "Crear un reto", Palette.SURFACE, Palette.TEXT, iconRes = R.drawable.ic_bolt,
                    heightDp = 44, radiusDp = 14, sizeSp = 14f, iconColor = Palette.YELLOW,
                ) { showCreateReto(target.key) },
                lp(0, WRAP, weight = 1f),
            )
            extras.addView(space(8))
            addPowerButton(extras)
            add(extras, 10)
        }

        val listening = target != null && listenMode
        val cooling = target != null && !listening && coolingDown()
        add(bullaSays(when {
            target == null -> "¡Hola! Soy Bulla. Agrega a un amigo y le hacemos bulla 🔊"
            listening -> "Toca un sonido y te lo hago sonar a ti 🎧"
            else -> "¿Qué le mandamos a ${target.name}?"
        }), 16)
        val soundsHead = row().apply { gravity = Gravity.CENTER_VERTICAL }
        soundsHead.addView(title("Sonidos", 20f, weight = 600), lp(0, WRAP, weight = 1f))
        soundsHead.addView(
            iconButton(R.drawable.ic_search, "Buscar un sonido", Palette.SURFACE, Palette.TEXT, 40) { openSearch() },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) },
        )
        if (target != null) soundsHead.addView(modeSwitch(), LinearLayout.LayoutParams(WRAP, WRAP))
        add(soundsHead, 22)
        soundsAnchor = soundsHead
        add(filterRow(), 10)
        when {
            listening -> add(listenHint(target!!), 8)
            cooling -> add(rechargeLine(), 8)
            else -> add(label("Toca ▶ para escucharlo tú · mantén presionado para personalizar", 12f, Palette.SUB), 8)
        }
        val query = soundQuery.lowercase()
        fun shown(name: String, vibe: Vibe, mine: Boolean): Boolean =
            (soundFilter == FILTER_ALL || soundFilter == vibe.key || (mine && soundFilter == FILTER_MINE)) &&
                (query.isEmpty() || name.lowercase().contains(query) || vibe.label.lowercase().contains(query))
        val builtIn = Sounds.all.mapNotNull { sound ->
            val look = Looks.button(sound.key)
            val name = look.label ?: sound.label
            val vibe = Vibes.byKey(Looks.vibe(sound.key))
            if (!shown(name, vibe, mine = false)) return@mapNotNull null
            val source = Player.Source.Asset(sound.asset)
            lateinit var tile: View
            tile = soundCard(look.emoji ?: sound.emoji, name, vibe,
                onClick = {
                    when {
                        target == null -> previewSound(source, name, 0, vibe.key, tile)
                        listening -> listenTo(target, source, name, sound.key, 0, vibe.key, tile)
                        else -> sendSound(target, sound.key, name, null, tile)
                    }
                },
                onLongClick = { soundOptions(sound) },
                onPreview = { previewSound(source, name, 0, vibe.key, tile) },
                swatch = Looks.swatch(look.swatch),
                outlined = listening,
            )
            if (cooling) tile.alpha = 0.55f
            tile
        }
        if (builtIn.isNotEmpty()) grid(builtIn, 2)

        val mine = Store.customSounds().mapNotNull { custom ->
            val source = Player.Source.FromFile(custom.file(this))
            val look = Looks.button("c:${custom.id}")
            val vibe = Vibes.byKey(Looks.vibe("c:${custom.id}"))
            if (!shown(custom.label, vibe, mine = true)) return@mapNotNull null
            lateinit var tile: View
            tile = soundCard(look.emoji ?: "🎤", custom.label, vibe,
                onClick = {
                    when {
                        target == null -> previewSound(source, custom.label, custom.startMs, vibe.key, tile)
                        listening -> listenTo(target, source, custom.label, "custom", custom.startMs, vibe.key, tile)
                        else -> sendSound(target, "custom", custom.label, custom, tile)
                    }
                },
                onLongClick = { customOptions(custom) },
                onPreview = { previewSound(source, custom.label, custom.startMs, vibe.key, tile) },
                swatch = Looks.swatch(look.swatch),
                outlined = listening,
            )
            if (cooling) tile.alpha = 0.55f
            tile
        }
        if (builtIn.isEmpty() && mine.isEmpty()) {
            add(card().apply {
                addView(label("No encontré sonidos así 🤔", 15f, bold = true), lp())
                addView(label("Prueba con otra palabra u otra categoría, o graba uno nuevo.", 13f, Palette.SUB), lp(topDp = 2))
            }, 12)
        }
        add(sectionTitle("Tus audios", if (Store.customSounds().isNotEmpty()) "${Store.customSounds().size}" else null), 22)
        if (mine.isNotEmpty()) grid(mine, 2)
        grid(
            listOf(
                addTile(R.drawable.ic_mic, "Grabar") { recordAudio() },
                addTile(R.drawable.ic_upload, "Subir") { pickAudio() },
                addTile(R.drawable.ic_sonoteca, "Sonoteca") { openPage(PAGE_MARKET) }.apply { contentDescription = "Abrir la Sonoteca" },
            ),
        )
        val hint = if (Store.customSounds().isEmpty()) "Graba tu voz o sube un audio que descargaste: ${CustomAudio.HINT}. Bulla adivina de qué categoría es."
        else "${CustomAudio.HINT}. Mantén presionado uno tuyo para escucharlo, elegir la parte que suena, cambiar su categoría o borrarlo."
        add(label(hint, 12f, Palette.SUB), 8)

        add(sectionTitle("Misiones de hoy", "${Quests.doneCount()} de 3"), 24)
        add(questsCard(), 10)
        addHistory()
        addFooter()
    }

    /** Bajo el título "Sonidos" mientras recarga: una barra que se llena sola. */
    private fun rechargeLine(): View = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(label("⏳ Recargando", 12f, Palette.YELLOW, bold = true), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
        addView(
            TimeBar(context, Palette.LINE, Palette.YELLOW, cooldownFrom, (cooldownUntil - cooldownFrom).coerceAtLeast(1)),
            lp(0, dp(8), weight = 1f),
        )
        contentDescription = "Recargando: espera un momento para mandar otro sonido"
    }

    /** Mandar o Escuchar: lo que hace tocar un sonido. */
    private fun modeSwitch(): View = row().apply {
        background = rounded(Palette.SURFACE, 999)
        setPadding(dp(3), dp(3), dp(3), dp(3))
        fun segment(text: String, on: Boolean, description: String, onClick: () -> Unit) {
            addView(label(text, 13f, if (on) Palette.INK else Palette.SUB, bold = true).apply {
                gravity = Gravity.CENTER
                background = if (on) rounded(Palette.YELLOW, 999) else null
                setPadding(dp(12), dp(7), dp(12), dp(7))
                isClickable = true
                isSelected = on
                contentDescription = description + if (on) ", elegido" else ""
                setOnClickListener {
                    if (!on) {
                        onClick()
                        render()
                    }
                }
            }, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        segment("🔊 Mandar", !listenMode, "Modo mandar: tocar un sonido se lo manda") { listenMode = false }
        segment("🎧 Escuchar", listenMode, "Modo escuchar: tocar un sonido lo escuchas tú") { listenMode = true }
    }

    /** En modo escuchar: qué pasa al tocar y la opción de oírlo como le llega a tu amigo. */
    private fun listenHint(target: Target): View = column().apply {
        addView(label("🎧 Tócalos para escucharlos tú: no se los manda a nadie.", 13f, Palette.YELLOW, bold = true), lp())
        val chip = row().apply {
            setPadding(dp(10), dp(6), dp(14), dp(6))
            background = clickable(rounded(if (listenAsFriend) Palette.ACCENT else Palette.SURFACE, 999))
            isClickable = true
            isSelected = listenAsFriend
            contentDescription = "Como le llega a ${target.name}" + if (listenAsFriend) ", activado" else ", desactivado"
            addView(label(if (listenAsFriend) "✓" else "📱", 14f, if (listenAsFriend) Palette.INK else Palette.TEXT, bold = true))
            addView(label("Como le llega a ${target.name}", 13f, if (listenAsFriend) Palette.INK else Palette.TEXT, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
            setOnClickListener {
                listenAsFriend = !listenAsFriend
                render()
            }
        }
        addView(chip, LinearLayout.LayoutParams(WRAP, dp(36)).apply { topMargin = dp(8) })
        if (listenAsFriend) {
            addView(
                label("Suena con la pantalla completa, el volumen de alarma y tu poder listo, igual que en su celular. Solo suena en el tuyo.", 12f, Palette.SUB),
                lp(topDp = 6),
            )
        }
    }

    /** Modo escuchar: suena solo en tu celular, como vista previa o como le llega a tu amigo. */
    private fun listenTo(target: Target, source: Player.Source, label: String, kind: String, startMs: Int, vibe: String, tile: View) {
        if (!listenAsFriend) {
            previewSound(source, label, startMs, vibe, tile)
            return
        }
        Anim.pop(tile, 1.08f)
        playTrial(target, source, label, kind, startMs)
    }

    /** Suena en tu celular tal como le llegaría a [target], sin mandarle nada. */
    private fun playTrial(target: Target, source: Player.Source, label: String, kind: String, startMs: Int) {
        val power = Powers.armed
        val vibe = if (kind == "custom" && source is Player.Source.FromFile) {
            Looks.vibe("c:" + source.file.nameWithoutExtension)
        } else {
            Looks.vibe(kind)
        }
        Player.play(
            this, source,
            Player.NowPlaying(
                Codes.random(12), Store.myCode, Store.myName, label, kind,
                loop = power?.key == Powers.ECO, power = power?.key ?: "", startMs = startMs, trialFor = target.name, vibe = vibe,
            ),
            incoming = true,
        )
    }

    /** Devolvérsela: abre Sonar con ese destino elegido, listo para tocar un sonido. */
    private fun prepareReply(key: String) {
        tab = Tab.SOUND
        pages.clear()
        selectedTarget = key
        listenMode = false
    }

    private fun replyTo(key: String) {
        prepareReply(key)
        render()
        val name = Chats.title(key) ?: "tu amigo"
        toast("↩️ Toca un sonido para devolvérsela a $name")
        soundsAnchor?.let { anchor -> scroll.post { scroll.smoothScrollTo(0, (anchor.top - dp(140)).coerceAtLeast(0)) } }
    }

    /** Las 3 misiones del día con su avance, y el cofre. */
    private fun questsCard(): View {
        val box = card(Palette.SURFACE, 22, 14)
        Quests.today().forEachIndexed { i, quest ->
            val progress = Quests.progress(quest)
            val done = progress >= quest.goal
            val line = column().apply {
                contentDescription = "Misión: ${quest.text}, " + if (done) "completada" else "$progress de ${quest.goal}"
            }
            val head = row()
            head.addView(label(quest.emoji, 16f).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            head.addView(label(quest.text, 14f, if (done) Palette.SUB else Palette.TEXT, bold = true).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, lp(0, WRAP, weight = 1f).apply { marginStart = dp(8) })
            head.addView(label(if (done) "✅" else "$progress/${quest.goal}", 13f, if (done) Palette.OK else Palette.YELLOW, bold = true).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            line.addView(head, lp())
            line.addView(progressBar(progress.toFloat() / quest.goal, if (done) Palette.OK else Palette.YELLOW), lp(MATCH, dp(6), topDp = 6))
            box.addView(line, lp(topDp = if (i == 0) 0 else 12))
        }
        when {
            Quests.chestReady() -> box.addView(
                button("🎁 Abrir el cofre del día", Palette.YELLOW, Palette.INK, heightDp = 48, radiusDp = 14, display = true, sizeSp = 16f) {
                    openChest()
                },
                lp(topDp = 14),
            )
            Quests.chestOpened() -> box.addView(label("🎁 Ya abriste el cofre de hoy. Mañana hay misiones nuevas.", 12f, Palette.SUB), lp(topDp = 12))
            else -> box.addView(label("🎁 Completa las 3 y abre el cofre del día: trae un premio sorpresa.", 12f, Palette.SUB), lp(topDp = 12))
        }
        return box
    }

    private fun openChest() {
        val reward = Quests.openChest() ?: return
        AlertDialog.Builder(this)
            .setTitle("🎁 ¡Cofre del día!")
            .setMessage("${reward.emoji}  ${reward.text}\n\nMañana hay misiones nuevas y otro cofre.")
            .setPositiveButton("¡Genial!", null)
            .show()
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
            setOnClickListener {
                tab = Tab.ME
                pages.clear()
                openPage(PAGE_SETTINGS)
            }
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
                contentDescription = "Mandar a ${option.name}" + (if (selected) ", elegido" else "") +
                    if (option.groupId == null && Presence.activeNow(option.key.removePrefix("f:"))) ", activo ahora" else ""
                val bubble = if (selected) Palette.INK else Palette.SURFACE2
                val fg = if (selected) Palette.ACCENT else Palette.SUB
                if (option.groupId != null) {
                    val icon = FrameLayout(context).apply { background = oval(bubble) }
                    icon.addView(icon(R.drawable.ic_nav_groups, fg, 18), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                    addView(icon, LinearLayout.LayoutParams(dp(32), dp(32)))
                } else {
                    // Prueba social: un punto verde si tu amigo está usando la app ahora.
                    val face = FrameLayout(context)
                    face.addView(avatar(option.name, bubble, fg, 32), FrameLayout.LayoutParams(dp(32), dp(32)))
                    if (Presence.activeNow(option.key.removePrefix("f:"))) {
                        face.addView(View(context).apply { background = oval(Palette.OK, Palette.SURFACE) }, FrameLayout.LayoutParams(dp(12), dp(12), Gravity.BOTTOM or Gravity.END))
                    }
                    addView(face, LinearLayout.LayoutParams(dp(32), dp(32)))
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
        add(label(if (listenMode) "Modo escuchar: tocar un sonido no se lo manda $who." else "Toca un sonido para mandárselo $who.", 13f, Palette.SUB), 8)
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
            add(bullaSays("Con un grupo, un sonido le llega a todos a la vez. ¡Armemos uno!"), 14)
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
        groups.filter { !it.community }.forEach { addGroupCard(it) }
        val communities = groups.filter { it.community }
        if (communities.isNotEmpty()) {
            add(caps("Comunidades"), 20)
            communities.forEach { addGroupCard(it) }
        }
        add(
            button(
                "Crear grupo", Palette.YELLOW, Palette.INK, iconRes = R.drawable.ic_plus,
                heightDp = 56, radiusDp = 18, display = true, sizeSp = 18f,
            ) { createGroup() },
            18,
        )
        add(
            button(
                "Crear comunidad", Palette.SURFACE2, Palette.TEXT, iconRes = R.drawable.ic_trophy,
                heightDp = 52, radiusDp = 18, iconColor = Palette.YELLOW,
            ) { createCommunity() },
            8,
        )
        add(
            label(
                "Grupo: para amigos cercanos, cada sonido le suena a todos. Comunidad: hasta ${Group.MAX_MEMBERS} personas " +
                    "con chat, ranking y retos; ahí solo suenan los retos.",
                12f, Palette.SUB,
            ),
            8,
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
        if (group.community && group.emoji.isNotEmpty()) {
            badge.addView(label(group.emoji, 24f).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            badge.addView(icon(R.drawable.ic_nav_groups, Palette.INK, 26), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        }
        head.addView(badge, LinearLayout.LayoutParams(dp(48), dp(48)))
        val texts = column()
        texts.addView(title(group.name, 19f, weight = 600).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp())
        val count = group.members.size
        val adminName = group.members.firstOrNull { it.code == group.adminCode }?.name
        val subtitle = buildString {
            if (group.community) append("Comunidad · ")
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
        if (group.community) {
            actions.addView(button("Ranking", Palette.YELLOW, Palette.INK, iconRes = R.drawable.ic_trophy, heightDp = 44, radiusDp = 14, sizeSp = 14f) {
                openPage(PAGE_RANKING + Chats.groupKey(group.id))
            }, lp(0, WRAP, weight = 1f))
        } else {
            actions.addView(button("Mandar sonido", heightDp = 44, radiusDp = 14, sizeSp = 14f) {
                selectedTarget = "g:${group.id}"
                openTab(Tab.SOUND)
            }, lp(0, WRAP, weight = 1f))
        }
        actions.addView(space(8))
        val unread = Store.unreadCounts()[Chats.groupKey(group.id)] ?: 0
        actions.addView(
            button(
                if (unread > 0) "Chat ($unread)" else "Chat", Palette.SURFACE2, Palette.TEXT, iconRes = R.drawable.ic_nav_chat,
                heightDp = 44, radiusDp = 14, sizeSp = 14f,
            ) { openChat(Chats.groupKey(group.id)) },
            lp(0, WRAP, weight = 1f),
        )
        actions.addView(space(8))
        actions.addView(
            iconButton(R.drawable.ic_qr, "Invitar a ${group.name} con QR", Palette.SURFACE2, Palette.TEXT) { showInvite(group) },
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
        val head = row()
        head.addView(title("Amigos", 30f), lp(0, WRAP, weight = 1f))
        head.addView(button("Agregar amigo", iconRes = R.drawable.ic_plus, heightDp = 46, radiusDp = 14, sizeSp = 14f) { showAddFriend() }, LinearLayout.LayoutParams(WRAP, WRAP))
        add(head)

        val codeCard = column().apply {
            background = LayerDrawable(arrayOf(
                rounded(Palette.LIP, 24),
                GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Palette.ACCENT_SOFT, Palette.SURFACE)).apply { cornerRadius = dp(24).toFloat() },
            )).apply { setLayerInset(1, 0, 0, 0, dp(4)) }
            setPadding(dp(18), dp(18), dp(18), dp(22))
        }
        codeCard.addView(caps("Tu código de amigo"), lp())
        codeCard.addView(title(Codes.pretty(Store.myCode), 26f, Palette.TEXT).apply {
            letterSpacing = 0.04f
            maxLines = 1
            setTextIsSelectable(true)
            setAutoSizeTextTypeUniformWithConfiguration(14, 26, 1, TypedValue.COMPLEX_UNIT_SP)
        }, lp(MATCH, dp(38), topDp = 6))
        codeCard.addView(label("Pásaselo solo a quien quieras que te pueda mandar sonidos.", 13f, Palette.SUB), lp(topDp = 2))
        val codeButtons = row()
        codeButtons.addView(
            button("Copiar", Palette.SURFACE, Palette.TEXT, iconRes = R.drawable.ic_copy, heightDp = 46, radiusDp = 14, sizeSp = 14f, strokeColor = Palette.LIP) {
                copyCode()
            },
            lp(0, WRAP, weight = 1f),
        )
        codeButtons.addView(space(8))
        codeButtons.addView(
            button("Compartir", Palette.YELLOW, Palette.INK, iconRes = R.drawable.ic_share, heightDp = 46, radiusDp = 14, sizeSp = 14f) { shareCode() },
            lp(0, WRAP, weight = 1f),
        )
        codeCard.addView(codeButtons, lp(topDp = 12))
        add(codeCard, 16)

        friends.filter { it.state == Friend.INCOMING }.forEach { friend ->
            val box = card().apply { background = rounded(Palette.SURFACE, 24, Palette.YELLOW) }
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
            add(bullaSays("Todavía no tienes amigos aquí. Pásale tu código a alguien o agrega el suyo."), 10)
        } else {
            val list = card(padDp = 0)
            others.forEachIndexed { i, friend ->
                if (i > 0) list.addView(divider(), dividerLp())
                list.addView(friendRow(friend), lp())
            }
            add(list, 8)
        }

        // Pasar la app: tarjeta con Bulla bailando (como en el diseño).
        val pass = row().apply {
            background = clickable(GradientDrawable().apply {
                setColor(Palette.BG_HIGH)
                cornerRadius = dp(22).toFloat()
                setStroke(dp(2), Palette.DASHED, dp(6).toFloat(), dp(5).toFloat())
            })
            setPadding(dp(12), dp(10), dp(16), dp(10))
            isClickable = true
            stateListAnimator = Anim.press()
            contentDescription = "Pasar la app: se la mandas por Quick Share o Bluetooth y la instala sin navegador"
            setOnClickListener { shareApp() }
        }
        val dancer = PetView(this).apply {
            species = "bulla"
            outfit = Wardrobe.outfit()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (screenIsNew) postDelayed({ dance(3f) }, 500)
        }
        pass.addView(dancer, LinearLayout.LayoutParams(dp(64), dp(64)))
        val passTexts = column()
        passTexts.addView(label("Pasar la app", 16f, bold = true), lp())
        passTexts.addView(label("Por Quick Share o Bluetooth, sin navegador.", 13f, Palette.SUB), lp())
        pass.addView(passTexts, lp(0, WRAP, weight = 1f).apply { marginStart = dp(12) })
        pass.addView(icon(R.drawable.ic_share, Palette.OK, 22))
        add(pass, 16)
    }

    private fun friendRow(friend: Friend): View {
        val line = row().apply {
            setPadding(dp(16), dp(10), dp(6), dp(10))
            background = rowRipple()
            isClickable = true
        }
        val active = friend.state == Friend.FRIEND
        line.addView(
            if (active) personAvatar(friend.code, friend.name, 40) else avatar(friend.name, Palette.SURFACE2, Palette.SUB, 40),
        )
        val texts = column()
        texts.addView(label(friend.name, 16f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp())
        val lock = if (active) Locks.activeWith(friend.code) else null
        val subtitle = when {
            lock != null -> "🔒 Candado hasta las ${Locks.untilText(lock)}"
            friend.state == Friend.FRIEND -> when {
                friend.muted -> "Silenciado: no te suenan sus sonidos"
                Presence.describe(friend.code) != null -> "🟢 ${Presence.describe(friend.code)!!.replaceFirstChar { it.uppercase() }} · toca para mandarle un sonido"
                else -> "Toca para mandarle un sonido"
            }
            friend.state == Friend.OUTGOING -> "Esperando que acepte tu solicitud"
            else -> "Bloqueado"
        }
        texts.addView(label(subtitle, 13f, if (lock != null) Palette.YELLOW else Palette.SUB, bold = lock != null), lp())
        line.addView(texts, lp(0, WRAP, weight = 1f).apply {
            marginStart = dp(12)
            marginEnd = dp(4)
        })
        val streak = if (active) Streaks.badge(friend.code) else null
        if (streak != null) {
            line.addView(label(streak, 13f, Palette.YELLOW, bold = true).apply {
                background = rounded(Palette.BADGE, 999)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                contentDescription = "${Streaks.describe(friend.code)} con ${friend.name}"
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(2) })
        }
        if (active) {
            line.addView(iconButton(R.drawable.ic_nav_chat, "Chat con ${friend.name}", 0, Palette.ACCENT_TEXT) {
                openChat(Chats.friendKey(friend.code))
            })
        }
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
        val top = row()
        top.addView(iconButton(R.drawable.ic_back, "Volver", Palette.SURFACE) { closePage() })
        top.addView(title("Ajustes", 30f), lp(0, WRAP, weight = 1f).apply { marginStart = dp(12) })
        add(top)
        val lock = Locks.anyActive()
        if (lock != null) {
            val name = Store.friend(lock.friend)?.name ?: "un amigo"
            alert(
                icon(R.drawable.ic_lock, Palette.YELLOW, 24), "Candado con $name",
                "Hasta las ${Locks.untilText(lock)} no se puede pausar ni cambiar cómo suena.", "Ver", Palette.YELLOW,
            ) { info("Candado con $name", lockRules(lock)) }
        }
        add(caps("Cuando te mandan un sonido"), 18)
        val box = card(padDp = 0)
        val (status, statusColor) = when {
            !Store.receiving -> "En pausa: nadie te puede hacer sonar" to Palette.SUB
            ListenService.connected -> "En línea · listo para recibir" to Palette.OK
            else -> "Conectando…" to Palette.YELLOW
        }
        box.addView(switchRow("Recibir sonidos", status, statusColor, Store.receiving) { checked ->
            if (lockBlocks()) return@switchRow
            if (checked && !Store.loudAccepted) {
                // Recibir sonidos es aceptar que suenen al máximo por el altavoz.
                render()
                askLoud(turnOn = true)
                return@switchRow
            }
            Store.receiving = checked
            if (checked) {
                ListenService.start(this)
            } else {
                Player.stop()
                ListenService.stop(this)
            }
        }, lp())
        box.addView(divider(), dividerLp())
        val loud = row().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
        loud.addView(icon(R.drawable.ic_nav_sound, if (Store.loudAccepted) Palette.OK else Palette.SUB, 22))
        val loudTexts = column()
        loudTexts.addView(label("Volumen máximo por el altavoz", 16f, bold = true), lp())
        loudTexts.addView(
            label(
                if (Store.loudAccepted) "Los sonidos suenan al máximo y por el altavoz principal, también en silencio. Al terminar, el volumen vuelve a como estaba."
                else "Para recibir sonidos hay que aceptarlo: activa «Recibir sonidos».",
                13f, Palette.SUB,
            ),
            lp(),
        )
        loud.addView(loudTexts, lp(0, WRAP, weight = 1f).apply { marginStart = dp(12) })
        box.addView(loud, lp())
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

        add(caps("Avisos"), 18)
        val notices = card(padDp = 0)
        notices.addView(
            switchRow(
                "Recordatorio diario",
                if (Reminders.enabled) "Uno al día, de tarde, si tu racha o tu mascota te necesitan" else "Apagado: no te recordamos nada",
                Palette.SUB, Reminders.enabled,
            ) { Reminders.enabled = it },
            lp(),
        )
        add(notices, 8)

        add(caps("Animaciones y fondo"), 18)
        val motion = card(padDp = 0)
        motion.addView(
            switchRow(
                "El fondo reacciona a los sonidos",
                if (Looks.reactiveBackground) "Cambia según la categoría y late con el audio" else "Apagado: el fondo no cambia",
                Palette.SUB, Looks.reactiveBackground,
            ) { Looks.reactiveBackground = it },
            lp(),
        )
        motion.addView(divider(), dividerLp())
        motion.addView(
            switchRow(
                "Reducir movimiento",
                if (Looks.reduceMotion) "Sin intro, fondos ni mascotas en movimiento" else "Todo se mueve (también se apaga con «Quitar animaciones» de Android)",
                Palette.SUB, Looks.reduceMotion,
            ) { Looks.reduceMotion = it },
            lp(),
        )
        add(motion, 8)

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
        if (BuildConfig.STORE_BUILD) {
            item("Versión", version, Palette.SUB) { info("Versión", "Friend Game $version. Las actualizaciones llegan por Google Play.") }
        } else {
            item("Versión", versionText, versionColor) { Updater.check(this, force = true) }
        }
        item("Servidor", Uri.parse(Store.server).host ?: Store.server, Palette.SUB) { editServer() }
        item("Alarmas puntuales", if (Retos.puntualAllowed(this)) "Permitidas" else "Permitir", if (Retos.puntualAllowed(this)) Palette.OK else Palette.YELLOW) {
            askExactAlarms()
        }
        add(rows, 8)

        add(caps("Reglas y privacidad"), 20)
        val legal = card(padDp = 0)
        fun legalItem(name: String, onClick: () -> Unit) {
            if (legal.childCount > 0) legal.addView(divider(), dividerLp())
            val line = row().apply {
                setPadding(dp(16), dp(14), dp(14), dp(14))
                background = rowRipple()
                isClickable = true
                setOnClickListener { onClick() }
            }
            line.addView(label(name, 15f, bold = true), lp(0, WRAP, weight = 1f))
            line.addView(icon(R.drawable.ic_chevron, Palette.FAINT, 16))
            legal.addView(line, lp())
        }
        legalItem("Términos de uso") { showLegal("Términos de uso", Legal.TERMS_ASSET) }
        legalItem("Política de privacidad") { showLegal("Política de privacidad", Legal.PRIVACY_ASSET) }
        legalItem("Ayuda y contacto") { showContact() }
        add(legal, 8)
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
                    if (BuildConfig.STORE_BUILD) "pásasela a tus amigos y déjale una reseña: eso la ayuda a crecer."
                    else "una donación ayuda a mantenerla y a agregar sonidos nuevos.",
                15f, Palette.SUB,
            ).apply { gravity = Gravity.CENTER },
            lp(topDp = 8),
        )
        add(hero, 4)

        // En Google Play no se permite donar por un enlace externo: se ofrece la reseña.
        if (!BuildConfig.STORE_BUILD) addDonation()

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
        if (BuildConfig.STORE_BUILD) {
            way(R.drawable.ic_star, Palette.YELLOW, "Déjale una reseña en Google Play") { openStorePage() }
        } else {
            way(R.drawable.ic_star, Palette.YELLOW, "Déjale una reseña cuando esté en Play Store", null)
        }
        add(other, 20)
        if (!BuildConfig.STORE_BUILD) {
            add(
                label("Las donaciones son voluntarias y no desbloquean nada: la app es igual para todos.", 12f, Palette.SUB)
                    .apply { gravity = Gravity.CENTER },
                18,
            )
        }
    }

    private fun addDonation() {
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

    // ------------------------------------------------------------------ Chats

    private fun buildComposer() {
        composerInput = EditText(this).apply {
            hint = "Escribe un mensaje…"
            setHintTextColor(Palette.FAINT)
            setTextColor(Palette.TEXT)
            textSize = 16f
            typeface = Fonts.body(context, 500)
            background = rounded(Palette.SURFACE, 22)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            minHeight = dp(46)
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(Chats.MAX_TEXT))
            contentDescription = "Mensaje"
        }
        composer = row().apply {
            setBackgroundColor(Palette.NAV)
            gravity = Gravity.BOTTOM
            visibility = View.GONE
        }
        composer.addView(composerInput, lp(0, WRAP, weight = 1f))
        composer.addView(space(8))
        composer.addView(iconButton(R.drawable.ic_send, "Enviar mensaje", Palette.ACCENT, Palette.INK, 46) { sendChat() })
    }

    private fun sendChat() {
        val key = chatKey ?: return
        val text = composerInput.text.toString().trim()
        if (text.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSendTextAt < 400) return
        lastSendTextAt = now
        composerInput.setText("")
        // Si no sale, se devuelve lo escrito para no perderlo.
        runInBackground({ Chats.send(key, text) }, onError = { if (composerInput.text.isEmpty()) composerInput.setText(text) }) {}
    }

    /** En Sonar: el candado activo y los retos que están por sonar. */
    private fun addGameBanners() {
        Locks.anyActive()?.let { lock ->
            val name = Store.friend(lock.friend)?.name ?: "un amigo"
            val body = liveText(13f, Palette.SUB, "Ninguno puede detener los sonidos del otro hasta las ${Locks.untilText(lock)}", bold = false) {
                "Ninguno puede detener los sonidos del otro. Quedan ${Locks.remainingText(lock.until)}."
            }
            deadline(lock.until)
            banner(icon(R.drawable.ic_lock, Palette.YELLOW, 24), "Candado con $name", body, "Chat", Palette.YELLOW) {
                openChat(Chats.friendKey(lock.friend))
            }
        }
        Store.retos().filter { it.state == Reto.WAITING && Chats.title(it.chat) != null }.sortedBy { it.at }.take(2).forEach { reto ->
            val kind = Retos.kindOf(reto.kind)
            val body = liveText(13f, Palette.SUB, "${kind.title} · suena a las ${timeText(reto.at)}", bold = false) {
                "${kind.title} · suena en ${Locks.remainingText(reto.at)}"
            }
            deadline(reto.at)
            banner(label(kind.emoji, 22f), "Reto en ${Chats.title(reto.chat)}", body, "Ver", Palette.ACCENT) { openChat(reto.chat) }
        }
    }

    private fun renderChats() {
        screenTitle("Chats", "Escríbele a tus amigos y grupos, y arma retos.")
        val keys = Chats.keys()
        if (keys.isEmpty()) {
            add(bullaSays("¡Aquí vamos a conversar! Primero agrega a alguien."), 14)
            val box = card()
            box.addView(title("Todavía no tienes con quién chatear", 18f, weight = 600), lp())
            box.addView(
                label("Agrega a un amigo con su código o únete a un grupo, y aquí aparecen sus chats.", 13f, Palette.SUB),
                lp(topDp = 4),
            )
            box.addView(button("Agregar amigo", heightDp = 44, radiusDp = 14, sizeSp = 14f) {
                openTab(Tab.FRIENDS)
                showAddFriend()
            }, lp(topDp = 12))
            add(box, 16)
            return
        }
        val unread = Store.unreadCounts()
        val list = card(padDp = 0)
        keys.forEach { key ->
            val name = Chats.title(key) ?: return@forEach
            val group = Chats.group(key)
            val last = Store.chat(key).lastOrNull()
            val count = unread[key] ?: 0
            if (list.childCount > 0) list.addView(divider(), dividerLp())
            val line = row().apply {
                setPadding(dp(16), dp(12), dp(14), dp(12))
                background = rowRipple()
                isClickable = true
                contentDescription = "Chat con $name" + if (count > 0) ", $count sin leer" else ""
                setOnClickListener { openChat(key) }
            }
            line.addView(chatAvatar(key, 46))
            val texts = column()
            val streak = if (group == null) Streaks.badge(Chats.codeOf(key)) else null
            texts.addView(label(if (streak != null) "$name  $streak" else name, 16f, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, lp())
            val lock = if (group == null) Locks.activeWith(Chats.codeOf(key)) else null
            val preview = when {
                lock != null -> "🔒 Candado activo hasta las ${Locks.untilText(lock)}"
                last == null -> if (group?.community == true) "Comunidad · toca para escribir" else "Toca para escribir"
                last.kind == ChatMessage.INFO -> last.text
                else -> (if (last.from == Store.myCode) "Tú: " else if (group != null) "${last.name}: " else "") + Chats.preview(last)
            }
            texts.addView(label(preview, 13f, if (count > 0) Palette.TEXT else Palette.SUB, bold = count > 0).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, lp())
            line.addView(texts, lp(0, WRAP, weight = 1f).apply {
                marginStart = dp(12)
                marginEnd = dp(8)
            })
            val side = column().apply { gravity = Gravity.END }
            if (last != null) side.addView(label(shortTime(last.time), 11f, Palette.SUB), LinearLayout.LayoutParams(WRAP, WRAP))
            if (count > 0) {
                side.addView(label(if (count > 99) "99+" else count.toString(), 11f, Palette.INK, bold = true).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    background = rounded(Palette.ACCENT, 999)
                    setPadding(dp(7), dp(2), dp(7), dp(2))
                    minWidth = dp(22)
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
            }
            line.addView(side)
            list.addView(line, lp())
        }
        add(list, 16)
        add(label("⚡ En cada chat puedes crear un reto y 🔒 con un amigo, proponer un candado.", 12f, Palette.SUB), 10)
    }

    private fun chatAvatar(key: String, sizeDp: Int): View {
        val group = Chats.group(key)
        if (group == null) return personAvatar(Chats.codeOf(key), Chats.title(key) ?: "?", sizeDp)
        val badge = FrameLayout(this).apply {
            background = rounded(if (group.isAdmin) Palette.YELLOW else Palette.avatar(group.id), sizeDp / 3)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }
        if (group.community && group.emoji.isNotEmpty()) {
            badge.addView(label(group.emoji, sizeDp * 0.45f).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            badge.addView(icon(R.drawable.ic_nav_groups, Palette.INK, sizeDp / 2), FrameLayout.LayoutParams(dp(sizeDp / 2), dp(sizeDp / 2), Gravity.CENTER))
        }
        return badge
    }

    private fun timeText(time: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time))

    private fun shortTime(time: Long): String =
        if (DateUtils.isToday(time)) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time))
        else DateUtils.formatDateTime(this, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

    private fun renderChat(key: String) {
        val group = Chats.group(key)
        val friend = Chats.friend(key)
        val name = Chats.title(key) ?: return
        if (started) {
            Store.markChatRead(key)
            Notifs.cancelChat(this, key)
        }

        // Encabezado fijo: no se va con el scroll.
        val head = row().apply {
            setPadding(dp(8), dp(8), dp(12), dp(8))
            setBackgroundColor(Palette.NAV)
        }
        head.addView(iconButton(R.drawable.ic_back, "Volver", 0, Palette.TEXT) { closePage() })
        head.addView(chatAvatar(key, 40))
        val texts = column()
        texts.addView(title(name, 18f, weight = 600).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp())
        val lock = friend?.let { Locks.activeWith(it.code) }
        val subtitle = when {
            lock != null -> {
                deadline(lock.until)
                liveText(12f, Palette.YELLOW, "Candado · quedan ${Locks.remainingText(lock.until)}, hasta las ${Locks.untilText(lock)}") {
                    "🔒 Candado · quedan ${Locks.remainingText(lock.until)}"
                }
            }
            else -> label(
                if (group != null) (if (group.community) "Comunidad · " else "Grupo · ") +
                    "${group.members.size} ${if (group.members.size == 1) "miembro" else "miembros"}"
                else "Chat con tu amigo",
                12f, Palette.SUB,
            ).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        }
        texts.addView(subtitle, lp())
        head.addView(texts, lp(0, WRAP, weight = 1f).apply {
            marginStart = dp(10)
            marginEnd = dp(6)
        })
        head.addView(iconButton(R.drawable.ic_bolt, "Crear un reto", Palette.SURFACE2, Palette.YELLOW, 40) { showCreateReto(key) })
        if (friend != null) {
            head.addView(space(6))
            head.addView(
                iconButton(
                    R.drawable.ic_lock, if (lock != null) "Candado activo" else "Proponer un candado",
                    if (lock != null) Palette.YELLOW else Palette.SURFACE2, if (lock != null) Palette.INK else Palette.TEXT, 40,
                ) { if (lock != null) info("Candado con ${friend.name}", lockRules(lock)) else showProposeLock(friend) },
            )
        }
        if (group != null) {
            head.addView(space(6))
            head.addView(iconButton(R.drawable.ic_trophy, "Ranking de ${group.name}", Palette.SURFACE2, Palette.YELLOW, 40) {
                openPage(PAGE_RANKING + key)
            })
        }
        head.addView(space(6))
        head.addView(iconButton(R.drawable.ic_star, "Decorar el chat", Palette.SURFACE2, Palette.ACCENT_TEXT, 40) { chooseChatTheme(key) })
        topBar.addView(head, lp())
        topBar.addView(divider(Palette.LINE), lp(MATCH, dp(1)))

        content.setPadding(dp(14), dp(12), dp(14), dp(16))
        val theme = Looks.chatTheme(key)
        content.background = if (theme.background.size > 1) {
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, theme.background)
        } else {
            ColorDrawable(theme.background[0])
        }
        val all = Store.chat(key)
        val messages = all.takeLast(120)
        chatTail = key + "/" + (all.lastOrNull()?.id ?: "") + "/" + all.size
        if (messages.isEmpty()) {
            val hint = if (friend != null) "Escribe el primer mensaje 👋\nCon ⚡ creas un reto y con 🔒 propones un candado."
            else "Escribe el primer mensaje 👋\nCon ⚡ creas un reto para todo el grupo."
            add(label(hint, 14f, Palette.SUB).apply { gravity = Gravity.CENTER }, 40)
            return
        }
        var previous: String? = null
        messages.forEach { message ->
            when (message.kind) {
                ChatMessage.INFO -> {
                    add(infoLine(message.text), 8)
                    previous = null
                }
                ChatMessage.RETO -> {
                    Store.reto(message.ref)?.let { add(retoCard(it), 10) }
                    previous = null
                }
                ChatMessage.LOCK -> {
                    Store.lock(message.ref)?.let { add(lockCard(it, message.from == Store.myCode), 10) }
                    previous = null
                }
                else -> {
                    val sameSender = message.from == previous
                    add(bubble(key, message, theme, showName = group != null && !sameSender), if (sameSender) 4 else 10)
                    previous = message.from
                }
            }
        }
    }

    private fun bubble(key: String, message: ChatMessage, theme: Looks.Theme, showName: Boolean): View {
        val mine = message.from == Store.myCode
        val line = row().apply { gravity = if (mine) Gravity.END else Gravity.START }
        val box = column().apply {
            background = rounded(if (mine) theme.mine else theme.theirs, 18)
            setPadding(dp(12), dp(8), dp(12), dp(6))
            isLongClickable = true
            contentDescription = (if (mine) "Tú" else message.name) + ": " + message.text
            setOnLongClickListener {
                messageOptions(key, message)
                true
            }
        }
        if (showName && !mine) box.addView(label(message.name, 12f, Palette.avatar(message.from), bold = true), LinearLayout.LayoutParams(WRAP, WRAP))
        val textColor = if (mine) theme.mineText else theme.theirsText
        box.addView(label(message.text, 15f, textColor).apply {
            maxWidth = (resources.displayMetrics.widthPixels * 0.72f).toInt()
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        box.addView(
            label(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.time)), 10f, (textColor and 0x00FFFFFF) or 0xB3000000.toInt())
                .apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(MATCH, WRAP),
        )
        line.addView(box, LinearLayout.LayoutParams(WRAP, WRAP))
        return line
    }

    private fun infoLine(text: String): View = row().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(label(text, 12f, Palette.SUB, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(Palette.SURFACE, 999)
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }, LinearLayout.LayoutParams(WRAP, WRAP))
    }

    private fun messageOptions(key: String, message: ChatMessage) {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        options += "📋 Copiar" to {
            copyText("Mensaje", message.text)
            toast("Copiado")
        }
        if (message.from != Store.myCode && message.from.isNotEmpty()) {
            options += "🚩 Denunciar a ${message.name}" to { report(message.from, message.name, message.text) }
        }
        AlertDialog.Builder(this)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    // ------------------------------------------------------------------ Retos

    private fun retoCard(reto: Reto): View {
        val kind = Retos.kindOf(reto.kind)
        val sound = Sounds.byKey(reto.sound)
        val mine = reto.from == Store.myCode
        val box = card(Palette.SURFACE2, 20, 14)
        val head = row()
        head.addView(label(kind.emoji, 26f).apply { includeFontPadding = false })
        val texts = column()
        texts.addView(title("Reto: ${kind.title}", 17f, weight = 600), lp())
        texts.addView(
            label("${if (mine) "Lo creaste tú" else "Lo creó ${reto.fromName}"} · ${sound?.emoji ?: "🔊"} ${sound?.label ?: reto.sound}", 13f, Palette.SUB),
            lp(),
        )
        head.addView(texts, lp(0, WRAP, weight = 1f).apply { marginStart = dp(10) })
        box.addView(head, lp())
        val status = label("", 15f, Palette.YELLOW, bold = true)
        when (reto.state) {
            Reto.WAITING -> deadline(reto.at)
            Reto.RANG -> {
                status.text = if (reto.kind == Retos.FASTEST) "¡Sonó! Esperando los tiempos de todos…" else "¡Sonó para todos!"
                deadline(reto.at + 91_000L)
            }
            Reto.DONE -> status.text = if (reto.kind == Retos.FASTEST) "Terminó" else "¡Sonó para todos!"
            Reto.MISSED -> {
                status.text = "No sonó en tu celular: estaba sin conexión a esa hora"
                status.setTextColor(Palette.SUB)
            }
            else -> {
                status.text = "Cancelado"
                status.setTextColor(Palette.SUB)
            }
        }
        if (reto.state == Reto.WAITING) {
            box.addView(
                liveText(15f, Palette.YELLOW, "Suena en ${Locks.remainingText(reto.at)}, a las ${timeText(reto.at)}") {
                    "Suena en ${Locks.remainingText(reto.at)}"
                },
                lp(topDp = 10),
            )
        } else {
            box.addView(status, lp(topDp = 10))
        }
        if (reto.kind == Retos.FASTEST && reto.results.isNotEmpty()) {
            val medals = listOf("🥇", "🥈", "🥉")
            Retos.ranking(reto).forEachIndexed { i, result ->
                val who = if (result.code == Store.myCode) "Tú" else result.name
                box.addView(label("${medals.getOrElse(i) { "${i + 1}." }} $who · ${Retos.seconds(result.ms)}", 14f, bold = i == 0), lp(topDp = 4))
            }
        }
        if (reto.state == Reto.WAITING) {
            box.addView(label(kind.text, 12f, Palette.SUB), lp(topDp = 4))
            if (!Retos.puntualAllowed(this)) {
                box.addView(
                    label("Para que suene a la hora exacta, permite las alarmas puntuales en Tú → Ajustes.", 12f, Palette.YELLOW),
                    lp(topDp = 4),
                )
            }
            if (mine) {
                box.addView(
                    button("Cancelar reto", Palette.SURFACE, Palette.TEXT, heightDp = 40, radiusDp = 12, sizeSp = 13f) { cancelReto(reto) },
                    lp(topDp = 10),
                )
            }
        }
        return box
    }

    /** Botón chiquito para elegir una opción (minutos, sonido, emoji). */
    private fun chip(text: String, selected: Boolean, onClick: () -> Unit): View =
        label(text, 14f, if (selected) Palette.INK else Palette.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            background = clickable(rounded(if (selected) Palette.YELLOW else Palette.SURFACE, 999))
            setPadding(dp(14), 0, dp(14), 0)
            isClickable = true
            isSelected = selected
            contentDescription = text + if (selected) ", elegido" else ""
            setOnClickListener { onClick() }
        }

    private fun chipRow(): Pair<HorizontalScrollView, LinearLayout> {
        val chips = row()
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }
        return scroller to chips
    }

    private fun showCreateReto(key: String) {
        val name = Chats.title(key) ?: return
        val sounds = Sounds.all
        var kind = Retos.FASTEST
        var delay = Retos.DEFAULT_DELAY_S
        // Con «A una hora» se guarda la hora exacta: el tiempo se cuenta al tocar Crear reto.
        var clockAt: Long? = null
        var sound = sounds.firstOrNull { it.key == "sirena" } ?: sounds.first()
        val kinds = column()
        val (timeScroll, times) = chipRow()
        val (soundScroll, soundChips) = chipRow()
        val whenText = label("", 13f, Palette.SUB)
        lateinit var refresh: () -> Unit
        refresh = {
            kinds.removeAllViews()
            Retos.KINDS.forEach { option ->
                val selected = option.key == kind
                val item = row().apply {
                    background = clickable(rounded(if (selected) Palette.ACCENT_SOFT else Palette.SURFACE, 16, if (selected) Palette.ACCENT else null))
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    isClickable = true
                    isSelected = selected
                    contentDescription = option.title + if (selected) ", elegido" else ""
                    setOnClickListener {
                        kind = option.key
                        refresh()
                    }
                }
                item.addView(label(option.emoji, 24f))
                val texts = column()
                texts.addView(label(option.title, 15f, bold = true), lp())
                texts.addView(label(option.text, 12f, Palette.SUB), lp())
                item.addView(texts, lp(0, WRAP, weight = 1f).apply { marginStart = dp(10) })
                kinds.addView(item, lp(topDp = 8))
            }
            times.removeAllViews()
            val at = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(clockAt ?: (System.currentTimeMillis() + delay * 1000)))
            // Con una hora exacta, el tiempo que falta se muestra en minutos redondos.
            val left = Retos.delayText(if (clockAt != null) maxOf(60, (delay + 30) / 60 * 60) else delay)
            // Un tiempo elegido a mano (o una hora) va primero, marcado.
            if (delay !in Retos.DELAYS_S || clockAt != null) {
                val text = if (clockAt != null) "🕐 $at" else "⏱️ $left"
                times.addView(chip(text, true) {}, LinearLayout.LayoutParams(WRAP, dp(40)).apply { marginEnd = dp(8) })
            }
            Retos.DELAYS_S.forEach { value ->
                times.addView(chip(Retos.delayText(value), value == delay && clockAt == null) {
                    delay = value
                    clockAt = null
                    refresh()
                }, LinearLayout.LayoutParams(WRAP, dp(40)).apply { marginEnd = dp(8) })
            }
            whenText.text = "Suena a las $at (en $left)."
            soundChips.removeAllViews()
            sounds.forEach { option ->
                soundChips.addView(chip("${option.emoji} ${option.label}", option.key == sound.key) {
                    sound = option
                    refresh()
                }, LinearLayout.LayoutParams(WRAP, dp(40)).apply { marginEnd = dp(8) })
            }
        }
        refresh()
        val body = column().apply { setPadding(dp(22), dp(4), dp(22), dp(4)) }
        body.addView(caps("Tipo de reto"), lp(topDp = 4))
        body.addView(kinds, lp())
        body.addView(caps("¿En cuánto tiempo suena?"), lp(topDp = 16))
        body.addView(timeScroll, lp(topDp = 8))
        val custom = row()
        custom.addView(chip("✏️ Otro tiempo", false) {
            askRetoTime(delay) {
                delay = it
                clockAt = null
                refresh()
            }
        }, LinearLayout.LayoutParams(WRAP, dp(40)).apply { marginEnd = dp(8) })
        custom.addView(chip("🕐 A una hora", false) {
            askRetoClock { at ->
                clockAt = at
                delay = (at - System.currentTimeMillis()) / 1000
                refresh()
            }
        }, LinearLayout.LayoutParams(WRAP, dp(40)))
        body.addView(custom, lp(topDp = 8))
        body.addView(whenText, lp(topDp = 8))
        body.addView(caps("Sonido"), lp(topDp = 16))
        body.addView(soundScroll, lp(topDp = 8))
        body.addView(label("Suena a la vez en el celular de todos los del chat, también en el tuyo.", 12f, Palette.SUB), lp(topDp = 12))
        AlertDialog.Builder(this)
            .setTitle("Nuevo reto en «$name»")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Crear reto") { _, _ ->
                val seconds = clockAt?.let { (it - System.currentTimeMillis()) / 1000 } ?: delay
                createReto(key, kind, sound.key, seconds.coerceIn(Retos.MIN_DELAY_S, Retos.MAX_DELAY_S))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun createReto(key: String, kind: String, soundKey: String, delaySeconds: Long) {
        val payload = Retos.create(this, key, kind, soundKey, delaySeconds)
        if (chatKey != key) openChat(key)
        runInBackground({ Relay.publishTopic(Chats.topic(key), payload) }) {
            toast("⚡ Reto creado: suena en ${Retos.delayText(delaySeconds)} para todos")
        }
    }

    /** Elegir a mano en cuánto suena el reto: horas y minutos, de 1 minuto a 6 horas. */
    private fun askRetoTime(current: Long, onPick: (Long) -> Unit) {
        fun field(value: Long, description: String, digits: Int) = EditText(this).apply {
            setText(value.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(digits))
            setSelectAllOnFocus(true)
            setSingleLine(true)
            textSize = 24f
            gravity = Gravity.CENTER
            contentDescription = description
        }
        val hours = field(current / 3600, "Horas del reto", 1)
        val minutes = field(current % 3600 / 60, "Minutos del reto", 3)
        val line = row()
        line.addView(hours, lp(0, WRAP, weight = 1f))
        line.addView(label("h", 18f, Palette.SUB, bold = true), LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(8), 0, dp(16), 0) })
        line.addView(minutes, lp(0, WRAP, weight = 1f))
        line.addView(label("min", 18f, Palette.SUB, bold = true), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        val body = padded(line, label("De 1 minuto a 6 horas.", 13f, Palette.SUB))
        val dialog = AlertDialog.Builder(this)
            .setTitle("¿En cuánto tiempo suena?")
            .setView(body)
            .setPositiveButton("Listo", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val total = (hours.text.toString().toLongOrNull() ?: 0) * 3600 + (minutes.text.toString().toLongOrNull() ?: 0) * 60
                if (total < 60 || total > Retos.MAX_DELAY_S) {
                    toast("Elige entre 1 minuto y 6 horas")
                } else {
                    onPick(total)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    /** Elegir la hora exacta del reto (dentro de las próximas 6 horas); [onPick] recibe ese momento. */
    private fun askRetoClock(onPick: (Long) -> Unit) {
        val now = Calendar.getInstance()
        TimePickerDialog(this, { _, hour, minute ->
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
            }
            val seconds = (target.timeInMillis - System.currentTimeMillis()) / 1000
            when {
                seconds > Retos.MAX_DELAY_S -> toast("Tiene que ser dentro de las próximas 6 horas")
                seconds < Retos.MIN_DELAY_S -> toast("Esa hora es ahora: elige una más adelante")
                else -> onPick(target.timeInMillis)
            }
        }, (now.get(Calendar.HOUR_OF_DAY) + 1) % 24, now.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(this)).show()
    }

    private fun cancelReto(reto: Reto) {
        confirm("¿Cancelar el reto?", "Ya no va a sonar para nadie.") {
            val payload = Retos.cancel(this, reto.id) ?: return@confirm
            Chats.info(reto.chat, "Cancelaste el reto")
            runInBackground({ Relay.publishTopic(Chats.topic(reto.chat), payload) }) {}
        }
    }

    // ------------------------------------------------------------------ Candado

    private fun lockCard(lock: Lock, byMe: Boolean): View {
        val name = Store.friend(lock.friend)?.name ?: "Tu amigo"
        val box = card(Palette.SURFACE2, 20, 14)
        val head = row()
        head.addView(icon(R.drawable.ic_lock, Palette.YELLOW, 26))
        val texts = column()
        texts.addView(title(if (byMe) "Propusiste un candado" else "$name propone un candado", 17f, weight = 600), lp())
        texts.addView(
            label("${lock.minutes} minutos en que ninguno podrá detener, silenciar ni pausar los sonidos del otro.", 13f, Palette.SUB),
            lp(),
        )
        head.addView(texts, lp(0, WRAP, weight = 1f).apply { marginStart = dp(10) })
        box.addView(head, lp())
        val pending = Locks.isPending(lock)
        when {
            lock.state == Lock.ASKED && pending -> {
                val actions = row()
                actions.addView(
                    button("Aceptar", Palette.YELLOW, Palette.INK, iconRes = R.drawable.ic_lock, heightDp = 44, radiusDp = 14, sizeSp = 14f) {
                        acceptLock(lock)
                    },
                    lp(0, WRAP, weight = 1f),
                )
                actions.addView(space(8))
                actions.addView(
                    button("No, gracias", Palette.SURFACE, Palette.TEXT, heightDp = 44, radiusDp = 14, sizeSp = 14f) { rejectLock(lock) },
                    lp(0, WRAP, weight = 1f),
                )
                box.addView(actions, lp(topDp = 12))
                deadline(Locks.expiresAt(lock))
            }
            lock.state == Lock.OFFERED && pending -> {
                box.addView(label("Esperando que $name acepte…", 14f, Palette.YELLOW, bold = true), lp(topDp = 10))
                box.addView(
                    button("Retirar propuesta", Palette.SURFACE, Palette.TEXT, heightDp = 40, radiusDp = 12, sizeSp = 13f) { rejectLock(lock) },
                    lp(topDp = 8),
                )
                deadline(Locks.expiresAt(lock))
            }
            lock.isActive -> {
                box.addView(
                    liveText(15f, Palette.YELLOW, "Activo hasta las ${Locks.untilText(lock)}") { "Activo · quedan ${Locks.remainingText(lock.until)}" },
                    lp(topDp = 10),
                )
                deadline(lock.until)
            }
            lock.state == Lock.ACTIVE || lock.state == Lock.DONE -> box.addView(label("✅ Cumplido", 14f, Palette.OK, bold = true), lp(topDp = 10))
            lock.state == Lock.REJECTED -> box.addView(label("No se hizo", 14f, Palette.SUB, bold = true), lp(topDp = 10))
            else -> box.addView(label("La propuesta venció", 14f, Palette.SUB, bold = true), lp(topDp = 10))
        }
        return box
    }

    private fun showProposeLock(friend: Friend) {
        var minutes = Locks.DEFAULT_MINUTES
        val (timeScroll, times) = chipRow()
        lateinit var refresh: () -> Unit
        refresh = {
            times.removeAllViews()
            Locks.MINUTES.forEach { value ->
                times.addView(chip("$value min", value == minutes) {
                    minutes = value
                    refresh()
                }, LinearLayout.LayoutParams(WRAP, dp(40)).apply { marginEnd = dp(8) })
            }
        }
        refresh()
        val body = column().apply { setPadding(dp(22), dp(4), dp(22), dp(4)) }
        body.addView(
            label(
                "Si ${friend.name} acepta, durante ese tiempo ninguno de los dos podrá detener, silenciar ni pausar " +
                    "los sonidos del otro, ni eliminarse o bloquearse.",
                14f,
            ),
            lp(),
        )
        body.addView(caps("¿Cuánto dura?"), lp(topDp = 16))
        body.addView(timeScroll, lp(topDp = 8))
        body.addView(
            label(
                "Siguen los límites de siempre: cada sonido dura como mucho 10 segundos y nadie puede mandar más de 4 cada 20 segundos.",
                12f, Palette.SUB,
            ),
            lp(topDp = 12),
        )
        AlertDialog.Builder(this)
            .setTitle("🔒 Candado con ${friend.name}")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Proponer") { _, _ ->
                val payload = Locks.propose(friend.code, minutes)
                openChat(Chats.friendKey(friend.code))
                runInBackground({ Relay.publish(friend.code, payload) }) {
                    toast("🔒 Propuesta enviada. Empieza cuando ${friend.name} acepte.")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun acceptLock(lock: Lock) {
        val name = Store.friend(lock.friend)?.name ?: "tu amigo"
        confirm(
            "¿Aceptar el candado?",
            "Durante ${lock.minutes} minutos ni tú ni $name podrán detener, silenciar ni pausar los sonidos del otro.",
        ) {
            val payload = Locks.accept(this, lock.id)
            if (payload == null) {
                toast("La propuesta ya venció")
                return@confirm
            }
            val until = Store.lock(lock.id)?.let { Locks.untilText(it) } ?: ""
            Chats.info(Chats.friendKey(lock.friend), "🔒 Aceptaste el candado: hasta las $until")
            runInBackground({ Relay.publish(lock.friend, payload) }) {}
        }
    }

    private fun rejectLock(lock: Lock) {
        val payload = Locks.reject(lock.id) ?: return
        runInBackground({ Relay.publish(lock.friend, payload) }) {}
    }

    private fun lockRules(lock: Lock): String {
        val name = Store.friend(lock.friend)?.name ?: "tu amigo"
        return "Hasta las ${Locks.untilText(lock)} (quedan ${Locks.remainingText(lock.until)}) ni tú ni $name pueden " +
            "detener, silenciar ni pausar los sonidos del otro, ni eliminarse o bloquearse.\n\n" +
            "Cada sonido sigue durando como mucho 10 segundos y nadie puede mandar más de 4 cada 20 segundos. " +
            "Si alguien te molesta de verdad, «Denunciar» siempre funciona."
    }

    /** true (y avisa) si hay un candado con [friend] (pudo empezar con el menú abierto). */
    private fun lockedWith(friend: Friend): Boolean {
        val lock = Locks.activeWith(friend.code) ?: return false
        toast("🔒 Tienes un candado con ${friend.name} hasta las ${Locks.untilText(lock)}")
        return true
    }

    /** true (y avisa) si un candado impide cambiar cómo se reciben los sonidos. */
    private fun lockBlocks(): Boolean {
        val lock = Locks.anyActive() ?: return false
        val name = Store.friend(lock.friend)?.name ?: "un amigo"
        toast("🔒 Tienes un candado con $name hasta las ${Locks.untilText(lock)}: no se puede cambiar hasta que termine")
        // Vuelve el interruptor a como estaba.
        ui.post { render() }
        return true
    }

    // ------------------------------------------------------------------ Pestaña Tú

    private fun renderMe() {
        val total = Points.total()
        val level = Points.level(total)
        val next = Points.next(level)
        val top = row()
        top.addView(title("Tú", 30f), lp(0, WRAP, weight = 1f))
        top.addView(iconButton(R.drawable.ic_nav_settings, "Ajustes", Palette.SURFACE) { openPage(PAGE_SETTINGS) })
        add(top)

        val hero = card(Palette.SURFACE, 24, 18)
        val head = row()
        head.addView(personAvatar(Store.myCode, Store.myName, 64).apply {
            isClickable = true
            contentDescription = "Cambiar tu avatar"
            setOnClickListener { editAvatar() }
        })
        val texts = column()
        texts.addView(title(Store.myName, 22f, weight = 600).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp())
        texts.addView(label("Nivel ${level.number} · ${level.name}", 14f, Palette.YELLOW, bold = true), lp())
        texts.addView(label("Cambiar avatar", 13f, Palette.ACCENT_TEXT, bold = true).apply {
            setOnClickListener { editAvatar() }
        }, lp(topDp = 2))
        head.addView(texts, lp(0, WRAP, weight = 1f).apply { marginStart = dp(14) })
        hero.addView(head, lp())
        hero.addView(title("$total puntos", 30f), lp(topDp = 14))
        hero.addView(progressBar(Points.progress(total)), lp(MATCH, dp(10), topDp = 8))
        hero.addView(
            label(
                if (next != null) "Te faltan ${next.min - total} para el nivel ${next.number}: ${next.name}" else "¡Llegaste al nivel máximo!",
                13f, Palette.SUB,
            ),
            lp(topDp = 6),
        )
        add(hero, 12)

        val stats = row()
        fun stat(value: String, name: String) {
            val box = card(Palette.SURFACE, 18, 12).apply { gravity = Gravity.CENTER_HORIZONTAL }
            box.addView(title(value, 22f).apply { gravity = Gravity.CENTER }, lp())
            box.addView(label(name, 12f, Palette.SUB).apply { gravity = Gravity.CENTER }, lp())
            if (stats.childCount > 0) stats.addView(space(8))
            stats.addView(box, lp(0, WRAP, weight = 1f))
        }
        val streak = Points.streak
        stat("🔥 $streak", (if (streak == 1) "día seguido" else "días seguidos") + if (Points.freezes > 0) " · 🧊 ${Points.freezes}" else "")
        stat("${Points.week()}", "esta semana")
        stat("${Points.stat("retos_won")}", "retos ganados")
        add(stats, 10)

        Seasons.current()?.let { season ->
            val got = Points.hasLogro(season.logro)
            val box = card(0xFF3A1D0E.toInt(), 22, 14)
            val head = row()
            head.addView(label(season.emoji, 28f).apply { includeFontPadding = false })
            val texts = column()
            texts.addView(title("${season.name} · hasta ${season.until}", 16f, Palette.CUSTOM, 600), lp())
            texts.addView(label(if (got) "✅ Ya ganaste el logro de la temporada." else season.text, 13f, Palette.TEXT), lp(topDp = 2))
            head.addView(texts, lp(0, WRAP, weight = 1f).apply { marginStart = dp(12) })
            box.addView(head, lp())
            add(box, 14)
            Seasons.markSeen(season)
        }

        add(sectionTitle("Misiones de hoy", "${Quests.doneCount()} de 3"), 22)
        add(questsCard(), 10)

        add(sectionTitle("Tu mascota"), 22)
        addPetCard()

        val market = row().apply {
            background = clickable(rounded(Palette.SURFACE2, 20))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            isClickable = true
            stateListAnimator = Anim.press()
            setOnClickListener { openPage(PAGE_MARKET) }
        }
        val bag = FrameLayout(this).apply { background = candy(Palette.ACCENT, 12) }
        bag.addView(icon(R.drawable.ic_store, Palette.INK, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        market.addView(bag, LinearLayout.LayoutParams(dp(42), dp(42)))
        val marketTexts = column()
        marketTexts.addView(label("Mercado", 16f, bold = true), lp())
        val armed = Powers.armed
        val fresh = Sonoteca.unseen().size
        marketTexts.addView(
            label(
                (if (fresh > 0) "🎧 $fresh ${if (fresh == 1) "sonido nuevo" else "sonidos nuevos"} · " else "Sonoteca, poderes y protector de racha · ") +
                    (if (armed != null) "${armed.emoji} ${armed.name} listo · " else "") +
                    "${Points.balance()} pts",
                13f, if (fresh > 0) Palette.ACCENT_TEXT else Palette.SUB, bold = fresh > 0,
            ),
            lp(),
        )
        market.addView(marketTexts, lp(0, WRAP, weight = 1f).apply { marginStart = dp(12) })
        market.addView(icon(R.drawable.ic_chevron, Palette.FAINT, 16))
        add(market, 12)

        add(sectionTitle("Fondo de la app", "Los animados se mueven"), 22)
        val (bgScroll, bgChips) = chipRow()
        bgChips.gravity = Gravity.TOP
        val currentBg = Looks.background().key
        Looks.BACKGROUNDS.forEach { bg ->
            val selected = bg.key == currentBg
            val cell = column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                isSelected = selected
                stateListAnimator = Anim.press()
                contentDescription = "${bg.emoji} ${bg.name}" + (if (bg.scene != null) ", fondo animado" else ", fondo") + if (selected) ", elegido" else ""
                setOnClickListener {
                    Looks.setBackground(bg.key)
                    toast("${bg.emoji} Fondo: ${bg.name}")
                }
            }
            val swatch = FrameLayout(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, bg.colors).apply {
                    cornerRadius = dp(18).toFloat()
                    setStroke(dp(3), if (selected) Palette.TEXT else Palette.LIP)
                }
            }
            swatch.addView(label(bg.emoji, 24f).apply {
                includeFontPadding = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            cell.addView(swatch, LinearLayout.LayoutParams(dp(72), dp(96)))
            cell.addView(label("${bg.emoji} ${bg.name}", 12f, if (selected) Palette.TEXT else Palette.SUB, bold = selected).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(80), WRAP).apply { topMargin = dp(6) })
            bgChips.addView(cell, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) })
        }
        add(bgScroll, 10)
        add(label("Cuando suena o mandas un sonido, el fondo cambia un rato al de su categoría (se apaga en Ajustes).", 12f, Palette.SUB), 6)

        add(sectionTitle("Ranking con tus amigos", "Esta semana"), 22)
        val people = Store.friends().filter { it.state == Friend.FRIEND }.map { it.code to it.name }
        add(rankingList(Points.ranking(people, weekly = true).take(5)), 10)
        if (people.isEmpty()) add(label("Agrega amigos para competir con ellos.", 13f, Palette.SUB), 8)
        add(
            button(
                "Ver ranking completo", Palette.SURFACE2, Palette.TEXT, iconRes = R.drawable.ic_trophy,
                heightDp = 44, radiusDp = 14, sizeSp = 14f, iconColor = Palette.YELLOW,
            ) { openPage(PAGE_RANKING) },
            10,
        )

        val got = Points.LOGROS.count { Points.hasLogro(it.key) }
        add(sectionTitle("Logros", "$got de ${Points.LOGROS.size}"), 24)
        grid(Points.LOGROS.map { logroTile(it) })

        add(sectionTitle("Desbloqueas subiendo de nivel", "Colores, temas y mascotas"), 24)
        val list = card(padDp = 0)
        val unlockables = Looks.SWATCHES.filter { it.level > 1 }.map { Triple(it.level, "🎨", "Color ${it.name} para botones y avatar") } +
            Looks.THEMES.filter { it.level > 1 }.map { Triple(it.level, "💬", "Tema de chat ${it.name}") } +
            Pets.SPECIES.filter { it.level > 1 }.map { Triple(it.level, it.emoji, "Mascota ${it.name}") }
        unlockables.sortedBy { it.first }.forEach { (needed, emoji, text) ->
            if (list.childCount > 0) list.addView(divider(), dividerLp())
            val unlocked = needed <= level.number
            val line = row().apply { setPadding(dp(16), dp(10), dp(16), dp(10)) }
            line.addView(label(emoji, 20f))
            line.addView(label(text, 14f, bold = true), lp(0, WRAP, weight = 1f).apply { marginStart = dp(12) })
            line.addView(label(if (unlocked) "✅" else "🔒 Nivel $needed", 13f, if (unlocked) Palette.OK else Palette.SUB, bold = true))
            list.addView(line, lp())
        }
        add(list, 10)

        val help = card()
        help.addView(title("Cómo ganar puntos", 17f, weight = 600), lp())
        listOf(
            "🔊 Mandar un sonido: +2 (hasta 20 veces al día)",
            "👂 Recibir un sonido: +1",
            "💬 Escribir en el chat: +1 (hasta 10 al día)",
            "⚡ Crear un reto: +5 · jugarlo: +5",
            "🥇 Ganar «El más rápido»: +15 (2.º +8, 3.º +4)",
            "🔒 Cumplir un candado: +20",
            "🤝 Amigo nuevo: +10 · 📅 Abrir la app cada día: +5",
            "🎯 Cada misión del día: +10 · 🎁 el cofre trae un premio sorpresa",
            "📤 Compartir en la Sonoteca: +5 · ❤️ si a un amigo le gusta o lo usa: +3",
            "🔥 Racha con un amigo: mándense algo los dos cada día",
            "🧊 Un protector de racha la salva si un día no abres la app",
            "🛍️ Gasta puntos en el Mercado: no bajas de nivel ni en el ranking",
        ).forEach { help.addView(label(it, 13f, Palette.SUB), lp(topDp = 6)) }
        add(help, 20)

        add(
            button("Ajustes", Palette.SURFACE, Palette.TEXT, iconRes = R.drawable.ic_nav_settings, heightDp = 52, iconColor = Palette.ACCENT_TEXT) {
                openPage(PAGE_SETTINGS)
            },
            16,
        )
        addFooter()
    }

    /** Barra de avance gordita con brillo (diseño 2.0; ver ProgressPill). */
    private fun progressBar(fraction: Float, color: Int = Palette.YELLOW): View {
        return ProgressPill(this, fraction, color)
    }

    private fun logroTile(logro: Points.Logro): View {
        val got = Points.hasLogro(logro.key)
        return column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            minimumHeight = dp(104)
            setPadding(dp(6), dp(10), dp(6), dp(10))
            background = clickable(rounded(if (got) Palette.SURFACE2 else Palette.SURFACE, 20))
            isClickable = true
            alpha = if (got) 1f else 0.55f
            contentDescription = "${logro.title}: ${logro.text}" + if (got) ", conseguido" else ", pendiente"
            addView(label(if (got) logro.emoji else "🔒", 26f).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, lp())
            addView(label(logro.title, 12f, bold = true).apply {
                gravity = Gravity.CENTER
                maxLines = 2
            }, lp(topDp = 6))
            setOnClickListener {
                info(
                    "${logro.emoji} ${logro.title}",
                    "${logro.text}.\n\n" + if (got) "¡Ya lo conseguiste! (+${logro.bonus} puntos)" else "Da +${logro.bonus} puntos.",
                )
            }
        }
    }

    private fun rankingList(entries: List<Points.Entry>): View {
        val box = card(padDp = 0)
        val medals = listOf("🥇", "🥈", "🥉")
        entries.forEachIndexed { i, entry ->
            if (i > 0) box.addView(divider(), dividerLp())
            val line = row().apply {
                setPadding(dp(14), dp(10), dp(16), dp(10))
                if (entry.isMe) background = rounded(Palette.ACCENT_SOFT, 20)
                contentDescription = "Puesto ${i + 1}: ${if (entry.isMe) "tú" else entry.name}, ${entry.points} puntos"
            }
            line.addView(label(medals.getOrElse(i) { "${i + 1}" }, if (i < 3) 22f else 15f, Palette.SUB, bold = true).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(34), WRAP))
            line.addView(personAvatar(entry.code, entry.name, 36), LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(6) })
            val texts = column()
            val pet = Looks.profile(entry.code)?.pet?.let { Pets.emojiFor(it) }
            texts.addView(label((if (entry.isMe) "Tú" else entry.name) + if (pet != null) " $pet" else "", 15f, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, lp())
            texts.addView(label("Nivel ${entry.level} · ${Points.LEVELS[entry.level - 1].name}", 12f, Palette.SUB), lp())
            line.addView(texts, lp(0, WRAP, weight = 1f).apply { marginStart = dp(10) })
            line.addView(title("${entry.points}", 18f, Palette.YELLOW), LinearLayout.LayoutParams(WRAP, WRAP))
            box.addView(line, lp())
        }
        return box
    }

    private fun renderRanking(scope: String) {
        val group = if (scope.isEmpty()) null else Chats.group(scope)
        val top = row()
        top.addView(iconButton(R.drawable.ic_back, "Volver", Palette.SURFACE) { closePage() })
        val texts = column()
        texts.addView(title("Ranking", 28f), lp())
        texts.addView(label(group?.name ?: "Tú y tus amigos", 14f, Palette.SUB), lp())
        top.addView(texts, lp(0, WRAP, weight = 1f).apply { marginStart = dp(12) })
        add(top)
        if (scope.isNotEmpty() && group == null) {
            add(label("Ya no estás en ese grupo.", 14f, Palette.SUB), 16)
            return
        }
        val toggle = row()
        toggle.addView(chip("Esta semana", rankingWeekly) {
            rankingWeekly = true
            render()
        }, LinearLayout.LayoutParams(WRAP, dp(40)).apply { marginEnd = dp(8) })
        toggle.addView(chip("Total", !rankingWeekly) {
            rankingWeekly = false
            render()
        }, LinearLayout.LayoutParams(WRAP, dp(40)))
        add(toggle, 16)
        val people = group?.members?.map { it.code to it.name }
            ?: Store.friends().filter { it.state == Friend.FRIEND }.map { it.code to it.name }
        add(rankingList(Points.ranking(people, rankingWeekly)), 12)
        add(
            label(
                "Los puntos de los demás se actualizan cuando usan la app y te mandan algo. La semana empieza el lunes.",
                12f, Palette.SUB,
            ),
            10,
        )
    }

    // ------------------------------------------------------------------ Personalización

    /** Avatar de una persona: su emoji y color si los eligió, o su inicial. */
    private fun personAvatar(code: String, name: String, sizeDp: Int): TextView {
        val profile = Looks.profile(code)
        val swatch = Looks.swatch(profile?.swatch)
        val emoji = profile?.emoji.orEmpty()
        val view = if (emoji.isNotEmpty()) {
            label(emoji, sizeDp * 0.48f).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        } else {
            avatar(name, swatch?.main ?: Palette.avatar(code), sizeDp = sizeDp)
        }
        view.background = swatch?.oval(this) ?: oval(Palette.avatar(code))
        return view
    }

    /** A quién va un sonido ahora (el destino elegido en Sonar), o null. */
    private fun currentTarget(): Target? =
        targets(Store.friends().filter { it.state == Friend.FRIEND }, Store.groups().filter { !it.community }).firstOrNull { it.key == selectedTarget }

    private fun soundOptions(sound: Sound) {
        val look = Looks.button(sound.key)
        val name = look.label ?: sound.label
        val target = currentTarget()
        val options = mutableListOf<Pair<String, () -> Unit>>(
            "▶️ Escucharlo" to { previewSound(Player.Source.Asset(sound.asset), name, 0, Looks.vibe(sound.key), null) },
            "🎨 Personalizar botón" to { customizeButton(sound.key, sound.emoji, sound.color, sound.label, editName = true) },
        )
        if (target != null) {
            options.add(1, "📱 Escuchar como le llega a ${target.name}" to { playTrial(target, Player.Source.Asset(sound.asset), name, sound.key, 0) })
        }
        options += "🏷️ Categoría: ${Vibes.byKey(Looks.vibe(sound.key)).label}" to { chooseVibe(sound.key) }
        if (look.emoji != null || look.swatch != null || look.label != null) {
            options += "↩️ Volver al original" to { Looks.setButton(sound.key, Looks.Look()) }
        }
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /**
     * Elige la categoría (vibe) de un sonido: le da su color, su fondo animado y cómo reacciona la
     * mascota. [key]: "bocina" o "c:id".
     */
    private fun chooseVibe(key: String) {
        val current = Looks.vibe(key)
        val labels = Vibes.all.map { "${it.emoji} ${it.label}" + if (it.key == current) "  ✓" else "" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("¿De qué categoría es este sonido?")
            .setItems(labels) { _, which ->
                val vibe = Vibes.all[which]
                Looks.setVibe(key, vibe.key)
                toast("${vibe.emoji} Categoría: ${vibe.label}")
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Filas de opciones de [columns] por fila (emojis). */
    private fun optionGrid(items: List<String>, selected: String, columns: Int = 8, onPick: (String) -> Unit): View {
        val box = column()
        items.chunked(columns).forEach { chunk ->
            val line = row()
            chunk.forEach { item ->
                val chosen = item == selected
                line.addView(label(item, 22f).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    background = clickable(rounded(if (chosen) Palette.ACCENT_SOFT else Palette.SURFACE, 12, if (chosen) Palette.ACCENT else null))
                    isClickable = true
                    isSelected = chosen
                    contentDescription = item + if (chosen) ", elegido" else ""
                    setOnClickListener { onPick(item) }
                }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            repeat(columns - chunk.size) { line.addView(View(this), LinearLayout.LayoutParams(0, dp(42), 1f)) }
            box.addView(line, lp())
        }
        return box
    }

    /** Fila de colores: los que todavía no desbloqueaste salen con candado. */
    private fun swatchRow(selected: String?, withDefault: Int?, onPick: (Looks.Swatch?) -> Unit): View {
        val (scroller, line) = chipRow()
        val level = Points.level().number
        fun dot(swatch: Looks.Swatch?, chosen: Boolean, locked: Boolean, description: String, onClick: () -> Unit) {
            val cell = FrameLayout(this).apply {
                background = swatch?.oval(this@MainActivity)?.apply { if (chosen) setStroke(dp(3), Palette.TEXT) }
                    ?: oval(withDefault ?: Palette.SURFACE2, if (chosen) Palette.TEXT else null)
                alpha = if (locked) 0.4f else 1f
                isClickable = true
                isSelected = chosen
                contentDescription = description + if (chosen) ", elegido" else if (locked) ", bloqueado" else ""
                setOnClickListener { onClick() }
            }
            if (locked) {
                cell.addView(label("🔒", 14f).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            line.addView(cell, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
        }
        if (withDefault != null) dot(null, selected == null, false, "Color de fábrica") { onPick(null) }
        Looks.SWATCHES.forEach { swatch ->
            val locked = swatch.level > level
            dot(swatch, swatch.key == selected, locked, "Color ${swatch.name}") {
                if (locked) toast("🔒 El color ${swatch.name} se desbloquea en el nivel ${swatch.level}") else onPick(swatch)
            }
        }
        return scroller
    }

    /** Elige emoji, color y (en los sonidos integrados) nombre de un botón. */
    private fun customizeButton(key: String, defaultEmoji: String, defaultColor: Int, defaultLabel: String, editName: Boolean) {
        val current = Looks.button(key)
        var emoji = current.emoji ?: defaultEmoji
        var swatch = Looks.swatch(current.swatch)
        val nameInput = if (editName) {
            editText(current.label ?: defaultLabel, "Nombre del botón").apply { filters = arrayOf<InputFilter>(InputFilter.LengthFilter(24)) }
        } else {
            null
        }
        val preview = FrameLayout(this)
        val emojis = FrameLayout(this)
        val colors = FrameLayout(this)
        lateinit var refresh: () -> Unit
        refresh = {
            preview.removeAllViews()
            val name = nameInput?.text?.toString()?.trim()?.ifEmpty { null } ?: defaultLabel
            preview.addView(soundTile(emoji, name, defaultColor, {}, {}, swatch).apply { isClickable = false; isLongClickable = false },
                FrameLayout.LayoutParams(dp(130), WRAP, Gravity.CENTER_HORIZONTAL))
            emojis.removeAllViews()
            emojis.addView(optionGrid((listOf(defaultEmoji) + Looks.EMOJIS).distinct(), emoji) {
                emoji = it
                refresh()
            })
            colors.removeAllViews()
            colors.addView(swatchRow(swatch?.key, defaultColor) {
                swatch = it
                refresh()
            })
        }
        refresh()
        val body = column().apply { setPadding(dp(22), dp(4), dp(22), dp(4)) }
        body.addView(preview, lp())
        if (nameInput != null) body.addView(nameInput, lp(topDp = 12))
        body.addView(caps("Emoji"), lp(topDp = 14))
        body.addView(emojis, lp(topDp = 6))
        body.addView(button("✏️ Escribir otro emoji", Palette.SURFACE, Palette.TEXT, heightDp = 40, radiusDp = 12, sizeSp = 13f) {
            textDialog("Tu emoji", "Escribe o pega el emoji que quieras.", "", "😀", "Listo") { typed ->
                val clean = Looks.cleanEmoji(typed)
                if (clean == null) {
                    toast("Pon un emoji (o hasta 4 caracteres)")
                    false
                } else {
                    emoji = clean
                    refresh()
                    true
                }
            }
        }, lp(topDp = 6))
        body.addView(caps("Color"), lp(topDp = 14))
        body.addView(colors, lp(topDp = 6))
        body.addView(label("Los colores con 🔒 se desbloquean subiendo de nivel.", 12f, Palette.SUB), lp(topDp = 8))
        AlertDialog.Builder(this)
            .setTitle("Personalizar botón")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Guardar") { _, _ ->
                val name = nameInput?.text?.toString()?.trim()?.ifEmpty { null }
                Looks.setButton(
                    key,
                    Looks.Look(emoji.takeIf { it != defaultEmoji }, swatch?.key, name?.takeIf { it != defaultLabel }),
                )
                toast("🎨 Botón personalizado")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Para audios largos: desde dónde suena (10 segundos desde ahí, también en el celular de tus amigos). */
    private fun chooseStart(custom: CustomSound) {
        val file = custom.file(this)
        val duration = CustomAudio.durationOf(file)
        if (duration == null) {
            toast("No se pudo leer ese audio.")
            return
        }
        if (duration <= Player.MAX_PLAY_MS + 500) {
            info("Elegir la parte que suena", "Este audio dura ${clockText(duration)}: suena completo.")
            return
        }
        var start = custom.startMs.coerceIn(0, (duration - 1000).toInt())
        val text = label("", 15f, Palette.YELLOW, bold = true)
        fun update() {
            text.text = "Desde ${clockText(start.toLong())} hasta ${clockText(minOf(start + Player.MAX_PLAY_MS, duration))}"
        }
        update()
        val bar = android.widget.SeekBar(this).apply {
            max = ((duration - 1000) / 100).toInt()
            progress = start / 100
            contentDescription = "Desde dónde suena"
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                    start = value * 100
                    update()
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            })
        }
        val play = button("Escuchar desde ahí", Palette.SURFACE2, Palette.TEXT, iconRes = R.drawable.ic_nav_sound, heightDp = 44, radiusDp = 14, sizeSp = 14f) {
            Player.preview(this, Player.Source.FromFile(file), custom.label, start)
        }
        AlertDialog.Builder(this)
            .setTitle("¿Desde dónde suena «${custom.label}»?")
            .setView(padded(
                label("El audio dura ${clockText(duration)}. Elige la parte graciosa: suenan 10 segundos desde ahí, en tu celular y en el de tus amigos.", 14f),
                text, bar, play,
            ))
            .setPositiveButton("Guardar") { _, _ ->
                Player.stop()
                Store.putCustomSound(custom.copy(startMs = start))
                toast("✂️ Listo: suena desde ${clockText(start.toLong())}")
            }
            .setNegativeButton("Cancelar") { _, _ -> Player.stop() }
            .show()
    }

    private fun clockText(ms: Long): String {
        val seconds = ms / 1000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    /** En Sonar: el poder listo para el próximo sonido, o el acceso a la tienda de poderes. */
    private fun addPowerButton(line: LinearLayout) {
        val armed = Powers.armed
        line.addView(
            button(
                if (armed != null) "${armed.emoji} ${armed.name} listo" else "Poderes", if (armed != null) Palette.YELLOW else Palette.SURFACE,
                if (armed != null) Palette.INK else Palette.TEXT, iconRes = R.drawable.ic_star, heightDp = 44, radiusDp = 14, sizeSp = 14f,
                iconColor = if (armed != null) Palette.INK else Palette.YELLOW,
            ) { showPowers() },
            lp(0, WRAP, weight = 1f),
        )
    }

    private fun showPowers() {
        val body = column().apply { setPadding(dp(22), dp(4), dp(22), dp(4)) }
        body.addView(
            label("Tienes ${Points.balance()} puntos para gastar. Gastarlos no te baja de nivel ni en el ranking.", 13f, Palette.SUB),
            lp(),
        )
        lateinit var dialog: AlertDialog
        Powers.ALL.forEach { power ->
            val ready = if (power.key == Powers.DOUBLE) Powers.doubleReady else Powers.armed?.key == power.key
            val item = card(Palette.SURFACE, 18, 12)
            val head = row()
            head.addView(label(power.emoji, 26f).apply { includeFontPadding = false })
            val texts = column()
            texts.addView(label("${power.name} · ${power.cost} puntos", 15f, bold = true), lp())
            texts.addView(label(power.text, 12f, Palette.SUB), lp())
            head.addView(texts, lp(0, WRAP, weight = 1f).apply { marginStart = dp(10) })
            item.addView(head, lp())
            item.addView(
                button(
                    if (ready) "Listo · devolver puntos" else "Usar", if (ready) Palette.SURFACE2 else Palette.YELLOW,
                    if (ready) Palette.TEXT else Palette.INK, heightDp = 40, radiusDp = 12, sizeSp = 13f,
                ) {
                    when {
                        ready -> {
                            Powers.cancel(power)
                            toast("Te devolvimos ${power.cost} puntos")
                        }
                        power.key != Powers.DOUBLE && Powers.armed != null -> toast("Ya tienes un poder listo para tu próximo sonido")
                        Powers.buy(power) -> toast(
                            "${power.emoji} ${power.name} listo" + if (power.key == Powers.DOUBLE) " para tu próximo reto" else ": va en tu próximo sonido",
                        )
                        else -> toast("Te faltan ${power.cost - Points.balance()} puntos")
                    }
                    dialog.dismiss()
                },
                lp(topDp = 10),
            )
            body.addView(item, lp(topDp = 10))
        }
        body.addView(
            label("Los poderes no le quitan el control a nadie: quien recibe siempre puede detener el sonido, silenciar o bloquear.", 12f, Palette.SUB),
            lp(topDp = 12),
        )
        dialog = AlertDialog.Builder(this)
            .setTitle("🌟 Poderes")
            .setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton("Cerrar", null)
            .create()
        dialog.show()
    }

    private fun editAvatar() {
        var emoji = Looks.myEmoji
        var swatch = Looks.swatch(Looks.mySwatch) ?: Looks.SWATCHES[2]
        val preview = FrameLayout(this)
        val emojis = FrameLayout(this)
        val colors = FrameLayout(this)
        lateinit var refresh: () -> Unit
        refresh = {
            preview.removeAllViews()
            val face = if (emoji.isNotEmpty()) label(emoji, 34f).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
            } else avatar(Store.myName, swatch.main, sizeDp = 72)
            face.background = swatch.oval(this)
            preview.addView(face, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER_HORIZONTAL))
            emojis.removeAllViews()
            emojis.addView(optionGrid(listOf("Aa") + Looks.EMOJIS, emoji.ifEmpty { "Aa" }) {
                emoji = if (it == "Aa") "" else it
                refresh()
            })
            colors.removeAllViews()
            colors.addView(swatchRow(swatch.key, null) { picked ->
                if (picked != null) swatch = picked
                refresh()
            })
        }
        refresh()
        val body = column().apply { setPadding(dp(22), dp(4), dp(22), dp(4)) }
        body.addView(preview, lp())
        body.addView(label("Tus amigos lo ven en el chat, el ranking y cuando les suena algo tuyo. «Aa» usa tu inicial.", 12f, Palette.SUB), lp(topDp = 10))
        body.addView(caps("Emoji"), lp(topDp = 14))
        body.addView(emojis, lp(topDp = 6))
        body.addView(caps("Color"), lp(topDp = 14))
        body.addView(colors, lp(topDp = 6))
        AlertDialog.Builder(this)
            .setTitle("Tu avatar")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Guardar") { _, _ -> Looks.setMyAvatar(emoji, swatch.key) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun chooseChatTheme(key: String) {
        val level = Points.level().number
        val current = Looks.chatTheme(key)
        val body = column().apply { setPadding(dp(22), dp(4), dp(22), dp(4)) }
        lateinit var dialog: AlertDialog
        Looks.THEMES.forEach { theme ->
            val locked = theme.level > level
            val item = row().apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, if (theme.background.size > 1) theme.background else intArrayOf(theme.background[0], theme.background[0])).apply {
                    cornerRadius = dp(16).toFloat()
                    if (theme.key == current.key) setStroke(dp(3), Palette.ACCENT)
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                alpha = if (locked) 0.5f else 1f
                isClickable = true
                contentDescription = "Tema ${theme.name}" + if (locked) ", se desbloquea en el nivel ${theme.level}" else ""
                setOnClickListener {
                    if (locked) {
                        toast("🔒 El tema ${theme.name} se desbloquea en el nivel ${theme.level}")
                    } else {
                        Looks.setChatTheme(key, theme)
                        dialog.dismiss()
                    }
                }
            }
            item.addView(label(theme.name, 15f, theme.theirsText, bold = true), lp(0, WRAP, weight = 1f))
            item.addView(label("Hola", 13f, theme.theirsText).apply {
                background = rounded(theme.theirs, 12)
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) })
            item.addView(label(if (locked) "🔒 ${theme.level}" else "¡Hola!", 13f, theme.mineText).apply {
                background = rounded(theme.mine, 12)
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }, LinearLayout.LayoutParams(WRAP, WRAP))
            body.addView(item, lp(topDp = 8))
        }
        body.addView(label("El tema es solo para ti: cada uno decora su chat como quiere.", 12f, Palette.SUB), lp(topDp = 12))
        dialog = AlertDialog.Builder(this)
            .setTitle("🎨 Decorar el chat")
            .setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton("Cerrar", null)
            .create()
        dialog.show()
    }

    // ------------------------------------------------------------------ Mascota

    private fun addPetCard() {
        val species = Pets.current
        val box = card(Palette.SURFACE, 24, 16)
        (petView.parent as? ViewGroup)?.removeView(petView)
        if (species == null) {
            val head = row()
            val egg = PetView(this).apply {
                this.species = "bulla"
                stage = 0
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            head.addView(egg, LinearLayout.LayoutParams(dp(72), dp(72)))
            val texts = column()
            texts.addView(title("Adopta una mascota", 18f, weight = 600), lp())
            texts.addView(label("Crece con los puntos que ganas y tus amigos la ven junto a tu nombre.", 13f, Palette.SUB), lp())
            head.addView(texts, lp(0, WRAP, weight = 1f).apply { marginStart = dp(12) })
            box.addView(head, lp())
            box.addView(button("Adoptar", Palette.YELLOW, Palette.INK, heightDp = 44, radiusDp = 14, sizeSp = 14f) { adoptPet() }, lp(topDp = 12))
            add(box, 12)
            return
        }
        val stage = Pets.stage()
        val next = Pets.next(stage)
        val mood = Pets.mood()
        petView.apply {
            this.species = species.key
            this.stage = stage.number
            this.mood = mood
            season = Seasons.current()?.key ?: ""
            progress = if (next != null) (Pets.xp - stage.xp).toFloat() / (next.xp - stage.xp) else 1f
            contentDescription = "${Pets.name}, ${species.name.lowercase()} ${stage.name.lowercase()}. Toca para acariciarla"
            isClickable = true
            setOnClickListener { petPet() }
        }
        val head = row()
        val spot = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                colors = intArrayOf(0xFF4B3790.toInt(), Palette.SURFACE)
                gradientRadius = dp(80).toFloat()
                setGradientCenter(0.5f, 0.6f)
                cornerRadius = dp(22).toFloat()
            }
        }
        spot.addView(petView, FrameLayout.LayoutParams(dp(128), dp(128), Gravity.CENTER))
        head.addView(spot, LinearLayout.LayoutParams(dp(132), dp(132)))
        val texts = column()
        val nameRow = row()
        nameRow.addView(title(Pets.name, 20f, weight = 600).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp(0, WRAP, weight = 1f))
        nameRow.addView(iconButton(R.drawable.ic_more, "Opciones de la mascota", 0, Palette.SUB, 36) { petOptions() })
        texts.addView(nameRow, lp())
        texts.addView(label("${species.name} · ${stage.name}", 13f, Palette.YELLOW, bold = true), lp())
        texts.addView(label("${mood.face} ${mood.text}", 13f, if (mood == Pets.Mood.HAPPY) Palette.OK else Palette.YELLOW, bold = true), lp())
        if (next != null) {
            texts.addView(progressBar((Pets.xp - stage.xp).toFloat() / (next.xp - stage.xp), Palette.OK), lp(MATCH, dp(8), topDp = 8))
            texts.addView(label("Le faltan ${next.xp - Pets.xp} puntos para ser ${next.name.lowercase()}", 12f, Palette.SUB), lp(topDp = 4))
        } else {
            texts.addView(label("👑 ¡Llegó a legendaria!", 13f, Palette.YELLOW, bold = true), lp(topDp = 8))
        }
        head.addView(texts, lp(0, WRAP, weight = 1f).apply { marginStart = dp(12) })
        box.addView(head, lp())
        box.addView(speechBubble(Pets.says()), lp(topDp = 10))
        box.addView(
            button("🏡 Cuidar y jugar", Palette.ACCENT, Palette.INK, heightDp = 46, radiusDp = 14, sizeSp = 14f) { openPage(PAGE_PETS) },
            lp(topDp = 12),
        )
        add(box, 12)
    }

    /** Burbuja con lo que «dice» la mascota (ver Looks.says). */
    private fun speechBubble(text: String): View = label("💬 $text", 14f, Palette.TEXT, bold = true).apply {
        background = rounded(Palette.SURFACE2, 16)
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }

    private fun petPet() {
        petView.cheer()
        Quests.track(Quests.PET)
        toast("${Pets.name} ${Pets.reaction()} 💕")
    }

    // ------------------------------------------------------------------ Mascotas (cuidado tipo Pou)

    /** La pieza de la mascota (tipo Pou): se arma una vez y se reusa, para no cortar lo que hace. */
    private var petRoomBuilt = false
    private val petRoom by lazy {
        petRoomBuilt = true
        PetRoom(this, object : PetRoom.Host {
            override fun toast(text: String) = this@MainActivity.toast(text)
            override fun openGames() = this@MainActivity.openGames()
            override fun withMic(then: () -> Unit) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) then()
                else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_TALK)
            }
            override fun petted() = Quests.track(Quests.PET)
        })
    }

    private fun renderPets() {
        val species = Pets.current
        val top = row()
        top.addView(iconButton(R.drawable.ic_back, "Volver", Palette.SURFACE) { closePage() })
        val heading = column()
        heading.addView(title(if (species != null) Pets.name else "Mascotas", 24f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp())
        if (species != null) {
            val trait = Pets.personality()?.let { " · ${it.trait} ${it.emoji}" } ?: ""
            heading.addView(label("${species.name} · ${Pets.stage().name}$trait", 12f, Palette.YELLOW, bold = true).apply { maxLines = 1 }, lp())
        }
        top.addView(heading, lp(0, WRAP, weight = 1f).apply { marginStart = dp(12) })
        top.addView(label("★ ${Points.balance()} pts", 13f, Palette.INK, bold = true).apply {
            background = lipped(Palette.YELLOW, 999, lipDp = 3)
            setPadding(dp(12), dp(7), dp(12), dp(9))
            contentDescription = "Tienes ${Points.balance()} puntos para gastar"
        })
        if (species != null) top.addView(iconButton(R.drawable.ic_more, "Opciones de la mascota", 0, Palette.SUB, 40) { petOptions() })
        add(top)

        if (species == null) {
            add(bullaSays("¡Adopta una mascota! La cuidas, la vistes y juegan juntos."), 14)
            val box = card(Palette.SURFACE, 24, 16)
            box.addView(title("Adopta una mascota", 18f, weight = 600), lp())
            box.addView(label("Cuídala, dale de comer y crece contigo.", 13f, Palette.SUB), lp(topDp = 4))
            box.addView(button("Adoptar", Palette.YELLOW, Palette.INK, heightDp = 46, radiusDp = 14, sizeSp = 14f) { adoptPet() }, lp(topDp = 12))
            add(box, 12)
            petCollection()
            addFooter()
            return
        }

        val stage = Pets.stage()
        val next = Pets.next(stage)
        (petRoom.parent as? ViewGroup)?.removeView(petRoom)
        petRoom.update(species, stage.number, if (next != null) (Pets.xp - stage.xp).toFloat() / (next.xp - stage.xp) else 1f)
        add(petRoom, 4)

        if (next != null) {
            add(progressBar((Pets.xp - stage.xp).toFloat() / (next.xp - stage.xp), Palette.OK), 16)
            add(label("Le faltan ${next.xp - Pets.xp} puntos para ser ${next.name.lowercase()}", 12f, Palette.SUB), 4)
        } else {
            add(label("👑 ¡Llegó a legendaria!", 13f, Palette.YELLOW, bold = true), 14)
        }
        Pets.lastVibe()?.let { add(speechBubble("${Pets.name} se acuerda: ${it.reaction}"), 10) }

        add(
            button("💌 Mandarle un saludo a un amigo", Palette.SURFACE2, Palette.TEXT, heightDp = 46, radiusDp = 14, sizeSp = 14f) { petGreeting() },
            12,
        )
        petCollection()
        addFooter()
    }

    // ------------------------------------------------------------------ Minijuegos

    private var games: GamesScreen? = null

    private fun openGames() {
        if (games != null) return
        val screen = GamesScreen(
            this, Pets.current?.key ?: "bulla",
            onClose = {
                games = null
                if (overlay.visibility != View.VISIBLE && upload == null) shell.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                updateBackCallback()
                render()
            },
            toast = { toast(it) },
        )
        games = screen
        root.addView(screen, root.indexOfChild(overlay), FrameLayout.LayoutParams(MATCH, MATCH))
        shell.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        applyInsets()
        updateBackCallback()
    }

    /** La colección: todas las especies, las que tienes desbloqueadas y las que faltan. */
    private fun petCollection() {
        val level = Points.level().number
        add(sectionTitle("Colección", "${Pets.SPECIES.count { it.level <= level }} de ${Pets.SPECIES.size}"), 20)
        val grid = column()
        Pets.SPECIES.chunked(4).forEach { chunk ->
            val line = row()
            chunk.forEach { sp ->
                val locked = sp.level > level
                val cell = column().apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    background = rounded(Palette.SURFACE, 16)
                    alpha = if (locked) 0.5f else 1f
                    contentDescription = sp.name + if (locked) ", se desbloquea en el nivel ${sp.level}" else ""
                }
                cell.addView(PetView(this).apply {
                    species = sp.key
                    stage = 3
                    animated = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(56), dp(56)))
                cell.addView(label(if (locked) "🔒 Nv ${sp.level}" else sp.name, 11f, if (locked) Palette.SUB else Palette.TEXT, bold = !locked).apply {
                    gravity = Gravity.CENTER
                }, lp(topDp = 4))
                if (line.childCount > 0) line.addView(space(8))
                line.addView(cell, lp(0, WRAP, weight = 1f))
            }
            repeat(4 - chunk.size) { line.addView(space(8)); line.addView(View(this), lp(0, WRAP, weight = 1f)) }
            grid.addView(line, lp(topDp = if (grid.childCount > 0) 8 else 0))
        }
        add(grid, 10)
        add(button("🔄 Adoptar otra", Palette.SURFACE2, Palette.TEXT, heightDp = 44, radiusDp = 14, sizeSp = 13f) { adoptPet() }, 10)
    }

    /** Manda un saludo de tu mascota a un amigo: es un mensaje de chat normal, no un sonido. */
    private fun petGreeting() {
        val species = Pets.current ?: return
        val friends = Store.friends().filter { it.state == Friend.FRIEND }
        if (friends.isEmpty()) {
            toast("Agrega amigos para mandarles un saludo")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("¿A quién le mandas un saludo?")
            .setItems(friends.map { it.name }.toTypedArray()) { _, which ->
                val friend = friends[which]
                val key = Chats.friendKey(friend.code)
                val trait = Pets.personality()?.trait?.lowercase()
                val lines = listOfNotNull(
                    "${species.emoji} ${Pets.name} te manda un saludo 💕",
                    "${species.emoji} ${Pets.name} quiere jugar con tu mascota 🐾",
                    trait?.let { "${species.emoji} ${Pets.name} (es $it) te saluda" },
                    "${species.emoji} ¡${Pets.name} te extraña! 🥺",
                )
                runInBackground({ Chats.send(key, lines.random()) }) {
                    toast("💌 ${Pets.name} saludó a ${friend.name}")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun petOptions() {
        val options = listOf<Pair<String, () -> Unit>>(
            "✏️ Cambiar nombre" to {
                textDialog("Nombre de tu mascota", null, Pets.name, "Nombre", "Guardar") { name ->
                    Pets.rename(name)
                    true
                }
            },
            "🔄 Adoptar otra" to { adoptPet() },
        )
        AlertDialog.Builder(this)
            .setTitle(Pets.name)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun adoptPet() {
        val level = Points.level().number
        var chosen = Pets.SPECIES.first()
        val choices = FrameLayout(this)
        val nameInput = editText("", "Nombre (opcional)").apply { filters = arrayOf<InputFilter>(InputFilter.LengthFilter(16)) }
        lateinit var refresh: () -> Unit
        refresh = {
            choices.removeAllViews()
            val box = column()
            Pets.SPECIES.chunked(4).forEach { chunk ->
                val line = row()
                chunk.forEach { species ->
                    val locked = species.level > level
                    val selected = species.key == chosen.key
                    val cell = column().apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(4), dp(8), dp(4), dp(8))
                        background = clickable(rounded(if (selected) Palette.ACCENT_SOFT else Palette.SURFACE, 14, if (selected) Palette.ACCENT else null))
                        alpha = if (locked) 0.45f else 1f
                        isClickable = true
                        contentDescription = species.name + if (locked) ", se desbloquea en el nivel ${species.level}" else ""
                        setOnClickListener {
                            if (locked) toast("🔒 ${species.name} se desbloquea en el nivel ${species.level}") else {
                                chosen = species
                                refresh()
                            }
                        }
                    }
                    if (locked) {
                        cell.addView(label("🔒", 26f).apply { gravity = Gravity.CENTER }, lp(MATCH, dp(56)))
                    } else {
                        cell.addView(PetView(this).apply {
                            this.species = species.key
                            stage = 3
                            animated = selected
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }, lp(MATCH, dp(56)))
                    }
                    cell.addView(label(species.name, 11f, bold = true).apply { gravity = Gravity.CENTER }, lp())
                    line.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                }
                box.addView(line, lp())
            }
            choices.addView(box)
        }
        refresh()
        val warning = if (Pets.current != null) "Tu mascota actual se va y la nueva empieza como huevo." else
            "Empieza como huevo y crece con los puntos que ganes desde hoy."
        AlertDialog.Builder(this)
            .setTitle("Adopta una mascota")
            .setView(padded(choices, nameInput, label(warning, 12f, Palette.SUB)))
            .setPositiveButton("Adoptar") { _, _ ->
                Pets.adopt(chosen, nameInput.text.toString())
                toast("🥚 ¡Adoptaste a ${Pets.name}! Gana puntos para que crezca.")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------------ Mercado y Sonoteca

    private fun renderMarket() {
        Sonoteca.markSeen()
        val top = row()
        top.addView(iconButton(R.drawable.ic_back, "Volver", Palette.SURFACE) { closePage() })
        val heading = column()
        heading.addView(title("Mercado", 30f), lp())
        heading.addView(label("Comparte sonidos y gasta tus puntos", 13f, Palette.SUB), lp())
        top.addView(heading, lp(0, WRAP, weight = 1f).apply { marginStart = dp(12) })
        top.addView(label("💰 ${Points.balance()}", 15f, Palette.YELLOW, bold = true).apply {
            background = rounded(Palette.SURFACE, 999)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            contentDescription = "${Points.balance()} puntos para gastar"
        })
        add(top)

        // ---- Sonoteca
        val intro = card(Palette.SURFACE2, 24, 16)
        val introHead = row()
        val disc = FrameLayout(this).apply { background = candy(Palette.ACCENT, 16) }
        disc.addView(icon(R.drawable.ic_sonoteca, Palette.INK, 26), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        introHead.addView(disc, LinearLayout.LayoutParams(dp(48), dp(48)))
        val introTexts = column()
        introTexts.addView(title("Sonoteca", 22f), lp())
        introTexts.addView(
            label("Los sonidos que comparten tus amigos y tus grupos. Úsalos, dales ❤️ y comparte los tuyos.", 13f, Palette.SUB),
            lp(),
        )
        introHead.addView(introTexts, lp(0, WRAP, weight = 1f).apply { marginStart = dp(12) })
        intro.addView(introHead, lp())
        intro.addView(
            button("Compartir un audio tuyo", Palette.ACCENT, Palette.INK, iconRes = R.drawable.ic_share, heightDp = 46, radiusDp = 14, sizeSp = 14f) {
                shareOwnSound()
            },
            lp(topDp = 12),
        )
        add(intro, 16)

        val (chipScroll, chips) = chipRow()
        listOf("🔥 Tendencias", "🆕 Nuevos", "⭐ Tuyos").forEachIndexed { i, name ->
            chips.addView(chip(name, sonotecaFilter == i) {
                sonotecaFilter = i
                render()
            }, LinearLayout.LayoutParams(WRAP, dp(40)).apply { marginEnd = dp(8) })
        }
        add(chipScroll, 14)
        val items = when (sonotecaFilter) {
            1 -> Sonoteca.newest()
            2 -> Sonoteca.mine()
            else -> Sonoteca.trending()
        }
        if (items.isEmpty()) {
            add(
                label(
                    if (sonotecaFilter == 2) "Todavía no compartes nada. Graba o sube un audio y compártelo: si a tus amigos les gusta, lo usan con tu nombre."
                    else "Todavía nadie te compartió sonidos. Comparte uno tuyo y pídele a tus amigos que hagan lo mismo.",
                    14f, Palette.SUB,
                ),
                12,
            )
        }
        items.forEach { add(sharedCard(it), 10) }
        add(
            label(
                "Solo ves lo que comparten tus amigos y tus grupos, y lo tuyo solo les llega a ellos. Comparte solo audios tuyos o que tengas permiso de usar: " +
                    "nada de canciones ni audios con derechos de autor de otros. Lo que no te guste, ocúltalo o denúncialo.",
                12f, Palette.SUB,
            ),
            12,
        )

        // ---- Tienda
        add(sectionTitle("Tienda", "Con tus puntos, no con dinero"), 26)
        val shop = card(padDp = 0)
        fun item(emoji: String, name: String, detail: String, onClick: () -> Unit) {
            if (shop.childCount > 0) shop.addView(divider(), dividerLp())
            val line = row().apply {
                setPadding(dp(16), dp(14), dp(14), dp(14))
                background = rowRipple()
                isClickable = true
                setOnClickListener { onClick() }
            }
            line.addView(label(emoji, 24f).apply { includeFontPadding = false })
            val texts = column()
            texts.addView(label(name, 15f, bold = true), lp())
            texts.addView(label(detail, 12f, Palette.SUB), lp())
            line.addView(texts, lp(0, WRAP, weight = 1f).apply {
                marginStart = dp(12)
                marginEnd = dp(8)
            })
            line.addView(icon(R.drawable.ic_chevron, Palette.FAINT, 16))
            shop.addView(line, lp())
        }
        val armed = Powers.armed
        item("🌟", "Poderes", if (armed != null) "${armed.emoji} ${armed.name} listo para tu próximo sonido" else "Súper sonido, Eco y Doble puntos") { showPowers() }
        item(
            "🧊", "Protector de racha · ${Points.FREEZE_COST} pts",
            "Si un día no abres la app, tu racha sigue. Tienes ${Points.freezes} de ${Points.MAX_FREEZES}.",
        ) { buyFreeze() }
        add(shop, 10)
        add(label("Los puntos no se compran con dinero y gastarlos no te baja de nivel ni en el ranking.", 12f, Palette.SUB), 8)
    }

    /** Una tarjeta de la Sonoteca: escuchar, usar, ❤️ y opciones. */
    private fun sharedCard(item: SharedSound): View {
        val box = card(Palette.SURFACE, 22, 14)
        val head = row()
        head.addView(label(item.emoji, 26f).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = candy(if (item.mine) Palette.CUSTOM else Palette.avatar(item.author), 16)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        val texts = column()
        texts.addView(title(item.label, 18f, weight = 600).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp())
        val by = if (item.mine) "Tuyo" else "de ${item.authorName}"
        val via = item.sharers.firstOrNull { it.code != item.author }?.let { " · te lo pasó ${it.name}" } ?: ""
        texts.addView(label(by + via, 13f, Palette.SUB).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp())
        val stats = when {
            item.mine -> "❤️ ${item.likes} · 🎧 ${item.uses} ${if (item.uses == 1) "lo usa" else "lo usan"}"
            item.sharers.size > 1 -> "🔥 ${item.sharers.size} amigos lo compartieron"
            else -> null
        }
        if (stats != null) texts.addView(label(stats, 12f, Palette.YELLOW, bold = true), lp(topDp = 2))
        head.addView(texts, lp(0, WRAP, weight = 1f).apply { marginStart = dp(12) })
        head.addView(iconButton(R.drawable.ic_more, "Opciones de «${item.label}»", 0, Palette.SUB) { sharedOptions(item) })
        box.addView(head, lp())

        val actions = row()
        actions.addView(
            button("Escuchar", Palette.SURFACE2, Palette.TEXT, iconRes = R.drawable.ic_nav_sound, heightDp = 40, radiusDp = 12, sizeSp = 13f, iconColor = Palette.ACCENT_TEXT) {
                Player.preview(this, Player.Source.FromFile(item.file(this)), item.label, item.startMs)
                Quests.track(Quests.SONOTECA)
            }.apply { contentDescription = "Escuchar «${item.label}»" },
            lp(0, WRAP, weight = 1f),
        )
        actions.addView(space(8))
        if (item.mine) {
            actions.addView(
                button("Compartir otra vez", Palette.ACCENT, Palette.INK, heightDp = 40, radiusDp = 12, sizeSp = 13f) {
                    confirmShare(item.file(this), item.label, item.emoji, item.startMs, item)
                }.apply { contentDescription = "Compartir otra vez «${item.label}»" },
                lp(0, WRAP, weight = 1f),
            )
        } else {
            actions.addView(
                button(if (item.used) "✓ En tus audios" else "Usar", if (item.used) Palette.SURFACE2 else Palette.OK, if (item.used) Palette.SUB else Palette.INK, heightDp = 40, radiusDp = 12, sizeSp = 13f) {
                    if (item.used) toast("Ya está en Tus audios, en la pestaña Sonar.") else useShared(item)
                }.apply { contentDescription = if (item.used) "«${item.label}» ya está en tus audios" else "Usar «${item.label}»" },
                lp(0, WRAP, weight = 1f),
            )
            actions.addView(space(8))
            actions.addView(
                iconButton(R.drawable.ic_heart, if (item.liked) "Ya te gusta «${item.label}»" else "Me gusta «${item.label}»", if (item.liked) Palette.ACCENT else Palette.SURFACE2, if (item.liked) Palette.INK else Palette.TEXT, 40) {
                    likeShared(item)
                },
            )
        }
        box.addView(actions, lp(topDp = 12))
        return box
    }

    private fun useShared(item: SharedSound) {
        runInBackground({
            val (custom, fan) = Sonoteca.use(this, item.id) ?: throw IllegalStateException("Ese audio ya no está.")
            Quests.track(Quests.SONOTECA)
            fan?.let { (topic, payload) ->
                try {
                    Relay.publishTopic(topic, payload)
                } catch (_: Exception) {
                }
            }
            ui.post { toast("🎧 «${custom.label}» quedó en Tus audios, con el nombre de ${item.authorName}") }
        }) {}
    }

    private fun likeShared(item: SharedSound) {
        if (item.liked) return
        val fan = Sonoteca.like(item.id)
        Anim.confetti(root)
        if (fan != null) runInBackground({ Relay.publishTopic(fan.first, fan.second) }) {}
    }

    private fun sharedOptions(item: SharedSound) {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (!item.mine) {
            options += "📤 Pasar a mis amigos" to { confirmShare(item.file(this), item.label, item.emoji, item.startMs, item) }
            options += "🙈 Ocultar este sonido" to { Sonoteca.hide(this, item.id) }
            options += "🙈 No ver nada de ${item.authorName}" to { Sonoteca.hideAuthor(this, item.author) }
            options += "🚩 Denunciar" to { reportShared(item) }
        } else {
            options += "🗑 Quitar de mis compartidos" to { Sonoteca.hide(this, item.id) }
        }
        AlertDialog.Builder(this)
            .setTitle(item.label)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Denunciar un sonido: se oculta al instante y, si hay correo, se arma el reporte. */
    private fun reportShared(item: SharedSound) {
        val reasons = listOf("Usa música o audio con derechos de autor", "Es ofensivo o de odio", "Es sexual o violento", "Me molesta o me acosa", "Otro motivo")
        AlertDialog.Builder(this)
            .setTitle("¿Por qué denuncias «${item.label}»?")
            .setItems(reasons.toTypedArray()) { _, which ->
                Sonoteca.hide(this, item.id)
                val body = "Denuncia en la Sonoteca de Friend Game\nMotivo: ${reasons[which]}\nSonido: «${item.label}» (${item.id})\n" +
                    "Autor: ${item.authorName} (código ${Codes.pretty(item.author)})\n" +
                    item.sharers.joinToString("") { "Lo compartió: ${it.name} (código ${Codes.pretty(it.code)})\n" } +
                    "Denuncia hecha por: ${Codes.pretty(Store.myCode)}"
                val email = Legal.reportIntent("Denuncia de sonido: ${item.label}", body)
                if (email != null) {
                    toast("Ocultamos «${item.label}». Manda el correo para que lo revisemos.")
                    startSafely(email)
                } else {
                    info("Ocultamos «${item.label}»", "Ya no lo verás. Si quieres, también puedes bloquear a ${item.authorName} desde Amigos.")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Elige uno de tus audios para compartirlo en la Sonoteca. */
    private fun shareOwnSound() {
        val customs = Store.customSounds().filter { it.credit.isEmpty() }
        if (customs.isEmpty()) {
            info("Primero agrega un audio", "En Sonar → Tus audios, toca Grabar o Subir. Después vuelve aquí para compartirlo.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("¿Qué audio compartes?")
            .setItems(customs.map { (Looks.button("c:${it.id}").emoji ?: "🎤") + " " + it.label }.toTypedArray()) { _, which ->
                val custom = customs[which]
                confirmShare(custom.file(this), custom.label, Looks.button("c:${custom.id}").emoji ?: "🎤", custom.startMs, null)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Antes de compartir: a quién (todos tus amigos, cada grupo) y la promesa de que tienes
     * derecho a compartirlo. Sin marcar esa casilla no se publica.
     */
    private fun confirmShare(file: java.io.File, label: String, emoji: String, startMs: Int, original: SharedSound?) {
        if (!file.exists()) {
            toast("No encuentro ese audio.")
            return
        }
        val friends = Store.friends().filter { it.state == Friend.FRIEND }
        val groups = Store.groups()
        if (friends.isEmpty() && groups.isEmpty()) {
            info("Todavía no tienes con quién compartir", "Agrega amigos o únete a un grupo, y vuelve a intentarlo.")
            return
        }
        val body = column().apply { setPadding(dp(22), dp(4), dp(22), dp(4)) }
        fun check(text: String, checked: Boolean): android.widget.CheckBox = android.widget.CheckBox(this).apply {
            this.text = text
            isChecked = checked
            setTextColor(Palette.TEXT)
            textSize = 15f
            buttonTintList = ColorStateList.valueOf(Palette.ACCENT)
        }
        body.addView(label("¿Con quién lo compartes?", 13f, Palette.SUB), lp())
        val allFriends = if (friends.isNotEmpty()) check("Todos mis amigos (${friends.size})", true).also { body.addView(it, lp(topDp = 4)) } else null
        val groupChecks = groups.map { group ->
            group to check((if (group.community) "Comunidad " else "Grupo ") + group.name, !group.community).also { body.addView(it, lp()) }
        }
        val someoneElse = original != null && !original.mine
        body.addView(
            label(
                if (original != null && someoneElse) "Lo creó ${original.authorName}: se comparte con su nombre."
                else "Comparte solo audios tuyos o que tengas permiso de usar. Nada de canciones ni audios con derechos de autor de otros, ni nada ofensivo.",
                12f, Palette.SUB,
            ),
            lp(topDp = 12),
        )
        val rights = check(
            if (someoneElse) "Confirmo que no es ofensivo ni infringe derechos de autor" else "Confirmo que tengo derecho a compartirlo",
            false,
        )
        body.addView(rights, lp(topDp = 6))
        val dialog = AlertDialog.Builder(this)
            .setTitle("Compartir «$label»")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Compartir", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!rights.isChecked) {
                    toast("Marca la casilla para confirmar que puedes compartirlo.")
                    return@setOnClickListener
                }
                val destinations = (if (allFriends?.isChecked == true) friends.map { Sonoteca.Destination(Relay.topicFor(it.code), null) } else emptyList()) +
                    groupChecks.filter { it.second.isChecked }.map { Sonoteca.Destination(Relay.groupTopic(it.first.id), it.first.id) }
                if (destinations.isEmpty()) {
                    toast("Elige con quién compartirlo.")
                    return@setOnClickListener
                }
                dialog.dismiss()
                toast("📤 Compartiendo…")
                var sent = 0
                runInBackground({ sent = Sonoteca.publish(this, file, label, emoji, startMs, destinations, original) }) {
                    toast("📤 «$label» compartido" + if (sent > 1) " en $sent lugares" else "")
                }
            }
        }
        dialog.show()
    }

    private fun buyFreeze() {
        when {
            Points.freezes >= Points.MAX_FREEZES -> toast("Ya tienes ${Points.MAX_FREEZES} protectores: es el máximo.")
            Points.buyFreeze() -> toast("🧊 Listo: si un día no abres la app, tu racha sigue.")
            else -> toast("Te faltan ${Points.FREEZE_COST - Points.balance()} puntos.")
        }
    }

    // ------------------------------------------------------------------ Reglas, privacidad y denuncias

    private fun showLegal(titleText: String, asset: String, onClose: (() -> Unit)? = null) {
        val text = label(Legal.text(this, asset), 14f).apply {
            setPadding(dp(22), dp(8), dp(22), dp(8))
            movementMethod = LinkMovementMethod.getInstance()
            setLinkTextColor(Palette.ACCENT_TEXT)
        }
        AlertDialog.Builder(this)
            .setTitle(titleText)
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("Entendido", null)
            .setNeutralButton("Abrir en la web") { _, _ -> openUrl(if (asset == Legal.TERMS_ASSET) Legal.TERMS_URL else Legal.PRIVACY_URL) }
            .setOnDismissListener { onClose?.invoke() }
            .show()
    }

    /**
     * Los sonidos de amigos suenan al volumen máximo y por el altavoz principal. Se pide aceptarlo al
     * instalar y al actualizar (hasta que responda). Quien no acepta queda con la recepción en pausa;
     * [turnOn]: lo pidió al activar «Recibir sonidos».
     */
    private fun askLoud(turnOn: Boolean = false, then: () -> Unit = {}) {
        if (loudDialog?.isShowing == true) return
        loudDialog = AlertDialog.Builder(this)
            .setTitle("🔊 Los sonidos suenan fuerte")
            .setMessage(
                "Para que siempre se escuchen, los sonidos de tus amigos suenan al volumen máximo y por el " +
                    "altavoz principal del celular, aunque esté en silencio o en vibración.\n\n" +
                    "• Duran como mucho 10 segundos y al terminar el volumen vuelve a como estaba.\n" +
                    "• Siempre puedes detenerlos, silenciar o bloquear a alguien, o pausar la recepción.\n" +
                    "• Si no lo aceptas, no te llegan sonidos. Puedes activarlos después en Ajustes.\n\n" +
                    "Está en los Términos de uso."
            )
            .setCancelable(false)
            .setPositiveButton("Acepto") { _, _ ->
                Store.loudAccepted = true
                Store.loudAnswered = true
                if (turnOn) {
                    Store.receiving = true
                    ListenService.start(this)
                }
                toast("🔊 Listo: los sonidos suenan al máximo por el altavoz")
                then()
            }
            .setNegativeButton("No recibir sonidos") { _, _ ->
                if (Locks.anyActive() == null) {
                    Store.loudAnswered = true
                    Store.receiving = false
                    Player.stop()
                    ListenService.stop(this)
                    toast("Recepción en pausa: actívala en Ajustes cuando quieras")
                } else {
                    // Con un candado no se puede pausar: se vuelve a preguntar cuando termine.
                    lockBlocks()
                }
                then()
            }
            .setNeutralButton("Leer términos") { _, _ ->
                showLegal("Términos de uso", Legal.TERMS_ASSET) { askLoud(turnOn, then) }
            }
            .show()
    }

    private fun showContact() {
        val message = if (Legal.hasContact()) {
            "Escríbenos a ${Legal.contactEmail} para dudas, problemas o para denunciar a alguien."
        } else {
            "Puedes dejar un reporte o una idea en la página del proyecto en GitHub (sin datos personales). " +
                "Para denunciar a alguien usa «Denunciar» en sus opciones: se bloquea al instante."
        }
        AlertDialog.Builder(this)
            .setTitle("Ayuda y contacto")
            .setMessage(message)
            .setPositiveButton(if (Legal.hasContact()) "Escribir" else "Abrir GitHub") { _, _ ->
                val email = Legal.reportIntent("Friend Game: ayuda", "")
                if (email != null) startSafely(email) else openUrl(Legal.ISSUES_URL)
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun legalNote(): TextView {
        val text = SpannableStringBuilder("Al empezar aceptas los ")
        fun link(linkText: String, action: () -> Unit) {
            val start = text.length
            text.append(linkText)
            text.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    action()
                }
            }, start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        link("Términos de uso") { showLegal("Términos de uso", Legal.TERMS_ASSET) }
        text.append(" y la ")
        link("Política de privacidad") { showLegal("Política de privacidad", Legal.PRIVACY_ASSET) }
        text.append(". Es para mayores de 13 años.")
        return label(text, 12f, Palette.SUB).apply {
            gravity = Gravity.CENTER
            movementMethod = LinkMovementMethod.getInstance()
            setLinkTextColor(Palette.ACCENT_TEXT)
        }
    }

    /** Denunciar bloquea al instante y, si hay correo de contacto, arma el correo con los datos. */
    private fun report(code: String, name: String, text: String = "") {
        val reasons = listOf("Me molesta o me acosa", "Manda contenido ofensivo", "Es spam", "Otro motivo")
        AlertDialog.Builder(this)
            .setTitle("¿Por qué denuncias a $name?")
            .setItems(reasons.toTypedArray()) { _, which ->
                val friend = Store.friend(code)
                if (friend != null) block(friend) else Store.putFriend(Friend(code, name, Friend.BLOCKED, muted = true))
                val body = "Denuncia en Friend Game\nMotivo: ${reasons[which]}\nPersona: $name (código ${Codes.pretty(code)})\n" +
                    (if (text.isNotEmpty()) "Mensaje: «$text»\n" else "") +
                    "Denuncia hecha por: ${Codes.pretty(Store.myCode)}"
                val email = Legal.reportIntent("Denuncia: $name", body)
                if (email != null) {
                    toast("Bloqueaste a $name. Manda el correo para que revisemos la denuncia.")
                    startSafely(email)
                } else {
                    info("Bloqueaste a $name", "Ya no te pueden llegar sus sonidos ni sus mensajes. Gracias por avisar.")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun reportGroup(group: Group) {
        val reasons = listOf("Molestan o acosan", "Contenido ofensivo", "Es spam", "Otro motivo")
        AlertDialog.Builder(this)
            .setTitle("¿Por qué denuncias «${group.name}»?")
            .setItems(reasons.toTypedArray()) { _, which ->
                runInBackground({ Groups.leave(group) }) {}
                val body = "Denuncia en Friend Game\nMotivo: ${reasons[which]}\nGrupo: ${group.name} (${group.id})\n" +
                    "Admin: ${Codes.pretty(group.adminCode)}\nDenuncia hecha por: ${Codes.pretty(Store.myCode)}"
                val email = Legal.reportIntent("Denuncia de grupo: ${group.name}", body)
                if (email != null) {
                    toast("Saliste de «${group.name}». Manda el correo para que revisemos la denuncia.")
                    startSafely(email)
                } else {
                    info("Saliste de «${group.name}»", "Ya no te llegarán sus sonidos ni sus mensajes. Gracias por avisar.")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast("No encontré una app para abrir esto.")
        }
    }

    private fun openUrl(url: String) {
        startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    }

    private fun openStorePage() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
        } catch (_: ActivityNotFoundException) {
            openUrl("https://play.google.com/store/apps/details?id=$packageName")
        }
    }

    private fun askExactAlarms() {
        if (Retos.puntualAllowed(this)) {
            info("Alarmas puntuales", "Listo: los retos suenan a la hora exacta.")
            return
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                toast("Búscalo en Ajustes → Apps → Friend Game → Alarmas y recordatorios.")
            }
        }
    }

    private fun createCommunity() {
        var emoji = COMMUNITY_EMOJIS.first()
        val input = editText("", "Nombre de la comunidad").apply { filters = arrayOf<InputFilter>(InputFilter.LengthFilter(24)) }
        val (emojiScroll, emojis) = chipRow()
        lateinit var refresh: () -> Unit
        refresh = {
            emojis.removeAllViews()
            COMMUNITY_EMOJIS.forEach { option ->
                emojis.addView(chip(option, option == emoji) {
                    emoji = option
                    refresh()
                }, LinearLayout.LayoutParams(WRAP, dp(44)).apply { marginEnd = dp(6) })
            }
        }
        refresh()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Crear comunidad")
            .setMessage("Hasta ${Group.MAX_MEMBERS} personas con chat, ranking y retos. Los sonidos solo suenan en los retos.")
            .setView(padded(input, label("Elige un emoji", 13f, Palette.SUB), emojiScroll))
            .setPositiveButton("Crear", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    input.error = "Escribe un nombre"
                    return@setOnClickListener
                }
                dialog.dismiss()
                val group = Groups.create(name, community = true, emoji = emoji)
                tab = Tab.GROUPS
                pages.clear()
                render()
                showInvite(group)
            }
        }
        dialog.show()
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
        val inChat = chatKey != null
        nav.visibility = if (inChat) View.GONE else View.VISIBLE
        navDivider.visibility = nav.visibility
        if (inChat) return
        val requests = Store.friends().count { it.state == Friend.INCOMING }
        val unread = Store.unreadCounts().filterKeys { Chats.title(it) != null }.values.sum()
        Tab.entries.forEach { item ->
            val selected = item == tab
            val cell = column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(2), 0, dp(2))
                background = RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), null, null)
                isClickable = true
                isSelected = selected
                contentDescription = "Pestaña ${item.title}" + when {
                    item == Tab.FRIENDS && requests > 0 -> ", $requests ${if (requests == 1) "solicitud" else "solicitudes"}"
                    item == Tab.CHATS && unread > 0 -> ", $unread ${if (unread == 1) "mensaje nuevo" else "mensajes nuevos"}"
                    else -> ""
                }
                setOnClickListener { openTab(item) }
            }
            val pill = FrameLayout(this).apply { if (selected) background = rounded(Palette.ACCENT_SOFT, 16) }
            pill.addView(
                icon(item.iconRes, if (selected) Palette.ACCENT_TEXT else Palette.SUB, 22),
                FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER),
            )
            val count = when (item) {
                Tab.FRIENDS -> requests
                Tab.CHATS -> unread
                else -> 0
            }
            if (count > 0) {
                val badge = label(if (count > 9) "9+" else count.toString(), 10f, Palette.INK, bold = true).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    background = oval(Palette.YELLOW)
                }
                pill.addView(badge, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply { marginEnd = dp(6) })
            }
            cell.addView(pill, LinearLayout.LayoutParams(dp(52), dp(32)))
            if (selected && renderedTab != null && renderedTab != tab) Anim.pop(pill, 1.2f)
            cell.addView(label(item.title, 12f, if (selected) Palette.ACCENT_TEXT else Palette.SUB).apply {
                typeface = Fonts.body(context, if (selected) 800 else 700)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
            nav.addView(cell, lp(0, WRAP, weight = 1f))
        }
        renderedTab = tab
    }

    // ------------------------------------------------------------------ Te llegó un sonido

    /** Mientras suena algo que te mandaron, ocupa toda la pantalla con el botón para detenerlo. */
    private fun renderOverlay() {
        overlay.removeAllViews()
        val now = Player.now
        if (now == null || now.isPreview) {
            if (overlay.visibility == View.VISIBLE) {
                overlayPet.removeCallbacks(overlayReaction)
                overlayPet.audioLevel = null
                overlayPet.calm()
            }
            overlay.visibility = View.GONE
            if (upload == null) shell.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            return
        }
        val appearing = overlay.visibility != View.VISIBLE
        if (appearing) {
            // Si estabas escribiendo en el chat, el teclado tapaba el botón Detener.
            composerInput.clearFocus()
            hideKeyboard(composerInput)
        }
        overlay.visibility = View.VISIBLE
        shell.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        val sound = Sounds.byKey(now.soundKey)
        val friend = Store.friend(now.fromCode)
        val sender = friend?.name ?: now.fromName.substringBefore(" · ")
        val page = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(16), dp(24), dp(24))
        }
        val reto = now.retoId?.let { Store.reto(it) }
        val locked = Locks.blocksStop(now)
        val superSound = now.power == Powers.SUPER
        val vibeKey = vibeOf(now)
        val vibe = Vibes.known(vibeKey)
        val tint = when {
            superSound -> Palette.YELLOW
            vibe != null -> vibe.color
            else -> Palette.ACCENT
        }
        // Súper sonido: pantalla dorada. Si no, el fondo animado de su categoría, que se abre al llegar.
        if (superSound) {
            overlay.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF4A3300.toInt(), 0xFF6B1450.toInt(), 0xFF1A0B38.toInt()))
        } else {
            overlay.background = ColorDrawable(Palette.OVERLAY)
            (overlayScene.parent as? ViewGroup)?.removeView(overlayScene)
            if (appearing) {
                overlayScene.clear()
                if (vibe != null) overlayScene.react(vibe.key)
            }
            overlay.addView(overlayScene, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        page.addView(View(this), lp(MATCH, 0, weight = 1f))
        val trial = now.trialFor.isNotEmpty()
        val heading = when {
            trial -> "🎧 Así le llega a ${now.trialFor}"
            reto != null -> "Reto · ${Retos.kindOf(reto.kind).title}"
            superSound -> "🌟 ¡Súper sonido! 🌟"
            now.power == Powers.ECO -> "🔁 Te llegó un sonido con eco"
            else -> "Te llegó un sonido"
        }
        page.addView(caps(heading).apply { textSize = 13f }, LinearLayout.LayoutParams(WRAP, WRAP))

        // Bulla se sorprende y después reacciona a la categoría, moviendo la boca con lo que suena.
        val stage = FrameLayout(this)
        stage.addView(PulseRings(this, tint), FrameLayout.LayoutParams(dp(250), dp(250), Gravity.CENTER))
        (overlayPet.parent as? ViewGroup)?.removeView(overlayPet)
        overlayPet.apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            outfit = Wardrobe.outfit()
            glow = tint
            audioLevel = { Player.level() }
        }
        if (appearing) {
            overlayPet.surprise()
            overlayPet.removeCallbacks(overlayReaction)
            overlayReaction = Runnable {
                val left = (now.durationMs - (SystemClock.elapsedRealtime() - now.startedAt)) / 1000f
                if (Player.now === now && left > 0.3f) overlayPet.react(vibe?.key ?: Vibes.default.key, left)
            }
            overlayPet.postDelayed(overlayReaction, 900)
        }
        stage.addView(overlayPet, FrameLayout.LayoutParams(dp(220), dp(220), Gravity.CENTER))
        page.addView(stage, LinearLayout.LayoutParams(dp(250), dp(250)))

        val what = row().apply { gravity = Gravity.CENTER }
        val tile = label(sound?.emoji ?: "🎤", 38f).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = if (superSound) Looks.swatch("oro")!!.drawable(context, 22) else lipped(tint, 22)
            setPadding(0, 0, 0, dp(4))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        what.addView(tile, LinearLayout.LayoutParams(dp(72), dp(76)))
        if (appearing) Anim.pop(tile, 1.3f)
        val names = column()
        names.addView(title(now.label, 32f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, lp())
        // La mascota reconoce la emoción del sonido.
        if (vibe != null && now.vibe.isNotEmpty() && reto == null && now.trialFor.isEmpty()) {
            names.addView(label("${vibe.emoji} Sonido ${vibe.name.lowercase()}", 13f, vibe.onColor, bold = true).apply {
                background = rounded(vibe.color, 999)
                setPadding(dp(12), dp(5), dp(12), dp(5))
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
        }
        what.addView(names, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) })
        page.addView(what, lp(topDp = 4))
        val from = row().apply {
            background = rounded(Palette.SURFACE, 999)
            setPadding(dp(8), dp(8), dp(16), dp(8))
        }
        from.addView(personAvatar(now.fromCode, sender, 32))
        from.addView(
            label(if (reto != null) now.fromName else "de ${now.fromName}", 16f, bold = true),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) },
        )
        page.addView(from, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(12) })

        page.addView(View(this), lp(MATCH, dp(32), weight = 1f))
        if (locked) {
            // Candado: lo acordaron los dos, así que el botón no corta el sonido.
            val lock = Locks.activeWith(now.fromCode)
            page.addView(
                button(
                    "Candado activo", Palette.SURFACE2, Palette.YELLOW, iconRes = R.drawable.ic_lock,
                    heightDp = 64, radiusDp = 22, display = true, sizeSp = 22f,
                ) { toast("🔒 Acordaron un candado: este sonido no se puede detener") },
                lp(),
            )
            if (lock != null) {
                page.addView(
                    liveText(
                        13f, Palette.YELLOW,
                        "Acordaste un candado con $sender hasta las ${Locks.untilText(lock)}. Se corta solo a los 10 segundos.",
                        center = true,
                    ) { "Acordaste un candado con $sender: quedan ${Locks.remainingText(lock.until)}. Se corta solo a los 10 segundos." },
                    lp(topDp = 12),
                )
            }
        } else {
            page.addView(
                button("Detener", Palette.DANGER, Palette.TEXT, iconRes = R.drawable.ic_stop, heightDp = 64, radiusDp = 22, display = true, sizeSp = 22f) {
                    Player.stopByUser(this)
                },
                lp(),
            )
            if (reto == null && !trial) {
                val muteText = if (now.groupId != null) "Silenciar el grupo" else "Silenciar a $sender"
                page.addView(button(muteText, Palette.SURFACE, Palette.TEXT, iconRes = R.drawable.ic_mute, heightDp = 52, radiusDp = 18) {
                    muteSender(now)
                }, lp(topDp = 12))
            }
            val note = when {
                trial -> "Es una prueba: solo suena en tu celular. Así lo verá ${now.trialFor}, y siempre lo podrá detener."
                reto?.kind == Retos.FASTEST -> "¡Rápido! Gana quien lo detenga primero."
                reto != null -> "¡Sonó para todos a la vez! Se corta solo a los 10 segundos."
                else -> "Se corta solo a los 10 segundos. Tú decides, siempre."
            }
            page.addView(label(note, 13f, Palette.SUB).apply { gravity = Gravity.CENTER }, lp(topDp = 12))
        }
        page.addView(TimeBar(this, Palette.LINE, tint, now.startedAt, now.durationMs), lp(MATCH, dp(8), topDp = 24))

        val pageScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(page, ViewGroup.LayoutParams(MATCH, WRAP))
        }
        overlay.addView(pageScroll, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** Después de sorprenderse, Bulla reacciona a la categoría del sonido que llegó. */
    private var overlayReaction = Runnable {}

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
        val view = buildWelcome() as ScrollView
        welcome = view
        root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        applyInsets()
        (view.getChildAt(0) as? ViewGroup)?.let { page -> Anim.enter((0 until page.childCount).map { page.getChildAt(it) }) }
    }

    private fun finishWelcome(name: String) {
        if (welcome == null) return
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
        floating("🔔", 0xFF54A0FF.toInt(), 56, Gravity.BOTTOM or Gravity.START, 34, 2, 8f)
        floating("🎉", 0xFFA29BFE.toInt(), 52, Gravity.BOTTOM or Gravity.END, 28, 10, -8f)
        // Bulla saluda en el centro, con su globo.
        art.addView(View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                colors = intArrayOf(0x663DBBF5, 0x003DBBF5)
                gradientRadius = dp(90).toFloat()
            }
        }, FrameLayout.LayoutParams(dp(190), dp(190), Gravity.CENTER))
        val hello = PetView(this).apply {
            species = "bulla"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        art.addView(hello, FrameLayout.LayoutParams(dp(176), dp(176), Gravity.CENTER))
        hello.postDelayed({ hello.wave() }, 600)
        art.addView(label("¡Hola! Soy Bulla", 14f, Palette.INK, bold = true).apply {
            background = lipped(Palette.TEXT, 16, Palette.SUB)
            setPadding(dp(12), dp(8), dp(12), dp(11))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(18), dp(4), 0) })
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
        input.setOnEditorActionListener { _, actionId, event ->
            // El ✓ del teclado, o Enter en un teclado físico.
            val enter = actionId == EditorInfo.IME_NULL && event?.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
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
        page.addView(legalNote(), lp(topDp = 6))

        return ScrollView(this).apply {
            isFillViewport = true
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Palette.BG_HIGH, Palette.BG))
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
        if (BuildConfig.STORE_BUILD) {
            val text = "¡Juguemos Friend Game! 🔊 Bájala de Google Play: https://play.google.com/store/apps/details?id=$packageName"
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Pasar Friend Game"))
            return
        }
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
        val install = if (BuildConfig.STORE_BUILD) "Bájala de Google Play: https://play.google.com/store/apps/details?id=$packageName"
        else "Instala la app (Android, ábrelo en Chrome): $INSTALL_PAGE"
        val text = "¡Juguemos Friend Game! 🔊 Agrégame con mi código: ${Codes.pretty(Store.myCode)}\n" + install
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(intent, "Compartir mi código"))
    }

    /** Cuánto falta para poder mandarle otro sonido a [targetKey] (0: ya se puede). */
    private fun sendWait(targetKey: String, now: Long): Long {
        if (sending) return SEND_COOLDOWN_MS
        var wait = cooldownUntil - now
        val times = recentSends[targetKey]
        if (times != null) {
            while (times.isNotEmpty() && now - times.first() > SEND_WINDOW_MS) times.removeFirst()
            if (times.size >= SEND_MAX_PER_WINDOW) wait = maxOf(wait, times.first() + SEND_WINDOW_MS - now)
        }
        return wait
    }

    private fun coolingDown(): Boolean = sending || SystemClock.elapsedRealtime() < cooldownUntil

    /**
     * Recarga: un sonido a la vez y una pausa corta entre uno y otro. Si se tocan muchos seguidos,
     * no se arma una fila de envíos ni se mandan sonidos que el otro celular igual descartaría.
     */
    private fun startCooldown(targetKey: String, now: Long) {
        val times = recentSends.getOrPut(targetKey) { ArrayDeque() }
        times.addLast(now)
        var until = now + SEND_COOLDOWN_MS
        if (times.size >= SEND_MAX_PER_WINDOW) until = maxOf(until, times.first() + SEND_WINDOW_MS)
        cooldownFrom = now
        cooldownUntil = until
        ui.removeCallbacks(cooldownOver)
        ui.postDelayed(cooldownOver, until - now + 50)
    }

    private fun sendSound(target: Target, kind: String, label: String, custom: CustomSound?, tile: View? = null) {
        val now = SystemClock.elapsedRealtime()
        val wait = sendWait(target.key, now)
        if (wait > 0) {
            tile?.let { Anim.shake(it) }
            if (now - lastCooldownNotice > 1_500) {
                lastCooldownNotice = now
                toast(
                    when {
                        sending -> "⏳ Todavía está saliendo el anterior…"
                        wait > SEND_COOLDOWN_MS -> "⏳ Cada amigo recibe hasta $SEND_MAX_PER_WINDOW sonidos cada 20 segundos. Espera ${(wait + 999) / 1000} s."
                        else -> "⏳ Recargando… espera un segundo"
                    },
                )
            }
            return
        }
        val playbackId = Codes.random(12)
        val vibeKey = Looks.vibe(if (custom != null) "c:${custom.id}" else kind)
        val payload = Relay.payload("snd").put("id", playbackId).put("s", kind).put("label", label).put("v", vibeKey)
        Pets.onVibeSent(vibeKey)
        target.groupId?.let { payload.put("g", it) }
        if (custom != null && custom.startMs > 0) payload.put("st", custom.startMs)
        val file = custom?.file(this)
        if (file != null && !file.exists()) {
            toast("No encuentro ese audio, súbelo otra vez.")
            return
        }
        // Un poder listo (Súper sonido, Eco) se usa en este sonido; si no sale, vuelve a quedar listo.
        val power = Powers.take()
        if (power != null) payload.put("pw", power.key)
        val toName = if (target.groupId != null) "el grupo ${target.name}" else target.name
        sending = true
        startCooldown(target.key, now)
        tile?.let { Anim.flyUp(root, it, Looks.button(if (custom != null) "c:${custom.id}" else kind).emoji ?: Sounds.byKey(kind)?.emoji ?: "🎤") }
        // El fondo se abre con la categoría del sonido desde el botón, y Bulla asoma reaccionando.
        if (Looks.reactiveBackground && Player.now == null) {
            tile?.let { rememberOrigin(it) }
            if (originX >= 0f) scene.react(vibeKey, originX, originY) else scene.react(vibeKey)
            originX = -1f
            ui.removeCallbacks(sendReactionOver)
            ui.postDelayed(sendReactionOver, 1_800)
        }
        if (Player.now == null) peek.show(vibeKey, 1.8f, follow = false)
        render()
        runInBackground({
            if (file != null) Relay.publishFileTopic(target.topic, payload, file) else Relay.publishTopic(target.topic, payload)
        }, onError = {
            sending = false
            power?.let { Powers.restore(it) }
            render()
        }) {
            sending = false
            if (target.groupId == null) Streaks.onSent(target.key.removePrefix("f:")) else Quests.track(Quests.GROUP)
            Replies.onSent(target.key)
            if (custom != null) Quests.track(Quests.CUSTOM)
            Points.onSoundSent()
            if (power != null) toast("${power.emoji} ${power.name} enviado")
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

    /** Termina la reacción del fondo al mandar (si no empezó a sonar otra cosa). */
    private val sendReactionOver = Runnable { if (Player.now == null && ::scene.isInitialized) scene.release() }

    private fun stopMySound(sent: Sent) {
        lastSent = null
        render()
        val payload = Relay.payload("stop").put("id", sent.playbackId)
        runInBackground({ Relay.publishTopic(sent.topic, payload) }) { toast("Listo, se detuvo tu sonido") }
    }

    private fun customOptions(custom: CustomSound) {
        val target = currentTarget()
        val options = mutableListOf<Pair<String, () -> Unit>>(
            "▶️ Escucharlo" to { previewSound(Player.Source.FromFile(custom.file(this)), custom.label, custom.startMs, Looks.vibe("c:${custom.id}"), null) },
            "🎨 Personalizar botón" to { customizeButton("c:${custom.id}", "🎤", Palette.CUSTOM, custom.label, editName = false) },
            "✂️ Elegir la parte que suena" to { chooseStart(custom) },
            "✏️ Cambiar nombre" to { renameCustom(custom) },
            "🏷️ Categoría: ${Vibes.byKey(Looks.vibe("c:${custom.id}")).label}" to { chooseVibe("c:${custom.id}") },
            "🗑 Borrar" to { deleteCustom(custom) },
        )
        if (target != null) {
            options.add(1, "📱 Escuchar como le llega a ${target.name}" to {
                playTrial(target, Player.Source.FromFile(custom.file(this)), custom.label, "custom", custom.startMs)
            })
        }
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
            if (invite.community) "Es una comunidad: chat, ranking y retos. Sus sonidos solo suenan en los retos. Puedes silenciarla o salirte cuando quieras."
            else "Las personas del grupo podrán mandarte sonidos. Puedes silenciarlo o salirte cuando quieras.",
        ) {
            val group = Groups.join(invite)
            selectedTarget = "g:${group.id}"
            tab = Tab.GROUPS
            pages.clear()
            render()
            runInBackground({ Groups.announceJoin(group) }) { toast("Te uniste a «${group.name}»") }
        }
    }

    private fun groupOptions(group: Group) {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        options += "💬 Chat" to { openChat(Chats.groupKey(group.id)) }
        options += "🏆 Ranking" to { openPage(PAGE_RANKING + Chats.groupKey(group.id)) }
        options += "⚡ Crear un reto" to { showCreateReto(Chats.groupKey(group.id)) }
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
            options += "🚩 Denunciar el grupo" to { reportGroup(group) }
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
        Points.onNewFriend(friend.code)
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
        val lock = Locks.activeWith(friend.code)
        when (friend.state) {
            Friend.FRIEND -> {
                options += "💬 Chat" to { openChat(Chats.friendKey(friend.code)) }
                options += "⚡ Crear un reto" to { showCreateReto(Chats.friendKey(friend.code)) }
                if (lock != null) {
                    // Durante el candado no se puede silenciar, eliminar ni bloquear: es el acuerdo.
                    options += "🔒 Candado activo hasta las ${Locks.untilText(lock)}" to {
                        info("Candado con ${friend.name}", lockRules(lock))
                    }
                } else {
                    options += "🔒 Proponer un candado" to { showProposeLock(friend) }
                    options += (if (friend.muted) "🔊 Volver a recibir sus sonidos" else "🔇 Silenciar sus sonidos") to {
                        if (!lockedWith(friend)) {
                            Store.putFriend(friend.copy(muted = !friend.muted))
                            if (!friend.muted) Player.stopIfFrom(friend.code)
                        }
                    }
                }
                options += "✏️ Cambiar nombre" to { renameFriend(friend) }
                if (lock == null) {
                    options += "👋 Eliminar amigo" to {
                        confirm("¿Eliminar a ${friend.name}?", "Ya no se podrán mandar sonidos hasta que se vuelvan a agregar.") {
                            if (!lockedWith(friend)) removeFriend(friend)
                        }
                    }
                    options += "🚫 Bloquear" to { if (!lockedWith(friend)) block(friend) }
                    options += "🚩 Denunciar" to { report(friend.code, friend.name) }
                }
            }
            Friend.OUTGOING -> {
                options += "📨 Reenviar solicitud" to { sendRequest(friend.code) }
                options += "✖️ Cancelar solicitud" to { removeFriend(friend) }
            }
            Friend.INCOMING -> {
                options += "✅ Aceptar" to { acceptRequest(friend) }
                options += "✖️ Rechazar" to { Store.removeFriend(friend.code) }
                options += "🚫 Bloquear" to { block(friend) }
                options += "🚩 Denunciar" to { report(friend.code, friend.name) }
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
            ui.post { showUpload(imported, "Subir audio") }
        }) {}
    }

    /** La pantalla «Subir audio» abierta (ver UploadSheet), o null. */
    private var upload: UploadSheet? = null

    /**
     * Después de subir o grabar un audio: Bulla lo escucha y adivina la categoría, se elige el
     * nombre y la parte que suena, y se guarda.
     */
    private fun showUpload(imported: CustomAudio.Imported, heading: String) {
        if (isFinishing || isDestroyed) {
            CustomAudio.fileFor(this, imported.sound).delete()
            return
        }
        upload?.close()
        val sheet = UploadSheet(
            this, imported, heading,
            onPreview = { source, name, startMs, vibe -> previewSound(source, name, startMs, vibe, null) },
            onSave = { sound, vibe ->
                Store.putCustomSound(sound)
                Looks.setVibe("c:${sound.id}", vibe)
                Points.onCustomAdded()
                Pets.onVibeSent(vibe)
                toast("🎤 «${sound.label}» está en Tus audios: ya puedes mandarlo")
            },
            onClose = {
                upload = null
                if (overlay.visibility != View.VISIBLE) shell.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                hideKeyboard(root)
                updateBackCallback()
            },
        )
        upload = sheet
        root.addView(sheet, root.indexOfChild(overlay), FrameLayout.LayoutParams(MATCH, MATCH))
        shell.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        applyInsets()
        sheet.enter()
        updateBackCallback()
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
        if (requestCode == REQ_TALK) {
            toast(
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) "🎤 Listo: mantén presionado el botón y háblale"
                else "Sin permiso de micrófono no te puede escuchar. Prueba con las frases de abajo.",
            )
        }
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
            recorder.setMaxDuration(CustomAudio.RECORD_MS.toInt())
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
        // El contador se pinta solo (LiveText): cambiar el texto cada segundo no deja leer la pantalla a TalkBack.
        val status = LiveText(this, 22f, Palette.TEXT, true, "Grabando, hasta 8 segundos") {
            "Grabando… ${(SystemClock.elapsedRealtime() - startedAt) / 1000} s de 8"
        }
        var finished = false
        lateinit var dialog: AlertDialog
        fun finish(keep: Boolean) {
            if (finished) return
            finished = true
            cancelRecording = null
            val ok = try {
                recorder.stop()
                true
            } catch (_: RuntimeException) {
                false // se detuvo antes de grabar algo
            }
            recorder.release()
            if (dialog.isShowing) dialog.dismiss()
            if (keep && ok && file.length() > 0) {
                showUpload(CustomAudio.Imported(sound, "Mi grabación", CustomAudio.durationOf(file) ?: 0L), "Tu grabación")
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
        // Cambiar de servidor cortaría al amigo del candado.
        if (lockBlocks()) return
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
        if (lockBlocks()) return
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
            Points.clearScores()
            Looks.clearProfiles()
            pages.clear()
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

    /** Corre [work] en segundo plano; si sale bien, [done] en la pantalla; si falla, avisa y llama a [onError]. */
    private fun runInBackground(work: () -> Unit, onError: () -> Unit = {}, done: () -> Unit) {
        try {
            io.execute {
                try {
                    work()
                    ui.post(done)
                } catch (e: Exception) {
                    ui.post {
                        toast(Relay.describe(e))
                        onError()
                    }
                }
            }
        } catch (_: Exception) {
            // La pantalla se cerró mientras tanto.
        }
    }

    /** Aviso corto: con Bulla dentro de la app (ver BullaToast); si la app no está a la vista, el del sistema. */
    private fun toast(text: String) {
        if (started && !isFinishing && ::bullaToast.isInitialized) bullaToast.show(text)
        else Toast.makeText(this, text, Toast.LENGTH_LONG).show()
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
