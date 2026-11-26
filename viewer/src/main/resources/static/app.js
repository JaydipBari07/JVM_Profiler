'use strict';
const $ = (id) => document.getElementById(id);
const state = { tab: 'traces', thread: '', samplesThread: '', metric: 'time', sortTraces: 'start', sortHot: 'total', flameRoot: null };

function h(tag, attrs, ...kids) {
  const e = document.createElement(tag);
  if (attrs) for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') e.className = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (v !== false && v != null) e.setAttribute(k, v === true ? '' : v);
  }
  for (const k of kids.flat()) if (k != null && k !== false) e.append(k.nodeType ? k : document.createTextNode(String(k)));
  return e;
}
const ms = (ns) => { const v = ns / 1e6; return v >= 100 ? v.toFixed(0) : v >= 1 ? v.toFixed(2) : v.toFixed(3); };
const bytes = (b) => {
  if (b < 1024) return b + ' B';
  const u = ['KB', 'MB', 'GB', 'TB']; let i = -1;
  do { b /= 1024; i++; } while (b >= 1024 && i < u.length - 1);
  return b.toFixed(b >= 100 ? 0 : 1) + ' ' + u[i];
};
const pct = (x) => (x * 100).toFixed(x >= 0.1 ? 1 : 2) + '%';
const fmtTime = (t) => new Date(t).toLocaleString(undefined, { hour12: false });
const th = (txt, num, onclick, active) => h('th', { class: num ? 'num' : '', onclick, style: onclick ? 'cursor:pointer' : null }, txt + (active ? ' \u25BE' : ''));

function range() {
  const sel = $('range').value;
  if (sel === 'custom') {
    const f = $('from').value, t = $('to').value;
    return { from: f ? new Date(f).getTime() : Date.now() - 3600000, to: t ? new Date(t).getTime() : Date.now() };
  }
  const to = Date.now();
  return { from: to - Number(sel), to };
}
function params(extra) {
  const r = range();
  const p = new URLSearchParams({ from: r.from, to: r.to });
  if ($('kind').value) p.set('kind', $('kind').value);
  if ($('q').value) p.set('q', $('q').value);
  if (Number($('minMs').value) > 0) p.set('minMs', $('minMs').value);
  if ($('err').checked) p.set('err', '1');
  if ($('alerts').checked) p.set('alerts', '1');
  if (state.thread) p.set('thread', state.thread);
  for (const [k, v] of Object.entries(extra || {})) p.set(k, v);
  return p;
}
async function api(path, extra) {
  const res = await fetch('/api/' + path + '?' + params(extra));
  if (!res.ok) throw new Error(res.status + ' ' + (await res.text()));
  return res.json();
}
function setStatus(t) { $('status').textContent = t; }
function empty(msg) { return h('div', { class: 'empty' }, msg); }

/* ---------- generic expandable tree table ---------- */
// node: {name, kids:[], vals:{...}}; cols: [{key,label,fmt,num}]; bar: key used for the bar/percent column
function treeTable(root, cols, barKey, barTotal, barClass) {
  const table = h('table', { class: 'tree' });
  table.append(h('thead', null, h('tr', null, h('th', null, 'Method'), h('th', null, ''), cols.map((c) => h('th', { class: 'num' }, c.label)))));
  const body = h('tbody');
  table.append(body);
  const open = new Set();
  function render() {
    body.replaceChildren();
    (function walk(n, depth) {
      const has = n.kids && n.kids.length;
      const tr = h('tr');
      const caret = h('span', { class: 'caret', onclick: () => { open.has(n) ? open.delete(n) : open.add(n); render(); } }, has ? (open.has(n) ? '\u25BE' : '\u25B8') : '');
      tr.append(h('td', { class: 'name', style: 'padding-left:' + (8 + depth * 16) + 'px' }, caret, n.name));
      const frac = barTotal > 0 ? n.vals[barKey] / barTotal : 0;
      tr.append(h('td', null, h('span', { class: 'bar ' + (barClass || ''), style: 'width:' + Math.max(1, Math.round(Math.min(1, frac) * 120)) + 'px' }), ' ', h('span', { class: 'muted' }, pct(frac))));
      for (const c of cols) tr.append(h('td', { class: 'num' }, c.fmt(n.vals[c.key], n)));
      body.append(tr);
      if (has && open.has(n)) for (const k of n.kids) walk(k, depth + 1);
    })(root, 0);
  }
  function expandAll(on) {
    open.clear();
    if (on) (function walk(n) { if (n.kids && n.kids.length) { open.add(n); n.kids.forEach(walk); } })(root);
    render();
  }
  // default: expand along the heaviest path
  (function heavy(n, d) {
    if (!n.kids || !n.kids.length || d > 12) return;
    open.add(n);
    const top = n.kids.reduce((a, b) => (b.vals[barKey] > a.vals[barKey] ? b : a));
    for (const k of n.kids) if (k === top || k.vals[barKey] >= 0.2 * n.vals[barKey]) heavy(k, d + 1);
  })(root, 0);
  render();
  return { el: table, expandAll };
}

