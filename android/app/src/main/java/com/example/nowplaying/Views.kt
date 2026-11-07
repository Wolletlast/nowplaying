package com.example.nowplaying

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import kotlin.math.min

/** Виниловая пластинка: канавки, блик и обложка трека в центре. Вращается снаружи через rotation. */
class VinylView(ctx: Context) : View(ctx) {
    var cover: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private var sheen: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        sheen = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.08f, 0.2f, 0.5f, 0.58f, 0.7f, 1f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f

        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = 0xFF0B0B0D.toInt()
        canvas.drawCircle(cx, cy, r, paint)

        // канавки
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = 0x26FFFFFF
        var rr = r * 0.5f
        while (rr < r - 2f) {
            canvas.drawCircle(cx, cy, rr, paint)
            rr += r * 0.05f
        }

        // блик
        paint.style = Paint.Style.FILL
        paint.shader = sheen
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null

        // обложка в центре
        val lr = r * 0.5f
        val bmp = cover
        if (bmp != null && !bmp.isRecycled) {
            canvas.save()
            clip.reset()
            clip.addCircle(cx, cy, lr, Path.Direction.CW)
            canvas.clipPath(clip)
            canvas.drawBitmap(bmp, null, RectF(cx - lr, cy - lr, cx + lr, cy + lr), paint)
            canvas.restore()
        } else {
            paint.color = 0xFF6C4BD6.toInt()
            canvas.drawCircle(cx, cy, lr, paint)
        }

        // отверстие
        paint.color = 0xFF0B0B0D.toInt()
        canvas.drawCircle(cx, cy, r * 0.045f, paint)
    }
}

/** Круглая кнопка с нарисованной иконкой (без картинок и шрифтов). */
class IconButton(ctx: Context) : View(ctx) {
    enum class Kind { PREV, NEXT, PLAY, PAUSE, HEART, HEART_FILLED }

    var kind: Kind = Kind.PLAY
        set(value) {
            field = value
            invalidate()
        }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2AFFFFFF }
    private val ic = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.35f
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        canvas.drawCircle(cx, cy, r, bg)
        val u = r * 0.42f
        ic.style = Paint.Style.FILL
        ic.color = Color.WHITE
        path.reset()

        when (kind) {
            Kind.PLAY -> {
                path.moveTo(cx - u * 0.7f, cy - u)
                path.lineTo(cx - u * 0.7f, cy + u)
                path.lineTo(cx + u, cy)
                path.close()
                canvas.drawPath(path, ic)
            }
            Kind.PAUSE -> {
                canvas.drawRect(cx - u * 0.8f, cy - u, cx - u * 0.2f, cy + u, ic)
                canvas.drawRect(cx + u * 0.2f, cy - u, cx + u * 0.8f, cy + u, ic)
            }
            Kind.NEXT -> {
                path.moveTo(cx - u, cy - u)
                path.lineTo(cx - u, cy + u)
                path.lineTo(cx + u * 0.6f, cy)
                path.close()
                canvas.drawPath(path, ic)
                canvas.drawRect(cx + u * 0.75f, cy - u, cx + u * 1.05f, cy + u, ic)
            }
            Kind.PREV -> {
                path.moveTo(cx + u, cy - u)
                path.lineTo(cx + u, cy + u)
                path.lineTo(cx - u * 0.6f, cy)
                path.close()
                canvas.drawPath(path, ic)
                canvas.drawRect(cx - u * 1.05f, cy - u, cx - u * 0.75f, cy + u, ic)
            }
            Kind.HEART, Kind.HEART_FILLED -> {
                val w = u * 1.25f
                path.moveTo(cx, cy + w * 0.75f)
                path.cubicTo(cx - w * 1.3f, cy - w * 0.05f, cx - w * 0.7f, cy - w * 0.95f, cx, cy - w * 0.3f)
                path.cubicTo(cx + w * 0.7f, cy - w * 0.95f, cx + w * 1.3f, cy - w * 0.05f, cx, cy + w * 0.75f)
                path.close()
                if (kind == Kind.HEART_FILLED) {
                    ic.color = 0xFFFF4D6D.toInt()
                } else {
                    ic.style = Paint.Style.STROKE
                    ic.strokeWidth = r * 0.09f
                }
                canvas.drawPath(path, ic)
            }
        }
    }
}
