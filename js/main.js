/* ===========================================================
   Точка входа: связывает данные, карту, схему и интерфейс.
   =========================================================== */

import { loadNetwork } from './network.js';
import { MapView } from './mapview.js';
import { SchemeView } from './schemeview.js';
import { findRoutes } from './router.js';

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => [...document.querySelectorAll(sel)];
const isMobile = () => window.matchMedia('(max-width: 900px)').matches;
const PREF_KEY = 'tp.prefs.v3';

const TYPE_LABEL = { metro: 'метро', mcc: 'МЦК', mcd: 'МЦД', project: 'проектируемая линия' };

const state = {
  network: null,
  mode: 'geo',
  types: new Set(['metro', 'mcc', 'mcd', 'project']),
  labels: true,
  route: null,
  variants: [],
  variantIndex: 0,
  selected: null,
  prefs: loadPrefs(),
};

function loadPrefs() {
  try {
    const raw = JSON.parse(localStorage.getItem(PREF_KEY) || '{}');
    return raw && typeof raw === 'object' ? raw : {};
  } catch {
    return {};
  }
}

function savePrefs() {
  try {
    localStorage.setItem(PREF_KEY, JSON.stringify({
      metro: state.types.has('metro'),
      mcc: state.types.has('mcc'),
      mcd: state.types.has('mcd'),
      project: state.types.has('project'),
      labels: state.labels,
      mode: state.mode,
    }));
  } catch { /* приватный режим — просто не сохраняем */ }
}

/* ---------- статус ---------- */
let statusTimer = null;
function showStatus(text, kind = 'error', ttl = 7000) {
  const el = $('#status');
  el.textContent = text;
  el.className = 'status is-visible' + (kind === 'ok' ? ' status--ok' : '');
  if (statusTimer) clearTimeout(statusTimer);
  if (ttl) statusTimer = setTimeout(() => { el.className = 'status'; }, ttl);
}
function hideStatus() {
  const el = $('#status');
  el.className = 'status';
}

/* ---------- карточка станции ---------- */
function showStationCard(place, { focus = false } = {}) {
  if (!place) return;
  state.selected = place;
  $('#cardName').textContent = place.name;
  const lines = place.lineRefs;
  $('#cardLines').replaceChildren(...lines.map((line) => {
    const badge = document.createElement('span');
    badge.className = 'badge-line';
    const dot = document.createElement('i');
    dot.style.background = line.color;
    badge.append(dot, document.createTextNode(`${line.name}${line.ref ? ' · ' + line.ref : ''}`));
    return badge;
  }));
  const meta = [];
  if (place.isTransfer) meta.push(`Пересадка · ${lines.length} ${plural(lines.length, 'линия', 'линии', 'линий')}`);
  if (place.isTerminal) meta.push('Конечная станция линии');
  if (place.hasProject && !place.hasActive) meta.push('Проектируемая станция · не утверждена');
  if (!meta.length) meta.push('Станция на линии');
  $('#cardMeta').textContent = meta.join(' · ');

  renderTransfers(place, lines);
  $('#stationCard').classList.remove('hidden');

  if (focus) {
    if (state.mode === 'geo') mapView.flyTo(place, 13);
  }
}

/* ---------- пересадки в карточке ---------- */

/** Соседние станции в пределах пешей пересадки. */
function nearbyStations(place, maxKm = 0.55) {
  if (!place.lat || !state.network) return [];
  return state.network.places
    .filter((p) => p.name !== place.name)
    .map((p) => ({ place: p, km: state.network.haversineKm(place, p) }))
    .filter((x) => x.km <= maxKm)
    .sort((a, b) => a.km - b.km)
    .slice(0, 3);
}

const BUS_CACHE = new Map();

