import { scene, heroScene, logoMark } from './art.js';

// ================================================================ state
const store = {
  get(k, d) {
    try {
      const v = localStorage.getItem(`stays.${k}`);
      return v ? JSON.parse(v) : d;
    } catch {
      return d;
    }
  },
  set(k, v) {
    try {
      localStorage.setItem(`stays.${k}`, JSON.stringify(v));
    } catch {}
  },
};

const newId = () => (crypto.randomUUID ? crypto.randomUUID() : `s${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`);

const S = {
  v: store.get('v', 'v1'),
  q: '',
  city: null,
  type: null,
  stars: null,
  pmin: 0,
  pmax: 1250,
  amenities: [],
  sort: '',
  page: 0,
  size: 24,
  compare: false,
  hybrid: false,
  items: [],
  total: 0,
  facets: null,
  last: null,
  cfg: null,
  status: null,
  requests: [],
  session: store.get('session', null) || newId(),
  chat: [],
  chatBusy: false,
};
store.set('session', S.session);

const VERSIONS = {
  v1: { name: 'Regex', tech: 'find() + $regex' },
  v2: { name: 'Atlas Search', tech: '$search + $searchMeta' },
  v3: { name: 'Vector Search', tech: '$vectorSearch + Voyage AI' },
};
const POPULAR_AMENITIES = ['Pool', 'Spa', 'Hammam', 'Sea view', 'Bosphorus view', 'Beach access', 'Pet friendly', 'Family rooms', 'Co-working space', 'Fireplace', 'Hot tub', 'Rooftop terrace', 'Parking', 'Gym', 'Airport shuttle', 'Kids club'];

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const eur = (n) => `€${Math.round(n).toLocaleString('en-US')}`;
const fold = (s) => String(s ?? '').toLocaleLowerCase('tr-TR').normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/ı/g, 'i');
const debounce = (fn, ms) => {
  let t;
  return (...a) => {
    clearTimeout(t);
    t = setTimeout(() => fn(...a), ms);
  };
};

function toast(msg) {
  const t = $('#toast');
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toast.t);
  toast.t = setTimeout(() => (t.hidden = true), 2600);
}

// ================================================================ api
class LabTodo extends Error {
  constructor(lab, message) {
    super(message);
    this.lab = lab;
  }
}

async function api(path, { method = 'GET', body, label } = {}) {
  const t0 = performance.now();
  const res = await fetch(path, { method, headers: body ? { 'content-type': 'application/json' } : {}, body: body ? JSON.stringify(body) : undefined });
  const json = await res.json().catch(() => ({}));
  if (res.status === 503) {
    pollStatus();
    throw new Error(json.error || 'Still starting');
  }
  if (res.status === 501 && json.lab) throw new LabTodo(json.lab, json.error);
  if (!res.ok) throw new Error(json.error || `HTTP ${res.status}`);
  const debug = [...(json.debug || []), ...(json.similar?.debug || [])];
  if (debug.length) logRequest({ label: label || path, ms: Math.round(performance.now() - t0), server: json.ms, debug });
  return json;
}

// ================================================================ status
let polling = false;
async function pollStatus() {
  if (polling) return;
  polling = true;
  const box = $('#status');
  for (;;) {
    let st;
    try {
      st = await (await fetch('/api/status')).json();
    } catch {
      st = { phase: 'connecting', message: 'The app server is not answering.' };
    }
    S.status = st;
    if (st.ready) {
      box.hidden = true;
      polling = false;
      renderStatusBits();
      return st;
    }
    box.hidden = false;
    box.classList.toggle('err', st.phase === 'error');
    $('#statusTitle').textContent = st.phase === 'error' ? 'Atlas connection failed' : 'Getting ready';
    $('#statusMsg').innerHTML = `${esc(st.message)}${st.phase === 'error' ? '<div class="muted">Check MONGODB_URI in your .env and that your IP is in the Atlas access list, then restart the app.</div>' : ''}`;
    await new Promise((r) => setTimeout(r, 1500));
  }
}

async function refreshStatus() {
  try {
    S.status = await (await fetch('/api/status')).json();
    renderStatusBits();
  } catch {}
}

const labDone = (id) => !!S.status?.labs?.find((l) => l.id === id)?.done;
const idx = (name) => S.status?.indexes?.[name] || {};

function renderStatusBits() {
  const st = S.status;
  if (!st) return;
  // header dot
  const a = idx('hotels_search');
  const b = idx('hotels_vector');
  const dot = $('#setupDot');
  dot.className = `dot ${st.phase === 'error' ? 'err' : a.queryable && b.queryable ? 'ok' : 'warn'}`;
  // version cards
  for (const v of ['v1', 'v2', 'v3']) {
    const labs = (st.labs || []).filter((l) => l.version === v && !l.stretch);
    const done = labs.filter((l) => l.done).length;
    const chips = [`<span class="chip ${done === labs.length ? 'chip--ok' : 'chip--todo'}">Labs ${done}/${labs.length}</span>`];
    if (v === 'v1') chips.push('<span class="chip">No index needed</span>');
    if (v === 'v2') chips.push(indexChip(a, 'hotels_search'));
    if (v === 'v3') chips.push(indexChip(b, 'hotels_vector'));
    $(`#vstate-${v}`).innerHTML = chips.join('');
  }
  renderConciergeLock();
  if (!$('#setupDrawer').hidden) renderSetup();
  $('#footInfo').textContent = st.database ? `Database ${st.database} on ${st.cluster}` : '';
}

function indexChip(i, name) {
  const s = i.status || 'MISSING';
  const cls = i.queryable ? 'chip--ok' : s === 'MISSING' || s === 'FAILED' || s === 'UNAVAILABLE' ? 'chip--err' : 'chip--todo';
  return `<span class="chip ${cls}">${esc(name)}: ${esc(s === 'MISSING' ? 'not created' : s)}</span>`;
}

