// Procedural destination scenes, so the app needs no photos.
// Each hotel gets a scene from its setting (Bosphorus, cave country, coast...),
// its time of day and a seed, so no two cards look the same.

function rng(seed) {
  let h = 2166136261 ^ (typeof seed === 'number' ? seed : [...String(seed)].reduce((a, c) => Math.imul(a ^ c.charCodeAt(0), 16777619), 7));
  return () => {
    h = Math.imul(h ^ (h >>> 15), h | 1);
    h ^= h + Math.imul(h ^ (h >>> 7), h | 61);
    return ((h ^ (h >>> 14)) >>> 0) / 4294967296;
  };
}

const SKIES = {
  dawn: { sky: ['#8fb8e6', '#f6c9b0', '#ffe2c2'], sun: '#fff1c9', water: ['#7ea7d6', '#3f6aa6'], tint: 0.05, land: '#6f7fa3' },
  day: { sky: ['#4aa3ea', '#9fd2fb', '#e3f4ff'], sun: '#fff6cf', water: ['#3b8bd8', '#1c4f8f'], tint: 0, land: '#5d7194' },
  sunset: { sky: ['#3b3a7c', '#e8706b', '#ffc76e'], sun: '#ffe3a1', water: ['#c56a6e', '#3a3a75'], tint: 0.18, land: '#4a3f6b' },
  night: { sky: ['#06122b', '#14295a', '#2b4580'], sun: '#f4f1df', water: ['#1d3561', '#08152e'], tint: 0.45, land: '#1d2a4a' },
};

const shade = (hex, amt) => {
  const n = parseInt(hex.slice(1), 16);
  const f = (c) => Math.max(0, Math.min(255, Math.round(c + (amt < 0 ? c * amt : (255 - c) * amt))));
  return `#${[(n >> 16) & 255, (n >> 8) & 255, n & 255].map(f).map((c) => c.toString(16).padStart(2, '0')).join('')}`;
};
// Darken a colour for the time of day
const tod = (hex, t) => shade(hex, -t.tint);

let uid = 0;

export function scene(h, { W = 320, H = 200, cls = '' } = {}) {
  const id = `s${++uid}`;
  const r = rng(h.art?.seed ?? h._id ?? h.name ?? 'x');
  const time = h.art?.time || 'day';
  const t = SKIES[time];
  const setting = h.setting || 'city';
  const parts = [];

  // sky
  parts.push(`<defs>
    <linearGradient id="${id}sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${t.sky[0]}"/><stop offset=".62" stop-color="${t.sky[1]}"/><stop offset="1" stop-color="${t.sky[2]}"/></linearGradient>
    <linearGradient id="${id}water" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${t.water[0]}"/><stop offset="1" stop-color="${t.water[1]}"/></linearGradient>
    <radialGradient id="${id}glow"><stop offset="0" stop-color="${t.sun}" stop-opacity=".9"/><stop offset="1" stop-color="${t.sun}" stop-opacity="0"/></radialGradient>
  </defs>`);
  parts.push(`<rect width="${W}" height="${H}" fill="url(#${id}sky)"/>`);

  // sun or moon
  const sx = W * (0.15 + r() * 0.7);
  const sy = time === 'sunset' ? 112 : time === 'dawn' ? 100 : 30 + r() * 30;
  if (time === 'night') {
    for (let i = 0; i < 40; i++) parts.push(`<circle cx="${(r() * W).toFixed(1)}" cy="${(r() * 100).toFixed(1)}" r="${(r() * 0.9 + 0.3).toFixed(2)}" fill="#fff" opacity="${(0.4 + r() * 0.6).toFixed(2)}"/>`);
    parts.push(`<circle cx="${sx}" cy="${sy}" r="11" fill="${t.sun}"/><circle cx="${sx + 5}" cy="${sy - 3}" r="10" fill="${t.sky[0]}"/>`);
  } else {
    parts.push(`<circle cx="${sx}" cy="${sy}" r="46" fill="url(#${id}glow)"/><circle cx="${sx}" cy="${sy}" r="${time === 'day' ? 12 : 17}" fill="${t.sun}"/>`);
  }
  // clouds
  if (time !== 'night') {
    for (let i = 0; i < 3; i++) {
      const cx = r() * W;
      const cy = 18 + r() * 50;
      const s = 0.6 + r() * 0.8;
      parts.push(`<g opacity="${time === 'sunset' ? 0.35 : 0.75}" fill="#fff"><ellipse cx="${cx}" cy="${cy}" rx="${22 * s}" ry="${6 * s}"/><ellipse cx="${cx + 10 * s}" cy="${cy - 4 * s}" rx="${12 * s}" ry="${6 * s}"/></g>`);
    }
  }

  const draw = SETTINGS[setting] || SETTINGS.city;
  parts.push(draw({ W, H, r, t, id, time, h }));

  // a pool in the foreground for the coast and countryside
  if ((h.amenities || []).includes('Pool') && ['sea', 'village', 'cave'].includes(setting)) {
    const px = W * (0.04 + r() * 0.2);
    parts.push(`<path d="M${px},${H - 4} l14,-22 h${W * 0.26} l14,22 z" fill="#5fd3e6" stroke="#fff" stroke-width="2.5"/><path d="M${px + 22},${H - 14} q10,-4 20,0 t20,0" stroke="#fff" stroke-width="1.5" fill="none" opacity=".7"/>`);
  }
  if (time === 'night') parts.push(`<rect width="${W}" height="${H}" fill="#0a1a3a" opacity=".12"/>`);

  return `<svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="xMidYMid slice" class="${cls}" aria-hidden="true" focusable="false">${parts.join('')}</svg>`;
}

