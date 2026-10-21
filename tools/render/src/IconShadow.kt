package com.friendgame.app

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.widget.ImageView
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import java.io.File

/** Sin aapt no hay recursos compilados: los íconos vectoriales se leen del XML a mano. */
@Implements(ImageView::class)
class IconShadow : org.robolectric.shadows.ShadowView() {
    @RealObject
    lateinit var realImage: ImageView

    @Implementation
    fun setImageResource(resId: Int) {
        val name = R.drawable::class.java.fields.firstOrNull { it.getInt(null) == resId }?.name ?: return
        realImage.setImageDrawable(VectorFile(File(System.getProperty("repo") + "/app/src/main/res/drawable/$name.xml")))
    }
}

class VectorFile(file: File) : Drawable() {
    private val viewport: Float
    private val paths = ArrayList<Pair<Path, Paint>>()

    init {
        val xml = file.readText()
        viewport = Regex("viewportWidth=\"([0-9.]+)\"").find(xml)?.groupValues?.get(1)?.toFloat() ?: 24f
        Regex("<path([^>]*)/>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
            val attrs = m.groupValues[1]
            fun attr(n: String) = Regex("android:$n=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)
            val data = attr("pathData") ?: return@forEach
            val path = android.util.PathParser.createPathFromPathData(data)
            val stroke = attr("strokeColor")
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                if (stroke != null) {
                    style = Paint.Style.STROKE
                    strokeWidth = attr("strokeWidth")?.toFloat() ?: 2f
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                } else style = Paint.Style.FILL
                color = 0xFFFFFFFF.toInt()
            }
            paths += path to paint
        }
    }

    private var filter: ColorFilter? = null

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / viewport, b.height() / viewport)
        paths.forEach { (p, paint) ->
            paint.colorFilter = filter
            canvas.drawPath(p, paint)
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) { filter = colorFilter }
    @Deprecated("") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = 24
    override fun getIntrinsicHeight(): Int = 24
}