// ================================================================ inspector
function logRequest(r) {
  S.requests.unshift({ ...r, at: new Date() });
  S.requests = S.requests.slice(0, 30);
  $('#inspectorCount').textContent = S.requests.length;
  if (!$('#inspector').hidden) renderInspector();
}

function prettyJson(obj) {
  return esc(JSON.stringify(obj, null, 2))
    .replace(/&quot;(\$[a-zA-Z]+)&quot;:/g, '<span class="k">"$1"</span>:')
    .replace(/: &quot;([^&]*)&quot;/g, ': <span class="s">"$1"</span>');
}

function renderInspector() {
  $('#inspectorBody').innerHTML = S.requests.length
    ? S.requests
        .map(
          (r, i) => `<details class="req" ${i === 0 ? 'open' : ''}>
        <summary><span>${esc(r.label)}</span><em>${r.server ?? r.ms} ms</em></summary>
        ${r.debug.map((d) => `<div class="lbl">${esc(d.label)}</div><pre>${prettyJson(d.query)}</pre>`).join('')}
      </details>`
        )
        .join('')
    : '<p class="muted">No queries yet. Search, filter, open a hotel or ask the concierge.</p>';
}

// ================================================================ version switch
function setVersion(v) {
  S.v = v;
  store.set('v', v);
  document.body.dataset.v = v;
  $$('.engine__btn').forEach((b) => b.setAttribute('aria-selected', String(b.dataset.v === v)));
  $$('.vcard').forEach((b) => b.classList.toggle('on', b.dataset.v === v));
  $('#hybridWrap').hidden = v !== 'v3';
  S.page = 0;
  S.facets = null;
  load();
}

// ================================================================ query string
function params(extra = {}) {
  const p = new URLSearchParams();
  p.set('v', S.v);
  if (S.q) p.set('q', S.q);
  if (S.city) p.set('city', S.city);
  if (S.type) p.set('type', S.type);
  if (S.stars) p.set('stars', S.stars);
  if (S.pmin > 0) p.set('pmin', S.pmin);
  if (S.pmax < 1250) p.set('pmax', S.pmax);
  if (S.amenities.length) p.set('amenity', S.amenities.join(','));
  if (S.sort) p.set('sort', S.sort);
  if (S.hybrid && S.v === 'v3') p.set('hybrid', 'true');
  p.set('page', S.page);
  p.set('size', S.size);
  for (const [k, v] of Object.entries(extra)) p.set(k, v);
  return p.toString();
}

// ================================================================ load + render results
let loadSeq = 0;
async function load({ append = false } = {}) {
  if (S.compare) return loadCompare();
  const seq = ++loadSeq;
  $('#compare').hidden = true;
  $('#grid').hidden = false;
  if (!append) $('#grid').style.opacity = '.5';
  try {
    const r = await api(`/api/search?${params()}`, { label: `${S.v} · ${S.q ? `“${S.q}”` : 'browse'}` });
    if (seq !== loadSeq) return;
    S.last = r;
    S.items = append ? [...S.items, ...r.items] : r.items;
    S.total = r.total;
    if (r.facets) S.facets = r.facets;
    else if (!append) S.facets = null;
    renderResults(r);
  } catch (e) {
    if (seq !== loadSeq) return;
    S.items = [];
    S.facets = null;
    renderError(e);
  } finally {
    $('#grid').style.opacity = '';
  }
  renderCats();
  renderFilters();
  renderActiveChips();
}

function resultsTitle() {
  if (S.q) return `“${S.q}”`;
  if (S.city) return `Stays in ${S.city}`;
  return 'All stays';
}

function renderResults(r) {
  $('#resultsTitle').textContent = resultsTitle();
  const v = VERSIONS[S.v];
  const count = S.v === 'v3' && S.q ? `${S.items.length} closest` : `${S.total} ${S.total === 1 ? 'stay' : 'stays'}`;
  $('#resultsMeta').innerHTML = `<b>${count}</b>  ·  ${r.ms} ms  ·  ${S.v} ${esc(v.name)} (${esc(v.tech)})`;
  $('#notices').innerHTML = (r.notes || []).map((n) => `<div class="notice ${/Regex has no relevance/.test(n) ? 'notice--info' : ''}">${esc(n)}</div>`).join('');
  if (!S.items.length) {
    $('#grid').innerHTML = `<div class="empty"><b>No stays match</b>${S.v === 'v1' && S.q ? 'A regex only finds these exact characters. Try v2 or v3 with the same words.' : 'Remove a filter or try other words.'}</div>`;
  } else {
    $('#grid').innerHTML = S.items.map((h, i) => card(h, i)).join('');
  }
  const more = S.v === 'v3' && S.q ? S.items.length >= (S.page + 1) * S.size && S.items.length < 96 : S.items.length < S.total;
  $('#moreBtn').hidden = !more;
}

function renderError(e) {
  $('#moreBtn').hidden = true;
  $('#notices').innerHTML = '';
  $('#resultsMeta').textContent = `${S.v} ${VERSIONS[S.v].name}`;
  $('#resultsTitle').textContent = resultsTitle();
  $('#grid').innerHTML = e instanceof LabTodo ? labCard(e.lab) : `<div class="notice">${esc(e.message)}</div>`;
}

function labCard(lab) {
  return `<div class="lab"><span class="lab__id">${esc(lab.id)}</span>
    <h3>Lab ${esc(lab.id)}: ${esc(lab.title)}</h3>
    <p>Open <code>${esc(lab.where)}</code> and replace the <code>throw Lab.todo("${esc(lab.id)}")</code>.</p>
    <p>${esc(lab.hint)}</p></div>`;
}

// --- cards
function markText(text, q) {
  if (!q) return esc(text);
  const words = fold(q).split(/\s+/).filter((w) => w.length > 1);
  if (!words.length) return esc(text);
  if (S.v === 'v1') {
    // v1 matches the exact characters, anywhere: show that, including inside words
    const f = fold(text);
    const needle = fold(q.trim());
    const i = f.indexOf(needle);
    if (i < 0 || f.length !== text.length) return esc(text);
    return `${esc(text.slice(0, i))}<mark>${esc(text.slice(i, i + needle.length))}</mark>${esc(text.slice(i + needle.length))}`;
  }
  return esc(text);
}

