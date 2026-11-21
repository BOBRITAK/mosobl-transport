/* ===========================================================
   Построение топологической схемы (в духе Яндекс.Метро).

   Модель: у каждой линии — СВОИ копии станций. Внутри линии это
   простая цепочка, поэтому её всегда можно уложить точно под 45/90°.
   Копии одной и той же станции стягиваются пружинами: если они
   сошлись — рисуем одну пересадочную станцию, если разошлись —
   короткий переход между ними, как на настоящих схемах.

   Задача наименьших квадратов:

     min  Σ_рёбра |(p_b − p_a) − L·u|²
        + Σ_связи W·|p_i − p_j|²          (копии одной станции)
        + λ Σ |p − p_гео|²                (общий рисунок)

   Система разрешается плотным Холецким — она маленькая и это
   точное решение, без проблем со сходимостью итераций.

   Запуск:  node tools/build-scheme.mjs
   Результат: data/scheme_layout.json
   =========================================================== */

import fs from 'node:fs';

const BASE = JSON.parse(fs.readFileSync('data/lines.json', 'utf8'));
const PROJECT = JSON.parse(fs.readFileSync('data/project_lines.json', 'utf8'));

const LINES = [
  ...BASE.lines.map((l) => ({ ...l, status: 'active' })),
  ...PROJECT.lines.map((l) => ({ ...l, status: 'project' })),
];

const MIN_LEN = 18;
const MAX_LEN = 34;
const TIE_W = Number(process.env.TIE_W || 1.2);          // жёсткость связи между копиями одной станции
const LAMBDA_WEAK = Number(process.env.LAMBDA_WEAK || 0.002);  // слабое притяжение к географии
const ANCHOR_W = 2.0;       // якорь, чтобы схема не уплывала
const CANVAS = 1500;
const ROUNDS = Number(process.env.ROUNDS || 18);

/* ---------- география ---------- */
function mercator(lat, lon) {
  const x = (lon + 180) / 360;
  const s = Math.sin((lat * Math.PI) / 180);
  const y = 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI);
  return { x, y };
}

function haversineKm(a, b) {
  const R = 6371;
  const dLat = ((b.lat - a.lat) * Math.PI) / 180;
  const dLon = ((b.lon - a.lon) * Math.PI) / 180;
  const la1 = (a.lat * Math.PI) / 180;
  const la2 = (b.lat * Math.PI) / 180;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(la1) * Math.cos(la2) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.asin(Math.min(1, Math.sqrt(h)));
}

/* ---------- копии станций ---------- */
const copies = [];              // {line, name, lat, lon, x, y, gx, gy}
const byName = new Map();       // name -> [copyIndex]
const lineCopies = new Map();   // lineId -> [copyIndex,...]

for (const line of LINES) {
  const list = [];
  for (const st of line.stations) {
    const key = [line.id, st.name];
    const idx = copies.length;
    copies.push({ line: line.id, name: st.name, lat: st.lat, lon: st.lon });
    if (!byName.has(st.name)) byName.set(st.name, []);
    byName.get(st.name).push(idx);
    list.push({ idx, name: st.name });
  }
  lineCopies.set(line.id, list);
}

const N = copies.length;

/* ---------- проекция и стартовые позиции ---------- */
{
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
  for (const c of copies) {
    const p = mercator(c.lat, c.lon);
    c.mx = p.x; c.my = p.y;
    minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
    minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
  }
  const scale = CANVAS / Math.max(maxX - minX, maxY - minY);
  for (const c of copies) {
    c.gx = (c.mx - minX) * scale;
    c.gy = (c.my - minY) * scale;
    c.x = c.gx; c.y = c.gy;
  }
}

/* ---------- рёбра линий и связи копий ---------- */
const lineEdges = [];  // {a, b, km, len}
for (const line of LINES) {
  const list = lineCopies.get(line.id).slice();
  if (line.ring && list.length > 2) list.push(list[0]);
  for (let i = 1; i < list.length; i++) {
    const A = copies[list[i - 1].idx];
    const B = copies[list[i].idx];
    const km = haversineKm(A, B);
    lineEdges.push({
      a: list[i - 1].idx,
      b: list[i].idx,
      km,
      len: Math.min(MAX_LEN, Math.max(MIN_LEN, 18 + (km - 1) * 2.2)),
    });
  }
}

const ties = [];
const tieWeights = [];
for (const [, list] of byName) {
  if (list.length < 2) continue;
  for (let i = 1; i < list.length; i++) {
    ties.push([list[0], list[i]]);
    tieWeights.push(TIE_W);
  }
}

