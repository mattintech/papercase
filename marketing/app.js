/*
 * Papercase store graphics. Every canvas is built at its real export size (e.g. 1080 x 1920) and
 * shown scaled down. Exports render the full-size canvas with html-to-image and save JPEGs
 * (Google Play rejects PNGs with an alpha channel).
 *
 * Shots are plain data in SETS below: change headlines, screens, or layout options there.
 */
(function () {
  const S = window.PC_SCREENS;

  // ---------- Brand ----------
  const ICON = `<svg viewBox="18 18 72 72" xmlns="http://www.w3.org/2000/svg"><defs><clipPath id="ic-r"><rect x="18" y="18" width="72" height="72" rx="16"/></clipPath></defs>
    <g clip-path="url(#ic-r)"><rect width="108" height="108" fill="#0B57D0"/>
    <g transform="rotate(8 58 44)"><rect x="42" y="27" width="30" height="36" rx="3" fill="#D3E3FD"/></g>
    <g transform="rotate(-5 50 45)"><rect x="34" y="29" width="31" height="37" rx="3" fill="#fff"/>
      <rect x="39" y="36" width="17" height="2.6" rx="1.3" fill="#0B57D0" fill-opacity=".6"/>
      <rect x="39" y="41.5" width="21" height="2.6" rx="1.3" fill="#0B57D0" fill-opacity=".32"/>
      <rect x="39" y="47" width="14" height="2.6" rx="1.3" fill="#0B57D0" fill-opacity=".32"/></g>
    <path d="M30,53 H78 V72 A7,7 0 0 1 71,79 H37 A7,7 0 0 1 30,72 Z" fill="#062E6F"/>
    <path d="M30,53 H78 V57 H30 Z" fill="#0842A0"/>
    <rect x="48" y="51.5" width="12" height="11.5" rx="3" fill="#FFC857"/>
    <circle cx="54" cy="57.25" r="1.8" fill="#062E6F"/></g></svg>`;

  // ---------- Devices ----------
  // Sizes are in screenshot pixels; the whole device is then scaled to the requested width.
  const SPECS = {
    phone: { bezel: 38, radius: 150, screenRadius: 116, cam: { d: 38, top: 40 }, bar: { h: 136, font: 44, pad: 66, icon: 40 } },
    tablet: { bezel: 64, radius: 120, screenRadius: 56, cam: { d: 20, top: -42 }, bar: { h: 96, font: 30, pad: 44, icon: 28 } },
  };

  const WIFI = 'M12 21 0 8.6C3.3 5.5 7.5 3.8 12 3.8s8.7 1.7 12 4.8z';
  const SIGNAL = 'M2 22h20V2z';
  const battery = (h) => `<svg viewBox="0 0 26 13" style="height:${h * 0.62}px"><rect x=".75" y=".75" width="21.5" height="11.5" rx="3.2" fill="none" stroke="currentColor" stroke-width="1.5"/><rect x="2.6" y="2.6" width="17.8" height="7.8" rx="1.8"/><rect x="23.3" y="4.2" width="1.9" height="4.6" rx=".9"/></svg>`;

  function statusBar(kind, color) {
    const b = SPECS[kind].bar;
    const dark = isDark(color);
    return `<div class="sbar" style="height:${b.h}px;padding:0 ${b.pad}px;background:${color};font-size:${b.font}px;color:${dark ? '#fff' : '#1B1B1F'}">
      <span>12:00</span>
      <span class="icons" style="gap:${b.icon * 0.35}px">
        <svg viewBox="0 0 24 24" style="height:${b.icon}px"><path d="${WIFI}"/></svg>
        <svg viewBox="0 0 24 24" style="height:${b.icon * 0.9}px"><path d="${SIGNAL}"/></svg>
        ${battery(b.icon)}
      </span></div>`;
  }

  function isDark(hex) {
    const n = parseInt(hex.slice(1), 16);
    return ((n >> 16) * 0.299 + ((n >> 8) & 255) * 0.587 + (n & 255) * 0.114) < 110;
  }

  /** A device showing screenshot `key`, `width` px wide, placed at (x, y) and rotated `rot` degrees. */
  function device(key, { x, y, width, rot = 0, z = 2 }) {
    const sc = S[key];
    const kind = key.startsWith('tablet') ? 'tablet' : 'phone';
    const sp = SPECS[kind];
    const fw = sc.w + sp.bezel * 2, fh = sc.h + sp.bezel * 2, k = width / fw;
    // Phones: punch-hole in the screen. Tablets (negative top): camera centred in the top bezel.
    const camTop = sp.cam.top < 0 ? (sp.bezel - sp.cam.d) / 2 : sp.bezel + sp.cam.top;
    const cam = `<div class="cam" style="width:${sp.cam.d}px;height:${sp.cam.d}px;left:${fw / 2 - sp.cam.d / 2}px;top:${camTop}px"></div>`;
    return `<div class="device" style="left:${x}px;top:${y}px;width:${width}px;height:${fh * k}px;transform:rotate(${rot}deg);z-index:${z}">
      <div style="position:absolute;left:0;top:0;width:${fw}px;height:${fh}px;transform:scale(${k});transform-origin:0 0">
        <div class="frame" style="border-radius:${sp.radius}px"></div>
        <div class="screen" style="left:${sp.bezel}px;top:${sp.bezel}px;width:${sc.w}px;height:${sc.h}px;border-radius:${sp.screenRadius}px">
          <div class="screen-inner" style="width:${sc.w}px;height:${sc.h}px">
            <img src="${sc.src}" width="${sc.w}" height="${sc.h}" alt="">
            ${statusBar(kind, sc.bar)}
          </div>
        </div>
        ${cam}
      </div></div>`;
  }

  function card(key, { x, y, width, rot = 0, z = 3 }) {
    const sc = S[key];
    return `<img class="float" src="${sc.src}" style="left:${x}px;top:${y}px;width:${width}px;height:${width * sc.h / sc.w}px;transform:rotate(${rot}deg);z-index:${z}" alt="">`;
  }

  function sheets(W, H, seed) {
    // A few large, faint paper shapes so the background isn't flat
    const r = (i) => ((Math.sin(seed * 91.7 + i * 13.3) + 1) / 2);
    let out = '';
    for (let i = 0; i < 3; i++) {
      const w = W * (0.42 + r(i) * 0.25), h = w * 1.3;
      out += `<div class="sheet" style="width:${w}px;height:${h}px;left:${r(i + 4) * W - w / 2}px;top:${H * (0.15 + r(i + 8) * 0.7) - h / 2}px;transform:rotate(${(r(i + 2) - 0.5) * 40}deg)"></div>`;
    }
    return out;
  }

  // ---------- Copy (editable) ----------
  const EDITS_KEY = 'papercase-marketing-edits';
  const edits = JSON.parse(localStorage.getItem(EDITS_KEY) || '{}');
  const text = (id, field, fallback) => (edits[id + '.' + field] ?? fallback);

  function copy(id, c, { W, top, align = 'center', size, sub, kicker, left = 0, width = W }) {
    const k = c.kicker ? `<p class="kicker" style="font-size:${kicker}px;margin-bottom:${kicker * 0.8}px">${c.kicker}</p>` : '';
    return `<div class="copy ${align}" style="top:${top}px;left:${left}px;width:${width}px;right:auto">
      ${k}
      <h2 class="headline" contenteditable spellcheck="false" data-edit="${id}.headline" style="font-size:${size}px">${text(id, 'headline', c.headline)}</h2>
      <p class="sub" contenteditable spellcheck="false" data-edit="${id}.sub" style="font-size:${sub}px;margin-top:${sub * 0.6}px">${text(id, 'sub', c.sub)}</p>
    </div>`;
  }

  // ---------- Layouts ----------
  const FORMATS = {
    phone: { W: 1080, H: 1920, size: 96, sub: 42, kicker: 30, dev: 0.74, top: 0.31, pairDev: 0.7 },
    tablet: { W: 1440, H: 2560, size: 124, sub: 54, kicker: 38, dev: 0.8, top: 0.29, pairDev: 0.98 },
  };

  /** One screenshot: headline on top, device below bleeding off the bottom edge. */
  function single(set, shot, i) {
    const f = FORMATS[set.format];
    const { W, H } = f;
    const dw = W * f.dev;
    let html = sheets(W, H, i + 1);
    html += copy(shot.id, shot, { W, top: H * 0.075, size: f.size, sub: f.sub, kicker: f.kicker });
    html += device(shot.screen, { x: (W - dw) / 2, y: H * f.top, width: dw });
    if (shot.card) html += card(shot.card.screen, { x: W * shot.card.x, y: H * shot.card.y, width: W * shot.card.w, rot: shot.card.rot });
    return { W, H, html, alt: i % 2 === 1 };
  }

  /** Two screenshots side by side with one device crossing the seam between them. */
  function pair(set, shot, i) {
    const f = FORMATS[set.format];
    const { W, H } = f;
    const dw = W * f.pairDev;
    let html = sheets(W * 2, H, i + 7);
    const half = { W, size: f.size * 0.95, sub: f.sub, kicker: f.kicker, width: W };
    html += copy(shot.id + '-a', shot.left, { ...half, top: H * 0.075, align: 'left', left: 0 });
    html += copy(shot.id + '-b', shot.right, { ...half, top: H * 0.075, align: 'right', left: W });
    html += device(shot.screen, { x: W - dw / 2 - W * 0.08, y: H * (f.top + 0.035), width: dw, rot: -7 });
    if (shot.card) html += card(shot.card.screen, { x: W * shot.card.x, y: H * shot.card.y, width: W * shot.card.w, rot: shot.card.rot });
    return { W: W * 2, H, html, alt: false, pair: true };
  }

  /** Play Store icon: full-bleed square; Play rounds the corners and adds the shadow itself. */
  function storeIcon() {
    const W = 512, H = 512;
    const svg = ICON.replace('viewBox="18 18 72 72"', 'viewBox="18 18 72 72" style="width:512px;height:512px;display:block"')
      .replace('<rect x="18" y="18" width="72" height="72" rx="16"/>', '<rect x="18" y="18" width="72" height="72"/>')
      .replace(/ic-r/g, 'ic-sq');
    return { W, H, html: svg, plain: true };
  }

  function feature() {
    const W = 1024, H = 500;
    let html = sheets(W, H * 2, 3);
    html += `<div style="position:absolute;left:64px;top:84px;width:520px">
      <div style="display:flex;align-items:center;gap:22px">
        <div style="width:104px;height:104px;border-radius:24px;box-shadow:0 18px 40px rgba(2,12,40,.35);overflow:hidden">${ICON.replace('<svg ', '<svg style="width:104px;height:104px;display:block" ')}</div>
        <h2 class="headline" style="font-size:76px">Papercase</h2>
      </div>
      <p class="sub" contenteditable spellcheck="false" data-edit="feature.sub" style="font-size:31px;margin-top:26px;color:#fff;font-weight:600">${text('feature', 'sub', 'Your important papers, encrypted on your phone.')}</p>
      <div class="badges" style="margin-top:26px"><span class="badge">Offline</span><span class="badge">No account</span><span class="badge">Open source</span></div>
    </div>`;
    html += device('phone-licence', { x: 640, y: 46, width: 270, rot: -8 });
    html += card('art-passport', { x: 790, y: 250, width: 236, rot: 10 });
    return { W, H, html };
  }

  // ---------- Content ----------
  const SETS = [
    {
      id: 'icon', title: 'App icon', spec: '512 × 512 PNG · Google Play (Play rounds the corners)', format: 'icon', ext: 'png',
      shots: [{ id: 'icon', name: 'app-icon-512', build: storeIcon }],
    },
    {
      id: 'feature', title: 'Feature graphic', spec: '1024 × 500 · Google Play', format: 'feature',
      shots: [{ id: 'feature', name: 'feature-graphic', build: feature }],
    },
    {
      id: 'phone', title: 'Phone screenshots', spec: '1080 × 1920 (9:16) · Google Play phone', format: 'phone',
      shots: [
        { id: 'p1', name: '01-locker', screen: 'phone-locker', kicker: 'Papercase',
          headline: 'Your papers, safely cased', sub: 'Passport, licence, health card and more, encrypted on your phone.' },
        { id: 'p2', name: ['02-zoom', '03-cloud'], pair: true, screen: 'phone-zoom',
          left: { headline: 'Zoom in on the fine print', sub: 'Pinch any photo or PDF page, and swipe through every page.' },
          right: { headline: 'Never stored in the cloud', sub: 'Location and camera details are removed from every photo.' },
          card: { screen: 'art-passport', x: 1.12, y: 0.6, w: 0.74, rot: 9 } },
        { id: 'p4', name: '04-folders', screen: 'phone-folders',
          headline: 'Organised for the whole family', sub: 'Folders inside folders, your own icons, and expiry dates you can\'t miss.' },
        { id: 'p5', name: '05-locked', screen: 'phone-locked',
          headline: 'Opens only for you', sub: 'Unlock with your fingerprint or device PIN. It locks itself when you leave.' },
        { id: 'p6', name: '06-transfer', screen: 'phone-transfer',
          headline: 'New phone? No cloud needed', sub: 'Send straight to your other device over Wi-Fi, or back up to a KeePass file.' },
        { id: 'p7', name: '07-passphrase', screen: 'phone-passphrase',
          headline: 'Your passphrase, one tap away', sub: 'Copy it with your fingerprint. The clipboard clears itself.' },
      ],
    },
    {
      id: 'tablet', title: 'Tablet screenshots', spec: '1440 × 2560 (9:16) · Google Play 7" and 10" tablet', format: 'tablet',
      shots: [
        { id: 't1', name: '01-locker', screen: 'tablet-locker', kicker: 'Papercase',
          headline: 'Your papers, safely cased', sub: 'Passport, licence, health card and more, encrypted on your device.' },
        { id: 't2', name: ['02-zoom', '03-cloud'], pair: true, screen: 'tablet-zoom',
          left: { headline: 'Zoom in on the fine print', sub: 'Pinch any photo or PDF page, and swipe through every page.' },
          right: { headline: 'Never stored in the cloud', sub: 'Location and camera details are removed from every photo.' },
          card: { screen: 'art-licence', x: 1.16, y: 0.66, w: 0.72, rot: 8 } },
        { id: 't4', name: '04-folders', screen: 'tablet-folders',
          headline: 'Organised for the whole family', sub: 'Folders inside folders, your own icons, and expiry dates you can\'t miss.' },
        { id: 't5', name: '05-locked', screen: 'tablet-locked',
          headline: 'Opens only for you', sub: 'Unlock with your fingerprint or device PIN. It locks itself when you leave.' },
        { id: 't6', name: '06-transfer', screen: 'tablet-transfer',
          headline: 'New device? No cloud needed', sub: 'Send straight to your other device over Wi-Fi, or back up to a KeePass file.' },
        { id: 't7', name: '07-passphrase', screen: 'tablet-passphrase',
          headline: 'Your passphrase, one tap away', sub: 'Copy it with your fingerprint. The clipboard clears itself.' },
      ],
    },
  ];

  function build(set, shot, i) {
    if (shot.build) return shot.build();
    return shot.pair ? pair(set, shot, i) : single(set, shot, i);
  }

  // ---------- Page ----------
  const PREVIEW_H = { icon: 200, feature: 250, phone: 460, tablet: 460 };
  const nodes = new Map(); // shot id -> { el, W, H, pair, set, shot }

  function render() {
    document.getElementById('logo').innerHTML = ICON;
    const root = document.getElementById('sets');
    root.innerHTML = '';
    for (const set of SETS) {
      const sec = document.createElement('section');
      sec.className = 'set';
      sec.innerHTML = `<div class="set-head"><h2>${set.title}</h2><span class="spec">${set.spec}</span><button data-set="${set.id}">Download set (.zip)</button></div><div class="row"></div>`;
      const row = sec.querySelector('.row');
      set.shots.forEach((shot, i) => {
        const b = build(set, shot, i);
        const scale = PREVIEW_H[set.format] / b.H;
        const c = document.createElement('div');
        c.className = 'card';
        const names = [].concat(shot.name);
        c.innerHTML = `<div class="preview" style="width:${b.W * scale}px;height:${b.H * scale}px">
            <div class="canvas${b.alt ? ' alt' : ''}${b.plain ? ' plain' : ''}" id="cv-${shot.id}" style="width:${b.W}px;height:${b.H}px;transform:scale(${scale})">${b.html}</div>
            ${b.pair ? `<div class="pair-split" style="left:${(b.W / 2) * scale}px"></div>` : ''}
          </div>
          <div class="meta"><span>${names.map((n) => n + '.' + (set.ext || 'jpg')).join(' + ')}</span><button data-shot="${shot.id}">Download</button></div>`;
        row.appendChild(c);
        nodes.set(shot.id, { el: c.querySelector('.canvas'), W: b.W, H: b.H, pair: !!b.pair, set, shot });
      });
      root.appendChild(sec);
    }
  }

  async function renderShot(id) {
    const n = nodes.get(id);
    document.activeElement?.blur();
    await document.fonts.ready;
    const canvas = await htmlToImage.toCanvas(n.el, {
      width: n.W, height: n.H, pixelRatio: 1, cacheBust: false,
      style: { transform: 'none' },
    });
    const names = [].concat(n.shot.name);
    if (!n.pair) return [{ name: names[0], canvas }];
    // Split the double-wide canvas into its two screenshots
    return names.map((name, i) => {
      const half = document.createElement('canvas');
      half.width = n.W / 2; half.height = n.H;
      half.getContext('2d').drawImage(canvas, i * n.W / 2, 0, n.W / 2, n.H, 0, 0, n.W / 2, n.H);
      return { name, canvas: half };
    });
  }

  // JPEG for screenshots and the feature graphic (no alpha); PNG for the icon
  const encode = (canvas, ext) => new Promise((ok) => canvas.toBlob(ok, ext === 'png' ? 'image/png' : 'image/jpeg', 0.95));

  function save(blob, filename) {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = filename;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 5000);
  }

  async function downloadShot(id) {
    const n = nodes.get(id);
    const ext = n.set.ext || 'jpg';
    const flat = n.set.id === 'feature' || n.set.id === 'icon';
    for (const { name, canvas } of await renderShot(id)) {
      save(await encode(canvas, ext), `papercase-${flat ? '' : n.set.id + '-'}${name}.${ext}`);
    }
  }

  async function downloadZip(setIds, filename) {
    const zip = new JSZip();
    for (const set of SETS.filter((s) => setIds.includes(s.id))) {
      for (const shot of set.shots) {
        toast(`Rendering ${set.title.toLowerCase()}…`);
        for (const { name, canvas } of await renderShot(shot.id)) {
          const ext = set.ext || 'jpg';
          const path = set.id === 'feature' || set.id === 'icon' ? `${name}.${ext}` : `${set.id}/${name}.${ext}`;
          zip.file(path, await encode(canvas, ext));
        }
      }
    }
    save(await zip.generateAsync({ type: 'blob' }), filename);
    toast('Downloaded ' + filename);
  }

  let toastTimer;
  function toast(msg) {
    const t = document.getElementById('toast');
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove('show'), 2200);
  }

  async function busy(button, fn) {
    button.disabled = true;
    try { await fn(); } catch (e) { console.error(e); toast('Export failed: ' + e.message); } finally { button.disabled = false; }
  }

  document.addEventListener('click', (e) => {
    const b = e.target.closest('button');
    if (!b) return;
    if (b.dataset.shot) busy(b, () => downloadShot(b.dataset.shot));
    else if (b.dataset.set) busy(b, () => downloadZip([b.dataset.set], `papercase-${b.dataset.set}.zip`));
    else if (b.id === 'dl-all') busy(b, () => downloadZip(SETS.map((s) => s.id), 'papercase-store-graphics.zip'));
    else if (b.id === 'reset-edits') { localStorage.removeItem(EDITS_KEY); location.reload(); }
  });

  document.addEventListener('input', (e) => {
    const key = e.target.dataset?.edit;
    if (!key) return;
    edits[key] = e.target.innerHTML;
    localStorage.setItem(EDITS_KEY, JSON.stringify(edits));
  });

  render();

  // ?render=<shot id>[&part=0|1]: replace the page with the exported image (used to check exports headlessly)
  const q = new URLSearchParams(location.search);
  if (q.get('render')) {
    renderShot(q.get('render')).then((parts) => {
      const p = parts[Number(q.get('part') || 0)];
      document.body.innerHTML = '';
      document.body.style.margin = '0';
      const img = new Image();
      img.src = p.canvas.toDataURL('image/jpeg', 0.95);
      img.id = 'rendered';
      document.body.appendChild(img);
    });
  }
})();