function renderTransfers(place, lines) {
  const box = $('#cardTransfers');
  box.replaceChildren();

  const title = document.createElement('div');
  title.className = 'transfers__title';
  title.textContent = 'Пересадки';
  box.appendChild(title);

  // 1. другие линии на этой же станции — уже в чипах выше, здесь только переходы
  const nearby = nearbyStations(place);
  if (nearby.length) {
    const row = document.createElement('div');
    row.className = 'transfers__row';
    const label = document.createElement('span');
    label.className = 'transfers__label';
    label.textContent = 'Переход';
    row.appendChild(label);
    for (const item of nearby) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'transfer-chip';
      const dot = document.createElement('i');
      dot.style.background = item.place.lineRefs[0]?.color || '#8b95a7';
      btn.append(dot, document.createTextNode(`${item.place.name} · ${Math.round(item.km * 1000)} м`));
      btn.addEventListener('click', () => selectPlace(item.place));
      row.appendChild(btn);
    }
    box.appendChild(row);
  }

  // 2. наземный транспорт: ссылка есть всегда, данные OSM подставляем,
  //    если сервер ответил достаточно быстро
  if (!place.lat) return;
  const busRow = document.createElement('div');
  busRow.className = 'transfers__row';
  const busLabel = document.createElement('span');
  busLabel.className = 'transfers__label';
  busLabel.textContent = 'Наземный';
  const busValue = document.createElement('span');
  busValue.className = 'transfers__value';
  busValue.textContent = 'автобусы и трамваи · ';
  const link = document.createElement('a');
  link.className = 'transfers__link';
  link.href = `https://www.openstreetmap.org/#map=18/${place.lat}/${place.lon}`;
  link.target = '_blank';
  link.rel = 'noopener';
  link.textContent = 'остановки на OSM';
  busValue.appendChild(link);
  busRow.append(busLabel, busValue);
  box.appendChild(busRow);

  if (BUS_CACHE.has(place.name)) {
    paintBus(busValue, BUS_CACHE.get(place.name));
    return;
  }

  const ttl = 4000;
  Promise.race([
    loadBusStops(place),
    new Promise((_, reject) => setTimeout(() => reject(new Error('timeout')), ttl)),
  ])
    .then((data) => {
      if (state.selected?.name === place.name) paintBus(busValue, data);
    })
    .catch(() => { /* остаётся ссылка — это нормальный сценарий */ });
}

function paintBus(node, data) {
  node.replaceChildren();
  if (!data || !data.count) {
    node.textContent = 'автобусы и трамваи · остановок рядом нет';
    return;
  }
  node.textContent = `${data.count} ${plural(data.count, 'остановка', 'остановки', 'остановок')} в радиусе 300 м`;
  if (data.routes && data.routes.length) {
    const routes = document.createElement('span');
    routes.className = 'transfers__routes';
    routes.textContent = 'маршруты: ' + data.routes.slice(0, 10).join(', ');
    node.appendChild(document.createElement('br'));
    node.appendChild(routes);
  }
}

const OVERPASS_HOSTS = [
  'https://overpass.kumi.systems/api/interpreter',
  'https://overpass-api.de/api/interpreter',
];

async function loadBusStops(place) {
  const cacheKey = `tp.bus.${place.name}`;
  try {
    const cached = localStorage.getItem(cacheKey);
    if (cached) {
      const parsed = JSON.parse(cached);
      if (parsed && typeof parsed.count === 'number') {
        BUS_CACHE.set(place.name, parsed);
        return parsed;
      }
    }
  } catch { /* приватный режим */ }

  const query = `[out:json][timeout:8];(node[highway=bus_stop](around:300,${place.lat},${place.lon});node[railway=tram_stop](around:300,${place.lat},${place.lon}););out tags 80;`;

  for (const host of OVERPASS_HOSTS) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 7000);
    try {
      const res = await fetch(host, {
        method: 'POST',
        body: 'data=' + encodeURIComponent(query),
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        signal: controller.signal,
      });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const json = await res.json();
      const stops = (json.elements || []).filter((e) => e.tags);
      const routes = new Set();
      for (const stop of stops) {
        for (const key of ['route_ref', 'ref']) {
          const value = stop.tags[key];
          if (!value) continue;
          for (const part of String(value).split(';')) {
            const clean = part.trim();
            if (clean) routes.add(clean);
          }
        }
      }
      const result = {
        count: stops.length,
        routes: [...routes].sort((a, b) => a.localeCompare(b, 'ru', { numeric: true })).slice(0, 12),
      };
      BUS_CACHE.set(place.name, result);
      try { localStorage.setItem(cacheKey, JSON.stringify(result)); } catch { /* переполнение — не страшно */ }
      return result;
    } catch {
      /* пробуем следующий сервер */
    } finally {
      clearTimeout(timer);
    }
  }
  throw new Error('все серверы OSM недоступны');
}

function hideStationCard() {
  $('#stationCard').classList.add('hidden');
}

/* ---------- поиск ---------- */
let searchActive = -1;

