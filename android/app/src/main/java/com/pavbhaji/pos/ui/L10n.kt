package com.pavbhaji.pos.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// Every word the app shows, in English / Hindi / Gujarati. The language is a
// per-phone choice (picker on the login screen, saved in Session). `t()` reads
// a mutableState, so composables that show text recompose the moment the
// language changes — no restart, no Android locale plumbing.
//
// NOT translated on purpose: dish/table/staff names (owner's data), PIN, KOT,
// QR, WiFi (everyday loanwords), and server error messages (come from the Go
// side in English; rare, and the owner reads English).
object L10n {
    var lang by mutableStateOf("en") // "en" | "hi" | "gu"
}

fun t(en: String, hi: String, gu: String): String =
    when (L10n.lang) { "hi" -> hi; "gu" -> gu; else -> en }

object S {
    // ---- shared ----
    val back get() = t("Back", "पीछे", "પાછળ")
    val save get() = t("Save", "सेव करो", "સેવ કરો")
    val add get() = t("Add", "जोड़ो", "ઉમેરો")
    val logout get() = t("Logout", "बाहर निकलो", "બહાર નીકળો")
    val reconnecting get() = t(
        "reconnecting to server… check WiFi",
        "सर्वर से दोबारा जुड़ रहे हैं… WiFi देखो",
        "સર્વર સાથે ફરી જોડાઈ રહ્યાં છીએ… WiFi જુઓ"
    )
    fun itemsN(n: Long) = t("$n item" + if (n == 1L) "" else "s", "$n आइटम", "$n આઈટમ")
    val now get() = t("now", "अभी", "હમણાં")
    fun statusLabel(raw: String) = when (raw) {
        "pending" -> t("pending", "बाकी", "બાકી")
        "preparing" -> t("preparing", "बन रहा", "બની રહ્યું")
        "ready" -> t("ready", "तैयार", "તૈયાર")
        "served" -> t("served", "परोसा", "પીરસ્યું")
        "cancelled" -> t("cancelled", "रद्द", "રદ")
        else -> raw
    }
    fun roleLabel(raw: String) = when (raw) {
        "waiter" -> t("waiter", "वेटर", "વેઈટર")
        "manager" -> t("manager", "मैनेजर", "મેનેજર")
        "chef" -> t("chef", "शेफ़", "શેફ")
        "admin" -> t("admin", "एडमिन", "એડમિન")
        "cashier" -> t("cashier", "कैशियर", "કેશિયર")
        "parcel" -> t("parcel manager", "पार्सल मैनेजर", "પાર્સલ મેનેજર")
        else -> raw
    }
    val reasonRequired get() = t(
        "A reason is required — it goes in the audit log.",
        "कारण ज़रूरी है — ऑडिट लॉग में जाएगा।",
        "કારણ જરૂરી છે — ઓડિટ લોગમાં જશે."
    )
    val reason get() = t("Reason", "कारण", "કારણ")
    val cancelItem get() = t("Cancel item", "आइटम रद्द करो", "આઈટમ રદ કરો")

