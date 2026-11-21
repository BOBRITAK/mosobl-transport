/* ===========================================================
   Точка входа: связывает данные, карту, схему и интерфейс.
   =========================================================== */

import { loadNetwork } from './network.js';
import { MapView } from './mapview.js';
import { SchemeView } from './schemeview.js';
import { findRoute } from './router.js';

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
  if (place.isTransfer) meta.push(`Пересадка · ${lines.length} линии`);
  if (place.isTerminal) meta.push('Конечная станция линии');
  if (place.hasProject && !place.hasActive) meta.push(`Проектируемая станция · не утверждена`);
  if (!meta.length) meta.push('Станция на линии');
  $('#cardMeta').textContent = meta.join(' · ');

  $('#stationCard').classList.remove('hidden');

  if (focus) {
    if (state.mode === 'geo') mapView.flyTo(place, 13);
    else schemeView.focusPlace?.(place);
  }
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
function renderRoute(result) {
  const box = $('#route');
  box.replaceChildren();

  if (!result.ok) {
    const p = document.createElement('div');
    p.className = 'route-empty';
    p.textContent = result.reason || 'Маршрут не построен.';
    box.appendChild(p);
    return;
  }

  const summary = document.createElement('div');
  summary.className = 'route-summary';
  const minutes = document.createElement('span');
  minutes.textContent = `${result.minutes} мин`;
  summary.append(minutes, document.createTextNode(
    ` · пересадок: ${result.transfers} · станций: ${result.path.length}`
  ));
  box.appendChild(summary);

  const legs = document.createElement('div');
  legs.className = 'route-legs';
  for (const step of result.steps) {
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
      text.textContent = `${short} · ${step.minutes} мин · ${names.length} ${plural(names.length, 'станция', 'станции', 'станций')}`;
    }
    rail.appendChild(bar);
    body.append(title, text);
    row.append(rail, body);
    legs.appendChild(row);
  }
  box.appendChild(legs);
}

function plural(n, one, few, many) {
  const mod10 = n % 10;
  const mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return one;
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return few;
  return many;
}

function buildRoute() {
  if (!state.network) return;
  const from = $('#from').value.trim();
  const to = $('#to').value.trim();
  if (!from || !to) {
    renderRoute({ ok: false, reason: 'Укажите станцию отправления и станцию назначения.' });
    return;
  }
  const result = findRoute(state.network, from, to, { types: state.types, pref: 'fast' });
  state.route = result;
  renderRoute(result);

  if (result.ok) {
    if (state.mode !== 'geo') setMode('geo');
    mapView.setRoute(result);
    closeSheet();
  } else {
    mapView.setRoute(null);
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