/* ---------- прямой решатель ---------- */
function solve(axis) {
  const A = new Float64Array(N * N);
  const b = new Float64Array(N);

  const addEdge = (i, j, w, tx, ty) => {
    A[i * N + i] += w; A[j * N + j] += w;
    A[i * N + j] -= w; A[j * N + i] -= w;
    const t = axis === 0 ? tx : ty;
    b[i] -= w * t;
    b[j] += w * t;
  };

  for (let k = 0; k < lineEdges.length; k++) {
    const e = lineEdges[k];
    addEdge(e.a, e.b, 1, e.tx, e.ty);
  }
  for (let k = 0; k < ties.length; k++) {
    const [i, j] = ties[k];
    addEdge(i, j, tieWeights[k], 0, 0);
  }

  for (let i = 0; i < N; i++) {
    const lam = LAMBDA_WEAK + (i === ANCHOR_INDEX ? ANCHOR_W : 0);
    A[i * N + i] += lam;
    b[i] += lam * (axis === 0 ? copies[i].gx : copies[i].gy);
  }

  // Холецкий
  for (let i = 0; i < N; i++) {
    for (let j = 0; j <= i; j++) {
      let s = A[i * N + j];
      for (let k = 0; k < j; k++) s -= A[i * N + k] * A[j * N + k];
      if (i === j) A[i * N + i] = Math.sqrt(Math.max(s, 1e-9));
      else A[i * N + j] = s / A[j * N + j];
    }
  }
  const y = new Float64Array(N);
  for (let i = 0; i < N; i++) {
    let s = b[i];
    for (let k = 0; k < i; k++) s -= A[i * N + k] * y[k];
    y[i] = s / A[i * N + i];
  }
  const x = new Float64Array(N);
  for (let i = N - 1; i >= 0; i--) {
    let s = y[i];
    for (let k = i + 1; k < N; k++) s -= A[k * N + i] * x[k];
    x[i] = s / A[i * N + i];
  }
  return x;
}

/* ---------- якорь рядом с центром сети ---------- */
let ANCHOR_INDEX = 0;
{
  const cx = copies.reduce((s, c) => s + c.gx, 0) / N;
  const cy = copies.reduce((s, c) => s + c.gy, 0) / N;
  let best = Infinity;
  for (let i = 0; i < N; i++) {
    const d = Math.hypot(copies[i].gx - cx, copies[i].gy - cy);
    if (d < best) { best = d; ANCHOR_INDEX = i; }
  }
}

/* ---------- итерации: снимок направлений и решение ---------- */
function snap(v) {
  const step = Math.PI / 4;
  return Math.round(Math.atan2(v.y, v.x) / step) * step;
}

function prepare(useGeo) {
  for (const e of lineEdges) {
    const A = copies[e.a], B = copies[e.b];
    const dx = useGeo ? B.gx - A.gx : B.x - A.x;
    const dy = useGeo ? B.gy - A.gy : B.y - A.y;
    const ang = snap({ x: dx, y: dy });
    const cur = Math.hypot(dx, dy);
    const len = Math.min(MAX_LEN, Math.max(MIN_LEN, useGeo ? e.len : cur));
    e.tx = Math.cos(ang) * len;
    e.ty = Math.sin(ang) * len;
    e.ang = ang;
  }
}

function report(label, what) {
  let ok = 0, worst = 0, sum = 0;
  for (const e of lineEdges) {
    const A = copies[e.a], B = copies[e.b];
    const ang = Math.atan2(B.y - A.y, B.x - A.x);
    const off = Math.abs(ang - Math.round(ang / (Math.PI / 4)) * (Math.PI / 4)) * 180 / Math.PI;
    if (off < 1.5) ok++;
    worst = Math.max(worst, off);
    sum += off;
  }
  let maxSpread = 0, sumSpread = 0, nT = 0;
  for (const [, list] of byName) {
    if (list.length < 2) continue;
    let mx = 0;
    for (const i of list) for (const j of list) {
      const d = Math.hypot(copies[i].x - copies[j].x, copies[i].y - copies[j].y);
      mx = Math.max(mx, d);
    }
    maxSpread = Math.max(maxSpread, mx);
    sumSpread += mx; nT++;
  }
  console.log(`  ${label}: углы ${(100 * ok / lineEdges.length).toFixed(1)}% (средн. ${(sum / lineEdges.length).toFixed(1)}°, худшее ${worst.toFixed(1)}°) | копии: средн. ${(sumSpread / nT).toFixed(1)}, макс ${maxSpread.toFixed(1)} — ${what}`);
}

prepare(true);
let px = solve(0);
let py = solve(1);
for (let i = 0; i < N; i++) { copies[i].x = px[i]; copies[i].y = py[i]; }
report('старт', 'направления из географии');

for (let r = 1; r <= ROUNDS; r++) {
  prepare(false);
  px = solve(0);
  py = solve(1);
  for (let i = 0; i < N; i++) { copies[i].x = px[i]; copies[i].y = py[i]; }
  if (r % 2 === 0 || r === ROUNDS) report(`итерация ${r}`, 'направления из решения');
}

separateStations();
report('расталкивание', 'станции разведены');
prepare(false);
px = solve(0);
py = solve(1);
for (let i = 0; i < N; i++) { copies[i].x = px[i]; copies[i].y = py[i]; }
report('финальное решение', 'направления восстановлены');

/* ---------- расталкивание близких станций ----------
   Двигаем станцию целиком (все её копии на одно и то же смещение),
   поэтому направления линий почти не страдают, а точки на схеме
   перестают наезжать друг на друга. */