    // ---- connect ----
    val searchingLaptop get() = t(
        "Looking for the laptop on this WiFi…",
        "इस WiFi पर लैपटॉप ढूँढ रहे हैं…",
        "આ WiFi પર લેપટોપ શોધી રહ્યાં છીએ…"
    )
    fun foundServers(n: Int) = t(
        "Found $n servers — tap yours:",
        "$n सर्वर मिले — अपना चुनो:",
        "$n સર્વર મળ્યા — તમારું પસંદ કરો:"
    )
    val notFound get() = t(
        "Couldn't find the laptop by itself.\nIs it on, black window open, same WiFi?",
        "लैपटॉप अपने आप नहीं मिला।\nचालू है? काली विंडो खुली है? वही WiFi?",
        "લેપટોપ જાતે ન મળ્યું.\nચાલુ છે? કાળી વિન્ડો ખુલી છે? એ જ WiFi?"
    )
    val scanQr get() = t("📷  Scan QR on laptop screen", "📷  लैपटॉप की QR स्कैन करो", "📷  લેપટોપનો QR સ્કેન કરો")
    val scanPrompt get() = t(
        "Point at the QR on the laptop screen",
        "लैपटॉप की स्क्रीन पर QR पर कैमरा रखो",
        "લેપટોપ સ્ક્રીન પરના QR પર કૅમેરો રાખો"
    )
    val searchAgain get() = t("🔄  Search again", "🔄  फिर ढूँढो", "🔄  ફરી શોધો")
    val orTypeAddr get() = t(
        "or type the address from the laptop",
        "या लैपटॉप का पता टाइप करो",
        "અથવા લેપટોપનું સરનામું ટાઈપ કરો"
    )
    val serverAddress get() = t("Server address", "सर्वर पता", "સર્વરનું સરનામું")
    val connect get() = t("Connect", "कनेक्ट करो", "કનેક્ટ કરો")
    val badAddress get() = t("That doesn't look like an address", "यह पता सही नहीं लगता", "આ સરનામું બરાબર નથી લાગતું")
    fun noServerAt(addr: String) = t(
        "No server at $addr. Same WiFi? Black window open on the laptop?",
        "$addr पर सर्वर नहीं मिला। वही WiFi? लैपटॉप पर काली विंडो खुली है?",
        "$addr પર સર્વર ન મળ્યું. એ જ WiFi? લેપટોપ પર કાળી વિન્ડો ખુલી છે?"
    )

    // ---- login ----
    val enterPin get() = t("Enter your PIN", "अपना PIN डालो", "તમારો PIN નાખો")
    val changeServer get() = t("change", "बदलो", "બદલો")

    // ---- waiter serve list ----
    val serveTitle get() = t("Serve", "परोसो", "પીરસો")
    val nothingToCarry get() = t("nothing to carry", "कुछ नहीं ले जाना", "કંઈ લઈ જવાનું નથી")
    fun toCarry(n: Int) = t("$n to carry · oldest on top", "$n ले जाने हैं · सबसे पुराना ऊपर", "$n લઈ જવાના · સૌથી જૂનું ઉપર")
    fun toCarryTables(n: Int, tables: Int) = t(
        "$n to carry · $tables tables · oldest on top",
        "$n ले जाने हैं · $tables टेबल · सबसे पुराना ऊपर",
        "$n લઈ જવાના · $tables ટેબલ · સૌથી જૂનું ઉપર"
    )
    fun toCarryOneTable(n: Int) = t("$n to carry · 1 table", "$n ले जाने हैं · 1 टेबल", "$n લઈ જવાના · 1 ટેબલ")
    val allCarried get() = t(
        "All carried out 🎉 New plates appear here the moment they're ready.",
        "सब पहुँच गया 🎉 प्लेट तैयार होते ही यहाँ दिखेगी।",
        "બધું પહોંચી ગયું 🎉 પ્લેટ તૈયાર થતાં જ અહીં દેખાશે."
    )
    val justCarry get() = t("✋ just carry", "✋ बस ले जाओ", "✋ ખાલી લઈ જાઓ")
    // Extra pav & co: on the list so nobody forgets it, but held till the bhaji is ready.
    fun waitsForBhaji(n: Long) = t(
        "⏳ goes with the bhaji · $n still cooking",
        "⏳ भाजी के साथ जाएगा · $n अभी बन रही है",
        "⏳ ભાજી સાથે જશે · $n હજી બની રહી છે"
    )
    val readyTitle get() = t("Ready", "तैयार", "તૈયાર")
    val servedBtn get() = t("Served ✓", "परोसा ✓", "પીરસ્યું ✓")
    val servedSection get() = t("SERVED — for checking", "परोसा हुआ — देखने के लिए", "પીરસેલું — જોવા માટે")
    val serveNow get() = t("SERVE NOW", "अभी परोसो", "હમણાં પીરસો")

