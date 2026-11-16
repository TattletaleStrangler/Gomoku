package com.example.gomoku.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.gomoku.model.GomokuGame
import com.example.gomoku.model.Stone
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the board, stones, move numbers and the last-move highlight, and reports
 * taps as zero-based (x, y) grid coordinates.
 */
class BoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var game: GomokuGame? = null
    var theme: GomokuTheme = Themes.LIGHT
    var showMoveNumbers: Boolean = true
    var onCellTapped: ((Int, Int) -> Unit)? = null

    private val stonePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private var cellSize = 0f
    private var originX = 0f
    private var originY = 0f
    private var stoneRadius = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val side = min(w, h)
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        val g = game ?: return
        computeGeometry()
        canvas.drawColor(theme.background)

        val size = g.size

        // Grid.
        linePaint.color = theme.boardLine
        linePaint.strokeWidth = 1.5f
        val extent = cellSize * (size - 1)
        for (i in 0 until size) {
            val p = originX + i * cellSize
            val q = originY + i * cellSize
            canvas.drawLine(originX, q, originX + extent, q, linePaint)
            canvas.drawLine(p, originY, p, originY + extent, linePaint)
        }

        drawHoshi(canvas, size)

        // Move numbers map (1-based order).
        val numbers = Array(size) { IntArray(size) }
        g.history.forEachIndexed { index, m -> numbers[m.y][m.x] = index + 1 }

        for (y in 0 until size) {
            for (x in 0 until size) {
                val s = g.board[y][x]
                if (s == Stone.EMPTY) continue
                drawStone(canvas, x, y, s, numbers[y][x])
            }
        }

        // Last move highlight.
        g.lastMove?.let { m ->
            val cx = originX + m.x * cellSize
            val cy = originY + m.y * cellSize
            ringPaint.color = theme.lastMoveRing
            ringPaint.strokeWidth = cellSize * 0.08f
            canvas.drawCircle(cx, cy, stoneRadius * 0.68f, ringPaint)
        }
    }

    private fun drawStone(canvas: Canvas, x: Int, y: Int, stone: Stone, number: Int) {
        val cx = originX + x * cellSize
        val cy = originY + y * cellSize

        stonePaint.style = Paint.Style.FILL
        stonePaint.color = if (stone == Stone.BLACK) theme.blackStone else theme.whiteStone
        canvas.drawCircle(cx, cy, stoneRadius, stonePaint)

        if (stone == Stone.WHITE) {
            stonePaint.style = Paint.Style.STROKE
            stonePaint.strokeWidth = cellSize * 0.06f
            stonePaint.color = theme.whiteStoneBorder
            canvas.drawCircle(cx, cy, stoneRadius - stonePaint.strokeWidth / 2f, stonePaint)
        }

        if (showMoveNumbers && number > 0) {
            textPaint.color = if (stone == Stone.BLACK) theme.numberOnBlackStone else theme.numberOnWhiteStone
            textPaint.textSize = stoneRadius * 0.92f
            val baseline = cy - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(number.toString(), cx, baseline, textPaint)
        }
    }

    private fun drawHoshi(canvas: Canvas, size: Int) {
        val points = when (size) {
            15 -> listOf(3 to 3, 3 to 11, 11 to 3, 11 to 11, 7 to 7)
            19 -> listOf(
                3 to 3, 3 to 9, 3 to 15,
                9 to 3, 9 to 9, 9 to 15,
                15 to 3, 15 to 9, 15 to 15
            )
            13 -> listOf(3 to 3, 3 to 9, 9 to 3, 9 to 9, 6 to 6)
            else -> emptyList()
        }
        linePaint.color = theme.boardLine
        linePaint.style = Paint.Style.FILL
        for ((hx, hy) in points) {
            val cx = originX + hx * cellSize
            val cy = originY + hy * cellSize
            canvas.drawCircle(cx, cy, cellSize * 0.08f, linePaint)
        }
    }

    private fun computeGeometry() {
        val g = game ?: return
        val size = g.size
        val side = min(width, height).toFloat()
        cellSize = side / size
        originX = (width - cellSize * (size - 1)) / 2f
        originY = (height - cellSize * (size - 1)) / 2f
        stoneRadius = cellSize * 0.42f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val g = game ?: return true
            computeGeometry()
            val x = ((event.x - originX) / cellSize).roundToInt()
            val y = ((event.y - originY) / cellSize).roundToInt()
            if (x in 0 until g.size && y in 0 until g.size) {
                onCellTapped?.invoke(x, y)
            }
        }
        return true
    }
}
