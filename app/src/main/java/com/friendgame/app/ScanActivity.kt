package com.friendgame.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.Toast
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Lector de QR con la cámara trasera. Devuelve el texto leído en [EXTRA_TEXT].
 * Usa la cámara clásica de Android y ZXing, así que funciona sin servicios de Google.
 */
@Suppress("DEPRECATION")
class ScanActivity : Activity(), SurfaceHolder.Callback, Camera.PreviewCallback {

    companion object {
        const val EXTRA_TEXT = "text"
        private const val REQ_CAMERA = 20
    }

    private var camera: Camera? = null
    private var surface: SurfaceHolder? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private val decoderThread = HandlerThread("friendgame-qr")
    private lateinit var decoder: Handler
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    @Volatile
    private var busy = false

    @Volatile
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        decoderThread.start()
        decoder = Handler(decoderThread.looper)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        val preview = SurfaceView(this)
        root.addView(preview, FrameLayout.LayoutParams(MATCH, MATCH))
        val guide = android.view.View(this).apply { background = rounded(0x00000000, 24, Palette.YELLOW) }
        root.addView(guide, FrameLayout.LayoutParams(dp(250), dp(250), Gravity.CENTER))
        val bottom = column().apply {
            setPadding(dp(24), dp(16), dp(24), dp(40))
            gravity = Gravity.CENTER_HORIZONTAL
            addView(label("Apunta al código QR del grupo", 18f, bold = true).apply { gravity = Gravity.CENTER }, lp())
            addView(pill("Cancelar", Palette.MUTED) { finish() }, lp(topDp = 14))
        }
        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        setContentView(root)

        preview.holder.addCallback(this)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "Sin permiso de cámara no se puede leer el QR. Puedes pegar el enlace.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        startCamera()
    }

    override fun onPause() {
        stopCamera()
        super.onPause()
    }

    override fun onDestroy() {
        stopCamera()
        decoderThread.quitSafely()
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder
        startCamera()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surface = null
        stopCamera()
    }

    private fun startCamera() {
        val holder = surface ?: return
        if (camera != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            val info = Camera.CameraInfo()
            val id = (0 until Camera.getNumberOfCameras()).firstOrNull {
                Camera.getCameraInfo(it, info)
                info.facing == Camera.CameraInfo.CAMERA_FACING_BACK
            } ?: 0
            Camera.getCameraInfo(id, info)
            val cam = Camera.open(id)
            cam.setDisplayOrientation(info.orientation % 360) // la pantalla está fija en vertical
            val params = cam.parameters
            val size = params.supportedPreviewSizes.minByOrNull {
                Math.abs(it.width * it.height - 1280 * 720)
            }
            if (size != null) params.setPreviewSize(size.width, size.height)
            if (Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE in params.supportedFocusModes) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            }
            cam.parameters = params
            frameWidth = cam.parameters.previewSize.width
            frameHeight = cam.parameters.previewSize.height
            cam.setPreviewDisplay(holder)
            cam.setPreviewCallback(this)
            cam.startPreview()
            camera = cam
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir la cámara.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun stopCamera() {
        val cam = camera ?: return
        camera = null
        try {
            cam.setPreviewCallback(null)
            cam.stopPreview()
        } catch (_: Exception) {
        }
        cam.release()
    }

    override fun onPreviewFrame(data: ByteArray?, cam: Camera?) {
        if (data == null || busy || done || frameWidth == 0) return
        busy = true
        val width = frameWidth
        val height = frameHeight
        decoder.post {
            try {
                val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
                val result = reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)
                done = true
                runOnUiThread {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, result.text))
                    finish()
                }
            } catch (_: ReaderException) {
                // Todavía no se ve un QR: se intenta con el siguiente cuadro.
            } catch (_: Exception) {
            } finally {
                reader.reset()
                busy = false
            }
        }
    }
}
