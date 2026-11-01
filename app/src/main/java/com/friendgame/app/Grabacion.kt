package com.friendgame.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Grabar la broma»: graba la pantalla del celular (MediaProjection) con el micrófono, mientras
 * la cámara se ve en una burbuja dentro de la app (ver [CameraBubble]), así queda en el mismo
 * video. La cámara se voltea cuando quieras (frontal ↔ trasera), sin cortar la grabación.
 *
 * Todo queda en el celular: el video se guarda en la galería (Películas/Friend Game) y solo sale
 * si la persona lo comparte. La pantalla la graba un servicio en primer plano con aviso fijo y
 * botón Detener; Android además muestra su propio aviso de que se está grabando.
 */
object Grabacion {
    /** Tope de cada grabación: 5 minutos (y 500 MB), para no llenar el celular por olvido. */
    const val MAX_MS = 5 * 60_000
    private const val MAX_BYTES = 500L * 1024 * 1024
    const val FOLDER = "Friend Game"

    enum class State { IDLE, STARTING, RECORDING, SAVING }

    /** Un video terminado: dónde quedó y cuánto dura. [file] solo si quedó en la carpeta de la app. */
    class Clip(val uri: Uri, val file: File?, val durationMs: Long, val inGallery: Boolean)

    interface Listener {
        fun onRecordingChanged()
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()

    @Volatile
    var state = State.IDLE
        private set

    /** Cuándo empezó a grabar (reloj elapsedRealtime). */
    var startedAt = 0L
        private set

    /** Lo último que se grabó y todavía no se mostró. */
    var finished: Clip? = null

    /** Por qué falló lo último (para avisarlo una vez), o null. */
    var problem: String? = null

    val active: Boolean get() = state != State.IDLE

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    private fun changed(newState: State) {
        main.post {
            state = newState
            if (newState == State.RECORDING) startedAt = SystemClock.elapsedRealtime()
            listeners.toList().forEach { it.onRecordingChanged() }
        }
    }

    fun elapsedMs(): Long = if (state == State.RECORDING) SystemClock.elapsedRealtime() - startedAt else 0L