    // ---- tables / manager ----
    val tablesTitle get() = t("Tables", "टेबल", "ટેબલ")
    fun occupiedSub(who: String, busy: Int, total: Int) = t(
        "$who · $busy of $total occupied",
        "$who · $total में से $busy भरे",
        "$who · $total માંથી $busy ભરેલા"
    )
    val gridTables get() = t("TABLES", "टेबल", "ટેબલ")
    val gridParcel get() = t("🥡 PARCEL — counter orders", "🥡 पार्सल — काउंटर ऑर्डर", "🥡 પાર્સલ — કાઉન્ટર ઓર્ડર")
    val freeTable get() = t("free", "खाली", "ખાલી")
    val inKitchen get() = t("in kitchen", "किचन में", "રસોડામાં")
    fun readyPickup(n: Long) = t("$n READY — pick up", "$n तैयार — ले जाओ", "$n તૈયાર — લઈ જાઓ")
    val collect get() = t("COLLECT", "पैसे लो", "પૈસા લો")
    fun collectAmt(amt: String) = t("COLLECT $amt", "$amt लो", "$amt લો")

    // ---- table order ----
    val newOrder get() = t("new order", "नया ऑर्डर", "નવો ઓર્ડર")
    fun orderNo(n: Long) = t("order #$n", "ऑर्डर #$n", "ઓર્ડર #$n")
    val sentToKitchenToast get() = t("Sent to kitchen ✓", "किचन में भेज दिया ✓", "રસોડામાં મોકલ્યું ✓")
    val sentToKitchen get() = t("SENT TO KITCHEN", "किचन में भेजा हुआ", "રસોડામાં મોકલેલું")
    val newKot get() = t("NEW KOT — NOT SENT YET", "नया KOT — अभी भेजा नहीं", "નવો KOT — હજી મોકલ્યો નથી")
    val noteBtn get() = t("note", "नोट", "નોંધ")
    val addItems get() = t("ADD ITEMS", "आइटम जोड़ो", "વસ્તુઓ ઉમેરો")
    val sending get() = t("Sending…", "भेज रहे हैं…", "મોકલી રહ્યાં છીએ…")
    fun tryAgain(items: String) = t("Try again — $items", "फिर कोशिश करो — $items", "ફરી પ્રયત્ન કરો — $items")
    fun sendToKitchen(items: String) = t("Send to kitchen 🍳  $items", "किचन में भेजो 🍳  $items", "રસોડામાં મોકલો 🍳  $items")
    val closeTable get() = t("Close table", "टेबल बंद करो", "ટેબલ બંધ કરો")
    fun closeAmt(amt: String) = t("Close · $amt", "बंद करो · $amt", "બંધ કરો · $amt")
    val tapToStart get() = t(
        "Tap items above to start this table's order",
        "इस टेबल का ऑर्डर शुरू करने को ऊपर आइटम दबाओ",
        "આ ટેબલનો ઓર્ડર શરૂ કરવા ઉપર આઈટમ દબાવો"
    )
    fun cancelQ(qty: Long, name: String) = t("Cancel $qty× $name?", "$qty× $name रद्द करें?", "$qty× $name રદ કરવું?")
    fun closeQ(table: String) = t("Close $table?", "$table बंद करें?", "$table બંધ કરવું?")
    fun totalLine(amt: String) = t("Total: $amt", "कुल: $amt", "કુલ: $amt")
    val collectFirst get() = t("Collect payment before closing.", "बंद करने से पहले पैसे ले लो।", "બંધ કરતાં પહેલાં પૈસા લઈ લો.")
    val cantClose get() = t("Can't close yet", "अभी बंद नहीं हो सकता", "હજી બંધ ન થાય")
    val cantCloseHint get() = t(
        "\nOnly waiters mark plates served, one by one as they carry them. Wait for that — or cancel items with a reason.",
        "\nसिर्फ़ वेटर ही प्लेट परोसा मार्क करते हैं, ले जाते हुए एक-एक करके। उसका इंतज़ार करो — या कारण देकर आइटम रद्द करो।",
        "\nફક્ત વેઈટર જ પ્લેટ પીરસી માર્ક કરે છે, લઈ જતાં એક-એક કરીને. એની રાહ જુઓ — અથવા કારણ આપી આઈટમ રદ કરો."
    )
    fun noteFor(name: String) = t("Note for $name", "$name के लिए नोट", "$name માટે નોંધ")
    val notePlaceholder get() = t("less spicy, no onion…", "कम तीखा, बिना प्याज़…", "ઓછું તીખું, ડુંગળી વગર…")
    fun varietiesN(n: Int) = t("$n varieties", "$n तरह", "$n જાત")
    val outOfStock get() = t("out of stock", "खत्म", "ખલાસ")

