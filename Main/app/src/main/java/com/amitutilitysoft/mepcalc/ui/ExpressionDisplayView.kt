package com.amitutilitysoft.mepcalc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.hypot

/**
 * Primary expression display with a blinking insertion cursor and tap-to-position support.
 * Preserves AppCompatTextView auto-sizing (20sp..42sp) while allowing arbitrary cursor
 * placement anywhere in the expression.
 */
class ExpressionDisplayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    var cursorIndex: Int = 0
        private set

    var onCursorTappedListener: ((Int) -> Unit)? = null

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E58A7B")
        strokeWidth = 2.2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    private var cursorVisibleState: Boolean = true
    private var isBlinkingActive: Boolean = false
    private var downX: Float = 0f
    private var downY: Float = 0f

    private val blinkRunnable = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || !isBlinkingActive) return
            cursorVisibleState = !cursorVisibleState
            invalidate()
            postDelayed(this, 530L)
        }
    }

    fun setCursorColor(color: Int) {
        if (cursorPaint.color != color) {
            cursorPaint.color = color
            invalidate()
        }
    }

    fun setCursorPosition(index: Int) {
        val len = text?.length ?: 0
        cursorIndex = index.coerceIn(0, len)
        resetBlink()
        post { ensureCursorVisible() }
        invalidate()
    }

    private fun resetBlink() {
        cursorVisibleState = true
        removeCallbacks(blinkRunnable)
        if (isAttachedToWindow && isBlinkingActive) {
            postDelayed(blinkRunnable, 530L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isBlinkingActive = true
        resetBlink()
    }

    override fun onDetachedFromWindow() {
        isBlinkingActive = false
        removeCallbacks(blinkRunnable)
        super.onDetachedFromWindow()
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        val len = text?.length ?: 0
        if (cursorIndex > len) {
            cursorIndex = len
        }
        resetBlink()
        post { ensureCursorVisible() }
    }

    private fun ensureCursorVisible() {
        val l = layout ?: return
        if (width <= 0) return
        val contentW = (width - compoundPaddingLeft - compoundPaddingRight).coerceAtLeast(1)
        val lineW = l.getLineWidth(0)
        if (lineW <= contentW) {
            if (scrollX != 0) scrollTo(0, 0)
            return
        }
        val safeIdx = cursorIndex.coerceIn(0, text?.length ?: 0)
        val cx = l.getPrimaryHorizontal(safeIdx)
        val lineLeft = l.getLineLeft(0)
        val lineRight = l.getLineRight(0)
        val pad = 8f * resources.displayMetrics.density
        var targetScroll = scrollX
        if (cx - pad < targetScroll) {
            targetScroll = (cx - pad).toInt()
        } else if (cx + pad > targetScroll + contentW) {
            targetScroll = (cx + pad - contentW).toInt()
        }
        val minScroll = lineLeft.toInt()
        val maxScroll = (lineRight - contentW).toInt().coerceAtLeast(minScroll)
        val clamped = targetScroll.coerceIn(minScroll, maxScroll)
        if (clamped != scrollX) {
            scrollTo(clamped, 0)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = layout ?: return
        if (!cursorVisibleState || !isShown) return

        val density = resources.displayMetrics.density
        val len = text?.length ?: 0
        val safeIdx = cursorIndex.coerceIn(0, len)
        val line = l.getLineForOffset(safeIdx)
        val xInLayout = l.getPrimaryHorizontal(safeIdx)

        val minX = scrollX + compoundPaddingLeft + 1.2f * density
        val maxX = (scrollX + width - compoundPaddingRight - 1.2f * density).coerceAtLeast(minX)
        val cursorX = (compoundPaddingLeft + xInLayout).coerceIn(minX, maxX)

        val fm = paint.fontMetrics
        val baseline = extendedPaddingTop + l.getLineBaseline(line)
        val top = (baseline + fm.ascent).coerceAtLeast(extendedPaddingTop.toFloat())
        val bottom = (baseline + fm.descent).coerceAtMost((height - extendedPaddingBottom).toFloat())
        if (bottom > top) {
            canvas.drawLine(cursorX, top, cursorX, bottom, cursorPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
                if (hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) <= touchSlop) {
                    val l = layout
                    if (l != null) {
                        val x = (event.x - compoundPaddingLeft + scrollX).coerceIn(0f, l.width.toFloat())
                        val y = (event.y - extendedPaddingTop + scrollY).coerceIn(0f, l.height.toFloat())
                        val line = l.getLineForVertical(y.toInt())
                        val offset = l.getOffsetForHorizontal(line, x)
                        onCursorTappedListener?.invoke(offset)
                        performClick()
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }
}