// ----------------------------------------------------------------- pieces
const lit = (time, r) => (time === 'night' || time === 'sunset') && r() < 0.6 ? '#ffd76a' : null;

function windows(x, y, w, h, color, time, r, cols = 3, rows = 4) {
  let s = '';
  const gw = w / (cols * 2 + 1);
  const gh = h / (rows * 2 + 1);
  for (let i = 0; i < cols; i++) {
    for (let j = 0; j < rows; j++) {
      const c = lit(time, r) || color;
      s += `<rect x="${(x + gw * (1 + i * 2)).toFixed(1)}" y="${(y + gh * (1 + j * 2)).toFixed(1)}" width="${gw.toFixed(1)}" height="${gh.toFixed(1)}" fill="${c}"/>`;
    }
  }
  return s;
}

function mosque(cx, base, s, fill) {
  // central dome, half domes and minarets
  let g = `<g fill="${fill}">`;
  g += `<rect x="${cx - 34 * s}" y="${base - 22 * s}" width="${68 * s}" height="${22 * s}"/>`;
  g += `<path d="M${cx - 24 * s},${base - 22 * s} a${24 * s},${22 * s} 0 0 1 ${48 * s},0 z"/>`;
  g += `<rect x="${cx - 2 * s}" y="${base - 52 * s}" width="${4 * s}" height="${9 * s}"/>`;
  g += `<path d="M${cx - 44 * s},${base - 10 * s} a${12 * s},${11 * s} 0 0 1 ${22 * s},0 z M${cx + 22 * s},${base - 10 * s} a${12 * s},${11 * s} 0 0 1 ${22 * s},0 z"/>`;
  for (const dx of [-50, 50]) {
    g += `<rect x="${cx + dx * s - 2.2 * s}" y="${base - 70 * s}" width="${4.4 * s}" height="${70 * s}"/>`;
    g += `<path d="M${cx + dx * s - 2.6 * s},${base - 70 * s} l${2.6 * s},${-12 * s} l${2.6 * s},${12 * s} z"/>`;
    g += `<rect x="${cx + dx * s - 3.6 * s}" y="${base - 50 * s}" width="${7.2 * s}" height="${2 * s}"/>`;
  }
  return g + '</g>';
}

