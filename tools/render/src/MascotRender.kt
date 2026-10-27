package com.friendgame.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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

/**
 * Bulla en cada estado (filas) a lo largo del tiempo (columnas), y la ropa del armario puesta en
 * varias especies. Deja mascot.png y outfits.png.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MascotRender {
    private val cell = 200

    private fun view(species: String = "bulla"): PetView = PetView(RuntimeEnvironment.getApplication()).apply {
        this.species = species
        stage = 3
        measure(View.MeasureSpec.makeMeasureSpec(cell, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(cell, View.MeasureSpec.EXACTLY))
        layout(0, 0, cell, cell)
    }

    @Test
    fun render() {
        val rows: List<Pair<String, (PetView) -> Unit>> = listOf(
            "tranquila" to { _ -> },
            "feliz" to { v -> v.cheer() },
            "baila" to { v -> v.dance() },
            "sorpresa" to { v -> v.surprise() },
            "risa" to { v -> v.laugh() },
            "susto" to { v -> v.scare() },
            "amor" to { v -> v.love() },
            "travieso" to { v -> v.troll() },
            "saluda" to { v -> v.wave() },
            "habla" to { v -> v.chat() },
            "festeja" to { v -> v.celebrate() },
            "ducha" to { v -> v.shower() },
            "come" to { v -> v.eat("🍪") },
            "escucha" to { v -> v.listening = true },
            "espuma" to { v -> v.foam = 1f; v.dirt = 0.6f },
            "audio" to { v -> v.audioLevel = { (System.nanoTime() / 7_000_000 % 10) / 10f }; v.glow = 0xFF48DBFB.toInt() },
        )
        val times = listOf(0.1, 0.3, 0.5, 0.8, 1.1, 1.5, 2.0)
        val labelW = 150
        val bitmap = Bitmap.createBitmap(labelW + cell * times.size, cell * rows.size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF231848.toInt())
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 28f
        }
        rows.forEachIndexed { r, (name, setup) ->
            val v = view()
            setup(v)
            canvas.drawText(name, 12f, r * cell + cell / 2f, text)
            var elapsed = 0L
            times.forEachIndexed { c, t ->
                val target = (t * 1000).toLong()
                ShadowSystemClock.advanceBy(Duration.ofMillis(target - elapsed))
                elapsed = target
                canvas.save()
                canvas.translate((labelW + c * cell).toFloat(), (r * cell).toFloat())
                v.draw(canvas)
                canvas.restore()
            }
        }
        File(System.getProperty("out"), "mascot.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // Ropa: cada prenda en Bulla, el gato, la rana y el pulpo.
        val species = listOf("bulla", "gato", "rana", "pulpo", "conejo")
        val items = Wardrobe.ITEMS
        val sheet = Bitmap.createBitmap(cell * items.size, cell * species.size, Bitmap.Config.ARGB_8888)
        val c2 = Canvas(sheet)
        c2.drawColor(0xFF231848.toInt())
        species.forEachIndexed { r, sp ->
            items.forEachIndexed { c, item ->
                val v = view(sp)
                v.animated = false
                v.outfit = when (item.slot) {
                    Wardrobe.Slot.HAT -> Wardrobe.Outfit(item.key, null, null)
                    Wardrobe.Slot.EYES -> Wardrobe.Outfit(null, item.key, null)
                    Wardrobe.Slot.NECK -> Wardrobe.Outfit(null, null, item.key)
                }
                c2.save()
                c2.translate((c * cell).toFloat(), (r * cell).toFloat())
                v.draw(c2)
                c2.restore()
            }
        }
        File(System.getProperty("out"), "outfits.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
