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
// QR (everyday loanwords). Server error messages come from the Go side in
// English; the ones floor staff actually meet are mapped in serverErrText,
// anything rarer (mostly admin) shows as the server said it.
object L10n {
    var lang by mutableStateOf("en") // "en" | "hi" | "gu"
}

fun t(en: String, hi: String, gu: String): String =
    when (L10n.lang) { "hi" -> hi; "gu" -> gu; else -> en }

// Every API refusal passes through here on its way to a banner or a toast
// (Api.call), so a waiter never reads "can't move X from ready to served as
// waiter" in English. Unknown messages are left exactly as the server sent
// them — better English than a wrong translation.
fun serverErrText(msg: String): String {
    val m = msg.trim()
    return when {
        m.startsWith("payment exceeds") -> S.payMoreThanBill
        m.startsWith("discount must be") -> S.discountTooBig
        m.startsWith("order already closed") -> S.billAlreadyClosed
        m == "wrong PIN" -> S.wrongPin
        m.startsWith("too many wrong PINs") ->
            S.tooManyPins(m.substringAfterLast("wait ").removeSuffix("s").ifEmpty { "30" })
        m.startsWith("db busy") -> S.errBusy
        m.startsWith("item not found on any open order") -> S.errItemGone
        m.contains(" already took this bucket") -> S.cookingThis(m.substringBefore(" already took"))
        m.startsWith("finish your ") && m.contains("bucket first") -> S.oneBucketRule
        m.contains("goes out with the bhaji") -> S.errWithBhaji
        m.startsWith("only admin can cancel") -> S.errAdminCancel
        m == "already cancelled" -> S.errAlreadyCancelled
        m.endsWith(" is marked out of stock") -> S.errOutOfStock(m.removeSuffix(" is marked out of stock"))
        m.startsWith("menu item not found") -> S.errDishGone
        m.startsWith("can't move ") -> S.errStale
        m.contains(" already has order #") -> S.errTableTaken
        m == "open order not found" -> S.errOrderGone
        m.startsWith("server error") -> S.errServer
        m.startsWith("session expired") || m == "not logged in" -> S.errSession
        m.startsWith("your role (") && m.endsWith("not allowed to do this") -> S.errRole
        m.startsWith("only the chef who took this bucket") -> S.errNotYourBucket
        // Inventory (server/inventory.go). "only 2.5 kg of Cheese in hand":
        // more was written as used than there is.
        m.startsWith("only ") && m.endsWith(" in hand") && m.contains(" of ") ->
            S.invOnlyInHand(m.removePrefix("only ").substringBefore(" of "))
        m.endsWith(" is already in the inventory") -> S.invNameTaken(m.removeSuffix(" is already in the inventory"))
        m.endsWith(" has been removed from the inventory") ->
            S.invGone(m.removeSuffix(" has been removed from the inventory"))
        else -> msg
    }
}

object S {
    // ---- shared ----
    val back get() = t("Back", "पीछे", "પાછળ")
    val save get() = t("Save", "सेव करो", "સેવ કરો")
    val add get() = t("Add", "जोड़ो", "ઉમેરો")
    val logout get() = t("Logout", "बाहर निकलो", "બહાર નીકળો")
    fun logoutQ(who: String) = t("Log out $who?", "$who, बाहर निकलें?", "$who, બહાર નીકળવું છે?")
    val logoutBody get() = t(
        "The next person logs in with their own PIN.",
        "अगला व्यक्ति अपने PIN से अंदर आएगा।",
        "આગળની વ્યક્તિ પોતાના PIN થી અંદર આવશે."
    )
    val stayBtn get() = t("Stay", "रुको", "રહો")

