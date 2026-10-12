/* Raw-material inventory ("in-house stock": cheese, paneer, pav) — ONE view
   for both browser screens. /admin mounts it as its Inventory tab, /manager
   opens it full screen from the tables grid. Each page hands over its own
   api(), toast() and language, so a refusal, a dead network and a language
   switch behave here exactly as they do on the rest of that page.

   It is separate from the Stock tab on purpose: Stock is the dish-level
   khatam switch, this is what is on the shelf and what to buy for tomorrow.

   Quantities are thousandths of the unit on the wire (2.5 kg = 2500), the
   way money is paise. The server does every sum; this file only shows them.

   The look is namespaced (.inv-*) and built from the colour tokens both
   skins define (pos.css and the admin page's own sheet), so it needs no
   class from either. Dialogs reuse the page's .modal/.box, which is a bottom
   sheet that rises above the phone keyboard on /manager and a centred box
   on /admin. */
(function () {
'use strict';

var CSS = [
  '.inv-hd{display:flex;flex-direction:column;gap:2px;margin-bottom:12px}',
  '.inv-hd b{font-size:18px}',
  '.inv-hd span{color:var(--ink2);font-size:13px}',
  '.inv-seg{display:flex;gap:4px;background:#f1e9df;border-radius:14px;padding:4px;margin-bottom:10px}',
  '.inv-seg button{flex:1;min-width:0;min-height:44px;border:0;border-radius:11px;background:transparent;font:inherit;',
  '  font-weight:800;font-size:14px;color:var(--ink2);cursor:pointer;padding:6px 8px;box-shadow:none}',
  '.inv-seg button:hover{box-shadow:none}',
  '.inv-seg button.on{background:var(--card);color:var(--red-deep);box-shadow:0 1px 3px rgba(60,35,20,.16)}',
  '.inv-sumrow{display:flex;align-items:center;gap:10px;margin:0 2px 10px}',
  '.inv-sum{flex:1;min-width:0;font-size:13.5px;font-weight:700;color:var(--ink2)}',
  '.inv-addbtn{flex:0 0 auto;font:inherit;font-size:14px;font-weight:800;cursor:pointer;white-space:nowrap;min-height:44px;',
  '  padding:8px 15px;border-radius:99px;background:#fff4ee;color:var(--red-deep);border:1px solid #f3c3ab}',
  '.inv-list{background:var(--card);border:1px solid var(--line);border-radius:var(--r);padding:2px 14px;',
  '  margin-bottom:12px;box-shadow:var(--shadow)}',
  /* a row: name over the quantity on the left, the pill over what to buy on the right */
  '.inv-row{display:flex;align-items:center;gap:10px;width:100%;text-align:left;font:inherit;color:var(--ink);cursor:pointer;',
  '  background:none;border:0;border-bottom:1px solid var(--line);border-radius:0;padding:11px 0;min-height:64px;box-shadow:none}',
  '.inv-row:last-child{border-bottom:0}',
  '.inv-row:hover{box-shadow:none;border-color:var(--line)}',
  '.inv-row:active{transform:none;background:#fbf5ee}',
  '.inv-main{flex:1;min-width:0;display:flex;flex-direction:column;gap:1px}',
  '.inv-nm{font-weight:700;font-size:15px;overflow-wrap:anywhere}',
  '.inv-qty{display:flex;align-items:baseline;gap:8px;flex-wrap:wrap}',
  '.inv-qty b{font-size:20px;letter-spacing:-.3px;font-variant-numeric:tabular-nums}',
  '.inv-qty small{font-size:12.5px;font-weight:600;color:var(--ink2)}',
  '.inv-st{flex:0 0 auto;display:flex;flex-direction:column;align-items:flex-end;gap:5px}',
  '.inv-st small{font-size:12.5px;font-weight:800;color:var(--amber);white-space:nowrap}',
  '.inv-row.out .inv-st small{color:var(--danger)}',
  '.inv-pill{display:inline-block;font-size:11px;font-weight:800;letter-spacing:.04em;padding:4px 10px;border-radius:99px;white-space:nowrap}',
  '.inv-pill.ok{background:var(--green-bg);color:var(--green)}',
  '.inv-pill.low{background:var(--amber-bg);color:var(--amber)}',
  '.inv-pill.out{background:var(--danger-bg);color:var(--danger)}',
  /* the order list: "Cheese — 3 kg" is the line to read out to the supplier */
  '.inv-row.buy .inv-nm{font-size:16px;font-weight:800}',
  '.inv-row.buy .inv-main small{font-size:12.5px;font-weight:600;color:var(--ink2)}',
  '.inv-row.buy .inv-go{flex:0 0 auto;font-size:20px;color:var(--ink3)}',
  '.inv-empty{text-align:center;color:var(--ink3);padding:30px 12px;font-size:14px}',
  '.inv-empty .inv-b{margin-top:14px;width:auto}',
  /* buttons inside the sheets (neither skin has all of these) */
  '.inv-b{font:inherit;font-weight:800;font-size:15px;cursor:pointer;width:100%;min-height:50px;padding:10px 14px;',
  '  border-radius:13px;border:1px solid transparent;box-shadow:none}',
  '.inv-b.in{background:var(--green);color:#fff}',
  '.inv-b.out{background:var(--amber);color:#fff}',
  '.inv-b.count{background:var(--card);color:var(--ink);border:2px solid var(--ink)}',
  '.inv-b.primary{background:var(--red);color:#fff}',
  '.inv-b.ghost{background:var(--card);color:var(--ink);border-color:var(--line)}',
  '.inv-b.danger{background:var(--danger-bg);color:var(--danger);border-color:#f6cfca}',
  '.inv-b:disabled,.inv-step button:disabled{opacity:.5;cursor:progress}',
  '.inv-modal .box{max-width:440px;max-height:calc(var(--vvh,100vh) - 16px);overflow-y:auto;outline:0}',
  '.inv-modal h3{overflow-wrap:anywhere}',
  '.inv-hand{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:14px}',
  '.inv-hand b{font-size:30px;line-height:1.1;letter-spacing:-.8px;font-variant-numeric:tabular-nums}',
  '.inv-hand small{font-size:13px;font-weight:600;color:var(--ink2)}',
  '.inv-step{display:flex;align-items:center;gap:8px}',
  '.inv-step button{flex:0 0 54px;width:54px;height:54px;min-height:0;padding:0;border-radius:14px;font:inherit;font-size:26px;',
  '  font-weight:700;line-height:1;background:var(--card);border:1px solid var(--line);color:var(--ink);cursor:pointer}',
  '.inv-step button:active{background:var(--line)}',
  '.inv-in{flex:1;min-width:0;position:relative;display:block}',
  '.inv-modal .inv-in input{height:54px;text-align:center;font-size:22px;font-weight:800;padding:6px 48px;font-variant-numeric:tabular-nums}',
  '.inv-u{position:absolute;right:12px;top:50%;transform:translateY(-50%);max-width:44px;overflow:hidden;text-overflow:ellipsis;',
  '  white-space:nowrap;color:var(--ink2);font-weight:700;font-size:13px;pointer-events:none}',
  '.inv-err{color:var(--danger);font-size:13.5px;font-weight:700;min-height:20px;margin:6px 0 8px}',
  '.inv-acts{display:flex;flex-direction:column;gap:8px}',
  '.inv-two{display:flex;gap:8px;margin-top:10px}',
  '.inv-two>*{flex:1;min-width:0}',
  '.inv-fld{display:block;margin-bottom:10px}',
  '.inv-fld>span{display:block;font-size:12px;font-weight:700;color:var(--ink2);margin-bottom:5px}',
  '.inv-modal input,.inv-modal select{font:inherit;font-size:16px;color:var(--ink);background:var(--card);width:100%;',
  '  min-height:46px;padding:10px 12px;border:1px solid var(--line);border-radius:12px}',
  '.inv-chips{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:12px}',
  '.inv-chip{font:inherit;font-size:13px;font-weight:700;cursor:pointer;white-space:nowrap;min-height:40px;padding:6px 12px;',
  '  border-radius:99px;background:var(--card);border:1px solid var(--line);color:var(--ink);box-shadow:none}',
  '.inv-chip small{font-size:11.5px;font-weight:600;color:var(--ink2);margin-left:3px}',
  '.inv-chip.on{background:#fff4ee;color:var(--red-deep);border-color:var(--red)}',
  '.inv-note{color:var(--ink2);font-size:14px;margin-bottom:4px}',
  '.inv-hrow{display:flex;align-items:center;gap:10px;padding:10px 0;border-bottom:1px solid var(--line)}',
  '.inv-hrow:last-child{border-bottom:0}',
  '.inv-hrow .g{flex:1;min-width:0}',
  '.inv-hrow b{display:block;font-size:14.5px}',
  '.inv-hrow small{display:block;color:var(--ink2);font-size:12.5px;overflow-wrap:anywhere}',
  '.inv-hrow .a{flex:0 0 auto;font-weight:800;font-size:14px;white-space:nowrap;font-variant-numeric:tabular-nums}',
  /* the door on the manager's tables screen: one row, the state of the shelf on its right */
  '.inv-entry{display:flex;align-items:center;gap:10px;width:100%;text-align:left;font:inherit;color:var(--ink);cursor:pointer;',
  '  min-height:50px;padding:6px 14px;margin-bottom:4px;background:var(--card);border:1px solid var(--line);',
  '  border-radius:var(--r);box-shadow:var(--shadow)}',
  '.inv-entry:active{border-color:var(--red)}',
  '.inv-entry .ic{flex:0 0 auto;font-size:22px;line-height:1}',
  '.inv-entry b{flex:0 0 auto;font-size:15.5px}',
  '.inv-entry .sub{flex:1;min-width:0;font-size:12.5px;font-weight:500;color:var(--ink2);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}',
  '.inv-entry.st .sub{display:none}',
  '.inv-entry .es{flex:0 0 auto;margin-left:auto;font-size:12.5px;font-weight:800;white-space:nowrap;padding:5px 11px;border-radius:99px}',
  '.inv-entry .es:empty{display:none}',
  '.inv-entry .es.ok{padding:5px 0;font-weight:700;color:var(--green)}',
  '.inv-entry .es.low{background:var(--amber-bg);color:var(--amber)}',
  '.inv-entry .es.out{background:var(--danger-bg);color:var(--danger)}',
  '.inv-entry .go{flex:0 0 auto;font-size:22px;line-height:1;color:var(--ink3)}'
].join('\n');
var st = document.createElement('style');
st.id = 'inv-css';
st.textContent = CSS;
document.head.appendChild(st);

// The names a new shop types first. A chip fills the name and the unit; the
// names are the owner's data, so they are the same in every language.
var QUICK = [['Cheese', 'kg'], ['Paneer', 'kg'], ['Butter', 'kg'], ['Pav', 'ladi'], ['Aloo', 'kg'], ['Tamatar', 'kg'],
  ['Kanda', 'kg'], ['Matar', 'kg'], ['Shimla mirch', 'kg'], ['Tel', 'L'], ['Doodh', 'L'], ['Dahi', 'kg'],
  ['Pav bhaji masala', 'kg'], ['Cold drink', 'bottle']];
var UNITS = ['kg', 'g', 'L', 'ml', 'pcs', 'packet', 'ladi', 'dozen', 'box', 'bottle', 'tin'];
var MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

var host = null;            // { el, api, toast, lang(), heading }
var items = [], units = UNITS, loaded = false, failed = '';
var seg = 'all';            // 'all' | 'order'
var gen = 0;                // bumped on mount/unmount: a late answer for an old screen is dropped
var timer = null;
var lastAt = 0;             // when the list was last asked for (the door's poll counts from it)
var doorLang = 'hg';
var stale = false;          // a background refresh was skipped while a sheet was open
var dlg = null, sending = false;
var S = {};

// ---------------------------------------------------------------- words
function langNow() { try { return host && host.lang ? host.lang() : 'hg'; } catch (e) { return 'hg'; } }
function words(lang) {
  var t = function (hg, hi, gu) { return lang === 'hi' ? hi : lang === 'gu' ? gu : hg; };
  return {
    title: t('Inventory', 'इन्वेंटरी', 'ઇન્વેન્ટરી'),
    sub: t('Kaccha maal: cheese, paneer, butter…', 'कच्चा माल: चीज़, पनीर, बटर…', 'કાચો માલ: ચીઝ, પનીર, બટર…'),
    segAll: t('Sab maal', 'सब माल', 'બધો માલ'),
    segOrder: function (n) { return t('Kal ka order', 'कल का ऑर्डर', 'કાલનો ઓર્ડર') + ' (' + n + ')'; },
    // "items", not "cheezein": चीज़ (a thing) reads as cheese next to an item called Cheese
    summary: function (n, low) {
      var a = t(n + (n === 1 ? ' item' : ' items'), n + ' आइटम', n + ' આઇટમ');
      var b = low ? t(low + ' kam hai', low + ' कम है', low + ' ઓછું છે') : t('sab poora hai', 'सब पूरा है', 'બધું પૂરું છે');
      return a + ' · ' + b;
    },
    // the door on the manager's tables screen
    doorLow: function (n) { return t(n + ' kam hai', n + ' कम है', n + ' ઓછું છે'); },
    doorOut: function (o, l) {
      return t(o + ' khatam', o + ' खत्म', o + ' ખતમ') + (l ? ' · ' + t(l + ' kam', l + ' कम', l + ' ઓછું') : '');
    },
    doorOk: t('sab poora', 'सब पूरा', 'બધું પૂરું'),
    // the server's three refusals, said in this page's language
    onlyHave: function (q) { return t('Sirf ' + q + ' hai', 'सिर्फ़ ' + q + ' है', 'ફક્ત ' + q + ' છે'); },
    dupName: function (n) { return t(n + ' pehle se list me hai', n + ' पहले से लिस्ट में है', n + ' પહેલેથી લિસ્ટમાં છે'); },
    goneItem: function (n) { return t(n + ' list se hata diya gaya hai', n + ' लिस्ट से हटा दिया गया है', n + ' લિસ્ટમાંથી હટાવી દેવાયું છે'); },
    add: t('＋ Naya maal', '＋ नया माल', '＋ નવો માલ'),
    addT: t('Naya maal', 'नया माल', 'નવો માલ'),
    need: function (q) { return t('Chahiye ' + q, 'चाहिए ' + q, 'જોઈએ ' + q); },
    order: function (q) { return t(q + ' mangao', q + ' मंगाओ', q + ' મંગાવો'); },
    now: function (q) { return t('abhi ' + q, 'अभी ' + q, 'અત્યારે ' + q); },
    ok: t('OK', 'ठीक', 'બરાબર'), low: t('KAM', 'कम', 'ઓછું'), out: t('KHATAM', 'खत्म', 'ખતમ'),
    none: t('Abhi koi maal nahi. ＋ Naya maal se shuru karo.', 'अभी कोई माल नहीं। ＋ नया माल से शुरू करो।', 'હજી કોઈ માલ નથી. ＋ નવો માલ થી શરૂ કરો.'),
    allFull: t('Sab maal poora hai', 'सब माल पूरा है', 'બધો માલ પૂરો છે'),
    share: t('📤 Share list', '📤 लिस्ट भेजो', '📤 લિસ્ટ મોકલો'),
    shareTitle: function (d) { return t('Kal ka order', 'कल का ऑर्डर', 'કાલનો ઓર્ડર') + ' (' + d + ')'; },
    copied: t('List copy ho gayi — WhatsApp mein paste karo', 'लिस्ट कॉपी हो गई — WhatsApp में पेस्ट करो', 'લિસ્ટ કૉપી થઈ ગઈ — WhatsApp માં પેસ્ટ કરો'),
    copyFail: t('List copy nahi hui', 'लिस्ट कॉपी नहीं हुई', 'લિસ્ટ કૉપી ન થઈ'),
    howMuch: t('Kitna', 'कितना', 'કેટલું'),
    actIn: t('＋ Maal aaya', '＋ माल आया', '＋ માલ આવ્યો'),
    actOut: t('− Use hua', '− इस्तेमाल हुआ', '− વપરાયો'),
    actCount: t('= Ginti set karo', '= गिनती सेट करो', '= ગણતરી સેટ કરો'),
    edit: t('✎ Edit', '✎ बदलो', '✎ બદલો'),
    editT: function (n) { return t('Edit: ' + n, 'बदलो: ' + n, 'બદલો: ' + n); },
    history: t('🕘 History', '🕘 हिस्ट्री', '🕘 હિસ્ટરી'),
    historyT: function (n) { return t('History: ' + n, 'हिस्ट्री: ' + n, 'હિસ્ટરી: ' + n); },
    noHistory: t('Abhi koi entry nahi', 'अभी कोई एंट्री नहीं', 'હજી કોઈ એન્ટ્રી નથી'),
    hIn: function (q) { return t('+' + q + ' aaya', '+' + q + ' आया', '+' + q + ' આવ્યો'); },
    hOut: function (q) { return t('−' + q + ' use hua', '−' + q + ' इस्तेमाल हुआ', '−' + q + ' વપરાયો'); },
    hCount: function (q) { return t('ginti ' + q, 'गिनती ' + q, 'ગણતરી ' + q); },
    back: t('Back', 'पीछे', 'પાછળ'),
    save: t('Save', 'सेव करो', 'સેવ કરો'),
    saved: t('Save ho gaya', 'सेव हो गया', 'સેવ થઈ ગયું'),
    name: t('Naam', 'नाम', 'નામ'),
    unit: t('Unit', 'यूनिट', 'યુનિટ'),
    qtyNow: t('Abhi kitna hai', 'अभी कितना है', 'અત્યારે કેટલું છે'),
    par: t('Roz kitna chahiye', 'रोज़ कितना चाहिए', 'રોજ કેટલું જોઈએ'),
    remove: t('Hatao', 'हटाओ', 'હટાવો'),
    removeQ: function (n) { return t(n + ' hatana hai?', n + ' हटाना है?', n + ' હટાવવું છે?'); },
    removeBody: t('List se hat jayega. Purani history rahegi.', 'लिस्ट से हट जाएगा। पुरानी हिस्ट्री रहेगी।', 'લિસ્ટમાંથી નીકળી જશે. જૂની હિસ્ટરી રહેશે.'),
    keep: t('Rehne do', 'रहने दो', 'રહેવા દો'),
    removed: function (n) { return t(n + ' hata diya', n + ' हटा दिया', n + ' હટાવી દીધું'); },
    needName: t('Naam likho', 'नाम लिखो', 'નામ લખો'),
    needQty: t('Pehle quantity likho', 'पहले मात्रा लिखो', 'પહેલા માત્રા લખો'),
    badQty: t('Quantity sahi likho, jaise 2.5', 'मात्रा सही लिखो, जैसे 2.5', 'માત્રા સાચી લખો, જેમ કે 2.5'),
    retry: t('Dobara try karo', 'फिर कोशिश करो', 'ફરી પ્રયત્ન કરો')
  };
}

// ---------------------------------------------------------------- numbers
function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}
// 2500 -> "2.5", 12000 -> "12", 250 -> "0.25", 1500000 -> "1,500" (Indian grouping)
var NUM = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 3 });
function fmtQty(milli) { return NUM.format(milli / 1000); }
function qty(milli, unit) { return fmtQty(milli) + ' ' + unit; }
// the same number for an input box: no grouping
function plain(milli) { return String(milli / 1000); }
// A plain decimal, at most 3 places, 0 to 1,000,000 units. null = not a quantity.
function parseQty(s) {
  var v = String(s == null ? '' : s).replace(/[,\s]/g, '');
  if (!/^(\d+\.?\d{0,3}|\.\d{1,3})$/.test(v)) return null;
  var n = Math.round(parseFloat(v) * 1000);
  return isNaN(n) || n < 0 || n > 1e9 ? null : n;
}
// "7:42 pm", as on the printed bill
function clock(ms) {
  var d = new Date(ms), h = d.getHours(), m = d.getMinutes();
  return (h % 12 || 12) + ':' + (m < 10 ? '0' : '') + m + ' ' + (h < 12 ? 'am' : 'pm');
}
function day(ms) { var d = new Date(ms); return d.getDate() + ' ' + MON[d.getMonth()]; }

