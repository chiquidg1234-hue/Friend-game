package com.friendgame.app

import android.app.Activity
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
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", manifest = Config.NONE, shadows = [IconShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Screens {
    private val out = System.getProperty("out")

    private fun idle() {
        repeat(5) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(300))
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** Captura toda la pantalla, y la parte con scroll completa (alto real del contenido). */
    private fun shot(activity: Activity, name: String, fullHeight: Boolean = true) {
        idle()
        val decor = activity.window.decorView
        val w = 780
        val content = findScroll(decor)
        val extra = if (fullHeight && content != null) (content.getChildAt(0).height - content.height).coerceAtLeast(0) else 0
        val h = 1688 + extra
        decor.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    private fun findScroll(v: View): android.widget.ScrollView? {
        if (v is android.widget.ScrollView && v.visibility == View.VISIBLE && v.isShown) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun find(v: View, predicate: (View) -> Boolean): View? {
        if (predicate(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), predicate)?.let { return it }
        return null
    }

    private fun tap(activity: Activity, desc: String) {
        val v = find(activity.window.decorView) { (it.contentDescription?.toString() ?: (it as? android.widget.TextView)?.text?.toString() ?: "").startsWith(desc) && it.isClickable }
            ?: find(activity.window.decorView) { (it as? android.widget.TextView)?.text?.toString() == desc }?.let { t -> generateSequence(t) { it.parent as? View }.firstOrNull { it.isClickable } }
            ?: error("No encontré $desc")
        v.performClick()
        idle()
    }

    @Test
    fun render() {
        val app = RuntimeEnvironment.getApplication()
        // Sin recursos compilados no hay assets: las fuentes se cargan desde el repo.
        val cacheField = Fonts::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(Fonts) as HashMap<String, android.graphics.Typeface>
        for (font in listOf("fredoka", "nunito")) for (w in 100..1000 step 100) {
            cache["fonts/$font.ttf:$w"] = android.graphics.Typeface.Builder(File(System.getProperty("repo") + "/app/src/main/assets/fonts/$font.ttf"))
                .setFontVariationSettings("'wght' $w").setWeight(w).build()
        }
        Store.init(app)
        Store.myName = "Robot"
        val ana = "aaaabbbbccccdddd"
        val beto = "eeeeffffgggghhhh"
        Store.putFriend(Friend(ana, "Ana", Friend.FRIEND))
        Store.putFriend(Friend(beto, "Beto", Friend.FRIEND))
        repeat(12) { Points.onSoundSent() }
        Points.onDailyOpen()
        Streaks.onReceived(ana)
        Streaks.onSent(ana)
        Pets.adopt(Pets.SPECIES[0], "Pelusa")
        repeat(40) { Points.onSoundSent(); Points.onChatSent() }
        Quests.track(Quests.CHAT)
        val dir = Sonoteca.dir(app)
        File(System.getProperty("repo") + "/tools/e2e/tono_20s.ogg").copyTo(File(dir, "sp1abcdefgh.ogg"), overwrite = true)
        File(System.getProperty("repo") + "/tools/e2e/tono_20s.ogg").copyTo(File(dir, "sp2abcdefgh.ogg"), overwrite = true)
        val now = System.currentTimeMillis()
        val items = org.json.JSONArray()
            .put(org.json.JSONObject("{\"id\":\"sp1abcdefgh\",\"label\":\"Risa de Ana\",\"e\":\"😂\",\"x\":\"ogg\",\"st\":0,\"by\":\"$ana\",\"byn\":\"Ana\",\"sh\":[{\"code\":\"$ana\",\"name\":\"Ana\"},{\"code\":\"$beto\",\"name\":\"Beto\"}],\"t\":${now - 60000}}"))
            .put(org.json.JSONObject("{\"id\":\"sp2abcdefgh\",\"label\":\"Gallo loco\",\"e\":\"🐓\",\"x\":\"ogg\",\"st\":0,\"by\":\"$beto\",\"byn\":\"Beto\",\"sh\":[{\"code\":\"$beto\",\"name\":\"Beto\"}],\"t\":$now,\"used\":true}"))
        app.getSharedPreferences("friendgame-sonoteca", 0).edit().putString("items", items.toString()).commit()
        Replies.onIncoming("f:$ana", "Ana", "Sirena")
        Presence.seen(ana)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        shot(activity, "1-sonar.png")
        tap(activity, "Modo escuchar")
        tap(activity, "Como le llega a Ana")
        shot(activity, "1b-escuchar.png")
        tap(activity, "Modo mandar")
        tap(activity, "Pestaña Tú")
        shot(activity, "2-tu.png")
        tap(activity, "Mercado")
        shot(activity, "3-mercado.png")
        tap(activity, "Volver")
        tap(activity, "Pestaña Amigos")
        shot(activity, "4-amigos.png", fullHeight = false)
    }
}
