package com.oldmermaidman.gomoku.net

import com.oldmermaidman.gomoku.net.dto.CreateGameRequest
import com.oldmermaidman.gomoku.net.dto.GameEventDto
import com.oldmermaidman.gomoku.net.dto.GameSnapshotDto
import com.oldmermaidman.gomoku.net.dto.JoinGameRequest
import com.oldmermaidman.gomoku.net.dto.MoveRequest
import com.oldmermaidman.gomoku.net.dto.UndoDecisionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The DTOs against the shapes the server actually sends (TZ_BACKEND.md §7, §8).
 *
 * The backend omits null fields, so "a missing key" and "a null value" have to mean the
 * same thing to the client; every optional field therefore has a default.
 */
class DtoSerializationTest {

    private val json = NetJson

    @Test
    fun aSnapshotWithOmittedNullsIsDecoded() {
        val wire = """
            {
              "gameId": "6f1c",
              "code": "K7M2QP",
              "rule": "RENJU",
              "timeControl": "RAPID",
              "status": "ACTIVE",
              "black": {"userId": "a", "nickname": "ann"},
              "white": {"userId": "b", "nickname": "bob"},
              "you": "BLACK",
              "ply": 12,
              "turn": "BLACK",
              "moves": [{"ply":1,"x":7,"y":7,"color":"BLACK"}],
              "clocks": {"blackRemainingMs": 812340, "whiteRemainingMs": 795120, "running": "BLACK"},
              "serverTime": "2026-11-21T03:20:11.482Z",
              "result": "NONE",
              "finishReason": "NONE",
              "undoRemaining": 3
            }
        """.trimIndent()

        val snapshot = json.decodeFromString<GameSnapshotDto>(wire)

        assertEquals("6f1c", snapshot.gameId)
        assertEquals(1, snapshot.moves.size)
        assertEquals(7, snapshot.moves[0].x)
        assertEquals(812340L, snapshot.clocks?.blackRemainingMs)
        assertEquals(3, snapshot.undoRemaining)
        assertNull(snapshot.undo)
        assertNull(snapshot.lastUndoneMove)
    }

    @Test
    fun aPendingUndoAndAGhostAreDecoded() {
        val wire = """
            {
              "gameId": "g", "code": "ABC123", "rule": "FREESTYLE", "timeControl": "UNTIMED",
              "status": "ACTIVE", "you": "WHITE", "ply": 3, "turn": "WHITE",
              "moves": [
                {"ply":1,"x":0,"y":0,"color":"BLACK"},
                {"ply":2,"x":1,"y":1,"color":"WHITE"}
              ],
              "clocks": {},
              "serverTime": "2026-11-21T03:20:11.482Z",
              "result": "NONE", "finishReason": "NONE", "undoRemaining": 2,
              "undo": {"requestedBy": "WHITE", "deadline": "2026-11-21T03:20:14.482Z", "ply": 2},
              "lastUndoneMove": {"ply": 2, "x": 1, "y": 1, "color": "WHITE"}
            }
        """.trimIndent()

        val snapshot = json.decodeFromString<GameSnapshotDto>(wire)

        assertEquals("WHITE", snapshot.undo?.requestedBy)
        assertEquals(2, snapshot.undo?.ply)
        assertEquals(1, snapshot.lastUndoneMove?.x)
        // An untimed game sends an empty clocks object, not a missing one.
        assertNull(snapshot.clocks?.blackRemainingMs)
        assertNull(snapshot.clocks?.whiteRemainingMs)
    }

    @Test
    fun aPongCarriesNoGame() {
        val event = json.decodeFromString<GameEventDto>("""{"type":"PONG"}""")
        assertEquals("PONG", event.type)
        assertNull(event.game)
    }

    @Test
    fun requestBodiesCarryEveryField() {
        assertEquals("""{"ply":1,"x":7,"y":7}""", json.encodeToString(MoveRequest(1, 7, 7)))
        assertEquals("""{"accept":false}""", json.encodeToString(UndoDecisionRequest(false)))
    }

    @Test
    fun theColourIsSentOnlyWhenThePlayerChoseOne() {
        assertEquals(
            """{"rule":"FREESTYLE","timeControl":"UNTIMED","color":"ANY"}""",
            json.encodeToString(CreateGameRequest("FREESTYLE", "UNTIMED", "ANY")),
        )
        assertEquals(
            """{"rule":"FREESTYLE","timeControl":"UNTIMED"}""",
            json.encodeToString(CreateGameRequest("FREESTYLE", "UNTIMED")),
        )
        assertEquals(
            """{"code":"ABC123","color":"WHITE"}""",
            json.encodeToString(JoinGameRequest("ABC123", "WHITE")),
        )
        assertEquals(
            """{"code":"ABC123"}""",
            json.encodeToString(JoinGameRequest("ABC123")),
        )
    }

    @Test
    fun anUnknownFieldDoesNotBreakTheClient() {
        val snapshot = json.decodeFromString<GameSnapshotDto>("""{"gameId":"g","newField":42}""")
        assertEquals("g", snapshot.gameId)
        assertEquals("NONE", snapshot.result)
    }
}