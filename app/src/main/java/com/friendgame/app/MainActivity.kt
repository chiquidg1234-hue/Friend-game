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
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
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
    }

    private class Sent(val topic: String, val toName: String, val playbackId: String, val label: String)

    /** A quién va un sonido: un amigo ("f:código") o un grupo ("g:id"). */
    private class Target(val key: String, val name: String, val topic: String, val groupId: String?)

    private lateinit var content: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private var selectedTarget: String? = null
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
        val groups = Store.groups()
        addHeader()
        addUpdateCard()
        addReceivingCard()
        addBatteryCard()
        Player.now?.let { addPlayingCard(it) }
        lastSent?.let { addSentCard(it) }
        val incoming = friends.filter { it.state == Friend.INCOMING }
        if (incoming.isNotEmpty()) addRequestsCard(incoming)
        addSoundsCard(friends.filter { it.state == Friend.FRIEND }, groups)
        addGroupsCard(groups)
        addFriendsCard(friends.filter { it.state != Friend.INCOMING })
        addHistoryCard()
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

            val silentLine = row()
            silentLine.addView(label("🔔 Sonar aunque esté en silencio", 15f, bold = true), lp(0, WRAP, weight = 1f))
            val silentToggle = Switch(context).apply {
                isChecked = Store.ringInSilent
                setOnCheckedChangeListener { _, checked -> Store.ringInSilent = checked }
            }
            silentLine.addView(silentToggle, lp(WRAP, WRAP))
            addView(silentLine, lp(topDp = 14))
            addView(
                label(
                    if (Store.ringInSilent) "Usa el volumen de alarma: suena aunque el celular esté en silencio o vibración."
                    else "Usa el volumen multimedia: si el celular está en silencio, puede no oírse.",
                    13f, Palette.SUB,
                ),
                lp(topDp = 2),
            )
            val problem = Store.lastProblem.ifEmpty { if (Store.receiving) SoundCheck.silentReason(context) ?: "" else "" }
            if (problem.isNotEmpty()) {
                addView(
                    label("⚠️ ${problem.replaceFirstChar { it.uppercase() }}: los sonidos no se van a oír.", 14f, Palette.YELLOW, bold = true),
                    lp(topDp = 12),
                )
            }
            addView(pill("🔊 Probar y revisar el sonido", Palette.MUTED) { checkSound() }, lp(topDp = 12))
        }
    }

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

    /** Sin permiso de batería, algunos celulares cortan la conexión con la pantalla apagada. */
    private fun addBatteryCard() {
        if (!Store.receiving) return
        val power = getSystemService(PowerManager::class.java) ?: return
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        section("🔋 Para que suene con la pantalla apagada", Palette.CARD_HIGHLIGHT) {
            addView(
                label(
                    "Algunos celulares cierran las apps en segundo plano para ahorrar batería. " +
                        "Permite que Friend Game siga escuchando aunque no la estés usando.",
                    13f, Palette.SUB,
                ),
                lp(topDp = 4),
            )
            addView(pill("Permitir", Palette.OK) { askBatteryExemption() }, lp(topDp = 10))
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

    private fun targets(friends: List<Friend>, groups: List<Group>): List<Target> =
        friends.map { Target("f:${it.code}", it.name, Relay.topicFor(it.code), null) } +
            groups.map { Target("g:${it.id}", it.name, Relay.groupTopic(it.id), it.id) }

    private fun addSoundsCard(accepted: List<Friend>, groups: List<Group>) {
        val all = targets(accepted, groups)
        if (all.none { it.key == selectedTarget }) selectedTarget = all.firstOrNull()?.key
        val target = all.firstOrNull { it.key == selectedTarget }

        section("Mandar un sonido") {
            if (target == null) {
                addView(
                    label(
                        "Cuando un amigo acepte tu solicitud o te unas a un grupo podrás mandar sonidos. Mientras, toca uno para escucharlo aquí.",
                        13f, Palette.SUB,
                    ),
                    lp(topDp = 4),
                )
            } else {
                addView(label("Para:", 13f, Palette.SUB), lp(topDp = 6))
                val chips = row()
                all.forEach { option ->
                    val muted = if (option.groupId == null) {
                        accepted.firstOrNull { "f:${it.code}" == option.key }?.muted == true
                    } else {
                        groups.firstOrNull { it.id == option.groupId }?.muted == true
                    }
                    val prefix = when {
                        muted -> "🔇 "
                        option.groupId != null -> "👥 "
                        else -> ""
                    }
                    val chip = pill(prefix + option.name, if (option.key == selectedTarget) Palette.ACCENT else Palette.MUTED) {
                        selectedTarget = option.key
                        render()
                    }
                    chips.addView(chip, lp(WRAP, WRAP).apply { marginEnd = dp(8) })
                }
                val scroller = HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(chips)
                }
                addView(scroller, lp(topDp = 6))
                val who = if (target.groupId != null) "al grupo ${target.name}" else "a ${target.name}"
                addView(
                    label("Toca para mandar $who · mantén presionado para escucharlo tú", 13f, Palette.SUB),
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
                    background = clickable(rounded(if ("f:${friend.code}" == selectedTarget) Palette.CARD_HIGHLIGHT else Palette.BG, 16))
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    isClickable = true
                    addView(label(title, 15f, bold = true), lp())
                    addView(label(subtitle, 12f, Palette.SUB), lp(topDp = 2))
                    setOnClickListener {
                        if (friend.state == Friend.FRIEND) {
                            selectedTarget = "f:${friend.code}"
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

    private fun addGroupsCard(groups: List<Group>) {
        section("Grupos") {
            if (groups.isEmpty()) {
                addView(
                    label("Crea un grupo e invita con un código QR: un sonido le llega a todos a la vez.", 13f, Palette.SUB),
                    lp(topDp = 4),
                )
            }
            groups.forEach { group ->
                val key = "g:${group.id}"
                val count = group.members.size
                val subtitle = "$count ${if (count == 1) "miembro" else "miembros"}" +
                    (if (group.isAdmin) " · Lo administras tú" else "") +
                    (if (group.muted) " · Silenciado" else "") + " · Mantén presionado para opciones"
                val item = column().apply {
                    background = clickable(rounded(if (key == selectedTarget) Palette.CARD_HIGHLIGHT else Palette.BG, 16))
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    isClickable = true
                    addView(label((if (group.muted) "🔇 " else "👥 ") + group.name, 15f, bold = true), lp())
                    addView(label(subtitle, 12f, Palette.SUB), lp(topDp = 2))
                    setOnClickListener {
                        selectedTarget = key
                        render()
                    }
                    setOnLongClickListener {
                        groupOptions(group)
                        true
                    }
                }
                addView(item, lp(topDp = 8))
            }
            addView(pill("➕ Crear grupo", Palette.ACCENT) { createGroup() }, lp(topDp = 12))
            val joins = row()
            joins.addView(pill("📷 Unirme con QR", Palette.MUTED) { scanInvite() }, lp(0, WRAP, weight = 1f))
            joins.addView(View(context), lp(dp(8), 1))
            joins.addView(pill("🔗 Pegar enlace", Palette.MUTED) { pasteInvite() }, lp(0, WRAP, weight = 1f))
            addView(joins, lp(topDp = 8))
        }
    }

    private fun addHistoryCard() {
        val history = Store.history()
        if (history.isEmpty()) return
        section("🕑 Últimos sonidos recibidos") {
            val now = System.currentTimeMillis()
            history.take(5).forEach { entry ->
                val ago = DateUtils.getRelativeTimeSpanString(entry.time, now, DateUtils.MINUTE_IN_MILLIS)
                addView(label(entry.text, 14f, bold = true), lp(topDp = 8))
                addView(label(ago, 12f, Palette.SUB), lp())
            }
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
            val buttons = row()
            buttons.addView(pill("🎙️ Grabar", Palette.CUSTOM, Palette.INK) { recordAudio() }, lp(0, WRAP, weight = 1f))
            buttons.addView(View(context), lp(dp(8), 1))
            buttons.addView(pill("⬆️ Subir archivo", Palette.CUSTOM, Palette.INK) { pickAudio() }, lp(0, WRAP, weight = 1f))
            addView(buttons, lp(topDp = 12))
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
            option("♻️ Cambiar mi código (borra amigos y grupos)") { confirmResetCode() }
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
            toast("✅ Enviado a $toName")
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
        runInBackground({ Relay.publishTopic(sent.topic, payload) }) { toast("⏹ Listo, se detuvo tu sonido") }
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
        val size = dp(240)
        val image = ImageView(this).apply {
            setImageBitmap(Qr.bitmap(link, size))
            background = rounded(0xFFFFFFFF.toInt(), 12)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val body = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(8), dp(22), dp(8))
            addView(image, LinearLayout.LayoutParams(size + dp(16), size + dp(16)))
            addView(
                label(
                    "Que lo escanee desde Friend Game → Grupos → «Unirme con QR». " +
                        "Si no tiene la app, al escanearlo con la cámara le abre la página para instalarla.",
                    14f,
                ),
                lp(topDp = 12),
            )
            addView(label(link, 10f, Palette.SUB).apply { setTextIsSelectable(true) }, lp(topDp = 8))
        }
        AlertDialog.Builder(this)
            .setTitle("Invitar a «${group.name}»")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Compartir enlace") { _, _ -> shareInvite(group) }
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
            runInBackground({ Groups.announceJoin(group) }) { toast("👥 Te uniste a «${group.name}»") }
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
        if (requestCode == REQ_SCAN && resultCode == RESULT_OK) {
            val invite = Invites.parse(data?.getStringExtra(ScanActivity.EXTRA_TEXT))
            if (invite == null) info("Ese QR no es de Friend Game", "Pide que te muestren el QR desde Grupos → Invitar con QR.")
            else confirmJoin(invite)
            return
        }
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
        val sound = CustomSound(Codes.random(10), "", "m4a")
        val file = sound.file(this)
        val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
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
        val status = label("🎙️ Grabando… 0 s", 20f, bold = true)
        var finished = false
        lateinit var dialog: AlertDialog
        val tick = object : Runnable {
            override fun run() {
                if (finished) return
                val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
                status.text = "🎙️ Grabando… $seconds s de 8"
                ui.postDelayed(this, 250)
            }
        }
        fun finish(keep: Boolean) {
            if (finished) return
            finished = true
            ui.removeCallbacks(tick)
            val ok = try {
                recorder.stop()
                true
            } catch (_: RuntimeException) {
                false // se detuvo antes de grabar algo
            }
            recorder.release()
            dialog.dismiss()
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
            val goodbyes = Store.friends()
                .filter { it.state == Friend.FRIEND || it.state == Friend.OUTGOING }
                .map { it.code to Relay.payload("bye") }
            val groups = Store.groups()
            Player.stop()
            runInBackground({
                goodbyes.forEach { (code, payload) ->
                    try {
                        Relay.publish(code, payload)
                    } catch (_: Exception) {
                    }
                }
                groups.forEach { group ->
                    try {
                        if (group.isAdmin) Groups.close(group) else Groups.leave(group)
                    } catch (_: Exception) {
                    }
                }
            }) {}
            Store.resetCode()
            selectedTarget = null
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
