package com.pavbhaji.pos.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

// Finds the laptop on the shop WiFi so nobody has to type an IP address.
// Two strategies: a UDP "who's there?" broadcast (instant), then a brute
// sweep of the phone's /24 subnet (fallback for routers that eat broadcasts).
object Discovery {
    private const val UDP_PORT = 8178
    private const val MAGIC = "PBPOS_DISCOVER_V1"
    const val DEFAULT_PORT = 8177

    @Serializable
    private data class ServerInfo(
        val app: String = "",
        val name: String = "",
        val id: String = "",
        val port: Int = DEFAULT_PORT,
    )

    data class Found(val addr: String, val id: String, val name: String)

    private val json = Json { ignoreUnknownKeys = true }

    // Snappy client just for probing — Api's 4s timeouts are too patient here.
    private val probeHttp = OkHttpClient.Builder()
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    // One full hunt: broadcast first, sweep only if nobody answered.
    suspend fun search(extraPorts: List<Int> = emptyList()): List<Found> {
        val shouted = broadcast()
        if (shouted.isNotEmpty()) return shouted
        return sweep(extraPorts)
    }

    // Asks addr "are you a PavBhaji server?". Falls back to the old status-page
    // signature so a new app can still connect to a server exe from before
    // /api/ping existed.
    suspend fun verify(addr: String): Found? = withContext(Dispatchers.IO) {
        try {
            probeHttp.newCall(Request.Builder().url("http://$addr/api/ping").get().build())
                .execute().use { r ->
                    if (r.code == 200) {
                        val info = json.decodeFromString<ServerInfo>(r.body?.string() ?: "")
                        return@withContext if (info.app == "pbpos") Found(addr, info.id, info.name) else null
                    }
                }
            probeHttp.newCall(Request.Builder().url("http://$addr/").get().build())
                .execute().use { r ->
                    if (r.isSuccessful && r.body?.string()?.contains("PavBhaji POS") == true)
                        Found(addr, "", "PavBhaji POS") else null
                }
        } catch (_: Exception) {
            null
        }
    }

    // Shout MAGIC at every broadcast address and collect who answers.
    // The reply's source IP is the server; its payload carries port + id.
    suspend fun broadcast(waitMs: Long = 1500): List<Found> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, Found>()
        try {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 250
                val payload = MAGIC.toByteArray()
                repeat(2) { // UDP is lossy — shout twice
                    for (t in broadcastTargets()) {
                        try {
                            sock.send(DatagramPacket(payload, payload.size, t, UDP_PORT))
                        } catch (_: Exception) {
                        }
                    }
                }
                val buf = ByteArray(512)
                val deadline = System.currentTimeMillis() + waitMs
                while (System.currentTimeMillis() < deadline) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(p)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val info = try {
                        json.decodeFromString<ServerInfo>(String(p.data, 0, p.length))
                    } catch (_: Exception) {
                        continue
                    }
                    if (info.app != "pbpos") continue
                    val addr = "${p.address.hostAddress}:${info.port}"
                    found[addr] = Found(addr, info.id, info.name)
                }
            }
        } catch (_: Exception) {
        }
        found.values.toList()
    }

    // Knock on every door of the phone's own /24: TCP-connect to the POS port,
    // then verify the few that answered. ~2s worst case at 64-wide concurrency.
    suspend fun sweep(extraPorts: List<Int> = emptyList()): List<Found> = withContext(Dispatchers.IO) {
        val prefixes = ownV4Prefixes()
        if (prefixes.isEmpty()) return@withContext emptyList()
        val ports = (listOf(DEFAULT_PORT) + extraPorts).filter { it in 1..65535 }.distinct()
        val sem = Semaphore(64)
        val open = coroutineScope {
            prefixes.flatMap { pre -> (1..254).flatMap { i -> ports.map { port -> "$pre.$i:$port" } } }
                .map { addr ->
                    async { sem.withPermit { if (tcpOpen(addr)) addr else null } }
                }.awaitAll().filterNotNull()
        }
        open.mapNotNull { verify(it) }
    }

    private fun broadcastTargets(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        try {
            out.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { out.add(it) }
            }
        } catch (_: Exception) {
        }
        return out.toList()
    }

    // "192.168.1" for every LAN IPv4 the phone holds (usually just one).
    private fun ownV4Prefixes(): List<String> {
        val out = LinkedHashSet<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val ip = ia.address
                    if (ip is Inet4Address && ip.isSiteLocalAddress) {
                        ip.hostAddress?.let { out.add(it.substringBeforeLast('.')) }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out.toList()
    }

    private fun tcpOpen(addr: String): Boolean {
        val host = addr.substringBeforeLast(':')
        val port = addr.substringAfterLast(':').toIntOrNull() ?: return false
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), 350) }
            true
        } catch (_: Exception) {
            false
        }
    }
}