    /** «0:42», «3:05». */
    fun clock(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    /** El pedido de Android para dejar grabar la pantalla (se abre con startActivityForResult). */
    fun consentIntent(context: Context): Intent? =
        context.getSystemService(MediaProjectionManager::class.java)?.createScreenCaptureIntent()

    /**
     * Arranca la grabación con el permiso que dio la persona. [width]/[height]/[dpi] son los de la
     * pantalla de la actividad (un servicio no tiene ventana para medirla).
     */
    fun start(activity: Activity, resultCode: Int, data: Intent, width: Int, height: Int, dpi: Int, mic: Boolean) {
        if (active) return
        problem = null
        state = State.STARTING
        val intent = Intent(activity, GrabacionService::class.java)
            .setAction(GrabacionService.ACTION_START)
            .putExtra(GrabacionService.EXTRA_CODE, resultCode)
            .putExtra(GrabacionService.EXTRA_DATA, data)
            .putExtra(GrabacionService.EXTRA_W, width)
            .putExtra(GrabacionService.EXTRA_H, height)
            .putExtra(GrabacionService.EXTRA_DPI, dpi)
            .putExtra(GrabacionService.EXTRA_MIC, mic)
        try {
            activity.startForegroundService(intent)
        } catch (_: Exception) {
            failed("Android no dejó empezar la grabación. Prueba de nuevo.")
        }
        listeners.toList().forEach { it.onRecordingChanged() }
    }

    fun stop(context: Context) {
        if (state != State.RECORDING && state != State.STARTING) return
        try {
            context.startService(Intent(context, GrabacionService::class.java).setAction(GrabacionService.ACTION_STOP))
        } catch (_: Exception) {
        }
    }

    internal fun recording() = changed(State.RECORDING)

    internal fun saving() = changed(State.SAVING)

    internal fun done(clip: Clip?) {
        main.post { finished = clip }
        changed(State.IDLE)
    }

    internal fun failed(text: String) {
        main.post { problem = text }
        changed(State.IDLE)
    }

    // ------------------------------------------------------------------ Tamaño del video

    /**
     * Tamaño del video: como la pantalla pero con el lado corto en 720 (o menos si el codificador
     * del celular no puede), en múltiplos de 16 para que ningún codificador deje bordes verdes.
     */
    fun videoSize(width: Int, height: Int): Pair<Int, Int> {
        val caps = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals("video/avc", true) } }
                ?.getCapabilitiesForType("video/avc")?.videoCapabilities
        } catch (_: Exception) {
            null
        }
        val shortSide = minOf(width, height).coerceAtLeast(1)
        for (target in intArrayOf(720, 640, 540, 480, 360)) {
            val scale = minOf(1f, target.toFloat() / shortSide)
            val w = align16(width * scale)
            val h = align16(height * scale)
            if (caps == null || caps.isSizeSupported(w, h)) return w to h
        }
        return align16(width * 360f / shortSide) to align16(height * 360f / shortSide)
    }

    private fun align16(value: Float): Int = ((value / 16f).toInt() * 16).coerceAtLeast(16)

    fun bitrate(w: Int, h: Int): Int = (w * h * 30 * 0.12f).toInt().coerceIn(2_000_000, 8_000_000)

    // ------------------------------------------------------------------ Archivos

    /** Carpeta de trabajo: el video se graba aquí y después pasa a la galería. */
    internal fun workDir(context: Context): File = File(context.filesDir, "clips").apply { mkdirs() }

    internal fun newWorkFile(context: Context): File {
        workDir(context).listFiles()?.forEach { if (it.name.startsWith("rec-")) it.delete() }
        return File(workDir(context), "rec-${System.currentTimeMillis()}.mp4")
    }

    private fun displayName(): String =
        "broma-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".mp4"

    /**
     * Pasa el video a la galería (Películas/Friend Game). En Android 8 y 9 hace falta el permiso de
     * almacenamiento; sin él, el video queda en la app y se puede compartir igual.
     */
    internal fun publish(context: Context, work: File, durationMs: Long): Clip? {
        val name = displayName()
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + FOLDER)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = try {
                resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            } catch (_: Exception) {
                null
            }
            if (uri != null) {
                try {
                    resolver.openOutputStream(uri)?.use { out -> work.inputStream().use { it.copyTo(out) } }
                        ?: throw IllegalStateException("sin salida")
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                    work.delete()
                    return Clip(uri, null, durationMs, inGallery = true)
                } catch (_: Exception) {
                    try {
                        resolver.delete(uri, null, null)
                    } catch (_: Exception) {
                    }
                }
            }
        } else if (context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            try {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER).apply { mkdirs() }
                val target = File(dir, name)
                work.copyTo(target, overwrite = true)
                work.delete()
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("video/mp4"), null)
                return Clip(ClipProvider.uriFor(context, target), target, durationMs, inGallery = true)
            } catch (_: Exception) {
            }
        }
        // Sin galería: queda en la app con un nombre fijo (se reemplaza con la próxima).
        val kept = File(workDir(context), name)
        workDir(context).listFiles()?.forEach { if (it.name.startsWith("broma-")) it.delete() }
        return if (work.renameTo(kept)) Clip(ClipProvider.uriFor(context, kept), kept, durationMs, inGallery = false) else null
    }

    /** Borra el video (de la galería o de la app). */
    fun delete(context: Context, clip: Clip): Boolean = try {
        if (clip.file != null) {
            val ok = clip.file.delete()
            if (ok && clip.inGallery) MediaScannerConnection.scanFile(context, arrayOf(clip.file.absolutePath), null, null)
            ok
        } else {
            context.contentResolver.delete(clip.uri, null, null) > 0
        }
    } catch (_: Exception) {
        false
    }

    /** Para mandarlo por WhatsApp, Instagram, TikTok… (elige la persona). */
    fun shareIntent(clip: Clip): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, clip.uri)
            .putExtra(Intent.EXTRA_TEXT, "Mi broma con Friend Game 😂")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Compartir la broma").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    internal const val MAX_FILE_BYTES = MAX_BYTES
}

/**
 * Servicio en primer plano que graba la pantalla. Android exige que la grabación de pantalla
 * (y el micrófono mientras la app no está a la vista) vayan en un servicio con aviso fijo.
 */