function adaptTrace(n) {
  const kids = (n.k || []).map(adaptTrace).sort((a, b) => b.vals.t - a.vals.t);
  const kt = kids.reduce((s, k) => s + k.vals.t, 0), ka = kids.reduce((s, k) => s + k.vals.a, 0);
  return {
    name: n.n, kids,
    vals: { c: n.c, t: n.t, self: Math.max(0, n.t - kt), a: n.a, selfA: Math.max(0, n.a - ka), avg: n.c ? n.t / n.c : 0, mn: n.mn || 0, mx: n.mx, e: n.e || 0 },
  };
}
const traceCols = [
  { key: 'c', label: 'Calls', fmt: (v) => v.toLocaleString() },
  { key: 't', label: 'Total ms', fmt: (v) => ms(v) },
  { key: 'self', label: 'Self ms', fmt: (v) => ms(v) },
  { key: 'a', label: 'Alloc total', fmt: (v) => bytes(v) },
  { key: 'selfA', label: 'Alloc self', fmt: (v) => bytes(v) },
  { key: 'avg', label: 'Avg ms', fmt: (v) => ms(v) },
  { key: 'mn', label: 'Min ms', fmt: (v) => ms(v) },
  { key: 'mx', label: 'Max ms', fmt: (v) => ms(v) },
  { key: 'e', label: 'Errors', fmt: (v) => (v ? h('span', { class: 'badge err' }, v) : '') },
];

/* ---------- Traces tab ---------- */
async function renderTraces(main) {
  const data = await api('traces', { sort: state.sortTraces, limit: 300 });
  main.append(h('div', { class: 'toolbar' }, h('span', { class: 'muted' }, `${data.total.toLocaleString()} matching, showing ${data.items.length}`),
    h('label', null, 'Sort ', h('select', { onchange: (e) => { state.sortTraces = e.target.value; refresh(); } },
      [['start', 'Newest'], ['dur', 'Slowest'], ['alloc', 'Most allocation'], ['cpu', 'Most CPU']].map(([v, l]) => h('option', { value: v, selected: v === state.sortTraces }, l))))));
  if (!data.items.length) { main.append(empty('No traces in this time range / filter.')); return; }
  const table = h('table');
  table.append(h('thead', null, h('tr', null, ['Time', 'Type', 'Title', 'Thread'].map((x) => h('th', null, x)),
    ['Duration ms', 'CPU ms', 'Wait ms', 'Allocated', 'Nodes'].map((x) => h('th', { class: 'num' }, x)), h('th', null, 'Status'))));
  const tb = h('tbody');
  table.append(tb);
  for (const t of data.items) {
    const tr = h('tr', { class: 'row' });
    tr.append(h('td', null, fmtTime(t.start)), h('td', null, h('span', { class: 'badge ' + t.kind }, t.kind === 'request' ? 'REQ' : 'BG')),
      h('td', { class: 'title', title: t.title }, t.title), h('td', null, t.thread),
      h('td', { class: 'num' }, ms(t.dur)), h('td', { class: 'num' }, t.cpu ? ms(t.cpu) : '-'), h('td', { class: 'num' }, t.cpu ? ms(Math.max(0, t.dur - t.cpu)) : '-'),
      h('td', { class: 'num' }, bytes(t.alloc)), h('td', { class: 'num' }, t.nodes),
      h('td', null, t.kind === 'request' && t.status > 0 ? h('span', { class: 'badge ' + (t.status >= 500 ? 'err' : t.status >= 400 ? 'alert' : 'ok') }, t.status) : '',
        t.err && !(t.status >= 500) ? h('span', { class: 'badge err' }, 'error') : '', t.alert ? h('span', { class: 'badge alert' }, 'alert') : ''));
    let detail = null;
    tr.addEventListener('click', async () => {
      if (detail) { detail.remove(); detail = null; return; }
      detail = h('tr', { class: 'detail' }, h('td', { colspan: 10 }, 'Loading...'));
      tr.after(detail);
      try { detail.firstChild.replaceChildren(await traceDetail(t)); } catch (e) { detail.firstChild.textContent = String(e); }
    });
    tb.append(tr);
  }
  main.append(table);
}

