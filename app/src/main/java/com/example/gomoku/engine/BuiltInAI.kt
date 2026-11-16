package com.example.gomoku.engine

import com.example.gomoku.model.Move
import com.example.gomoku.model.Stone
import com.example.gomoku.rules.ForbiddenMoves
import kotlin.random.Random

/**
 * Fallback engine: iterative-deepening alpha-beta with a transposition table,
 * killer / history heuristics, PVS, null-move pruning, quiescence search and
 * a richer pattern-based evaluation.
 *
 * Single self-contained class — no external dependencies (TZ §5).
 * Hard time budget per move: **3 s**. The search stops cleanly between
 * iterations regardless of depth.
 */
class BuiltInAI {

    // ---- constants ---------------------------------------------------------

    companion object {
        private const val WIN_SCORE      = 1_000_000
        private const val INF            = 200_000_000
        private const val TT_SIZE        = 1 shl 18          // 262144 entries
        private const val TT_MASK        = TT_SIZE - 1L
        private const val MAX_PLY        = 64
        private const val MAX_CANDIDATES = 24
        private const val NULL_REDUCTION = 3
        private const val MAX_BOARD      = 19

        private val DIRS = arrayOf(
            intArrayOf(1, 0),
            intArrayOf(0, 1),
            intArrayOf(1, 1),
            intArrayOf(1, -1)
        )
    }

    // ---- transposition table -----------------------------------------------

    private enum class NodeType { EXACT, LOWER, UPPER }

    private class TTEntry(
        var key: Long = 0,
        var depth: Int = -1,
        var score: Int = 0,
        var type: NodeType = NodeType.EXACT,
        var bestX: Int = -1,
        var bestY: Int = -1
    )

    private val tt = Array(TT_SIZE) { TTEntry() }

    private fun ttStore(key: Long, depth: Int, score: Int, type: NodeType, bx: Int, by: Int) {
        val i = (key and TT_MASK).toInt()
        tt[i].key = key; tt[i].depth = depth; tt[i].score = score; tt[i].type = type
        tt[i].bestX = bx; tt[i].bestY = by
    }

    private fun ttProbe(key: Long): TTEntry? {
        val e = tt[(key and TT_MASK).toInt()]
        return if (e.key == key) e else null
    }

    // ---- instance state ----------------------------------------------------

    private var size = 15
    private lateinit var board: Array<Array<Stone>>
    private var ai: Stone = Stone.BLACK
    private var opp: Stone = Stone.WHITE
    private var forbidBlack = false
    private var deadlineMs = 0L
    private var stopped = false

    // Zobrist — allocated once for max supported board
    private val zobrist = Array(MAX_BOARD) { Array(MAX_BOARD) { LongArray(3) } }
    private var hashKey = 0L

    // move ordering
    private val history = Array(MAX_PLY) { IntArray(MAX_BOARD * MAX_BOARD) }
    private val killers = Array(MAX_PLY) { IntArray(2) { -1 } }

    // iterative deepening
    private var rootBestX = -1
    private var rootBestY = -1

    // ---- public API --------------------------------------------------------

    init {
        val rng = Random(42)
        for (y in 0 until MAX_BOARD) for (x in 0 until MAX_BOARD) for (c in 0..2)
            zobrist[y][x][c] = rng.nextLong()
    }