    // ---- kitchen ----
    val kitchenTitle get() = t("Kitchen", "किचन", "રસોડું")
    fun bucketsSub(n: Int, who: String) = t("$n buckets · $who", "$n बकेट · $who", "$n બકેટ · $who")
    val noBuckets get() = t("No buckets — nothing to cook 👨‍🍳", "कोई बकेट नहीं — कुछ नहीं पकाना 👨‍🍳", "કોઈ બકેટ નથી — કંઈ રાંધવાનું નથી 👨‍🍳")
    fun yoursChip(m: Long) = t("🔒 YOURS · ${m}m", "🔒 तुम्हारा · ${m}m", "🔒 તમારું · ${m}m")
    val fullChip get() = t("FULL", "भरा", "ભરેલું")
    val fillingChip get() = t("FILLING", "भर रहा", "ભરાય છે")
    val hintLocked get() = t("locked — pick another colour", "बंद — दूसरा रंग लो", "બંધ — બીજો રંગ લો")
    val hintMine get() = t("tap to open · finish when done", "खोलने को दबाओ · होने पर पूरा करो", "ખોલવા દબાવો · થાય એટલે પૂરું કરો")
    val hintAfterYours get() = t("after your bucket", "पहले अपना बकेट", "પહેલા તમારું બકેટ")
    val hintSealed get() = t("sealed — take it", "सील — इसे लो", "સીલ — આ લો")
    val hintTapTables get() = t("tap for tables", "टेबल देखने को दबाओ", "ટેબલ જોવા દબાવો")
    fun bucketReady(qty: Long) = t("Bucket ready ✓ — all $qty plates", "बकेट तैयार ✓ — सभी $qty प्लेट", "બકેટ તૈયાર ✓ — બધી $qty પ્લેટ")
    fun restReady(qty: Long) = t("Rest ready ✓ — $qty plates", "बाकी तैयार ✓ — $qty प्लेट", "બાકી તૈયાર ✓ — $qty પ્લેટ")
    val tickHint get() = t(
        "tap each plate the moment it is done — it goes straight to the pass, the rest stay on the tawa",
        "हर प्लेट बनते ही उसे दबाओ — वह सीधी पास पर चली जाएगी, बाकी तवे पर ही रहेंगी",
        "દરેક પ્લેટ થાય એટલે તેને દબાવો — તે સીધી પાસ પર જશે, બાકી તવા પર જ રહેશે"
    )
    fun plateLeft(left: Long, total: Long) = t(
        "$left of $total left",
        "$total में से $left बाकी",
        "$total માંથી $left બાકી"
    )
    val platesAllOut get() = t("all out ✓", "सब बाहर ✓", "બધી બહાર ✓")
    val platePass get() = t("at the pass ✓", "पास पर ✓", "પાસ પર ✓")
    val leaveBucket get() = t(
        "Leave bucket ↩ — come back later",
        "बकेट छोड़ो ↩ — बाद में आना",
        "બકેટ છોડો ↩ — પછી આવજો"
    )
    fun readyTicked(qty: Long) = t(
        "Ticked ready ✓ — $qty plates out",
        "टिक की हुई तैयार ✓ — $qty प्लेट बाहर",
        "ટિક કરેલી તૈયાર ✓ — $qty પ્લેટ બહાર"
    )
    val donePlates get() = t("gone to the pass ✓", "पास पर चली गई ✓", "પાસ પર ગઈ ✓")
    fun cookingThis(who: String) = t(
        "🔒 $who is cooking this — pick another colour",
        "🔒 $who यह बना रहा है — दूसरा रंग लो",
        "🔒 $who આ બનાવે છે — બીજો રંગ લો"
    )
    val oneBucketRule get() = t(
        "🔒 finish your bucket first — one bucket per chef",
        "🔒 पहले अपना बकेट पूरा करो — एक शेफ़, एक बकेट",
        "🔒 પહેલા તમારું બકેટ પૂરું કરો — એક શેફ, એક બકેટ"
    )
    val takeBucket get() = t("Take this bucket ▶", "यह बकेट लो ▶", "આ બકેટ લો ▶")