function atlasHighlight(item, path) {
  const hs = (item._highlights || []).filter((x) => x.path === path).sort((a, b) => b.score - a.score);
  if (!hs.length) return null;
  return hs[0].texts.map((t) => (t.type === 'hit' ? `<mark>${esc(t.value)}</mark>` : esc(t.value))).join('');
}

function snippet(h) {
  const hl = atlasHighlight(h, 'description');
  if (hl) return hl;
  const d = h.description || '';
  if (S.v === 'v1' && S.q) {
    const f = fold(d);
    const i = f.indexOf(fold(S.q.trim()));
    if (i >= 0 && f.length === d.length) {
      const start = Math.max(0, i - 60);
      const part = d.slice(start, i + S.q.length + 90);
      return `${start > 0 ? '…' : ''}${markText(part, S.q)}…`;
    }
  }
  return esc(d.split(/(?<=\.)\s/).slice(0, 2).join(' '));
}

function pickAmenities(h) {
  const want = new Set([...S.amenities, ...POPULAR_AMENITIES.slice(0, 11)]);
  const list = (h.amenities || []).filter((a) => want.has(a));
  const q = fold(S.q);
  list.sort((a, b) => (q && fold(b).includes(q) ? 1 : 0) - (q && fold(a).includes(q) ? 1 : 0) || (S.amenities.includes(b) ? 1 : 0) - (S.amenities.includes(a) ? 1 : 0));
  return list.slice(0, 3);
}

function why(h) {
  const out = [];
  if (h._sources?.length) out.push(...h._sources.map((s) => `<span class="src ${s === 'text' ? 'src--text' : ''}">${esc(s)}</span>`));
  if (typeof h._score === 'number') {
    const label = S.v === 'v3' && !S.hybrid ? 'similarity' : S.v === 'v3' ? 'fused' : 'score';
    out.push(`<span class="metric" title="${label}">${label} ${h._score < 1 ? h._score.toFixed(3) : h._score.toFixed(2)}</span>`);
  } else if (S.v === 'v1' && S.q) {
    out.push('<span class="metric" title="A regex match has no score">no score</span>');
  }
  return out.join('');
}

function stars(n) {
  return `<span class="stars" aria-label="${n} stars">${'★'.repeat(n)}</span>`;
}

function card(h, i) {
  const name = atlasHighlight(h, 'name') || markText(h.name, S.q);
  return `<button class="hcard" data-id="${esc(h._id)}" aria-label="${esc(h.name)}">
    <div class="hcard__art">${scene(h)}<span class="hcard__type">${esc(h.type)}</span><span class="rating">${Number(h.rating).toFixed(1)}</span>${S.q ? `<span class="hcard__rank">${i + 1}</span>` : ''}</div>
    <div class="hcard__body">
      <h3>${name}</h3>
      <div class="hcard__where">${esc(h.district)}, ${esc(h.city)}  ${stars(h.stars)}</div>
      <p class="hcard__snip">${snippet(h)}</p>
      <div class="hcard__amen">${pickAmenities(h).map((a) => `<span>${esc(a)}</span>`).join('')}</div>
      <div class="hcard__foot"><span class="price">${eur(h.pricePerNight)} <small>/ night</small></span><span class="why">${why(h)}</span></div>
    </div>
  </button>`;
}

function mini(h) {
  return `<button class="mini" data-id="${esc(h._id)}"><div class="art">${scene(h, { W: 320, H: 160 })}</div><div class="t"><b>${esc(h.name)}</b><small>${esc(h.district)} · ${eur(h.pricePerNight)} · ${Number(h.rating).toFixed(1)}</small></div></button>`;
}

// ================================================================ compare
async function loadCompare() {
  const seq = ++loadSeq;
  $('#grid').hidden = true;
  $('#moreBtn').hidden = true;
  $('#notices').innerHTML = '';
  const box = $('#compare');
  box.hidden = false;
  box.style.opacity = '.5';
  $('#resultsTitle').textContent = resultsTitle();
  $('#resultsMeta').innerHTML = '<b>Same request, three engines</b>';
  try {
    const p = new URLSearchParams(params({ size: 8, page: 0 }));
    p.delete('v');
    const r = await api(`/api/compare?${p}`, { label: `compare · ${S.q ? `“${S.q}”` : 'browse'}` });
    if (seq !== loadSeq) return;
    for (const v of ['v1', 'v2', 'v3']) if (r[v]?.debug) logRequest({ label: `${v} in compare`, ms: r[v].ms, server: r[v].ms, debug: r[v].debug });
    box.innerHTML = ['v1', 'v2', 'v3'].map((v) => compareColumn(v, r[v])).join('');
  } catch (e) {
    box.innerHTML = `<div class="notice">${esc(e.message)}</div>`;
  } finally {
    box.style.opacity = '';
  }
}

function compareColumn(v, r) {
  const head = `<div class="ccol__head"><b>${v}</b><span>${esc(VERSIONS[v].name)}</span><em>${r?.ms != null ? `${r.ms} ms` : ''}</em></div>`;
  if (!r) return `<div class="ccol" data-v="${v}">${head}</div>`;
  if (r.todo) return `<div class="ccol" data-v="${v}">${head}<div class="ccol__body">${labCard(r.todo)}</div></div>`;
  if (r.error) return `<div class="ccol" data-v="${v}">${head}<div class="notice">${esc(r.error)}</div></div>`;
  const notes = (r.notes || []).filter((n) => !/Regex has no relevance/.test(n)).map((n) => `<div class="notice">${esc(n)}</div>`).join('');
  const rows = r.items.length
    ? r.items
        .map(
          (h, i) => `<button class="crow" data-id="${esc(h._id)}"><i>${i + 1}</i><div class="art">${scene(h, { W: 320, H: 200 })}</div>
      <div><b>${esc(h.name)}</b><small>${esc(h.district)}, ${esc(h.city)} · ${eur(h.pricePerNight)}</small></div></button>`
        )
        .join('')
    : `<div class="empty"><b>Nothing found</b>${v === 'v1' ? 'No document contains these exact characters.' : 'No match.'}</div>`;
  return `<div class="ccol" data-v="${v}">${head}${notes}<div class="ccol__body">${rows}</div></div>`;
}