    /**
     * @param maxDepth   ignored (ID runs until time runs out); kept for compatibility
     * @param timeLimitMs hard time budget, default **3 s**
     */
    fun nextMove(
        current: Array<Array<Stone>>,
        aiColor: Stone,
        maxDepth: Int = 99,
        timeLimitMs: Long = 3000,
        forbiddenMoves: Boolean = false
    ): Move? {
        size = current.size
        board = Array(size) { current[it].copyOf() }
        ai = aiColor
        opp = aiColor.opponent()
        forbidBlack = forbiddenMoves
        deadlineMs = System.currentTimeMillis() + timeLimitMs
        stopped = false
        rootBestX = -1; rootBestY = -1

        // reset history & killers (only the cells that exist on this board)
        val n = size * size
        for (p in 0 until MAX_PLY) {
            for (i in 0 until n) history[p][i] = 0
            killers[p][0] = -1; killers[p][1] = -1
        }
        clearTT()

        // rebuild Zobrist key
        hashKey = 0L
        for (y in 0 until size) for (x in 0 until size) {
            val c = board[y][x]
            if (c != Stone.EMPTY) hashKey = hashKey xor zobrist[y][x][c.ordinal]
        }

        // root candidate moves
        val rootMoves = genMoves(ai, MAX_CANDIDATES, true)
        if (rootMoves.isEmpty()) return null

        // immediate win?
        for (m in rootMoves) {
            if (isWinMove(m)) return m
        }
        // block opponent's immediate win
        for (m in genMoves(opp, MAX_CANDIDATES, false)) {
            if (isWinMove(m)) return m
        }

        // ID loop
        for (depth in 1..MAX_PLY) {
            searchRoot(depth, rootMoves)
            if (stopped) break
        }

        return if (rootBestX >= 0) Move(rootBestX, rootBestY, ai) else rootMoves.first()
    }

    // ---- iterative deepening root ------------------------------------------

    private fun searchRoot(depth: Int, rootMoves: List<Move>) {
        var alpha = -INF
        val beta = INF
        var bestScore = -INF

        for (m in rootMoves) {
            if (outOfTime()) break

            makeMove(m.x, m.y, ai)
            val score = -pvs(-beta, -alpha, depth - 1, opp, 1, false)
            undoMove(m.x, m.y, ai)

            if (score > bestScore) {
                bestScore = score
                rootBestX = m.x; rootBestY = m.y
            }
            if (score > alpha) alpha = score
        }

        if (!stopped && rootBestX >= 0)
            ttStore(hashKey, depth, bestScore, NodeType.EXACT, rootBestX, rootBestY)
    }

    // ---- PVS search --------------------------------------------------------

    private fun pvs(alpha: Int, beta: Int, depth: Int, color: Stone, ply: Int, isNull: Boolean): Int {
        if (outOfTime()) return evaluateFor(color)

        // leaf — quiescence search
        if (depth <= 0) return qSearch(alpha, beta, color, ply)

        // no moves → draw
        val moves = genMoves(color, MAX_CANDIDATES, false)
        if (moves.isEmpty()) return 0

        // TT probe
        val entry = ttProbe(hashKey)
        if (entry != null && entry.depth >= depth) {
            when (entry.type) {
                NodeType.EXACT -> return entry.score
                NodeType.LOWER  -> if (entry.score >= beta) return entry.score
                NodeType.UPPER  -> if (entry.score <= alpha) return entry.score
            }
        }

        // null-move pruning
        if (!isNull && depth >= NULL_REDUCTION + 1 && !isInCheck(color)) {
            val nullScore = -pvs(-beta, -beta + 1, depth - NULL_REDUCTION - 1, color.opponent(), ply + 1, true)
            if (nullScore >= beta) return beta
        }

        var a = alpha
        var best = -INF
        var bestX = -1
        var bestY = -1
        var first = true

        for (m in moves) {
            if (outOfTime()) return best

            makeMove(m.x, m.y, color)
            var score = 0
            if (first) {
                score = -pvs(-beta, -a, depth - 1, color.opponent(), ply + 1, false)
                first = false
            } else {
                // zero-window search first, then re-search full window if promising
                score = -pvs(-a - 1, -a, depth - 1, color.opponent(), ply + 1, false)
                if (score > a && score < beta) {
                    score = -pvs(-beta, -a, depth - 1, color.opponent(), ply + 1, false)
                }
            }
            undoMove(m.x, m.y, color)

            if (score > best) {
                best = score; bestX = m.x; bestY = m.y
            }
            if (best > a) a = best
            if (a >= beta) {
                // beta cutoff → store killer
                val idx = bestY * size + bestX
                if (bestX >= 0 && killers[ply][0] != idx) {
                    killers[ply][1] = killers[ply][0]
                    killers[ply][0] = idx
                }
                history[ply][idx] += depth * depth
                break
            }
        }

        val type = when {
            best <= alpha -> NodeType.UPPER
            best >= beta  -> NodeType.LOWER
            else          -> NodeType.EXACT
        }
        if (!stopped && bestX >= 0)
            ttStore(hashKey, depth, best, type, bestX, bestY)

        return best
    }

