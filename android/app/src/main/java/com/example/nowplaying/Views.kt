package com.example.nowplaying

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Виниловая пластинка: обложка трека занимает весь диск, поверх канавки и блик.
 * Вращается только диск (свойство angle), блик стоит на месте, как на настоящей пластинке.
 */
class VinylView(ctx: Context) : View(ctx) {

    var cover: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    var angle: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val src = Rect()
    private val dst = RectF()
    private var sheen: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        sheen = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.08f, 0.2f, 0.5f, 0.58f, 0.7f, 1f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        val hairline = max(1f, r / 140f)
        val bmp = cover?.takeIf { !it.isRecycled }

        // ---- диск (вращается) ----
        canvas.save()
        canvas.rotate(angle, cx, cy)
        clip.reset()
        clip.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.clipPath(clip)

        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = 0xFF0B0B0D.toInt()
        canvas.drawCircle(cx, cy, r, paint)

        if (bmp != null) {
            val side = min(bmp.width, bmp.height)
            src.set((bmp.width - side) / 2, (bmp.height - side) / 2, (bmp.width + side) / 2, (bmp.height + side) / 2)
            dst.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawBitmap(bmp, src, dst, paint)
        } else {
            paint.color = 0xFF6C4BD6.toInt()
            canvas.drawCircle(cx, cy, r * 0.36f, paint)
        }

        // канавки
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = hairline
        paint.color = if (bmp != null) 0x33000000 else 0x26FFFFFF
        var rr = r * 0.34f
        while (rr < r - 1f) {
            canvas.drawCircle(cx, cy, rr, paint)
            rr += max(3f, r * 0.03f)
        }
        canvas.restore()

        // ---- неподвижные детали ----
        paint.style = Paint.Style.FILL
        paint.shader = sheen
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = hairline * 2
        paint.color = 0x33FFFFFF
        canvas.drawCircle(cx, cy, r - hairline, paint)

        // шпиндель в центре
        paint.style = Paint.Style.FILL
        paint.color = 0xFF15161A.toInt()
        canvas.drawCircle(cx, cy, r * 0.1f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = hairline * 2
        paint.color = 0x40FFFFFF
        canvas.drawCircle(cx, cy, r * 0.1f, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xFFD9D9DE.toInt()
        canvas.drawCircle(cx, cy, r * 0.04f, paint)
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

    /** Белая кнопка с тёмной иконкой (главная кнопка play/pause). */
    var solid: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Без круглой подложки (сердечко рядом с названием). */
    var plain: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ic = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        isClickable = true
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.35f
    }

    /** Лёгкое сжатие при нажатии, чтобы было видно, что кнопка сработала. */
    override fun dispatchSetPressed(pressed: Boolean) {
        val s = if (pressed) 0.92f else 1f
        scaleX = s
        scaleY = s
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        val iconColor = if (solid) 0xFF14161C.toInt() else Color.WHITE

        if (!plain) {
            bg.style = Paint.Style.FILL
            bg.color = if (solid) Color.WHITE else 0x2EFFFFFF
            canvas.drawCircle(cx, cy, r, bg)
        }

        val u = r * if (plain) 0.5f else 0.4f
        ic.style = Paint.Style.FILL
        ic.color = iconColor
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
                    ic.strokeWidth = max(2f, r * 0.08f)
                }
                canvas.drawPath(path, ic)
            }
        }
    }
}