function renderSearch(query) {
  const list = $('#list');
  const q = query.trim().toLowerCase();
  $('#searchClear').classList.toggle('hidden', !q);
  searchActive = -1;

  if (!q) {
    list.replaceChildren();
    list.setAttribute('aria-expanded', 'false');
    return;
  }

  const found = state.network.places
    .filter((p) => p.searchText.includes(q))
    .sort((a, b) => {
      const ai = a.name.toLowerCase().startsWith(q) ? 0 : 1;
      const bi = b.name.toLowerCase().startsWith(q) ? 0 : 1;
      if (ai !== bi) return ai - bi;
      return a.name.localeCompare(b.name, 'ru');
    })
    .slice(0, 40);

  if (!found.length) {
    const li = document.createElement('li');
    li.className = 'results__empty';
    li.textContent = 'Ничего не найдено. Проверьте название.';
    list.replaceChildren(li);
    return;
  }

  list.replaceChildren(...found.map((place, index) => {
    const li = document.createElement('li');
    li.className = 'results__item';
    li.setAttribute('role', 'option');
    li.dataset.index = String(index);

    const dot = document.createElement('span');
    dot.className = 'line-dot';
    dot.style.background = place.lineRefs[0]?.color || '#8b95a7';

    const nameWrap = document.createElement('span');
    nameWrap.className = 'results__name';
    const b = document.createElement('b');
    b.textContent = place.name;
    const small = document.createElement('small');
    small.textContent = place.lineRefs.map((l) => `${l.name}${l.ref ? ' · ' + l.ref : ''}`).join(', ');
    nameWrap.append(b, small);

    li.append(dot, nameWrap);
    li.addEventListener('click', () => selectPlace(place));
    return li;
  }));
  list.setAttribute('aria-expanded', 'true');
}

function selectPlace(place) {
  showStationCard(place, { focus: true });
  const input = $('#search');
  input.value = place.name;
  $('#list').replaceChildren();
  input.blur();
}

function moveSearchSelection(delta) {
  const items = $$('#list .results__item');
  if (!items.length) return;
  searchActive = (searchActive + delta + items.length) % items.length;
  items.forEach((el, i) => el.classList.toggle('active', i === searchActive));
  items[searchActive].scrollIntoView({ block: 'nearest' });
}

/* ---------- легенда ---------- */
function renderLegend() {
  const box = $('#legend');
  box.replaceChildren();
  const groups = [
    { title: 'Метро', lines: state.network.linesByType.metro },
    { title: 'МЦК', lines: state.network.linesByType.mcc },
    { title: 'МЦД', lines: state.network.linesByType.mcd },
    { title: 'Проектируемые', lines: state.network.linesByType.project },
  ];
  for (const group of groups) {
    if (!group.lines.length) continue;
    const wrap = document.createElement('div');
    wrap.className = 'legend-group';
    const title = document.createElement('div');
    title.className = 'legend-group__title';
    title.textContent = `${group.title} · ${group.lines.length}`;
    const list = document.createElement('div');
    list.className = 'legend-list';

    for (const line of group.lines) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'legend-item' + (line.type === 'project' ? ' legend-item--project' : '');
      btn.title = 'Показать линию на карте';

      const ref = document.createElement('span');
      ref.className = 'legend-item__ref';
      ref.textContent = line.ref || '';

      const bar = document.createElement('span');
      bar.className = 'legend-item__bar';
      if (line.type === 'project') {
        bar.style.color = line.color;
      } else {
        bar.style.background = line.color;
      }

      const name = document.createElement('span');
      name.className = 'legend-item__name';
      name.textContent = line.name;

      btn.append(ref, bar, name);
      btn.addEventListener('click', () => {
        if (state.mode !== 'geo') setMode('geo');
        mapView.focusLine(line);
      });
      list.appendChild(btn);
    }
    wrap.append(title, list);
    box.appendChild(wrap);
  }
}

/* ---------- маршрут ---------- */
/* ---------- маршрут ---------- */
function formatDuration(total) {
  const m = Math.max(1, Math.round(total));
  if (m < 60) return `${m} мин`;
  const h = Math.floor(m / 60);
  const rest = m % 60;
  return rest ? `${h} ч ${rest} мин` : `${h} ч`;
}

function plural(n, one, few, many) {
  const mod10 = n % 10;
  const mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return one;
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return few;
  return many;
}

