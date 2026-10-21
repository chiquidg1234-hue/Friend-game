package com.friendgame.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random

/*
 * Lo que invita a volver, sin trampas. Sigue el ciclo de los juegos sociales (un aviso, una acción
 * corta, un premio que varía y algo que se va construyendo) con límites:
 * - nada se compra con dinero y los premios no se pueden cambiar por nada;
 * - perder una racha no quita puntos ni nivel;
 * - a lo más un recordatorio al día, nunca de noche, y se apaga en Ajustes;
 * - no hay cuentas regresivas falsas ni "solo por hoy".
 */

private fun engagePrefs(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences("friendgame-engage", Context.MODE_PRIVATE)

private fun epochDay(): Long = LocalDate.now().toEpochDay()

/**
 * Racha con cada amigo: los días seguidos en que los dos se mandaron algo (un sonido o un
 * mensaje). Se ve con 🔥 junto a su nombre; con ⌛ si hoy todavía falta que alguno mande algo.
 */
object Streaks {
    private lateinit var prefs: SharedPreferences

    @Synchronized
    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = engagePrefs(context)
    }

    private fun json(code: String): JSONObject = try {
        JSONObject(prefs.getString("streak_$code", "{}") ?: "{}")
    } catch (_: JSONException) {
        JSONObject()
    }

    fun onSent(code: String) = touch(code, sent = true)

    fun onReceived(code: String) = touch(code, sent = false)

    @Synchronized
    private fun touch(code: String, sent: Boolean) {
        val day = epochDay()
        val o = json(code)
        o.put(if (sent) "ls" else "lr", day)
        if (o.optLong("ls", -1) == day && o.optLong("lr", -1) == day && o.optLong("d", -9) != day) {
            val days = if (o.optLong("d", -9) == day - 1) o.optInt("n") + 1 else 1
            o.put("n", days).put("d", day)
            val name = Store.friend(code)?.name ?: "tu amigo"
            if (days >= 2) Points.celebrate("🔥 ¡Racha de $days días con $name!")
            Points.onFriendStreak(days)
            Quests.track(Quests.STREAK)
        }
        prefs.edit().putString("streak_$code", o.toString()).apply()
        Store.notifyChanged()
    }

    /** Días seguidos con ese amigo (0 si se cortó). */
    @Synchronized
    fun days(code: String): Int {
        val o = json(code)
        return if (o.optLong("d", -9) >= epochDay() - 1) o.optInt("n") else 0
    }

    /** La racha sigue, pero hoy todavía no se mandaron nada los dos: se corta a medianoche. */
    @Synchronized
    fun atRisk(code: String): Boolean = days(code) > 0 && json(code).optLong("d", -9) != epochDay()

    /** Texto corto para la lista de amigos ("🔥 5" o "🔥 5 ⌛"), o null sin racha. */
    fun badge(code: String): String? {
        val days = days(code)
        if (days <= 0) return null
        return "🔥 $days" + if (atRisk(code)) " ⌛" else ""
    }

    fun describe(code: String): String? {
        val days = days(code)
        if (days <= 0) return null
        return "Racha de $days ${if (days == 1) "día" else "días"}" + if (atRisk(code)) ", se corta hoy si no se mandan nada" else ""
    }

    @Synchronized
    fun forget(code: String) {
        prefs.edit().remove("streak_$code").apply()
    }
}

/**
 * Misiones del día: tres metas cortas que cambian cada día. Cada una da puntos y, al completar
 * las tres, se abre un cofre con un premio que varía (puntos, un protector de racha o un poder).
 */
object Quests {
    class Quest(val key: String, val emoji: String, val text: String, val goal: Int)

    const val SEND = "send"
    const val CHAT = "chat"
    const val GROUP = "group"
    const val RETO = "reto"
    const val STREAK = "streak"
    const val PET = "pet"
    const val SONOTECA = "sonoteca"
    const val SHARE = "share"
    const val CUSTOM = "custom"

    /** La primera es siempre la misma; las otras dos van rotando. */
    private val FIRST = Quest(SEND, "🔊", "Manda 3 sonidos", 3)
    private val SOCIAL = listOf(
        Quest(CHAT, "💬", "Escribe 2 mensajes en el chat", 2),
        Quest(STREAK, "🔥", "Mantén una racha con un amigo", 1),
        Quest(GROUP, "👥", "Manda un sonido a un grupo", 1),
        Quest(RETO, "⚡", "Crea o juega un reto", 1),
    )
    private val FUN = listOf(
        Quest(PET, "🐾", "Alimenta o acaricia a tu mascota", 1),
        Quest(SONOTECA, "🎧", "Escucha un sonido de la Sonoteca", 1),
        Quest(CUSTOM, "🎤", "Manda un audio tuyo", 1),
        Quest(SHARE, "📤", "Comparte un sonido en la Sonoteca", 1),
        Quest(PET, "🐾", "Alimenta o acaricia a tu mascota", 1),
    )

    class Reward(val emoji: String, val text: String)

    private lateinit var prefs: SharedPreferences

