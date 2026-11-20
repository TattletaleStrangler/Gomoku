package com.oldmermaidman.gomoku.engine

import com.oldmermaidman.gomoku.model.Move
import com.oldmermaidman.gomoku.model.Stone
import com.oldmermaidman.gomoku.rules.ForbiddenMoves
import kotlin.random.Random

/**
 * Engine behind the "normal" difficulty, and the fallback for the "hard" level
 * when the native Rapfi engine is unavailable. The board is always 15×15.
 *
 * Structure:
 *  - **Forced-move ladder** — before searching, a handful of provably forced
 *    situations are resolved directly: win, block a five, make an open four,
 *    block an opponent's open four (which is also the "block a three/four" case).
 *  - **Iterative deepening PVS** over a transposition table, with killer/history
 *    ordering and Zobrist hashing.
 *  - **Forced-reply quiescence** — at the horizon only forcing moves are
 *    explored; if the opponent threatens five, only blocking moves are legal.
 *  - **Window evaluation** — every 5-cell window on the board is scored, so
 *    broken shapes (`XX.X`, `X.XX`, `X.X.X`) are seen as well as solid runs.
 *
 * Deliberately absent: null-move pruning. In gomoku a "pass" is never safe —
 * threats dominate — so it produced bad cutoffs; ordering plus the forced-reply
 * quiescence recovers the speed more safely.
 *
 * Single self-contained class, no external dependencies (TZ §5).
 */
class BuiltInAI {

    // ---- constants ---------------------------------------------------------

    companion object {
        private const val WIN_SCORE  = 10_000_000
        private const val INF        = 1_000_000_000
        private const val TT_SIZE    = 1 shl 18                 // 262144 entries
        private const val TT_MASK    = TT_SIZE - 1L
        private const val MAX_PLY    = 64
        /** The only supported board size (Rapfi plays 15×15). */
        private const val MAX_BOARD  = 15

        /** Candidates searched at the root / inside the tree. */
        private const val ROOT_CANDIDATES = 24
        private const val NODE_CANDIDATES = 12

        /**
         * A candidate scoring at least this much makes or blocks a four, so it is
         * never dropped by the candidate cap.
         */
        private const val CRITICAL_SCORE = 50_000

        private val DIRS = arrayOf(
            intArrayOf(1, 0),
            intArrayOf(0, 1),
            intArrayOf(1, 1),
            intArrayOf(1, -1)
        )

        /** Score of a clean 5-window holding this many of one colour. */
        private val WINDOW = intArrayOf(0, 10, 100, 1_000, 100_000, WIN_SCORE)
    }

    // ---- diagnostics (used by tests) ---------------------------------------

    /** Depth of the last fully completed iteration. */
    var lastDepth: Int = 0
        private set

    /** Nodes visited during the last search. */
    var lastNodes: Long = 0
        private set

    // ---- transposition table -----------------------------------------------

    private enum class NodeType { EXACT, LOWER, UPPER }

    private class TTEntry(
        var key: Long = 0,
        var depth: Int = -1,
        var score: Int = 0,
        var type: NodeType = NodeType.EXACT,
        var bestIdx: Int = -1
    )

    private val tt = Array(TT_SIZE) { TTEntry() }

    // ---- instance state ----------------------------------------------------

    private var size = 15
    private lateinit var board: Array<Array<Stone>>
    private var ai: Stone = Stone.BLACK
    private var opp: Stone = Stone.WHITE
    private var forbidBlack = false
    private var deadlineMs = 0L
    private var stopped = false

    private val zobrist = Array(MAX_BOARD) { Array(MAX_BOARD) { LongArray(3) } }
    private var hashKey = 0L

    // move ordering
    private val history = Array(MAX_PLY) { IntArray(MAX_BOARD * MAX_BOARD) }
    private val killers = Array(MAX_PLY) { IntArray(2) { -1 } }

    // iterative deepening
    private var rootBestX = -1
    private var rootBestY = -1

