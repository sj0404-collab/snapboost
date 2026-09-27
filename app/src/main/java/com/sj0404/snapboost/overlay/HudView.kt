package com.sj0404.snapboost.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.sj0404.snapboost.overlay.HudFormatter.HudLine
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Лёгкий оверлей.
 *
 * Требование к себе — быть почти бесплатным. Поэтому здесь нет ни ViewGroup,
 * ни вложенных layout'ов, ни TextView: это один View, который рисует готовые
 * строки, переданные из фонового потока. Никаких аллокаций в onDraw.
 *
 * Ширина и высота считаются по реальному тексту, а не задаются «с запасом»,
 * иначе невидимый слой перехватывал бы касания на экране игры.
 */
class HudView(context: Context) : View(context) {

    private var lines: List<HudLine> = emptyList()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics)
    }

    private val rect = RectF()
    private val density = resources.displayMetrics.density
    private val padX = 8f * density
    private val padY = 6f * density
    private val lineHeight = textPaint.textSize * 1.28f
    private val corner = 6f * density

    private var bgAlpha = 0.72f
    private var needW = 0
    private var needH = 0

    var onTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    var onDrag: ((dx: Int, dy: Int) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!dragging) onTap?.invoke()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!dragging) onLongPress?.invoke()
            }
        }
    )

    init {
        setWillNotDraw(false)
        isClickable = true
    }

    /**
     * Приём строк из фонового потока. Пересчёт размера и перерисовка
     * планируются, а не выполняются здесь.
     */
    fun submit(newLines: List<HudLine>, alpha: Float) {
        val sizeChanged = newLines != lines
        lines = newLines
        bgAlpha = alpha
        if (sizeChanged) recalcSize()
        postInvalidateOnAnimation()
    }

    private fun recalcSize() {
        var w = 0f
        for (l in lines) {
            w = max(w, textPaint.measureText(l.text))
        }
        val newW = (w + padX * 2).roundToInt()
        val newH = (lineHeight * lines.size + padY * 2).roundToInt()
        if (newW != needW || newH != needH) {
            needW = newW
            needH = newH
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (needW == 0 || needH == 0) {
            recalcSize()
        }
        setMeasuredDimension(needW.coerceAtLeast(1), needH.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        if (lines.isEmpty()) return
        bgPaint.alpha = (255 * bgAlpha).toInt().coerceIn(0, 255)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, corner, corner, bgPaint)

        strokePaint.color = Color.argb(90, 255, 255, 255)
        rect.inset(0.5f * density, 0.5f * density)
        canvas.drawRoundRect(rect, corner, corner, strokePaint)

        var y = padY + textPaint.textSize
        for (l in lines) {
            textPaint.color = l.color
            canvas.drawText(l.text, padX, y, textPaint)
            y += lineHeight
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                    dragging = true
                }
                if (dragging) onDrag?.invoke(dx.toInt(), dy.toInt())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}