function lineChip(lineId) {
  const line = state.network.lineById.get(lineId);
  const chip = document.createElement('span');
  chip.className = 'badge-line';
  const dot = document.createElement('i');
  dot.style.background = line ? line.color : '#8b95a7';
  chip.append(dot, document.createTextNode(line ? (line.ref || line.name) : lineId));
  return chip;
}

/** Список из нескольких вариантов проезда + шаги выбранного. */
function renderRouteResult(result) {
  const box = $('#route');
  box.replaceChildren();

  if (!result.ok) {
    const p = document.createElement('div');
    p.className = 'route-empty';
    p.textContent = result.reason || 'Маршрут не построен.';
    box.appendChild(p);
    return;
  }

  const list = document.createElement('div');
  list.className = 'variants';
  list.setAttribute('role', 'radiogroup');
  list.setAttribute('aria-label', 'Варианты проезда');

  result.variants.forEach((v, index) => {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'variant' + (index === state.variantIndex ? ' is-active' : '');
    btn.dataset.index = String(index);
    btn.setAttribute('role', 'radio');
    btn.setAttribute('aria-checked', String(index === state.variantIndex));

    const top = document.createElement('div');
    top.className = 'variant__top';
    const time = document.createElement('span');
    time.className = 'variant__time';
    time.textContent = formatDuration(v.minutes);
    const label = document.createElement('span');
    label.className = 'variant__label';
    label.textContent = v.label;
    top.append(time, label);

    const meta = document.createElement('div');
    meta.className = 'variant__meta';
    meta.textContent = `${v.transfers} ${plural(v.transfers, 'пересадка', 'пересадки', 'пересадок')} · ${v.stationCount} ${plural(v.stationCount, 'станция', 'станции', 'станций')}`;

    const chips = document.createElement('div');
    chips.className = 'variant__chips';
    for (const id of v.lineIds) chips.appendChild(lineChip(id));

    btn.append(top, meta, chips);
    btn.addEventListener('click', () => selectVariant(index));
    list.appendChild(btn);
  });
  box.appendChild(list);

  const steps = document.createElement('div');
  steps.className = 'route-steps';
  steps.id = 'routeSteps';
  box.appendChild(steps);
}

function renderSteps(route) {
  const box = $('#routeSteps');
  if (!box || !route) return;
  box.replaceChildren();

  const summary = document.createElement('div');
  summary.className = 'route-summary';
  const strong = document.createElement('span');
  strong.textContent = formatDuration(route.minutes);
  summary.append(strong, document.createTextNode(
    ` · пересадок: ${route.transfers} · станций: ${route.stationCount}`
  ));
  box.appendChild(summary);

  const legs = document.createElement('div');
  legs.className = 'route-legs';
  for (const step of route.steps) {
    const row = document.createElement('div');
    row.className = 'route-leg';
    const rail = document.createElement('div');
    rail.className = 'route-leg__rail';
    const bar = document.createElement('div');
    bar.className = 'route-leg__bar';

    const body = document.createElement('div');
    const title = document.createElement('div');
    title.className = 'route-leg__title';
    const text = document.createElement('div');
    text.className = 'route-leg__text';

    if (step.kind === 'transfer') {
      bar.style.background = 'rgba(255,255,255,.25)';
      bar.style.minHeight = '14px';
      title.textContent = 'Пересадка';
      text.textContent = `на станции «${step.name}»`;
    } else {
      bar.style.background = step.color;
      title.textContent = `${step.name}${step.ref ? ' · ' + step.ref : ''}`;
      const names = step.stationNames;
      const short = names.length > 4
        ? `${names[0]} → … → ${names[names.length - 1]}`
        : names.join(' → ');
      text.textContent = `${short} · ${formatDuration(step.minutes)} · ${names.length} ${plural(names.length, 'станция', 'станции', 'станций')}`;
    }
    rail.appendChild(bar);
    body.append(title, text);
    row.append(rail, body);
    legs.appendChild(row);
  }
  box.appendChild(legs);
}

/** Выбор варианта: подсветка линий, затемнение остальных, шаги. */
function selectVariant(index) {
  const variant = state.variants[index];
  if (!variant) return;
  state.variantIndex = index;
  state.route = variant;

  for (const btn of $$('.variant')) {
    const active = Number(btn.dataset.index) === index;
    btn.classList.toggle('is-active', active);
    btn.setAttribute('aria-checked', String(active));
  }

  mapView.setRoute(variant);
  schemeView.focusLines(variant.lineIds);
  renderSteps(variant);
}