    // ---- app updates (the app pulls and installs them by itself) ----
    val updateLater get() = t("Later", "बाद में", "પછી")
    val updateAllowTitle get() = t(
        "One-time permission", "एक बार की परमिशन", "એક વખતની પરમિશન"
    )
    val updateAllowBody get() = t(
        "Android asks once before an app may install its own updates. Turn it on for PavBhaji POS and you will never be asked again — every new version arrives on its own.",
        "कोई भी ऐप खुद के अपडेट लगाए, उससे पहले Android एक बार पूछता है. PavBhaji POS के लिए चालू कर दो — फिर हर नया वर्जन अपने आप आ जाएगा, दोबारा पूछेगा नहीं.",
        "કોઈ પણ એપ પોતાના અપડેટ લગાવે એ પહેલાં Android એક વાર પૂછે છે. PavBhaji POS માટે ચાલુ કરો — પછી દર નવું વર્ઝન જાતે આવી જશે, ફરી નહીં પૂછે."
    )
    val updateOpenSettings get() = t("Open settings", "सेटिंग खोलो", "સેટિંગ ખોલો")
    // The one offline banner. The server is on the internet, so WiFi or
    // mobile data both work — the words name the problem, not a cure.
    val reconnecting get() = t(
        "No internet — orders won't go through. Reconnecting by itself…",
        "इंटरनेट नहीं है — ऑर्डर आगे नहीं जाएँगे। अपने आप दोबारा जुड़ रहा है…",
        "ઇન્ટરનેટ નથી — ઓર્ડર આગળ નહીં જાય. જાતે ફરી જોડાઈ રહ્યું છે…"
    )
    // What a request that never reached the server says (toast / banner).
    val noNetwork get() = t(
        "No internet — try again",
        "इंटरनेट नहीं है — फिर कोशिश करो",
        "ઇન્ટરનેટ નથી — ફરી પ્રયત્ન કરો"
    )
    val noNetworkAll = setOf(
        "No internet — try again", "इंटरनेट नहीं है — फिर कोशिश करो", "ઇન્ટરનેટ નથી — ફરી પ્રયત્ન કરો"
    )
    val offlineShort get() = t("offline", "इंटरनेट नहीं", "ઇન્ટરનેટ નથી")
    val liveOn get() = t("connected", "जुड़ा है", "જોડાયેલું છે")
    val wrongPin get() = t("Wrong PIN", "गलत PIN", "ખોટો PIN")
    fun tooManyPins(secs: String) = t(
        "Too many wrong PINs — wait $secs seconds",
        "बहुत बार गलत PIN — $secs सेकंड रुको",
        "ઘણી વાર ખોટો PIN — $secs સેકન્ડ રાહ જુઓ"
    )
    val errBusy get() = t("Server is busy — try again", "सर्वर व्यस्त है — फिर कोशिश करो", "સર્વર વ્યસ્ત છે — ફરી પ્રયત્ન કરો")
    val errServer get() = t("Server problem — try again", "सर्वर में दिक्कत — फिर कोशिश करो", "સર્વરમાં તકલીફ — ફરી પ્રયત્ન કરો")
    val errItemGone get() = t(
        "That plate is no longer on an open order — the list will refresh",
        "यह प्लेट अब किसी खुले ऑर्डर में नहीं — लिस्ट ताज़ा हो रही है",
        "આ પ્લેટ હવે કોઈ ખુલ્લા ઓર્ડરમાં નથી — લિસ્ટ તાજી થાય છે"
    )
    val errStale get() = t(
        "Someone already changed this — the list will refresh",
        "किसी ने पहले ही बदल दिया — लिस्ट ताज़ा हो रही है",
        "કોઈએ પહેલેથી બદલી દીધું — લિસ્ટ તાજી થાય છે"
    )
    val errWithBhaji get() = t(
        "This goes out with the bhaji — nothing for this table is ready yet",
        "यह भाजी के साथ जाएगा — इस टेबल का अभी कुछ तैयार नहीं",
        "આ ભાજી સાથે જશે — આ ટેબલનું હજી કંઈ તૈયાર નથી"
    )
    val errAdminCancel get() = t(
        "The kitchen already started this — only admin can cancel it now",
        "किचन ने यह शुरू कर दिया — अब सिर्फ़ एडमिन रद्द कर सकता है",
        "રસોડાએ આ શરૂ કરી દીધું — હવે ફક્ત એડમિન રદ કરી શકે"
    )
    val errAlreadyCancelled get() = t("Already cancelled", "पहले ही रद्द है", "પહેલેથી રદ છે")
    val errSession get() = t(
        "Logged out on this phone — log in again with your PIN",
        "इस फ़ोन से लॉग आउट हो गए — अपने PIN से फिर अंदर आओ",
        "આ ફોનમાંથી લોગ આઉટ થયા — તમારા PIN થી ફરી અંદર આવો"
    )
    val errRole get() = t(
        "Your role can't do this",
        "आपका रोल यह नहीं कर सकता",
        "તમારો રોલ આ ન કરી શકે"
    )
    val errNotYourBucket get() = t(
        "Only the chef who took this bucket can do that",
        "यह सिर्फ़ वही शेफ़ कर सकता है जिसने बकेट लिया",
        "આ ફક્ત એ જ શેફ કરી શકે જેણે બકેટ લીધું"
    )
    fun errOutOfStock(dish: String) = t(
        "$dish is out of stock — take it out of the cart",
        "$dish खत्म है — कार्ट से हटाओ",
        "$dish ખલાસ છે — કાર્ટમાંથી કાઢો"
    )
    val errDishGone get() = t(
        "A dish in the cart was just removed from the menu",
        "कार्ट की एक डिश अभी मेनू से हटाई गई है",
        "કાર્ટની એક વાનગી હમણાં જ મેનૂમાંથી હટાવાઈ"
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
        "Pick a reason — it goes in the audit log.",
        "कारण चुनो — ऑडिट लॉग में जाएगा।",
        "કારણ પસંદ કરો — ઓડિટ લોગમાં જશે."
    )
    val reason get() = t("Reason", "कारण", "કારણ")
    val reasonExtra get() = t("Or type a reason", "या कारण लिखो", "અથવા કારણ લખો")
    // (what goes in the audit log, what the button says)
    fun cancelReasons() = listOf(
        "Customer changed mind" to t("Customer changed mind", "ग्राहक ने मना किया", "ગ્રાહકે ના પાડી"),
        "Wrong punch" to t("Wrong punch", "गलत पंच हुआ", "ખોટું પંચ થયું"),
        "Out of stock" to t("Out of stock", "माल खत्म", "માલ ખલાસ"),
        "Took too long" to t("Took too long", "बहुत देर लगी", "બહુ વાર લાગી"),
    )
    val cancelItem get() = t("Cancel item", "आइटम रद्द करो", "આઈટમ રદ કરો")

