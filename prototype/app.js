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
    face: '<path d="M4 8V6a2 2 0 0 1 2-2h2M16 4h2a2 2 0 0 1 2 2v2M20 16v2a2 2 0 0 1-2 2h-2M8 20H6a2 2 0 0 1-2-2v-2"/><path d="M9 9v1.5M15 9v1.5M12.5 9v4h-1"/><path d="M9 15.5c1.7 1.3 4.3 1.3 6 0"/>',
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
    other: '<circle cx="6" cy="12" r="1.2"/><circle cx="12" cy="12" r="1.2"/><circle cx="18" cy="12" r="1.2"/>',
    up: '<path d="M12 19V5M6 11l6-6 6 6"/>',
    down: '<path d="M12 5v14M6 13l6 6 6-6"/>',
    pin: '<path d="M9 4h6l-1 6 3 3H7l3-3z"/><path d="M12 13v7"/>',
    folder: '<path d="M3.5 7.5a2 2 0 0 1 2-2h4l2 2h7a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z"/>',
    phone: '<path d="M6.5 4h3l1.5 4-2 1.5a11 11 0 0 0 5.5 5.5l1.5-2 4 1.5v3a2 2 0 0 1-2 2A15.5 15.5 0 0 1 4.5 6a2 2 0 0 1 2-2z"/>',
    calendar: '<rect x="4" y="5.5" width="16" height="14.5" rx="2"/><path d="M4 10h16M8.5 3.5v4M15.5 3.5v4"/>',
    codes: '<path d="M10 7h10M10 12h10M10 17h10"/><path d="M4.5 6.5l1.2-.7v3.4M4.3 11h2.2l-2.2 2.6h2.2M4.3 16h2.2l-1.1 1.2 1.1 1.3H4.3"/>'
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

  // ---------- Platform: the Android or the iPhone version of the design ----------
  // The two apps differ only where the platforms do (threat-model §10): Face ID instead of a fingerprint,
  // the passcode instead of the screen lock, and no screenshot blocking on iPhone. The first visit follows
  // the device; the choice is then remembered in this browser.
  const PLATFORM_KEY = 'vaultaire-prototype-platform';
  function loadPlatform() {
    const asked = new URLSearchParams(location.search).get('platform'); // e.g. ?platform=ios, for review links
    if (asked === 'ios' || asked === 'android') return asked;
    try {
      const saved = localStorage.getItem(PLATFORM_KEY);
      if (saved === 'ios' || saved === 'android') return saved;
    } catch (e) { /* storage unavailable: detect */ }
    const ua = navigator.userAgent;
    return /iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1) ? 'ios' : 'android';
  }
  let platform = loadPlatform();
  document.documentElement.dataset.platform = platform;
  const PLATFORMS = {
    android: {
      name: 'Android', bio: 'fingerprint', Bio: 'Fingerprint', bioIcon: 'finger', device: 'phone', credential: 'screen lock',
      prompt: (verb, what) => `Touch the sensor to ${verb} the ${what}.`,
      shots: 'Screenshots, PDF and printing are blocked on this screen.',
      checks: [
        ['Screen lock', 'On', true], ['Class 3 biometric', 'Enrolled', true], ['Key storage', 'StrongBox', true],
        ['Security patch', '41 days old', true], ['USB debugging', 'On', false, 'Turn it off when you are not developing.'],
        ['Third-party keyboard', 'None', true], ['Last backup', '12 days ago', true]
      ]
    },
    ios: {
      name: 'iPhone', bio: 'Face ID', Bio: 'Face ID', bioIcon: 'face', device: 'iPhone', credential: 'passcode',
      prompt: (verb, what) => `Look at your iPhone to ${verb} the ${what}.`,
      shots: 'iPhone cannot block screenshots. Vaultaire hides this screen while it is recorded or mirrored.',
      checks: [
        ['Passcode', 'On', true], ['Face ID', 'Enrolled', true], ['Key storage', 'Secure Enclave', true],
        ['iOS version', 'Up to date', true], ['Screen recording', 'Not active', true],
        ['Third-party keyboards', 'Blocked in Vaultaire', true], ['Last backup', '12 days ago', false, 'Back up after adding important items.']
      ]
    }
  };
  const P = () => PLATFORMS[platform];
  function setPlatform(next) {
    if (!PLATFORMS[next] || next === platform) return;
    platform = next;
    document.documentElement.dataset.platform = next;
    try { localStorage.setItem(PLATFORM_KEY, next); } catch (e) { /* not persisted; fine for a prototype */ }
    render();
    announce(`Showing the ${PLATFORMS[next].name} version`);
  }

  // ---------- Typeface: three candidates to compare on a phone (the two not chosen get removed) ----------
  const TYPEFACES = { plex: 'Plex', newsreader: 'Newsreader', schibsted: 'Schibsted' };
  const TYPE_KEY = 'vaultaire-prototype-typeface';
  const own = (obj, key) => Object.prototype.hasOwnProperty.call(obj, key);
  function loadTypeface() {
    const asked = new URLSearchParams(location.search).get('type'); // e.g. ?type=newsreader
    if (asked && own(TYPEFACES, asked)) return asked;
    try {
      const saved = localStorage.getItem(TYPE_KEY);
      if (saved && own(TYPEFACES, saved)) return saved;
    } catch (e) { /* storage unavailable: default */ }
    return 'plex';
  }
  let typeface = loadTypeface();
  document.documentElement.dataset.type = typeface;
  function setTypeface(next) {
    if (!own(TYPEFACES, next) || next === typeface) return;
    typeface = next;
    document.documentElement.dataset.type = next;
    try { localStorage.setItem(TYPE_KEY, next); } catch (e) { /* not persisted; fine for a prototype */ }
    render();
    announce(`Typeface: ${TYPEFACES[next]}`);
  }

  // ---------- Categories, in the order the vault shows them ----------
  const CATEGORIES = [
    { type: 'card', label: 'Cards', one: 'card', many: 'cards', add: 'Add card', note: 'Card numbers and security codes need your {bio} to show or copy.' },
    { type: 'login', label: 'Passwords', one: 'password', many: 'passwords', add: 'Add password', note: 'Passwords stay masked until you tap. Copies clear after 60 seconds.' },
    { type: 'devkey', label: 'API keys', one: 'key', many: 'keys', add: 'Add API key', note: 'Secrets need your {bio} to show or copy.' },
    { type: 'codes', label: 'Recovery codes', one: 'set', many: 'sets', add: 'Add recovery codes', note: 'Codes need your {bio} to show. Each code works once, so tick it off when you use it.' },
    { type: 'bank', label: 'Bank accounts', one: 'account', many: 'accounts', add: 'Add bank account', note: 'Account numbers need your {bio} to show or copy.' },
    { type: 'identity', label: 'Identity', one: 'document', many: 'documents', add: 'Add document', note: 'Document numbers need your {bio} to show or copy.' },
    { type: 'note', label: 'Notes', one: 'note', many: 'notes', add: 'Add note', note: 'Notes stay hidden until you tap.' },
    { type: 'membership', label: 'Memberships', one: 'membership', many: 'memberships', add: 'Add membership', note: 'Membership PINs stay masked until you tap.' },
    { type: 'other', label: 'Other', one: 'item', many: 'items', add: 'Add item', note: 'Your own fields, protected at the level you choose.' }
  ];
  const categoryOf = (type) => CATEGORIES.find((c) => c.type === type) || CATEGORIES[CATEGORIES.length - 1];
  const countLabel = (cat, n) => `${n} ${n === 1 ? cat.one : cat.many}`;

  // ---------- Home layout: the user's own order and visibility ----------
  // Cards are pinned first. Hiding only tidies the home screen: hidden items stay in search and backups.
  // The real app keeps this inside the encrypted vault (vault-format §5.5, key 11). The prototype
  // keeps it in this browser only, for convenience while testing.
  const DEFAULT_ORDER = CATEGORIES.map((c) => c.type).filter((t) => t !== 'card');
  const LAYOUT_KEY = 'vaultaire-prototype-layout';
  function loadLayout() {
    try {
      const saved = JSON.parse(localStorage.getItem(LAYOUT_KEY));
      if (saved && Array.isArray(saved.order)) {
        const order = saved.order.filter((t, i) => DEFAULT_ORDER.includes(t) && saved.order.indexOf(t) === i);
        for (const t of DEFAULT_ORDER) if (!order.includes(t)) order.push(t);
        return { order, hidden: saved.hidden && typeof saved.hidden === 'object' ? saved.hidden : {} };
      }
    } catch (e) { /* storage unavailable: use the default */ }
    return { order: DEFAULT_ORDER.slice(), hidden: {} };
  }
  const layout = loadLayout();
  function saveLayout() {
    try { localStorage.setItem(LAYOUT_KEY, JSON.stringify(layout)); } catch (e) { /* not persisted; fine for a prototype */ }
  }
  const orderedCategories = () => ['card', ...layout.order].map(categoryOf);
  const isHidden = (type) => type !== 'card' && !!layout.hidden[type];
  const announcer = document.getElementById('announce');
  const announce = (text) => { if (announcer) { announcer.textContent = ''; setTimeout(() => { announcer.textContent = text; }, 50); } };

  // ---------- Sample data (not real) ----------
  const FINISHES = [['obsidian', 'Obsidian'], ['titanium', 'Titanium'], ['platinum', 'Platinum'], ['ivory', 'Ivory'], ['champagne', 'Champagne']];
  // Hotline numbers are fictional (the reserved 555-01xx range).
  const CARDS = {
    1: { id: '1', nick: 'Travel card', network: 'VISA', issuer: 'Northbridge Bank', holder: 'Alex Morgan', number: '4929 1845 7720 4821', last4: '4821', expiry: '08 / 29', cvv: '317', hotline: '+1 800 555 0142' },
    2: { id: '2', nick: 'Everyday debit', network: 'MASTERCARD', issuer: 'Harbour Mutual', holder: 'Alex Morgan', number: '5412 7534 9021 1177', last4: '1177', expiry: '12 / 26', cvv: null, hotline: '+1 800 555 0163' },
    3: { id: '3', nick: 'Platinum', network: 'AMEX', issuer: 'American Express', holder: 'Alex Morgan', number: '3714 496353 98431', last4: '8431', expiry: '05 / 28', cvv: null, hotline: '+1 800 555 0177' },
    4: { id: '4', nick: 'Gold', network: 'AMEX', issuer: 'American Express', holder: 'Alex Morgan', number: '3787 344936 71006', last4: '1006', expiry: '04 / 27', cvv: null, hotline: '+1 800 555 0177' },
    5: { id: '5', nick: 'Business Platinum', network: 'AMEX', issuer: 'American Express', holder: 'Alex Morgan', number: '3782 822463 10005', last4: '0005', expiry: '10 / 29', cvv: null, hotline: '+1 800 555 0177' },
    6: { id: '6', nick: 'Blue Cash', network: 'AMEX', issuer: 'American Express', holder: 'Alex Morgan', number: '3400 000000 00009', last4: '0009', expiry: '02 / 28', cvv: null, hotline: '+1 800 555 0177' }
  };
  // Field tiers follow blueprint §17: normal (shown), concealed (tap), critical (biometric).
  const N = (label, value, extra) => Object.assign({ label, value, tier: 'normal', copy: false }, extra);
  const C = (label, value, extra) => Object.assign({ label, value, tier: 'concealed', copy: true }, extra);
  const X = (label, value, extra) => Object.assign({ label, value, tier: 'critical', copy: true }, extra);
  const ITEMS = [
    { id: 'c1', type: 'card', card: '1', title: 'Travel card', sub: 'Visa · •••• 4821', fav: true },
    { id: 'c2', type: 'card', card: '2', title: 'Everyday debit', sub: 'Mastercard · •••• 1177', fav: false },
    { id: 'c3', type: 'card', card: '3', title: 'Platinum', sub: 'American Express · •••• 8431', fav: false },
    { id: 'c4', type: 'card', card: '4', title: 'Gold', sub: 'American Express · •••• 1006', fav: false },
    { id: 'c5', type: 'card', card: '5', title: 'Business Platinum', sub: 'American Express · •••• 0005', fav: false },
    { id: 'c6', type: 'card', card: '6', title: 'Blue Cash', sub: 'American Express · •••• 0009', fav: false },
    {
      id: 'p1', type: 'login', title: 'Primary email', sub: 'alex.morgan@example.com', fav: true,
      fields: [N('Username', 'alex.morgan@example.com', { copy: true }), C('Password', 'harbor-violet-onyx-ledger-canyon-rune', { mono: true, meter: [5, 'Very strong · 6-word passphrase'] }), N('Website', 'mail.example.com'), N('Password history', '2 earlier versions kept')],
      foot: 'Tags: personal · Edited 14 Sep 2026'
    },
    {
      id: 'p2', type: 'login', title: 'Online banking', sub: 'amorgan · online.northbridge.example',
      fields: [N('Username', 'amorgan', { copy: true }), C('Password', 'Q7!vR2#mK9@xLp4s', { mono: true, meter: [4, 'Strong · 16 characters'] }), N('Website', 'online.northbridge.example')],
      foot: 'Linked to: Salary account · Edited 2 Aug 2026'
    },
    {
      id: 'p3', type: 'login', title: 'Code hosting', sub: 'alexm-dev · code.example',
      fields: [N('Username', 'alexm-dev', { copy: true }), C('Password', 'lantern-quartz-willow-ember-orbit-slate', { mono: true, meter: [5, 'Very strong · 6-word passphrase'] }), N('Website', 'code.example'), N('Two-step login', 'Authenticator app · recovery codes saved')],
      foot: 'Tags: work · Edited 21 Sep 2026'
    },
    {
      id: 'r1', type: 'codes', title: 'Code hosting', service: 'code.example', sub: 'code.example',
      codes: ['4f9a-2c71', '8b3e-d046', '19c5-7e2a', 'a6d8-30fb', 'e27b-9154', '0c3f-b8a9', '5d12-f7c0', '96ab-3e48', 'c0e5-1b93', '7f84-a26d']
    },
    {
      id: 'r2', type: 'codes', title: 'Primary email', service: 'mail.example.com', sub: 'mail.example.com',
      codes: ['KQ7M 4TXB', 'R9HF 2WZN', 'D3PV 8YCJ', 'G5EA 6LNU', 'P2SD 9HBK', 'W4MX 7QRT', 'J8CF 3VZE', 'N6TB 5AGY']
    },
    {
      id: 'k1', type: 'devkey', title: 'Cloud root key', sub: 'production · AKIA…MPLE',
      fields: [N('Service', 'Cloud provider'), N('Environment', 'production'), N('Key ID', 'AKIAIOSFODNN7EXAMPLE', { mono: true, copy: true }), X('Secret', 'wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY', { mono: true }), N('Scope', 'Full account access'), N('Expires', 'Never')],
      foot: 'Root keys can do anything in the account. Rotate this one if you ever doubt where it has been.'
    },
    {
      id: 'k2', type: 'devkey', title: 'Payments API', sub: 'live · secret key',
      fields: [N('Service', 'Payments'), N('Environment', 'live'), X('Secret key', 'pay_live_9fK2mQ7xR4vT1nB8wZ3cY6hJ', { mono: true }), N('Endpoint', 'api.payments.example')],
      foot: 'Tags: business · Edited 3 Sep 2026'
    },
    {
      id: 'b1', type: 'bank', title: 'Salary account', sub: 'Northbridge Bank · •••• 0932',
      fields: [N('Bank', 'Northbridge Bank'), N('Account holder', 'Alex Morgan', { copy: true }), N('Account type', 'Current'), X('Account number', '5521 4471 0932', { mono: true, keep: '0932' }), N('Sort code', '20-41-55', { mono: true, copy: true }), N('SWIFT', 'NBRGGB2L', { mono: true, copy: true })],
      foot: 'Linked to: Online banking'
    },
    {
      id: 'i1', type: 'identity', title: 'Passport', sub: 'Expires Mar 2031', expires: '2031-03-14',
      fields: [N('Document type', 'Passport'), N('Full name', 'Alex Jordan Morgan'), X('Document number', '552 081 443', { mono: true }), N('Issuing country', 'United Kingdom'), N('Expires', '14 Mar 2031')],
      foot: 'Tags: travel'
    },
    {
      id: 'i2', type: 'identity', title: 'Driving licence', sub: 'Expires Aug 2029', expires: '2029-08-02',
      fields: [N('Document type', 'Driving licence'), N('Full name', 'Alex Jordan Morgan'), X('Licence number', 'MORGA 853214 AJ9', { mono: true }), N('Categories', 'B, BE'), N('Expires', '2 Aug 2029')]
    },
    {
      id: 'n1', type: 'note', title: 'Home Wi-Fi and router', sub: 'Secure note',
      fields: [N('Network name', 'Morgan-Home-5G', { copy: true }), C('Wi-Fi password', 'tidal-cobalt-meadow-41', { mono: true }), C('Note', 'Router admin page is on the sticker under the router.\nAdmin password changed March 2026.\nGuest network off.', { multiline: true, copy: false })]
    },
    {
      id: 'm1', type: 'membership', title: 'Airline miles', sub: 'Skyward · Gold', expires: '2027-01-31', expiresWhat: 'Gold tier ends',
      fields: [N('Program', 'Skyward'), N('Member number', 'SKY 8830 1274', { mono: true, copy: true }), N('Tier', 'Gold'), N('Tier ends', '31 Jan 2027'), C('Account PIN', '4417', { mono: true })]
    }
  ];
  const itemById = (id) => ITEMS.find((it) => it.id === id);
  const hrefOf = (it) => (it.card ? `#/card/${it.card}` : `#/item/${it.id}`);

  const TYPES = [
    { id: 'card', label: 'Card', desc: 'Debit or credit card' },
    { id: 'login', label: 'Password', desc: 'For a website or app' },
    { id: 'devkey', label: 'API key', desc: 'Secrets and access keys' },
    { id: 'codes', label: 'Recovery codes', desc: 'Backup codes for two-step login' },
    { id: 'bank', label: 'Bank account', desc: 'Account and routing numbers' },
    { id: 'identity', label: 'Identity', desc: 'Passport, licence, ID card' },
    { id: 'note', label: 'Secure note', desc: 'Private text' },
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
    membership: [F('Program', 'e.g. Airline miles'), F('Member number'), F('Tier', 'e.g. Gold'), F('Tier or membership ends', 'DD / MM / YYYY')],
    codes: [F('Account', 'e.g. Code hosting'), F('Website', 'example.com'), F('Codes', 'Paste them all, one per line', { area: true, critical: true })],
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
  const finishes = { 1: 'titanium', 2: 'platinum', 3: 'platinum', 4: 'champagne', 5: 'obsidian', 6: 'ivory' };
  // The real app stores these inside the encrypted vault, like every other item detail.
  const cardFolder = {}; // card id → a folder the user made; otherwise the card sits in its bank's folder
  const blocked = {}; // card id → true once the user has marked it blocked (a checklist, nothing is sent)
  const usedCodes = { r1: new Set([1, 4, 7]), r2: new Set([0, 1, 2, 3, 4, 5]) };
  const fresh = () => ({
    pw: '', pwShown: false, verifying: false, unlocking: false,
    query: '', banner: true,
    revealed: null, secs: 30, sheet: null, lastAuth: 0, toast: '',
    addType: null, addPw: '', addVals: {}, addCvv: false,
    rkStep: 'show', rkA: '', rkB: '', rkErr: false,
    newFolder: ''
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
  function critical(kind, field, label) {
    if (Date.now() - S.lastAuth < 60000) { doAction(kind, field); return; }
    fx.sheet = true; // the sheet animates in only on the render that opens it
    set({ sheet: { kind, field, label } });
    focusId('bio-confirm');
  }
  function doAction(kind, field) {
    if (kind === 'reveal') startReveal(field);
    else toast('Copied · clears in 60 s');
  }
  function focusId(id) {
    const el = document.getElementById(id);
    if (el) el.focus();
  }

  // ---------- Shared fragments ----------
  // On iPhone the back button names where it goes, as iOS navigation does.
  const backLink = (href, label) => {
    const to = label.replace(/^Back to /, '');
    const text = platform === 'ios' ? `<span class="back-text">${esc(to.charAt(0).toUpperCase() + to.slice(1))}</span>` : '';
    return `<a class="icon-btn strong back-btn" href="${href}" aria-label="${label}">${icon('back', 24)}${text}</a>`;
  };
  const topbar = (back, label) => `<div class="topbar">
    ${backLink(back, label)}
    <a class="icon-btn accent" href="#/lock" aria-label="Lock now">${icon('lock')}</a>
  </div>`;
  // `key` identifies the field; `tier` decides the gate; `label` names it in prompts.
  const copyBtn = (key, label, tier = 'normal') => `<button type="button" id="copy-${key}" class="icon-btn" data-action="copy" data-arg="${key}" data-tier="${tier}" data-label="${esc(label)}" aria-label="Copy ${esc(label)}">${icon('copy', 20)}</button>`;
  const revealBtn = (key, label, tier, on) => `<button type="button" id="reveal-${key}" class="icon-btn" data-action="reveal" data-arg="${key}" data-tier="${tier}" data-label="${esc(label)}" aria-label="${on ? 'Hide' : 'Reveal'} ${esc(label)}" aria-pressed="${on}">${icon(on ? 'eyeOff' : 'eye', 20)}</button>`;
  const frow = (label, valueHtml, actions = '') => `<div class="frow"><div class="frow-text"><span class="frow-label">${label}</span><span class="frow-value">${valueHtml}</span></div>${actions}</div>`;
  const criticalBadge = `<span class="badge">${icon('lock', 10, '', 'stroke-width="2.5"')}CRITICAL</span>`;
  const timer = (on) => (on ? `<span class="timer" id="reveal-timer">Hides in ${S.secs} s</span>` : '');
  const secret = (on, shown, masked, cls = '') => `<span class="secret${cls}${on && fx.reveal ? ' fade-in' : ''}">${on ? shown : masked}</span>`;
  const finishName = (f) => FINISHES.find(([id]) => id === f)[1];
  const meter = ([level, text]) => `<span class="meter" aria-hidden="true">${Array.from({ length: 5 }, (_, i) => `<span class="${i < level ? 'on' : ''}"></span>`).join('')}</span><span class="frow-note">${text}</span>`;

  const segmented = (labelledBy, options, current, action) => `<span class="segmented" role="group" aria-labelledby="${labelledBy}">${Object.keys(options).map((k) =>
    `<button type="button" id="${labelledBy}-${k}" data-action="${action}" data-arg="${k}" aria-pressed="${k === current}">${options[k]}</button>`).join('')}</span>`;
  const platformSwitch = (labelledBy) => segmented(labelledBy, { android: 'Android', ios: 'iPhone' }, platform, 'platform');
  const typefaceSwitch = (labelledBy) => segmented(labelledBy, TYPEFACES, typeface, 'typeface');

  // Folders: cards group by bank unless the user moves one into a folder of their own.
  const folderOf = (c) => cardFolder[c.id] || c.issuer;
  const folderHref = (name) => `#/folder/${encodeURIComponent(name)}`;
  function folders() {
    const map = new Map();
    for (const it of ITEMS.filter((x) => x.type === 'card')) {
      const c = CARDS[it.card];
      const name = folderOf(c);
      if (!map.has(name)) map.set(name, []);
      map.get(name).push(c);
    }
    return Array.from(map, ([name, cards]) => ({ name, cards }));
  }
  const cardCount = (n) => `${n} ${n === 1 ? 'card' : 'cards'}`;
  // A folder of two or more cards shows as a fanned deck in the wallet; a lone card shows as itself.
  function walletEntry(f) {
    if (f.cards.length === 1) {
      const c = f.cards[0];
      return `<a class="wallet-entry" href="#/card/${c.id}" aria-label="Open ${esc(c.nick)}">${cardFace(c, true)}<span class="wallet-caption">${esc(c.nick)}</span></a>`;
    }
    const layers = f.cards.slice(0, 3).reverse();
    const fan = layers.map((c, i) => `<span class="fan-card fan-${layers.length - 1 - i}">${cardFace(c, true)}</span>`).join('');
    return `<a class="wallet-entry" href="${folderHref(f.name)}" aria-label="Open folder ${esc(f.name)}, ${cardCount(f.cards.length)}">
      <span class="fan" aria-hidden="true">${fan}</span>
      <span class="wallet-caption">${icon('folder', 14)}${esc(f.name)} · ${f.cards.length}</span></a>`;
  }
  function cardRow(c) {
    const soon = expiryOf(c);
    return `<a class="item" href="#/card/${c.id}"><span class="type-tile"><span class="mini finish-${finishes[c.id]}"></span></span>
      <span class="item-text"><span class="item-title">${esc(c.nick)}${blocked[c.id] ? ' <span class="badge badge-crit">BLOCKED</span>' : ''}</span>
      <span class="item-sub">${c.network === 'AMEX' ? 'Amex' : c.network.charAt(0) + c.network.slice(1).toLowerCase()} · •••• ${c.last4} · ${soon.days <= 183 ? `<span class="warn-text">exp. ${c.expiry.replace(/\s/g, '')}</span>` : `exp. ${c.expiry.replace(/\s/g, '')}`}</span></span>
      ${icon('chev', 18, 'chev')}</a>`;
  }

  // Expiry watch: shown only inside the unlocked app. A notification would have to read the vault while locked.
  const DAY = 86400000;
  function expiryOf(c) {
    const [m, y] = c.expiry.split('/').map((v) => parseInt(v, 10));
    const date = new Date(2000 + y, m, 0); // cards work until the end of their expiry month
    return { date, days: Math.ceil((date - Date.now()) / DAY) };
  }
  function upcoming() {
    const list = [];
    for (const it of ITEMS) {
      let e = null;
      if (it.card) e = Object.assign(expiryOf(CARDS[it.card]), { what: 'Card expires' });
      else if (it.expires) {
        const date = new Date(it.expires + 'T00:00:00');
        e = { date, days: Math.ceil((date - Date.now()) / DAY), what: it.expiresWhat || 'Expires' };
      }
      if (e && e.days <= 183) list.push(Object.assign(e, { it }));
    }
    return list.sort((a, b) => a.days - b.days);
  }
  const whenText = (days) => (days < 0 ? 'Expired' : days <= 45 ? `in ${days} days` : `in ${Math.round(days / 30.44)} months`);
  const monthYear = (d) => d.toLocaleDateString('en-GB', { month: 'short', year: 'numeric' });

  const codesLeft = (it) => it.codes.length - usedCodes[it.id].size;

  function cardFace(c, small) {
    const f = finishes[c.id];
    return `<div class="card-face finish-${f}${small ? ' sm' : ''}${blocked[c.id] ? ' is-blocked' : ''}" role="img" aria-label="${esc(c.nick)}: ${c.network} ending ${c.last4}, ${finishName(f)} finish${blocked[c.id] ? ', marked blocked' : ''}">
      ${blocked[c.id] ? '<span class="blocked-band">BLOCKED</span>' : ''}
      <svg class="engrave" viewBox="0 0 350 221" preserveAspectRatio="none" aria-hidden="true"><path d="${CARD_ENGRAVING}"/></svg>
      <div class="card-top"><span>${esc(c.issuer.toUpperCase())}</span>${small ? '' : `<span>${finishName(f).toUpperCase()}</span>`}</div>
      <div class="card-bottom">
        <div><div class="card-num">•••• ${c.last4}</div>${small ? '' : `<div class="card-name">${esc(c.holder.toUpperCase())}</div>`}</div>
        <span class="network">${c.network}</span>
      </div>
    </div>`;
  }

  function itemRow(it) {
    const lead = it.card ? `<span class="type-tile"><span class="mini finish-${finishes[it.card]}"></span></span>` : typeTile(it.type);
    return `<a class="item" href="${hrefOf(it)}">${lead}<span class="item-text"><span class="item-title">${esc(it.title)}</span><span class="item-sub">${it.type === 'codes' ? `${codesLeft(it)} of ${it.codes.length} codes left${codesLeft(it) <= 2 ? ' · <span class="warn-text">running low</span>' : ''}` : esc(it.sub)}</span></span>${it.fav ? icon('star', 14, 'fav', 'fill="currentColor" role="img" aria-label="Favorite"') : ''}${icon('chev', 18, 'chev')}</a>`;
  }

  // ---------- Screens ----------
  function lock() {
    return `<section class="screen lock${S.unlocking ? ' unlocking' : ''}">
      <div class="stack intro"><h1 class="brand"><img src="brand/lockup.png" alt="Vaultaire" width="300" height="200"></h1><p class="small muted">You have things worth protecting.</p></div>
      <div class="grow"><div class="ring">
        <svg class="rosette" width="300" height="300" viewBox="0 0 300 300" aria-hidden="true"><path d="${LOCK_ROSETTE}"/></svg>
        <svg class="track" width="148" height="148" viewBox="0 0 148 148" fill="none" aria-hidden="true"><circle cx="74" cy="74" r="70" stroke="rgba(255,245,230,0.10)" stroke-width="1.5"/><circle class="arc" cx="74" cy="74" r="70" stroke="#C9B38A" stroke-width="1.5" stroke-linecap="round" transform="rotate(-90 74 74)"/></svg>
        <span class="ring-core">${icon('lock', 36)}</span>
      </div></div>
      <div class="stack actions">
        <button type="button" id="bio-unlock" class="btn btn-primary" data-action="bioUnlock">${icon(P().bioIcon)}Unlock with ${P().bio}</button>
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
      <div class="topbar">${backLink('#/lock', 'Back to lock')}</div>
      <form class="pad grow col pw-form" data-submit="unlock" autocomplete="off">
        <div class="stack intro"><h1 class="display-l">Enter vault password</h1><p class="body muted">Your ${P().device} will then ask for its ${P().credential}. Both are needed to open the vault this way.</p></div>
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

  // Home: one scrolling page of category sections in the user's order. Empty and hidden sections are left out;
  // search still covers every category, hidden ones included.
  function vault() {
    const q = S.query.trim().toLowerCase();
    const matches = (it) => !q || (it.title + ' ' + it.sub).toLowerCase().includes(q);
    const groups = orderedCategories()
      .filter((cat) => q || !isHidden(cat.type))
      .map((cat) => ({ cat, items: ITEMS.filter((it) => it.type === cat.type && matches(it)) }))
      .filter((g) => g.items.length);
    const hiddenWithItems = orderedCategories()
      .filter((cat) => isHidden(cat.type))
      .map((cat) => ({ cat, n: ITEMS.filter((it) => it.type === cat.type).length }))
      .filter((h) => h.n);
    const hiddenNote = !q && hiddenWithItems.length
      ? `<p class="hidden-note">Hidden from home: ${hiddenWithItems.map((h) => `<a href="#/category/${h.cat.type}">${h.cat.label} (${h.n})</a>`).join(', ')}</p>`
      : '';

    const section = ({ cat, items }) => {
      const head = `<a class="sec-head" href="#/category/${cat.type}"><h2 class="sec-title">${cat.label}</h2><span class="sec-count">${items.length}</span>${icon('chev', 16, 'chev')}</a>`;
      if (cat.type === 'card' && !q) {
        return `<section class="sec">${head}<div class="wallet" id="wallet" data-keep-scroll>${folders().map(walletEntry).join('')}</div></section>`;
      }
      const shown = q ? items : items.slice(0, 3);
      const more = items.length > shown.length ? `<a class="see-all" href="#/category/${cat.type}">See all ${countLabel(cat, items.length)}${icon('chev', 16)}</a>` : '';
      return `<section class="sec">${head}<div class="group">${shown.map(itemRow).join('')}${more}</div></section>`;
    };

    const soon = q ? [] : upcoming();
    const comingUp = soon.length
      ? `<section class="sec"><div class="sec-head static"><h2 class="sec-title">Coming up</h2><span class="sec-count">${soon.length}</span></div><div class="group">${soon.slice(0, 3).map((e) => `<a class="item soon" href="${hrefOf(e.it)}">
          <span class="type-tile">${icon('calendar')}</span>
          <span class="item-text"><span class="item-title">${esc(e.it.title)}</span><span class="item-sub">${e.what} ${monthYear(e.date)}</span></span>
          <span class="soon-when">${whenText(e.days)}</span></a>`).join('')}</div></section>`
      : '';
    // Cards stay first; "Coming up" follows them.
    const body = groups.length
      ? groups.map((g, i) => section(g) + (i === 0 && g.cat.type === 'card' ? comingUp : '')).join('') + (groups[0].cat.type === 'card' ? '' : comingUp)
      : `<div class="empty"><p class="display">Nothing found</p><p class="small muted">No items match “${esc(S.query)}”.</p></div>`;
    const banner = S.banner ? `<div class="banner" role="status">${icon('alert', 20)}<p>3 failed password attempts since your last unlock</p><button type="button" id="dismiss" class="text-btn" data-action="dismissBanner">Dismiss</button></div>` : '';

    return `<section class="screen fixed${fx.enter ? ' enter' : ''}">
      <div class="vault-head">
        <div class="col"><span class="label">Vault</span><h1 class="display">${greeting()}</h1></div>
        <div class="inline">
          ${platform === 'ios' ? `<a class="icon-btn" href="#/add" aria-label="Add item">${icon('plus')}</a>` : ''}
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
      ${banner}
      <div class="list" id="vault-list" data-keep-scroll>
        ${body}
        ${hiddenNote}
        ${q ? '' : `<div class="organise"><a class="btn btn-secondary btn-small" href="#/sections">${icon('sliders', 18)}Organise sections</a></div>`}
        <p class="list-foot">Kept only on this phone. Never synced, never uploaded.</p>
      </div>
      ${platform === 'ios' ? '' : `<a class="fab" href="#/add" aria-label="Add item">${icon('plus', 24, '', 'stroke-width="1.75"')}</a>`}
    </section>`;
  }

  // The user decides which sections the home screen shows, and in what order. Cards stay pinned first.
  function sections() {
    const countOf = (type) => ITEMS.filter((it) => it.type === type).length;
    const cardCat = categoryOf('card');
    const pinned = `<div class="edit-row pinned">
        <span class="edit-text"><span class="edit-name">${cardCat.label}</span><span class="edit-meta">Always first · ${countLabel(cardCat, countOf('card'))}</span></span>
        ${icon('pin', 20, 'pin-icon', 'role="img" aria-label="Pinned"')}
      </div>`;
    const last = layout.order.length - 1;
    const rows = layout.order.map((type, i) => {
      const cat = categoryOf(type);
      const hidden = isHidden(type);
      const n = countOf(type);
      const meta = hidden ? 'Hidden' : n ? countLabel(cat, n) : 'Empty';
      return `<div class="edit-row${hidden ? ' is-hidden' : ''}">
        <span class="edit-text"><span class="edit-name">${cat.label}</span><span class="edit-meta">${meta}</span></span>
        <button type="button" id="up-${type}" class="icon-btn" data-action="moveSection" data-arg="${type}:-1" aria-label="Move ${cat.label} up"${i === 0 ? ' disabled' : ''}>${icon('up', 20)}</button>
        <button type="button" id="down-${type}" class="icon-btn" data-action="moveSection" data-arg="${type}:1" aria-label="Move ${cat.label} down"${i === last ? ' disabled' : ''}>${icon('down', 20)}</button>
        <input type="checkbox" role="switch" id="show-${type}" class="switch" data-action="toggleSection" data-arg="${type}" aria-label="Show ${cat.label} on home"${hidden ? '' : ' checked'}>
      </div>`;
    }).join('');
    const changed = layout.order.join() !== DEFAULT_ORDER.join() || Object.values(layout.hidden).some(Boolean);
    return `<section class="screen${fx.enter ? ' enter' : ''}" id="scr-sections" data-keep-scroll>
      ${topbar('#/vault', 'Back to vault')}
      <div class="gutter cat-head">
        <span class="label">Vault</span>
        <h1 class="display">Home sections</h1>
        <p class="small muted">Choose what your home screen shows, and in what order.</p>
      </div>
      <div class="gutter cat-list">
        <div class="group">${pinned}</div>
        <h2 class="label edit-label">Your order</h2>
        <div class="group">${rows}</div>
      </div>
      <p class="assurance gutter cat-note">${icon('shield', 18)}<span>Hiding a section only tidies your home screen. Its items stay in your vault, still appear in search, and are still in your backups. It is not extra protection.</span></p>
      <div class="gutter cat-add"><button type="button" id="reset-sections" class="btn btn-ghost" data-action="resetSections"${changed ? '' : ' disabled'}>Reset to default</button></div>
    </section>`;
  }

  // One category, every item in it, and how that category is protected.
  function category(type) {
    const cat = categoryOf(type);
    const items = ITEMS.filter((it) => it.type === cat.type);
    const list = !items.length
      ? `<div class="empty"><p class="display">Nothing here yet</p><p class="small muted">Items you add will appear here.</p></div>`
      : cat.type === 'card'
        ? `<a class="btn btn-secondary lost-btn" href="#/lost">${icon('alert', 20)}Lost your wallet?</a>
          ${folders().map((f) => `<section class="folder-sec">
            <a class="folder-head" href="${folderHref(f.name)}">${icon('folder', 18)}<span class="folder-name">${esc(f.name)}</span><span class="sec-count">${f.cards.length}</span>${icon('chev', 16, 'chev')}</a>
            <div class="group">${f.cards.map(cardRow).join('')}</div></section>`).join('')}`
        : `<div class="group">${items.map(itemRow).join('')}</div>`;
    return `<section class="screen${fx.enter ? ' enter' : ''}" id="scr-cat-${cat.type}" data-keep-scroll>
      ${topbar('#/vault', 'Back to vault')}
      <div class="gutter cat-head">
        <span class="label">Vault</span>
        <h1 class="display">${cat.label}</h1>
        <p class="small muted">${countLabel(cat, items.length)}</p>
      </div>
      <p class="assurance gutter cat-note">${icon('shield', 18)}<span>${cat.note.replace('{bio}', P().bio)}</span></p>
      <div class="gutter cat-list">${list}</div>
      <div class="gutter cat-add"><a class="btn btn-secondary" href="#/add/${cat.type}">${icon('plus', 20)}${cat.add}</a></div>
    </section>`;
  }

  // Any non-card item. Each field follows its tier: shown, tap to reveal, or biometric to reveal.
  function item(id) {
    const it = itemById(id) || ITEMS[2];
    const cat = categoryOf(it.type);
    if (it.type === 'codes') return codesItem(it, cat);
    const rows = it.fields.map((f, i) => {
      const key = `${it.id}-${i}`;
      const on = S.revealed === key;
      const mono = f.mono ? ' mono' : '';
      if (f.tier === 'normal') {
        return frow(esc(f.label), `<span class="${mono.trim()}">${esc(f.value)}</span>`, f.copy ? copyBtn(key, f.label) : '');
      }
      const masked = f.keep ? `•••• •••• ${esc(f.keep)}` : f.multiline ? '•••••••• ••••••• ••••••••' : '••••••••••••';
      const shown = esc(f.value);
      const cls = (f.mono ? '' : ' plain') + (f.multiline ? ' multiline' : '');
      return `<div class="frow"><div class="frow-text">
          <span class="frow-label">${esc(f.label)}${f.tier === 'critical' ? ' ' + criticalBadge : ''}</span>
          ${secret(on, shown, masked, cls)}
          ${timer(on)}
          ${f.meter ? meter(f.meter) : ''}
        </div>${revealBtn(key, f.label, f.tier, on)}${f.copy ? copyBtn(key, f.label, f.tier) : ''}</div>`;
    }).join('');
    return `<section class="screen${fx.enter ? ' enter' : ''}" id="scr-item" data-keep-scroll>
      ${topbar(`#/category/${it.type}`, `Back to ${cat.label}`)}
      <div class="title-row">${typeTile(it.type)}<div class="col"><span class="label">${cat.label}</span><h1 class="display">${esc(it.title)}</h1></div></div>
      <div class="fields">
        <h2 class="label">Details</h2>
        ${rows}
        ${it.foot ? `<p class="faint">${esc(it.foot)}</p>` : ''}
      </div>
    </section>`;
  }

  // Two-step login backup codes: all hidden behind one biometric, each ticked off once used.
  function codesItem(it, cat) {
    const key = `codes-${it.id}`;
    const on = S.revealed === key;
    const left = codesLeft(it);
    const used = usedCodes[it.id];
    const warn = left <= 2
      ? `<p class="assurance warn-note">${icon('alert', 18)}<span>Only ${left} ${left === 1 ? 'code' : 'codes'} left. Sign in to ${esc(it.service)} and make a new set before you run out, or you could be locked out of the account.</span></p>`
      : '';
    const list = on
      ? `<ol class="codes${fx.reveal ? ' fade-in' : ''}">${it.codes.map((c, i) => `<li class="code-row${used.has(i) ? ' used' : ''}">
          <span class="code-n">${i + 1}</span><span class="code mono">${esc(c)}</span>
          <button type="button" id="use-${it.id}-${i}" class="text-btn" data-action="toggleCode" data-arg="${it.id}:${i}" aria-pressed="${used.has(i)}" aria-label="Code ${i + 1}: ${used.has(i) ? 'used, mark unused' : 'mark used'}">${used.has(i) ? 'Used' : 'Mark used'}</button></li>`).join('')}</ol>`
      : `<div class="codes-masked">${icon('lock', 18)}<span>${it.codes.length} codes hidden</span></div>`;
    return `<section class="screen${fx.enter ? ' enter' : ''}" id="scr-item" data-keep-scroll>
      ${topbar(`#/category/${it.type}`, `Back to ${cat.label}`)}
      <div class="title-row">${typeTile(it.type)}<div class="col"><span class="label">${cat.label}</span><h1 class="display">${esc(it.title)}</h1></div></div>
      <div class="fields">
        ${frow('Website', esc(it.service))}
        <div class="frow"><div class="frow-text"><span class="frow-label">Codes ${criticalBadge}</span><span class="frow-value">${left} of ${it.codes.length} unused</span>${timer(on)}</div></div>
        ${list}
        <button type="button" id="reveal-${key}" class="btn btn-secondary" data-action="reveal" data-arg="${key}" data-tier="critical" data-label="recovery codes" aria-pressed="${on}">${icon(on ? 'eyeOff' : 'eye', 20)}${on ? 'Hide codes' : 'Show codes'}</button>
        ${warn}
        <p class="assurance">${icon('shield', 18)}<span>Each code signs you in once if you lose your authenticator. Tick one off as soon as you use it.</span></p>
      </div>
    </section>`;
  }

  // A folder: every card in it, full size.
  function folder(param) {
    let name = '';
    try { name = decodeURIComponent(param || ''); } catch (e) { /* malformed link: fall through */ }
    const all = folders();
    const f = all.find((x) => x.name === name) || all.find((x) => x.cards.length > 1) || all[0];
    const custom = f.cards.some((c) => cardFolder[c.id] === f.name);
    return `<section class="screen${fx.enter ? ' enter' : ''}" id="scr-folder" data-keep-scroll>
      ${topbar('#/category/card', 'Back to Cards')}
      <div class="gutter cat-head">
        <span class="label">${custom ? 'Your folder' : 'Bank folder'}</span>
        <h1 class="display">${esc(f.name)}</h1>
        <p class="small muted">${cardCount(f.cards.length)}</p>
      </div>
      <div class="gutter cat-list"><div class="card-stack">${f.cards.map((c) => `<a class="wallet-card" href="#/card/${c.id}" aria-label="Open ${esc(c.nick)}">${cardFace(c, false)}</a>`).join('')}</div></div>
      <p class="assurance gutter cat-note">${icon('folder', 18)}<span>${custom ? 'You made this folder. Move cards in or out from each card’s details.' : 'Cards from the same bank are grouped automatically. To use a folder of your own, open a card and choose Folder.'}</span></p>
    </section>`;
  }

  // Choose a card's folder: its bank's, one the user made, or a new one.
  function move(id) {
    const c = CARDS[id] || CARDS[1];
    const current = folderOf(c);
    const names = [c.issuer, ...new Set(Object.values(cardFolder))].filter((n, i, a) => a.indexOf(n) === i);
    const rows = names.map((n) => `<button type="button" id="to-${encodeURIComponent(n).replace(/%/g, '_')}" class="srow pick-row" data-action="moveCard" data-arg="${c.id}:${esc(n)}" aria-pressed="${n === current}">
        <span class="srow-main"><span class="srow-label">${esc(n)}</span>${n === c.issuer ? '<span class="srow-sub">Its bank · automatic</span>' : ''}</span>
        ${n === current ? icon('check', 20, 'accent-icon') : ''}</button>`).join('');
    const name = S.newFolder.trim();
    return `<section class="screen${fx.enter ? ' enter' : ''}" id="scr-move" data-keep-scroll>
      ${topbar(`#/card/${c.id}`, `Back to ${c.nick}`)}
      <div class="gutter cat-head"><span class="label">${esc(c.nick)} · •••• ${c.last4}</span><h1 class="display">Move to folder</h1></div>
      <div class="gutter cat-list"><div class="group">${rows}</div></div>
      <form class="gutter new-folder" data-submit="createFolder" autocomplete="off">
        <label class="label" for="new-folder">New folder</label>
        <div class="inline">
          <input id="new-folder" class="input" type="text" maxlength="30" placeholder="e.g. Business" value="${esc(S.newFolder)}" data-bind="newFolder" spellcheck="false" autocomplete="off">
          <button type="submit" id="create-folder" class="btn btn-primary btn-small"${name && !names.includes(name) ? '' : ' disabled'}>Create</button>
        </div>
      </form>
    </section>`;
  }

  // Lost wallet: every card by bank, one call per bank, and a checklist of what is blocked.
  function lost() {
    const cards = ITEMS.filter((x) => x.type === 'card').map((x) => CARDS[x.card]);
    const banks = [...new Set(cards.map((c) => c.issuer))];
    const done = cards.filter((c) => blocked[c.id]).length;
    const groups = banks.map((b) => {
      const list = cards.filter((c) => c.issuer === b);
      const tel = list[0].hotline.replace(/[^+\d]/g, '');
      return `<div class="group lost-group">
        <div class="lost-head"><span class="srow-main"><span class="srow-label">${esc(b)}</span><span class="srow-sub mono">${esc(list[0].hotline)}</span></span>
          <a class="btn btn-primary btn-small call-btn" href="tel:${tel}" aria-label="Call ${esc(b)} to block cards">${icon('phone', 18)}Call</a></div>
        ${list.map((c) => `<label class="srow lost-row" for="blocked-${c.id}">
          <span class="type-tile"><span class="mini finish-${finishes[c.id]}"></span></span>
          <span class="srow-main"><span class="srow-label">${esc(c.nick)}</span><span class="srow-sub">•••• ${c.last4}</span></span>
          <span class="lost-state">${blocked[c.id] ? 'Blocked' : ''}</span>
          <input type="checkbox" role="switch" id="blocked-${c.id}" class="switch" data-action="toggleBlocked" data-arg="${c.id}" aria-label="${esc(c.nick)} blocked"${blocked[c.id] ? ' checked' : ''}></label>`).join('')}
      </div>`;
    }).join('');
    return `<section class="screen${fx.enter ? ' enter' : ''}" id="scr-lost" data-keep-scroll>
      ${topbar('#/category/card', 'Back to Cards')}
      <div class="gutter cat-head">
        <span class="label">Cards</span>
        <h1 class="display">Lost wallet</h1>
        <p class="small muted">Call each bank to block your cards, then tick them off here.</p>
      </div>
      <div class="gutter progress-line" role="status"><span class="progress-bar"><span class="progress-fill p${Math.round((done / cards.length) * 10)}"></span></span><span class="small">${done} of ${cards.length} blocked</span></div>
      <div class="gutter cat-list lost-list">${groups}</div>
      <p class="assurance gutter cat-note">${icon('shield', 18)}<span>This is your own checklist. Vaultaire never contacts your bank, and nothing leaves this phone.</span></p>
      <p class="assurance gutter cat-note lost-last">${icon('alert', 18)}<span>Next, change the passwords of any accounts that could be opened with what was in the wallet.</span></p>
    </section>`;
  }

  function card(id) {
    const c = CARDS[id] || CARDS[1];
    const onNum = S.revealed === 'card-number';
    const onCvv = S.revealed === 'card-cvv';
    const cvvRow = c.cvv
      ? `<div class="frow"><div class="frow-text">
          <span class="frow-label">Security code (CVV) ${criticalBadge}</span>
          ${secret(onCvv, c.cvv, '•••')}
          ${onCvv ? timer(true) : '<span class="timer">Stored at your request · never autofilled</span>'}
        </div>${revealBtn('card-cvv', 'security code', 'critical', onCvv)}${copyBtn('card-cvv', 'security code', 'critical')}</div>`
      : frow('Security code (CVV)', '<span class="muted">Not stored</span>');
    const note = c.cvv
      ? `<p class="assurance warn-note">${icon('alert', 18)}<span>This card's CVV is stored. With the number and expiry, it is everything needed to shop online with this card. Remove it if you don't need it here.</span></p>`
      : `<p class="assurance">${icon('shield', 18)}<span>No CVV stored for this card. Number, expiry and CVV together are all an online thief needs.</span></p>`;
    return `<section class="screen${fx.enter ? ' enter' : ''}" id="scr-card" data-keep-scroll>
      ${topbar('#/category/card', 'Back to Cards')}
      <div class="detail-head">
        <div class="col"><span class="label">Cards</span><h1 class="display">${esc(c.nick)}</h1></div>
        ${cardFace(c, false)}
        <div class="finishes" role="group" aria-label="Card finish">
          ${FINISHES.map(([f, name]) => `<button type="button" id="finish-${f}" class="swatch-btn" data-action="finish" data-arg="${c.id}:${f}" aria-label="${name} finish" aria-pressed="${finishes[c.id] === f}"><span class="swatch finish-${f}"></span></button>`).join('')}
          <span class="finish-name">${finishName(finishes[c.id])}</span>
        </div>
      </div>
      <div class="fields">
        <h2 class="label">Details</h2>
        ${frow('Cardholder', esc(c.holder), copyBtn('card-name', 'cardholder name'))}
        <div class="frow"><div class="frow-text">
          <span class="frow-label">Card number ${criticalBadge}</span>
          ${secret(onNum, c.number, '•••• •••• •••• ' + c.last4)}
          ${timer(onNum)}
        </div>${revealBtn('card-number', 'card number', 'critical', onNum)}${copyBtn('card-number', 'card number', 'critical')}</div>
        ${frow('Expiry', `<span class="mono">${c.expiry}</span>${expiryOf(c).days <= 183 ? `<span class="frow-note warn-text">${expiryOf(c).days < 0 ? 'Expired' : `Expires ${whenText(expiryOf(c).days)}`} · ask ${esc(c.issuer)} for a new card</span>` : ''}`, copyBtn('card-expiry', 'expiry date'))}
        ${cvvRow}
        ${frow('Issuer', esc(c.issuer))}
        <a class="frow frow-link" href="#/move/${c.id}"><div class="frow-text"><span class="frow-label">Folder</span><span class="frow-value">${icon('folder', 16)} ${esc(folderOf(c))}</span></div>${icon('chev', 18, 'chev')}</a>
        ${frow('If lost or stolen', `<span class="mono">${esc(c.hotline)}</span>${blocked[c.id] ? '<span class="frow-note warn-text">Marked blocked</span>' : ''}`, `<a class="icon-btn accent" href="tel:${c.hotline.replace(/[^+\d]/g, '')}" aria-label="Call ${esc(c.issuer)}">${icon('phone', 20)}</a>`)}
        <a class="btn btn-secondary lost-btn" href="#/lost">${icon('alert', 20)}Lost your wallet?</a>
        ${note}
        <p class="assurance">${icon('lock', 18)}<span>Card PINs are never stored.</span></p>
      </div>
    </section>`;
  }

  function add() {
    if (!S.addType) {
      return `<section class="screen${fx.enter ? ' enter' : ''}" id="scr-add" data-keep-scroll>
        <div class="topbar"><a class="icon-btn strong" href="#/vault" aria-label="Cancel">${icon('close')}</a></div>
        <div class="gutter stack-tight"><h1 class="display">Add item</h1><p class="body muted">What would you like to keep?</p></div>
        <div class="tiles">${orderedCategories().map((c) => TYPES.find((t) => t.id === c.type)).sort((a, b) => isHidden(a.id) - isHidden(b.id)).map((t) => `<button type="button" id="tile-${t.id}" class="tile" data-action="pick" data-arg="${t.id}">${typeTile(t.id)}<span><span class="tile-name">${t.label}</span><span class="tile-desc">${t.desc}</span></span></button>`).join('')}</div>
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
    // CVV is opt-in per card, off by default, with the risk stated up front (threat model §9).
    const cvv = t.id === 'card' ? `<div class="optin">
        <label class="switch-row" for="cvv-optin">
          <span class="col"><span class="srow-label">Also store the security code</span><span class="faint">Off by default. Most people don't need it here.</span></span>
          <input id="cvv-optin" type="checkbox" role="switch" class="switch" data-action="toggleCvv"${S.addCvv ? ' checked' : ''}>
        </label>
        ${S.addCvv ? `<div class="notice${fx.reveal ? ' fade-in' : ''}">${icon('alert', 20)}<p>Card number, expiry and CVV together are everything needed to shop online with this card. It is kept as a critical field, needs your ${P().bio} to show, and is never autofilled.</p></div>
        <div class="field"><label class="label" for="field-cvv">Security code (CVV) ${criticalBadge}</label>${input('field-cvv', 'mono inputmode="numeric" maxlength="4" placeholder="3 or 4 digits"')}</div>` : ''}
        <p class="assurance">${icon('lock', 18)}<span>There is no field for the card PIN. It is never stored.</span></p>
      </div>` : '';
    return `<section class="screen fixed${fx.enter ? ' enter' : ''}">
      <div class="topbar"><button type="button" id="add-back" class="icon-btn strong" data-action="addBack" aria-label="Choose another type">${icon('back', 24)}</button></div>
      <div class="title-row">${typeTile(t.id)}<div class="col"><span class="label">${categoryOf(t.id).label}</span><h1 class="display">New ${t.label === 'API key' ? 'API key' : t.label.toLowerCase()}</h1></div></div>
      <div class="form scroll" id="add-form" data-keep-scroll>${fields}${cvv}</div>
      <div class="savebar"><a class="btn btn-primary" href="#/category/${t.id}">Save ${t.label === 'API key' ? 'API key' : t.label.toLowerCase()}</a></div>
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
      <p class="faint after">${P().shots}</p>
      <div class="grow"></div>
      <button type="button" id="rk-verify" class="btn btn-primary" data-action="rkVerify">I have written it down</button>
    </section>`;
  }

  function settings() {
    const cvvCount = Object.values(CARDS).filter((c) => c.cvv).length;
    const protection = [
      ['Unlock methods', `${P().Bio} + password`], ['Auto-lock', 'After 2 min'], ['Clear clipboard', 'After 60 s'],
      ['Critical fields', `${P().Bio} after 60 s`], ['Card security codes', `Stored for ${cvvCount} of ${Object.keys(CARDS).length} cards`],
      ['Password check', 'Every 14 days'], ['Security log', '3 failed attempts']
    ];
    const device = P().checks;
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
          <div class="group">
            <a class="srow" href="#/sections"><span class="srow-label">Home sections</span><span class="inline"><span class="srow-value">${layout.order.join() !== DEFAULT_ORDER.join() || Object.values(layout.hidden).some(Boolean) ? 'Customised' : 'Default'}</span>${icon('chev', 18, 'chev')}</span></a>
            ${simple('Theme', 'Dark · Obsidian')}${simple('Haptics', 'On')}
          </div>
        </section>
        <section class="section"><h2 class="label">Prototype</h2>
          <div class="group">
            <div class="srow"><span class="srow-label" id="plat-label">Show as</span>${platformSwitch('plat-label')}</div>
            <div class="srow srow-stack"><span class="srow-label" id="type-label">Typeface</span>${typefaceSwitch('type-label')}</div>
          </div>
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
      html += `<div class="backdrop${fx.sheet ? ' fade-in' : ''}" data-action="cancelSheet"><div class="sheet${fx.sheet ? ' rise' : ''}" role="dialog" aria-modal="true" aria-labelledby="bio-title">
        ${icon(P().bioIcon, 52, '', 'stroke-width="1.2"')}
        <h2 id="bio-title">Confirm it's you</h2>
        <p class="body muted">${P().prompt(verb, esc(S.sheet.label.toLowerCase()))}</p>
        <button type="button" id="bio-confirm" class="btn btn-primary" data-action="confirmSheet">Simulate ${P().bio}</button>
        <button type="button" id="bio-cancel" class="btn btn-ghost" data-action="cancelSheet">Cancel</button>
      </div></div>`;
    }
    return html;
  }

  const ROUTES = {
    lock: { title: 'Lock screen', render: lock },
    password: { title: 'Vault password', render: password },
    vault: { title: 'Vault', render: vault },
    sections: { title: 'Home sections', render: sections },
    category: { title: 'Category · Passwords', render: category, link: '#/category/login' },
    card: { title: 'Card · critical fields', render: card, link: '#/card/1' },
    folder: { title: 'Card folder', render: folder, link: '#/folder/American%20Express' },
    move: { title: 'Move to folder', render: move, link: '#/move/5', hidden: true },
    lost: { title: 'Lost wallet', render: lost },
    item: { title: 'Item · API key', render: item, link: '#/item/k1' },
    add: { title: 'Add item', render: add },
    recovery: { title: 'Recovery Kit', render: recovery },
    settings: { title: 'Settings', render: settings }
  };
  function route() {
    const [name, param] = location.hash.replace(/^#\/?/, '').split('/');
    return Object.prototype.hasOwnProperty.call(ROUTES, name) ? { name, param, key: name + '/' + (param || '') } : { name: 'lock', key: 'lock/' };
  }
  function pageTitle(r) {
    if (r.name === 'category') return categoryOf(r.param).label;
    if (r.name === 'item') return (itemById(r.param) || {}).title || 'Item';
    if (r.name === 'card') return (CARDS[r.param] || CARDS[1]).nick;
    if (r.name === 'folder') { try { return decodeURIComponent(r.param || '') || 'Folder'; } catch (e) { return 'Folder'; } }
    return ROUTES[r.name].title;
  }

  // ---------- Rendering ----------
  function render() {
    const r = route();
    const active = document.activeElement;
    const keepFocus = active && active.id && app.contains(active) ? { id: active.id, sel: selection(active) } : null;
    const scrolls = Array.from(app.querySelectorAll('[data-keep-scroll]'), (el) => [el.id, el.scrollTop, el.scrollLeft]);

    app.innerHTML = ROUTES[r.name].render(r.param) + overlays();
    fx = {};
    document.title = pageTitle(r) + ' · Vaultaire prototype';
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
    nav.innerHTML = `<img class="nav-mark" src="brand/mark.png" alt="" width="48" height="48">
      <p class="label">Vaultaire · UI prototype</p>
      ${Object.keys(ROUTES).filter((k) => !ROUTES[k].hidden).map((k) => `<a href="${ROUTES[k].link || '#/' + k}"${k === name ? ' aria-current="page"' : ''}>${ROUTES[k].title}</a>`).join('')}
      <a href="#/item/r2">Recovery codes</a>
      <p class="label nav-plat" id="nav-plat">Show as</p>${platformSwitch('nav-plat')}
      <p class="label nav-plat" id="nav-type">Typeface</p>${typefaceSwitch('nav-type')}
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
      if (prev === 'move') S.newFolder = '';
      // "Add password" on a category page opens the form for that type directly.
      if (next.name === 'add' && next.param && TEMPLATES[next.param]) {
        Object.assign(S, { addType: next.param, addPw: next.param === 'login' ? passphrase() : '', addVals: {}, addCvv: false });
      }
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
    finish: (arg) => { const [id, f] = arg.split(':'); finishes[id] = f; render(); },
    moveSection: (arg, el) => {
      const [type, step] = arg.split(':');
      const i = layout.order.indexOf(type);
      const j = i + Number(step);
      if (i < 0 || j < 0 || j >= layout.order.length) return;
      [layout.order[i], layout.order[j]] = [layout.order[j], layout.order[i]];
      saveLayout();
      render();
      // Keep focus on the moved section: fall back to its other arrow when this one became disabled.
      const again = document.getElementById(el.id);
      if (again && again.disabled) focusId(Number(step) < 0 ? `down-${type}` : `up-${type}`);
      announce(`${categoryOf(type).label} moved to position ${j + 2} of ${layout.order.length + 1}`);
    },
    toggleSection: (type) => {
      layout.hidden[type] = !layout.hidden[type];
      saveLayout();
      render();
      announce(`${categoryOf(type).label} ${layout.hidden[type] ? 'hidden from' : 'shown on'} home`);
    },
    resetSections: () => {
      layout.order = DEFAULT_ORDER.slice();
      layout.hidden = {};
      saveLayout();
      render();
      const h = app.querySelector('h1');
      if (h) { h.setAttribute('tabindex', '-1'); h.focus({ preventScroll: true }); }
      announce('Home sections reset to default');
    },
    reveal: (key, el) => {
      if (S.revealed === key) { stopReveal(); render(); return; }
      if (el.dataset.tier === 'critical') critical('reveal', key, el.dataset.label);
      else startReveal(key);
    },
    copy: (key, el) => (el.dataset.tier === 'critical' ? critical('copy', key, el.dataset.label) : toast('Copied · clears in 60 s')),
    confirmSheet: () => {
      const { kind, field } = S.sheet;
      S.sheet = null;
      S.lastAuth = Date.now();
      doAction(kind, field);
    },
    cancelSheet: () => set({ sheet: null }),
    pick: (type) => { fx.enter = true; set({ addType: type, addPw: type === 'login' ? passphrase() : '', addVals: {}, addCvv: false }); },
    addBack: () => { fx.enter = true; set({ addType: null, addVals: {}, addCvv: false }); },
    toggleCvv: () => { fx.reveal = !S.addCvv; set({ addCvv: !S.addCvv }); },
    generate: () => set({ addPw: passphrase() }),
    rkVerify: () => { fx.step = true; set({ rkStep: 'verify', rkA: '', rkB: '', rkErr: false }); },
    rkShow: () => { fx.step = true; set({ rkStep: 'show' }); },
    platform: (next) => setPlatform(next),
    typeface: (next) => setTypeface(next),
    moveCard: (arg) => {
      const i = arg.indexOf(':');
      const id = arg.slice(0, i);
      const name = arg.slice(i + 1);
      if (!CARDS[id]) return;
      if (name === CARDS[id].issuer) delete cardFolder[id]; else cardFolder[id] = name;
      location.hash = `#/card/${id}`;
      toast(`Moved to ${name}`);
    },
    toggleBlocked: (id) => {
      blocked[id] = !blocked[id];
      render();
      announce(`${CARDS[id].nick} ${blocked[id] ? 'marked blocked' : 'no longer marked blocked'}`);
    },
    toggleCode: (arg) => {
      const [id, n] = arg.split(':');
      const set = usedCodes[id];
      const i = Number(n);
      if (!set) return;
      if (set.has(i)) set.delete(i); else set.add(i);
      render();
      announce(`Code ${i + 1} ${set.has(i) ? 'marked used' : 'marked unused'}`);
    },
    notBuilt: (what) => toast(what + ' is not in the prototype yet')
  };
  // Each returns true when the screen must re-render.
  const BINDS = {
    pw: (v) => { S.pw = v; return true; },
    query: (v) => { S.query = v; return true; },
    addPw: (v) => { S.addPw = v; return false; },
    addField: (v, el) => { S.addVals[el.dataset.key] = v; return false; },
    rkA: (v) => { S.rkA = v; S.rkErr = false; return true; },
    rkB: (v) => { S.rkB = v; S.rkErr = false; return true; },
    newFolder: (v) => { S.newFolder = v; return true; }
  };
  const SUBMITS = {
    // The dial turns while the (simulated) key derivation runs: real progress, not decoration.
    unlock: () => {
      if (!S.pw || S.verifying) return;
      set({ verifying: true });
      later(900, () => { location.hash = '#/vault'; });
    },
    createFolder: () => {
      const id = route().param;
      const name = S.newFolder.trim().slice(0, 30);
      if (!name || !CARDS[id]) return;
      ACTIONS.moveCard(`${id}:${name}`);
    },
    rkCheck: () => {
      const ok = normKey(S.rkA) === RECOVERY_KEY[2] && normKey(S.rkB) === RECOVERY_KEY[5];
      if (ok) fx.step = true;
      set(ok ? { rkStep: 'done' } : { rkErr: true });
    }
  };

  function onClick(e) {
    const el = e.target.closest('[data-action]');
    if (!el || !e.currentTarget.contains(el)) return;
    if (el.classList.contains('backdrop') && e.target !== el) return; // clicks inside the sheet
    const fn = ACTIONS[el.dataset.action];
    if (fn) fn(el.dataset.arg, el);
  }
  app.addEventListener('click', onClick);
  nav.addEventListener('click', onClick);
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

  // Offline support once installed on a phone. Browsers allow this only on HTTPS or localhost.
  if ('serviceWorker' in navigator && window.isSecureContext) {
    navigator.serviceWorker.register('sw.js').catch(() => { /* the prototype works without it */ });
  }

  onRoute();
})();
