package com.friendgame.app

import android.Manifest
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
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
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * Capturas de «Grabar la broma» en screenshots/fase-4: la tarjeta en Sonar, la pantalla que lo
 * explica, la píldora REC con la burbuja de la cámara y el video terminado.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xhdpi", manifest = Config.NONE, shadows = [IconShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Recording {
    private val repo = System.getProperty("repo")
    private val out = File(System.getProperty("shots") ?: "$repo/screenshots")
    private val w = 780
    private val h = 1688

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

    private fun shot(activity: Activity, file: String): Bitmap {
        idle(300)
        val view = activity.window.decorView
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(out, file).also { it.parentFile.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
        return bitmap
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

    @Test
    fun render() {
        fonts()
        val app = RuntimeEnvironment.getApplication()
        Store.init(app)
        Store.myName = "Robot"
        Store.loudAccepted = true
        Store.loudAnswered = true
        Store.putFriend(Friend("aaaabbbbccccdddd", "Ana", Friend.FRIEND))
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle(1500)
        val card = find(activity.window.decorView) { text(it).contains("Graba tu broma") && it.isClickable }!!
        val scroll = find(activity.window.decorView) { it is android.widget.ScrollView && it.isShown } as android.widget.ScrollView
        scroll.scrollTo(0, ((card.parent as View).top + card.top - 500).coerceAtLeast(0))
        shot(activity, "fase-4/01-sonar-tarjeta.png")

        tap(activity, "Graba tu broma")
        idle(800)
        shot(activity, "fase-4/02-graba-tu-broma.png")
        tap(activity, "📷 Trasera")
        val sheetScroll = find(activity.window.decorView) { it is android.widget.ScrollView && it.isShown && it.parent?.parent is RecordStartSheet } as? android.widget.ScrollView
        sheetScroll?.scrollTo(0, 2000)
        shot(activity, "fase-4/03-graba-tu-broma-opciones.png")
        tap(activity, "Volver")

        // Grabando: la píldora REC arriba y la burbuja de la cámara (el entorno de prueba no tiene cámara).
        Grabacion.recording()
        idle(2500)
        shot(activity, "fase-4/04-grabando.png")

        // Terminó: el video, para compartir, borrar o grabar otro.
        Grabacion.done(Grabacion.Clip(Uri.parse("content://media/external/video/media/1"), null, 42_000, inGallery = true))
        idle(1200)
        shot(activity, "fase-4/05-quedo-grabada.png")
    }
}
