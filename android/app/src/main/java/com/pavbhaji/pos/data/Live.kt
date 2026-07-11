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

@Serializable
private data class TopicMsg(val topic: String = "")

// Keeps one WebSocket to the hub alive and re-dials it forever.
// Screens collect `topics` to know when to refetch, and `connected`
// drives the red "reconnecting" banner.
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
            while (true) {
                if (session.token.isEmpty() || session.serverAddr.isEmpty()) {
                    delay(1000); continue
                }
                val opened = MutableStateFlow(true)
                val req = Request.Builder()
                    .url("ws://" + session.serverAddr + "/ws?token=" + session.token)
                    .build()
                ws = api.http.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        connected.value = true
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
                delay(2000)
            }
        }
    }

    fun stop() {
        ws?.cancel()
        scope?.cancel()
        scope = null
        connected.value = false
    }
}
