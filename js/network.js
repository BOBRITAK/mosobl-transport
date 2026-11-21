/* ===========================================================
   Модель сети: загрузка данных и нормализация.
   Ключевой принцип: никаких глобальных переменных с именем NETWORK.
   Данные приходят через fetch и живут только внутри модуля.
   =========================================================== */

const SOURCES = {
  lines: 'data/lines.json',
  project: 'data/project_lines.json',
};

const SPEED_KMH = { metro: 39, mcc: 46, mcd: 55, project: 40 };
const DWELL_MIN = 0.7;

async function fetchJson(url) {
  const res = await fetch(url, { cache: 'no-cache' });
  if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`);
  const data = await res.json();
  if (!data || typeof data !== 'object') throw new Error(`${url}: пустой ответ`);
  return data;
}

function haversineKm(a, b) {
  const R = 6371;
  const dLat = (b.lat - a.lat) * Math.PI / 180;
  const dLon = (b.lon - a.lon) * Math.PI / 180;
  const la1 = a.lat * Math.PI / 180;
  const la2 = b.lat * Math.PI / 180;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(la1) * Math.cos(la2) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** Приводит сырые линии к единому виду. */
function normalizeLines(rawLines, status) {
  const out = [];
  for (const raw of rawLines) {
    if (!raw || !Array.isArray(raw.stations) || raw.stations.length < 1) continue;
    const stations = raw.stations
      .filter((s) => s && typeof s.lat === 'number' && typeof s.lon === 'number' && s.name)
      .map((s, idx) => ({ name: String(s.name).trim(), lat: s.lat, lon: s.lon, idx }));
    if (!stations.length) continue;
    out.push({
      id: raw.id,
      type: raw.type,
      ref: raw.ref || '',
      name: raw.name || '',
      color: raw.color || '#8b95a7',
      status,
      note: raw.note || '',
      stations,
    });
  }
  return out;
}

/** Точки (place) — станция как объект города, общий для всех линий с тем же названием. */
function buildPlaces(lines) {
  const byName = new Map();
  for (const line of lines) {
    for (const st of line.stations) {
      let place = byName.get(st.name);
      if (!place) {
        place = { name: st.name, lat: 0, lon: 0, lines: [], types: new Set(), _n: 0 };
        byName.set(st.name, place);
      }
      place.lat += st.lat;
      place.lon += st.lon;
      place._n += 1;
      if (!place.lines.includes(line.id)) place.lines.push(line.id);
      place.types.add(line.type);
    }
  }
  for (const place of byName.values()) {
    place.lat /= place._n;
    place.lon /= place._n;
    place.lines.sort();
    place.isTransfer = place.lines.length > 1;
    // основной вид: приоритет у метро, затем МЦК, МЦД, проект
    const order = ['metro', 'mcc', 'mcd', 'project'];
    place.type = order.find((t) => place.types.has(t)) || 'metro';
    delete place._n;
  }
  return byName;
}

export async function loadNetwork() {
  const [base, project] = await Promise.all([
    fetchJson(SOURCES.lines),
    fetchJson(SOURCES.project).catch(() => ({ lines: [] })),
  ]);

  const lines = [
    ...normalizeLines(base.lines || [], 'active'),
    ...normalizeLines(project.lines || [], 'project'),
  ];
  if (!lines.length) throw new Error('В данных нет ни одной линии');

  const byName = buildPlaces(lines);
  const lineById = new Map(lines.map((l) => [l.id, l]));

  // конечные станции линий — их подписываем всегда
  const terminals = new Set();
  for (const line of lines) {
    if (!line.stations.length) continue;
    terminals.add(line.stations[0].name);
    terminals.add(line.stations[line.stations.length - 1].name);
  }

  // производные поля
  for (const place of byName.values()) {
    place.lineRefs = place.lines.map((id) => lineById.get(id)).filter(Boolean);
    place.hasProject = place.types.has('project');
    place.hasActive = place.types.has('metro') || place.types.has('mcc') || place.types.has('mcd');
    place.isTerminal = terminals.has(place.name);
  }

  // индекс для поиска
  const places = [...byName.values()];
  for (const p of places) {
    const refs = p.lineRefs.map((l) => `${l.name}${l.ref ? ' ' + l.ref : ''}`).join(' ');
    p.searchText = `${p.name} ${refs}`.toLowerCase();
  }
  const sorted = places.slice().sort((a, b) => a.name.localeCompare(b.name, 'ru'));

  const linesByType = {
    metro: lines.filter((l) => l.type === 'metro'),
    mcc: lines.filter((l) => l.type === 'mcc'),
    mcd: lines.filter((l) => l.type === 'mcd'),
    project: lines.filter((l) => l.type === 'project'),
  };

  return { lines, linesByType, lineById, places, placesSorted: sorted, byName, haversineKm, SPEED_KMH, DWELL_MIN };
}

/** Границы всех станций выбранных линий. */
export function boundsOf(lines) {
  let minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
  for (const line of lines) {
    for (const s of line.stations) {
      if (s.lat < minLat) minLat = s.lat;
      if (s.lat > maxLat) maxLat = s.lat;
      if (s.lon < minLon) minLon = s.lon;
      if (s.lon > maxLon) maxLon = s.lon;
    }
  }
  if (minLat > maxLat) return null;
  return { minLat, maxLat, minLon, maxLon };
}
