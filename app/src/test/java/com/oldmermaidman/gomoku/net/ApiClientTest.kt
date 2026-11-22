package com.oldmermaidman.gomoku.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * [ApiClient] against a real HTTP server, [MockWebServer] (TZ §11.3).
 *
 * These are JVM tests: the client talks to a local socket, so the refresh and retry
 * paths are exercised for real rather than against a mock.
 */
class ApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ApiClient
    private lateinit var tokens: FakeSessionStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokens = FakeSessionStore()
        client = ApiClient(server.url("/"), tokens, OkHttpClient(), NetJson)
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun registerStoresTheSession() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"accessToken":"a","refreshToken":"r","expiresIn":900,""" +
                    """"user":{"id":"u","nickname":"ann"}}"""
            )
        )

        val session = client.register("ann", "password-123")

        assertEquals("a", session.accessToken)
        assertEquals("ann", tokens.session()?.nickname)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/auth/register", request.path)
        assertTrue(request.body.readUtf8().contains("\"nickname\":\"ann\""))
    }

    @Test
    fun anExpiredTokenIsRefreshedBeforeTheCall() = runBlocking {
        tokens.save(Session("old", "refresh-1", 0L, "u", "ann"))
        server.enqueue(
            MockResponse().setBody(
                """{"accessToken":"new","refreshToken":"refresh-2","expiresIn":900,""" +
                    """"user":{"id":"u","nickname":"ann"}}"""
            )
        )
        server.enqueue(
            MockResponse().setBody("""{"id":"u","nickname":"ann","stats":{"wins":1,"losses":2,"draws":3}}""")
        )

        val profile = client.profile()

        assertEquals("ann", profile.nickname)
        assertEquals(1L, profile.stats.wins)
        assertEquals("new", tokens.session()?.accessToken)
        assertEquals("/api/v1/auth/refresh", server.takeRequest().path)
        assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun a401IsRetriedOnceAfterARefresh() = runBlocking {
        tokens.save(Session("a", "refresh-1", Long.MAX_VALUE, "u", "ann"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"TOKEN_EXPIRED"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"accessToken":"b","refreshToken":"refresh-2","expiresIn":900,""" +
                    """"user":{"id":"u","nickname":"ann"}}"""
            )
        )
        server.enqueue(MockResponse().setBody("""{"id":"u","nickname":"ann","stats":{}}"""))

        client.profile()

        assertEquals("/api/v1/me", server.takeRequest().path)
        assertEquals("/api/v1/auth/refresh", server.takeRequest().path)
        assertEquals("/api/v1/me", server.takeRequest().path)
    }

    @Test
    fun aRejectedRefreshSignsThePlayerOut() = runBlocking {
        tokens.save(Session("a", "refresh-1", 0L, "u", "ann"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"TOKEN_INVALID"}"""))

        try {
            client.profile()
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(ApiError.TOKEN_INVALID, e.error)
        }
        assertNull(tokens.session())
    }

    @Test
    fun theErrorCodeAndDetailsAreMapped() = runBlocking {
        tokens.save(Session("a", "r", Long.MAX_VALUE, "u", "ann"))
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"code":"CELL_OCCUPIED","message":"taken","details":{"x":7,"y":7}}"""
            )
        )

        try {
            client.move("g", 12, 7, 7)
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(ApiError.CELL_OCCUPIED, e.error)
            assertEquals(7, e.details?.get("x")?.jsonPrimitive?.int)
        }
    }

    @Test
    fun aMoveSendsTheExpectedPly() = runBlocking {
        tokens.save(Session("a", "r", Long.MAX_VALUE, "u", "ann"))
        server.enqueue(MockResponse().setBody(SNAPSHOT))

        client.move("g", 12, 7, 7)

        val request = server.takeRequest()
        assertEquals("/api/v1/games/g/moves", request.path)
        assertEquals("""{"ply":12,"x":7,"y":7}""", request.body.readUtf8())
    }

    @Test
    fun aNetworkFailureIsReportedAsNetwork() = runBlocking {
        tokens.save(Session("a", "r", Long.MAX_VALUE, "u", "ann"))
        server.shutdown()

        try {
            client.profile()
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(ApiError.NETWORK, e.error)
        }
    }

    private companion object {
        const val SNAPSHOT = """
            {
              "gameId": "g", "code": "ABC123", "rule": "FREESTYLE", "timeControl": "UNTIMED",
              "status": "ACTIVE", "you": "BLACK", "ply": 12, "turn": "BLACK",
              "moves": [], "clocks": {}, "serverTime": "2026-11-21T03:20:11.482Z",
              "result": "NONE", "finishReason": "NONE", "undoRemaining": 3
            }
        """
    }
}

/** An in-memory [SessionStore], so the JVM tests do not need a Context. */
private class FakeSessionStore : SessionStore {

    private var value: Session? = null

    override fun session(): Session? = value

    override fun save(session: Session) {
        value = session
    }

    override fun clear() {
        value = null
    }

    override fun hasSession(): Boolean = value != null
}