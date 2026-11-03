package com.friendgame.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/** Hoja de contacto de Bulla con varias caras, para elegir la del ícono (tools/render/out/caras.png). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Icono {
    @Test
    fun caras() {
        val context = RuntimeEnvironment.getApplication()
        val cell = 220
        val rows = listOf("troll", "laugh", "cheer", "wave", "love", "celebrate")
        val times = listOf(0.2, 0.45, 0.7, 1.0, 1.3, 1.7, 2.1)
        val bitmap = Bitmap.createBitmap(cell * times.size, cell * rows.size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF5B2BD6.toInt())
        rows.forEachIndexed { r, action ->
            val view = PetView(context)
            view.species = "bulla"
            view.stage = 3
            view.awake = true
            view.measure(View.MeasureSpec.makeMeasureSpec(cell, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(cell, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, cell, cell)
            when (action) {
                "troll" -> view.troll(3f)
                "laugh" -> view.laugh(3f)
                "cheer" -> view.cheer()
                "wave" -> view.wave()
                "love" -> view.love(3f)
                "celebrate" -> view.celebrate()
            }
            var elapsed = 0L
            times.forEachIndexed { c, t ->
                val target = (t * 1000).toLong()
                ShadowSystemClock.advanceBy(Duration.ofMillis(target - elapsed))
                elapsed = target
                canvas.save()
                canvas.translate((c * cell).toFloat(), (r * cell).toFloat())
                view.draw(canvas)
                canvas.restore()
            }
        }
        File(System.getProperty("out"), "caras.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Bulla con cara de troll, grande y con fondo transparente (para armar el ícono). */
    @Test
    fun troll() {
        val context = RuntimeEnvironment.getApplication()
        val size = 2400
        val view = PetView(context)
        view.species = "bulla"
        view.stage = 3
        view.awake = true
        view.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, size, size)
        view.troll(5f)
        val wanted = (System.getProperty("t") ?: "700").toLong()
        ShadowSystemClock.advanceBy(Duration.ofMillis(wanted))
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(System.getProperty("out"), "bulla-troll.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
