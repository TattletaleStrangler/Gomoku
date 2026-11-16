package com.example.gomoku.engine

import android.util.Log
import com.example.gomoku.model.Move
import com.example.gomoku.model.Stone
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Drives the native Rapfi engine over the Gomocup/Piskvork protocol through
 * stdin/stdout (see README.md and TZ §4.2).
 *
 * Lifecycle and robustness (TZ §4.3):
 *  - the binary is located by [NativeExe] in `nativeLibraryDir`;
 *  - a self-check runs one real move before the engine is marked available;
 *  - every reply is read with a timeout; on failure the process is restarted
 *    and the position is replayed once;
 *  - only 15×15 boards are sent to Rapfi — the caller selects the built-in AI
 *    for 13×13 / 19×19.
 *
 * Two Rapfi-specific details are handled here (see its `command/gomocup.cpp`):
 *
 *  1. **Move order matters.** `BOARD` is parsed by Rapfi's `getPosition()`, which
 *     derives its own color from the *first* listed stone
 *     (`selfColor = side == SELF ? BLACK : WHITE`) and then replays the list
 *     assuming the first move was black. Sending the stones in coordinate order
 *     (as an earlier version did) makes Rapfi pick the wrong color and then abort
 *     the position without printing a move — which looks like a hang. Stones are
 *     therefore sent in **the order they were played**.
 *  2. **`INFO timeout_turn` is required.** Without it Rapfi searches with its own
 *     default budget, which can exceed the protocol read timeout. It is sent
 *     before every move request.
 */
class RapfiGomokuEngine(private val nativeExe: NativeExe) {

    companion object {
        private const val TAG = "RapfiGomokuEngine"

        /** Timeout for reading engine replies; must exceed the sent timeout_turn. */
        private const val TIMEOUT_MS = 12_000L
        private const val BOARD_SIZE = 15

        /**
         * Rapfi's own `INFO rule` mapping (NOT the plain Piskvork bitmask):
         * 0 = freestyle, 1 = standard, 4 = renju, 5 = freestyle+swap1, 6 = freestyle+swap2.
         */
        private const val RULE_FREESTYLE = 0
        private const val RULE_RENJU = 4
    }

    @Volatile
    var available: Boolean = false
        private set

    /** Human-readable reason the engine is unavailable (for diagnostics / UI hint). */
    @Volatile
    var lastError: String? = null
        private set

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val lines = LinkedBlockingQueue<String>()