    // ---- admin ----
    val adminTitle get() = t("Admin", "एडमिन", "એડમિન")
    val today get() = t("TODAY", "आज", "આજે")
    fun nOrders(n: Long) = t("$n orders", "$n ऑर्डर", "$n ઓર્ડર")
    fun nOpen(n: Long) = t("$n open", "$n खुले", "$n ખુલ્લા")
    fun nCooking(n: Long) = t("$n cooking", "$n बन रहे", "$n બની રહ્યા")
    val navFloor get() = t("Floor", "फ्लोर", "ફ્લોર")
    val navMenu get() = t("Menu & colours", "मेनू और रंग", "મેનૂ અને રંગ")
    val navStaff get() = t("Staff", "स्टाफ़", "સ્ટાફ")
    val navReport get() = t("Day report", "दिन की रिपोर्ट", "દિવસનો રિપોર્ટ")
    fun clockSkew(laptop: String, phone: String) = t(
        "⚠ Laptop time ($laptop) differs from this phone ($phone). Fix the laptop clock in Windows settings — reports and the day boundary trust it.",
        "⚠ लैपटॉप का समय ($laptop) इस फ़ोन ($phone) से अलग है। Windows सेटिंग्स में लैपटॉप की घड़ी ठीक करो — रिपोर्ट इसी पर चलती है।",
        "⚠ લેપટોપનો સમય ($laptop) આ ફોન ($phone) થી અલગ છે. Windows સેટિંગ્સમાં લેપટોપની ઘડિયાળ ઠીક કરો — રિપોર્ટ એના પર ચાલે છે."
    )
    val savingBackup get() = t("saving backup…", "बैकअप सेव हो रहा…", "બેકઅપ સેવ થાય છે…")
    val saveBackup get() = t("save a backup to this phone", "इस फ़ोन में बैकअप सेव करो", "આ ફોનમાં બેકઅપ સેવ કરો")
    fun backupSaved(where: String) = t("Backup saved: $where", "बैकअप सेव हुआ: $where", "બેકઅપ સેવ થયો: $where")

