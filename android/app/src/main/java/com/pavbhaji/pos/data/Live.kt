package com.pavbhaji.pos.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
private data class TopicMsg(val topic: String = "")

// Keeps one WebSocket to the hub alive and re-dials it forever.
// Screens collect `topics` to know when to refetch, and `connected`
// drives the amber "no internet" banner under every header.
class Live(private val session: Session, private val api: Api) {
    val connected = MutableStateFlow(false)
    val topics = MutableSharedFlow<String>(extraBufferCapacity = 64)

    private var scope: CoroutineScope? = null
    private var ws: WebSocket? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            var deadDials = 0 // consecutive dials that never even opened
            while (true) {
                if (session.token.isEmpty() || session.serverAddr.isEmpty()) {
                    delay(1000); continue
                }
                val opened = MutableStateFlow(true)
                val sawOpen = AtomicBoolean(false)
                val req = Request.Builder()
                    .url(session.wsBase() + "/ws?token=" + session.token)
                    .build()
                ws = api.http.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        sawOpen.set(true); connected.value = true
                        // Back online: whatever changed while the line was down
                        // never arrived as a push, so every open screen re-reads
                        // now — and its "no internet" error clears by itself
                        // instead of waiting for the next slow poll.
                        listOf("tables", "kitchen", "menu", "inventory").forEach { topics.tryEmit(it) }
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val t = try { json.decodeFromString<TopicMsg>(text).topic } catch (_: Exception) { "" }
                        if (t.isNotEmpty()) topics.tryEmit(t)
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        connected.value = false; opened.value = false
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        connected.value = false; opened.value = false
                    }
                })
                // wait until this socket dies, then loop and re-dial
                while (opened.value) delay(500)
                ws?.cancel()
                if (sawOpen.get()) deadDials = 0 else deadDials++
                // Can't even reach the server several dials in a row? Its IP may
                // have changed (router restart) — hunt for it and repoint.
                if (deadDials > 0 && deadDials % 3 == 0) rediscover(deep = deadDials >= 9)
                delay(2000)
            }
        }
    }

    // Every API call and the next dial read session.serverAddr fresh, so
    // updating it here heals the whole app. Only trusts a server proving the
    // same identity — or any single one if this install never saw an id.
    private suspend fun rediscover(deep: Boolean) {
        // A server on the internet does not move. Dead dials there mean this
        // phone has lost its connection, and sweeping the shop WiFi for a
        // machine that was never on it only burns battery.
        if (session.serverAddr.startsWith("http")) return
        val lastPort = session.serverAddr.substringAfterLast(':', "").toIntOrNull()
        val found = if (deep) Discovery.search(listOfNotNull(lastPort)) else Discovery.broadcast()
        val ours = found.firstOrNull { it.id.isNotEmpty() && it.id == session.serverId }
            ?: if (session.serverId.isEmpty()) found.singleOrNull() else null
        if (ours != null && ours.addr != session.serverAddr) {
            session.serverAddr = ours.addr
            if (ours.id.isNotEmpty()) session.serverId = ours.id
        }
    }

    fun stop() {
        ws?.cancel()
        scope?.cancel()
        scope = null
        connected.value = false
    }
}
