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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PetRender {
    @Test
    fun render() {
        val context = RuntimeEnvironment.getApplication()
        val species = listOf("bulla", "gato", "perro", "zorro", "rana", "conejo", "pollito", "oso", "panda", "pulpo", "dragon", "unicornio")
        val cell = 220
        val bitmap = Bitmap.createBitmap(cell * species.size, cell * 8, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF231848.toInt())
        species.forEachIndexed { col, key ->
            for (row in 0 until 8) {
                val view = PetView(context)
                view.species = key
                view.animated = false
                view.stage = when {
                    row < 5 -> row
                    row == 7 -> 2
                    else -> 3
                }
                view.progress = 0.8f
                if (row == 5) view.mood = Pets.Mood.SAD
                if (row == 6) view.season = "halloween"
                if (row == 7) view.season = "navidad"
                view.measure(View.MeasureSpec.makeMeasureSpec(cell, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(cell, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, cell, cell)
                canvas.save()
                canvas.translate((col * cell).toFloat(), (row * cell).toFloat())
                view.draw(canvas)
                canvas.restore()
            }
        }
        File(System.getProperty("out"), "pets.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
