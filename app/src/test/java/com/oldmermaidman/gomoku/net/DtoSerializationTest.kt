package com.oldmermaidman.gomoku.net

import com.oldmermaidman.gomoku.net.dto.CreateGameRequest
import com.oldmermaidman.gomoku.net.dto.DrawDecisionRequest
import com.oldmermaidman.gomoku.net.dto.GameEventDto
import com.oldmermaidman.gomoku.net.dto.GameHistoryDto
import com.oldmermaidman.gomoku.net.dto.GameSnapshotDto
import com.oldmermaidman.gomoku.net.dto.JoinGameRequest
import com.oldmermaidman.gomoku.net.dto.LeaderboardRowDto
import com.oldmermaidman.gomoku.net.dto.LobbyGameDto
import com.oldmermaidman.gomoku.net.dto.MoveRequest
import com.oldmermaidman.gomoku.net.dto.QuickGameRequest
import com.oldmermaidman.gomoku.net.dto.ReportRequest
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
    fun theQuickMatchAndLobbyDtosMatchTheWire() {
        assertEquals(
            """{"rule":"FREESTYLE","timeControl":"RAPID"}""",
            json.encodeToString(QuickGameRequest("FREESTYLE", "RAPID")),
        )
        assertEquals(
            """{"rule":"FREESTYLE","timeControl":"RAPID","color":"WHITE"}""",
            json.encodeToString(QuickGameRequest("FREESTYLE", "RAPID", "WHITE")),
        )
        assertEquals(
            """{"rule":"FREESTYLE","timeControl":"RAPID","visibility":"PUBLIC"}""",
            json.encodeToString(CreateGameRequest("FREESTYLE", "RAPID", visibility = "PUBLIC")),
        )

        val lobby = json.decodeFromString<List<LobbyGameDto>>(
            """[{"gameId":"g","code":"ABC123","rule":"RENJU","timeControl":"BLITZ","creatorColor":"ANY","creatorNickname":"ann"}]""",
        )
        assertEquals(1, lobby.size)
        assertEquals("ABC123", lobby[0].code)
        assertEquals("ann", lobby[0].creatorNickname)
        assertEquals("ANY", lobby[0].creatorColor)
    }

    @Test
    fun theHistoryRowReadsFromTheWire() {
        val rows = json.decodeFromString<List<GameHistoryDto>>(
            """[{"gameId":"g","rule":"RENJU","timeControl":"BLITZ","ranked":true,""" +
                """"result":"WHITE_WIN","finishReason":"RESIGN","you":"WHITE",""" +
                """"opponentNickname":"ann","opponentRating":1184,"ratingDelta":16,""" +
                """"moveCount":24}]""",
        )

        assertEquals(1, rows.size)
        assertEquals("WHITE", rows[0].you)
        assertEquals(16, rows[0].ratingDelta)
        assertEquals(1184, rows[0].opponentRating)
        assertEquals(24, rows[0].moveCount)
    }

    @Test
    fun theLeaderboardRowReadsFromTheWire() {
        val rows = json.decodeFromString<List<LeaderboardRowDto>>(
            """[{"rank":1,"userId":"u","nickname":"ann","rating":2032,"wins":7,"losses":2,"draws":1}]""",
        )

        assertEquals(1, rows.size)
        assertEquals(1, rows[0].rank)
        assertEquals("ann", rows[0].nickname)
        assertEquals(2032, rows[0].rating)
        assertEquals(7L, rows[0].wins)
    }

    @Test
    fun theRematchOfferComesFromTheSnapshot() {
        val dto = json.decodeFromString<GameSnapshotDto>(
            """{"gameId":"g","status":"FINISHED","rematchGameId":"r"}""",
        )

        assertEquals("r", dto.rematchGameId)
    }

    @Test
    fun theReportRequestCarriesTheReason() {
        assertEquals("""{"reason":"CHEATING"}""", json.encodeToString(ReportRequest("CHEATING")))
    }

    @Test
    fun theDrawOfferComesFromTheSnapshot() {
        val dto = json.decodeFromString<GameSnapshotDto>(
            """{"gameId":"g","status":"ACTIVE","drawRemaining":2,""" +
                """"draw":{"requestedBy":"BLACK","deadline":"2026-11-21T03:20:14.482Z"}}""",
        )

        assertEquals(2, dto.drawRemaining)
        assertEquals("BLACK", dto.draw?.requestedBy)
    }

    @Test
    fun theDrawDecisionCarriesTheAnswer() {
        assertEquals("""{"accept":true}""", json.encodeToString(DrawDecisionRequest(true)))
    }

    @Test
    fun anUnknownFieldDoesNotBreakTheClient() {
        val snapshot = json.decodeFromString<GameSnapshotDto>("""{"gameId":"g","newField":42}""")
        assertEquals("g", snapshot.gameId)
        assertEquals("NONE", snapshot.result)
    }
}