    // ---- connect (server setup; reached by a long press on the login logo) ----
    // The shop server is on the internet: no laptop, no black window. The
    // only thing a phone can be missing is its own internet.
    val searchingLaptop get() = t(
        "Looking for the shop server…",
        "दुकान का सर्वर ढूँढ रहे हैं…",
        "દુકાનનું સર્વર શોધી રહ્યાં છીએ…"
    )
    fun foundServers(n: Int) = t(
        "Found $n servers — tap yours:",
        "$n सर्वर मिले — अपना चुनो:",
        "$n સર્વર મળ્યા — તમારું પસંદ કરો:"
    )
    val notFound get() = t(
        "Couldn't reach the shop server.\nCheck this phone's internet (WiFi or mobile data) and search again.",
        "दुकान का सर्वर नहीं मिला।\nइस फ़ोन का इंटरनेट (WiFi या मोबाइल डेटा) देखो और फिर ढूँढो।",
        "દુકાનનું સર્વર ન મળ્યું.\nઆ ફોનનું ઇન્ટરનેટ (WiFi કે મોબાઈલ ડેટા) જુઓ અને ફરી શોધો."
    )
    val scanQr get() = t("📷  Scan the server QR", "📷  सर्वर की QR स्कैन करो", "📷  સર્વરનો QR સ્કેન કરો")
    val scanPrompt get() = t(
        "Point at the server's QR code",
        "सर्वर के QR कोड पर कैमरा रखो",
        "સર્વરના QR કોડ પર કૅમેરો રાખો"
    )
    val searchAgain get() = t("🔄  Search again", "🔄  फिर ढूँढो", "🔄  ફરી શોધો")
    val orTypeAddr get() = t(
        "or type the server address",
        "या सर्वर का पता टाइप करो",
        "અથવા સર્વરનું સરનામું ટાઈપ કરો"
    )
    val serverAddress get() = t("Server address", "सर्वर पता", "સર્વરનું સરનામું")
    val connect get() = t("Connect", "कनेक्ट करो", "કનેક્ટ કરો")
    val checking get() = t("Checking…", "जाँच रहे हैं…", "ચકાસી રહ્યાં છીએ…")
    val badAddress get() = t("That doesn't look like an address", "यह पता सही नहीं लगता", "આ સરનામું બરાબર નથી લાગતું")
    fun noServerAt(addr: String) = t(
        "No POS server answered at $addr. Check the address and this phone's internet.",
        "$addr पर कोई POS सर्वर नहीं मिला। पता और इस फ़ोन का इंटरनेट देखो।",
        "$addr પર કોઈ POS સર્વર ન મળ્યું. સરનામું અને આ ફોનનું ઇન્ટરનેટ જુઓ."
    )

    // ---- login ----
    val enterPin get() = t("Enter your PIN", "अपना PIN डालो", "તમારો PIN નાખો")

    // ---- waiter serve list ----
    val serveTitle get() = t("Serve", "परोसो", "પીરસો")
    val nothingToCarry get() = t("nothing to carry", "कुछ नहीं ले जाना", "કંઈ લઈ જવાનું નથી")
    fun toCarry(n: Int) = t(
        "$n to carry · by table, longest wait on top",
        "$n ले जाने हैं · टेबल के हिसाब से, सबसे पुराना ऊपर",
        "$n લઈ જવાના · ટેબલ પ્રમાણે, સૌથી જૂનું ઉપર"
    )
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
    // Tables header: dine-in tables only, numbers first.
    fun tablesBusy(busy: Int, total: Int) = t(
        "$busy/$total tables busy",
        "$busy/$total टेबल भरी",
        "$busy/$total ટેબલ ભરેલાં"
    )
    fun parcelsOpenN(n: Int) = t("$n parcel" + if (n == 1) "" else "s", "$n पार्सल", "$n પાર્સલ")
    val gridTables get() = t("TABLES", "टेबल", "ટેબલ")
    val gridParcel get() = t("🥡 PARCEL — counter orders", "🥡 पार्सल — काउंटर ऑर्डर", "🥡 પાર્સલ — કાઉન્ટર ઓર્ડર")
    val freeTable get() = t("free", "खाली", "ખાલી")
    val inKitchen get() = t("in kitchen", "किचन में", "રસોડામાં")
    fun readyPickup(n: Long) = t("$n READY", "$n तैयार", "$n તૈયાર")
    val collect get() = t("COLLECT", "पैसे लो", "પૈસા લો")
    // The cashier's two states on the tables grid.
    val settlePill get() = t("SETTLE", "हिसाब करो", "હિસાબ કરો")
    val runningPill get() = t("running", "चालू", "ચાલુ")