class GrabacionService : Service() {
    companion object {
        const val ACTION_START = "com.friendgame.app.REC_START"
        const val ACTION_STOP = "com.friendgame.app.REC_STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_W = "w"
        const val EXTRA_H = "h"
        const val EXTRA_DPI = "dpi"
        const val EXTRA_MIC = "mic"
        private const val ID = 7
        private const val CHANNEL = "recording"

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                android.app.NotificationChannel(CHANNEL, "Grabando la broma", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Aviso fijo mientras grabas la pantalla, con botón para detener."
                    setShowBadge(false)
                },
            )
        }
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var work: File? = null
    private var startedAt = 0L
    private var stopping = false
    private val handler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        // La persona tocó «Dejar de compartir» en el aviso de Android: se guarda lo grabado.
        override fun onStop() {
            handler.post { finish(save = true) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent)
            ACTION_STOP -> finish(save = true)
            else -> if (recorder == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        createChannel(this)
        val open = PendingIntent.getActivity(
            this, 70, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 71, Intent(this, GrabacionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle("🎬 Grabando tu broma")
            .setContentText("Se graba la pantalla. Toca Detener para guardar el video.")
            .setContentIntent(open)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "⏹ Detener y guardar", stop).build())
            .build()
    }

    private fun begin(intent: Intent) {
        if (recorder != null) return
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        val micWanted = intent.getBooleanExtra(EXTRA_MIC, true)
        val mic = micWanted && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        // Primero el aviso fijo (Android 14 lo exige antes de pedir la proyección).
        val withMic = try {
            if (Build.VERSION.SDK_INT >= 30 && mic) {
                startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(ID, notification())
            }
            mic
        } catch (_: Exception) {
            // Sin permiso para el micrófono en segundo plano: se graba sin sonido.
            try {
                if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                else startForeground(ID, notification())
                false
            } catch (_: Exception) {
                fail("Android no dejó grabar la pantalla.")
                return
            }
        }
        if (data == null) {
            fail("Falta el permiso para grabar la pantalla.")
            return
        }
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val mp = manager.getMediaProjection(code, data) ?: throw IllegalStateException("sin proyección")
            projection = mp
            mp.registerCallback(projectionCallback, handler)

            val (w, h) = Grabacion.videoSize(intent.getIntExtra(EXTRA_W, 720), intent.getIntExtra(EXTRA_H, 1280))
            val file = Grabacion.newWorkFile(this)
            work = file
            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            if (withMic) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(file.absolutePath)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(w, h)
            rec.setVideoFrameRate(30)
            rec.setVideoEncodingBitRate(Grabacion.bitrate(w, h))
            if (withMic) {
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioChannels(1)
                rec.setAudioSamplingRate(44_100)
                rec.setAudioEncodingBitRate(128_000)
            }
            rec.setMaxDuration(Grabacion.MAX_MS)
            rec.setMaxFileSize(Grabacion.MAX_FILE_BYTES)
            rec.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                ) handler.post { finish(save = true) }
            }
            rec.setOnErrorListener { _, _, _ -> handler.post { finish(save = true) } }
            rec.prepare()
            recorder = rec
            display = mp.createVirtualDisplay(
                "friendgame-broma", w, h, intent.getIntExtra(EXTRA_DPI, 320),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rec.surface, null, handler,
            )
            rec.start()
            startedAt = SystemClock.elapsedRealtime()
            Grabacion.recording()
        } catch (e: Exception) {
            fail("No se pudo empezar a grabar en este celular.")
        }
    }

    private fun fail(text: String) {
        release()
        work?.delete()
        Grabacion.failed(text)
        stopSelfNow()
    }

    private fun finish(save: Boolean) {
        if (stopping) return
        val rec = recorder
        if (rec == null) {
            if (Grabacion.state != Grabacion.State.IDLE) Grabacion.failed("Se canceló la grabación.")
            stopSelfNow()
            return
        }
        stopping = true
        val duration = SystemClock.elapsedRealtime() - startedAt
        var ok = save
        try {
            rec.stop()
        } catch (_: Exception) {
            // Muy corto: MediaRecorder no alcanzó a escribir nada.
            ok = false
        }
        release()
        val file = work
        if (!ok || file == null || !file.exists() || file.length() < 1024) {
            file?.delete()
            Grabacion.failed(if (duration < 1500) "Fue muy corto: graba al menos un par de segundos." else "No se pudo guardar el video.")
            stopSelfNow()
            return
        }
        Grabacion.saving()
        val app = applicationContext
        Thread {
            val clip = try {
                Grabacion.publish(app, file, duration)
            } catch (_: Exception) {
                null
            }
            if (clip == null) {
                file.delete()
                Grabacion.failed("No se pudo guardar el video.")
            } else {
                Grabacion.done(clip)
            }
            handler.post { stopSelfNow() }
        }.start()
    }

    private fun release() {
        try {
            recorder?.reset()
        } catch (_: Exception) {
        }
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        try {
            display?.release()
        } catch (_: Exception) {
        }
        display = null
        val mp = projection
        projection = null
        if (mp != null) {
            try {
                mp.unregisterCallback(projectionCallback)
                mp.stop()
            } catch (_: Exception) {
            }
        }
    }

    private fun stopSelfNow() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        if (recorder != null) {
            // Android cerró el servicio a la fuerza: se intenta guardar lo grabado.
            stopping = false
            finish(save = true)
        }
        release()
        super.onDestroy()
    }
}

/**
 * Entrega los videos que quedaron dentro de la app (Android 8 y 9 sin permiso de almacenamiento)
 * a la app que elijas para compartir. Solo lectura, solo videos de broma, y solo para quien
 * reciba el permiso de leer ese video en el momento de compartir.
 */
class ClipProvider : ContentProvider() {
    companion object {
        fun authority(context: Context) = context.packageName + ".clips"

        fun uriFor(context: Context, file: File): Uri = Uri.Builder()
            .scheme("content")
            .authority(authority(context))
            .appendPath(if (file.parentFile == Grabacion.workDir(context)) "app" else "movies")
            .appendPath(file.name)
            .build()
    }

    override fun onCreate(): Boolean = true

    private fun fileFor(uri: Uri): File? {
        val context = context ?: return null
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        val name = segments[1]
        // Solo nombres de video simples: nada de «..» ni subcarpetas.
        if (!name.matches(Regex("broma-[0-9-]+\\.mp4"))) return null
        val dir = when (segments[0]) {
            "app" -> Grabacion.workDir(context)
            "movies" -> @Suppress("DEPRECATION") File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), Grabacion.FOLDER)
            else -> return null
        }
        return File(dir, name).takeIf { it.isFile }
    }

    override fun getType(uri: Uri): String = "video/mp4"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("solo lectura")
        val file = fileFor(uri) ?: throw java.io.FileNotFoundException()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val file = fileFor(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        return MatrixCursor(cols, 1).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
