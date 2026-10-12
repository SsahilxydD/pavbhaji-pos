package com.pavbhaji.pos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Quantities are whole thousandths on the wire; these are the only two places
// the app turns them into text and back.
class QtyTest {
    @Test fun fmt() {
        assertEquals("2.5", fmtQty(2500))
        assertEquals("12", fmtQty(12000))
        assertEquals("0.25", fmtQty(250))
        assertEquals("0", fmtQty(0))
        assertEquals("0.001", fmtQty(1))
        assertEquals("1.05", fmtQty(1050))
        assertEquals("1000000", fmtQty(QTY_MAX_MILLI))
        assertEquals("-2.5", fmtQty(-2500))
        assertEquals("2.5 kg", qtyUnit(2500, "kg"))
    }

    @Test fun parse() {
        assertEquals(5000L, parseQty("5"))
        assertEquals(2500L, parseQty("2.5"))
        assertEquals(250L, parseQty(".25"))
        assertEquals(125L, parseQty("0.125"))
        assertEquals(5000L, parseQty("5."))
        assertEquals(0L, parseQty("0"))
        assertEquals(2500L, parseQty(" 2.5 "))
        assertEquals(QTY_MAX_MILLI, parseQty("1000000"))
    }

    @Test fun parseRefuses() {
        assertNull(parseQty(""))
        assertNull(parseQty("."))
        assertNull(parseQty("-1"))
        assertNull(parseQty("1.2345"))
        assertNull(parseQty("1,5"))
        assertNull(parseQty("abc"))
        assertNull(parseQty("1000000.001"))
        assertNull(parseQty("12345678"))
    }

    @Test fun roundTrip() {
        for (v in listOf(0L, 1L, 250L, 999L, 1000L, 2500L, 12000L, 123456L)) {
            assertEquals(v, parseQty(fmtQty(v)))
        }
    }

    @Test fun cleanInput() {
        assertEquals("2.5", cleanQtyInput("2.5"))
        assertEquals("2.5", cleanQtyInput("2.5kg"))
        assertEquals("1.234", cleanQtyInput("1.23456"))
        assertEquals("1.23", cleanQtyInput("1.2.3"))
        assertEquals("1234567", cleanQtyInput("123456789"))
        assertEquals("", cleanQtyInput("-"))
    }

    @Test fun orderText() {
        val items = listOf(
            InventoryItemDto(1, "Cheese", "kg", qty = 2000, par = 5000, short = 3000, status = "low"),
            InventoryItemDto(2, "Butter", "kg", qty = 9000, par = 4000, short = 0),
            InventoryItemDto(3, "Pav", "ladi", qty = 0, par = 20000, short = 20000, status = "out")
        )
        assertEquals(
            "Tomorrow's order (12 Oct)\nCheese: 3 kg\nPav: 20 ladi",
            orderListText("Tomorrow's order", "12 Oct", items)
        )
    }
}