function buildRoute() {
  if (!state.network) return;
  const from = $('#from').value.trim();
  const to = $('#to').value.trim();
  if (!from || !to) {
    renderRouteResult({ ok: false, reason: 'Укажите станцию отправления и станцию назначения.' });
    return;
  }

  const result = findRoutes(state.network, from, to, { types: state.types });
  state.variants = result.ok ? result.variants : [];
  state.variantIndex = 0;
  renderRouteResult(result);

  const section = $('#routeSection');
  if (section) {
    section.open = true;
    section.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }

  if (result.ok) {
    if (state.mode !== 'geo') setMode('geo');
    selectVariant(0);
    closeSheet();
  } else {
    mapView.setRoute(null);
    schemeView.focusLines(null);
  }
}

/* ---------- режимы ---------- */
function setMode(mode) {
  state.mode = mode;
  savePrefs();
  for (const btn of $$('[data-mode]')) {
    btn.setAttribute('aria-pressed', String(btn.dataset.mode === mode));
  }
  const geo = mode === 'geo';
  $('#map').classList.toggle('hidden', !geo);
  $('#scheme').classList.toggle('hidden', geo);
  if (geo) {
    mapView.invalidateSize();
    if (state.route && state.route.ok) mapView.setRoute(state.route);
  } else {
    schemeView.resize();
    if (!schemeView.fitted) {
      schemeView.fit(isMobile() ? schemeView.coreBox() : null);
      schemeView.fitted = true;
    }
  }
  const schemeCtrl = $('#schemeControls');
  if (schemeCtrl) schemeCtrl.classList.toggle('hidden', geo);
}

/* ---------- слои ---------- */
const SWITCHES = [
  { id: 'ly-metro', type: 'metro' },
  { id: 'ly-mcc', type: 'mcc' },
  { id: 'ly-mcd', type: 'mcd' },
  { id: 'ly-project', type: 'project' },
];
const LABELS_ID = 'ly-labels';

function applyLayers() {
  const types = [...state.types];
  mapView.setVisibleTypes(types);
  schemeView.setVisibleTypes(types);
  savePrefs();
}

function readSwitchesFromState() {
  for (const sw of SWITCHES) {
    const input = document.getElementById(sw.id);
    if (input) input.checked = state.types.has(sw.type);
  }
  const labelsInput = document.getElementById(LABELS_ID);
  if (labelsInput) labelsInput.checked = state.labels;
}

/* ---------- мобильная панель ---------- */
function openSheet() {
  $('#sidebar').classList.add('is-open');
  $('#sheetHandle').setAttribute('aria-expanded', 'true');
}
function closeSheet() {
  $('#sidebar').classList.remove('is-open');
  $('#sheetHandle').setAttribute('aria-expanded', 'false');
}
function toggleSheet() {
  if ($('#sidebar').classList.contains('is-open')) closeSheet();
  else openSheet();
}

/* ---------- инициализация ---------- */
let mapView;
let schemeView;

async function boot() {
  let network;
  try {
    network = await loadNetwork();
  } catch (err) {
    showStatus(`Не удалось загрузить данные сети: ${err.message}. Обновите страницу.`, 'error', 0);
    return;
  }
  state.network = network;

  // предпочтения
  if (state.prefs && typeof state.prefs.metro === 'boolean') {
    state.types = new Set(['metro', 'mcc', 'mcd', 'project'].filter((t) => state.prefs[t] !== false));
  }
  if (typeof state.prefs.labels === 'boolean') state.labels = state.prefs.labels;

  mapView = new MapView($('#map'), {
    onStationClick: (place) => showStationCard(place),
    onStatus: (text, kind) => showStatus(text, kind === 'warn' ? 'error' : 'ok', 9000),
  });
  schemeView = new SchemeView($('#scheme'), $('#schemeSvg'), {
    onStationClick: (place) => showStationCard(place, { focus: false }),
    onLineClick: (line) => {
      if (state.mode !== 'geo') setMode('geo');
      mapView.focusLine(line);
    },
  });

  mapView.render(network);
  try {
    await schemeView.load();
  } catch (err) {
    showStatus(`Не удалось загрузить схему: ${err.message}. Переключитесь в «Географию».`, 'error', 9000);
  }
  readSwitchesFromState();
  applyLayers();
  mapView.setLabels(state.labels);

  // стартовый кадр
  mapView.fitInitial(window.matchMedia('(max-width: 900px)').matches);
  mapView.invalidateSize();

  renderLegend();
  populateDatalist();
  setMode(state.prefs.mode === 'scheme' ? 'scheme' : 'geo');
  hideStatus();

  bindUI();

  // отладочный доступ для проверок в браузере
  window.__tp = { state, mapView, schemeView, network };

  registerServiceWorker();
}