function byId(id) {
  for (var i = 0; i < items.length; i++) if (items[i].id === id) return items[i];
  return null;
}
function shortItems() { return items.filter(function (i) { return i.short > 0; }); }
function pill(it) {
  var k = it.status === 'out' ? 'out' : it.status === 'low' ? 'low' : 'ok';
  return '<span class="inv-pill ' + k + '">' + esc(S[k]) + '</span>';
}

// ---------------------------------------------------------------- the list
function rowAll(it) {
  return '<button type="button" class="inv-row ' + esc(it.status) + '" data-inv="' + it.id + '">' +
    '<span class="inv-main"><span class="inv-nm">' + esc(it.name) + '</span>' +
      '<span class="inv-qty"><b>' + esc(qty(it.qty, it.unit)) + '</b>' +
      (it.par > 0 ? '<small>' + esc(S.need(qty(it.par, it.unit))) + '</small>' : '') + '</span></span>' +
    '<span class="inv-st">' + pill(it) +
      (it.short > 0 ? '<small>' + esc(S.order(qty(it.short, it.unit))) + '</small>' : '') + '</span></button>';
}
function rowBuy(it) {
  return '<button type="button" class="inv-row buy ' + esc(it.status) + '" data-inv="' + it.id + '">' +
    '<span class="inv-main"><span class="inv-nm">' + esc(it.name + ' — ' + qty(it.short, it.unit)) + '</span>' +
      '<small>' + esc(S.now(qty(it.qty, it.unit))) + '</small></span>' +
    '<span class="inv-go">›</span></button>';
}
function body(short) {
  if (!loaded) {
    if (!failed) return '<div class="inv-empty">…</div>';
    return '<div class="inv-empty">⚠ ' + esc(failed) + '<br><button type="button" class="inv-b ghost" id="invRetry">' +
      esc(S.retry) + '</button></div>';
  }
  if (seg === 'order') {
    if (!short.length) return '<div class="inv-empty" id="invFull">✅ ' + esc(S.allFull) + '</div>';
    return '<div class="inv-list" id="invOrder">' + short.map(rowBuy).join('') + '</div>' +
      '<button type="button" class="inv-b primary" id="invShare">' + esc(S.share) + '</button>';
  }
  if (!items.length) return '<div class="inv-empty">' + esc(S.none) + '</div>';
  return '<div class="inv-list" id="invAll">' + items.map(rowAll).join('') + '</div>';
}
function render() {
  if (!host) return;
  S = words(langNow());
  var short = shortItems();
  host.el.innerHTML = '<div class="inv">' +
    (host.heading ? '<div class="inv-hd"><b>' + esc(S.title) + '</b><span>' + esc(S.sub) + '</span></div>' : '') +
    '<div class="inv-seg">' +
      '<button type="button" data-seg="all" class="' + (seg === 'all' ? 'on' : '') + '">' + esc(S.segAll) + '</button>' +
      '<button type="button" data-seg="order" class="' + (seg === 'order' ? 'on' : '') + '">' + esc(S.segOrder(short.length)) + '</button></div>' +
    '<div class="inv-sumrow"><span class="inv-sum" id="invSum">' + (loaded ? esc(S.summary(items.length, short.length)) : '') + '</span>' +
      '<button type="button" class="inv-addbtn" id="invAdd">' + esc(S.add) + '</button></div>' +
    body(short) + '</div>';
}