    /**
     * Installs the binary, starts the process, and runs the self-check from TZ §4.1:
     * `START 15` → `OK` → `BEGIN` → one valid move. Only then is the engine marked
     * available. Any failure leaves `available == false` and the caller falls back
     * to the built-in AI.
     */
    @Synchronized
    fun initAndSelfCheck(): Boolean {
        if (!nativeExe.install()) {
            lastError = "install failed"
            Log.e(TAG, "engine install failed")
            available = false
            return false
        }
        if (!ensureRunning()) {
            stop()
            available = false
            return false
        }
        return try {
            sendInfo(RULE_FREESTYLE, SELF_CHECK_BUDGET_MS)
            send("BEGIN")
            val move = readMove(Stone.BLACK)
            if (move == null) {
                lastError = "no valid opening move"
                Log.w(TAG, "self-check: no valid opening move")
                stop()
                available = false
                return false
            }
            Log.i(TAG, "self-check passed, opening move = ${move.x},${move.y}")
            lastError = null
            // The self-check leaves the process in a post-BEGIN state; tear it down
            // so the first real move starts a clean START + (BEGIN|BOARD) session.
            stop()
            available = true
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "self-check failed", e)
            stop()
            available = false
            false
        }
    }

    /**
     * Requests the engine's move for the position described by [moves], which MUST
     * be ordered as the stones were played (see the class comment). The stones
     * belonging to [aiColor] are sent as protocol field 1 ("own stone").
     *
     * Returns null when the engine is unavailable or failed twice.
     */
    @Synchronized
    fun nextMove(
        moves: List<Move>,
        aiColor: Stone,
        boardSize: Int,
        renju: Boolean,
        timeLimitMs: Long
    ): Move? {
        if (!available || boardSize != BOARD_SIZE) return null

        // Every request runs in a fresh process: stateless and predictable.
        stop()

        var move = requestMove(moves, aiColor, renju, timeLimitMs)
        if (move == null && !Thread.currentThread().isInterrupted) {
            Log.w(TAG, "move request failed — restarting and retrying once")
            stop()
            move = if (ensureRunning()) {
                requestMove(moves, aiColor, renju, timeLimitMs)
            } else {
                null
            }
        }
        available = move != null
        return move
    }

    /** Graceful shutdown: send END, wait briefly, then force-kill if needed. */
    @Synchronized
    fun stop() {
        available = false
        try {
            send("END")
        } catch (_: Exception) {
        }
        try {
            val p = process
            if (p != null) {
                if (!p.waitFor(1, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    p.waitFor(500, TimeUnit.MILLISECONDS)
                }
            }
        } catch (_: Exception) {
        }
        process = null
        writer = null
        lines.clear()
    }

    // ---- internals --------------------------------------------------------

    private fun isAlive(): Boolean = process?.isAlive == true

    /** Starts the process and performs the START handshake. */
    private fun ensureRunning(): Boolean {
        if (isAlive()) return true
        if (!startProcess()) return false
        send("START $BOARD_SIZE")

        // The engine may print a banner or other lines before replying OK;
        // read until OK (or an ERROR) within the timeout.
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                lastError = "timeout waiting for OK after START"
                Log.e(TAG, lastError!!)
                return false
            }
            val line = readLine(remaining) ?: run {
                lastError = "engine exited before START"
                Log.e(TAG, lastError!!)
                return false
            }
            val t = line.trim()
            Log.d(TAG, "engine -> $t")
            if (t == "OK") return true
            if (t.startsWith("ERROR")) {
                lastError = t
                Log.e(TAG, "engine error: $t")
                return false
            }
        }
    }

    private fun startProcess(): Boolean {
        val bin = nativeExe.binaryFile
        if (bin == null || !bin.exists()) {
            lastError = "binary missing in nativeLibraryDir"
            Log.e(TAG, lastError!!)
            return false
        }
        return try {
            val pb = ProcessBuilder(bin.absolutePath)
            pb.directory(nativeExe.engineDir)
            pb.redirectErrorStream(true) // merge stderr so a full pipe cannot deadlock
            process = pb.start()
            writer = BufferedWriter(OutputStreamWriter(process!!.outputStream, Charsets.UTF_8))
            lines.clear()

            val reader = BufferedReader(InputStreamReader(process!!.inputStream, Charsets.UTF_8))
            Thread({
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        lines.offer(line)
                    }
                } catch (_: Exception) {
                }
            }, "rapfi-stdout").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "process started: ${bin.absolutePath}")
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "startProcess failed: $lastError", e)
            process = null
            false
        }
    }

    /** Sends the options Rapfi needs before a move (rule, opponent type, time budget). */
    private fun sendInfo(rule: Int, timeLimitMs: Long) {
        send("INFO rule $rule")
        send("INFO game_type 0")          // 0 = human opponent
        send("INFO timeout_turn $timeLimitMs") // WITHOUT this Rapfi uses its own, longer budget
    }

    private fun requestMove(
        moves: List<Move>,
        aiColor: Stone,
        renju: Boolean,
        timeLimitMs: Long
    ): Move? {
        if (!ensureRunning()) return null
        sendInfo(if (renju) RULE_RENJU else RULE_FREESTYLE, timeLimitMs)

        if (moves.isEmpty()) {
            // Opening move on an empty board: the engine plays black.
            send("BEGIN")
        } else {
            sendBoard(moves, aiColor)
        }

        val move = readMove(aiColor)
        if (move == null) {
            lastError = "no move from engine (timeout or rejected BOARD)"
            Log.e(TAG, lastError!!)
        }
        return move
    }

    /**
     * Sends `BOARD` + `x,y,field` lines + `DONE`, **in the order the stones were
     * played** (required by Rapfi's `getPosition()`; see the class comment).
     * field 1 = the engine's own stone, field 2 = the opponent's stone.
     */
    private fun sendBoard(moves: List<Move>, aiColor: Stone) {
        val sb = StringBuilder()
        sb.append("BOARD\n")
        for (m in moves) {
            if (m.x !in 0 until BOARD_SIZE || m.y !in 0 until BOARD_SIZE) continue
            val field = if (m.color == aiColor) 1 else 2
            sb.append(m.x).append(',').append(m.y).append(',').append(field).append('\n')
        }
        sb.append("DONE\n")
        try {
            writer?.write(sb.toString())
            writer?.flush()
            Log.d(TAG, "sent BOARD with ${moves.size} stones (ai = $aiColor)")
        } catch (e: Exception) {
            Log.e(TAG, "sendBoard failed", e)
        }
    }

    private fun send(line: String): Boolean {
        return try {
            writer?.write(line + "\n")
            writer?.flush()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun readLine(timeoutMs: Long = TIMEOUT_MS): String? {
        return try {
            lines.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    /**
     * Reads lines until a timeout or EOF and returns the **last** valid coordinate
     * line. This mirrors the TZ §4.4 note: engines may emit DEBUG/MESSAGE lines and
     * the move is the last line that carries a valid coordinate.
     */
    private fun readMove(aiColor: Stone): Move? {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        var last: Pair<Int, Int>? = null
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val line = readLine(remaining) ?: break
            Log.d(TAG, "engine -> $line")
            val coord = parseCoord(line)
            if (coord != null) {
                last = coord
                // Drain anything that arrives immediately afterwards, keeping the last
                // coordinate (the engine's actual move) in case of trailing output.
                while (true) {
                    val more = readLine(80) ?: break
                    val mc = parseCoord(more)
                    if (mc != null) last = mc
                }
                break
            }
        }
        return last?.let { Move(it.first, it.second, aiColor) }
    }

    private fun parseCoord(line: String): Pair<Int, Int>? {
        val t = line.trim()
        val m = Regex("^(\\d{1,2})\\s*,\\s*(\\d{1,2})$").find(t) ?: return null
        val x = m.groupValues[1].toIntOrNull() ?: return null
        val y = m.groupValues[2].toIntOrNull() ?: return null
        if (x !in 0 until BOARD_SIZE || y !in 0 until BOARD_SIZE) return null
        return x to y
    }
}

/** Time budget used for the one-move self-check (the opening comes from Rapfi's book). */
private const val SELF_CHECK_BUDGET_MS = 3_000L