/* Офлайн-режим и сброс старого кэша. Регистрируем после загрузки,
   чтобы не конкурировать с первой отрисовкой карты. */
function registerServiceWorker() {
  if (!('serviceWorker' in navigator)) return;
  const start = () => navigator.serviceWorker.register('sw.js', { scope: './' }).catch(() => {
    /* на file:// или при запрете политики — не критично */
  });
  // модуль мог выполниться уже после события load — тогда регистрируем сразу
  if (document.readyState === 'complete') start();
  else window.addEventListener('load', start, { once: true });
}

function populateDatalist() {
  const dl = $('#stationNames');
  dl.replaceChildren(...state.network.placesSorted.map((place) => {
    const opt = document.createElement('option');
    opt.value = place.name;
    return opt;
  }));
}

function bindUI() {
  // поиск
  const search = $('#search');
  search.addEventListener('input', (e) => renderSearch(e.target.value));
  search.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); moveSearchSelection(1); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); moveSearchSelection(-1); }
    else if (e.key === 'Enter') {
      const items = $$('#list .results__item');
      const target = items[searchActive >= 0 ? searchActive : 0];
      if (target) { e.preventDefault(); target.click(); }
    } else if (e.key === 'Escape') {
      search.value = '';
      renderSearch('');
      search.blur();
    }
  });
  search.addEventListener('focus', () => { if (window.innerWidth <= 900) openSheet(); });
  $('#searchClear').addEventListener('click', () => {
    search.value = '';
    renderSearch('');
    search.focus();
  });

  // режимы
  for (const btn of $$('[data-mode]')) {
    btn.addEventListener('click', () => setMode(btn.dataset.mode));
  }

  // слои
  for (const sw of SWITCHES) {
    const input = document.getElementById(sw.id);
    input?.addEventListener('change', () => {
      if (input.checked) state.types.add(sw.type);
      else state.types.delete(sw.type);
      if (!state.types.size) {
        // нельзя оставить пустую карту
        state.types.add(sw.type);
        input.checked = true;
        showStatus('Оставьте включённым хотя бы один вид транспорта.', 'error', 4000);
      }
      applyLayers();
    });
  }
  document.getElementById(LABELS_ID)?.addEventListener('change', (e) => {
    state.labels = e.target.checked;
    mapView.setLabels(state.labels);
    schemeView.setLabels(state.labels);
    savePrefs();
  });

  // управление схемой
  $('#schemeZoomIn')?.addEventListener('click', () => schemeView.zoomIn());
  $('#schemeZoomOut')?.addEventListener('click', () => schemeView.zoomOut());
  $('#schemeReset')?.addEventListener('click', () => {
    schemeView.fit(isMobile() ? schemeView.coreBox() : null);
  });

  // маршрут
  $('#routeBtn').addEventListener('click', buildRoute);
  $('#swapBtn').addEventListener('click', () => {
    const a = $('#from').value;
    $('#from').value = $('#to').value;
    $('#to').value = a;
    if (state.route) buildRoute();
  });
  for (const id of ['#from', '#to']) {
    $(id).addEventListener('keydown', (e) => { if (e.key === 'Enter') buildRoute(); });
  }

  // карточка станции
  $('#cardClose').addEventListener('click', hideStationCard);
  $('#cardFrom').addEventListener('click', () => {
    if (state.selected) {
      $('#from').value = state.selected.name;
      $('#routeSection').open = true;
      if (window.innerWidth <= 900) openSheet();
    }
  });
  $('#cardTo').addEventListener('click', () => {
    if (state.selected) {
      $('#to').value = state.selected.name;
      $('#routeSection').open = true;
      if (window.innerWidth <= 900) openSheet();
    }
  });

  // мобильная панель
  $('#sheetHandle').addEventListener('click', toggleSheet);
  $('#sheetHandle').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleSheet(); }
  });

  // resize
  window.addEventListener('resize', () => {
    mapView.invalidateSize();
    schemeView.resize();
  });

  // клик по пустой карте — спрятать карточку
  mapView.map.on('click', () => hideStationCard());
}

boot();