    @Synchronized
    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = engagePrefs(context)
    }

    fun today(): List<Quest> {
        val day = epochDay().toInt()
        return listOf(FIRST, SOCIAL[Math.floorMod(day, SOCIAL.size)], FUN[Math.floorMod(day / 2 + day, FUN.size)])
    }

    /** Los contadores son del día: al cambiar de día empiezan de cero. */
    @Synchronized
    private fun counts(): JSONObject {
        val day = epochDay()
        val o = try {
            JSONObject(prefs.getString("quests", "{}") ?: "{}")
        } catch (_: JSONException) {
            JSONObject()
        }
        return if (o.optLong("day", -1) == day) o else JSONObject().put("day", day)
    }

    @Synchronized
    fun progress(quest: Quest): Int = counts().optInt(quest.key).coerceAtMost(quest.goal)

    fun done(quest: Quest): Boolean = progress(quest) >= quest.goal

    fun doneCount(): Int = today().count { done(it) }

    /** Suma uno a [key]; si con eso se completa una misión de hoy, da sus puntos. */
    @Synchronized
    fun track(key: String) {
        if (!::prefs.isInitialized) return
        val o = counts()
        val before = o.optInt(key)
        o.put(key, before + 1)
        prefs.edit().putString("quests", o.toString()).apply()
        today().filter { it.key == key && before < it.goal && before + 1 >= it.goal }.forEach { quest ->
            Points.onQuestDone()
            Points.celebrate("${quest.emoji} Misión cumplida: ${quest.text} (+10)")
        }
        Store.notifyChanged()
    }

    @Synchronized
    fun chestOpened(): Boolean = counts().optBoolean("chest")

    fun chestReady(): Boolean = doneCount() == 3 && !chestOpened()

    /**
     * Abre el cofre del día: casi siempre puntos (a veces muchos), y de vez en cuando un protector
     * de racha o un poder. Nada de esto se compra ni vale dinero.
     */
    @Synchronized
    fun openChest(): Reward? {
        if (!chestReady()) return null
        val o = counts().put("chest", true)
        prefs.edit().putString("quests", o.toString()).apply()
        val roll = Random.nextInt(100)
        val reward = when {
            roll < 10 && Points.giftFreeze() -> Reward("🧊", "¡Un protector de racha!")
            roll < 15 && Powers.gift(Powers.ECO) -> Reward("🔁", "¡El poder Eco, gratis para tu próximo sonido!")
            roll < 25 -> points(50, "🤑", "¡Premio gordo!")
            roll < 50 -> points(25, "💰", "")
            else -> points(15, "🪙", "")
        }
        Points.celebrate("🎁 Cofre del día: ${reward.text}")
        Store.notifyChanged()
        return reward
    }

    private fun points(amount: Int, emoji: String, lead: String): Reward {
        Points.onChestOpened(amount)
        return Reward(emoji, (if (lead.isNotEmpty()) "$lead " else "") + "+$amount puntos")
    }
}

/**
 * Un recordatorio amable al día, de 18:00 a 20:59, solo si no abriste la app y hay algo que
 * perder o que hacer (tu racha, la de un amigo, tu mascota con hambre, el cofre). Lo revisa el
 * servicio cada vez que el servidor da señales (cada ~45 s); se apaga en Ajustes.
 */
object Reminders {
    private lateinit var prefs: SharedPreferences

    @Synchronized
    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = engagePrefs(context)
    }

    var enabled: Boolean
        get() = prefs.getBoolean("reminders", true)
        set(value) {
            prefs.edit().putBoolean("reminders", value).apply()
            Store.notifyChanged()
        }

    @Synchronized
    fun check(context: Context) {
        if (!::prefs.isInitialized || !enabled || Store.myName.isBlank()) return
        val hour = LocalTime.now().hour
        if (hour !in 18..20) return
        val day = epochDay()
        // Ya la abrió hoy, o hace más de una semana que no la usa: no se insiste.
        if (prefs.getLong("reminded", -1) == day || Points.openedToday() || Points.daysSinceOpen() > 7) return
        val friendAtRisk = Store.friends().filter { it.state == Friend.FRIEND && Streaks.atRisk(it.code) }
            .maxByOrNull { Streaks.days(it.code) }
        val pet = Pets.current
        val (title, text) = when {
            Points.streak >= 2 -> "🔥 Tu racha de ${Points.streak} días se corta hoy" to "Abre Friend Game un momento para mantenerla."
            friendAtRisk != null -> "🔥 Tu racha con ${friendAtRisk.name} se corta hoy" to
                "Llevan ${Streaks.days(friendAtRisk.code)} días seguidos. Mándale un sonido o un mensaje."
            pet != null && Pets.mood() != Pets.Mood.HAPPY -> "🥺 ${Pets.name} te extraña" to "Tiene hambre. Pasa a darle de comer."
            else -> "🎁 Tu cofre del día te espera" to "Completa 3 misiones cortas y ábrelo."
        }
        prefs.edit().putLong("reminded", day).apply()
        Notifs.reminder(context, title, text)
    }
}
