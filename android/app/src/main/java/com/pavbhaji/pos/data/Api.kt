package com.pavbhaji.pos.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    data class Err(
        val message: String,
        val code: Int = 0, // 0 = network problem (server unreachable)
        val blockers: List<String> = emptyList()
    ) : ApiResult<Nothing>()
}

@Serializable
private data class ErrBody(val error: String = "", val blockers: List<String> = emptyList())

@Serializable
data class OkResp(val ok: Boolean = true, val updated: Long = 0, val total: Long = 0, val id: Long = 0)

class Api(private val session: Session) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Called when the server says our token is dead — the app bounces to the PIN screen.
    var onAuthFail: (() -> Unit)? = null

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun baseUrl() = "http://" + session.serverAddr

    private suspend inline fun <reified T> call(
        method: String, path: String, body: String? = null, auth: Boolean = true
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(baseUrl() + path)
        if (auth && session.token.isNotEmpty()) b.header("Authorization", "Bearer " + session.token)
        when (method) {
            "GET" -> b.get()
            else -> b.method(method, (body ?: "{}").toRequestBody(jsonMedia))
        }
        try {
            http.newCall(b.build()).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    ApiResult.Ok(json.decodeFromString<T>(text))
                } else {
                    if (resp.code == 401 && auth) onAuthFail?.invoke()
                    val e = try { json.decodeFromString<ErrBody>(text) } catch (_: Exception) { ErrBody("server error ${resp.code}") }
                    ApiResult.Err(e.error.ifEmpty { "server error ${resp.code}" }, resp.code, e.blockers)
                }
            }
        } catch (e: IOException) {
            ApiResult.Err("can't reach server — check WiFi", 0)
        } catch (e: Exception) {
            ApiResult.Err("unexpected: ${e.message}", -1)
        }
    }

    // ---- auth ----
    suspend fun login(pin: String): ApiResult<LoginResp> =
        call("POST", "/api/login", """{"pin":"$pin"}""", auth = false)

    suspend fun logout(): ApiResult<OkResp> = call("POST", "/api/logout")

    // ---- shared reads ----
    suspend fun bootstrap(): ApiResult<BootstrapDto> = call("GET", "/api/bootstrap")

    @Serializable data class TablesResp(val tables: List<TableStateDto> = emptyList())
    suspend fun tables(): ApiResult<TablesResp> = call("GET", "/api/tables")

    @Serializable data class MenuResp(val menu: List<CategoryDto> = emptyList())
    suspend fun menu(): ApiResult<MenuResp> = call("GET", "/api/menu")

    @Serializable data class OrderWrap(val order: OrderDetailDto? = null)
    suspend fun tableOrder(tableId: Long): ApiResult<OrderWrap> =
        call("GET", "/api/dining-tables/$tableId/order")

    suspend fun kitchen(): ApiResult<KitchenDto> = call("GET", "/api/kitchen")

    // ---- waiter actions ----
    @Serializable
    private data class TicketReq(val clientTicketId: String, val items: List<NewTicketLine>)

    // The one call that must never double-punch. clientTicketId is generated when
    // the cart is created and reused across every retry — the server treats a
    // repeat as "already saved, here's the result". Network errors retry
    // automatically; HTTP errors (a real server decision) do not.
    suspend fun sendTicket(
        tableId: Long, clientTicketId: String, lines: List<NewTicketLine>
    ): ApiResult<OrderDetailDto> {
        val body = json.encodeToString(TicketReq(clientTicketId, lines))
        var last: ApiResult<OrderDetailDto> = ApiResult.Err("not sent", 0)
        repeat(4) { attempt ->
            last = call("POST", "/api/dining-tables/$tableId/tickets", body)
            when (val l = last) {
                is ApiResult.Ok -> return l
                is ApiResult.Err -> if (l.code != 0) return l // server answered: don't retry
            }
            delay(1200L * (attempt + 1))
        }
        return last
    }

    suspend fun itemStatus(itemId: Long, to: String): ApiResult<OkResp> =
        call("POST", "/api/items/$itemId/status", """{"to":"$to"}""")

    suspend fun itemCancel(itemId: Long, reason: String): ApiResult<OkResp> =
        call("POST", "/api/items/$itemId/cancel", json.encodeToString(mapOf("reason" to reason)))

    suspend fun serveAll(orderId: Long): ApiResult<OkResp> =
        call("POST", "/api/orders/$orderId/serve-all")

    suspend fun closeOrder(orderId: Long): ApiResult<OkResp> =
        call("POST", "/api/orders/$orderId/close")

    // ---- admin ----
    @Serializable data class StaffResp(val staff: List<StaffDto> = emptyList())
    suspend fun staffList(): ApiResult<StaffResp> = call("GET", "/api/admin/staff")

    suspend fun staffCreate(name: String, pin: String, role: String): ApiResult<OkResp> =
        call("POST", "/api/admin/staff", json.encodeToString(mapOf("name" to name, "pin" to pin, "role" to role)))

    suspend fun staffPatch(id: Long, patch: String): ApiResult<OkResp> =
        call("PATCH", "/api/admin/staff/$id", patch)

    @Serializable data class AdminTablesResp(val tables: List<AdminTableDto> = emptyList())
    suspend fun adminTables(): ApiResult<AdminTablesResp> = call("GET", "/api/admin/tables")

    suspend fun tableCreate(name: String): ApiResult<OkResp> =
        call("POST", "/api/admin/tables", json.encodeToString(mapOf("name" to name)))

    suspend fun tablePatch(id: Long, patch: String): ApiResult<OkResp> =
        call("PATCH", "/api/admin/tables/$id", patch)

    suspend fun categoryCreate(name: String): ApiResult<OkResp> =
        call("POST", "/api/admin/categories", json.encodeToString(mapOf("name" to name)))

    suspend fun menuItemCreate(categoryId: Long, name: String, price: Long): ApiResult<OkResp> =
        call("POST", "/api/admin/menu-items",
            """{"categoryId":$categoryId,"name":${json.encodeToString(name)},"price":$price}""")

    suspend fun menuItemPatch(id: Long, patch: String): ApiResult<OkResp> =
        call("PATCH", "/api/admin/menu-items/$id", patch)

    suspend fun report(date: String?): ApiResult<ReportDto> =
        call("GET", "/api/admin/report" + if (date.isNullOrEmpty()) "" else "?date=$date")

    suspend fun dashboard(): ApiResult<ReportDto> = call("GET", "/api/admin/dashboard")

    @Serializable data class AuditResp(val events: List<AuditEventDto> = emptyList())
    suspend fun audit(beforeId: Long = 0): ApiResult<AuditResp> =
        call("GET", "/api/admin/audit?limit=100" + if (beforeId > 0) "&beforeId=$beforeId" else "")

    suspend fun backupBytes(): ApiResult<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(baseUrl() + "/api/admin/backup")
                .header("Authorization", "Bearer " + session.token).get().build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) ApiResult.Ok(resp.body!!.bytes())
                else ApiResult.Err("backup failed (${resp.code})", resp.code)
            }
        } catch (e: Exception) {
            ApiResult.Err("can't reach server — check WiFi", 0)
        }
    }
}