    // ---- table order ----
    val newOrder get() = t("new order", "नया ऑर्डर", "નવો ઓર્ડર")
    fun orderNo(n: Long) = t("order #$n", "ऑर्डर #$n", "ઓર્ડર #$n")
    val sentToKitchenToast get() = t("Sent to kitchen ✓", "किचन में भेज दिया ✓", "રસોડામાં મોકલ્યું ✓")
    val sentToKitchen get() = t("SENT TO KITCHEN", "किचन में भेजा हुआ", "રસોડામાં મોકલેલું")
    val newKot get() = t("NEW KOT — NOT SENT YET", "नया KOT — अभी भेजा नहीं", "નવો KOT — હજી મોકલ્યો નથી")
    val noteBtn get() = t("note", "नोट", "નોંધ")
    val oneLess get() = t("One less", "एक कम", "એક ઓછું")
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
    // Back pressed with a cart that hasn't gone to the kitchen.
    fun discardCartQ(items: String) = t(
        "Discard $items not sent yet?",
        "$items अभी भेजे नहीं — हटा दें?",
        "$items હજી મોકલી નથી — કાઢી નાખવી?"
    )
    val discardCartBody get() = t(
        "They haven't gone to the kitchen. Keep them to send, or discard and go back.",
        "ये किचन में नहीं गए हैं। भेजने हैं तो रखो, वरना हटाकर वापस जाओ।",
        "આ રસોડામાં ગઈ નથી. મોકલવી હોય તો રાખો, નહીં તો કાઢીને પાછા જાઓ."
    )
    val keepBtn get() = t("Keep", "रखो", "રાખો")
    val discardBtn get() = t("Discard", "हटा दो", "કાઢી નાખો")
    fun closeQ(table: String) = t("Close $table?", "$table बंद करें?", "$table બંધ કરવું?")
    fun totalLine(amt: String) = t("Total: $amt", "कुल: $amt", "કુલ: $amt")
    val notServedYet get() = t("Not served yet:", "अभी तक परोसा नहीं गया:", "હજી પીરસાયું નથી:")
    val closeAnyway get() = t("Close anyway", "फिर भी बंद करो", "તોય બંધ કરો")
    val closeAnywayHint get() = t(
        "\nThese are already on the bill and will count as served once the table closes. Don't want to give them? Get them cancelled first.",
        "\nये बिल में पहले से हैं और टेबल बंद होते ही परोसे हुए गिने जाएँगे। नहीं देनी हैं तो पहले रद्द करा दो।",
        "\nઆ બિલમાં પહેલેથી છે અને ટેબલ બંધ થતાં જ પીરસાયેલા ગણાશે. નથી આપવા? તો પહેલાં રદ કરાવી દો."
    )
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
    // There is no laptop any more: the server is in the cloud and keeps
    // exact time, so a gap almost always means this phone's clock is off.
    fun clockSkew(server: String, phone: String) = t(
        "⚠ Shop server time is $server, this phone says $phone. Set this phone to automatic date & time. Reports and the day's start follow the server.",
        "⚠ दुकान के सर्वर का समय $server है, यह फ़ोन $phone बता रहा है। फ़ोन में ऑटोमैटिक तारीख और समय चालू करो। रिपोर्ट और दिन की शुरुआत सर्वर के समय से चलती है।",
        "⚠ દુકાનના સર્વરનો સમય $server છે, આ ફોન $phone બતાવે છે. ફોનમાં ઓટોમેટિક તારીખ અને સમય ચાલુ કરો. રિપોર્ટ અને દિવસની શરૂઆત સર્વરના સમય પ્રમાણે ચાલે છે."
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
    fun archiveQ(name: String) = t("Archive $name?", "$name हटाएँ?", "$name હટાવવી?")
    val archiveBody get() = t(
        "It disappears from every menu and can't be ordered any more. Old bills keep it.",
        "यह हर मेनू से गायब हो जाएगा और फिर ऑर्डर नहीं हो पाएगा। पुराने बिलों में रहेगा।",
        "આ દરેક મેનૂમાંથી ગાયબ થઈ જશે અને ફરી ઓર્ડર નહીં થાય. જૂના બિલોમાં રહેશે."
    )
    val archiveBtn get() = t("Archive", "हटाओ", "હટાવો")
    val namePriceNeeded get() = t("Name and a price are needed", "नाम और दाम चाहिए", "નામ અને ભાવ જોઈએ")
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
    fun colourTooClose(names: String) = t(
        "⚠ Looks like $names — chefs may mix up the buckets. Pick one further away.",
        "⚠ $names जैसा दिखता है — शेफ़ बकेट में गड़बड़ कर सकते हैं। थोड़ा अलग रंग लो।",
        "⚠ $names જેવો દેખાય છે — શેફ બકેટમાં ગોટાળો કરી શકે. થોડો અલગ રંગ લો."
    )
    fun colourSameAs(names: String) = t(
        "Same colour as $names — they cook in one bucket.",
        "$names जैसा ही रंग — एक ही बकेट में बनेंगे।",
        "$names જેવો જ રંગ — એક જ બકેટમાં બનશે."
    )

    // ---- staff & tables admin ----
    val staffTitle get() = t("Staff & PINs", "स्टाफ़ और PIN", "સ્ટાફ અને PIN")
    val addStaff get() = t("+ staff", "+ स्टाफ़", "+ સ્ટાફ")
    val resetPin get() = t("reset PIN", "PIN बदलो", "PIN બદલો")
    val deactivated get() = t("deactivated", "बंद", "બંધ")
    val activeLbl get() = t("active", "चालू", "ચાલુ")
    val availableLbl get() = t("available", "उपलब्ध", "ઉપલબ્ધ")
    fun deactivateQ(who: String) = t("Switch off $who?", "$who को बंद करें?", "$who ને બંધ કરવા?")
    val deactivateBody get() = t(
        "They are logged out on every phone at once and their PIN stops working. Switch them back on any time.",
        "वे तुरंत हर फ़ोन से बाहर हो जाएँगे और उनका PIN काम नहीं करेगा। कभी भी वापस चालू कर सकते हो।",
        "એ તરત જ દરેક ફોનમાંથી બહાર થઈ જશે અને એમનો PIN કામ નહીં કરે. ક્યારેય પણ પાછા ચાલુ કરી શકો."
    )
    val deactivateBtn get() = t("Switch off", "बंद करो", "બંધ કરો")
    val newStaff get() = t("New staff member", "नया स्टाफ़", "નવો સ્ટાફ")
    val pin4 get() = t("PIN (4 digits)", "PIN (4 अंक)", "PIN (4 આંકડા)")
    val showPin get() = t("show", "दिखाओ", "બતાવો")
    val hidePin get() = t("hide", "छुपाओ", "છુપાવો")
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
    val preparingPrint get() = t("Getting the bill…", "बिल तैयार हो रहा…", "બિલ તૈયાર થાય છે…")
    val printFailed get() = t(
        "Couldn't load the bill to print — check internet and try again",
        "छापने के लिए बिल नहीं खुला — इंटरनेट देखो और फिर कोशिश करो",
        "છાપવા માટે બિલ ન ખૂલ્યું — ઇન્ટરનેટ જુઓ અને ફરી પ્રયત્ન કરો"
    )
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
    val noOpenOrder get() = t(
        "No open order on this table.",
        "इस टेबल पर कोई खुला ऑर्डर नहीं।",
        "આ ટેબલ પર કોઈ ખુલ્લો ઓર્ડર નથી."
    )
    fun paidHow(how: String) = t("Paid ✓ $how", "पेड ✓ $how", "પેડ ✓ $how")
    val billLoadFailed get() = t(
        "Couldn't load this bill's items.",
        "इस बिल के आइटम नहीं खुले।",
        "આ બિલની આઈટમ ન ખૂલી."
    )
    val retryBtn get() = t("Try again", "फिर कोशिश करो", "ફરી પ્રયત્ન કરો")
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
    val splitApply get() = t("split, rest cash", "बाँटो, बाकी कैश", "વહેંચો, બાકી કેશ")
    val splitEnterAmount get() = t(
        "Type the online amount first", "पहले ऑनलाइन रकम लिखो", "પહેલાં ઓનલાઈન રકમ લખો"
    )
    val splitTooBig get() = t(
        "Online amount can't be more than the bill",
        "ऑनलाइन रकम बिल से ज़्यादा नहीं हो सकती",
        "ઓનલાઈન રકમ બિલ કરતાં વધારે ન હોઈ શકે"
    )
    val paidTick get() = t("PAID ✓", "पेड ✓", "પેડ ✓")
    fun payLeft(amt: String) = t("$amt still to collect", "$amt अभी लेने बाकी", "$amt હજી લેવાના બાકી")
    val cashReceived get() = t("Cash received (₹)", "मिला कैश (₹)", "મળેલું કેશ (₹)")
    fun giveBack(amt: String) = t("Give back $amt", "$amt वापस दो", "$amt પાછા આપો")
    fun cashShort(amt: String) = t("$amt short", "$amt कम", "$amt ઓછા")
    val pickPayFirst get() = t(
        "Tap 💵 all cash or 📲 all online first",
        "पहले 💵 पूरा कैश या 📲 पूरा ऑनलाइन दबाओ",
        "પહેલાં 💵 આખું કેશ કે 📲 આખું ઓનલાઈન દબાવો"
    )
    val billCatchingUp get() = t(
        "New items just came in — the bill is updating…",
        "नए आइटम आए हैं — बिल अपडेट हो रहा है…",
        "નવી આઈટમ આવી — બિલ અપડેટ થાય છે…"
    )
    // The server's bill refusals, in the cashier's language (billErrorText).
    val payMoreThanBill get() = t(
        "The money entered is more than the bill now. Tap 💵 all cash or 📲 all online again.",
        "डाली गई रकम अब बिल से ज़्यादा है। 💵 पूरा कैश या 📲 पूरा ऑनलाइन फिर से दबाओ।",
        "નાખેલી રકમ હવે બિલ કરતાં વધારે છે. 💵 આખું કેશ કે 📲 આખું ઓનલાઈન ફરી દબાવો."
    )
    val discountTooBig get() = t(
        "Discount can't be more than the bill.",
        "छूट बिल से ज़्यादा नहीं हो सकती।",
        "છૂટ બિલ કરતાં વધારે ન હોઈ શકે."
    )
    val billAlreadyClosed get() = t(
        "This bill is already closed.",
        "यह बिल पहले ही बंद हो चुका है।",
        "આ બિલ પહેલેથી બંધ થઈ ગયું છે."
    )
    val cashierCloses get() = t(
        "The cashier makes the bill and closes this table.",
        "बिल बनाना और टेबल बंद करना कैशियर का काम है।",
        "બિલ બનાવવું અને ટેબલ બંધ કરવું કેશિયરનું કામ છે."
    )

    // ---- changing table (the customers moved) ----
    val moveBtn get() = t("⇄ Change table", "⇄ टेबल बदलो", "⇄ ટેબલ બદલો")
    fun moveWhereQ(from: String) = t(
        "Where did ${from} move?", "${from} वाले किस टेबल पर गए?", "${from} વાળા કયા ટેબલ પર ગયા?"
    )
    val moveHint get() = t(
        "Pick the new table — KOTs, kitchen, waiter list and bill all go with it.",
        "नई टेबल चुनो — KOT, किचन, वेटर और बिल सब साथ जाएंगे।",
        "નવું ટેબલ પસંદ કરો — KOT, રસોડું, વેઇટર અને બિલ બધું સાથે જશે."
    )
    fun joinTag(orderNo: Long) = t("#${orderNo} · join", "#${orderNo} · जोड़ो", "#${orderNo} · જોડો")
    fun moveBody(orderNo: Long, to: String) = t(
        "Order #${orderNo} moves to ${to}. Kitchen, waiter and bill will all show ${to}.",
        "ऑर्डर #${orderNo} ${to} पर चला जाएगा। किचन, वेटर और बिल सब ${to} दिखाएंगे।",
        "ઓર્ડર #${orderNo} ${to} પર જશે. રસોડું, વેઇટર અને બિલ બધે ${to} દેખાશે."
    )
    val moveGo get() = t("Change table", "टेबल बदलो", "ટેબલ બદલો")
    fun mergeQ(a: String, b: String) = t("Join ${a} + ${b}?", "${a} + ${b} जोड़ दें?", "${a} + ${b} જોડી દઈએ?")
    fun mergeBody(to: String, orderNo: Long) = t(
        "${to} already has order #${orderNo}. The two become one order — one bill.",
        "${to} पर पहले से ऑर्डर #${orderNo} है। दोनों एक ऑर्डर बन जाएंगे — एक ही बिल।",
        "${to} પર પહેલેથી ઓર્ડર #${orderNo} છે. બંને એક ઓર્ડર બની જશે — એક જ બિલ."
    )
    val mergeBtn get() = t("Join", "जोड़ो", "જોડો")
    fun movedToast(to: String) = t("Moved to ${to} ✓", "${to} पर शिफ्ट हो गया ✓", "${to} પર ખસેડ્યું ✓")
    fun mergedToast(to: String) = t("Joined into ${to} ✓", "${to} में जोड़ दिया ✓", "${to}માં જોડી દીધું ✓")
    val errTableTaken get() = t(
        "That table just got an order of its own — pick again.",
        "उस टेबल पर अभी-अभी ऑर्डर आया है — फिर से चुनो।",
        "એ ટેબલ પર હમણાં જ ઓર્ડર આવ્યો — ફરી પસંદ કરો."
    )
    val errOrderGone get() = t(
        "This order isn't open any more — it was closed or moved.",
        "यह ऑर्डर अब खुला नहीं है — बंद हो गया या शिफ्ट हो गया।",
        "આ ઓર્ડર હવે ખુલ્લો નથી — બંધ થયો અથવા ખસેડાયો."
    )
    val reportCash get() = t("💵 cash", "💵 कैश", "💵 કેશ")
    val reportOnline get() = t("📲 online", "📲 ऑनलाइन", "📲 ઓનલાઈન")
    val reportDiscounts get() = t("discounts", "छूट", "છૂટ")
    val navBills get() = t("Bills", "बिल", "બિલ")
    fun billsCount(n: Int) = t("$n bills", "$n बिल", "$n બિલ")
    val noBillsDay get() = t("No bills for this day.", "इस दिन का कोई बिल नहीं।", "આ દિવસનું કોઈ બિલ નથી.")
    fun closedLine(t2: String, who: String) = t("closed $t2 · $who", "$t2 बंद · $who", "$t2 બંધ · $who")

    // ---- audit log: the server's action codes, in words ----
    fun auditAction(code: String) = when (code) {
        "ticket" -> t("KOT sent", "KOT भेजा", "KOT મોકલ્યો")
        "parcel" -> t("parcel", "पार्सल", "પાર્સલ")
        "order:closed" -> t("table closed", "टेबल बंद", "ટેબલ બંધ")
        "order:moved" -> t("table changed", "टेबल बदली", "ટેબલ બદલ્યું")
        "order:merged" -> t("tables joined", "टेबल जोड़ी", "ટેબલ જોડ્યા")
        "batch:taken" -> t("bucket taken", "बकेट लिया", "બકેટ લીધું")
        "batch:plate-ready" -> t("plate ready", "प्लेट तैयार", "પ્લેટ તૈયાર")
        "batch:item-ready" -> t("dish ready", "डिश तैयार", "વાનગી તૈયાર")
        "batch:ready" -> t("bucket ready", "बकेट तैयार", "બકેટ તૈયાર")
        "batch:released" -> t("bucket left", "बकेट छोड़ा", "બકેટ છોડ્યું")
        "batch:sealed" -> t("bucket sealed", "बकेट सील", "બકેટ સીલ")
        "item:served" -> t("served", "परोसा", "પીરસ્યું")
        "item:cancelled" -> t("cancelled", "रद्द", "રદ")
        "bill:discount" -> t("discount", "छूट", "છૂટ")
        "bill-settings" -> t("bill settings", "बिल सेटिंग", "બિલ સેટિંગ")
        "login" -> t("logged in", "लॉग इन", "લોગ ઇન")
        "logout" -> t("logged out", "लॉग आउट", "લોગ આઉટ")
        "login:blocked" -> t("wrong PINs", "गलत PIN", "ખોટા PIN")
        "staff" -> t("staff", "स्टाफ़", "સ્ટાફ")
        "tables" -> t("tables", "टेबल", "ટેબલ")
        "menu:item" -> t("menu", "मेनू", "મેનૂ")
        "menu:category" -> t("menu category", "मेनू कैटेगरी", "મેનૂ કેટેગરી")
        "backup" -> t("backup", "बैकअप", "બેકઅપ")
        "seed" -> t("setup", "सेटअप", "સેટઅપ")
        "inventory:added" -> t("stock item added", "नया माल जोड़ा", "નવો માલ ઉમેર્યો")
        "inventory:edited" -> t("stock item edited", "माल बदला", "માલ બદલ્યો")
        "inventory:removed" -> t("stock item removed", "माल हटाया", "માલ કાઢ્યો")
        "inventory:in" -> t("stock in", "माल आया", "માલ આવ્યો")
        "inventory:out" -> t("stock used", "माल यूज़ हुआ", "માલ વપરાયો")
        "inventory:count" -> t("stock counted", "माल की गिनती", "માલની ગણતરી")
        else -> if (code.startsWith("item:")) statusLabel(code.removePrefix("item:")) else code
    }

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
    // Parcel card pills say the parcel's STATE: tapping a card opens its
    // order, not a hand-over or payment step, so the pill must not promise one.
    val allReadyPill get() = t("ALL READY ✓", "सब तैयार ✓", "બધું તૈયાર ✓")
    fun amountDue(amt: String) = t("$amt due", "$amt बाकी", "$amt બાકી")
    val sendParcel get() = t("Send parcel to kitchen 🍳", "पार्सल किचन में भेजो 🍳", "પાર્સલ રસોડામાં મોકલો 🍳")

    // ---- inventory: raw materials in hand (not the dish "khatam" switch) ----
    // Item names and units are the owner's data and stay as he typed them;
    // numbers and units read the same in every language ("2.5 kg").
    val inventoryTitle get() = t("Inventory", "इन्वेंटरी", "ઇન્વેન્ટરી")
    val invSubtitle get() = t(
        "Raw material: cheese, paneer, butter…",
        "कच्चा माल: चीज़, पनीर, बटर…",
        "કાચો માલ: ચીઝ, પનીર, બટર…"
    )
    val invAll get() = t("All stock", "सारा माल", "બધો માલ")
    val invOrderTitle get() = t("Tomorrow's order", "कल का ऑर्डर", "કાલનો ઓર્ડર")
    fun invOrderTab(n: Int) = "$invOrderTitle ($n)"
    fun invItemsN(n: Int) = t("$n item" + if (n == 1) "" else "s", "$n चीज़ें", "$n વસ્તુઓ")
    fun invLowN(n: Int) = t("$n low", "$n कम हैं", "$n ઓછી છે")
    fun invOutN(n: Int) = t("$n out", "$n खत्म", "$n ખલાસ")
    val invAllFull get() = t("All stock is full", "सब माल पूरा है", "બધો માલ પૂરો છે")
    val invEmpty get() = t(
        "No raw material added yet. Add cheese, paneer, pav… to see what is in hand and what to order for tomorrow.",
        "अभी कोई कच्चा माल नहीं जोड़ा। चीज़, पनीर, पाव… जोड़ो, फिर दिखेगा कितना माल है और कल क्या मँगाना है।",
        "હજી કોઈ કાચો માલ ઉમેર્યો નથી. ચીઝ, પનીર, પાવ… ઉમેરો, પછી દેખાશે કેટલો માલ છે અને કાલે શું મંગાવવાનું છે."
    )
    val invLow get() = t("LOW", "कम", "ઓછું")
    val invOut get() = t("OUT", "खत्म", "ખલાસ")
    val invOk get() = t("OK", "ठीक", "બરાબર")
    fun invNeed(q: String) = t("Need $q", "चाहिए $q", "જોઈએ $q")
    fun invOrderQ(q: String) = t("Order $q", "$q मँगाओ", "$q મંગાવો")
    fun invNow(q: String) = t("now $q", "अभी $q", "હાલ $q")
    fun invInHand(q: String) = t("In hand: $q", "अभी है: $q", "હાલ છે: $q")
    fun invToday(inQ: String, outQ: String) = t(
        "Today: $inQ in · $outQ used",
        "आज: $inQ आया · $outQ यूज़ हुआ",
        "આજે: $inQ આવ્યો · $outQ વપરાયું"
    )
    val invQty get() = t("How much", "कितना", "કેટલું")
    val invStockIn get() = t("Stock in", "माल आया", "માલ આવ્યો")
    val invUsed get() = t("Used", "यूज़ हुआ", "વપરાયું")
    val invSetCount get() = t("Set count", "गिनती सेट करो", "ગણતરી સેટ કરો")
    val invQtyFirst get() = t(
        "Enter how much first (like 2.5)",
        "पहले लिखो कितना (जैसे 2.5)",
        "પહેલાં લખો કેટલું (જેમ કે 2.5)"
    )
    fun invOnlyInHand(q: String) = t(
        "Only $q in hand — can't use more than that",
        "अभी सिर्फ़ $q है — इससे ज़्यादा यूज़ नहीं हो सकता",
        "હાલ ફક્ત $q છે — એનાથી વધારે વપરાય નહીં"
    )
    fun invNameTaken(name: String) = t(
        "$name is already in the list",
        "$name पहले से लिस्ट में है",
        "$name પહેલેથી લિસ્ટમાં છે"
    )
    // Another phone removed the item while this one still showed it.
    fun invGone(name: String) = t(
        "$name was removed from the list",
        "$name लिस्ट से हटा दिया गया है",
        "$name લિસ્ટમાંથી કાઢી નાખ્યું છે"
    )
    // What TalkBack says for the − and + beside the quantity field.
    val invLess get() = t("1 less", "1 कम", "1 ઓછું")
    val invMore get() = t("1 more", "1 ज़्यादा", "1 વધારે")
    val invAdd get() = t("Add item", "नया माल", "નવો માલ")
    val invEdit get() = t("Edit", "बदलो", "બદલો")
    val invHistory get() = t("History", "हिस्ट्री", "હિસ્ટ્રી")
    val invShare get() = t("Share list", "लिस्ट भेजो", "લિસ્ટ મોકલો")
    val invQuick get() = t("QUICK PICK", "जल्दी चुनो", "ઝડપથી પસંદ કરો")
    val invNameEg get() = t("Name (e.g. Cheese)", "नाम (जैसे Cheese)", "નામ (જેમ કે Cheese)")
    val invUnit get() = t("UNIT", "यूनिट", "યુનિટ")
    val invQtyNow get() = t("In hand now", "अभी कितना है", "હાલ કેટલું છે")
    val invPar get() = t("Needed daily", "रोज़ कितना चाहिए", "રોજ કેટલું જોઈએ")
    val invParHint get() = t(
        "When stock goes below the daily need, it shows in tomorrow's order. 0 = never.",
        "माल रोज़ की ज़रूरत से कम हुआ तो कल के ऑर्डर में दिखेगा। 0 = कभी नहीं।",
        "માલ રોજની જરૂરથી ઓછો થાય તો કાલના ઓર્ડરમાં દેખાશે. 0 = ક્યારેય નહીં."
    )
    val invNameNeeded get() = t("Write the name", "नाम लिखो", "નામ લખો")
    val invRemove get() = t("Remove from list", "लिस्ट से हटाओ", "લિસ્ટમાંથી કાઢો")
    fun invRemoveQ(name: String) = t("Remove $name?", "$name हटाएँ?", "$name કાઢવું છે?")
    val invRemoveBody get() = t(
        "It leaves the stock list and tomorrow's order. Its history stays.",
        "यह माल की लिस्ट और कल के ऑर्डर से हट जाएगा। इसकी हिस्ट्री रहेगी।",
        "આ માલની લિસ્ટ અને કાલના ઓર્ડરમાંથી નીકળી જશે. એની હિસ્ટ્રી રહેશે."
    )
    val invRemoveBtn get() = t("Remove", "हटाओ", "કાઢો")
    fun invRemoved(name: String) = t("$name removed", "$name हटाया", "$name કાઢ્યું")
    val invNoHistory get() = t("Nothing written yet", "अभी कुछ नहीं लिखा", "હજી કંઈ લખાયું નથી")
    fun invMoveIn(q: String) = t("+$q in", "+$q आया", "+$q આવ્યો")
    fun invMoveOut(q: String) = t("−$q used", "−$q यूज़ हुआ", "−$q વપરાયું")
    fun invMoveCount(q: String) = t("count $q", "गिनती $q", "ગણતરી $q")
    val invShareNothing get() = t("Nothing to order", "मँगाने को कुछ नहीं", "મંગાવવાનું કંઈ નથી")

    // ---- report / audit ----
    val logBtn get() = t("log", "लॉग", "લોગ")
    val todayBtn get() = t("today", "आज", "આજે")
    val todayWord get() = t("Today", "आज", "આજે")
    val yesterdayWord get() = t("Yesterday", "कल", "ગઈકાલે")
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