    // reusable buffers (no allocation inside the search)
    private val stamp = IntArray(MAX_BOARD * MAX_BOARD)
    private var stampVal = 0
    private val candList = IntArray(MAX_BOARD * MAX_BOARD)
    private var candCount = 0

    private val moveBuf = Array(MAX_PLY) { IntArray(MAX_BOARD * MAX_BOARD) }
    private val scoreBuf = Array(MAX_PLY) { IntArray(MAX_BOARD * MAX_BOARD) }

    private val lineBuf = IntArray(MAX_BOARD)      // 0 = empty, 1 = ai, 2 = opp
    private val winBuf = CharArray(9)

    // ---- public API --------------------------------------------------------

    init {
        val rng = Random(42)
        for (y in 0 until MAX_BOARD) for (x in 0 until MAX_BOARD) for (c in 0..2)
            zobrist[y][x][c] = rng.nextLong()
    }

    /**
     * @param maxDepth     upper bound for iterative deepening (search usually stops
     *                     on the time budget first)
     * @param timeLimitMs  hard time budget per move
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
        lastDepth = 0
        lastNodes = 0

        val n = size * size
        for (p in 0 until MAX_PLY) {
            for (i in 0 until n) history[p][i] = 0
            killers[p][0] = -1; killers[p][1] = -1
        }
        clearTT()

        hashKey = 0L
        for (y in 0 until size) for (x in 0 until size) {
            val c = board[y][x]
            if (c != Stone.EMPTY) hashKey = hashKey xor zobrist[y][x][c.ordinal]
        }

        // 1. Resolve forced situations without searching.
        forcedMove()?.let { return it }

        // 2. Iterative deepening.
        // genMoves() writes packed cell indices into moveBuf[0] and returns the count.
        val rootCount = genMoves(ai, ROOT_CANDIDATES, 0)
        if (rootCount == 0) return null
        val rootBuf = moveBuf[0]
        val rootMoves = ArrayList<Move>(rootCount)
        for (i in 0 until rootCount) {
            val idx = rootBuf[i]
            rootMoves.add(Move(idx % size, idx / size, ai))
        }

        val depthLimit = if (maxDepth < 1) 1 else minOf(maxDepth, MAX_PLY)
        for (depth in 1..depthLimit) {
            val completed = searchRoot(depth, rootMoves)
            if (!completed) break
            lastDepth = depth
            if (outOfTime()) break
        }

        return if (rootBestX >= 0) Move(rootBestX, rootBestY, ai) else rootMoves.first()
    }

    // ---- forced-move ladder ------------------------------------------------

    /**
     * Resolves the situations where the right move is not a matter of judgement.
     * Returns null when the position needs a real search.
     *
     *   1. I can make five                 → play it
     *   2. the opponent can make five      → block it
     *   3. I can make an open four         → play it (wins next move)
     *   4. the opponent can make an open four → block it
     *
     * Step 4 is also the answer to "the opponent has three in a row": the cell
     * where they would create an open four is exactly the cell that must be taken.
     */
    private fun forcedMove(): Move? {
        buildCandidates()
        if (candCount == 0) return null

        // 1. my five
        for (i in 0 until candCount) {
            val idx = candList[i]; val x = idx % size; val y = idx / size
            if (!legalIfBlack(x, y)) continue
            if (maxRunIfPlaced(x, y, ai) >= 5) return Move(x, y, ai)
        }

        // 2. block the opponent's five
        for (i in 0 until candCount) {
            val idx = candList[i]; val x = idx % size; val y = idx / size
            if (maxRunIfPlaced(x, y, opp) >= 5 && legalIfBlack(x, y)) return Move(x, y, ai)
        }

        // 3. my open four (two winning points, therefore unstoppable)
        for (i in 0 until candCount) {
            val idx = candList[i]; val x = idx % size; val y = idx / size
            if (!legalIfBlack(x, y)) continue
            if (maxRunIfPlaced(x, y, ai) != 4) continue
            makeMove(x, y, ai)
            val winPoints = countWinningPoints(x, y, ai)
            undoMove(x, y, ai)
            if (winPoints >= 2) return Move(x, y, ai)
        }

        // 4. block the opponent's open four
        for (i in 0 until candCount) {
            val idx = candList[i]; val x = idx % size; val y = idx / size
            if (!legalIfBlack(x, y)) continue
            if (maxRunIfPlaced(x, y, opp) != 4) continue
            makeMove(x, y, opp)
            val winPoints = countWinningPoints(x, y, opp)
            undoMove(x, y, opp)
            if (winPoints >= 2) return Move(x, y, ai)
        }

        return null
    }