// quiet = a background refresh: a miss keeps the list that is on screen
async function load(quiet) {
  if (!host) return;
  var my = gen;
  lastAt = Date.now();
  try {
    var d = await host.api('GET', '/api/inventory');
    if (my !== gen) return;
    items = d.items || [];
    if (d.units && d.units.length) units = d.units;
    loaded = true; failed = ''; stale = false;
  } catch (e) {
    if (my !== gen || (quiet && loaded)) return;
    loaded = false; failed = e.message;
  }
  render();
}

// A background refresh never runs under a sheet or a focused field (the
// list is redrawn whole; a redraw mid-typing is the trap the other screens
// already fell into). It is remembered and runs when the sheet closes.
function busy() {
  if (dlg || document.querySelector('.modal')) return true;
  var a = document.activeElement;     // (not SELECT: the language chip keeps focus after a pick)
  return !!a && (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA');
}
// A change made on another device arrives with the 20s poll. Neither page
// holds a /ws connection, and this view does not open one: the hub counts
// every socket as a phone, so a browser on this screen would make the
// dashboard's "phones online" read one too many.
function background() {
  if (!host) return;
  if (document.hidden || busy()) { stale = true; return; }
  load(true);
}
function onVisible() { if (!document.hidden && stale && !busy()) load(true); }

// ---------------------------------------------------------------- the door
// The manager's tables screen shows one row that opens this view, with the
// state of the shelf on its right: red when something is khatam, amber when
// something is under its order level, a quiet green when all is there, and
// nothing while the list is empty. It is drawn from the copy held here, so
// it is right the moment the manager comes back from the view.
function doorState() {
  if (!loaded || !items.length) return null;
  var out = 0, low = 0;
  items.forEach(function (i) { if (i.status === 'out') out++; else if (i.status === 'low') low++; });
  if (out) return { k: 'out', text: S.doorOut(out, low) };
  if (low) return { k: 'low', text: S.doorLow(low) };
  return { k: 'ok', text: S.doorOk };
}
function entry(lang) {
  doorLang = lang;
  S = words(lang);
  var s = doorState();
  return '<button type="button" class="inv-entry' + (s ? ' st' : '') + '" id="invEntry"><span class="ic">📦</span>' +
    '<b>' + esc(S.title) + '</b><span class="sub">' + esc(S.sub) + '</span>' +
    '<span class="es' + (s ? ' ' + s.k : '') + '" id="invEntrySt">' + esc(s ? s.text : '') + '</span>' +
    '<span class="go">›</span></button>';
}
// In place, never a new button: a repaint between finger down and finger up
// would swallow the tap.
function paintDoor() {
  var b = document.getElementById('invEntry'), e = document.getElementById('invEntrySt');
  if (!b || !e) return;
  S = words(doorLang);
  var s = doorState();
  b.className = 'inv-entry' + (s ? ' st' : '');
  e.className = 'es' + (s ? ' ' + s.k : '');
  e.textContent = s ? s.text : '';
}
// The tables screen calls this on each of its own repaints (every 10s); the
// list is asked for at most every 20s, and not under a dialog, in a field,
// or in a hidden tab. A miss keeps what the door says.
async function peek(api) {
  if (host || document.hidden || busy() || Date.now() - lastAt < 15000) return;
  lastAt = Date.now();
  var d;
  try { d = await api('GET', '/api/inventory'); } catch (e) { return; }
  if (host) return;           // the view opened meanwhile and reads for itself
  items = d.items || [];
  if (d.units && d.units.length) units = d.units;
  loaded = true; failed = '';
  paintDoor();
}

// The server refuses in English. Its three inventory refusals are said in
// the page's language; anything else is shown as it was sent.
function refusal(msg) {
  var s = String(msg || ''), m;
  if ((m = /^only (.+?) of .+ in hand$/.exec(s))) return S.onlyHave(m[1]);
  if ((m = /^(.+) is already in the inventory$/.exec(s))) return S.dupName(m[1]);
  if ((m = /^(.+) has been removed from the inventory$/.exec(s))) return S.goneItem(m[1]);
  return s;
}

// ---------------------------------------------------------------- sheets
function sheet(title, html, keys) {
  if (dlg) dlg.remove();
  var m = document.createElement('div');
  m.className = 'modal inv-modal';
  m.innerHTML = '<div class="box" role="dialog" aria-modal="true" tabindex="-1"><h3>' + esc(title) + '</h3>' + html + '</div>';
  m.addEventListener('click', function (e) { if (e.target === m && !sending) (m.onEsc || closeSheet)(); });
  m.onEsc = keys && keys.esc;
  m.onEnter = keys && keys.enter;
  document.body.appendChild(m);
  dlg = m; sending = false;
  m.firstChild.focus();
  return m;
}
function closeSheet() {
  if (dlg) { dlg.remove(); dlg = null; }
  sending = false;
  if (stale && host) load(true);
}
function q(id) { return dlg ? dlg.querySelector('#' + id) : null; }
function showErr(msg) { var e = q('invErr'); if (e) e.textContent = msg || ''; }
// one request at a time per sheet: a second tap on "Maal aaya" must not add twice
function lock(on) {
  sending = on;
  if (!dlg) return;
  [].forEach.call(dlg.querySelectorAll('button'), function (b) { b.disabled = on; });
}
function onKey(e) {
  if (!dlg || e.ctrlKey || e.metaKey || e.altKey || e.isComposing) return;
  if (e.key === 'Escape') {
    e.preventDefault(); e.stopPropagation();
    if (!sending) (dlg.onEsc || closeSheet)();
  } else if (e.key === 'Enter' && e.target.tagName === 'INPUT' && dlg.onEnter) {
    e.preventDefault(); e.stopPropagation();
    if (!sending) dlg.onEnter();
  }
}
function fld(label, control) { return '<label class="inv-fld"><span>' + esc(label) + '</span>' + control + '</label>'; }
function unitSelect(cur) {
  var list = units.indexOf(cur) < 0 && cur ? units.concat([cur]) : units;
  return '<select id="invUnit">' + list.map(function (u) {
    return '<option' + (u === cur ? ' selected' : '') + '>' + esc(u) + '</option>';
  }).join('') + '</select>';
}
function put(it) {            // the server's answer replaces our copy at once
  var had = false;
  items = items.map(function (x) { if (x.id === it.id) { had = true; return it; } return x; });
  if (!had) items.push(it);
  render();
}

// One item: what is in hand, a quantity with − / +, and the three moves.
function openItem(id) {
  var it = byId(id);
  if (!it) return;
  sheet(it.name,
    '<div class="inv-hand"><b id="invHand">' + esc(qty(it.qty, it.unit)) + '</b>' + pill(it) +
      (it.par > 0 ? '<small>' + esc(S.need(qty(it.par, it.unit))) + '</small>' : '') + '</div>' +
    '<div class="inv-step"><button type="button" id="invMinus" aria-label="−1">−</button>' +
      '<label class="inv-in"><input id="invQ" type="text" inputmode="decimal" autocomplete="off" placeholder="0" aria-label="' +
        esc(S.howMuch) + '"><span class="inv-u">' + esc(it.unit) + '</span></label>' +
      '<button type="button" id="invPlus" aria-label="+1">+</button></div>' +
    '<div class="inv-err" id="invErr" role="alert"></div>' +
    '<div class="inv-acts">' +
      '<button type="button" class="inv-b in" data-kind="in">' + esc(S.actIn) + '</button>' +
      '<button type="button" class="inv-b out" data-kind="out">' + esc(S.actOut) + '</button>' +
      '<button type="button" class="inv-b count" data-kind="count">' + esc(S.actCount) + '</button></div>' +
    '<div class="inv-two"><button type="button" class="inv-b ghost" id="invEdit">' + esc(S.edit) + '</button>' +
      '<button type="button" class="inv-b ghost" id="invHist">' + esc(S.history) + '</button></div>' +
    '<div class="inv-two"><button type="button" class="inv-b ghost" id="invBack">' + esc(S.back) + '</button></div>');
  var bump = function (d) {
    var v = parseQty(q('invQ').value);
    q('invQ').value = plain(Math.min(1e9, Math.max(0, (v === null ? 0 : v) + d * 1000)));
    showErr('');
  };
  q('invMinus').onclick = function () { bump(-1); };
  q('invPlus').onclick = function () { bump(1); };
  q('invQ').oninput = function () { showErr(''); };
  q('invBack').onclick = closeSheet;
  q('invEdit').onclick = function () { openEdit(id); };
  q('invHist').onclick = function () { openHistory(id); };
  [].forEach.call(dlg.querySelectorAll('[data-kind]'), function (b) {
    b.onclick = function () { move(it, b.getAttribute('data-kind')); };
  });
}
async function move(it, kind) {
  if (sending) return;
  var raw = q('invQ').value.trim();
  if (!raw) { showErr(S.needQty); return; }     // an empty box is never "count = 0"
  var v = parseQty(raw);
  if (v === null) { showErr(S.badQty); return; }
  if (kind !== 'count' && v === 0) { showErr(S.needQty); return; }
  // Using more than is in hand: the server would refuse it, so say it at once.
  var cur = byId(it.id) || it;
  if (kind === 'out' && v > cur.qty) { showErr(S.onlyHave(qty(cur.qty, cur.unit))); return; }
  var my = gen;
  lock(true);
  try {
    var r = await host.api('POST', '/api/inventory/' + it.id + '/move', { kind: kind, qty: v });
    if (my !== gen) return;
    closeSheet();
    host.toast(r.name + ': ' + qty(r.qty, r.unit), 'ok');
    put(r);
    load(true);
  } catch (e) {
    if (my !== gen) return;
    lock(false);
    stale = true;             // a refusal means our copy is old: read again when the sheet closes
    showErr(refusal(e.message));
  }
}

function openEdit(id) {
  var it = byId(id);
  if (!it) { closeSheet(); return; }
  var back = function () { openItem(id); };
  var save = async function () {
    if (sending) return;
    var name = q('invName').value.trim(), parRaw = q('invPar').value.trim();
    var par = parRaw ? parseQty(parRaw) : 0;
    if (!name) { showErr(S.needName); return; }
    if (par === null) { showErr(S.badQty); return; }
    var my = gen;
    lock(true);
    try {
      var r = await host.api('PATCH', '/api/inventory/' + id, { name: name, unit: q('invUnit').value, par: par });
      if (my !== gen) return;
      closeSheet();
      host.toast(S.saved, 'ok');
      put(r);
      load(true);
    } catch (e) {
      if (my !== gen) return;
      lock(false);
      showErr(refusal(e.message));
    }
  };
  sheet(S.editT(it.name),
    fld(S.name, '<input id="invName" type="text" maxlength="40" autocomplete="off" value="' + esc(it.name) + '">') +
    fld(S.unit, unitSelect(it.unit)) +
    fld(S.par, '<input id="invPar" type="text" inputmode="decimal" autocomplete="off" placeholder="0" value="' +
      (it.par > 0 ? plain(it.par) : '') + '">') +
    '<div class="inv-err" id="invErr" role="alert"></div>' +
    '<div class="inv-two"><button type="button" class="inv-b ghost" id="invBack">' + esc(S.back) + '</button>' +
      '<button type="button" class="inv-b primary" id="invSave">' + esc(S.save) + '</button></div>' +
    '<div class="inv-two"><button type="button" class="inv-b danger" id="invRemove">🗑 ' + esc(S.remove) + '</button></div>',
    { esc: back, enter: save });
  q('invBack').onclick = back;
  q('invSave').onclick = save;
  q('invRemove').onclick = function () { askRemove(id); };
}
function askRemove(id) {
  var it = byId(id);
  if (!it) { closeSheet(); return; }
  var back = function () { openEdit(id); };
  sheet(S.removeQ(it.name),
    '<div class="inv-note">' + esc(S.removeBody) + '</div><div class="inv-err" id="invErr" role="alert"></div>' +
    '<div class="inv-two"><button type="button" class="inv-b ghost" id="invBack">' + esc(S.keep) + '</button>' +
      '<button type="button" class="inv-b danger" id="invYes">' + esc(S.remove) + '</button></div>',
    { esc: back });
  q('invBack').onclick = back;
  q('invYes').onclick = async function () {
    if (sending) return;
    var my = gen;
    lock(true);
    try {
      await host.api('PATCH', '/api/inventory/' + id, { active: false });
      if (my !== gen) return;
      closeSheet();
      host.toast(S.removed(it.name), 'ok');
      items = items.filter(function (x) { return x.id !== id; });
      render();
      load(true);
    } catch (e) {
      if (my !== gen) return;
      lock(false);
      showErr(refusal(e.message));
    }
  };
}

async function openHistory(id) {
  var it = byId(id);
  if (!it) { closeSheet(); return; }
  var back = function () { openItem(id); };
  var paint = function (inner) {
    sheet(S.historyT(it.name), '<div id="invMoves">' + inner + '</div>' +
      '<div class="inv-two"><button type="button" class="inv-b ghost" id="invBack">' + esc(S.back) + '</button></div>', { esc: back });
    q('invBack').onclick = back;
  };
  paint('<div class="inv-empty">…</div>');
  var mine = dlg, my = gen, d;
  try { d = await host.api('GET', '/api/inventory/' + id + '/moves?limit=30'); }
  catch (e) { if (my === gen && dlg === mine) paint('<div class="inv-empty">⚠ ' + esc(e.message) + '</div>'); return; }
  if (my !== gen || dlg !== mine) return;     // they went back while it loaded
  var moves = d.moves || [];
  paint(moves.length ? moves.map(function (m) {
    var n = qty(Math.abs(m.delta), it.unit);
    var what = m.kind === 'in' ? S.hIn(n) : m.kind === 'out' ? S.hOut(n) : S.hCount(qty(m.qtyAfter, it.unit));
    return '<div class="inv-hrow" data-kind="' + esc(m.kind) + '"><span class="g"><b>' + esc(what) + '</b>' +
      '<small>' + esc(day(m.at) + ' · ' + clock(m.at) + (m.by ? ' · ' + m.by : '') + (m.note ? ' · ' + m.note : '')) + '</small></span>' +
      '<span class="a">→ ' + esc(qty(m.qtyAfter, it.unit)) + '</span></div>';
  }).join('') : '<div class="inv-empty">' + esc(S.noHistory) + '</div>');
}

function openAdd() {
  var have = {};
  items.forEach(function (i) { have[i.name.toLowerCase()] = true; });
  var chips = QUICK.filter(function (c) { return !have[c[0].toLowerCase()]; });
  var save = async function () {
    if (sending) return;
    var name = q('invName').value.trim(), qRaw = q('invQty').value.trim(), pRaw = q('invPar').value.trim();
    var n = qRaw ? parseQty(qRaw) : 0, par = pRaw ? parseQty(pRaw) : 0;
    if (!name) { showErr(S.needName); return; }
    if (n === null || par === null) { showErr(S.badQty); return; }
    var my = gen;
    lock(true);
    try {
      var r = await host.api('POST', '/api/inventory', { name: name, unit: q('invUnit').value, qty: n, par: par });
      if (my !== gen) return;
      closeSheet();
      host.toast(r.name + ': ' + qty(r.qty, r.unit), 'ok');
      put(r);
      load(true);
    } catch (e) {
      if (my !== gen) return;
      lock(false);
      showErr(refusal(e.message));
    }
  };
  sheet(S.addT,
    (chips.length ? '<div class="inv-chips">' + chips.map(function (c) {
      return '<button type="button" class="inv-chip" data-name="' + esc(c[0]) + '" data-unit="' + esc(c[1]) + '">' +
        esc(c[0]) + '<small>' + esc(c[1]) + '</small></button>';
    }).join('') + '</div>' : '') +
    fld(S.name, '<input id="invName" type="text" maxlength="40" autocomplete="off">') +
    fld(S.unit, unitSelect('kg')) +
    '<div class="inv-two" style="margin-top:0">' +
      fld(S.qtyNow, '<input id="invQty" type="text" inputmode="decimal" autocomplete="off" placeholder="0">') +
      fld(S.par, '<input id="invPar" type="text" inputmode="decimal" autocomplete="off" placeholder="0">') + '</div>' +
    '<div class="inv-err" id="invErr" role="alert"></div>' +
    '<div class="inv-two" style="margin-top:0"><button type="button" class="inv-b ghost" id="invBack">' + esc(S.back) + '</button>' +
      '<button type="button" class="inv-b primary" id="invSave">' + esc(S.save) + '</button></div>',
    { enter: save });
  q('invBack').onclick = closeSheet;
  q('invSave').onclick = save;
  [].forEach.call(dlg.querySelectorAll('.inv-chip'), function (c) {
    c.onclick = function () {
      q('invName').value = c.getAttribute('data-name');
      q('invUnit').value = c.getAttribute('data-unit');
      [].forEach.call(dlg.querySelectorAll('.inv-chip'), function (x) { x.classList.toggle('on', x === c); });
      showErr('');
    };
  });
}

// ---------------------------------------------------------------- share
function shareText() {
  return [S.shareTitle(day(Date.now()))].concat(shortItems().map(function (i) {
    return i.name + ': ' + qty(i.short, i.unit);
  })).join('\n');
}
// The shop reaches this page over plain http on its WiFi, where the browser
// offers neither navigator.share nor navigator.clipboard: the old
// select-and-copy still works there.
function copyOld(text) {
  var ta = document.createElement('textarea');
  ta.value = text; ta.setAttribute('readonly', '');
  ta.style.cssText = 'position:fixed;left:-9999px;top:0;opacity:0';
  document.body.appendChild(ta);
  ta.select(); ta.setSelectionRange(0, text.length);
  var ok = false;
  try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
  ta.remove();
  return ok;
}
async function share() {
  var text = shareText(), toast = host.toast;
  if (navigator.share) {
    try { await navigator.share({ text: text }); return; }
    catch (e) { if (e && e.name === 'AbortError') return; }   // they closed the share sheet
  }
  var ok = false;
  if (navigator.clipboard && navigator.clipboard.writeText) {
    try { await navigator.clipboard.writeText(text); ok = true; } catch (e) { ok = false; }
  }
  if (!ok) ok = copyOld(text);
  toast(ok ? S.copied : S.copyFail, ok ? 'ok' : 'bad');
}

// ---------------------------------------------------------------- mount
function onClick(e) {
  var b = e.target.closest('[data-seg]');
  if (b) { seg = b.getAttribute('data-seg'); render(); return; }
  if (e.target.closest('#invAdd')) { openAdd(); return; }
  if (e.target.closest('#invShare')) { share(); return; }
  if (e.target.closest('#invRetry')) { load(false); return; }
  b = e.target.closest('[data-inv]');
  if (b) openItem(Number(b.getAttribute('data-inv')));
}
function unmount() {
  if (!host) return;
  gen++;
  host.el.removeEventListener('click', onClick);
  document.removeEventListener('keydown', onKey, true);
  document.removeEventListener('visibilitychange', onVisible);
  clearInterval(timer);
  if (dlg) { dlg.remove(); dlg = null; }
  sending = false;
  host = null;
}
// Mounting again (a language switch) redraws at once from the copy we hold.
function mount(h) {
  unmount();
  host = h; gen++;
  host.el.addEventListener('click', onClick);
  document.addEventListener('keydown', onKey, true);
  document.addEventListener('visibilitychange', onVisible);
  render();
  load(false);
  timer = setInterval(background, 20000);
}

window.PBInventory = {
  mount: mount,
  unmount: unmount,
  refresh: function () { return load(true); },
  entry: entry,
  peek: peek,
  words: function (lang) { var w = words(lang); return { title: w.title, sub: w.sub }; }
};
})();
