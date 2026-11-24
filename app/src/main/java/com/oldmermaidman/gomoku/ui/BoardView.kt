package com.oldmermaidman.gomoku.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.oldmermaidman.gomoku.model.GomokuGame
import com.oldmermaidman.gomoku.model.Move
import com.oldmermaidman.gomoku.model.Stone
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

    companion object {
        /** The only supported board size: Rapfi plays 15×15. */
        private const val MAX_BOARD = GomokuGame.BOARD_SIZE

        /** Star points of the 15×15 board, stored flat as x,y pairs. */
        private val HOSHI = intArrayOf(3, 3, 3, 11, 11, 3, 11, 11, 7, 7)

        /**
         * Every ghost is the same light blue and semi-transparent. It is deliberately not
         * the colour of the stone that was taken back: on a light theme a white ghost and
         * on a dark one a black ghost would be lost against the board.
         */
        private val GHOST_COLOR = 0xFF4FC3F7.toInt()

        /** Opacity of the ghost; clearly fainter than a real stone, still easy to see. */
        private const val GHOST_ALPHA = 150
    }

    var game: GomokuGame? = null
    var theme: GomokuTheme = Themes.LIGHT
    var showMoveNumbers: Boolean = true
    var onCellTapped: ((Int, Int) -> Unit)? = null

    /** Cells of the winning line; empty unless the game was won. */
    var winningLine: List<Move> = emptyList()

    /**
     * A stone an accepted online undo took back, drawn semi-transparent so its owner
     * can see which move was canceled (FEATURES.md, "the ghost of the undone stone").
     * Null for the opponent — the server does not even send it to them.
     */
    var ghost: Move? = null

    private val stonePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val winPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /**
     * Reused move-number buffer. Allocating this inside [onDraw] would create a
     * garbage object on every frame (lint: DrawAllocation).
     */
    private val numbers = Array(MAX_BOARD) { IntArray(MAX_BOARD) }

    /** Grid cell of the most recent tap; consumed by [performClick]. */
    private var pendingX = -1
    private var pendingY = -1

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
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = 1.5f
        val extent = cellSize * (size - 1)
        for (i in 0 until size) {
            val p = originX + i * cellSize
            val q = originY + i * cellSize
            canvas.drawLine(originX, q, originX + extent, q, linePaint)
            canvas.drawLine(p, originY, p, originY + extent, linePaint)
        }

        drawHoshi(canvas, size)

        // Move numbers map (1-based order), filled into the reused buffer.
        for (i in 0 until size) numbers[i].fill(0, 0, size)
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

        // Ghost of a move an accepted online undo took back. It is drawn only on an
        // empty cell: once the player places a new stone, the server clears the ghost.
        ghost?.let { m ->
            if (m.x in 0 until size && m.y in 0 until size && g.board[m.y][m.x] == Stone.EMPTY) {
                drawGhost(canvas, m)
            }
        }

        // Winning line, drawn over the stones so it reads on every theme.
        if (winningLine.size >= 2) {
            val first = winningLine.first()
            val last = winningLine.last()
            winPaint.color = theme.lastMoveRing
            winPaint.alpha = 150
            winPaint.strokeWidth = cellSize * 0.16f
            canvas.drawLine(
                originX + first.x * cellSize, originY + first.y * cellSize,
                originX + last.x * cellSize, originY + last.y * cellSize,
                winPaint
            )
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

    /**
     * The stone an undone move left behind: the same shape as a real one, but always the
     * same light blue and semi-transparent, so it reads as "not a stone" on every theme.
     */
    private fun drawGhost(canvas: Canvas, move: Move) {
        val cx = originX + move.x * cellSize
        val cy = originY + move.y * cellSize
        stonePaint.style = Paint.Style.FILL
        stonePaint.alpha = GHOST_ALPHA
        stonePaint.color = GHOST_COLOR
        canvas.drawCircle(cx, cy, stoneRadius, stonePaint)
        stonePaint.alpha = 255
    }

    private fun drawHoshi(canvas: Canvas, size: Int) {
        if (size != GomokuGame.BOARD_SIZE) return
        linePaint.color = theme.boardLine
        linePaint.style = Paint.Style.FILL
        var i = 0
        while (i < HOSHI.size) {
            val cx = originX + HOSHI[i] * cellSize
            val cy = originY + HOSHI[i + 1] * cellSize
            canvas.drawCircle(cx, cy, cellSize * 0.08f, linePaint)
            i += 2
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
            pendingX = ((event.x - originX) / cellSize).roundToInt()
            pendingY = ((event.y - originY) / cellSize).roundToInt()
            if (pendingX in 0 until g.size && pendingY in 0 until g.size) {
                // Route the tap through performClick() so assistive services that
                // invoke the click action reach the same code path
                // (lint: ClickableViewAccessibility).
                performClick()
            }
        }
        return true
    }

    /**
     * Performs the tap that [onTouchEvent] registered. Keeping the actual action
     * here — rather than only calling super — is what lets accessibility services
     * activate the board, and it satisfies lint's EmptySuperCall check.
     */
    override fun performClick(): Boolean {
        super.performClick()
        val g = game
        if (g != null && pendingX in 0 until g.size && pendingY in 0 until g.size) {
            onCellTapped?.invoke(pendingX, pendingY)
        }
        return true
    }
}