    // ---- root --------------------------------------------------------------

    /** @return true when the iteration ran to completion. */
    private fun searchRoot(depth: Int, moves: MutableList<Move>): Boolean {
        var alpha = -INF
        val beta = INF
        var bestScore = -INF
        var bestX = -1
        var bestY = -1

        for (m in moves) {
            if (outOfTime()) return false
            makeMove(m.x, m.y, ai)
            val score = -pvs(-beta, -alpha, depth - 1, opp, 1)
            undoMove(m.x, m.y, ai)

            if (score > bestScore) {
                bestScore = score; bestX = m.x; bestY = m.y
            }
            if (score > alpha) alpha = score
        }

        if (bestX < 0) return false

        rootBestX = bestX
        rootBestY = bestY
        ttStore(hashKey, depth, bestScore, NodeType.EXACT, bestY * size + bestX)

        // Search the previous best move first on the next iteration.
        val at = moves.indexOfFirst { it.x == bestX && it.y == bestY }
        if (at > 0) moves.add(0, moves.removeAt(at))
        return true
    }

    // ---- principal variation search ---------------------------------------

    private fun pvs(alpha: Int, beta: Int, depth: Int, color: Stone, ply: Int): Int {
        lastNodes++
        if (outOfTime()) return evaluateFor(color)
        if (depth <= 0) return qSearch(alpha, beta, color, ply)
        if (ply >= MAX_PLY) return evaluateFor(color)

        val entry = ttProbe(hashKey)
        if (entry != null && entry.depth >= depth) {
            when (entry.type) {
                NodeType.EXACT -> return entry.score
                NodeType.LOWER  -> if (entry.score >= beta) return entry.score
                NodeType.UPPER  -> if (entry.score <= alpha) return entry.score
            }
        }

        val n = genMoves(color, NODE_CANDIDATES, ply)
        if (n == 0) return 0

        val buf = moveBuf[ply]
        var a = alpha
        var best = -INF
        var bestIdx = -1
        var first = true

        for (i in 0 until n) {
            if (outOfTime()) return best

            val idx = buf[i]
            val x = idx % size; val y = idx / size
            makeMove(x, y, color)

            var score: Int
            if (first) {
                score = -pvs(-beta, -a, depth - 1, color.opponent(), ply + 1)
                first = false
            } else {
                score = -pvs(-a - 1, -a, depth - 1, color.opponent(), ply + 1)
                if (score > a && score < beta) {
                    score = -pvs(-beta, -a, depth - 1, color.opponent(), ply + 1)
                }
            }
            undoMove(x, y, color)

            if (score > best) {
                best = score; bestIdx = idx
            }
            if (best > a) a = best
            if (a >= beta) {
                if (bestIdx >= 0) {
                    if (killers[ply][0] != bestIdx) {
                        killers[ply][1] = killers[ply][0]
                        killers[ply][0] = bestIdx
                    }
                    history[ply][bestIdx] += depth * depth
                }
                break
            }
        }

        val type = when {
            best <= alpha -> NodeType.UPPER
            best >= beta  -> NodeType.LOWER
            else          -> NodeType.EXACT
        }
        if (!stopped && bestIdx >= 0) ttStore(hashKey, depth, best, type, bestIdx)
        return best
    }

