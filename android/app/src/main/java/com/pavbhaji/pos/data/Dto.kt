package com.pavbhaji.pos.data

import kotlinx.serialization.Serializable

@Serializable
data class StaffDto(val id: Long, val name: String, val role: String, val active: Boolean = true)

@Serializable
data class LoginResp(val token: String, val staff: StaffDto)

@Serializable
data class PingDto(
    val app: String = "", val name: String = "", val id: String = "",
    val port: Int = 0, val time: Long = 0 // laptop's clock, for drift detection
)

@Serializable
data class MenuItemDto(
    val id: Long, val name: String, val price: Long,
    val available: Boolean = true, val sort: Int = 0,
    val colour: String = "", val station: String = "kitchen",
    val batchCap: Long? = null, val batchAgeMin: Long? = null
)

@Serializable
data class CategoryDto(
    val id: Long, val name: String, val sort: Int = 0,
    val items: List<MenuItemDto> = emptyList()
)

@Serializable
data class TableOrderDto(
    val orderId: Long, val orderNo: Long, val openedAt: Long,
    val openedByName: String = "", val itemCount: Long = 0,
    val pendingCount: Long = 0, val readyCount: Long = 0, val total: Long = 0
)

@Serializable
data class TableStateDto(
    val id: Long, val name: String, val kind: String = "dine",
    val order: TableOrderDto? = null
)

@Serializable
data class OrderItemDto(
    val id: Long, val menuItemId: Long = 0, val name: String, val price: Long,
    val qty: Long, val note: String = "", val status: String,
    val cancelReason: String = "",
    val station: String = "kitchen", val colour: String = ""
)

@Serializable
data class TicketDto(
    val id: Long, val ticketNo: Long, val createdAt: Long,
    val createdByName: String = "", val items: List<OrderItemDto> = emptyList()
)

@Serializable
data class OrderDetailDto(
    val id: Long, val orderNo: Long, val bizDate: String = "",
    val tableId: Long, val tableName: String = "", val status: String,
    val openedAt: Long = 0, val openedByName: String = "",
    val total: Long = 0, val tickets: List<TicketDto> = emptyList()
)

@Serializable
data class BootstrapDto(
    val me: StaffDto,
    val menu: List<CategoryDto> = emptyList(),
    val tables: List<TableStateDto> = emptyList(),
    val serverTime: Long = 0, val bizDate: String = ""
)

@Serializable
data class BucketLineDto(val tableName: String, val qty: Long, val note: String = "")

@Serializable
data class BucketDto(
    val id: Long, val menuItemId: Long = 0, val name: String, val colour: String = "",
    val status: String, val cap: Long = 0, val qty: Long,
    val createdAt: Long = 0, val takenAt: Long = 0,
    val takenBy: Long = 0, val takenByName: String = "",
    val lines: List<BucketLineDto> = emptyList()
)

@Serializable
data class KitchenDto(
    val buckets: List<BucketDto> = emptyList(),
    val cap: Long = 15,
    val serverTime: Long = 0
)

@Serializable
data class PickupLineDto(
    val itemId: Long, val name: String, val qty: Long, val note: String = "",
    val colour: String = "", val station: String = "kitchen",
    val tableName: String = "", val sinceMs: Long = 0,
    val servedBy: String = "" // only on history lines
)

@Serializable
data class PickupDto(
    val lines: List<PickupLineDto> = emptyList(),
    val served: List<PickupLineDto> = emptyList(), // last hour, newest first
    val serverTime: Long = 0
)

@Serializable
data class ReportLineDto(val name: String, val qty: Long, val value: Long)

@Serializable
data class CancelLogDto(val at: Long, val detail: String, val by: String = "")

@Serializable
data class ReportDto(
    val date: String = "",
    val ordersOpened: Long = 0, val ordersClosed: Long = 0, val ordersOpen: Long = 0,
    val soldQty: Long = 0, val soldTotal: Long = 0,
    val cancelledQty: Long = 0, val cancelledTotal: Long = 0,
    val sold: List<ReportLineDto> = emptyList(),
    val cancelled: List<ReportLineDto> = emptyList(),
    val waiters: List<ReportLineDto> = emptyList(),
    val cancelLog: List<CancelLogDto> = emptyList(),
    val kitchenPending: Long = 0, val devicesOnline: Int = 0
)

@Serializable
data class AdminTableDto(
    val id: Long, val name: String, val active: Boolean = true,
    val sort: Int = 0, val kind: String = "dine"
)

@Serializable
data class AuditEventDto(
    val id: Long, val at: Long, val by: String = "",
    val action: String = "", val detail: String = ""
)

@Serializable
data class NewTicketLine(val menuItemId: Long, val qty: Long, val note: String = "")