    // ---- menu admin ----
    val addCategory get() = t("+ category", "+ कैटेगरी", "+ કેટેગરી")
    val addItem get() = t("+ item", "+ आइटम", "+ આઈટમ")
    val name get() = t("Name", "नाम", "નામ")
    val priceRs get() = t("Price (₹)", "दाम (₹)", "ભાવ (₹)")
    val tawaCap get() = t("Tawa cap", "तवा कैप", "તવા કેપ")
    val sealAfter get() = t("Seal after (min)", "कितने मिनट में सील", "કેટલી મિનિટે સીલ")
    val archiveItem get() = t("Archive item (hide everywhere)", "आइटम हटाओ (हर जगह छुपेगा)", "આઈટમ હટાવો (બધે છુપાશે)")
    fun newItemIn(cat: String) = t("New item — $cat", "नया आइटम — $cat", "નવી આઈટમ — $cat")
    val newItem get() = t("New item", "नया आइटम", "નવી આઈટમ")
    val newCategory get() = t("New category", "नई कैटेगरी", "નવી કેટેગરી")
    val stationKitchen get() = t("🍳 kitchen", "🍳 किचन", "🍳 રસોડું")
    val stationCounter get() = t("✋ counter", "✋ काउंटर", "✋ કાઉન્ટર")
    val counterTag get() = t("✋ counter", "✋ काउंटर", "✋ કાઉન્ટર")
    val withFoodTag get() = t("⏳ with the bhaji", "⏳ भाजी के साथ", "⏳ ભાજી સાથે")
    val withFoodToggle get() = t(
        "Carry out with the table's bhaji",
        "टेबल की भाजी के साथ ले जाओ",
        "ટેબલની ભાજી સાથે લઈ જાઓ"
    )
    val withFoodHint get() = t(
        "Extra pav, butter, onion — held at the counter till the bhaji is ready. Leave off for drinks and water.",
        "एक्स्ट्रा पाव, बटर, प्याज़ — भाजी तैयार होने तक काउंटर पर रुकेगा। ठंडे पीने की चीज़ों के लिए बंद रखो।",
        "એક્સ્ટ્રા પાવ, બટર, ડુંગળી — ભાજી તૈયાર થાય ત્યાં સુધી કાઉન્ટર પર રોકાશે. પીણાં માટે બંધ રાખો."
    )
    val bucketColour get() = t("Bucket colour", "बकेट का रंग", "બકેટનો રંગ")
    val orMixOwn get() = t("or mix your own", "या अपना रंग बनाओ", "અથવા તમારો રંગ બનાવો")

    // ---- staff & tables admin ----
    val staffTitle get() = t("Staff & PINs", "स्टाफ़ और PIN", "સ્ટાફ અને PIN")
    val addStaff get() = t("+ staff", "+ स्टाफ़", "+ સ્ટાફ")
    val resetPin get() = t("reset PIN", "PIN बदलो", "PIN બદલો")
    val deactivated get() = t("deactivated", "बंद", "બંધ")
    val newStaff get() = t("New staff member", "नया स्टाफ़", "નવો સ્ટાફ")
    val pin4 get() = t("PIN (4 digits)", "PIN (4 अंक)", "PIN (4 આંકડા)")
    val namePinNeeded get() = t("name + 4-digit PIN needed", "नाम + 4 अंकों का PIN चाहिए", "નામ + 4 આંકડાનો PIN જોઈએ")
    val min4digits get() = t("at least 4 digits", "कम से कम 4 अंक", "ઓછામાં ઓછા 4 આંકડા")
    val setPin get() = t("Set PIN", "PIN सेट करो", "PIN સેટ કરો")
    fun newPinFor(who: String) = t("New PIN for $who", "$who का नया PIN", "$who નો નવો PIN")
    val tablesSetup get() = t("Tables setup", "टेबल सेटअप", "ટેબલ સેટઅપ")
    val addTable get() = t("+ table", "+ टेबल", "+ ટેબલ")
    val rename get() = t("rename", "नाम बदलो", "નામ બદલો")
    val parcelTag get() = t("🥡 parcel", "🥡 पार्सल", "🥡 પાર્સલ")
    val newTable get() = t("New table", "नया टेबल", "નવું ટેબલ")
    val tableNameEg get() = t("Name (e.g. T11 or P4)", "नाम (जैसे T11 या P4)", "નામ (જેમ કે T11 કે P4)")
    val dineIn get() = t("🍽 dine-in", "🍽 डाइन-इन", "🍽 ડાઈન-ઈન")
    fun renameX(x: String) = t("Rename $x", "$x का नाम बदलो", "$x નું નામ બદલો")

