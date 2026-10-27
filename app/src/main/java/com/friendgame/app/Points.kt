package com.friendgame.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONException
import org.json.JSONObject
import java.util.Calendar

/**
 * Puntos, niveles, logros y el ranking con tus amigos. Todo se calcula en tu celular; tus puntos
 * viajan en cada mensaje que mandas, así tus amigos y tus grupos pueden armar el ranking.
 * Los puntos ganados cuentan para el nivel y el ranking; los que gastas (mascota, poderes) solo
 * bajan lo que te queda para gastar.
 */
object Points {

    class Level(val number: Int, val name: String, val min: Int)

    val LEVELS = listOf(
        Level(1, "Novato", 0),
        Level(2, "Travieso", 40),
        Level(3, "Bromista", 120),
        Level(4, "Ruidoso", 250),
        Level(5, "Escandaloso", 450),
        Level(6, "Maestro del susto", 700),
        Level(7, "Rey del ruido", 1000),
        Level(8, "Leyenda", 1400),
        Level(9, "Mítico", 1900),
        Level(10, "Dios del caos", 2500),
    )

    class Logro(val key: String, val emoji: String, val title: String, val text: String, val bonus: Int)

    val LOGROS = listOf(
        Logro("first_sound", "🔊", "Primer susto", "Manda tu primer sonido", 10),
        Logro("sounds_50", "📣", "Escandaloso", "Manda 50 sonidos", 30),
        Logro("heard_25", "👂", "Buen oído", "Recibe 25 sonidos", 20),
        Logro("friends_3", "🤝", "Buena onda", "Ten 3 amigos", 20),
        Logro("group", "👥", "En manada", "Crea o únete a un grupo", 10),
        Logro("chat_30", "💬", "Conversador", "Manda 30 mensajes en el chat", 20),
        Logro("reto_made", "⏰", "Organizador", "Crea 3 retos", 20),
        Logro("reto_win", "⚡", "Reflejos de gato", "Gana un reto de «El más rápido»", 30),
        Logro("lock_done", "🔒", "Palabra de honor", "Cumple un candado hasta el final", 25),
        Logro("custom", "🎤", "Mi propia voz", "Graba o sube un audio", 10),
        Logro("streak_3", "🔥", "Racha de 3", "Usa la app 3 días seguidos", 15),
        Logro("streak_7", "🌟", "Semana completa", "Usa la app 7 días seguidos", 40),
        Logro("best_friends", "💞", "Mejores amigos", "Ten una racha de 7 días con un amigo", 40),
        Logro("dj", "🎧", "DJ del grupo", "Comparte 3 sonidos en la Sonoteca", 20),
        Logro("viral", "🚀", "Viral", "Que 5 amigos usen un sonido tuyo", 50),
        Logro("halloween", "🎃", "Truco o trato", "Manda 5 sonidos en la Semana de Halloween (20 oct – 2 nov)", 30),
        Logro("navidad", "🎄", "Feliz ruido", "Manda 5 sonidos en Navidad (18 dic – 1 ene)", 30),
    )

    /** Puntos por cada cosa, con un tope diario para que no se puedan inflar mandando spam. */
    private object Rule {
        const val SOUND_SENT = 2
        const val SOUND_SENT_DAILY = 20
        const val SOUND_HEARD = 1
        const val SOUND_HEARD_DAILY = 20
        const val CHAT_SENT = 1
        const val CHAT_SENT_DAILY = 10
        const val RETO_MADE = 5
        const val RETO_MADE_DAILY = 3
        const val RETO_PLAYED = 5
        const val RETO_PLAYED_DAILY = 10
        const val DAILY = 5
        const val NEW_FRIEND = 10
        const val NEW_GROUP = 5
        const val LOCK_DONE = 20
        val RETO_PLACE = listOf(15, 8, 4)
        const val QUEST_DONE = 10
        const val SOUND_SHARED = 5
        const val SOUND_SHARED_DAILY = 3
        const val SOUND_FAN = 3
        const val SOUND_FAN_DAILY = 10
    }