    // ---- forced-reply quiescence ------------------------------------------

    /**
     * At the horizon only forcing moves are examined. If the opponent can make
     * five, their winning cells are the *only* moves considered — the old version
     * looked exclusively at its own threats, which is why it could not see that a
     * block was required.
     */
    private fun qSearch(alpha: Int, beta: Int, color: Stone, ply: Int): Int {
        lastNodes++
        if (outOfTime()) return evaluateFor(color)
        if (ply >= MAX_PLY) return evaluateFor(color)

        buildCandidates()

        // Must-block: the opponent threatens five.
        var forced = 0
        val forcedBuf = moveBuf[ply]
        for (i in 0 until candCount) {
            val idx = candList[i]
            val x = idx % size; val y = idx / size
            if (maxRunIfPlaced(x, y, color.opponent()) >= 5) forcedBuf[forced++] = idx
        }
        if (forced > 0) {
            var a = alpha
            var best = -INF
            for (i in 0 until forced) {
                val idx = forcedBuf[i]
                val x = idx % size; val y = idx / size
                makeMove(x, y, color)
                val score = if (maxRunIfPlaced(x, y, color) >= 5) {
                    WIN_SCORE - ply
                } else {
                    -qSearch(-beta, -a, color.opponent(), ply + 1)
                }
                undoMove(x, y, color)
                if (score > best) best = score
                if (best > a) a = best
                if (a >= beta) return a
            }
            return best
        }

        val standPat = evaluateFor(color)
        if (standPat >= beta) return beta
        var a = if (standPat > alpha) standPat else alpha

        // Otherwise: my own forcing moves only (a four or better).
        for (i in 0 until candCount) {
            val idx = candList[i]
            val x = idx % size; val y = idx / size
            if (maxRunIfPlaced(x, y, color) < 4) continue
            if (color == Stone.BLACK && forbidBlack && !legalIfBlack(x, y)) continue
            makeMove(x, y, color)
            val score = if (maxRunIfPlaced(x, y, color) >= 5) {
                WIN_SCORE - ply
            } else {
                -qSearch(-beta, -a, color.opponent(), ply + 1)
            }
            undoMove(x, y, color)
            if (score > a) a = score
            if (a >= beta) return a
        }
        return a
    }

    // ---- evaluation --------------------------------------------------------

    /**
     * Sliding 5-window evaluation. Every window containing stones of one colour
     * only is scored by how many stones it holds, so a broken shape like `XX.X`
     * (two windows with four stones) scores far above a live two, unlike a
     * consecutive-run scan which saw only "two in a row".
     */
    private fun evaluate(): Int {
        var score = 0
        for (d in DIRS) {
            val dx = d[0]; val dy = d[1]
            for (y in 0 until size) {
                for (x in 0 until size) {
                    // Only walk lines from their first cell.
                    val px = x - dx; val py = y - dy
                    if (px in 0 until size && py in 0 until size) continue

                    var len = 0
                    var cx = x; var cy = y
                    while (cx in 0 until size && cy in 0 until size) {
                        val c = board[cy][cx]
                        lineBuf[len++] = if (c == ai) 1 else if (c == opp) 2 else 0
                        cx += dx; cy += dy
                    }
                    if (len < 5) continue

                    var mine = 0; var theirs = 0
                    for (i in 0 until 5) {
                        val v = lineBuf[i]
                        if (v == 1) mine++ else if (v == 2) theirs++
                    }
                    for (i in 0..len - 5) {
                        if (i > 0) {
                            val out = lineBuf[i - 1]
                            if (out == 1) mine-- else if (out == 2) theirs--
                            val inn = lineBuf[i + 4]
                            if (inn == 1) mine++ else if (inn == 2) theirs++
                        }
                        if (mine > 0 && theirs == 0) score += WINDOW[mine]
                        else if (theirs > 0 && mine == 0) score -= WINDOW[theirs]
                    }
                }
            }
        }
        return score
    }