    // ---- cashier / bill ----
    val billTitle get() = t("Bill", "बिल", "બિલ")
    val printBill get() = t("🖨  Print bill", "🖨  बिल छापो", "🖨  બિલ છાપો")
    val billItem get() = t("Item", "आइटम", "આઈટમ")
    val billQty get() = t("Qty", "मात्रा", "જથ્થો")
    val billRate get() = t("Rate", "दाम", "ભાવ")
    val billAmount get() = t("Amount", "रकम", "રકમ")
    val billRunning get() = t(
        "running bill — order is still open, it can grow",
        "चालू बिल — ऑर्डर अभी खुला है, बढ़ सकता है",
        "ચાલુ બિલ — ઓર્ડર હજી ખુલ્લો છે, વધી શકે છે"
    )
    fun billStatusLine(pending: Long, ready: Long) = t(
        "$pending cooking · $ready ready to serve",
        "$pending बन रहे · $ready परोसने को तैयार",
        "$pending બની રહ્યા · $ready પીરસવા તૈયાર"
    )
    val tapOccupied get() = t(
        "Tap an occupied table to see its bill",
        "बिल देखने के लिए भरा टेबल दबाओ",
        "બિલ જોવા માટે ભરેલું ટેબલ દબાવો"
    )
    val subtotalRow get() = t("Subtotal", "सबटोटल", "સબટોટલ")
    val discountRow get() = t("Discount", "छूट", "છૂટ")
    val addDiscount get() = t("＋ discount", "＋ छूट", "＋ છૂટ")
    val discountAmt get() = t("Discount (₹)", "छूट (₹)", "છૂટ (₹)")
    val discountNoteLbl get() = t("Reason (optional)", "कारण (ज़रूरी नहीं)", "કારણ (જરૂરી નથી)")
    val paymentSection get() = t("PAYMENT", "पेमेंट", "પેમેન્ટ")
    val cashLbl get() = t("Cash", "कैश", "કેશ")
    val onlineLbl get() = t("Online / UPI", "ऑनलाइन / UPI", "ઓનલાઈન / UPI")
    val allCashChip get() = t("💵 all cash", "💵 पूरा कैश", "💵 આખું કેશ")
    val allOnlineChip get() = t("📲 all online", "📲 पूरा ऑनलाइन", "📲 આખું ઓનલાઈન")
    val onlineAmtLbl get() = t("Online amount (₹)", "ऑनलाइन रकम (₹)", "ઓનલાઈન રકમ (₹)")
    val splitApply get() = t("✓ split, rest cash", "✓ बाकी कैश", "✓ બાકી કેશ")
    val paidTick get() = t("PAID ✓", "पेड ✓", "પેડ ✓")
    fun payLeft(amt: String) = t("$amt not assigned yet", "$amt अभी बाकी", "$amt હજી બાકી")
    val cashierCloses get() = t(
        "The cashier makes the bill and closes this table.",
        "बिल बनाना और टेबल बंद करना कैशियर का काम है।",
        "બિલ બનાવવું અને ટેબલ બંધ કરવું કેશિયરનું કામ છે."
    )
    val reportCash get() = t("💵 cash", "💵 कैश", "💵 કેશ")
    val reportOnline get() = t("📲 online", "📲 ऑनलाइन", "📲 ઓનલાઈન")
    val reportDiscounts get() = t("discounts", "छूट", "છૂટ")
    val navBills get() = t("Bills", "बिल", "બિલ")
    fun billsCount(n: Int) = t("$n bills", "$n बिल", "$n બિલ")
    val noBillsDay get() = t("No bills for this day.", "इस दिन का कोई बिल नहीं।", "આ દિવસનું કોઈ બિલ નથી.")
    fun closedLine(t2: String, who: String) = t("closed $t2 · $who", "$t2 बंद · $who", "$t2 બંધ · $who")

