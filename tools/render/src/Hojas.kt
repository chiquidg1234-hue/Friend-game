package com.friendgame.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * Capturas de las hojas (Sheet) y de las pantallas nuevas en screenshots/fase-5: cada hoja se
 * dibuja abajo, sobre la pantalla oscurecida, como se ve en el celular.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xhdpi", manifest = Config.NONE, shadows = [IconShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Hojas {
    private val repo = System.getProperty("repo")
    private val out = File(System.getProperty("shots") ?: "$repo/screenshots", "fase-5")
    private val w = 780
    private val h = 1688

    fun idle(ms: Long = 600, step: Long = 50) {
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

    private fun draw(view: View, width: Int, height: Int, exact: Boolean): Bitmap {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, if (exact) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(view.measuredWidth.coerceAtLeast(1), view.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun save(bitmap: Bitmap, name: String) {
        File(out, name).also { it.parentFile.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    /** La pantalla de la app tal cual. */
    fun screen(activity: Activity, name: String) {
        idle(400)
        save(draw(activity.window.decorView, w, h, exact = true), name)
    }

    /** La hoja abierta más reciente, abajo, sobre la pantalla oscurecida. */
    fun sheet(activity: Activity, name: String) {
        idle(400)
        val dialog: Dialog = ShadowDialog.getLatestDialog() ?: error("no hay hoja abierta para $name")
        val back = draw(activity.window.decorView, w, h, exact = true)
        val content = (dialog.window!!.decorView as ViewGroup)
        val front = draw(content, w, h, exact = false)
        val canvas = Canvas(back)
        canvas.drawColor(0x9E000000.toInt())
        canvas.drawBitmap(front, 0f, (h - front.height).toFloat(), null)
        save(back, name)
        dialog.dismiss()
        idle(200)
    }

    fun call(activity: Activity, method: String, vararg args: Any) {
        val m = MainActivity::class.java.declaredMethods.first { it.name == method && it.parameterCount == args.size }
        m.isAccessible = true
        m.invoke(activity, *args)
        idle(300)
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
        Store.putFriend(Friend("eeeeffffgggghhhh", "Beto", Friend.FRIEND))
        repeat(30) { Points.onSoundSent(); Points.onChatSent() }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle(1500)

        call(activity, "soundOptions", Sounds.byKey("bocina")!!)
        sheet(activity, "01-opciones-sonido.png")
        call(activity, "customizeButton", "pato", "🦆", 0xFF1DD1A1.toInt(), "Pato", true)
        sheet(activity, "02-personalizar-boton.png")
        call(activity, "chooseVibe", "pato")
        sheet(activity, "03-categoria.png")
        Extra.run(this, activity)
    }
}
