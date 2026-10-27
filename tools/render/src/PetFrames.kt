package com.friendgame.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PetFrames {
    @Test
    fun render() {
        val context = RuntimeEnvironment.getApplication()
        val cell = 200
        val rows = listOf("gato" to "eat", "panda" to "eat", "dragon" to "joy", "unicornio" to "joy", "rana" to "sad")
        val times = listOf(0.15, 0.5, 0.9, 1.2, 1.5, 1.9, 2.3, 2.9)
        val bitmap = Bitmap.createBitmap(cell * times.size, cell * rows.size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF231848.toInt())
        rows.forEachIndexed { r, (species, action) ->
            val view = PetView(context)
            view.species = species
            view.stage = 3
            if (action == "sad") view.mood = Pets.Mood.SAD
            view.measure(View.MeasureSpec.makeMeasureSpec(cell, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(cell, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, cell, cell)
            val start = SystemClock.uptimeMillis()
            when (action) {
                "eat" -> view.eat()
                "joy" -> view.cheer()
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
        File(System.getProperty("out"), "frames.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