function palm(x, base, s, color) {
  return `<g stroke="${color}" fill="none" stroke-linecap="round"><path d="M${x},${base} q${6 * s},${-30 * s} ${2 * s},${-58 * s}" stroke-width="${3.4 * s}"/>
    <g stroke-width="${3 * s}"><path d="M${x + 2 * s},${base - 58 * s} q${-14 * s},${-6 * s} ${-26 * s},${6 * s}"/><path d="M${x + 2 * s},${base - 58 * s} q${16 * s},${-8 * s} ${28 * s},${4 * s}"/>
    <path d="M${x + 2 * s},${base - 58 * s} q${-6 * s},${-14 * s} ${-18 * s},${-14 * s}"/><path d="M${x + 2 * s},${base - 58 * s} q${8 * s},${-14 * s} ${20 * s},${-12 * s}"/></g></g>`;
}

function pine(x, base, s, color) {
  return `<path d="M${x},${base - 34 * s} l${9 * s},${14 * s} h${-4 * s} l${8 * s},${11 * s} h${-5 * s} l${8 * s},${9 * s} h${-32 * s} l${8 * s},${-9 * s} h${-5 * s} l${8 * s},${-11 * s} h${-4 * s} z" fill="${color}"/><rect x="${x - 1.5 * s}" y="${base}" width="${3 * s}" height="${4 * s}" fill="${color}"/>`;
}

function balloon(x, y, s, r) {
  const colors = [['#e63946', '#ffd166'], ['#2a9d8f', '#f4f1de'], ['#ff7b00', '#3a86ff'], ['#8338ec', '#ffbe0b'], ['#ef476f', '#06d6a0']];
  const [a, b] = colors[Math.floor(r() * colors.length)];
  return `<g transform="translate(${x} ${y}) scale(${s})"><path d="M0,-14 c10,0 14,8 12,15 c-2,6 -8,10 -10,13 h-4 c-2,-3 -8,-7 -10,-13 c-2,-7 2,-15 12,-15 z" fill="${a}"/>
    <path d="M0,-14 c4,0 5,10 3,16 c-1,4 -2,8 -2,12 h-2 c0,-4 -1,-8 -2,-12 c-2,-6 -1,-16 3,-16 z" fill="${b}"/><path d="M-2,14 l-1,5 M2,14 l1,5" stroke="#5a4a3a" stroke-width=".6"/><rect x="-2.5" y="19" width="5" height="3.5" fill="#7a5a3a"/></g>`;
}

function tent(x, base, s, color) {
  return `<path d="M${x},${base} l${16 * s},${-24 * s} l${16 * s},${24 * s} z" fill="${color}"/><path d="M${x + 16 * s},${base - 24 * s} l${-5 * s},${24 * s} h${10 * s} z" fill="#00000030"/>`;
}

