/* ===========================================================
   Построение маршрута: граф (станция, линия) + Дейкстра.
   Пересадка возможна только внутри одной станции (одно название),
   поэтому маршрут не «перепрыгивает» между далёкими точками.
   =========================================================== */

const TRANSFER_MIN = { fast: 4, fewer: 12 };
// ожидание на пересадке: метро ходит чаще, чем МЦД и МЦК
const TRANSFER_EXTRA = { metro: 0, mcc: 2, mcd: 3, project: 1 };

function speedOf(line) {
  return { metro: 39, mcc: 46, mcd: 55, project: 40 }[line.type] || 39;
}

/**
 * @param {object} network результат loadNetwork()
 * @param {object} opts {types:Set, project:boolean, pref:'fast'|'fewer'}
 */
export function findRoute(network, fromName, toName, opts = {}) {
  const pref = opts.pref === 'fewer' ? 'fewer' : 'fast';
  const types = opts.types || new Set(['metro', 'mcc', 'mcd', 'project']);
  const transferMin = TRANSFER_MIN[pref];
  const speedBonus = pref === 'fewer' ? 0.82 : 1; // «меньше пересадок» слегка сглаживает выбор линии

  const usable = network.lines.filter((l) => types.has(l.type));
  if (!usable.length) return { ok: false, reason: 'Не выбрано ни одного вида транспорта' };

  const from = network.byName.get(fromName);
  const to = network.byName.get(toName);
  if (!from) return { ok: false, reason: `Станция «${fromName}» не найдена` };
  if (!to) return { ok: false, reason: `Станция «${toName}» не найдена` };
  if (from.name === to.name) return { ok: false, reason: 'Начало и конец маршрута совпадают' };

  // ---- узлы
  const nodes = new Map(); // key -> {key, place, line, edges:[]}
  const keyOf = (place, line) => `${place.name}||${line.id}`;
  for (const line of usable) {
    for (const st of line.stations) {
      const place = network.byName.get(st.name);
      if (!place) continue;
      const key = keyOf(place, line);
      if (!nodes.has(key)) nodes.set(key, { key, place, line, edges: [] });
    }
  }

  // ---- рёбра вдоль линий
  for (const line of usable) {
    const seq = [];
    for (const st of line.stations) {
      const place = network.byName.get(st.name);
      if (place) seq.push(place);
    }
    for (let i = 1; i < seq.length; i++) {
      const a = seq[i - 1];
      const b = seq[i];
      if (a.name === b.name) continue;
      const ka = keyOf(a, line);
      const kb = keyOf(b, line);
      if (!nodes.has(ka) || !nodes.has(kb)) continue;
      const km = network.haversineKm(a, b);
      const minutes = Math.max(1, (km / speedOf(line)) * 60 * speedBonus + 0.7);
      nodes.get(ka).edges.push({ to: kb, w: minutes, kind: 'ride' });
      nodes.get(kb).edges.push({ to: ka, w: minutes, kind: 'ride' });
    }
  }

  // ---- пересадки внутри станции
  const byPlace = new Map();
  for (const node of nodes.values()) {
    if (!byPlace.has(node.place.name)) byPlace.set(node.place.name, []);
    byPlace.get(node.place.name).push(node);
  }
  for (const group of byPlace.values()) {
    for (const a of group) {
      for (const b of group) {
        if (a === b) continue;
        a.edges.push({ to: b.key, w: transferMin + (TRANSFER_EXTRA[b.line.type] || 0), kind: 'transfer' });
      }
    }
  }

  // ---- старт и финиш
  const starts = [...nodes.values()].filter((n) => n.place.name === from.name);
  const ends = new Set([...nodes.values()].filter((n) => n.place.name === to.name).map((n) => n.key));
  if (!starts.length) return { ok: false, reason: `«${fromName}» не входит в выбранные виды транспорта` };
  if (!ends.size) return { ok: false, reason: `«${toName}» не входит в выбранные виды транспорта` };

  // ---- Дейкстра
  const dist = new Map();
  const prev = new Map();
  const visited = new Set();
  for (const s of starts) dist.set(s.key, 0);

  while (true) {
    let cur = null;
    let best = Infinity;
    for (const [key, d] of dist) {
      if (visited.has(key)) continue;
      if (d < best) { best = d; cur = key; }
    }
    if (!cur) break;
    visited.add(cur);
    if (ends.has(cur)) break;
    const node = nodes.get(cur);
    for (const e of node.edges) {
      const nd = best + e.w;
      if (nd < (dist.get(e.to) ?? Infinity)) {
        dist.set(e.to, nd);
        prev.set(e.to, { from: cur, edge: e });
      }
    }
  }

  let bestEnd = null;
  let bestTime = Infinity;
  for (const key of ends) {
    const d = dist.get(key);
    if (d !== undefined && d < bestTime) { bestTime = d; bestEnd = key; }
  }
  if (!bestEnd) {
    return { ok: false, reason: 'Маршрут не найден: станции не связаны выбранными линиями' };
  }

  // ---- восстановление пути
  const path = [];
  let cursor = bestEnd;
  while (cursor !== undefined) {
    const node = nodes.get(cursor);
    path.unshift(node);
    const p = prev.get(cursor);
    if (!p) break;
    cursor = p.from;
  }

  // ---- группировка в участки по линиям
  const legs = [];
  let current = null;
  let transfers = 0;
  for (let i = 0; i < path.length; i++) {
    const node = path[i];
    if (!current || current.line.id !== node.line.id) {
      if (current) transfers += 1;
      current = {
        lineId: node.line.id,
        line: node.line,
        type: node.line.type,
        ref: node.line.ref,
        name: node.line.name,
        color: node.line.color,
        stationNames: [node.place.name],
        minutes: 0,
        points: [[node.place.lat, node.place.lon]],
      };
      legs.push(current);
    } else {
      const prevNode = path[i - 1];
      const edge = nodes.get(prevNode.key).edges.find((e) => e.to === node.key);
      current.minutes += edge && edge.kind === 'ride' ? edge.w : 0;
      current.stationNames.push(node.place.name);
      current.points.push([node.place.lat, node.place.lon]);
    }
  }

  // пересадки как отдельные элементы для наглядности
  const steps = [];
  for (let i = 0; i < legs.length; i++) {
    steps.push({ kind: 'leg', ...legs[i], minutes: Math.max(1, Math.round(legs[i].minutes)) });
    const next = legs[i + 1];
    if (next) {
      steps.push({
        kind: 'transfer',
        name: legs[i].stationNames[legs[i].stationNames.length - 1],
        minutes: transferMin,
      });
    }
  }

  return {
    ok: true,
    minutes: Math.round(bestTime),
    transfers,
    legs,
    steps,
    path: path.map((n) => [n.place.lat, n.place.lon]),
  };
}
