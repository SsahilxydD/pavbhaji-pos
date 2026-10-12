package com.pavbhaji.pos.data

// Raw-material quantities travel as whole THOUSANDTHS of the unit (2.5 kg =
// 2500), the same way money travels as paise: no floating point anywhere
// between the phone and the database. The web screens format and parse the
// same way.

// The most the server takes for one quantity: 1,000,000 units.
const val QTY_MAX_MILLI = 1_000_000_000L

// 2500 → "2.5" · 12000 → "12" · 250 → "0.25". At most three decimals, no
// trailing zeros, no grouping (it is also what the quantity field holds).
fun fmtQty(milli: Long): String {
    val a = kotlin.math.abs(milli)
    val frac = (a % 1000).toString().padStart(3, '0').trimEnd('0')
    val s = (a / 1000).toString() + if (frac.isEmpty()) "" else ".$frac"
    return if (milli < 0) "-$s" else s
}

// "2.5 kg": a quantity is never shown without its unit.
fun qtyUnit(milli: Long, unit: String): String = fmtQty(milli) + " " + unit

// What was typed, in thousandths: a plain decimal with up to three places
// ("5", "2.5", ".25", "0.125"). null = not a quantity the server would take:
// empty, negative, a comma, a fourth decimal, or above 1,000,000 units.
fun parseQty(text: String): Long? {
    val s = text.trim()
    if (s.isEmpty() || s == "." || !Regex("""\d{0,7}(\.\d{0,3})?""").matches(s)) return null
    val whole = s.substringBefore('.').ifEmpty { "0" }.toLong()
    val frac = s.substringAfter('.', "").padEnd(3, '0').toLong()
    val v = whole * 1000 + frac
    return if (v > QTY_MAX_MILLI) null else v
}

// Keeps a quantity field parseable while it is being typed: digits and one
// dot, seven whole digits, three decimals. Anything else never lands.
fun cleanQtyInput(raw: String): String {
    val kept = raw.filter { it.isDigit() || it == '.' }
    val dot = kept.indexOf('.')
    if (dot < 0) return kept.take(7)
    return kept.substring(0, dot).take(7) + "." + kept.substring(dot + 1).replace(".", "").take(3)
}

// The text the manager sends to the supplier: one line per item that is
// below its daily level, with the amount to buy.
//   Tomorrow's order (12 Oct)
//   Cheese: 3 kg
//   Pav: 20 ladi
fun orderListText(title: String, day: String, items: List<InventoryItemDto>): String =
    buildString {
        append(title).append(" (").append(day).append(")")
        items.filter { it.short > 0 }.forEach {
            append('\n').append(it.name).append(": ").append(qtyUnit(it.short, it.unit))
        }
    }