// ================================================================ cities + filters
function renderCats() {
  const cities = S.cfg?.catalog?.cities || [];
  const counts = Object.fromEntries((S.facets?.city || []).map((b) => [b._id, b.count]));
  const has = !!S.facets?.city;
  const all = has ? Object.values(counts).reduce((a, b) => a + b, 0) : null;
  const pill = (val, label, n) =>
    `<button class="cat ${S.city === val ? 'on' : ''} ${has && n === 0 && S.city !== val ? 'zero' : ''}" data-city="${esc(val ?? '')}">${esc(label)}${n != null ? ` <small>${n}</small>` : ''}</button>`;
  const ordered = has ? [...cities].sort((a, b) => (counts[b] || 0) - (counts[a] || 0)) : cities;
  $('#cats').innerHTML = pill(null, 'All of Türkiye', all) + ordered.map((c) => pill(c, c, has ? counts[c] || 0 : null)).join('');
}

const FILTER_NOTES = {
  v1: 'Plain MQL in find(). There are no counts: counting per value needs Atlas Search facets.',
  v2: 'Counts come from $searchMeta facets. Each group is counted without its own selection.',
  v3: 'Filters run inside $vectorSearch as a pre-filter, before the nearest neighbours are picked.',
};

function renderFilters() {
  const f = S.facets;
  const types = S.cfg?.catalog?.types || [];
  const typeCounts = Object.fromEntries((f?.type || []).map((b) => [b._id, b.count]));
  const amenCounts = Object.fromEntries((f?.amenities || []).map((b) => [b._id, b.count]));
  const starCounts = Object.fromEntries((f?.stars || []).map((b) => [b._id, b.count]));
  const active = S.city || S.type || S.stars || S.pmin > 0 || S.pmax < 1250 || S.amenities.length;
  $('#filterDot').hidden = !active;

  const typeOpts = [['', 'Any type', null], ...types.map((t) => [t, t, f ? typeCounts[t] || 0 : null])]
    .filter(([, , n]) => n == null || n > 0 || S.type)
    .map(
      ([val, label, n]) =>
        `<label class="opt ${n === 0 ? 'zero' : ''}"><input type="radio" name="type" value="${esc(val)}" ${(S.type || '') === val ? 'checked' : ''}/><span>${esc(label)}</span>${n != null ? `<small>${n}</small>` : ''}</label>`
    )
    .join('');

  const starsAtLeast = (k) => Object.entries(starCounts).filter(([b]) => Number(b) >= k).reduce((a, [, n]) => a + n, 0);
  const starBtns = [[null, 'Any'], [3, '3+'], [4, '4+'], [5, '5']]
    .map(([k, label]) => `<button type="button" class="${S.stars === k ? 'on' : ''}" data-stars="${k ?? ''}" title="${f && k ? `${starsAtLeast(k)} stays` : ''}">${label}${k ? '★' : ''}</button>`)
    .join('');

  // price histogram from the facet buckets (v2 only)
  const pb = f?.price || [];
  const max = Math.max(1, ...pb.map((b) => b.count));
  const hist = pb.length
    ? `<div class="hist" aria-hidden="true">${pb
        .filter((b) => b._id !== 'more')
        .map((b) => `<i class="${b._id >= S.pmin && b._id < S.pmax ? 'in' : ''}" style="height:${Math.max(4, (b.count / max) * 100)}%" title="from €${b._id}: ${b.count}"></i>`)
        .join('')}</div>`
    : '';

  const amenList = f ? Object.keys(amenCounts).filter((a) => !['Free WiFi', 'Air conditioning', 'Breakfast included', '24h reception'].includes(a)) : POPULAR_AMENITIES;
  const amenities = [...new Set([...S.amenities, ...amenList])].slice(0, 22);

  $('#filters').innerHTML = `
    <div class="f__head"><h3>Filters</h3>${active ? '<button class="f__clear" data-clear="all">Clear all</button>' : ''}</div>
    <div class="f__note">${esc(FILTER_NOTES[S.v])}</div>
    <div class="f__group"><h4>Type of stay</h4>${typeOpts}</div>
    <div class="f__group"><h4>Stars</h4><div class="stars-pick">${starBtns}</div></div>
    <div class="f__group"><h4>Price per night</h4>${hist}
      <div class="range"><div class="range__fill" style="left:${(S.pmin / 1250) * 100}%;right:${100 - (S.pmax / 1250) * 100}%"></div>
        <input type="range" min="0" max="1250" step="10" value="${S.pmin}" data-range="min" aria-label="Minimum price"/>
        <input type="range" min="0" max="1250" step="10" value="${S.pmax}" data-range="max" aria-label="Maximum price"/></div>
      <div class="range__labels"><b id="pminLbl">${eur(S.pmin)}</b><b id="pmaxLbl">${S.pmax >= 1250 ? 'any' : eur(S.pmax)}</b></div></div>
    <div class="f__group"><h4>Amenities</h4><div class="chipset">${amenities
      .map((a) => {
        const n = f ? amenCounts[a] || 0 : null;
        return `<button type="button" class="chipopt ${S.amenities.includes(a) ? 'on' : ''} ${n === 0 ? 'zero' : ''}" data-amenity="${esc(a)}">${esc(a)}${n != null ? `<small>${n}</small>` : ''}</button>`;
      })
      .join('')}</div></div>
    <div class="f__cta"><button class="btn btn--blue" data-close="filters">Show ${S.v === 'v3' && S.q ? 'stays' : `${S.total} stays`}</button></div>`;
}

