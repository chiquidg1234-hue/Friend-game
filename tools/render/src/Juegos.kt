package com.friendgame.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import android.view.View
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

/** Capturas de los minijuegos (la lista y cada juego andando) en screenshots/fase-5. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xhdpi", manifest = Config.NONE, shadows = [IconShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Juegos {
    private val repo = System.getProperty("repo")
    private val out = File(System.getProperty("shots") ?: "$repo/screenshots", "fase-5")
    private val w = 780
    private val h = 1688

    private fun frame(view: View): Bitmap {
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    /** Avanza el juego [ms] dibujando un cuadro cada 33 ms (cada cuadro mueve el juego). */
    private fun play(view: View, ms: Long, each: (Long) -> Unit = {}) {
        var t = 0L
        while (t < ms) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(33))
            shadowOf(Looper.getMainLooper()).idle()
            frame(view)
            each(t)
            t += 33
        }
    }

    private fun save(view: View, name: String) {
        File(out, name).also { it.parentFile.mkdirs() }.outputStream().use { frame(view).compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    private fun tap(view: View, x: Float, y: Float) {
        view.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun swipe(view: View, x0: Float, y0: Float, x1: Float, y1: Float) {
        view.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x0, y0, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(0, 80, MotionEvent.ACTION_UP, x1, y1, 0))
    }

    private fun game(screen: GamesScreen): GameView {
        fun find(v: View): GameView? = if (v is GameView) v else (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        return find(screen) ?: error("no hay juego")
    }

    private fun start(screen: GamesScreen, kind: Games.Kind): GameView {
        val m = GamesScreen::class.java.getDeclaredMethod("start", Games.Kind::class.java).apply { isAccessible = true }
        m.invoke(screen, kind)
        return game(screen)
    }

    @Test
    fun render() {
        Hojas().fonts()
        val app = RuntimeEnvironment.getApplication()
        Store.init(app)
        Store.myName = "Robot"
        Pets.adopt(Pets.species("bulla")!!, "Bulla")
        val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val screen = GamesScreen(activity, "bulla", {}, {})
        activity.setContentView(screen)
        play(screen, 900)
        save(screen, "30-minijuegos.png")
        val scroll = (screen.getChildAt(0) as android.widget.ScrollView)
        scroll.scrollTo(0, 99999)
        play(screen, 300)
        save(screen, "31-minijuegos-abajo.png")

        val d = activity.resources.displayMetrics.density
        // Carrera: cambiar de carril y saltar mientras avanza.
        var g = start(screen, Games.Kind.CAR)
        play(screen, 3200) { t ->
            if (t in 990L..1022L) swipe(g, 300f, 1200f, 100f, 1200f)
            if (t in 2310L..2342L) swipe(g, 300f, 1200f, 600f, 1200f)
        }
        save(screen, "32-carrera.png")
        play(screen, 1200) { t -> if (t in 0L..32L) swipe(g, 400f, 1300f, 400f, 1000f) }
        save(screen, "33-carrera-salto.png")

        // Memoria: dar vuelta cuatro cartas.
        g = start(screen, Games.Kind.MEMORY)
        play(screen, 400)
        val size = minOf(w - 32 * d, h - 300 * d)
        val cell = size / 4f
        val x0 = (w - size) / 2f
        val y0 = maxOf(150 * d, 150 * d + (h - 150 * d - 120 * d - size) / 2f)
        fun cellAt(i: Int) = Pair(x0 + cell * (i % 4 + 0.5f), y0 + cell * (i / 4 + 0.5f))
        cellAt(0).let { tap(g, it.first, it.second) }
        cellAt(5).let { tap(g, it.first, it.second) }
        play(screen, 1500)
        cellAt(10).let { tap(g, it.first, it.second) }
        play(screen, 200)
        save(screen, "34-memoria.png")

        // Topo: unos segundos con golpes al azar.
        g = start(screen, Games.Kind.MOLE)
        play(screen, 2600)
        save(screen, "35-topo.png")

        // Los de antes, para ver los fondos nuevos.
        g = start(screen, Games.Kind.CATCH)
        play(screen, 2500)
        save(screen, "36-atrapa.png")
        g = start(screen, Games.Kind.JUMP)
        play(screen, 2500)
        save(screen, "37-salto.png")
        g = start(screen, Games.Kind.BUBBLES)
        play(screen, 2500)
        save(screen, "38-burbujas.png")
    }
}