// ----------------------------------------------------------------- settings
const SETTINGS = {
  bosphorus({ W, H, r, t, id, time }) {
    const horizon = 122;
    const shore = tod('#556a92', t);
    let s = `<path d="M0,${horizon} L0,${horizon - 14} Q${W * 0.2},${horizon - 28} ${W * 0.45},${horizon - 18} T${W},${horizon - 22} L${W},${horizon} Z" fill="${shade(shore, 0.15)}"/>`;
    // skyline on the far shore
    s += mosque(W * (0.25 + r() * 0.5), horizon - 6, 0.55 + r() * 0.2, tod('#3e4e74', t));
    for (let i = 0; i < 14; i++) {
      const x = r() * W;
      const w = 8 + r() * 14;
      const hh = 6 + r() * 12;
      s += `<rect x="${x}" y="${horizon - hh}" width="${w}" height="${hh}" fill="${tod('#4a5b82', t)}"/>`;
      if (lit(time, r)) s += `<rect x="${x + 2}" y="${horizon - hh + 2}" width="2" height="2" fill="#ffd76a"/>`;
    }
    // suspension bridge
    const b1 = W * 0.12;
    const b2 = W * 0.82;
    const deck = horizon - 10;
    const top = horizon - 58;
    const steel = tod('#2c3550', t);
    s += `<g stroke="${steel}" fill="none"><path d="M${b1 - 40},${deck - 2} Q${b1 - 20},${deck - 12} ${b1},${top} Q${(b1 + b2) / 2},${deck + 4} ${b2},${top} Q${b2 + 20},${deck - 12} ${b2 + 40},${deck - 2}" stroke-width="1.6"/>`;
    for (let x = b1 + 8; x < b2; x += 8) {
      const k = (x - b1) / (b2 - b1);
      const y = top + (deck + 4 - top) * (1 - Math.pow(2 * k - 1, 2)) * 0.9;
      s += `<path d="M${x},${y.toFixed(1)} V${deck}" stroke-width=".5" opacity=".7"/>`;
    }
    s += `</g><rect x="${b1 - 2}" y="${top}" width="4" height="${horizon - top}" fill="${steel}"/><rect x="${b2 - 2}" y="${top}" width="4" height="${horizon - top}" fill="${steel}"/><rect x="0" y="${deck}" width="${W}" height="2.5" fill="${steel}"/>`;
    if (time === 'night' || time === 'sunset') for (let x = 6; x < W; x += 14) s += `<circle cx="${x}" cy="${deck + 1}" r="1" fill="#ffe08a"/>`;
    // water, reflections, ferry
    s += `<rect y="${horizon}" width="${W}" height="${H - horizon}" fill="url(#${id}water)"/>`;
    for (let i = 0; i < 9; i++) s += `<rect x="${r() * W}" y="${horizon + 6 + r() * (H - horizon - 10)}" width="${10 + r() * 30}" height="1.2" fill="#fff" opacity=".25"/>`;
    const fx = W * (0.15 + r() * 0.6);
    const fy = horizon + 30 + r() * 25;
    s += `<g><path d="M${fx},${fy} h46 l-6,8 h-36 z" fill="#f4f4f4"/><rect x="${fx + 8}" y="${fy - 8}" width="26" height="8" fill="#fff"/><rect x="${fx + 10}" y="${fy - 6}" width="22" height="3" fill="#24486e"/><rect x="${fx + 24}" y="${fy - 15}" width="4" height="7" fill="#d23b3b"/></g>`;
    return s;
  },

  historic({ W, H, r, t, time }) {
    const base = 150;
    let s = mosque(W * (0.35 + r() * 0.3), base, 1 + r() * 0.25, tod('#45507a', t));
    if (r() < 0.6) s += mosque(W * (r() < 0.5 ? 0.12 : 0.88), base + 4, 0.55, tod('#5a6690', t));
    // rooftops
    let x = -10;
    while (x < W) {
      const w = 26 + r() * 30;
      const hh = 22 + r() * 22;
      const wall = tod(['#e9d8c0', '#d8b99a', '#efe1cf', '#c9a27e'][Math.floor(r() * 4)], t);
      s += `<rect x="${x}" y="${H - hh}" width="${w}" height="${hh}" fill="${wall}"/><path d="M${x - 3},${H - hh} l${w / 2 + 3},-12 l${w / 2 + 3},12 z" fill="${tod('#b5523b', t)}"/>`;
      s += windows(x, H - hh + 4, w, hh - 6, tod('#7a5a44', t), time, r, 2, 2);
      x += w + 2;
    }
    return s;
  },

  city({ W, H, r, t, time }) {
    let s = '';
    // Galata-style tower
    const tx = W * (0.2 + r() * 0.6);
    const stone = tod('#c8b49a', t);
    s += `<rect x="${tx - 10}" y="70" width="20" height="${H - 70}" fill="${stone}"/><path d="M${tx - 12},70 l12,-26 l12,26 z" fill="${tod('#4f5d7e', t)}"/><rect x="${tx - 12}" y="78" width="24" height="5" fill="${shade(stone, -0.2)}"/>`;
    for (let i = 0; i < 4; i++) s += `<rect x="${tx - 6 + (i % 2) * 8}" y="${92 + i * 14}" width="4" height="7" fill="${lit(time, r) || shade(stone, -0.35)}"/>`;
    let x = -6;
    while (x < W) {
      const w = 22 + r() * 26;
      const hh = 40 + r() * 50;
      if (Math.abs(x + w / 2 - tx) > 24) {
        const wall = tod(['#d7c7b5', '#c7d0dc', '#e6d4c1', '#b9a48e', '#d9cdbf'][Math.floor(r() * 5)], t);
        s += `<rect x="${x}" y="${H - hh}" width="${w}" height="${hh}" fill="${wall}"/>`;
        s += windows(x, H - hh + 3, w, hh - 3, shade(wall, -0.3), time, r, 3, Math.round(hh / 14));
      }
      x += w + 3;
    }
    return s;
  },

  business({ W, H, r, t, time }) {
    let s = '';
    let x = -8;
    while (x < W) {
      const w = 26 + r() * 34;
      const hh = 70 + r() * 100;
      const glass = tod(['#6f9fcf', '#8fb5d8', '#5d84b3', '#a4c3e0'][Math.floor(r() * 4)], t);
      s += `<rect x="${x}" y="${H - hh}" width="${w}" height="${hh}" fill="${glass}"/><rect x="${x}" y="${H - hh}" width="${w * 0.35}" height="${hh}" fill="#fff" opacity=".12"/>`;
      for (let y = H - hh + 6; y < H; y += 7) s += `<rect x="${x + 3}" y="${y}" width="${w - 6}" height="1" fill="${lit(time, r) || '#ffffff'}" opacity="${time === 'night' ? 0.8 : 0.25}"/>`;
      x += w + 6;
    }
    return s;
  },

  sea({ W, H, r, t, id, time, h }) {
    const horizon = 110;
    let s = `<path d="M${W * 0.55},${horizon} q${W * 0.15},-26 ${W * 0.45},-18 V${horizon} z" fill="${tod('#7d8fb0', t)}" opacity=".8"/>`;
    s += `<rect y="${horizon}" width="${W}" height="${H - horizon}" fill="url(#${id}water)"/>`;
    for (let i = 0; i < 8; i++) s += `<rect x="${r() * W}" y="${horizon + 4 + r() * 40}" width="${8 + r() * 26}" height="1.2" fill="#fff" opacity=".35"/>`;
    // sailboat
    const bx = W * (0.3 + r() * 0.5);
    s += `<path d="M${bx},${horizon + 14} l10,-24 v24 z" fill="#fff"/><path d="M${bx - 6},${horizon + 15} h22 l-4,4 h-14 z" fill="${tod('#3a4a6a', t)}"/>`;
    // beach
    s += `<path d="M0,${H - 34} Q${W * 0.35},${H - 52} ${W * 0.7},${H - 38} T${W},${H - 44} V${H} H0 Z" fill="${tod('#f1d9a8', t)}"/>`;
    s += `<path d="M0,${H - 34} Q${W * 0.35},${H - 52} ${W * 0.7},${H - 38} T${W},${H - 44}" stroke="#fff" stroke-width="2" fill="none" opacity=".7"/>`;
    const trunk = tod(time === 'sunset' || time === 'night' ? '#2b2440' : '#3f6b3a', t);
    s += palm(W * (0.78 + r() * 0.15), H - 30, 1, trunk) + palm(W * (0.62 + r() * 0.1), H - 34, 0.75, trunk);
    for (let i = 0; i < 3; i++) {
      const ux = W * (0.35 + i * 0.09 + r() * 0.03);
      const c = ['#e63946', '#f4a261', '#2a9d8f'][i];
      s += `<path d="M${ux},${H - 20} v-16" stroke="#555" stroke-width="1"/><path d="M${ux - 10},${H - 34} q10,-10 20,0 z" fill="${c}"/>`;
    }
    if (h.type === 'Glamping') s += tent(W * 0.12, H - 22, 0.9, tod('#f4ead2', t)) + tent(W * 0.24, H - 26, 0.7, tod('#e9dcbd', t));
    return s;
  },

  cave({ W, H, r, t, time }) {
    let s = '';
    const n = time === 'dawn' ? 9 : time === 'night' ? 0 : 4;
    for (let i = 0; i < n; i++) s += balloon(r() * W, 22 + r() * 70, 0.6 + r() * 0.9, r);
    s += `<path d="M0,128 Q${W * 0.25},102 ${W * 0.5},122 T${W},112 V${H} H0 Z" fill="${tod('#e6c9a2', t)}"/>`;
    // fairy chimneys
    for (let i = 0; i < 7; i++) {
      const x = r() * W;
      const hh = 34 + r() * 46;
      const w = 12 + r() * 12;
      const base = 150 + r() * 30;
      const rock = tod(['#e9cfa9', '#dcb88d', '#f0dcbc'][Math.floor(r() * 3)], t);
      s += `<path d="M${x - w},${base} Q${x - w * 0.4},${base - hh * 0.6} ${x - 3},${base - hh} h6 Q${x + w * 0.4},${base - hh * 0.6} ${x + w},${base} z" fill="${rock}"/>`;
      s += `<ellipse cx="${x}" cy="${base - hh}" rx="7" ry="4" fill="${tod('#8a6b4e', t)}"/>`;
      if (r() < 0.7) s += `<path d="M${x - 3},${base - hh * 0.4} v-5 a3,3 0 0 1 6,0 v5 z" fill="${lit(time, r) || tod('#5b4434', t)}"/>`;
    }
    s += `<path d="M0,170 Q${W * 0.3},158 ${W * 0.6},172 T${W},166 V${H} H0 Z" fill="${tod('#d4ae82', t)}"/>`;
    return s;
  },

  mountain({ W, H, r, t, time }) {
    let s = '';
    for (let layer = 0; layer < 2; layer++) {
      let path = `M0,${H}`;
      let x = 0;
      const base = layer ? 130 : 110;
      while (x < W + 40) {
        const peak = base - 40 - r() * (layer ? 30 : 50);
        path += ` L${x},${base} L${x + 40},${peak}`;
        if (!layer) s += `<path d="M${x + 40},${peak} l-11,${14} l6,-2 l5,5 l5,-5 l6,2 z" fill="#fff" opacity=".95"/>`;
        x += 80 + r() * 30;
      }
      path += ` L${W},${H} Z`;
      s = `<path d="${path}" fill="${tod(layer ? '#5d7194' : '#8ea0c0', t)}"/>` + s;
    }
    s += `<path d="M0,160 Q${W * 0.4},146 ${W},158 V${H} H0 Z" fill="${tod('#e9f0f7', t)}"/>`;
    // chalet
    const cx = W * (0.4 + r() * 0.3);
    s += `<rect x="${cx}" y="150" width="40" height="22" fill="${tod('#8a5a3c', t)}"/><path d="M${cx - 6},150 l26,-18 l26,18 z" fill="${tod('#f4f7fb', t)}"/><rect x="${cx + 8}" y="156" width="8" height="7" fill="${lit(time, r) || '#ffd76a'}"/><rect x="${cx + 24}" y="156" width="8" height="7" fill="#ffd76a"/>`;
    for (let i = 0; i < 8; i++) s += pine(r() * W, 178 + r() * 18, 0.8 + r() * 0.6, tod('#24553f', t));
    return s;
  },

  lake({ W, H, r, t, id, time }) {
    let s = `<path d="M0,112 L${W * 0.18},62 L${W * 0.34},104 L${W * 0.55},58 L${W * 0.78},100 L${W},70 L${W},112 Z" fill="${tod('#4f7a5f', t)}"/>`;
    s += `<rect y="112" width="${W}" height="${H - 112}" fill="url(#${id}water)"/>`;
    s += `<path d="M0,112 L${W * 0.18},150 L${W * 0.34},118 L${W * 0.55},156 L${W * 0.78},122 L${W},146 L${W},112 Z" fill="${tod('#4f7a5f', t)}" opacity=".3"/>`;
    // wooden houses on the shore
    for (let i = 0; i < 3; i++) {
      const x = W * (0.1 + i * 0.12 + r() * 0.05);
      s += `<rect x="${x}" y="96" width="16" height="16" fill="${tod('#9b6a43', t)}"/><path d="M${x - 2},96 l10,-8 l10,8 z" fill="${tod('#6b3e2a', t)}"/><rect x="${x + 5}" y="101" width="5" height="5" fill="${lit(time, r) || '#f2e3c6'}"/>`;
    }
    // a mosque by the lake, Uzungöl style
    const mx = W * 0.72;
    s += `<rect x="${mx}" y="94" width="20" height="18" fill="${tod('#f1ece2', t)}"/><path d="M${mx + 2},94 a8,7 0 0 1 16,0 z" fill="${tod('#7d8fa8', t)}"/><rect x="${mx + 22}" y="70" width="3" height="42" fill="${tod('#f1ece2', t)}"/><path d="M${mx + 21.5},70 l2,-8 l2,8 z" fill="${tod('#7d8fa8', t)}"/>`;
    for (let i = 0; i < 10; i++) s += pine(i < 5 ? r() * W * 0.3 : W * 0.7 + r() * W * 0.3, 176 + r() * 22, 0.9 + r() * 0.6, tod('#1f4a36', t));
    return s;
  },

  village({ W, H, r, t, time, h }) {
    let s = `<path d="M0,124 Q${W * 0.3},92 ${W * 0.6},116 T${W},100 V${H} H0 Z" fill="${tod('#a7b97a', t)}"/>`;
    // windmill
    if (r() < 0.6) {
      const wx = W * (0.7 + r() * 0.2);
      s += `<path d="M${wx - 8},118 l3,-34 h10 l3,34 z" fill="${tod('#f4efe6', t)}"/><path d="M${wx},82 l-6,-6 l6,6 l6,-6" stroke="${tod('#6b5a48', t)}" stroke-width="1"/>`;
      s += `<g stroke="${tod('#6b5a48', t)}" stroke-width="1.6">${[0, 90, 180, 270].map((a) => `<path d="M${wx},84 l${(22 * Math.cos(((a + 20) * Math.PI) / 180)).toFixed(1)},${(22 * Math.sin(((a + 20) * Math.PI) / 180)).toFixed(1)}"/>`).join('')}</g>`;
    }
    s += `<path d="M0,150 Q${W * 0.4},128 ${W * 0.7},146 T${W},140 V${H} H0 Z" fill="${tod('#c6c08c', t)}"/>`;
    // whitewashed stone houses with blue shutters
    for (let i = 0; i < 6; i++) {
      const x = r() * (W - 30);
      const y = 140 + r() * 30;
      const w = 22 + r() * 14;
      s += `<rect x="${x}" y="${y - 16}" width="${w}" height="18" fill="${tod('#f7f4ee', t)}"/><rect x="${x}" y="${y - 18}" width="${w}" height="3" fill="${tod('#d8cfc0', t)}"/>`;
      s += `<rect x="${x + 4}" y="${y - 12}" width="5" height="7" fill="${lit(time, r) || '#2f6db5'}"/><rect x="${x + w - 9}" y="${y - 12}" width="5" height="7" fill="#2f6db5"/>`;
    }
    // olive trees and a cypress
    for (let i = 0; i < 5; i++) {
      const x = r() * W;
      const y = 176 + r() * 18;
      s += `<rect x="${x - 1}" y="${y}" width="2" height="8" fill="#6b5236"/><ellipse cx="${x}" cy="${y - 2}" rx="${9 + r() * 5}" ry="7" fill="${tod('#6f8a4c', t)}"/>`;
    }
    const cx = W * r();
    s += `<path d="M${cx},120 q6,20 3,52 h-6 q-3,-32 3,-52 z" fill="${tod('#2f5a35', t)}"/>`;
    if (h.type === 'Glamping') s += tent(W * 0.55, H - 10, 1, tod('#f4ead2', t)) + tent(W * 0.72, H - 14, 0.8, tod('#efe2c2', t));
    return s;
  },
};

// The hero: the Bosphorus at dusk, wide.
export function heroScene() {
  return scene({ setting: 'bosphorus', art: { seed: 1017, time: 'sunset' }, amenities: [] }, { W: 640, H: 200 });
}

export function logoMark(size = 30) {
  return `<svg viewBox="0 0 40 40" width="${size}" height="${size}" aria-hidden="true">
    <rect width="40" height="40" rx="11" fill="#fff"/>
    <path d="M20 6.5c-6.1 0-10.5 4.6-10.5 10.3 0 7.7 10.5 16.7 10.5 16.7s10.5-9 10.5-16.7C30.5 11.1 26.1 6.5 20 6.5z" fill="#1f6fd1"/>
    <path d="M14.5 20v-3.6l5.5-3.9 5.5 3.9V20" fill="none" stroke="#fff" stroke-width="2.2" stroke-linejoin="round"/>
    <circle cx="20" cy="27" r="1.6" fill="#00ED64"/>
  </svg>`;
}
