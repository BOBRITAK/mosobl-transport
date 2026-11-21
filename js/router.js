/* ===========================================================
   Маршруты: граф (станция + линия) и несколько вариантов проезда.

   Один и тот же граф просчитывается с разными «предпочтениями»:
   быстрее всего, меньше пересадок, почти без МЦД, через диаметры.
   Так пользователь сам выбирает, как ехать, а не получает один ответ.
   =========================================================== */

const TRANSFER_MIN = { fast: 4, fewer: 12 };
const TRANSFER_EXTRA = { metro: 0, mcc: 2, mcd: 3, project: 1 };

function speedOf(line) {
  return { metro: 39, mcc: 46, mcd: 55, project: 40 }[line.type] || 39;
}

const PROFILES = [
  {
    id: 'fastest',
    label: 'Быстрее всего',
    hint: 'минимум времени в пути',
    transferMul: 1,
    typeMul: { metro: 1, mcc: 1, mcd: 1, project: 1.05 },
  },
  {
    id: 'fewer',
    label: 'Меньше пересадок',
    hint: 'можно дольше, но без переходов',
    transferMul: 3.2,
    typeMul: { metro: 1, mcc: 1.1, mcd: 1.1, project: 1.2 },
  },
  {
    id: 'metro',
    label: 'Почти без МЦД',
    hint: 'метро и МЦК вместо диаметров',
    transferMul: 1,
    typeMul: { metro: 1, mcc: 1.3, mcd: 2.1, project: 1.15 },
  },
  {
    id: 'diameter',
    label: 'Через МЦД и МЦК',
    hint: 'городские электрички быстрее на длинных плечах',
    transferMul: 1,
    typeMul: { metro: 1.6, mcc: 1, mcd: 1, project: 1.3 },
  },
];

/** Граф поездок по выбранным видам транспорта. */
function buildGraph(network, types) {
  const usable = network.lines.filter((l) => types.has(l.type));
  const nodes = new Map();
  const keyOf = (place, line) => `${place.name}||${line.id}`;

  for (const line of usable) {
    for (const st of line.stations) {
      const place = network.byName.get(st.name);
      if (!place) continue;
      const key = keyOf(place, line);
      if (!nodes.has(key)) nodes.set(key, { key, place, line, edges: [] });
    }
  }

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
      const minutes = Math.max(1, (km / speedOf(line)) * 60 + 0.7);
      nodes.get(ka).edges.push({ to: kb, w: minutes, kind: 'ride', lineType: line.type, lineId: line.id });
      nodes.get(kb).edges.push({ to: ka, w: minutes, kind: 'ride', lineType: line.type, lineId: line.id });
    }
  }

  const byPlace = new Map();
  for (const node of nodes.values()) {
    if (!byPlace.has(node.place.name)) byPlace.set(node.place.name, []);
    byPlace.get(node.place.name).push(node);
  }
  for (const group of byPlace.values()) {
    for (const a of group) {
      for (const b of group) {
        if (a === b) continue;
        a.edges.push({ to: b.key, w: TRANSFER_MIN.fast + (TRANSFER_EXTRA[b.line.type] || 0), kind: 'transfer', lineType: b.line.type });
      }
    }
  }

  return { nodes, usable, byPlace };
}

