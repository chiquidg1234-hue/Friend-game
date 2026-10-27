package com.friendgame.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * Capturas de cada fase del diseño 2.0 en screenshots/ (ver screenshots/README.md):
 * fase-1 (intro, Bulla, avisos), fase-2 (pantallas) y fase-3 (fondos y detección de audio).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xhdpi", manifest = Config.NONE, shadows = [IconShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Phases {
    private val repo = System.getProperty("repo")
    private val out = File(System.getProperty("shots") ?: "$repo/screenshots")
    private val w = 780
    private val h = 1688
    private val ana = "aaaabbbbccccdddd"
    private val beto = "eeeeffffgggghhhh"

    private fun idle(ms: Long = 1500, step: Long = 50) {
        var left = ms
        while (left > 0) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(minOf(step, left)))
            shadowOf(Looper.getMainLooper()).idle()
            left -= step
        }
    }

    private fun fonts() {
        val cacheField = Fonts::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(Fonts) as HashMap<String, android.graphics.Typeface>
        for (font in listOf("fredoka", "nunito")) for (weight in 100..1000 step 100) {
            cache["fonts/$font.ttf:$weight"] = android.graphics.Typeface.Builder(File("$repo/app/src/main/assets/fonts/$font.ttf"))
                .setFontVariationSettings("'wght' $weight").setWeight(weight).build()
        }
    }

    private fun data(friends: Boolean = true) {
        val app = RuntimeEnvironment.getApplication()
        Store.init(app)
        Store.myName = "Robot"
        Store.loudAccepted = true
        Store.loudAnswered = true
        if (friends) {
            Store.putFriend(Friend(ana, "Ana", Friend.FRIEND))
            Store.putFriend(Friend(beto, "Beto", Friend.FRIEND))
            Streaks.onReceived(ana)
            Streaks.onSent(ana)
            Presence.seen(ana)
        }
        repeat(30) { Points.onSoundSent(); Points.onChatSent() }
        Points.onDailyOpen()
    }

    /** Dibuja la pantalla (o solo [view]) en un PNG del tamaño de un teléfono. */
    private fun capture(view: View, file: String, width: Int = w, height: Int = h): Bitmap {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(out, file).also { it.parentFile.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
        return bitmap
    }

    private fun shot(activity: Activity, file: String): Bitmap {
        idle(300)
        return capture(activity.window.decorView, file)
    }

    /** Varias capturas lado a lado (con un margen) en un solo PNG. */
    private fun strip(file: String, frames: List<Bitmap>, scale: Float = 0.5f) {
        val fw = (frames[0].width * scale).toInt()
        val fh = (frames[0].height * scale).toInt()
        val pad = 24
        val bitmap = Bitmap.createBitmap(pad + frames.size * (fw + pad), fh + 2 * pad, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF0B0618.toInt())
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        frames.forEachIndexed { i, frame ->
            canvas.drawBitmap(Bitmap.createScaledBitmap(frame, fw, fh, true), (pad + i * (fw + pad)).toFloat(), pad.toFloat(), paint)
        }
        File(out, file).also { it.parentFile.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    private fun find(v: View, predicate: (View) -> Boolean): View? {
        if (predicate(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), predicate)?.let { return it }
        return null
    }

    private fun text(v: View): String = (v.contentDescription?.toString() ?: "") + " " + ((v as? android.widget.TextView)?.text?.toString() ?: "")

    private fun tap(activity: Activity, what: String) {
        val root = activity.window.decorView
        val v = find(root) { it.isShown && it.isClickable && text(it).trim().startsWith(what) }
            ?: find(root) { it.isShown && (it as? android.widget.TextView)?.text?.toString() == what }?.let { t -> generateSequence(t) { it.parent as? View }.firstOrNull { it.isClickable } }
            ?: error("No encontré $what")
        v.performClick()
        idle(400)
    }

    private inline fun <reified T : View> child(activity: Activity): T? = find(activity.window.decorView) { it is T && it.isShown } as T?

    @Test
    fun render() {
        fonts()
        data()
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(9000, 0) }
        val app = RuntimeEnvironment.getApplication()

        // ---------------------------------------------------------- Fase 1
        // Intro: cuadros a lo largo de la animación.
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val first = Robolectric.buildActivity(MainActivity::class.java, launch).setup().get()
        val intro = mutableListOf<Bitmap>()
        idle(80)
        intro += capture(first.window.decorView, "fase-1/01-intro-a.png")
        idle(520)
        intro += capture(first.window.decorView, "fase-1/01-intro-b.png")
        idle(500)
        intro += capture(first.window.decorView, "fase-1/01-intro-c.png")
        idle(700)
        intro += capture(first.window.decorView, "fase-1/01-intro-d.png")
        strip("fase-1/01-intro.png", intro)
        listOf("a", "b", "c", "d").forEach { File(out, "fase-1/01-intro-$it.png").delete() }
        idle(1500)

        // Bulla: sus estados en hoja.
        val states = listOf<Pair<String, (PetView) -> Unit>>(
            "tranquila" to {}, "feliz" to { it.cheer() }, "bailando" to { it.dance() }, "sorprendida" to { it.surprise() },
            "riendo" to { it.laugh() }, "asustada" to { it.scare() }, "enamorada" to { it.love() }, "traviesa" to { it.troll() },
            "saluda" to { it.wave() }, "habla" to { it.chat() }, "festeja" to { it.celebrate() }, "come" to { it.eat("🍩") },
        )
        val cell = 300
        val sheet = Bitmap.createBitmap(cell * 4, (cell + 50) * 3, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Palette.BG)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.TEXT
            textSize = 30f
            textAlign = Paint.Align.CENTER
            typeface = Fonts.display(app, 600)
        }
        states.forEachIndexed { i, (name, act) ->
            val pet = PetView(app).apply { species = "bulla" }
            pet.measure(View.MeasureSpec.makeMeasureSpec(cell, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(cell, View.MeasureSpec.EXACTLY))
            pet.layout(0, 0, cell, cell)
            act(pet)
            ShadowSystemClock.advanceBy(Duration.ofMillis(if (name == "come") 1200 else 420))
            canvas.save()
            canvas.translate(((i % 4) * cell).toFloat(), ((i / 4) * (cell + 50)).toFloat())
            pet.draw(canvas)
            canvas.drawText(name, cell / 2f, cell + 36f, label)
            canvas.restore()
        }
        File(out, "fase-1/02-bulla-estados.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 90, it) }
        File("$repo/tools/render/out/mascot.png").takeIf { it.exists() }?.copyTo(File(out, "fase-1/03-bulla-animaciones.png"), overwrite = true)

        // Bienvenida con Bulla (sin nombre todavía).
        Store.myName = ""
        val welcome = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle(900)
        shot(welcome, "fase-1/04-bienvenida.png")
        Store.myName = "Robot"

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle(600)
        // Mandar un sonido: el fondo se abre con su categoría, el emoji vuela y Bulla asoma.
        tap(activity, "Pato")
        idle(250)
        val send1 = shot(activity, "fase-1/05-envio-a.png")
        idle(500)
        val send2 = shot(activity, "fase-1/05-envio-b.png")
        strip("fase-1/05-envio.png", listOf(send1, send2))
        File(out, "fase-1/05-envio-a.png").delete()
        File(out, "fase-1/05-envio-b.png").delete()
        idle(3000)

        // Aviso con Bulla (toast) y pantalla vacía de Chats.
        tap(activity, "Pestaña Grupos")
        idle(900)
        shot(activity, "fase-1/06-vacio-grupos.png")

        // ---------------------------------------------------------- Fase 2
        tap(activity, "Pestaña Sonar")
        idle(600)
        val sonar = activity.window.decorView
        val scroll = find(sonar) { it is android.widget.ScrollView && it.isShown } as android.widget.ScrollView
        scroll.scrollTo(0, 0)
        shot(activity, "fase-2/01-sonar.png")
        val anchor = find(sonar) { it is android.widget.TextView && it.text.toString() == "Sonidos" }!!
        scroll.scrollTo(0, (anchor.parent as View).top - 40)
        shot(activity, "fase-2/02-sonar-tarjetas.png")
        tap(activity, "Filtro Risa")
        scroll.scrollTo(0, (anchor.parent as View).top - 40)
        shot(activity, "fase-2/03-filtro-risa.png")
        tap(activity, "Filtro Todos")
        tap(activity, "Buscar un sonido")
        val input = find(activity.window.decorView) { it is android.widget.EditText && it.isShown && it.contentDescription == "Buscar un sonido" } as android.widget.EditText
        input.setText("ca")
        idle(500)
        shot(activity, "fase-2/04-buscar.png")
        tap(activity, "Cerrar el buscador")

        // Subir audio: Bulla escucha y adivina la categoría.
        val sound = CustomSound("risatest01", "", "wav")
        val wav = File(System.getProperty("user.home") + "/.cache/fg-render/audio/set/risa_jaja.wav")
        val file = CustomAudio.fileFor(app, sound)
        if (wav.exists()) wav.copyTo(file, overwrite = true) else file.writeBytes(ByteArray(1000))
        val showUpload = MainActivity::class.java.getDeclaredMethod("showUpload", CustomAudio.Imported::class.java, String::class.java).apply { isAccessible = true }
        showUpload.invoke(activity, CustomAudio.Imported(sound, "Risa de Ana", 6400), "Subir audio")
        idle(300)
        val listening = shot(activity, "fase-2/05-subir-escuchando.png")
        idle(1200)
        val sheetView = child<UploadSheet>(activity)!!
        if (wav.exists()) {
            val (samples, rate) = readWav(wav)
            val result = AudioClassifier.classify(samples, rate)
            val env = FloatArray(160) { i -> val a = i * samples.size / 160; val b = minOf(samples.size, a + samples.size / 160); var s = 0f; for (k in a until b) s += samples[k] * samples[k]; kotlin.math.sqrt(kotlin.math.sqrt(s / (b - a + 1)) * 4f).coerceAtMost(1f) }
            sheetView.showResult(AudioAnalysis.Analysis(env, result, 6400))
        }
        idle(600)
        val detected = shot(activity, "fase-2/06-subir-detectado.png")
        strip("fase-3/05-deteccion-de-audio.png", listOf(listening, detected))
        sheetView.close()
        idle(500)

        tap(activity, "Pestaña Amigos")
        shot(activity, "fase-2/07-amigos.png")
        tap(activity, "Pestaña Tú")
        shot(activity, "fase-2/08-tu.png")
        tap(activity, "Ajustes")
        idle(300)
        shot(activity, "fase-2/09-ajustes.png")
        tap(activity, "Volver")

        // Mascota tipo Pou: adoptar a Bulla y recorrer las piezas.
        Pets.adopt(Pets.SPECIES[0], "Bulla")
        Pets.init(app)
        // Que ya esté grande (adulta) para la captura.
        app.getSharedPreferences("friendgame-looks", 0).edit().putInt("pet_bonus", 600).putLong("pet_bath", System.currentTimeMillis() - 26 * 3600_000L).commit()
        Wardrobe.toggle(Wardrobe.item("fiesta")!!)
        idle(300)
        tap(activity, "🏡 Cuidar y jugar")
        idle(800)
        val room = mutableListOf<Bitmap>()
        room += shot(activity, "fase-2/10-mascota-cocina.png")
        tap(activity, "Baño")
        child<PetRoom>(activity)!!.pet.foam = 0.9f
        idle(300)
        room += shot(activity, "fase-2/11-mascota-bano.png")
        tap(activity, "Dormir")
        tap(activity, "🌙 Apagar la luz")
        idle(800)
        room += shot(activity, "fase-2/12-mascota-dormir.png")
        tap(activity, "Armario")
        idle(300)
        room += shot(activity, "fase-2/13-mascota-armario.png")
        tap(activity, "Háblale")
        idle(300)
        room += shot(activity, "fase-2/14-mascota-hablale.png")
        strip("fase-2/15-mascota-piezas.png", room, 0.4f)
        tap(activity, "Jugar")
        tap(activity, "🎮 Minijuegos")
        idle(800)
        shot(activity, "fase-2/16-minijuegos.png")
        tap(activity, "Atrapa la comida")
        val game = child<CatchGame>(activity)!!
        repeat(150) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(16))
            capture(activity.window.decorView, "fase-2/tmp.png")
        }
        shot(activity, "fase-2/17-juego-atrapa.png")
        game.stop()
        tap(activity, "Salir del juego")
        tap(activity, "Revienta burbujas")
        val bubbles = child<BubbleGame>(activity)!!
        repeat(120) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(16))
            capture(activity.window.decorView, "fase-2/tmp.png")
        }
        shot(activity, "fase-2/18-juego-burbujas.png")
        // El juego avanza como mucho 50 ms por cuadro: 30 segundos son 600 cuadros.
        val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        repeat(620) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(50))
            activity.window.decorView.draw(Canvas(frame))
        }
        idle(1200)
        shot(activity, "fase-2/19-juego-resultado.png")
        File(out, "fase-2/tmp.png").delete()
        bubbles.stop()
        tap(activity, "Salir")
        tap(activity, "Volver")
        tap(activity, "Volver")

        // ---------------------------------------------------------- Fase 3
        // Los seis fondos de categoría, quietos y con el volumen al máximo.
        val scenes = Vibes.all.map { vibe ->
            val scene = SceneView(app).apply { level = { 0.7f } }
            scene.base = Looks.sceneFor(vibe.key).key
            ShadowSystemClock.advanceBy(Duration.ofMillis(700))
            capture(scene, "fase-3/tmp.png", 540, 960)
        }
        strip("fase-3/01-fondos-por-categoria.png", scenes, 0.6f)
        File(out, "fase-3/tmp.png").delete()

        // Te llega un sonido: el fondo se abre con su categoría y Bulla reacciona.
        tap(activity, "Pestaña Sonar")
        val incoming = listOf("pato" to "risa", "redoble" to "fiesta", "sirena" to "susto", "campana" to "amor").map { (key, vibe) ->
            val s = Sounds.byKey(key)!!
            // Sin aapt no hay assets: el sonido se pasa como archivo (lo «reproduce» ShadowMediaPlayer).
            val copy = File(app.cacheDir, "$key.ogg")
            File("$repo/app/src/main/assets/${s.asset}").copyTo(copy, overwrite = true)
            Player.play(app, Player.Source.FromFile(copy), Player.NowPlaying("p-$key", ana, "Ana", s.label, key, vibe = vibe), incoming = true)
            idle(200)
            val opening = capture(activity.window.decorView, "fase-3/tmp.png")
            idle(1400)
            val full = shot(activity, "fase-3/02-recibir-$vibe.png")
            Player.stop()
            idle(800)
            if (vibe == "risa") strip("fase-3/03-reaccion-al-llegar.png", listOf(opening, full))
            full
        }
        strip("fase-3/04-recibir-por-categoria.png", incoming, 0.45f)
        File(out, "fase-3/tmp.png").delete()

        // Escuchar un sonido: el fondo late con el audio y Bulla mueve la boca.
        tap(activity, "Pestaña Tú")
        val bgAnchor = find(activity.window.decorView) { it is android.widget.TextView && it.text.toString() == "Fondo de la app" }!!
        val tuScroll = find(activity.window.decorView) { it is android.widget.ScrollView && it.isShown } as android.widget.ScrollView
        tuScroll.scrollTo(0, (bgAnchor.parent as View).top - 300)
        shot(activity, "fase-3/06-elegir-fondo.png")
        tap(activity, "🕺 Disco")
        idle(400)
        tap(activity, "Pestaña Sonar")
        shot(activity, "fase-3/07-fondo-disco-elegido.png")
    }

    private fun readWav(file: File): Pair<FloatArray, Int> {
        val bytes = file.readBytes()
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = 16000
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = buf.getInt(pos + 4)
            if (id == "fmt ") rate = buf.getInt(pos + 12)
            if (id == "data") {
                val count = minOf(size, bytes.size - pos - 8) / 2
                return FloatArray(count) { buf.getShort(pos + 8 + it * 2) / 32768f } to rate
            }
            pos += 8 + size + (size and 1)
        }
        return FloatArray(0) to rate
    }
}