    // ---- audit filters ----
    fun auditFilter(key: String) = when (key) {
        "orders" -> t("orders", "ऑर्डर", "ઓર્ડર")
        "kitchen" -> t("kitchen", "किचन", "રસોડું")
        "serving" -> t("serving", "परोसना", "પીરસવું")
        "cancel" -> t("cancelled", "रद्द", "રદ")
        "billing" -> t("billing", "बिलिंग", "બિલિંગ")
        "staff" -> t("staff", "स्टाफ़", "સ્ટાફ")
        else -> t("all", "सब", "બધા")
    }

    // ---- parcel manager ----
    val parcelsTitle get() = t("Parcels", "पार्सल", "પાર્સલ")
    val newParcel get() = t("＋  New parcel", "＋  नया पार्सल", "＋  નવો પાર્સલ")
    val customerName get() = t("Customer name", "ग्राहक का नाम", "ગ્રાહકનું નામ")
    val customerPhone get() = t("Phone (optional)", "फ़ोन (ज़रूरी नहीं)", "ફોન (જરૂરી નથી)")
    val nameNeeded get() = t("customer name is required", "ग्राहक का नाम ज़रूरी है", "ગ્રાહકનું નામ જરૂરી છે")
    fun parcelPlaced(n: Long) = t("Parcel order #$n placed ✓", "पार्सल ऑर्डर #$n लग गया ✓", "પાર્સલ ઓર્ડર #$n લાગી ગયો ✓")
    val noParcels get() = t(
        "No parcels right now.\nStart one with the button below.",
        "अभी कोई पार्सल नहीं।\nनीचे के बटन से शुरू करो।",
        "હમણાં કોઈ પાર્સલ નથી.\nનીચેના બટનથી શરૂ કરો."
    )
    fun openParcels(n: Int) = t("$n open", "$n खुले", "$n ખુલ્લા")
    val allReadyPill get() = t("ALL READY — hand over", "सब तैयार — दे दो", "બધું તૈયાર — આપી દો")
    val sendParcel get() = t("Send parcel to kitchen 🍳", "पार्सल किचन में भेजो 🍳", "પાર્સલ રસોડામાં મોકલો 🍳")

    // ---- report / audit ----
    val logBtn get() = t("log", "लॉग", "લોગ")
    val todayBtn get() = t("today", "आज", "આજે")
    val salesServed get() = t("sales (served)", "बिक्री (परोसा)", "વેચાણ (પીરસેલું)")
    val ordersCard get() = t("orders", "ऑर्डर", "ઓર્ડર")
    val cancelledCard get() = t("cancelled", "रद्द", "રદ")
    fun stillOpen(n: Long) = t(
        "⚠ $n order(s) still open for this date",
        "⚠ इस दिन के $n ऑर्डर अभी खुले हैं",
        "⚠ આ દિવસના $n ઓર્ડર હજી ખુલ્લા છે"
    )
    val soldSection get() = t("SOLD (served items)", "बिका (परोसे आइटम)", "વેચાયું (પીરસેલી વસ્તુઓ)")
    val nothingYet get() = t("nothing yet", "अभी कुछ नहीं", "હજી કંઈ નથી")
    val totalRow get() = t("TOTAL", "कुल", "કુલ")
    val byWaiter get() = t("BY WAITER", "वेटर के हिसाब से", "વેઈટર પ્રમાણે")
    val cancelledSection get() = t("CANCELLED", "रद्द", "રદ")
    val noneParty get() = t("none 🎉", "कोई नहीं 🎉", "કોઈ નહીં 🎉")
    val auditTitle get() = t("Audit log", "ऑडिट लॉग", "ઓડિટ લોગ")
    val system get() = t("system", "सिस्टम", "સિસ્ટમ")
    val loading get() = t("loading…", "लोड हो रहा…", "લોડ થાય છે…")
    val loadOlder get() = t("Load older", "पुराने देखो", "જૂના જુઓ")
}