    // ---- quiescence search ------------------------------------------------

    private fun qSearch(alpha: Int, beta: Int, color: Stone, ply: Int): Int {
        if (outOfTime()) return evaluateFor(color)
        if (ply >= MAX_PLY) return evaluateFor(color)

        val standPat = evaluateFor(color)
        if (standPat >= beta) return beta
        var a = if (standPat > alpha) standPat else alpha

        val captures = genQmoves(color)
        for (m in captures) {
            makeMove(m.x, m.y, color)
            val threat = isThreat(m.x, m.y, color)
            val score = if (threat) -qSearch(-beta, -a, color.opponent(), ply + 1) else standPat
            undoMove(m.x, m.y, color)
            if (!threat) continue
            if (score >= beta) return beta
            if (score > a) a = score
        }
        return a
    }

    // ---- evaluation --------------------------------------------------------

    private fun evaluate(): Int {
        var score = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                val c = board[y][x]
                if (c == Stone.EMPTY) continue
                val sign = if (c == ai) 1 else -1
                for (d in DIRS) {
                    val px = x - d[0]; val py = y - d[1]
                    if (px in 0 until size && py in 0 until size && board[py][px] == c) continue

                    val pair = scanRun(x, y, d[0], d[1], c)
                    score += sign * patternScore(pair.first, pair.second)
                }
            }
        }
        return score
    }

    private fun evaluateFor(color: Stone): Int =
        if (color == ai) evaluate() else -evaluate()

    /** Returns (length, openEnds) for the run starting at (sx,sy). */
    private fun scanRun(sx: Int, sy: Int, dx: Int, dy: Int, color: Stone): Pair<Int, Int> {
        var len = 0
        var nx = sx; var ny = sy
        while (nx in 0 until size && ny in 0 until size && board[ny][nx] == color) {
            len++; nx += dx; ny += dy
        }
        val open2 = if (nx in 0 until size && ny in 0 until size && board[ny][nx] == Stone.EMPTY) 1 else 0
        val open1 = if (sx - dx in 0 until size && sy - dy in 0 until size && board[sy - dy][sx - dx] == Stone.EMPTY) 1 else 0
        return len to (open1 + open2)
    }

    private fun patternScore(len: Int, openEnds: Int): Int = when {
        len >= 5 -> WIN_SCORE
        openEnds == 0 -> 0
        len == 4 && openEnds == 2 -> 240_000
        len == 4 && openEnds == 1 -> 30_000
        len == 3 && openEnds == 2 -> 18_000
        len == 3 && openEnds == 1 -> 1_200
        len == 2 && openEnds == 2 -> 700
        len == 2 && openEnds == 1 -> 80
        len == 1 && openEnds == 2 -> 30
        len == 1 && openEnds == 1 -> 4
        else -> 0
    }

    /** True when placing [color] at (x,y) creates a direct threat (4 or open-3). */
    private fun isThreat(x: Int, y: Int, color: Stone): Boolean {
        for (d in DIRS) {
            val line = countLine(x, y, d[0], d[1], color)
            if (line >= 5) return true
            val px = x - d[0]; val py = y - d[1]
            val nx = x + d[0] * line; val ny = y + d[1] * line
            val open1 = px in 0 until size && py in 0 until size && board[py][px] == Stone.EMPTY
            val open2 = nx in 0 until size && ny in 0 until size && board[ny][nx] == Stone.EMPTY
            if (line == 4 && (open1 || open2)) return true
            if (line == 3 && open1 && open2) return true
        }
        return false
    }

    // ---- move generation ---------------------------------------------------

    private fun genMoves(color: Stone, cap: Int, sort: Boolean): List<Move> {
        val n = size * size
        val seen = BooleanArray(n)
        val pts = ArrayList<Pair<Int, Int>>()
        var hasStone = false

        for (y in 0 until size) {
            for (x in 0 until size) {
                if (board[y][x] == Stone.EMPTY) continue
                hasStone = true
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val nx = x + dx; val ny = y + dy
                        if (nx in 0 until size && ny in 0 until size && board[ny][nx] == Stone.EMPTY) {
                            val idx = ny * size + nx
                            if (!seen[idx]) { seen[idx] = true; pts.add(nx to ny) }
                        }
                    }
                }
            }
        }
        if (!hasStone) return listOf(Move(size / 2, size / 2, color))

        var raw: List<Move> = pts.map { (x, y) -> Move(x, y, color) }
        if (color == Stone.BLACK && forbidBlack) raw = raw.filter { isLegal(it) }
        if (!sort) return raw.take(cap)

        // sort by history + quick eval
        val scored = raw.map { m ->
            val idx = m.y * size + m.x
            var sc = history[0][idx] * 2
            makeMove(m.x, m.y, color)
            sc += evalQuick(color)
            undoMove(m.x, m.y, color)
            m to sc
        }.sortedByDescending { it.second }.map { it.first }

        return scored.take(cap)
    }

    private fun genQmoves(color: Stone): List<Move> {
        val n = size * size
        val seen = BooleanArray(n)
        val pts = ArrayList<Move>()
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (board[y][x] == Stone.EMPTY) continue
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx; val ny = y + dy
                        if (nx in 0 until size && ny in 0 until size && board[ny][nx] == Stone.EMPTY) {
                            val idx = ny * size + nx
                            if (!seen[idx]) { seen[idx] = true; pts.add(Move(nx, ny, color)) }
                        }
                    }
                }
            }
        }
        return pts
    }

    /** Lightweight evaluation for move ordering. */
    private fun evalQuick(color: Stone): Int {
        var s = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (board[y][x] != color) continue
                for (d in DIRS) {
                    val px = x - d[0]; val py = y - d[1]
                    if (px in 0 until size && py in 0 until size && board[py][px] == color) continue
                    val pair = scanRun(x, y, d[0], d[1], color)
                    s += patternScore(pair.first, pair.second)
                }
            }
        }
        return s
    }

    // ---- helpers -----------------------------------------------------------

    private fun countLine(x: Int, y: Int, dx: Int, dy: Int, color: Stone): Int {
        var c = 0
        var nx = x; var ny = y
        while (nx in 0 until size && ny in 0 until size && board[ny][nx] == color) { c++; nx += dx; ny += dy }
        return c
    }

    private fun isWinMove(m: Move): Boolean {
        makeMove(m.x, m.y, m.color)
        val win = ForbiddenMoves.hasFive(board, m.x, m.y, m.color)
        undoMove(m.x, m.y, m.color)
        return win
    }

    private fun isLegal(m: Move): Boolean {
        if (m.color != Stone.BLACK || !forbidBlack) return true
        makeMove(m.x, m.y, Stone.BLACK)
        val ok = !ForbiddenMoves.isForbidden(board, m.x, m.y)
        undoMove(m.x, m.y, Stone.BLACK)
        return ok
    }

    private fun isInCheck(color: Stone): Boolean {
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (board[y][x] != Stone.EMPTY) continue
                makeMove(x, y, color.opponent())
                val win = ForbiddenMoves.hasFive(board, x, y, color.opponent())
                undoMove(x, y, color.opponent())
                if (win) return true
            }
        }
        return false
    }

    // ---- board mutation ----------------------------------------------------

    private fun makeMove(x: Int, y: Int, c: Stone) {
        board[y][x] = c
        hashKey = hashKey xor zobrist[y][x][c.ordinal]
    }

    private fun undoMove(x: Int, y: Int, c: Stone) {
        board[y][x] = Stone.EMPTY
        hashKey = hashKey xor zobrist[y][x][c.ordinal]
    }

    private fun outOfTime(): Boolean {
        if (!stopped && System.currentTimeMillis() > deadlineMs) stopped = true
        return stopped
    }

    private fun clearTT() {
        for (i in 0 until TT_SIZE) tt[i].key = 0
    }
}