function renderActiveChips() {
  const chips = [];
  if (S.q) chips.push(['q', `“${S.q}”`]);
  if (S.city) chips.push(['city', S.city]);
  if (S.type) chips.push(['type', S.type]);
  if (S.stars) chips.push(['stars', `${S.stars}★ and up`]);
  if (S.pmin > 0 || S.pmax < 1250) chips.push(['price', `${eur(S.pmin)} to ${S.pmax >= 1250 ? 'any' : eur(S.pmax)}`]);
  for (const a of S.amenities) chips.push([`amenity:${a}`, a]);
  $('#activeChips').innerHTML = chips.map(([k, l]) => `<button data-unset="${esc(k)}" aria-label="Remove ${esc(l)}">${esc(l)} ✕</button>`).join('');
}

function unset(k) {
  if (k === 'q') {
    S.q = '';
    $('#searchInput').value = '';
  } else if (k === 'city') S.city = null;
  else if (k === 'type') S.type = null;
  else if (k === 'stars') S.stars = null;
  else if (k === 'price') {
    S.pmin = 0;
    S.pmax = 1250;
  } else if (k.startsWith('amenity:')) S.amenities = S.amenities.filter((a) => a !== k.slice(8));
  else if (k === 'all') {
    S.city = S.type = S.stars = null;
    S.pmin = 0;
    S.pmax = 1250;
    S.amenities = [];
  }
  S.page = 0;
  load();
}

// ================================================================ autocomplete
let acItems = [];
let acIndex = -1;
const runAc = debounce(async (q) => {
  if (S.v === 'v1' || q.length < 2) return hideAc();
  try {
    const r = await api(`/api/autocomplete?q=${encodeURIComponent(q)}`, { label: `autocomplete “${q}”` });
    // stale, or the user already pressed Enter for this text
    if ($('#searchInput').value.trim() !== q || q === S.q || document.activeElement !== $('#searchInput')) return;
    acItems = r.items || [];
    acIndex = -1;
    if (!acItems.length) return hideAc();
    $('#acPanel').innerHTML =
      acItems
        .map(
          (h, i) => `<div class="ac__item" role="option" data-i="${i}"><div class="art">${scene(h, { W: 320, H: 200 })}</div>
          <div class="t"><div>${esc(h.name)}</div><div>${esc(h.type)} · ${esc(h.district)}, ${esc(h.city)}</div></div><span class="p">${eur(h.pricePerNight)}</span></div>`
        )
        .join('') + `<div class="ac__foot"><span>$search autocomplete on name, ${r.ms} ms</span><span>Enter to search “${esc(q)}”</span></div>`;
    $('#acPanel').hidden = false;
    $('#searchInput').setAttribute('aria-expanded', 'true');
  } catch {
    hideAc(); // Lab 2.5 is a stretch goal: no suggestions until it's written
  }
}, 140);

function hideAc() {
  $('#acPanel').hidden = true;
  $('#searchInput').setAttribute('aria-expanded', 'false');
  acIndex = -1;
}

function moveAc(d) {
  const els = $$('.ac__item');
  if (!els.length) return;
  acIndex = (acIndex + d + els.length) % els.length;
  els.forEach((e, i) => e.classList.toggle('on', i === acIndex));
}