async function traceDetail(t) {
  const full = await (await fetch('/api/trace?id=' + encodeURIComponent(t.id))).json();
  const root = adaptTrace(full.root);
  const metric = h('select', null, h('option', { value: 'time' }, 'Bars: time'), h('option', { value: 'alloc' }, 'Bars: allocation'));
  const wrap = h('div');
  let tt;
  const build = () => {
    tt = metric.value === 'alloc' ? treeTable(root, traceCols, 'a', Math.max(1, root.vals.a), 'alloc') : treeTable(root, traceCols, 't', Math.max(1, root.vals.t));
    holder.replaceChildren(tt.el);
  };
  const holder = h('div');
  metric.addEventListener('change', build);
  const facts = h('div', { class: 'facts' },
    h('span', null, 'Trace: ', h('b', null, full.id)), h('span', null, 'Thread: ', h('b', null, full.thread)), h('span', null, 'Wall: ', h('b', null, ms(full.dur) + ' ms')),
    full.cpu ? h('span', null, 'CPU: ', h('b', null, ms(full.cpu) + ' ms')) : null, h('span', null, 'Allocated: ', h('b', null, bytes(full.alloc))),
    h('span', null, 'Call paths: ', h('b', null, full.nodes)), full.dropped ? h('span', { class: 'hot-high' }, 'Dropped calls (limits): ' + full.dropped) : null,
    full.rid ? h('span', null, 'Request id: ', h('b', null, full.rid)) : null, full.error ? h('span', { class: 'hot-high' }, 'Exception: ' + full.error) : null);
  wrap.append(facts, h('div', { class: 'toolbar' },
    h('button', { onclick: (e) => { e.stopPropagation(); tt.expandAll(true); } }, 'Expand all'),
    h('button', { onclick: (e) => { e.stopPropagation(); tt.expandAll(false); } }, 'Collapse all'), metric,
    h('a', { href: '/api/speedscope?id=' + encodeURIComponent(t.id), onclick: (e) => e.stopPropagation() }, 'Export Speedscope'),
    h('a', { href: '/api/trace?id=' + encodeURIComponent(t.id) + '&dl=1', onclick: (e) => e.stopPropagation() }, 'Export JSON'),
    h('span', { class: 'muted' }, 'Identical call paths are merged: Calls = invocations, times/allocation are exact sums.')), holder);
  build();
  wrap.addEventListener('click', (e) => e.stopPropagation());
  return wrap;
}

/* ---------- Threads tab ---------- */
async function renderThreads(main) {
  const data = await api('threads');
  data.sort((a, b) => b.totalNanos - a.totalNanos || b.samples - a.samples);
  if (!data.length) { main.append(empty('No thread activity in range.')); return; }
  const table = h('table');
  table.append(h('thead', null, h('tr', null, h('th', null, 'Thread (group)'), ['Threads seen', 'Traces', 'Requests', 'Total ms', 'Max ms', 'Allocated', 'Errors', 'Samples'].map((x) => h('th', { class: 'num' }, x)),
    h('th', null, 'Sampled states'), h('th', null, ''))));
  const tb = h('tbody');
  for (const g of data) {
    const st = Object.entries(g.states || {}).map(([k, v]) => `${k.toLowerCase()} ${v}`).join(', ');
    tb.append(h('tr', null, h('td', null, g.group), h('td', { class: 'num' }, g.threads || ''), h('td', { class: 'num' }, g.traces), h('td', { class: 'num' }, g.requests),
      h('td', { class: 'num' }, ms(g.totalNanos)), h('td', { class: 'num' }, ms(g.maxNanos)), h('td', { class: 'num' }, bytes(g.alloc)), h('td', { class: 'num' }, g.errors),
      h('td', { class: 'num' }, g.samples), h('td', { class: 'muted' }, st),
      h('td', null, g.traces ? h('button', { onclick: () => { state.thread = g.group; switchTab('traces'); } }, 'Traces') : '', ' ',
        g.samples ? h('button', { onclick: () => { state.samplesThread = g.group; switchTab('samples'); } }, 'Stacks') : '')));
  }
  table.append(tb);
  main.append(table);
}

