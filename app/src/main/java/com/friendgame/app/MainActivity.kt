package com.friendgame.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
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
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity(), Store.Listener {

    private companion object {
        const val REQ_PICK_AUDIO = 10
        const val REQ_NOTIFICATIONS = 11

        /** Tiempo durante el que se muestra el botón para detener el sonido que mandaste. */
        const val STOP_WINDOW_MS = 15_000L
        const val SEND_COOLDOWN_MS = 1_200L
        /**
         * Página para instalar, no el .apk directo: abierto desde WhatsApp o un lector de QR, el
         * .apk se descarga en una ventanita del navegador y no aparece el instalador. La página
         * explica cómo pasar a Chrome y ahí tiene el enlace de descarga.
         */
        const val INSTALL_PAGE = "https://github.com/chiquidg1234-hue/Friend-game/releases/latest"
    }

    private class Sent(val toCode: String, val toName: String, val playbackId: String, val label: String)

    private lateinit var content: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private var selectedCode: String? = null
    private var lastSent: Sent? = null
    private var lastSendAt = 0L
    private var renderPosted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Notifs.createChannels(this)
        goEdgeToEdge()

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Palette.BG)
        }
        content = column().apply { setPadding(dp(16), dp(12), dp(16), dp(32)) }
        scroll.addView(content, LinearLayout.LayoutParams(MATCH, WRAP))
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            applyInsets(view, insets)
            insets
        }
        setContentView(scroll)

        if (Store.myName.isBlank()) askName(first = true) else onReady()
    }

    private fun onReady() {
        ListenService.start(this)
        askNotificationPermission()
        render()
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
        super.onStop()
    }

    override fun onDestroy() {
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

    // ------------------------------------------------------------------ Pantalla

    private fun render() {
        content.removeAllViews()
        val friends = Store.friends()
        addHeader()
        addUpdateCard()
        addReceivingCard()
        Player.now?.let { addPlayingCard(it) }
        lastSent?.let { addSentCard(it) }
        val incoming = friends.filter { it.state == Friend.INCOMING }
        if (incoming.isNotEmpty()) addRequestsCard(incoming)
        addSoundsCard(friends.filter { it.state == Friend.FRIEND })
        addFriendsCard(friends.filter { it.state != Friend.INCOMING })
        addMyAudiosCard()
        addSettingsCard()
        addFooter()
    }

    private fun section(title: String?, color: Int = Palette.CARD, build: LinearLayout.() -> Unit) {
        val card = column().apply {
            background = rounded(color, 22)
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        if (title != null) card.addView(label(title, 18f, bold = true), lp())
        card.build()
        content.addView(card, lp(topDp = 14))
    }

    private fun addHeader() {
        content.addView(label("🔊 Friend Game", 28f, bold = true), lp())
        if (Store.myName.isNotBlank()) {
            content.addView(label("Hola, ${Store.myName} 👋", 16f, Palette.SUB), lp(topDp = 2))
        }
        section("Tu código de amigo") {
            addView(label(Codes.pretty(Store.myCode), 24f, Palette.YELLOW, bold = true).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            }, lp(topDp = 8))
            addView(
                label("Pásaselo solo a quien quieras que te pueda mandar sonidos.", 13f, Palette.SUB),
                lp(topDp = 4),
            )
            val buttons = row()
            buttons.addView(pill("📋 Copiar", Palette.MUTED) { copyCode() }, lp(0, WRAP, weight = 1f))
            buttons.addView(View(context), lp(dp(10), 1))
            buttons.addView(pill("📤 Compartir") { shareCode() }, lp(0, WRAP, weight = 1f))
            addView(buttons, lp(topDp = 12))
        }
    }

    private fun addUpdateCard() {
        val release = Updater.release ?: return
        val state = Updater.state
        if (state == Updater.State.IDLE || state == Updater.State.CHECKING) return
        section("🆕 Versión nueva: ${release.versionName}", Palette.CARD_HIGHLIGHT) {
            addView(label(Updater.message, 14f, Palette.SUB), lp(topDp = 4))
            when (state) {
                Updater.State.READY -> addView(
                    pill("⬆️ Actualizar ahora", Palette.OK) { Updater.install(this@MainActivity) },
                    lp(topDp = 10),
                )
                Updater.State.FAILED -> addView(
                    pill("Reintentar", Palette.MUTED) { Updater.check(this@MainActivity, force = true) },
                    lp(topDp = 10),
                )
                else -> Unit
            }
        }
    }

    private fun addReceivingCard() {
        section(null) {
            val line = row()
            val status = when {
                !Store.receiving -> "⏸ En pausa: nadie te puede mandar sonidos."
                ListenService.connected -> "🟢 Listo para recibir sonidos"
                else -> "🟡 Conectando…"
            }
            line.addView(label(status, 15f, bold = true), lp(0, WRAP, weight = 1f))
            val toggle = Switch(context).apply {
                isChecked = Store.receiving
                setOnCheckedChangeListener { _, checked ->
                    Store.receiving = checked
                    if (checked) {
                        ListenService.start(this@MainActivity)
                    } else {
                        Player.stop()
                        ListenService.stop(this@MainActivity)
                    }
                }
            }
            line.addView(toggle, lp(WRAP, WRAP))
            addView(line, lp())
            addView(label("Recibir sonidos (tú decides, siempre)", 13f, Palette.SUB), lp(topDp = 2))
        }
    }

    private fun addPlayingCard(now: Player.NowPlaying) {
        section(null, Palette.CARD_HIGHLIGHT) {
            val text = if (now.isPreview) "🔊 Sonando: ${now.label}" else "🔊 ${now.fromName} te mandó «${now.label}»"
            addView(label(text, 16f, bold = true), lp())
            addView(pill("⏹ Detener", Palette.DANGER) { Player.stop() }, lp(topDp = 10))
        }
    }

    private fun addSentCard(sent: Sent) {
        section(null, Palette.CARD_HIGHLIGHT) {
            addView(label("📨 Mandaste «${sent.label}» a ${sent.toName}", 15f, bold = true), lp())
            addView(label("¿Te pasaste? Puedes cortarlo:", 13f, Palette.SUB), lp(topDp = 2))
            addView(pill("⏹ Detener mi sonido", Palette.DANGER) { stopMySound(sent) }, lp(topDp = 10))
        }
    }

    private fun addRequestsCard(incoming: List<Friend>) {
        section("Solicitudes de amistad") {
            incoming.forEach { friend ->
                addView(label("👋 ${friend.name} te mandó una solicitud", 15f, bold = true), lp(topDp = 12))
                addView(label("Si aceptas, se podrán mandar sonidos entre ustedes.", 13f, Palette.SUB), lp(topDp = 2))
                val buttons = row()
                buttons.addView(pill("Aceptar", Palette.OK) { acceptRequest(friend) }, lp(0, WRAP, weight = 1f))
                buttons.addView(View(context), lp(dp(8), 1))
                buttons.addView(pill("Rechazar", Palette.MUTED) { Store.removeFriend(friend.code) }, lp(0, WRAP, weight = 1f))
                buttons.addView(View(context), lp(dp(8), 1))
                buttons.addView(pill("Bloquear", Palette.DANGER) { block(friend) }, lp(0, WRAP, weight = 1f))
                addView(buttons, lp(topDp = 8))
            }
        }
    }

    private fun addSoundsCard(accepted: List<Friend>) {
        if (accepted.none { it.code == selectedCode }) selectedCode = accepted.firstOrNull()?.code
        val target = accepted.firstOrNull { it.code == selectedCode }

        section("Mandar un sonido") {
            if (target == null) {
                addView(
                    label(
                        "Cuando un amigo acepte tu solicitud podrás mandarle sonidos. Mientras, toca uno para escucharlo aquí.",
                        13f, Palette.SUB,
                    ),
                    lp(topDp = 4),
                )
            } else {
                addView(label("Para:", 13f, Palette.SUB), lp(topDp = 6))
                val chips = row()
                accepted.forEach { friend ->
                    val selected = friend.code == selectedCode
                    val chip = pill(
                        (if (friend.muted) "🔇 " else "") + friend.name,
                        if (selected) Palette.ACCENT else Palette.MUTED,
                    ) {
                        selectedCode = friend.code
                        render()
                    }
                    chips.addView(chip, lp(WRAP, WRAP).apply { marginEnd = dp(8) })
                }
                val scroller = HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(chips)
                }
                addView(scroller, lp(topDp = 6))
                addView(
                    label("Toca para mandar a ${target.name} · mantén presionado para escucharlo tú", 13f, Palette.SUB),
                    lp(topDp = 10),
                )
            }

            val tiles = mutableListOf<View>()
            Sounds.all.forEach { sound ->
                tiles += soundTile(sound.emoji, sound.label, sound.color,
                    onClick = {
                        if (target == null) Player.preview(context, Player.Source.Asset(sound.asset), sound.label)
                        else sendSound(target, sound.key, sound.label, null)
                    },
                    onLongClick = { Player.preview(context, Player.Source.Asset(sound.asset), sound.label) },
                )
            }
            Store.customSounds().forEach { custom ->
                val source = Player.Source.FromFile(custom.file(context))
                tiles += soundTile("🎤", custom.label, Palette.CUSTOM,
                    onClick = {
                        if (target == null) Player.preview(context, source, custom.label)
                        else sendSound(target, "custom", custom.label, custom)
                    },
                    onLongClick = { Player.preview(context, source, custom.label) },
                )
            }
            tiles.chunked(3).forEach { group ->
                val line = row()
                group.forEachIndexed { i, tile ->
                    if (i > 0) line.addView(View(context), lp(dp(8), 1))
                    line.addView(tile, lp(0, WRAP, weight = 1f))
                }
                repeat(3 - group.size) {
                    line.addView(View(context), lp(dp(8), 1))
                    line.addView(View(context), lp(0, 1, weight = 1f))
                }
                addView(line, lp(topDp = 8))
            }
        }
    }

    private fun addFriendsCard(friends: List<Friend>) {
        val order = listOf(Friend.FRIEND, Friend.OUTGOING, Friend.BLOCKED)
        section("Amigos") {
            if (friends.isEmpty()) {
                addView(
                    label("Todavía no tienes amigos aquí. Agrega a alguien con su código 👇", 13f, Palette.SUB),
                    lp(topDp = 4),
                )
            }
            friends.sortedBy { order.indexOf(it.state) }.forEach { friend ->
                val (title, subtitle) = when (friend.state) {
                    Friend.FRIEND -> (if (friend.muted) "🔇 " else "😀 ") + friend.name to
                        (if (friend.muted) "Silenciado · " else "") + "Mantén presionado para opciones"
                    Friend.OUTGOING -> "⏳ ${friend.name}" to "Esperando que acepte tu solicitud"
                    else -> "🚫 ${friend.name}" to "Bloqueado"
                }
                val item = column().apply {
                    background = clickable(rounded(if (friend.code == selectedCode) Palette.CARD_HIGHLIGHT else Palette.BG, 16))
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    isClickable = true
                    addView(label(title, 15f, bold = true), lp())
                    addView(label(subtitle, 12f, Palette.SUB), lp(topDp = 2))
                    setOnClickListener {
                        if (friend.state == Friend.FRIEND) {
                            selectedCode = friend.code
                            render()
                        } else {
                            friendOptions(friend)
                        }
                    }
                    setOnLongClickListener {
                        friendOptions(friend)
                        true
                    }
                }
                addView(item, lp(topDp = 8))
            }
            addView(pill("➕ Agregar amigo", Palette.ACCENT) { showAddFriend() }, lp(topDp = 12))
            addView(pill("📲 Pasar la app a un amigo", Palette.MUTED) { shareApp() }, lp(topDp = 8))
            addView(
                label("Se la mandas por Quick Share o Bluetooth y la instala sin navegador.", 12f, Palette.SUB)
                    .apply { gravity = Gravity.CENTER },
                lp(topDp = 4),
            )
        }
    }

    private fun addMyAudiosCard() {
        section("Mis audios") {
            addView(label(CustomAudio.HINT, 13f, Palette.SUB), lp(topDp = 4))
            Store.customSounds().forEach { custom ->
                val line = row()
                line.addView(label("🎤 ${custom.label}", 15f, bold = true), lp(0, WRAP, weight = 1f))
                line.addView(pill("▶", Palette.MUTED) {
                    Player.preview(this@MainActivity, Player.Source.FromFile(custom.file(this@MainActivity)), custom.label)
                }, lp(WRAP, WRAP))
                line.addView(View(context), lp(dp(6), 1))
                line.addView(pill("✏️", Palette.MUTED) { renameCustom(custom) }, lp(WRAP, WRAP))
                line.addView(View(context), lp(dp(6), 1))
                line.addView(pill("🗑", Palette.MUTED) { deleteCustom(custom) }, lp(WRAP, WRAP))
                addView(line, lp(topDp = 10))
            }
            addView(pill("⬆️ Subir audio", Palette.CUSTOM, Palette.INK) { pickAudio() }, lp(topDp = 12))
        }
    }

    private fun addSettingsCard() {
        section("Ajustes") {
            fun option(text: String, onClick: () -> Unit) {
                addView(pill(text, Palette.MUTED, onClick = onClick).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }, lp(topDp = 8))
            }
            option("✏️ Cambiar mi nombre (${Store.myName})") { askName(first = false) }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                option("🔔 Activar notificaciones") { askNotificationPermission() }
            }
            val power = getSystemService(PowerManager::class.java)
            if (power != null && !power.isIgnoringBatteryOptimizations(packageName)) {
                option("🔋 Que los sonidos lleguen siempre (quitar ahorro de batería)") { askBatteryExemption() }
            } else {
                addView(label("🔋 Ahorro de batería desactivado para Friend Game ✅", 13f, Palette.SUB), lp(topDp = 10))
            }
            option("🔄 Buscar actualizaciones (versión ${Updater.currentVersionName(context)})") {
                Updater.check(this@MainActivity, force = true)
            }
            if (Updater.message.isNotEmpty() && Updater.release == null) {
                addView(label(Updater.message, 13f, Palette.SUB), lp(topDp = 4))
            }
            option("🌐 Servidor: ${Store.server}") { editServer() }
            option("♻️ Cambiar mi código (borra tus amigos)") { confirmResetCode() }
        }
    }

    private fun addFooter() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        content.addView(
            label(
                "Versión $version · Cada quien manda en su propio celular: siempre puedes detener un sonido, " +
                    "silenciar o bloquear a alguien, o pausar la recepción.",
                12f, Palette.SUB,
            ).apply { gravity = Gravity.CENTER },
            lp(topDp = 18),
        )
    }

    // ------------------------------------------------------------------ Acciones

    private fun copyCode() {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Código Friend Game", Codes.pretty(Store.myCode)))
        toast("Código copiado")
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

    private fun sendSound(target: Friend, kind: String, label: String, custom: CustomSound?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSendAt < SEND_COOLDOWN_MS) return
        lastSendAt = now
        val playbackId = Codes.random(12)
        val payload = Relay.payload("snd").put("id", playbackId).put("s", kind).put("label", label)
        val file = custom?.file(this)
        if (file != null && !file.exists()) {
            toast("No encuentro ese audio, súbelo otra vez.")
            return
        }
        runInBackground({
            if (file != null) Relay.publishFile(target.code, payload, file) else Relay.publish(target.code, payload)
        }) {
            val sent = Sent(target.code, target.name, playbackId, label)
            lastSent = sent
            toast("✅ Enviado a ${target.name}")
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
        runInBackground({ Relay.publish(sent.toCode, payload) }) { toast("⏹ Listo, se detuvo tu sonido") }
    }

    private fun acceptRequest(friend: Friend) {
        Store.putFriend(friend.copy(state = Friend.FRIEND))
        selectedCode = friend.code
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
        val codeInput = editText("", "Su código (ej. ABCD-EFGH-JKMN-PQRS)").apply {
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

    private fun clipboardCode(): String? = try {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else null
        val found = text?.let { Regex("[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}").find(it)?.value }
        Codes.normalize(found)?.takeIf { it != Store.myCode }
    } catch (_: Exception) {
        null
    }

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
        if (requestCode != REQ_PICK_AUDIO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        toast("Revisando el audio…")
        io.execute {
            try {
                val imported = CustomAudio.import(this, uri)
                ui.post { nameNewAudio(imported) }
            } catch (e: CustomAudio.ImportError) {
                ui.post { info("No se pudo agregar", e.message ?: "") }
            } catch (e: Exception) {
                ui.post { info("No se pudo agregar", "No se pudo leer ese archivo.") }
            }
        }
    }

    private fun nameNewAudio(imported: CustomAudio.Imported) {
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

    private fun askName(first: Boolean) {
        val dialog = textDialog(
            if (first) "¡Te damos la bienvenida a Friend Game! 👋" else "Cambiar mi nombre",
            "¿Cómo te llamas? Así te verán tus amigos.",
            Store.myName, "Tu nombre", "Listo",
            cancelable = !first,
        ) { name ->
            Store.myName = name
            if (first) onReady()
            true
        }
        if (first) dialog.setCanceledOnTouchOutside(false)
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
        render()
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
            "Se borrará tu lista de amigos y quien tenga tu código viejo ya no podrá escribirte. Útil si tu código llegó a alguien que no querías.",
        ) {
            val goodbyes = Store.friends()
                .filter { it.state == Friend.FRIEND || it.state == Friend.OUTGOING }
                .map { it.code to Relay.payload("bye") }
            Player.stop()
            runInBackground({
                goodbyes.forEach { (code, payload) ->
                    try {
                        Relay.publish(code, payload)
                    } catch (_: Exception) {
                    }
                }
            }) {}
            Store.resetCode()
            selectedCode = null
            ListenService.restart(this)
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
        cancelable: Boolean = true,
        onSave: (String) -> Boolean,
    ): AlertDialog {
        val input = editText(value, hint).apply { filters = arrayOf<InputFilter>(InputFilter.LengthFilter(if (hint.startsWith("http")) 200 else 24)) }
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(padded(input))
            .setCancelable(cancelable)
            .setPositiveButton(okText, null)
        if (cancelable) builder.setNegativeButton("Cancelar", null)
        val dialog = builder.create()
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
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private fun applyInsets(view: View, insets: WindowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        } else {
            view.setPadding(
                insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
            )
        }
    }
}