function doSearch(q) {
  S.q = q.trim();
  S.page = 0;
  $('#searchInput').value = S.q;
  hideAc();
  load();
  document.getElementById('results').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

// ================================================================ hotel modal
async function openHotel(id) {
  const dlg = $('#hotelModal');
  dlg.innerHTML = '<div class="hm"><div class="hm__body"><div class="muted">Loading…</div></div></div>';
  if (!dlg.open) dlg.showModal();
  try {
    const { hotel: h, similar, similarLab } = await api(`/api/hotels/${encodeURIComponent(id)}`, { label: 'hotel + similar stays' });
    const sim = similar?.items?.length
      ? `<div class="hm__section"><h4>Similar stays</h4><div class="muted">$vectorSearch with this hotel's own profile as the query</div><div class="hm__row">${similar.items.map(mini).join('')}</div></div>`
      : similarLab
        ? `<div class="hm__section"><h4>Similar stays</h4><div class="muted">Appears when Lab ${esc(similarLab)} is done.</div></div>`
        : `<div class="hm__section"><h4>Similar stays</h4><div class="muted">Appears when the hotels_vector index is READY.</div></div>`;
    dlg.innerHTML = `<div class="hm">
      <div class="hm__art">${scene(h, { W: 640, H: 240 })}<button class="x" data-close="modal" aria-label="Close">×</button></div>
      <div class="hm__body">
        <div>
          <h2>${esc(h.name)}</h2>
          <div class="hm__where">${esc(h.type)} in ${esc(h.district)}, ${esc(h.city)}  ${stars(h.stars)}</div>
          <p class="hm__desc" lang="${esc(h.language || 'en')}">${esc(h.description)}</p>
          <div class="hm__amen">${(h.amenities || []).map((a) => `<span>${esc(a)}</span>`).join('')}</div>
        </div>
        <div class="hm__side">
          <div class="price">${eur(h.pricePerNight)} <small>/ night</small></div>
          <div class="big-rating"><b>${Number(h.rating).toFixed(1)}</b><span>${h.reviewCount} reviews</span></div>
          <ul>${(h.nearby || []).map((n) => `<li>${esc(n)}</li>`).join('')}<li>${h.rooms} rooms</li></ul>
        </div>
      </div>
      <div class="reviews">${(h.reviews || []).map((r) => `<div class="review"><p>“${esc(r.text)}”</p><small>${esc(r.author)}, ${esc(r.country)} · ${r.rating}/10</small></div>`).join('')}</div>
      ${sim}
    </div>`;
  } catch (e) {
    dlg.innerHTML = `<div class="hm"><div class="hm__body"><div class="notice">${esc(e.message)}</div></div></div>`;
  }
}

// ================================================================ concierge
function renderConciergeLock() {
  const missing = [];
  for (const id of ['3.2', '4.1', '4.2']) if (S.status?.labs && !labDone(id)) missing.push(id);
  const vec = idx('hotels_vector');
  const lock = $('#aiLocked');
  if (missing.length) {
    lock.hidden = false;
    lock.innerHTML = `The concierge needs Lab ${missing.map((m) => `<code>${m}</code>`).join(', ')} first. You can still ask: the app will say which exercise is missing.`;
  } else if (S.status?.indexes && !vec.queryable) {
    lock.hidden = false;
    lock.innerHTML = `The <code>hotels_vector</code> index is ${esc(vec.status === 'MISSING' ? 'not created yet' : vec.status)}. Create it in Setup; retrieval returns nothing until it is READY.`;
  } else lock.hidden = true;
  const mem = labDone('4.3');
  $('#memBadge').textContent = mem ? 'Memory on' : 'Memory off (Lab 4.3)';
  $('#memBadge').classList.toggle('on', mem);
  $('#llmBadge').textContent = S.status?.llm ? S.status.llm : 'No LLM key: retrieval only';
  $('#llmBadge').classList.toggle('on', !!S.status?.llm);
}

function formatAnswer(text) {
  return esc(text).replace(/\*\*(.+?)\*\*/g, '<b>$1</b>');
}

function renderChat() {
  $('#chatLog').innerHTML = S.chat
    .map((m) => {
      if (m.role === 'user') return `<div class="msg msg--user">${esc(m.content)}</div>`;
      if (m.typing) return '<div class="msg msg--bot"><span class="typing" aria-label="Thinking"><i></i><i></i><i></i></span></div>';
      if (m.lab) return `<div class="msg msg--bot">${labCard(m.lab)}</div>`;
      return `<div class="msg msg--bot"><div class="answer">${formatAnswer(m.content)}</div>
        ${m.hotels?.length ? `<div class="msg__hotels">${m.hotels.map(mini).join('')}</div>` : ''}
        ${m.trace?.length ? `<div class="trace">${m.trace.map((t) => `<span><b>${esc(t.step)}</b> ${esc(t.detail)}${t.ms ? ` · ${t.ms} ms` : ''}</span>`).join('')}</div>` : ''}</div>`;
    })
    .join('');
  const log = $('#chatLog');
  log.scrollTop = log.scrollHeight;
}

async function ask(message) {
  if (S.chatBusy || !message.trim()) return;
  S.chatBusy = true;
  S.chat.push({ role: 'user', content: message.trim() });
  S.chat.push({ role: 'assistant', typing: true });
  renderChat();
  $('#aiInput').value = '';
  try {
    const r = await api('/api/chat', { method: 'POST', body: { sessionId: S.session, message: message.trim() }, label: `concierge “${message.trim().slice(0, 40)}”` });
    S.chat.pop();
    S.chat.push({ role: 'assistant', content: r.answer, hotels: r.hotels, trace: r.trace });
  } catch (e) {
    S.chat.pop();
    if (e instanceof LabTodo) S.chat.push({ role: 'assistant', lab: e.lab });
    else S.chat.push({ role: 'assistant', content: `Something went wrong: ${e.message}` });
  } finally {
    S.chatBusy = false;
    renderChat();
  }
}

async function loadHistory() {
  try {
    const r = await api(`/api/chat/${S.session}`);
    S.chat = (r.messages || []).map((m) => ({ role: m.role, content: m.content }));
    renderChat();
  } catch {}
}

async function resetChat() {
  try {
    await api(`/api/chat/${S.session}`, { method: 'DELETE' });
  } catch {}
  S.session = newId();
  store.set('session', S.session);
  S.chat = [];
  renderChat();
  toast('New conversation started');
}

const AI_CHIPS = [
  'A romantic hotel on the beach in Kaş or Kalkan',
  'Which of those has the best breakfast?',
  'What did I just ask you?',
  'Kayak sonrası şömine başında dinlenebileceğim bir yer',
];

// ================================================================ ladder
function renderLadder(rows) {
  const rungs = rows || (S.cfg?.ladder || []).map((r) => ({ ...r, results: null }));
  const head = `<div class="lrow lrow--head"><div></div><div>Question</div>${['v1', 'v2', 'v3']
    .map((v) => `<div class="vh" style="color:var(--${v})"><b>${v}</b><span>${esc(VERSIONS[v].name)}</span></div>`)
    .join('')}</div>`;
  const icon = { pass: '✓', partial: '~', fail: '✕', todo: '', error: '!', idle: '' };
  const body = rungs
    .map((r) => {
      const cells = ['v1', 'v2', 'v3']
        .map((v) => {
          const c = r.results?.[v] || { status: 'idle' };
          const label = c.status === 'todo' ? `Lab ${c.lab}` : c.status === 'error' ? 'Error' : c.status === 'idle' ? 'Not run' : `${c.hits}/${c.of}`;
          const top = c.top?.length
            ? `<details><summary>Top ${c.top.length}</summary><ol>${c.top.map((t) => `<li class="${t.ok ? 'ok' : 'no'}">${esc(t.name)}</li>`).join('')}</ol></details>`
            : c.status === 'error'
              ? `<details><summary>Why</summary><div class="muted">${esc(c.error)}</div></details>`
              : '';
          return `<div class="cell cell--${c.status}"><span class="cell__s" title="${esc(label)}">${icon[c.status]} <span class="lbl">${esc(c.status === 'todo' || c.status === 'idle' || c.status === 'error' ? label : `${c.hits}/${c.of}`)}</span></span>${top}</div>`;
        })
        .join('');
      return `<div class="lrow"><div class="n">${r.n}</div><div class="q"><button data-ladder-q="${esc(r.q)}" title="Compare all three on this question">${esc(r.q)}</button><small>${esc(r.lesson)} Expect: ${esc(r.expect)}.</small></div>${cells}</div>`;
    })
    .join('');
  $('#ladder').innerHTML = head + body;
}

async function runLadder() {
  const btn = $('#ladderBtn');
  btn.disabled = true;
  btn.textContent = 'Running 24 searches…';
  try {
    const r = await api('/api/ladder', { label: 'query ladder' });
    renderLadder(r.rungs);
    const sum = ['v1', 'v2', 'v3'].map((v) => `${v}: ${r.rungs.filter((x) => x.results[v].status === 'pass').length}/${r.rungs.length}`);
    toast(`Ladder done. Full passes ${sum.join(', ')}`);
  } catch (e) {
    toast(e.message);
  } finally {
    btn.disabled = false;
    btn.textContent = 'Run the ladder again';
  }
}

// ================================================================ setup drawer
const INDEX_INFO = {
  hotels_search: { kind: 'Atlas Search', lab: 'Lab 2', text: 'Lucene index on names, places, amenities and descriptions, with a diacritic-folding analyzer, autocomplete and facet fields.' },
  hotels_vector: { kind: 'Vector Search · automated embedding', lab: 'Lab 3', text: 'Atlas embeds the profile field with Voyage AI at index time and your query text at query time.' },
};
const openDefs = new Set();
const dropArmed = new Set();

async function renderSetup() {
  const st = S.status || {};
  const labs = st.labs || [];
  const byV = ['v1', 'v2', 'v3'].map((v) => [v, labs.filter((l) => l.version === v)]);
  const idxHtml = await Promise.all(
    Object.entries(INDEX_INFO).map(async ([name, info]) => {
      const i = idx(name);
      let def = '';
      if (openDefs.has(name)) {
        try {
          const d = await (await fetch(`/api/indexes/${name}/definition`)).json();
          def = `<pre>${esc(JSON.stringify(d, null, 2))}</pre>`;
        } catch {}
      }
      return `<div class="idx"><div class="idx__top"><b>${esc(name)}</b>${indexChip(i, info.kind)}</div>
        <p>${esc(info.lab)}. ${esc(info.text)}</p>
        <div class="idx__btns">
          ${!i.exists ? `<button class="btn btn--blue btn--sm" data-index-create="${name}">Create index</button>` : ''}
          <button class="btn btn--ghost btn--sm" data-index-def="${name}">${openDefs.has(name) ? 'Hide' : 'Show'} definition</button>
          ${i.exists ? `<button class="btn btn--ghost btn--sm" data-index-drop="${name}">${dropArmed.has(name) ? 'Click again to drop' : 'Drop'}</button>` : ''}
        </div>${def}</div>`;
    })
  );
  $('#setupBody').innerHTML = `
    <div class="sblock"><h4>Your workspace</h4><div class="kv">
      <span>Attendee</span><b>${esc(st.attendee)}</b>
      <span>Database</span><b>${esc(st.database)}</b>
      <span>Cluster</span><b>${esc(st.cluster)}</b>
      <span>Hotels</span><b>${st.counts?.hotels ?? '…'}</b>
      <span>Chat messages</span><b>${st.counts?.chat_history ?? '…'}</b>
      <span>Embedding</span><b>${esc(st.embedModel)} (automated)</b>
      <span>LLM</span><b>${esc(st.llm || 'not configured')}</b>
    </div></div>
    <div class="sblock"><h4>Search indexes</h4>${idxHtml.join('')}</div>
    <div class="sblock"><h4>Labs</h4>${byV
      .map(
        ([v, ls]) => `<ul class="labs">${ls
          .map(
            (l) => `<li class="${l.done ? 'done' : ''}"><b>${esc(l.id)}</b><div>${esc(l.title)}${l.stretch ? ' <span class="chip">stretch</span>' : ''}<small>${esc(l.where)}</small></div>
          <span class="chip ${l.done ? 'chip--ok' : 'chip--todo'}">${l.done ? 'Done' : 'To do'}</span></li>`
          )
          .join('')}</ul>`
      )
      .join('')}</div>`;
}

function openSetup() {
  $('#setupDrawer').hidden = false;
  $('#scrim').hidden = false;
  refreshStatus().then(renderSetup);
}

function closeDrawers() {
  $('#setupDrawer').hidden = true;
  $('#scrim').hidden = true;
  $('#filters').classList.remove('open');
}

// ================================================================ events
function bind() {
  $('#brandMark').innerHTML = logoMark(30);
  $('#heroArt').innerHTML = heroScene();

  $$('.engine__btn, .vcard').forEach((b) => (b.onclick = () => setVersion(b.dataset.v)));
  document.addEventListener('click', (e) => {
    const t = e.target.closest('[data-id],[data-city],[data-unset],[data-clear],[data-stars],[data-amenity],[data-close],[data-ladder-q],[data-try],[data-ai],[data-index-create],[data-index-def],[data-index-drop],[data-action]');
    if (!t) {
      if (!e.target.closest('#search')) hideAc();
      return;
    }
    if (t.dataset.action === 'home') {
      e.preventDefault();
      window.scrollTo({ top: 0, behavior: 'smooth' });
    } else if (t.dataset.id) openHotel(t.dataset.id);
    else if (t.dataset.city !== undefined) {
      S.city = t.dataset.city || null;
      S.page = 0;
      load();
    } else if (t.dataset.unset) unset(t.dataset.unset);
    else if (t.dataset.clear) unset('all');
    else if (t.dataset.stars !== undefined) {
      S.stars = t.dataset.stars ? Number(t.dataset.stars) : null;
      S.page = 0;
      load();
    } else if (t.dataset.amenity) {
      const a = t.dataset.amenity;
      S.amenities = S.amenities.includes(a) ? S.amenities.filter((x) => x !== a) : [...S.amenities, a];
      S.page = 0;
      load();
    } else if (t.dataset.close === 'modal') $('#hotelModal').close();
    else if (t.dataset.close === 'setup') closeDrawers();
    else if (t.dataset.close === 'filters') closeDrawers();
    else if (t.dataset.close === 'inspector') $('#inspector').hidden = true;
    else if (t.dataset.ladderQ) {
      S.compare = true;
      $('#compareToggle').checked = true;
      doSearch(t.dataset.ladderQ);
    } else if (t.dataset.try) doSearch(t.dataset.try);
    else if (t.dataset.ai) ask(t.dataset.ai);
    else if (t.dataset.indexCreate) {
      t.disabled = true;
      api(`/api/indexes/${t.dataset.indexCreate}`, { method: 'POST', label: `create ${t.dataset.indexCreate}` })
        .then((r) => {
          logRequest({ label: `createSearchIndexes ${t.dataset.indexCreate}`, ms: 0, debug: [{ label: 'command', query: r.command }] });
          toast(r.ok ? `${t.dataset.indexCreate} requested. Building takes about a minute.` : r.error);
        })
        .catch((err) => toast(err.message))
        .finally(() => setTimeout(() => refreshStatus().then(renderSetup), 800));
    } else if (t.dataset.indexDef) {
      const n = t.dataset.indexDef;
      openDefs.has(n) ? openDefs.delete(n) : openDefs.add(n);
      renderSetup();
    } else if (t.dataset.indexDrop) {
      const n = t.dataset.indexDrop;
      if (!dropArmed.has(n)) {
        dropArmed.add(n);
        renderSetup();
        setTimeout(() => {
          dropArmed.delete(n);
          if (!$('#setupDrawer').hidden) renderSetup();
        }, 4000);
        return;
      }
      dropArmed.delete(n);
      api(`/api/indexes/${n}`, { method: 'DELETE' })
        .then((r) => toast(r.ok ? `${n} dropped` : r.error))
        .finally(() => setTimeout(() => refreshStatus().then(renderSetup), 800));
    }
  });

  $('#hotelModal').addEventListener('click', (e) => {
    if (e.target === $('#hotelModal')) $('#hotelModal').close();
  });

  $('#filters').addEventListener('change', (e) => {
    if (e.target.name === 'type') {
      S.type = e.target.value || null;
      S.page = 0;
      load();
    }
  });
  $('#filters').addEventListener('input', (e) => {
    const k = e.target.dataset.range;
    if (!k) return;
    const v = Number(e.target.value);
    if (k === 'min') S.pmin = Math.min(v, S.pmax - 10);
    else S.pmax = Math.max(v, S.pmin + 10);
    $('#pminLbl').textContent = eur(S.pmin);
    $('#pmaxLbl').textContent = S.pmax >= 1250 ? 'any' : eur(S.pmax);
    $('.range__fill').style.left = `${(S.pmin / 1250) * 100}%`;
    $('.range__fill').style.right = `${100 - (S.pmax / 1250) * 100}%`;
    priceChanged();
  });
  const priceChanged = debounce(() => {
    S.page = 0;
    load();
  }, 350);

  const input = $('#searchInput');
  input.addEventListener('input', () => runAc(input.value.trim()));
  input.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      moveAc(1);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      moveAc(-1);
    } else if (e.key === 'Escape') hideAc();
    else if (e.key === 'Enter') {
      e.preventDefault();
      if (acIndex >= 0 && acItems[acIndex]) {
        hideAc();
        openHotel(acItems[acIndex]._id);
      } else doSearch(input.value);
    }
  });
  input.addEventListener('search', () => {
    if (!input.value && S.q) doSearch('');
  });
  $('#acPanel').addEventListener('mousedown', (e) => {
    const it = e.target.closest('.ac__item');
    if (!it) return;
    e.preventDefault();
    hideAc();
    openHotel(acItems[Number(it.dataset.i)]._id);
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === '/' && !/INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName)) {
      e.preventDefault();
      input.focus();
    }
    if (e.key === 'Escape') {
      closeDrawers();
      $('#inspector').hidden = true;
    }
  });

  $('#sortSel').onchange = (e) => {
    S.sort = e.target.value;
    S.page = 0;
    load();
  };
  $('#compareToggle').onchange = (e) => {
    S.compare = e.target.checked;
    load();
  };
  $('#hybridToggle').onchange = (e) => {
    S.hybrid = e.target.checked;
    S.page = 0;
    load();
  };
  $('#moreBtn').onclick = () => {
    S.page++;
    load({ append: true });
  };
  $('#filterBtn').onclick = () => {
    $('#filters').classList.add('open');
    $('#scrim').hidden = false;
  };
  $('#scrim').onclick = closeDrawers;
  $('#setupBtn').onclick = openSetup;
  $('#ladderBtn').onclick = runLadder;
  $('#chatReset').onclick = resetChat;
  $('#aiForm').onsubmit = (e) => {
    e.preventDefault();
    ask($('#aiInput').value);
  };
  $('#aiInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      ask($('#aiInput').value);
    }
  });
  $('#aiChips').innerHTML = AI_CHIPS.map((c) => `<button type="button" data-ai="${esc(c)}">${esc(c)}</button>`).join('');
  $('#inspectorBtn').onclick = () => {
    const i = $('#inspector');
    i.hidden = !i.hidden;
    if (!i.hidden) renderInspector();
  };
}

// ================================================================ init
async function init() {
  bind();
  document.body.dataset.v = S.v;
  $$('.engine__btn').forEach((b) => b.setAttribute('aria-selected', String(b.dataset.v === S.v)));
  $$('.vcard').forEach((b) => b.classList.toggle('on', b.dataset.v === S.v));
  $('#hybridWrap').hidden = S.v !== 'v3';
  await pollStatus();
  try {
    S.cfg = await api('/api/config');
  } catch (e) {
    toast(e.message);
  }
  $('#heroChips').innerHTML = (S.cfg?.ladder || []).map((r) => `<button type="button" data-try="${esc(r.q)}">${esc(r.q)}</button>`).join('');
  renderLadder();
  renderCats();
  load();
  loadHistory();
  setInterval(refreshStatus, 6000);
}

init();