/* ---------- Hot methods tab ---------- */
async function renderHot(main) {
  const data = await api('hot', { sort: state.sortHot, limit: 200 });
  if (data.truncated) main.append(h('div', { class: 'muted' }, 'Aggregated over the newest 3000 matching traces.'));
  if (!data.items.length) { main.append(empty('No instrumented method data in range.')); return; }
  const mk = (label, key, num) => th(label, num, () => { state.sortHot = key; refresh(); }, state.sortHot === key);
  const table = h('table');
  table.append(h('thead', null, h('tr', null, h('th', null, 'Method'), mk('Calls', 'calls', true), mk('Total ms', 'total', true), mk('Self ms', 'self', true),
    mk('Alloc total', 'alloc', true), mk('Alloc self', 'selfAlloc', true), h('th', { class: 'num' }, 'Avg ms'), h('th', { class: 'num' }, 'Max ms'), h('th', { class: 'num' }, 'In traces'), h('th', { class: 'num' }, 'Errors'))));
  const tb = h('tbody');
  for (const m of data.items) {
    tb.append(h('tr', null, h('td', { class: 'name' }, m.name), h('td', { class: 'num' }, m.calls.toLocaleString()), h('td', { class: 'num' }, ms(m.total)), h('td', { class: 'num' }, ms(m.self)),
      h('td', { class: 'num' }, bytes(m.alloc)), h('td', { class: 'num' }, bytes(m.selfAlloc)), h('td', { class: 'num' }, ms(m.calls ? m.total / m.calls : 0)), h('td', { class: 'num' }, ms(m.max)),
      h('td', { class: 'num' }, m.traces), h('td', { class: 'num' }, m.errors || '')));
  }
  table.append(tb);
  main.append(table);
}

/* ---------- Flame graph tab ---------- */
function colorFor(name) {
  let x = 0;
  for (let i = 0; i < name.length; i++) x = (x * 31 + name.charCodeAt(i)) | 0;
  return `hsl(${20 + (Math.abs(x) % 40)}, 85%, ${68 + (Math.abs(x >> 8) % 14)}%)`;
}
async function renderFlame(main) {
  const data = await api('flame');
  if (!data.k || !data.k.length) { main.append(empty('No instrumented trace data in range.')); return; }
  const metric = h('select', { onchange: (e) => { state.metric = e.target.value; draw(); } }, h('option', { value: 'time', selected: state.metric === 'time' }, 'Time'), h('option', { value: 'alloc', selected: state.metric === 'alloc' }, 'Allocation'));
  const crumbs = h('span', { class: 'muted' });
  const flame = h('div', { id: 'flame' });
  main.append(h('div', { class: 'toolbar' }, 'Metric ', metric, h('button', { onclick: () => { state.flameRoot = null; draw(); } }, 'Reset zoom'), crumbs, h('span', { class: 'muted' }, 'Click a frame to zoom in.')), flame);
  let root = data;
  function draw() {
    const key = state.metric === 'alloc' ? 'a' : 't';
    const top = state.flameRoot || root;
    const total = Math.max(1, top[key]);
    flame.replaceChildren();
    crumbs.textContent = top.n === 'all' ? '' : ' Zoom: ' + top.n;
    let maxDepth = 0;
    (function walk(n, x, depth) {
      const w = n[key] / total;
      if (w < 0.0015) return;
      maxDepth = Math.max(maxDepth, depth);
      const label = n.n + ' - ' + (key === 't' ? ms(n.t) + ' ms' : bytes(n.a)) + ' (' + pct(w) + ', ' + n.c.toLocaleString() + ' calls)';
      flame.append(h('div', { class: 'f', title: label, style: `left:${x * 100}%;width:${w * 100}%;top:${depth * 19}px;background:${colorFor(n.n)}`, onclick: (e) => { e.stopPropagation(); state.flameRoot = n; draw(); } }, n.n.replace(/^.*\.([^.]+\.[^.]+)$/, '$1')));
      let cx = x;
      for (const k of [...(n.k || [])].sort((a, b) => b[key] - a[key])) { walk(k, cx, depth + 1); cx += k[key] / total; }
    })(top, 0, 0);
    flame.style.height = (maxDepth + 1) * 19 + 4 + 'px';
  }
  draw();
}