    /** Protector de racha: si un día no abres la app, tu racha no se corta (se compra con puntos). */
    const val FREEZE_COST = 60
    const val MAX_FREEZES = 2

    /** Puntos de otra persona, según su último mensaje. */
    class Score(val code: String, val name: String, val total: Int, val week: Int, val level: Int)

    /** Una fila del ranking. */
    class Entry(val code: String, val name: String, val points: Int, val level: Int, val isMe: Boolean)

    private lateinit var prefs: SharedPreferences

    /** Logros y subidas de nivel que la pantalla todavía no mostró. */
    private val celebrations = ArrayList<String>()

    @Synchronized
    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences("friendgame-points", Context.MODE_PRIVATE)
        }
    }

    // ---- Mis puntos ----

    @Synchronized
    fun total(): Int = prefs.getInt("total", 0)

    /** Puntos de esta semana (de lunes a domingo). */
    @Synchronized
    fun week(): Int = if (prefs.getString("week_id", "") == weekId()) prefs.getInt("week", 0) else 0

    fun level(points: Int = total()): Level = LEVELS.last { points >= it.min }

    fun next(level: Level): Level? = LEVELS.getOrNull(level.number)

    /** Avance hacia el próximo nivel, de 0 a 1. */
    fun progress(points: Int = total()): Float {
        val current = level(points)
        val next = next(current) ?: return 1f
        return (points - current.min).toFloat() / (next.min - current.min)
    }

    fun weekId(now: Long = System.currentTimeMillis()): String {
        val calendar = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            minimalDaysInFirstWeek = 4
            timeInMillis = now
        }
        return "${calendar.weekYear}-${calendar.get(Calendar.WEEK_OF_YEAR)}"
    }

    private fun today(): String {
        val c = Calendar.getInstance()
        return "%04d%02d%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun yesterday(): String = daysAgo(1)

    private fun daysAgo(days: Int): String {
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -days) }
        return "%04d%02d%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** Ya abriste la app hoy (para no recordártelo). */
    fun openedToday(): Boolean = prefs.getString("last_day", "") == today()

    /** Días desde la última vez que abriste la app (mucho si nunca). */
    fun daysSinceOpen(): Int {
        val last = prefs.getString("last_day", "") ?: ""
        return try {
            val day = java.time.LocalDate.of(last.substring(0, 4).toInt(), last.substring(4, 6).toInt(), last.substring(6, 8).toInt())
            (java.time.LocalDate.now().toEpochDay() - day.toEpochDay()).toInt()
        } catch (_: Exception) {
            999
        }
    }

    fun stat(name: String): Int = prefs.getInt("stat_$name", 0)

    /** Puntos que te quedan para gastar en la mascota y los poderes. */
    @Synchronized
    fun balance(): Int = (total() - prefs.getInt("spent", 0)).coerceAtLeast(0)

    /** Gasta [points] si alcanzan. No cambia el nivel ni el ranking. */
    @Synchronized
    fun spend(points: Int): Boolean {
        if (balance() < points) return false
        prefs.edit().putInt("spent", prefs.getInt("spent", 0) + points).apply()
        Store.notifyChanged()
        return true
    }

    @Synchronized
    fun refund(points: Int) {
        prefs.edit().putInt("spent", (prefs.getInt("spent", 0) - points).coerceAtLeast(0)).apply()
        Store.notifyChanged()
    }

    val streak: Int get() = if (prefs.getString("last_day", "") in listOf(today(), yesterday())) stat("streak") else 0

    fun hasLogro(key: String): Boolean = prefs.getBoolean("logro_$key", false)

    /** Protectores de racha guardados. */
    val freezes: Int @Synchronized get() = stat("freezes")

    /** Compra un protector de racha. Devuelve false si no alcanzan los puntos o ya tienes el máximo. */
    @Synchronized
    fun buyFreeze(): Boolean {
        if (freezes >= MAX_FREEZES || !spend(FREEZE_COST)) return false
        bump("freezes")
        Store.notifyChanged()
        return true
    }

    /** Un protector de regalo (el cofre del día). Devuelve false si ya tienes el máximo. */
    @Synchronized
    fun giftFreeze(): Boolean {
        if (freezes >= MAX_FREEZES) return false
        bump("freezes")
        Store.notifyChanged()
        return true
    }

    /** Un aviso para festejar en la pantalla (rachas, misiones, cofre). */
    @Synchronized
    fun celebrate(text: String) {
        celebrations += text
        Store.notifyChanged()
    }

    /** Hay algo para festejar con confeti (logro o nivel nuevo). */
    @Synchronized
    fun hasBigCelebration(): Boolean = celebrations.any { it.startsWith("🎉") || it.startsWith("🏆") || it.startsWith("🎁") }

    /** Mensajes para festejar (logros, nivel nuevo); al leerlos se borran. */
    @Synchronized
    fun takeCelebrations(): List<String> {
        val list = celebrations.toList()
        celebrations.clear()
        return list
    }

    /**
     * Suma [points]. Con [daily] (nombre del contador) y [cap], no da más de [cap] veces al día.
     * Devuelve false si ya llegó al tope de hoy.
     */
    @Synchronized
    private fun add(points: Int, daily: String? = null, cap: Int = 0): Boolean {
        if (daily != null) {
            val day = today()
            if (prefs.getString("cap_day", "") != day) {
                val edit = prefs.edit()
                prefs.all.keys.filter { it.startsWith("cap_n_") }.forEach { edit.remove(it) }
                edit.putString("cap_day", day).apply()
            }
            val used = prefs.getInt("cap_n_$daily", 0)
            if (used >= cap) return false
            prefs.edit().putInt("cap_n_$daily", used + 1).apply()
        }
        val before = level()
        val week = week() + points
        prefs.edit()
            .putInt("total", total() + points)
            .putString("week_id", weekId())
            .putInt("week", week)
            .apply()
        val after = level()
        if (after.number > before.number) {
            val unlocked = Looks.SWATCHES.filter { it.level == after.number }.map { "color ${it.name}" } +
                Looks.THEMES.filter { it.level == after.number }.map { "tema ${it.name}" } +
                Pets.SPECIES.filter { it.level == after.number }.map { "mascota ${it.emoji} ${it.name}" }
            celebrations += "🎉 ¡Subiste a nivel ${after.number}: ${after.name}!" +
                if (unlocked.isEmpty()) "" else " Desbloqueaste: " + unlocked.joinToString()
        }
        Store.notifyChanged()
        return true
    }

    @Synchronized
    private fun bump(name: String, by: Int = 1): Int {
        val value = stat(name) + by
        prefs.edit().putInt("stat_$name", value).apply()
        return value
    }

    @Synchronized
    private fun unlock(key: String) {
        if (hasLogro(key)) return
        val logro = LOGROS.firstOrNull { it.key == key } ?: return
        prefs.edit().putBoolean("logro_$key", true).apply()
        celebrations += "🏆 Logro: ${logro.emoji} ${logro.title} (+${logro.bonus} puntos)"
        add(logro.bonus)
    }

    // ---- Lo que da puntos ----

    /** Devuelve los puntos que ganó (0 si ya llegó al tope de hoy), para mostrarlos al mandar. */
    fun onSoundSent(): Int {
        val sent = bump("sent")
        val got = add(Rule.SOUND_SENT, "sent", Rule.SOUND_SENT_DAILY)
        unlock("first_sound")
        if (sent >= 50) unlock("sounds_50")
        Seasons.current()?.let { season ->
            if (bump("season_${season.key}_${java.time.LocalDate.now().year}") >= 5) unlock(season.logro)
        }
        Quests.track(Quests.SEND)
        return if (got) Rule.SOUND_SENT else 0
    }

    fun onSoundHeard() {
        val heard = bump("heard")
        add(Rule.SOUND_HEARD, "heard", Rule.SOUND_HEARD_DAILY)
        if (heard >= 25) unlock("heard_25")
    }

    fun onChatSent() {
        val sent = bump("chat")
        add(Rule.CHAT_SENT, "chat", Rule.CHAT_SENT_DAILY)
        if (sent >= 30) unlock("chat_30")
        Quests.track(Quests.CHAT)
    }

    fun onRetoCreated() {
        val made = bump("retos_made")
        add(Rule.RETO_MADE, "reto_made", Rule.RETO_MADE_DAILY)
        if (made >= 3) unlock("reto_made")
        Quests.track(Quests.RETO)
    }

    fun onRetoPlayed() {
        bump("retos_played")
        add(Rule.RETO_PLAYED, "reto_played", Rule.RETO_PLAYED_DAILY)
        Quests.track(Quests.RETO)
    }

    /** [place] empieza en 1. Solo cuenta si jugaron al menos dos. */
    fun onRetoPlace(place: Int, players: Int) {
        if (players < 2) return
        var points = Rule.RETO_PLACE.getOrNull(place - 1) ?: return
        if (place == 1) {
            bump("retos_won")
            // Pico-final: lo que más se recuerda de un juego es el momento de ganar.
            celebrations += "🎉 ¡Ganaste el reto! Fuiste el más rápido"
            unlock("reto_win")
            if (Powers.takeDouble()) {
                points *= 2
                celebrations += "✖️ Poder Doble puntos: ganaste $points puntos"
            }
        }
        add(points, "reto_place", 6)
    }

    fun onLockDone() {
        bump("locks_done")
        add(Rule.LOCK_DONE, "lock", 4)
        unlock("lock_done")
    }

    /** Puntos por cada amigo nuevo, una sola vez por persona. */
    @Synchronized
    fun onNewFriend(code: String) {
        val key = "friend_$code"
        if (prefs.getBoolean(key, false)) return
        prefs.edit().putBoolean(key, true).apply()
        add(Rule.NEW_FRIEND, "friend", 5)
        val count = Store.friends().count { it.state == Friend.FRIEND }
        if (count >= 3) unlock("friends_3")
    }

    @Synchronized
    fun onGroupJoined(groupId: String) {
        val key = "group_$groupId"
        if (prefs.getBoolean(key, false)) return
        prefs.edit().putBoolean(key, true).apply()
        add(Rule.NEW_GROUP, "group", 3)
        unlock("group")
    }

    fun onCustomAdded() {
        unlock("custom")
    }

    /**
     * Terminaste un minijuego: de 1 a 5 puntos según el puntaje, como mucho 6 partidas con puntos
     * al día (hasta 30). Devuelve cuántos ganaste (0 si ya llegaste al tope de hoy).
     */
    @Synchronized
    fun onGamePlayed(score: Int): Int {
        val points = (score / 10).coerceIn(1, 5)
        return if (add(points, "game", 6)) points else 0
    }

    fun onQuestDone() {
        add(Rule.QUEST_DONE)
    }

    fun onChestOpened(points: Int) {
        bump("chests")
        add(points)
    }

    /** Compartiste un sonido en la Sonoteca. */
    fun onSoundShared() {
        val shared = bump("shared")
        add(Rule.SOUND_SHARED, "shared", Rule.SOUND_SHARED_DAILY)
        if (shared >= 3) unlock("dj")
        Quests.track(Quests.SHARE)
    }

    /** A alguien le gustó o usa tu sonido. [users]: cuántos lo usan ya. */
    fun onSoundFan(users: Int) {
        add(Rule.SOUND_FAN, "fan", Rule.SOUND_FAN_DAILY)
        if (users >= 5) unlock("viral")
    }

    fun onFriendStreak(days: Int) {
        if (days >= 7) unlock("best_friends")
    }

    /** Al abrir la app: puntos del día y racha de días seguidos. */
    @Synchronized
    fun onDailyOpen() {
        val day = today()
        val last = prefs.getString("last_day", "")
        if (last == day) return
        // Faltaste un solo día y tienes un protector: la racha sigue.
        val saved = last == daysAgo(2) && stat("streak") > 0 && freezes > 0
        if (saved) {
            bump("freezes", -1)
            celebrations += "🧊 Tu protector salvó tu racha de ${stat("streak")} días"
        }
        val streak = if (last == yesterday() || saved) stat("streak") + 1 else 1
        prefs.edit()
            .putString("last_day", day)
            .putInt("stat_streak", streak)
            .putInt("stat_best_streak", maxOf(streak, stat("best_streak")))
            .apply()
        add(Rule.DAILY)
        if (streak >= 3) unlock("streak_3")
        if (streak >= 7) unlock("streak_7")
    }

    // ---- Ranking ----

    /** Agrega mis puntos a un mensaje que sale. */
    fun attach(payload: JSONObject): JSONObject = payload
        .put("pts", total())
        .put("wk", week())
        .put("wid", weekId())
        .put("lvl", level().number)

    /** Guarda los puntos que trae el mensaje de un amigo o de alguien de un grupo. */
    @Synchronized
    fun updateScore(code: String, name: String, payload: JSONObject) {
        if (!payload.has("pts")) return
        val total = payload.optInt("pts").coerceIn(0, 1_000_000)
        val week = if (payload.optString("wid") == weekId()) payload.optInt("wk").coerceIn(0, total) else 0
        val level = payload.optInt("lvl", 1).coerceIn(1, LEVELS.size)
        val scores = scoresJson()
        val old = scores.optJSONObject(code)
        if (old != null && old.optInt("p") == total && old.optInt("w") == week && old.optString("wid") == weekId()) return
        scores.put(code, JSONObject().put("n", name).put("p", total).put("w", week).put("wid", weekId()).put("l", level))
        prefs.edit().putString("scores", scores.toString()).apply()
        Store.notifyChanged()
    }

    @Synchronized
    fun score(code: String): Score? {
        val o = scoresJson().optJSONObject(code) ?: return null
        val week = if (o.optString("wid") == weekId()) o.optInt("w") else 0
        return Score(code, o.optString("n"), o.optInt("p"), week, o.optInt("l", 1))
    }

    @Synchronized
    fun clearScores() {
        prefs.edit().remove("scores").apply()
    }

    private fun scoresJson(): JSONObject = try {
        JSONObject(prefs.getString("scores", "{}") ?: "{}")
    } catch (_: JSONException) {
        JSONObject()
    }

    /** Ranking de [people] (código y nombre) más yo, por puntos de la semana o totales. */
    fun ranking(people: List<Pair<String, String>>, weekly: Boolean): List<Entry> {
        val me = Store.myCode
        val others = people.filter { it.first != me }.distinctBy { it.first }.map { (code, name) ->
            val score = score(code)
            Entry(code, name, if (weekly) score?.week ?: 0 else score?.total ?: 0, score?.level ?: 1, false)
        }
        val mine = Entry(me, Store.myName, if (weekly) week() else total(), level().number, true)
        return (others + mine).sortedWith(compareByDescending<Entry> { it.points }.thenByDescending { it.isMe })
    }

    /** Cuándo se mandaron los puntos a todos por última vez (ver MainActivity). */
    var lastBroadcast: Long
        @Synchronized get() = prefs.getLong("last_broadcast", 0)
        @Synchronized set(value) {
            prefs.edit().putLong("last_broadcast", value).putInt("broadcast_total", total()).apply()
        }

    /** Hay que avisar los puntos: pasaron 6 horas o cambiaron bastante. */
    fun broadcastDue(): Boolean =
        System.currentTimeMillis() - lastBroadcast > 6 * 3600_000L || total() - prefs.getInt("broadcast_total", 0) >= 20
}