function separateStations(rounds = 16, minD = 9.5, strength = 0.6) {
  const byNameList = [...byName.entries()];
  for (let round = 0; round < rounds; round++) {
    let moved = 0;
    for (let i = 0; i < byNameList.length; i++) {
      for (let j = i + 1; j < byNameList.length; j++) {
        const [, la] = byNameList[i];
        const [, lb] = byNameList[j];
        const ca = centroid(la);
        const cb = centroid(lb);
        const dx = cb.x - ca.x;
        const dy = cb.y - ca.y;
        const d = Math.hypot(dx, dy);
        if (d > minD || d < 1e-6) continue;
        const push = ((minD - d) / 2) * strength;
        const ux = dx / d;
        const uy = dy / d;
        for (const k of la) { copies[k].x -= ux * push; copies[k].y -= uy * push; }
        for (const k of lb) { copies[k].x += ux * push; copies[k].y += uy * push; }
        moved++;
      }
    }
    if (!moved) break;
  }
}

function centroid(list) {
  let x = 0, y = 0;
  for (const i of list) { x += copies[i].x; y += copies[i].y; }
  return { x: x / list.length, y: y / list.length };
}

/* ---------- нормализация ---------- */
let geoMinX = Infinity, geoMinY = Infinity;
for (const c of copies) {
  geoMinX = Math.min(geoMinX, c.gx);
  geoMinY = Math.min(geoMinY, c.gy);
}
let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
for (const c of copies) {
  minX = Math.min(minX, c.x); maxX = Math.max(maxX, c.x);
  minY = Math.min(minY, c.y); maxY = Math.max(maxY, c.y);
}
const PAD = 45;
for (const c of copies) {
  c.x = +(c.x - minX + PAD).toFixed(1);
  c.y = +(c.y - minY + PAD).toFixed(1);
}
const width = +(maxX - minX + PAD * 2).toFixed(1);
const height = +(maxY - minY + PAD * 2).toFixed(1);

/* ---------- сборка результата ---------- */
const stations = [];
for (const [name, list] of byName) {
  const group = list.map((i) => copies[i]);
  const x = +(group.reduce((s, c) => s + c.x, 0) / group.length).toFixed(1);
  const y = +(group.reduce((s, c) => s + c.y, 0) / group.length).toFixed(1);
  let spread = 0;
  for (const a of group) for (const b of group) {
    spread = Math.max(spread, Math.hypot(a.x - b.x, a.y - b.y));
  }
  const activeLine = group.some((c) => LINES.find((l) => l.id === c.line && l.status === 'active'));
  stations.push({
    name,
    x,
    y,
    spread: +spread.toFixed(1),
    transfer: group.length > 1,
    project: !activeLine,
    lines: [...new Set(group.map((c) => c.line))],
    copies: group.map((c) => [c.line, c.x, c.y]),
  });
}

const lineOut = LINES.map((line) => {
  const list = lineCopies.get(line.id).map((c) => copies[c.idx]);
  const pts = list.map((c) => [c.x, c.y]);
  if (line.ring && pts.length > 2) pts.push(pts[0]);
  return {
    id: line.id,
    type: line.type,
    ref: line.ref || '',
    name: line.name,
    color: line.color,
    ring: !!line.ring,
    status: line.status,
    stations: list.map((c) => c.name),
    points: pts,
  };
});

/* ---------- итоговые метрики ---------- */
let ok = 0, sumDev = 0;
for (const e of lineEdges) {
  const A = copies[e.a], B = copies[e.b];
  const ang = Math.atan2(B.y - A.y, B.x - A.x);
  const off = Math.abs(ang - Math.round(ang / (Math.PI / 4)) * (Math.PI / 4)) * 180 / Math.PI;
  if (off < 1.5) ok++;
  sumDev += off;
}
const spreads = stations.filter((s) => s.transfer).map((s) => s.spread);
// насколько схема ушла от географического рисунка (в долях холста)
const geoShift = copies.reduce((s, c) => s + Math.hypot(c.x - (c.gx - geoMinX + PAD), c.y - (c.gy - geoMinY + PAD)), 0) / N;
const meta = {
  width, height,
  stations: stations.length,
  lines: lineOut.length,
  angleCompliance: +((100 * ok) / lineEdges.length).toFixed(1),
  avgAngleDeviation: +(sumDev / lineEdges.length).toFixed(2),
  transfers: spreads.length,
  avgCopySpread: +(spreads.reduce((s, v) => s + v, 0) / spreads.length).toFixed(2),
  maxCopySpread: +Math.max(...spreads).toFixed(1),
  avgGeoShiftPct: +((100 * geoShift) / Math.max(width, height)).toFixed(1),
};

fs.writeFileSync('data/scheme_layout.json', JSON.stringify({ version: 2, meta, stations, lines: lineOut }));

console.log('схема:', width, 'x', height, '| станций', stations.length, '| линий', lineOut.length);
console.log('итог:', JSON.stringify(meta));
console.log('файл:', Math.round(fs.statSync('data/scheme_layout.json').size / 1024), 'KB');