/* ---------- Sampled stacks tab ---------- */
async function renderSamples(main) {
  const threads = (await api('threads')).filter((g) => g.samples > 0).sort((a, b) => b.samples - a.samples);
  if (!threads.length) { main.append(empty('No sampled stacks in range (sampling disabled or all threads idle).')); return; }
  if (!threads.find((g) => g.group === state.samplesThread)) state.samplesThread = threads[0].group;
  const sel = h('select', { onchange: (e) => { state.samplesThread = e.target.value; refresh(); } },
    threads.map((g) => h('option', { value: g.group, selected: g.group === state.samplesThread }, `${g.group} (${g.samples} samples)`)));
  main.append(h('div', { class: 'toolbar' }, 'Thread group ', sel, h('span', { class: 'muted' }, 'Stack sampling covers all code, including packages that are not instrumented. Busy (non-idle) stacks only.')));
  const d = await api('samples', { thread: state.samplesThread });
  const total = Math.max(1, d.tree.n);
  main.append(h('div', { class: 'facts' }, h('span', null, 'Busy samples: ', h('b', null, d.tree.n)), h('span', null, 'All samples: ', h('b', null, d.samples)),
    h('span', null, 'States: ', h('b', null, Object.entries(d.states).map(([k, v]) => `${k.toLowerCase()} ${v}`).join(', '))), h('span', null, 'Interval: ', h('b', null, d.intervalMs + ' ms'))));
  const adapt = (n) => ({ name: n.f || d.group, kids: (n.k || []).map(adapt).sort((a, b) => b.vals.n - a.vals.n), vals: { n: n.n, est: n.n * d.intervalMs * 1e6 } });
  const cols = [{ key: 'n', label: 'Samples', fmt: (v) => v.toLocaleString() }, { key: 'est', label: 'Est. ms', fmt: (v) => ms(v) }];
  const tt = treeTable(adapt(d.tree), cols, 'n', total);
  main.append(h('div', { class: 'toolbar' }, h('button', { onclick: () => tt.expandAll(true) }, 'Expand all'), h('button', { onclick: () => tt.expandAll(false) }, 'Collapse all')), tt.el);
}