function dijkstra(graph, fromName, toName, profile) {
  const starts = [...graph.nodes.values()].filter((n) => n.place.name === fromName);
  const ends = new Set([...graph.nodes.values()].filter((n) => n.place.name === toName).map((n) => n.key));
  if (!starts.length || !ends.size) return null;

  const cost = (e) => {
    if (e.kind === 'transfer') return e.w * profile.transferMul;
    const typeMul = profile.typeMul[e.lineType] || 1;
    const lineMul = (profile.lineMul && profile.lineMul[e.lineId]) || 1;
    return e.w * typeMul * lineMul;
  };

  const dist = new Map();
  const prev = new Map();
  const visited = new Set();
  for (const s of starts) dist.set(s.key, 0);

  while (true) {
    let cur = null;
    let best = Infinity;
    for (const [key, d] of dist) {
      if (visited.has(key) || d >= best) continue;
      best = d;
      cur = key;
    }
    if (!cur) break;
    visited.add(cur);
    if (ends.has(cur) && cur !== null) { /* продолжаем: нужен минимум */ }
    const node = graph.nodes.get(cur);
    for (const e of node.edges) {
      const nd = best + cost(e);
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
  if (!bestEnd) return null;

  const path = [];
  let cursor = bestEnd;
  while (cursor !== undefined) {
    path.unshift(graph.nodes.get(cursor));
    const p = prev.get(cursor);
    if (!p) break;
    cursor = p.from;
  }

  // реальное время (профили меняют только выбор, не время)
  const legs = [];
  let current = null;
  let actual = 0;
  let transfers = 0;
  for (let i = 0; i < path.length; i++) {
    const node = path[i];
    if (!current || current.lineId !== node.line.id) {
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
      const edge = graph.nodes.get(prevNode.key).edges.find((e) => e.to === node.key);
      const add = edge && edge.kind === 'ride' ? edge.w : 0;
      current.minutes += add;
      actual += add;
      current.stationNames.push(node.place.name);
      current.points.push([node.place.lat, node.place.lon]);
    }
  }

  const steps = [];
  let transfersTime = 0;
  for (let i = 0; i < legs.length; i++) {
    steps.push({ kind: 'leg', ...legs[i], minutes: Math.max(1, Math.round(legs[i].minutes)) });
    const next = legs[i + 1];
    if (next) {
      const t = TRANSFER_MIN.fast + (TRANSFER_EXTRA[next.type] || 0);
      transfersTime += t;
      steps.push({ kind: 'transfer', name: legs[i].stationNames[legs[i].stationNames.length - 1], minutes: t });
    }
  }

  return {
    ok: true,
    minutes: Math.round(actual + transfersTime),
    transfers,
    legs,
    steps,
    path: path.map((n) => [n.place.lat, n.place.lon]),
    lineIds: [...new Set(legs.map((l) => l.lineId))],
    stationCount: path.length,
  };
}

/**
 * Возвращает несколько вариантов проезда, отсортированных по времени.
 * @returns {Array<{id,label,hint,minutes,transfers,steps,legs,path,lineIds}>}
 */
export function findRoutes(network, fromName, toName, opts = {}) {
  const types = opts.types || new Set(['metro', 'mcc', 'mcd', 'project']);
  if (!network.byName.get(fromName)) return { ok: false, reason: `Станция «${fromName}» не найдена`, variants: [] };
  if (!network.byName.get(toName)) return { ok: false, reason: `Станция «${toName}» не найдена`, variants: [] };
  if (fromName === toName) return { ok: false, reason: 'Начало и конец маршрута совпадают', variants: [] };

  const graph = buildGraph(network, types);
  if (!graph.usable.length) return { ok: false, reason: 'Не выбрано ни одного вида транспорта', variants: [] };

  const seen = new Set();
  const variants = [];
  const signatureOf = (route) => route.legs
    .map((l) => `${l.lineId}:${l.stationNames[0]}→${l.stationNames[l.stationNames.length - 1]}`)
    .join('|');

  const tryAdd = (route, meta) => {
    if (!route) return false;
    const signature = signatureOf(route);
    if (seen.has(signature)) return false;
    seen.add(signature);
    variants.push({ ...route, ...meta });
    return true;
  };

  // 1. основные профили предпочтений
  for (const profile of PROFILES) {
    tryAdd(dijkstra(graph, fromName, toName, profile), { id: profile.id, label: profile.label, hint: profile.hint });
  }

  // 2. обходные пути: штрафуем линии уже найденных вариантов
  for (let round = 0; round < 3 && variants.length < 4; round++) {
    const used = {};
    for (const v of variants) for (const id of v.lineIds) used[id] = 1.75;
    const alt = dijkstra(graph, fromName, toName, {
      transferMul: 1.1,
      typeMul: { metro: 1, mcc: 1, mcd: 1, project: 1.1 },
      lineMul: used,
    });
    if (!tryAdd(alt, { id: 'alt' + round, label: 'Другой путь', hint: 'другая комбинация линий' })) break;
  }

  if (!variants.length) {
    return { ok: false, reason: 'Маршрут не найден: станции не связаны выбранными линиями', variants: [] };
  }
  variants.sort((a, b) => a.minutes - b.minutes);
  return { ok: true, variants };
}
