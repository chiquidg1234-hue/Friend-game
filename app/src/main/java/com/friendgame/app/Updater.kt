package com.friendgame.app

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Actualizaciones sin pasar por el navegador: la app pregunta a GitHub por la última versión,
 * la descarga sola y la instala con el instalador de Android (el mismo que usa Play Store).
 * Así el navegador solo hace falta para la primera instalación.
 */
object Updater {
    private const val REPO = "https://github.com/chiquidg1234-hue/Friend-game"
    private const val APK_ASSET = "FriendGame.apk"
    private const val CHECK_EVERY_MS = 3 * 60 * 60 * 1000L
    private const val MAX_APK_BYTES = 50_000_000L

    enum class State { IDLE, CHECKING, DOWNLOADING, READY, INSTALLING, FAILED }

    class Release(val versionCode: Long, val versionName: String, val apkUrl: String)

    @Volatile
    var state = State.IDLE
        private set

    @Volatile
    var release: Release? = null
        private set

    /** Texto para la pantalla: qué pasó con la última búsqueda o instalación. */
    @Volatile
    var message = ""
        private set

    private var apkFile: File? = null
    private val busy = AtomicBoolean(false)

    fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

    /** Busca (como mucho cada 3 horas, o ya si [force]) y deja descargada la versión nueva. */
    fun check(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (!force && System.currentTimeMillis() - Store.lastUpdateCheck < CHECK_EVERY_MS) return
        if (state == State.READY || state == State.INSTALLING) return
        if (!busy.compareAndSet(false, true)) return
        set(State.CHECKING, "Buscando…")
        Thread({
            try {
                val latest = fetchLatest()
                Store.lastUpdateCheck = System.currentTimeMillis()
                if (latest == null || latest.versionCode <= currentVersionCode(app)) {
                    release = null
                    set(State.IDLE, "Tienes la versión más nueva (${currentVersionName(app)}).")
                } else {
                    release = latest
                    set(State.DOWNLOADING, "Descargando la versión ${latest.versionName}…")
                    apkFile = download(app, latest)
                    set(State.READY, "La versión ${latest.versionName} está lista para instalar.")
                }
            } catch (e: Exception) {
                set(State.FAILED, "No se pudo buscar la actualización: ${Relay.describe(e)}")
            } finally {
                busy.set(false)
            }
        }, "friendgame-update").start()
    }

    /** Lanza el instalador de Android con la versión ya descargada. */
    fun install(activity: Activity) {
        val file = apkFile
        if (state != State.READY || file == null || !file.exists()) {
            check(activity, force = true)
            return
        }
        if (!activity.packageManager.canRequestPackageInstalls()) {
            set(State.READY, "Activa «Permitir de esta fuente» para Friend Game y vuelve a tocar Actualizar.")
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                )
            } catch (_: Exception) {
            }
            return
        }
        val app = activity.applicationContext
        set(State.INSTALLING, "Instalando…")
        Thread({
            try {
                commit(app, file)
            } catch (e: Exception) {
                set(State.FAILED, "No se pudo instalar: ${e.message ?: e.javaClass.simpleName}")
            }
        }, "friendgame-install").start()
    }

    /** Respuesta del instalador (llega a [InstallReceiver]). */
    fun onInstallResult(status: Int, detail: String?) {
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> set(State.IDLE, "Actualizada.")
            PackageInstaller.STATUS_FAILURE_ABORTED -> set(State.READY, "Cancelaste la instalación. Toca Actualizar cuando quieras.")
            else -> set(State.FAILED, "No se pudo instalar${if (detail.isNullOrBlank()) "." else ": $detail"}")
        }
    }

    private fun set(newState: State, text: String) {
        state = newState
        message = text
        Store.notifyChanged()
    }

    /**
     * La página "latest" de GitHub redirige a la etiqueta de la última versión (v1.0.N, donde N es
     * el versionCode). Se usa eso en vez de la API porque la API limita las consultas sin cuenta.
     */
    private fun fetchLatest(): Release? {
        val conn = URL("$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "FriendGame")
            val code = conn.responseCode
            if (code !in 300..399) throw Relay.HttpError(code)
            val tag = conn.getHeaderField("Location")?.substringAfter("/releases/tag/", "")
                ?.takeIf { it.isNotEmpty() } ?: return null
            val versionCode = Regex("(\\d+)$").find(tag)?.value?.toLongOrNull() ?: return null
            return Release(versionCode, tag.removePrefix("v"), "$REPO/releases/download/$tag/$APK_ASSET")
        } finally {
            conn.disconnect()
        }
    }

    private fun download(context: Context, release: Release): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "FriendGame-${release.versionCode}.apk")
        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "FriendGame")
            if (conn.responseCode != 200) throw Relay.HttpError(conn.responseCode)
            var total = 0L
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_APK_BYTES) throw IOException("archivo demasiado grande")
                        output.write(buffer, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        // Antes de ofrecerlo, se comprueba que de verdad es Friend Game y la versión esperada.
        val info = context.packageManager.getPackageArchiveInfo(out.absolutePath, 0)
        val code = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }
        if (info == null || info.packageName != context.packageName || code != release.versionCode) {
            out.delete()
            throw IOException("el archivo descargado no es la versión esperada")
        }
        return out
    }

    private fun commit(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            // Desde la segunda actualización Android puede instalarla sin preguntar.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite(APK_ASSET, 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallReceiver::class.java).setAction(InstallReceiver.ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }
}