/* ---------- JVM metrics tab ---------- */
function chart(title, points, series, fmt) {
  const canvas = h('canvas', { class: 'chart' });
  const box = h('div', null, h('div', { class: 'chart-title' }, title, ' ', ...series.map((s) => h('span', { style: 'color:' + s.color + ';margin-left:10px' }, '\u25A0 ' + s.label))), canvas);
  requestAnimationFrame(() => {
    const dpr = window.devicePixelRatio || 1, w = canvas.clientWidth, hh = canvas.clientHeight;
    canvas.width = w * dpr; canvas.height = hh * dpr;
    const c = canvas.getContext('2d'); c.scale(dpr, dpr);
    if (!points.length) { c.fillStyle = '#999'; c.fillText('no data', 10, 20); return; }
    const t0 = points[0].ts, t1 = Math.max(points[points.length - 1].ts, t0 + 1);
    let max = 0; for (const p of points) for (const s of series) max = Math.max(max, s.get(p) || 0);
    max = max || 1;
    const L = 56, R = 8, T = 8, B = 20, X = (t) => L + (t - t0) / (t1 - t0) * (w - L - R), Y = (v) => T + (1 - v / max) * (hh - T - B);
    c.strokeStyle = '#e2e5ea'; c.fillStyle = '#6b7482'; c.font = '11px sans-serif';
    for (let i = 0; i <= 4; i++) { const v = max * i / 4, y = Y(v); c.beginPath(); c.moveTo(L, y); c.lineTo(w - R, y); c.stroke(); c.fillText(fmt(v), 4, y + 3); }
    c.fillText(new Date(t0).toLocaleTimeString(), L, hh - 5); c.textAlign = 'right'; c.fillText(new Date(t1).toLocaleTimeString(), w - R, hh - 5); c.textAlign = 'left';
    for (const s of series) {
      c.strokeStyle = s.color; c.lineWidth = 1.5; c.beginPath();
      points.forEach((p, i) => { const x = X(p.ts), y = Y(s.get(p) || 0); i ? c.lineTo(x, y) : c.moveTo(x, y); });
      c.stroke();
    }
  });
  return box;
}
async function renderJvm(main) {
  const d = await api('metrics');
  const pts = d.points;
  if (!pts.length) { main.append(empty('No JVM metrics in range.')); return; }
  const secs = pts.length > 1 ? (pts[1].ts - pts[0].ts) / 1000 : 10;
  main.append(
    chart('Heap', pts, [{ label: 'used', color: '#2f6fed', get: (p) => p.heapUsed }, { label: 'committed', color: '#9aa7bd', get: (p) => p.heapCommitted }, { label: 'non-heap', color: '#7a5af8', get: (p) => p.nonHeap }], bytes),
    chart('GC time per interval (ms)', pts, [{ label: 'gc ms', color: '#d6343f', get: (p) => p.gcMs }, { label: 'gc count', color: '#d98a00', get: (p) => p.gcCount }], (v) => v.toFixed(0)),
    chart('CPU', pts, [{ label: 'process', color: '#1f9d55', get: (p) => Math.max(0, p.procCpu) * 100 }, { label: 'system', color: '#9aa7bd', get: (p) => Math.max(0, p.sysCpu) * 100 }], (v) => v.toFixed(0) + '%'),
    chart('Threads', pts, [{ label: 'live threads', color: '#2f6fed', get: (p) => p.threads }], (v) => v.toFixed(0)),
    chart('Profiler self-overhead (estimated % of CPU capacity) and drops', pts, [{ label: 'overhead %', color: '#d98a00', get: (p) => p.overheadPct }, { label: 'dropped traces', color: '#d6343f', get: (p) => p.dropped }], (v) => v.toFixed(1)));
  if (d.events.length) main.append(h('div', { class: 'chart-title' }, 'Profiler events'), h('table', null, h('tbody', null, d.events.map((e) => h('tr', null, h('td', null, fmtTime(e.ts)), h('td', null, e.msg))))));
}

/* ---------- plumbing ---------- */
const renderers = { traces: renderTraces, threads: renderThreads, hot: renderHot, flame: renderFlame, samples: renderSamples, jvm: renderJvm };
let seq = 0;
async function refresh() {
  const my = ++seq, main = $('main');
  const chip = $('thread-chip');
  chip.hidden = !state.thread;
  chip.textContent = state.thread ? 'thread: ' + state.thread + '  \u2715' : '';
  const t0 = performance.now();
  const frag = h('div');
  try {
    await renderers[state.tab](frag);
    if (my !== seq) return;
    main.replaceChildren(frag);
    setStatus('updated ' + new Date().toLocaleTimeString() + ' (' + Math.round(performance.now() - t0) + ' ms)');
  } catch (e) {
    if (my === seq) { main.replaceChildren(h('div', { class: 'empty' }, 'Error: ' + e.message)); setStatus(''); }
  }
}
function switchTab(tab) {
  state.tab = tab;
  document.querySelectorAll('#tabs button').forEach((b) => b.classList.toggle('active', b.dataset.tab === tab));
  refresh();
}
document.querySelectorAll('#tabs button').forEach((b) => b.addEventListener('click', () => switchTab(b.dataset.tab)));
$('range').addEventListener('change', () => { $('custom').hidden = $('range').value !== 'custom'; if ($('range').value !== 'custom') refresh(); });
$('apply').addEventListener('click', refresh);
$('q').addEventListener('keydown', (e) => { if (e.key === 'Enter') refresh(); });
$('thread-chip').addEventListener('click', () => { state.thread = ''; refresh(); });
for (const id of ['kind', 'err', 'alerts']) $(id).addEventListener('change', refresh);
setInterval(() => { if ($('auto').checked && $('range').value !== 'custom' && !document.querySelector('tr.detail')) refresh(); }, 10000);
refresh();
