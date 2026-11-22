package com.oldmermaidman.gomoku.net

import android.content.Context
import com.oldmermaidman.gomoku.BuildConfig
import kotlinx.coroutines.CoroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The network layer's single entry point, created once by [com.oldmermaidman.gomoku.GomokuApp].
 *
 * One [OkHttpClient] serves both REST and the WebSocket, so the connection pool and the
 * keep-alive pings are shared. The base URL comes from `BuildConfig.API_BASE_URL`, which
 * the build sets per variant: debug points at the development server, release at the
 * deployed one.
 */
class NetContainer(context: Context) {

    /** The signed-in player's tokens; the only place they live. */
    val tokens = TokenStore(context)

    val json = NetJson

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // No pingInterval: the server does not answer WebSocket ping frames, so OkHttp's
        // keepalive would fail the connection every 20 seconds with "no pong". The socket
        // is kept alive by the server's own traffic and by reconnecting when it drops.
        .build()

    val api = ApiClient(BuildConfig.API_BASE_URL.toHttpUrl(), tokens, http, json)

    /** A fresh socket; [scope] owns its lifetime (the ViewModel's scope, in practice). */
    fun socket(scope: CoroutineScope): GameSocket = GameSocket(api.socketUrl, api, http, json, scope)
}