    private fun evaluateFor(color: Stone): Int =
        if (color == ai) evaluate() else -evaluate()

    // ---- tactical helpers --------------------------------------------------

    /**
     * Longest run of [color] through (x,y) if a stone of that colour were placed
     * there. Cheap; used to filter candidates before the precise checks.
     */
    private fun maxRunIfPlaced(x: Int, y: Int, color: Stone): Int {
        var best = 1
        for (d in DIRS) {
            val dx = d[0]; val dy = d[1]
            var run = 1
            var nx = x + dx; var ny = y + dy
            while (nx in 0 until size && ny in 0 until size && board[ny][nx] == color) {
                run++; nx += dx; ny += dy
            }
            nx = x - dx; ny = y - dy
            while (nx in 0 until size && ny in 0 until size && board[ny][nx] == color) {
                run++; nx -= dx; ny -= dy
            }
            if (run > best) best = run
        }
        return best
    }

    /**
     * Number of distinct empty cells that would complete a five for [color] on the
     * lines through (x,y), assuming (x,y) already holds that colour.
     *
     * `>= 2` means an open four (unstoppable), `1` means a simple four (forcing).
     */
    private fun countWinningPoints(x: Int, y: Int, color: Stone): Int {
        var count = 0
        for (d in DIRS) {
            for (k in -4..4) {
                if (k == 0) continue
                val nx = x + d[0] * k
                val ny = y + d[1] * k
                if (nx !in 0 until size || ny !in 0 until size) continue
                if (board[ny][nx] != Stone.EMPTY) continue
                makeMove(nx, ny, color)
                val win = ForbiddenMoves.hasFive(board, nx, ny, color)
                undoMove(nx, ny, color)
                if (win) count++
            }
        }
        return count
    }

    // ---- move generation ---------------------------------------------------

