'use strict';
// Vaultaire UI prototype: a clickable design mockup.
// No cryptography, no storage, sample data only. Do not copy any of this into the product.

(function () {
  const app = document.getElementById('app');
  const nav = document.getElementById('screens');
  const calm = window.matchMedia('(prefers-reduced-motion: reduce)');

  // ---------- Icons: 1.5 px stroke on a 24 px grid (blueprint §15) ----------
  const PATHS = {
    lock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
    sliders: '<path d="M4 7h10M18 7h2M4 17h4M12 17h8"/><circle cx="16" cy="7" r="2"/><circle cx="10" cy="17" r="2"/>',
    search: '<circle cx="11" cy="11" r="6.5"/><path d="M16 16l4.5 4.5"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    back: '<path d="M15 5l-7 7 7 7"/>',
    chev: '<path d="M9 5l7 7-7 7"/>',
    close: '<path d="M6 6l12 12M18 6L6 18"/>',
    copy: '<rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V6a2 2 0 0 1 2-2h8"/>',
    eye: '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/>',
    eyeOff: '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/><path d="M4 4l16 16"/>',
    finger: '<path d="M7.5 19c1.2-2 1.5-4 1.5-7a3 3 0 0 1 6 0c0 1.5-.1 3-.4 4.3"/><path d="M12 12c0 3-.6 5.6-2 8"/><path d="M5 15.5c.5-1.3.5-2.3.5-3.5a6.5 6.5 0 0 1 11.6-4"/><path d="M18.3 10.5c.2.5.2 1 .2 1.5 0 2.2-.3 4.2-.9 6"/>',
    shield: '<path d="M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z"/><path d="M9 12l2 2 4-4"/>',
    alert: '<path d="M12 4l9 16H3z"/><path d="M12 10v4.5M12 17.5v.01"/>',
    check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
    checkCircle: '<circle cx="12" cy="12" r="9"/><path d="M8 12.5l2.8 2.8L16.5 9.5"/>',
    errCircle: '<circle cx="12" cy="12" r="9"/><path d="M12 7.5v5.5M12 16.5v.01"/>',
    refresh: '<path d="M19 12a7 7 0 1 1-2.05-4.95M19 4.5V8h-3.5"/>',
    star: '<path d="M12 4l2.4 4.9 5.4.8-3.9 3.8.9 5.4-4.8-2.5-4.8 2.5.9-5.4-3.9-3.8 5.4-.8z"/>',
    login: '<circle cx="8" cy="15" r="4"/><path d="M11 12l8-8M16 7l2 2M14 9l2 2"/>',
    card: '<rect x="3" y="6" width="18" height="12" rx="2"/><path d="M3 10h18M7 15h3"/>',
    bank: '<path d="M3 10l9-5 9 5M5 10v8M9.5 10v8M14.5 10v8M19 10v8M3 20h18"/>',
    identity: '<rect x="3" y="5" width="18" height="14" rx="2"/><circle cx="9" cy="11" r="2.5"/><path d="M5.5 16.5c.7-1.5 2-2.2 3.5-2.2s2.8.7 3.5 2.2M15 10h3.5M15 13.5h3.5"/>',
    note: '<path d="M6 3.5h8l4 4v13H6z"/><path d="M14 3.5v4h4M9 12h6M9 15.5h6"/>',
    devkey: '<path d="M8.5 8L4.5 12l4 4M15.5 8l4 4-4 4M13.5 5.5l-3 13"/>',
    membership: '<path d="M4 6h16v4a2 2 0 0 0 0 4v4H4v-4a2 2 0 0 0 0-4z"/><path d="M14 8v2M14 12.5v1M14 16v.01"/>',
    other: '<circle cx="6" cy="12" r="1.2"/><circle cx="12" cy="12" r="1.2"/><circle cx="18" cy="12" r="1.2"/>'
  };

  // `attrs` come first so they win over the defaults (the first duplicate attribute wins).
  function icon(name, size = 22, cls = '', attrs = '') {
    const hidden = attrs.includes('aria-label') ? '' : ' aria-hidden="true"';
    return `<svg ${attrs} class="${cls}" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"${hidden}>${PATHS[name]}</svg>`;
  }
  const typeTile = (kind) => `<span class="type-tile">${icon(kind)}</span>`;
  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  // ---------- Guilloché: the engraved line work of banknotes and watch dials ----------
  // A band of closed polar waves, r(θ) = base + amp·sin(kθ + φ), each line phase-shifted.
  function rosette(size, bands) {
    const c = size / 2;
    let d = '';
    for (const { base, amp, k, lines } of bands) {
      for (let i = 0; i < lines; i++) {
        const phi = (i * 2 * Math.PI) / (k * lines);
        for (let s = 0; s <= 480; s++) {
          const t = (s / 480) * 2 * Math.PI;
          const r = base + amp * Math.sin(k * t + phi);
          d += (s ? 'L' : 'M') + (c + r * Math.cos(t)).toFixed(1) + ' ' + (c + r * Math.sin(t)).toFixed(1);
        }
      }
    }
    return d;
  }
  // Interlaced horizontal waves across a card face (viewBox 350 × 221).
  function engraving() {
    let d = '';
    for (let i = 0; i < 16; i++) {
      for (let s = 0; s <= 70; s++) {
        const x = (s / 70) * 350;
        const y = 132 + 26 * Math.sin((x / 350) * 2 * Math.PI * 1.5 + i * 0.2) + 9 * Math.sin((x / 350) * 2 * Math.PI * 6 - i * 0.35);
        d += (s ? 'L' : 'M') + x.toFixed(1) + ' ' + y.toFixed(1);
      }
    }
    return d;
  }
  const LOCK_ROSETTE = rosette(300, [{ base: 132, amp: 8, k: 28, lines: 9 }, { base: 108, amp: 5, k: 36, lines: 7 }]);
  const DONE_ROSETTE = rosette(160, [{ base: 70, amp: 5, k: 22, lines: 7 }]);
  const CARD_ENGRAVING = engraving();

  // ---------- Sample data (not real) ----------
  const FINISHES = [['obsidian', 'Obsidian'], ['titanium', 'Titanium'], ['platinum', 'Platinum'], ['ivory', 'Ivory'], ['champagne', 'Champagne']];
  const CARDS = {
    1: { id: '1', nick: 'Travel card', network: 'VISA', issuer: 'Northbridge Bank', holder: 'Alex Morgan', number: '4929 1845 7720 4821', last4: '4821', expiry: '08 / 29', cvv: '317' },
    2: { id: '2', nick: 'Everyday debit', network: 'MASTERCARD', issuer: 'Harbour Mutual', holder: 'Alex Morgan', number: '5412 7534 9021 1177', last4: '1177', expiry: '11 / 27', cvv: null }
  };
  const ITEMS = [
    { type: 'card', card: '1', title: 'Travel card', sub: 'Visa · •••• 4821', fav: true, href: '#/card/1' },
    { type: 'login', title: 'Primary email', sub: 'alex.morgan@example.com', fav: true, href: '#/login' },
    { type: 'card', card: '2', title: 'Everyday debit', sub: 'Mastercard · •••• 1177', fav: false, href: '#/card/2' },
    { type: 'bank', title: 'Salary account', sub: 'Northbridge Bank · •••• 0932', fav: false },
    { type: 'identity', title: 'Passport', sub: 'Expires Mar 2031', fav: false },
    { type: 'devkey', title: 'Cloud root key', sub: 'production · key ID AKIA…7Q2F', fav: false },
    { type: 'note', title: 'Home Wi-Fi and router', sub: 'Secure note', fav: false },
    { type: 'membership', title: 'Airline miles', sub: 'Skyward · Gold', fav: false }
  ];
  const CHIPS = [
    ['all', 'All'], ['fav', 'Favorites'], ['login', 'Logins'], ['card', 'Cards'], ['bank', 'Bank accounts'],
    ['identity', 'Identity'], ['note', 'Notes'], ['devkey', 'Keys'], ['membership', 'Memberships']
  ];
  const TYPES = [
    { id: 'login', label: 'Login', desc: 'Username and password' },
    { id: 'card', label: 'Card', desc: 'Debit or credit card' },
    { id: 'bank', label: 'Bank account', desc: 'Account and routing numbers' },
    { id: 'identity', label: 'Identity', desc: 'Passport, licence, ID card' },
    { id: 'note', label: 'Secure note', desc: 'Private text' },
    { id: 'devkey', label: 'Developer key', desc: 'API secrets and access keys' },
    { id: 'membership', label: 'Membership', desc: 'Loyalty and member numbers' },
    { id: 'other', label: 'Other', desc: 'Your own fields' }
  ];
  const F = (label, hint = '', extra = {}) => Object.assign({ label, hint, critical: false, pw: false, area: false }, extra);
  const TEMPLATES = {
    login: [F('Title', 'e.g. Primary email'), F('Username', 'name@example.com'), F('Password', '', { pw: true }), F('Website', 'example.com')],
    card: [F('Nickname', 'e.g. Travel card'), F('Cardholder', 'Name on card'), F('Card number', '0000 0000 0000 0000', { critical: true }), F('Expiry', 'MM / YY'), F('Issuer', 'Bank name')],
    bank: [F('Title', 'e.g. Salary account'), F('Bank', 'Bank name'), F('Account holder', 'Full name'), F('Account number or IBAN', '', { critical: true }), F('Routing, IFSC, sort code or SWIFT')],
    identity: [F('Document type', 'e.g. Passport'), F('Full name', 'As printed'), F('Document number', '', { critical: true }), F('Issuing country'), F('Expiry date', 'DD / MM / YYYY')],
    note: [F('Title', 'e.g. Home Wi-Fi and router'), F('Note', 'Only you can read this', { area: true })],
    devkey: [F('Name', 'e.g. Cloud root key'), F('Service'), F('Environment', 'e.g. production'), F('Key ID'), F('Secret', '', { critical: true })],
    membership: [F('Program', 'e.g. Airline miles'), F('Member number'), F('Tier', 'e.g. Gold')],
    other: [F('Title'), F('Field label', 'e.g. Locker code'), F('Value')]
  };
  const RECOVERY_KEY = ['7KQM', '4TXB', 'R9HF', '2WZN', 'D3PV', '8YCJ', 'G5EA'];
  // 64 words, so `n % 64` on a uint32 has no modulo bias. The real app uses the
  // 7,776-word EFF list (~77 bits for 6 words); this list gives only ~36 bits.
  const WORDS = ['amber', 'anchor', 'atlas', 'basalt', 'beacon', 'birch', 'cedar', 'cinder', 'cobalt', 'comet', 'coral', 'delta', 'ember', 'falcon', 'fjord', 'garnet', 'glacier', 'granite', 'harbor', 'hazel', 'heron', 'indigo', 'iris', 'jade', 'juniper', 'kestrel', 'lantern', 'larch', 'ledger', 'lumen', 'marble', 'meadow', 'mesa', 'nectar', 'nimbus', 'oak', 'obsidian', 'onyx', 'opal', 'orbit', 'pewter', 'pine', 'prism', 'quartz', 'quill', 'raven', 'reef', 'rune', 'saffron', 'sable', 'slate', 'sparrow', 'summit', 'tarn', 'thistle', 'tundra', 'umber', 'vale', 'velvet', 'violet', 'willow', 'yarrow', 'zenith', 'zephyr'];

  function passphrase() {
    const buf = new Uint32Array(6);
    crypto.getRandomValues(buf);
    return Array.from(buf, (n) => WORDS[n % WORDS.length]).join('-');
  }
  // Recovery Key input ignores case, spaces and dashes; O→0, I/L→1 (blueprint §10.1).
  const normKey = (v) => v.toUpperCase().replace(/[\s-]/g, '').replace(/O/g, '0').replace(/[IL]/g, '1');
  function greeting() {
    const h = new Date().getHours();
    return h >= 5 && h < 12 ? 'Good morning' : h >= 12 && h < 18 ? 'Good afternoon' : 'Good evening';
  }

  // ---------- State ----------
  // Card finishes are vault settings, so locking does not reset them.
  const finishes = { 1: 'titanium', 2: 'platinum' };
  const fresh = () => ({
    pw: '', pwShown: false, verifying: false, unlocking: false,
    query: '', filter: 'all', banner: true,
    revealed: null, secs: 30, sheet: null, lastAuth: 0, toast: '',
    addType: null, addPw: '', addVals: {}, addCvv: false,
    rkStep: 'show', rkA: '', rkB: '', rkErr: false
  });
  let S = fresh();
  let current = null;
  let tick = null;
  let toastTimer = null;
  let fx = {}; // one-shot animation flags, consumed by the next render

  function set(patch) { Object.assign(S, patch); render(); }
  const later = (ms, fn) => setTimeout(fn, calm.matches ? 0 : ms);

  function stopReveal() {
    clearInterval(tick);
    tick = null;
    S.revealed = null;
    S.secs = 30;
  }
  // One field at a time; re-masks after 30 s (blueprint §17).
  function startReveal(field) {
    clearInterval(tick);
    S.revealed = field;
    S.secs = 30;
    fx.reveal = true;
    render();
    tick = setInterval(() => {
      S.secs -= 1;
      if (S.secs <= 0) { stopReveal(); render(); return; }
      const t = document.getElementById('reveal-timer');
      if (t) t.textContent = `Hides in ${S.secs} s`;
    }, 1000);
  }
  function toast(text) {
    clearTimeout(toastTimer);
    set({ toast: text });
    toastTimer = setTimeout(() => set({ toast: '' }), 2600);
  }
  // Critical fields need a fresh biometric unless one happened in the last 60 s.
  function critical(kind, field) {
    if (Date.now() - S.lastAuth < 60000) { doCritical(kind, field); return; }
    fx.sheet = true; // the sheet animates in only on the render that opens it
    set({ sheet: { kind, field } });
    focusId('bio-confirm');
  }
  function doCritical(kind, field) {
    if (kind === 'reveal') startReveal(field);
    else toast('Copied · clears in 60 s');
  }
  function focusId(id) {
    const el = document.getElementById(id);
    if (el) el.focus();
  }

  // ---------- Shared fragments ----------
  const topbar = (back, label) => `<div class="topbar">
    <a class="icon-btn strong" href="${back}" aria-label="${label}">${icon('back', 24)}</a>
    <a class="icon-btn accent" href="#/lock" aria-label="Lock now">${icon('lock')}</a>
  </div>`;
  const copyBtn = (what, label) => `<button type="button" id="copy-${what}" class="icon-btn" data-action="copy" data-arg="${what}" aria-label="${label}">${icon('copy', 20)}</button>`;
  const revealBtn = (field, on, what) => `<button type="button" id="reveal-${field}" class="icon-btn" data-action="reveal" data-arg="${field}" aria-label="${on ? 'Hide' : 'Reveal'} ${what}" aria-pressed="${on}">${icon(on ? 'eyeOff' : 'eye', 20)}</button>`;
  const frow = (label, valueHtml, actions = '') => `<div class="frow"><div class="frow-text"><span class="frow-label">${label}</span><span class="frow-value">${valueHtml}</span></div>${actions}</div>`;
  const criticalBadge = `<span class="badge">${icon('lock', 10, '', 'stroke-width="2.5"')}CRITICAL</span>`;
  const timer = (on) => (on ? `<span class="timer" id="reveal-timer">Hides in ${S.secs} s</span>` : '');
  const secret = (on, shown, masked) => `<span class="secret${on && fx.reveal ? ' fade-in' : ''}">${on ? shown : masked}</span>`;
  const finishName = (f) => FINISHES.find(([id]) => id === f)[1];

  function cardFace(c, small) {
    const f = finishes[c.id];
    return `<div class="card-face finish-${f}${small ? ' sm' : ''}" role="img" aria-label="${esc(c.nick)}: ${c.network} ending ${c.last4}, ${finishName(f)} finish">
      <svg class="engrave" viewBox="0 0 350 221" preserveAspectRatio="none" aria-hidden="true"><path d="${CARD_ENGRAVING}"/></svg>
      <div class="card-top"><span>${esc(c.issuer.toUpperCase())}</span>${small ? '' : `<span>${finishName(f).toUpperCase()}</span>`}</div>
      <div class="card-bottom">
        <div><div class="card-num">•••• ${c.last4}</div>${small ? '' : `<div class="card-name">${esc(c.holder.toUpperCase())}</div>`}</div>
        <span class="network">${c.network}</span>
      </div>
    </div>`;
  }

  // ---------- Screens ----------
  function lock() {
    return `<section class="screen lock${S.unlocking ? ' unlocking' : ''}">
      <div class="stack intro"><h1 class="wordmark">Vaultaire</h1><p class="small muted">You have things worth protecting.</p></div>
      <div class="grow"><div class="ring">
        <svg class="rosette" width="300" height="300" viewBox="0 0 300 300" aria-hidden="true"><path d="${LOCK_ROSETTE}"/></svg>
        <svg class="track" width="148" height="148" viewBox="0 0 148 148" fill="none" aria-hidden="true"><circle cx="74" cy="74" r="70" stroke="rgba(255,245,230,0.10)" stroke-width="1.5"/><circle class="arc" cx="74" cy="74" r="70" stroke="#C9B38A" stroke-width="1.5" stroke-linecap="round" transform="rotate(-90 74 74)"/></svg>
        <span class="ring-core">${icon('lock', 36)}</span>
      </div></div>
      <div class="stack actions">
        <button type="button" id="bio-unlock" class="btn btn-primary" data-action="bioUnlock">${icon('finger')}Unlock with fingerprint</button>
        <a class="btn btn-secondary" href="#/password">Use vault password</a>
        <p class="beta">UNAUDITED BETA · DO NOT STORE REAL CREDENTIALS</p>
      </div>
    </section>`;
  }

  function password() {
    const button = S.verifying
      ? `<button class="btn btn-primary" type="button" disabled aria-live="polite"><svg class="dial" width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true"><circle cx="12" cy="12" r="9" stroke="currentColor" stroke-opacity="0.25" stroke-width="1.5"/><path d="M12 3a9 9 0 0 1 9 9" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>Verifying…</button>`
      : `<button class="btn btn-primary" type="submit"${S.pw ? '' : ' disabled'}>Unlock</button>`;
    return `<section class="screen${fx.enter ? ' enter' : ''}">
      <div class="topbar"><a class="icon-btn strong" href="#/lock" aria-label="Back">${icon('back', 24)}</a></div>
      <form class="pad grow col pw-form" data-submit="unlock" autocomplete="off">
        <div class="stack intro"><h1 class="display-l">Enter vault password</h1><p class="body muted">Your phone will then ask for its screen lock. Both are needed to open the vault this way.</p></div>
        <div class="field pw-field">
          <label class="label" for="vault-pw">Vault password</label>
          <div class="inline">
            <input id="vault-pw" class="input mono" type="${S.pwShown ? 'text' : 'password'}" value="${esc(S.pw)}" data-bind="pw" spellcheck="false" autocomplete="off"${S.verifying ? ' disabled' : ''}>
            <button type="button" id="pw-toggle" class="icon-btn boxed" data-action="togglePw" aria-label="${S.pwShown ? 'Hide' : 'Show'} password" aria-pressed="${S.pwShown}">${icon(S.pwShown ? 'eyeOff' : 'eye')}</button>
          </div>
          <p class="faint">Forgot it? You will need your Recovery Key.</p>
        </div>
        <div class="grow"></div>
        ${button}
      </form>
    </section>`;
  }

  function vault() {
    const q = S.query.trim().toLowerCase();
    const items = ITEMS
      .filter((it) => S.filter === 'all' || (S.filter === 'fav' ? it.fav : it.type === S.filter))
      .filter((it) => !q || (it.title + ' ' + it.sub).toLowerCase().includes(q));
    const rows = items.map((it) => {
      const lead = it.card ? `<span class="type-tile"><span class="mini finish-${finishes[it.card]}"></span></span>` : typeTile(it.type);
      const inner = `${lead}<span class="item-text"><span class="item-title">${esc(it.title)}</span><span class="item-sub">${esc(it.sub)}</span></span>${it.fav ? icon('star', 16, 'fav', 'fill="currentColor" role="img" aria-label="Favorite"') : ''}`;
      return it.href ? `<a class="item" href="${it.href}">${inner}${icon('chev', 18, 'chev')}</a>` : `<div class="item">${inner}</div>`;
    }).join('');
    const showWallet = !q && (S.filter === 'all' || S.filter === 'card');
    const wallet = showWallet ? `<h2 class="label gutter section-label">Cards</h2>
      <div class="wallet" id="wallet" data-keep-scroll>${Object.values(CARDS).map((c) => `<a class="wallet-card" href="#/card/${c.id}" aria-label="Open ${esc(c.nick)}">${cardFace(c, true)}</a>`).join('')}</div>` : '';
    const empty = `<div class="empty"><p class="display">Nothing here</p><p class="small muted">No items match this search or filter.</p></div>`;
    const banner = S.banner ? `<div class="banner" role="status">${icon('alert', 20)}<p>3 failed password attempts since your last unlock</p><button type="button" id="dismiss" class="text-btn" data-action="dismissBanner">Dismiss</button></div>` : '';
    return `<section class="screen fixed${fx.enter ? ' enter' : ''}">
      <div class="vault-head">
        <div class="col"><span class="label">Vault</span><h1 class="display">${greeting()}</h1></div>
        <div class="inline">
          <a class="icon-btn" href="#/settings" aria-label="Settings">${icon('sliders')}</a>
          <a class="icon-btn accent" href="#/lock" aria-label="Lock now">${icon('lock')}</a>
        </div>
      </div>
      <a class="health" href="#/settings">
        <span class="health-ok">${icon('shield', 18)}Protections on</span>
        <span class="health-sep" aria-hidden="true"></span>
        <span class="health-warn">1 thing to check</span>
        ${icon('chev', 16, 'chev')}
      </a>
      <div class="search">${icon('search', 20)}<input id="search" class="input" type="search" placeholder="Search ${ITEMS.length} items" aria-label="Search vault" value="${esc(S.query)}" data-bind="query" autocomplete="off" spellcheck="false"></div>
      <div class="chips" id="chips" data-keep-scroll role="toolbar" aria-label="Filter by type">${CHIPS.map(([id, label]) => `<button type="button" id="chip-${id}" class="chip" data-action="filter" data-arg="${id}" aria-pressed="${S.filter === id}">${label}</button>`).join('')}</div>
      ${banner}
      <div class="list" id="vault-list" data-keep-scroll>
        ${wallet}
        ${items.length ? `<h2 class="label gutter section-label">${S.filter === 'all' && !q ? 'All items' : 'Results'} · ${items.length}</h2>` : ''}
        ${rows || empty}
        <p class="list-foot">Kept only on this phone. Never synced, never uploaded.</p>
      </div>
      <a class="fab" href="#/add" aria-label="Add item">${icon('plus', 24, '', 'stroke-width="1.75"')}</a>
    </section>`;
  }

  function card(id) {
    const c = CARDS[id] || CARDS[1];
    const onNum = S.revealed === 'number';
    const onCvv = S.revealed === 'cvv';
    const cvvRow = c.cvv
      ? `<div class="frow"><div class="frow-text">
          <span class="frow-label">Security code (CVV) ${criticalBadge}</span>
          ${secret(onCvv, c.cvv, '•••')}
          ${onCvv ? timer(true) : '<span class="timer">Stored at your request · never autofilled</span>'}
        </div>${revealBtn('cvv', onCvv, 'security code')}${copyBtn('cvv', 'Copy security code')}</div>`
      : frow('Security code (CVV)', '<span class="muted">Not stored</span>');
    const note = c.cvv
      ? `<p class="assurance warn-note">${icon('alert', 18)}<span>This card's CVV is stored. With the number and expiry, it is everything needed to shop online with this card. Remove it if you don't need it here.</span></p>`
      : `<p class="assurance">${icon('shield', 18)}<span>No CVV stored for this card. Number, expiry and CVV together are all an online thief needs.</span></p>`;
    return `<section class="screen${fx.enter ? ' enter' : ''}" id="scr-card" data-keep-scroll>
      ${topbar('#/vault', 'Back to vault')}
      <div class="detail-head">
        <div class="col"><span class="label">Card</span><h1 class="display">${esc(c.nick)}</h1></div>
        ${cardFace(c, false)}
        <div class="finishes" role="group" aria-label="Card finish">
          ${FINISHES.map(([f, name]) => `<button type="button" id="finish-${f}" class="swatch-btn" data-action="finish" data-arg="${c.id}:${f}" aria-label="${name} finish" aria-pressed="${finishes[c.id] === f}"><span class="swatch finish-${f}"></span></button>`).join('')}
          <span class="finish-name">${finishName(finishes[c.id])}</span>
        </div>
      </div>
      <div class="fields">
        <h2 class="label">Details</h2>
        ${frow('Cardholder', esc(c.holder), copyBtn('name', 'Copy cardholder name'))}
        <div class="frow"><div class="frow-text">
          <span class="frow-label">Card number ${criticalBadge}</span>
          ${secret(onNum, c.number, '•••• •••• •••• ' + c.last4)}
          ${timer(onNum)}
        </div>${revealBtn('number', onNum, 'card number')}${copyBtn('number', 'Copy card number')}</div>
        ${frow('Expiry', `<span class="mono">${c.expiry}</span>`, copyBtn('expiry', 'Copy expiry date'))}
        ${cvvRow}
        ${frow('Issuer', esc(c.issuer))}
        ${note}
        <p class="assurance">${icon('lock', 18)}<span>Card PINs are never stored.</span></p>
      </div>
    </section>`;
  }

  function login() {
    const on = S.revealed === 'login';
    return `<section class="screen${fx.enter ? ' enter' : ''}" id="scr-login" data-keep-scroll>
      ${topbar('#/vault', 'Back to vault')}
      <div class="title-row">${typeTile('login')}<div class="col"><span class="label">Login</span><h1 class="display">Primary email</h1></div></div>
      <div class="fields">
        <h2 class="label">Details</h2>
        ${frow('Username', 'alex.morgan@example.com', copyBtn('username', 'Copy username'))}
        <div class="frow"><div class="frow-text">
          <span class="frow-label">Password</span>
          ${secret(on, 'harbor-violet-onyx-ledger-canyon-rune', '••••••••••••')}
          ${timer(on)}
        </div>${revealBtn('login', on, 'password')}${copyBtn('password', 'Copy password')}</div>
        <div class="frow"><div class="frow-text">
          <span class="frow-label">Strength</span>
          <span class="meter" aria-hidden="true"><span></span><span></span><span></span><span></span><span></span></span>
          <span class="frow-value small">Very strong · 6-word passphrase</span>
        </div></div>
        ${frow('Website', 'mail.example.com')}
        ${frow('Password history', '2 earlier versions kept')}
        <p class="faint">Tags: personal · Edited 14 Sep 2026</p>
      </div>
    </section>`;
  }

  function add() {
    if (!S.addType) {
      return `<section class="screen${fx.enter ? ' enter' : ''}" id="scr-add" data-keep-scroll>
        <div class="topbar"><a class="icon-btn strong" href="#/vault" aria-label="Cancel">${icon('close')}</a></div>
        <div class="gutter stack-tight"><h1 class="display">Add item</h1><p class="body muted">What would you like to keep?</p></div>
        <div class="tiles">${TYPES.map((t) => `<button type="button" id="tile-${t.id}" class="tile" data-action="pick" data-arg="${t.id}">${typeTile(t.id)}<span><span class="tile-name">${t.label}</span><span class="tile-desc">${t.desc}</span></span></button>`).join('')}</div>
      </section>`;
    }
    const t = TYPES.find((x) => x.id === S.addType);
    const input = (id, extra = '') => `<input id="${id}" class="input${extra.includes('mono') ? ' mono' : ''}" type="text" value="${esc(S.addVals[id] || '')}" data-bind="addField" data-key="${id}" spellcheck="false" autocomplete="off" ${extra.replace('mono', '')}>`;
    const fields = TEMPLATES[t.id].map((f, i) => {
      const id = 'field-' + i;
      let control;
      if (f.pw) {
        control = `<input id="${id}" class="input mono" type="text" value="${esc(S.addPw)}" data-bind="addPw" spellcheck="false" autocomplete="off">
          <button type="button" id="generate" class="btn btn-secondary btn-small" data-action="generate">${icon('refresh', 18)}Generate 6-word passphrase</button>`;
      } else if (f.area) {
        control = `<textarea id="${id}" class="input" rows="5" placeholder="${esc(f.hint)}" data-bind="addField" data-key="${id}">${esc(S.addVals[id] || '')}</textarea>`;
      } else {
        control = input(id, `placeholder="${esc(f.hint)}"`);
      }
      return `<div class="field"><label class="label" for="${id}">${esc(f.label)}${f.critical ? ' ' + criticalBadge : ''}</label>${control}</div>`;
    }).join('');
    // CVV is opt-in per card, off by default, with the risk stated up front (blueprint §13).
    const cvv = t.id === 'card' ? `<div class="optin">
        <label class="switch-row" for="cvv-optin">
          <span class="col"><span class="srow-label">Also store the security code</span><span class="faint">Off by default. Most people don't need it here.</span></span>
          <input id="cvv-optin" type="checkbox" role="switch" class="switch" data-action="toggleCvv"${S.addCvv ? ' checked' : ''}>
        </label>
        ${S.addCvv ? `<div class="notice${fx.reveal ? ' fade-in' : ''}">${icon('alert', 20)}<p>Card number, expiry and CVV together are everything needed to shop online with this card. It is kept as a critical field, needs your fingerprint to show, and is never autofilled.</p></div>
        <div class="field"><label class="label" for="field-cvv">Security code (CVV) ${criticalBadge}</label>${input('field-cvv', 'mono inputmode="numeric" maxlength="4" placeholder="3 or 4 digits"')}</div>` : ''}
        <p class="assurance">${icon('lock', 18)}<span>There is no field for the card PIN. It is never stored.</span></p>
      </div>` : '';
    return `<section class="screen fixed">
      <div class="topbar"><button type="button" id="add-back" class="icon-btn strong" data-action="addBack" aria-label="Choose another type">${icon('back', 24)}</button></div>
      <div class="title-row">${typeTile(t.id)}<div class="col"><span class="label">Add item</span><h1 class="display">New ${t.label.toLowerCase()}</h1></div></div>
      <div class="form scroll" id="add-form" data-keep-scroll>${fields}${cvv}</div>
      <div class="savebar"><a class="btn btn-primary" href="#/vault">Save item</a></div>
    </section>`;
  }

  function recovery() {
    const steps = Array.from({ length: 7 }, (_, i) => `<span class="${i < 6 ? 'on' : ''}"></span>`).join('');
    const head = `<div><span class="label">Step 6 of 7 · Setup</span><div class="steps" aria-hidden="true">${steps}</div></div>`;
    const enter = fx.enter || fx.step ? ' enter' : '';
    if (S.rkStep === 'verify') {
      return `<section class="screen rk${enter}">${head}
        <form class="grow col" data-submit="rkCheck" autocomplete="off">
          <div class="stack intro"><h1 class="display-l">Check your copy</h1><p class="body muted">Type two groups from what you wrote. Letter case, spaces and dashes don't matter.</p></div>
          <div class="pair">
            <div class="field"><label class="label" for="rk-a">Group 3</label><input id="rk-a" class="input key-input" type="text" maxlength="6" value="${esc(S.rkA)}" data-bind="rkA" autocapitalize="characters" spellcheck="false" autocomplete="off"></div>
            <div class="field"><label class="label" for="rk-b">Group 6</label><input id="rk-b" class="input key-input" type="text" maxlength="6" value="${esc(S.rkB)}" data-bind="rkB" autocapitalize="characters" spellcheck="false" autocomplete="off"></div>
          </div>
          ${S.rkErr ? `<p class="error" role="alert">${icon('errCircle', 18)}That doesn't match. Check your handwriting and try again.</p>` : ''}
          <p class="faint hint">Hint for the prototype: group 3 is R9HF, group 6 is 8YCJ.</p>
          <div class="grow"></div>
          <div class="buttons">
            <button type="submit" class="btn btn-primary">Confirm</button>
            <button type="button" id="rk-show" class="btn btn-ghost" data-action="rkShow">Show the key again</button>
          </div>
        </form>
      </section>`;
    }
    if (S.rkStep === 'done') {
      return `<section class="screen rk${enter}">${head}
        <div class="done">
          <div class="done-mark">
            <svg class="rosette" width="160" height="160" viewBox="0 0 160 160" aria-hidden="true"><path d="${DONE_ROSETTE}"/></svg>
            <span class="done-ring">${icon('check', 36)}</span>
          </div>
          <h1 class="display-l">Recovery Kit saved</h1>
          <p class="body muted">Store the paper somewhere safe, away from this phone. Every 14 days we'll ask for your vault password to keep it fresh.</p>
        </div>
        <a class="btn btn-primary" href="#/vault">Open my vault</a>
      </section>`;
    }
    return `<section class="screen rk${enter}">${head}
      <div class="stack intro"><h1 class="display-l">Your Recovery Key</h1><p class="body muted">Write it down by hand. With your vault password, it is the only way back in if this phone loses its unlock keys.</p></div>
      <div class="keybox" role="group" aria-label="Recovery Key, 7 groups">
        <svg class="engrave" viewBox="0 0 350 221" preserveAspectRatio="none" aria-hidden="true"><path d="${CARD_ENGRAVING}"/></svg>
        ${RECOVERY_KEY.map((g, i) => `<div class="kg"><span class="kg-n">${i + 1}</span><span class="kg-t">${g}</span></div>`).join('')}
      </div>
      <p class="faint sample">Sample key for this prototype.</p>
      <div class="notice">${icon('alert', 20)}<p>Keep this away from your phone and your cloud photos. We will never ask you for it.</p></div>
      <p class="faint after">Screenshots, PDF and printing are blocked on this screen.</p>
      <div class="grow"></div>
      <button type="button" id="rk-verify" class="btn btn-primary" data-action="rkVerify">I have written it down</button>
    </section>`;
  }

  function settings() {
    const cvvCount = Object.values(CARDS).filter((c) => c.cvv).length;
    const protection = [
      ['Unlock methods', 'Fingerprint + password'], ['Auto-lock', 'After 2 min'], ['Clear clipboard', 'After 60 s'],
      ['Critical fields', 'Fingerprint after 60 s'], ['Card security codes', `Stored for ${cvvCount} of ${Object.keys(CARDS).length} cards`],
      ['Password check', 'Every 14 days'], ['Security log', '3 failed attempts']
    ];
    const device = [
      ['Screen lock', 'On', true], ['Class 3 biometric', 'Enrolled', true], ['Key storage', 'StrongBox', true],
      ['Security patch', '41 days old', true], ['USB debugging', 'On', false, 'Turn it off when you are not developing.'],
      ['Third-party keyboard', 'None', true], ['Last backup', '12 days ago', true]
    ];
    const simple = (label, value) => `<div class="srow"><span class="srow-label">${label}</span><span class="srow-value">${value}</span></div>`;
    return `<section class="screen${fx.enter ? ' enter' : ''}" id="scr-settings" data-keep-scroll>
      ${topbar('#/vault', 'Back to vault')}
      <h1 class="display gutter">Settings</h1>
      <div class="sections">
        <section class="section"><h2 class="label">Protection</h2>
          <div class="group">${protection.map(([l, v]) => simple(l, v)).join('')}</div>
        </section>
        <section class="section"><h2 class="label">Device protection check</h2>
          <div class="group">${device.map(([l, v, ok, note]) => `<div class="srow">
            ${ok ? icon('checkCircle', 20, 'ok', 'role="img" aria-label="Passed"') : icon('alert', 20, 'warn', 'role="img" aria-label="Warning"')}
            <span class="srow-main"><span class="srow-label">${l}</span>${note ? `<span class="srow-note">${note}</span>` : ''}</span>
            <span class="srow-value">${v}</span></div>`).join('')}</div>
        </section>
        <section class="section"><h2 class="label">Backup and recovery</h2>
          <div class="group">
            <div class="srow"><span class="srow-main"><span class="srow-label">Create encrypted backup</span><span class="srow-sub">Needs your vault password</span></span></div>
            <div class="srow"><span class="srow-label">Restore or import</span></div>
            <a class="srow" href="#/recovery"><span class="srow-label">Recovery Kit</span>${icon('chev', 18, 'chev')}</a>
          </div>
        </section>
        <section class="section"><h2 class="label">Appearance</h2>
          <div class="group">${simple('Theme', 'Dark · Obsidian')}${simple('Haptics', 'On')}</div>
        </section>
        <section class="section"><h2 class="label">About</h2>
          <div class="group">${simple('Version', '<span class="mono">0.1 prototype</span>')}<div class="srow"><span class="srow-label">How Vaultaire protects you</span></div>${simple('Licence', 'GPL-3.0-or-later')}</div>
        </section>
        <div class="notice">${icon('alert', 20)}<p>Unaudited beta. Do not store real credentials until an independent security review is published.</p></div>
        <button type="button" id="delete-vault" class="btn btn-danger" data-action="notBuilt" data-arg="Vault deletion">Delete vault</button>
      </div>
    </section>`;
  }

  function overlays() {
    let html = '';
    if (S.toast) {
      html += `<div class="toast" role="status"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true"><circle cx="12" cy="12" r="9" stroke="rgba(255,245,230,0.12)" stroke-width="1.5"/><circle cx="12" cy="12" r="9" stroke="#C9B38A" stroke-width="1.5" stroke-linecap="round" stroke-dasharray="42 57" transform="rotate(-90 12 12)"/></svg>${esc(S.toast)}</div>`;
    }
    if (S.sheet) {
      const verb = S.sheet.kind === 'copy' ? 'copy' : 'show';
      const what = S.sheet.field === 'cvv' ? 'security code' : 'card number';
      html += `<div class="backdrop${fx.sheet ? ' fade-in' : ''}" data-action="cancelSheet"><div class="sheet${fx.sheet ? ' rise' : ''}" role="dialog" aria-modal="true" aria-labelledby="bio-title">
        ${icon('finger', 52, '', 'stroke-width="1.2"')}
        <h2 id="bio-title">Confirm it's you</h2>
        <p class="body muted">Touch the sensor to ${verb} the ${what}.</p>
        <button type="button" id="bio-confirm" class="btn btn-primary" data-action="confirmSheet">Simulate fingerprint</button>
        <button type="button" id="bio-cancel" class="btn btn-ghost" data-action="cancelSheet">Cancel</button>
      </div></div>`;
    }
    return html;
  }

  const ROUTES = {
    lock: { title: 'Lock screen', render: lock },
    password: { title: 'Vault password', render: password },
    vault: { title: 'Vault', render: vault },
    card: { title: 'Card · critical fields', render: card, link: '#/card/1' },
    login: { title: 'Login · concealed field', render: login },
    add: { title: 'Add item', render: add },
    recovery: { title: 'Recovery Kit', render: recovery },
    settings: { title: 'Settings', render: settings }
  };
  function route() {
    const [name, param] = location.hash.replace(/^#\/?/, '').split('/');
    return Object.prototype.hasOwnProperty.call(ROUTES, name) ? { name, param, key: name + '/' + (param || '') } : { name: 'lock', key: 'lock/' };
  }

  // ---------- Rendering ----------
  function render() {
    const r = route();
    const active = document.activeElement;
    const keepFocus = active && active.id && app.contains(active) ? { id: active.id, sel: selection(active) } : null;
    const scrolls = Array.from(app.querySelectorAll('[data-keep-scroll]'), (el) => [el.id, el.scrollTop, el.scrollLeft]);

    app.innerHTML = ROUTES[r.name].render(r.param) + overlays();
    fx = {};
    document.title = ROUTES[r.name].title + ' · Vaultaire prototype';
    renderNav(r.name);

    for (const [id, top, left] of scrolls) {
      const el = document.getElementById(id);
      if (el) { el.scrollTop = top; el.scrollLeft = left; }
    }
    if (keepFocus) {
      const el = document.getElementById(keepFocus.id);
      if (el) {
        el.focus({ preventScroll: true });
        if (keepFocus.sel) { try { el.setSelectionRange(keepFocus.sel[0], keepFocus.sel[1]); } catch (e) { /* not a text field */ } }
      }
    }
  }
  function selection(el) {
    try { return el.selectionStart == null ? null : [el.selectionStart, el.selectionEnd]; } catch (e) { return null; }
  }
  function renderNav(name) {
    nav.innerHTML = `<p class="label">Vaultaire · UI prototype</p>
      ${Object.keys(ROUTES).map((k) => `<a href="${ROUTES[k].link || '#/' + k}"${k === name ? ' aria-current="page"' : ''}>${ROUTES[k].title}</a>`).join('')}
      <p class="note">Design mockup. No encryption, no storage, sample data only.</p>`;
  }

  function onRoute() {
    const next = route();
    if (next.key !== current) {
      const prev = current ? current.split('/')[0] : null;
      stopReveal(); // secrets re-mask on navigation
      S.sheet = null;
      if (next.name === 'lock') S = fresh(); // locking wipes session state, instantly and without animation
      else fx.enter = true;
      if (prev === 'add') Object.assign(S, { addType: null, addPw: '', addVals: {}, addCvv: false });
      if (prev === 'recovery') Object.assign(S, { rkStep: 'show', rkA: '', rkB: '', rkErr: false });
      if (prev === 'password') Object.assign(S, { verifying: false });
      current = next.key;
      app.innerHTML = ''; // new screen: do not carry focus or scroll over
    }
    render();
    const h = app.querySelector('h1');
    if (h) { h.setAttribute('tabindex', '-1'); h.focus({ preventScroll: true }); }
  }

  // ---------- Events ----------
  const ACTIONS = {
    // The ring closes, then the vault is composed (the real app renders nothing before the key is available).
    bioUnlock: () => {
      if (S.unlocking) return;
      set({ unlocking: true });
      later(320, () => { location.hash = '#/vault'; });
    },
    togglePw: () => set({ pwShown: !S.pwShown }),
    dismissBanner: () => set({ banner: false }),
    filter: (id) => set({ filter: id }),
    finish: (arg) => { const [id, f] = arg.split(':'); finishes[id] = f; render(); },
    reveal: (field) => {
      if (S.revealed === field) { stopReveal(); render(); return; }
      if (field === 'number' || field === 'cvv') critical('reveal', field);
      else startReveal(field);
    },
    copy: (what) => (what === 'number' || what === 'cvv' ? critical('copy', what) : toast('Copied · clears in 60 s')),
    confirmSheet: () => {
      const { kind, field } = S.sheet;
      S.sheet = null;
      S.lastAuth = Date.now();
      doCritical(kind, field);
    },
    cancelSheet: () => set({ sheet: null }),
    pick: (type) => { fx.enter = true; set({ addType: type, addPw: type === 'login' ? passphrase() : '', addVals: {}, addCvv: false }); },
    addBack: () => { fx.enter = true; set({ addType: null, addVals: {}, addCvv: false }); },
    toggleCvv: () => { fx.reveal = !S.addCvv; set({ addCvv: !S.addCvv }); },
    generate: () => set({ addPw: passphrase() }),
    rkVerify: () => { fx.step = true; set({ rkStep: 'verify', rkA: '', rkB: '', rkErr: false }); },
    rkShow: () => { fx.step = true; set({ rkStep: 'show' }); },
    notBuilt: (what) => toast(what + ' is not in the prototype yet')
  };
  // Each returns true when the screen must re-render.
  const BINDS = {
    pw: (v) => { S.pw = v; return true; },
    query: (v) => { S.query = v; return true; },
    addPw: (v) => { S.addPw = v; return false; },
    addField: (v, el) => { S.addVals[el.dataset.key] = v; return false; },
    rkA: (v) => { S.rkA = v; S.rkErr = false; return true; },
    rkB: (v) => { S.rkB = v; S.rkErr = false; return true; }
  };
  const SUBMITS = {
    // The dial turns while the (simulated) key derivation runs: real progress, not decoration.
    unlock: () => {
      if (!S.pw || S.verifying) return;
      set({ verifying: true });
      later(900, () => { location.hash = '#/vault'; });
    },
    rkCheck: () => {
      const ok = normKey(S.rkA) === RECOVERY_KEY[2] && normKey(S.rkB) === RECOVERY_KEY[5];
      if (ok) fx.step = true;
      set(ok ? { rkStep: 'done' } : { rkErr: true });
    }
  };

  app.addEventListener('click', (e) => {
    const el = e.target.closest('[data-action]');
    if (!el || !app.contains(el)) return;
    if (el.classList.contains('backdrop') && e.target !== el) return; // clicks inside the sheet
    const fn = ACTIONS[el.dataset.action];
    if (fn) fn(el.dataset.arg);
  });
  app.addEventListener('input', (e) => {
    const el = e.target.closest('[data-bind]');
    if (!el) return;
    const fn = BINDS[el.dataset.bind];
    if (fn && fn(el.value, el)) render();
  });
  app.addEventListener('submit', (e) => {
    e.preventDefault();
    const fn = SUBMITS[e.target.dataset.submit];
    if (fn) fn();
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && S.sheet) set({ sheet: null });
  });
  // Secrets re-mask when the app goes to the background.
  document.addEventListener('visibilitychange', () => {
    if (document.hidden && (S.revealed || S.sheet)) { stopReveal(); S.sheet = null; render(); }
  });
  window.addEventListener('hashchange', onRoute);

  onRoute();
})();