    /** Fills [candList] with empty cells within two cells of an existing stone. */
    private fun buildCandidates() {
        stampVal++
        candCount = 0
        var anyStone = false

        for (y in 0 until size) {
            for (x in 0 until size) {
                if (board[y][x] == Stone.EMPTY) continue
                anyStone = true
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val nx = x + dx; val ny = y + dy
                        if (nx !in 0 until size || ny !in 0 until size) continue
                        if (board[ny][nx] != Stone.EMPTY) continue
                        val idx = ny * size + nx
                        if (stamp[idx] == stampVal) continue
                        stamp[idx] = stampVal
                        candList[candCount++] = idx
                    }
                }
            }
        }

        if (!anyStone) {
            val c = size / 2
            candList[0] = c * size + c
            candCount = 1
        }
    }

    /**
     * Writes up to `cap` packed cell indices into `moveBuf[ply]` and returns the
     * count. Moves that make or block a four always survive the cap.
     */
    private fun genMoves(color: Stone, cap: Int, ply: Int): Int {
        buildCandidates()
        val buf = moveBuf[ply]
        val scores = scoreBuf[ply]

        var n = 0
        for (i in 0 until candCount) {
            val idx = candList[i]
            val x = idx % size; val y = idx / size
            if (color == Stone.BLACK && forbidBlack && !legalIfBlack(x, y)) continue
            buf[n] = idx
            scores[n] = orderingScore(x, y, color, ply)
            n++
        }
        if (n == 0) return 0
        if (n <= cap) {
            sortDesc(buf, scores, 0, n)
            return n
        }
        return orderMoves(buf, scores, n, cap)
    }

    /** Cheap ordering value: my gain, plus what the move denies the opponent. */
    private fun orderingScore(x: Int, y: Int, color: Stone, ply: Int): Int {
        val idx = y * size + x
        val mine = runWeight(maxRunIfPlaced(x, y, color))
        val denied = runWeight(maxRunIfPlaced(x, y, color.opponent()))
        var s = mine * 2 + denied
        s += history[ply][idx]
        if (killers[ply][0] == idx) s += 50_000
        else if (killers[ply][1] == idx) s += 25_000
        return s
    }

    private fun runWeight(run: Int): Int = when {
        run >= 5 -> 1_000_000
        run == 4 -> 100_000
        run == 3 -> 1_000
        run == 2 -> 100
        else -> 10
    }

    /**
     * Partitions out the critical moves (four-making or four-blocking), sorts them
     * first, then fills the rest of the budget with the best remaining moves.
     */
    private fun orderMoves(buf: IntArray, scores: IntArray, n: Int, cap: Int): Int {
        var write = 0
        for (i in 0 until n) {
            if (scores[i] >= CRITICAL_SCORE) {
                if (i != write) {
                    val tb = buf[i]; buf[i] = buf[write]; buf[write] = tb
                    val ts = scores[i]; scores[i] = scores[write]; scores[write] = ts
                }
                write++
            }
        }
        val critical = write
        sortDesc(buf, scores, 0, critical)

        if (critical >= cap) {
            val take = minOf(critical, cap * 2)
            return take
        }
        val got = selectTopDesc(buf, scores, critical, n, cap - critical)
        return critical + got
    }

    /** Insertion sort by score, descending (ranges are small). */
    private fun sortDesc(buf: IntArray, scores: IntArray, from: Int, to: Int) {
        for (i in from + 1 until to) {
            val b = buf[i]; val s = scores[i]
            var j = i - 1
            while (j >= from && scores[j] < s) {
                buf[j + 1] = buf[j]; scores[j + 1] = scores[j]
                j--
            }
            buf[j + 1] = b; scores[j + 1] = s
        }
    }

    /** Moves the best `k` entries of [from,to) to the front; returns how many. */
    private fun selectTopDesc(buf: IntArray, scores: IntArray, from: Int, to: Int, k: Int): Int {
        val limit = minOf(k, to - from)
        for (i in 0 until limit) {
            var bestAt = from + i
            for (j in from + i + 1 until to) {
                if (scores[j] > scores[bestAt]) bestAt = j
            }
            if (bestAt != from + i) {
                val tb = buf[bestAt]; buf[bestAt] = buf[from + i]; buf[from + i] = tb
                val ts = scores[bestAt]; scores[bestAt] = scores[from + i]; scores[from + i] = ts
            }
        }
        return limit
    }

    // ---- helpers -----------------------------------------------------------

    private fun legalIfBlack(x: Int, y: Int): Boolean {
        if (ai != Stone.BLACK || !forbidBlack) return true
        makeMove(x, y, Stone.BLACK)
        val ok = !ForbiddenMoves.isForbidden(board, x, y)
        undoMove(x, y, Stone.BLACK)
        return ok
    }

    private fun makeMove(x: Int, y: Int, c: Stone) {
        board[y][x] = c
        hashKey = hashKey xor zobrist[y][x][c.ordinal]
    }

    private fun undoMove(x: Int, y: Int, c: Stone) {
        board[y][x] = Stone.EMPTY
        hashKey = hashKey xor zobrist[y][x][c.ordinal]
    }

    private fun ttStore(key: Long, depth: Int, score: Int, type: NodeType, bestIdx: Int) {
        val e = tt[(key and TT_MASK).toInt()]
        if (e.depth > depth && e.key == key) return
        e.key = key; e.depth = depth; e.score = score; e.type = type; e.bestIdx = bestIdx
    }

    private fun ttProbe(key: Long): TTEntry? {
        val e = tt[(key and TT_MASK).toInt()]
        return if (e.key == key) e else null
    }

    private fun outOfTime(): Boolean {
        if (!stopped && System.currentTimeMillis() > deadlineMs) stopped = true
        return stopped
    }

    private fun clearTT() {
        for (i in 0 until TT_SIZE) tt[i].key = 0
    }
}
