/* ===========================================================
   Карта (географический режим) на Leaflet.
   Тайлы подложки подключаются здесь же, с запасным источником —
   именно их отсутствие делало карту чёрной.
   =========================================================== */

import { boundsOf } from './network.js';

const TILE_SOURCES = [
  {
    name: 'CARTO Dark',
    url: 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',
    options: {
      subdomains: 'abcd',
      maxZoom: 20,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>, &copy; <a href="https://carto.com/attributions">CARTO</a>',
    },
  },
  {
    name: 'OpenStreetMap',
    url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
    options: { maxZoom: 19, attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>' },
  },
];

const LABEL_MIN_ZOOM = 9;

export class MapView {
  constructor(el, hooks = {}) {
    this.el = el;
    this.hooks = hooks;
    this.map = L.map(el, {
      center: [55.72, 37.62],
      zoom: 9,
      zoomControl: false,
      attributionControl: true,
      preferCanvas: false,
      worldCopyJump: false,
      zoomSnap: 0.5,
      minZoom: 7,
      maxZoom: 17,
    });

    L.control.zoom({ position: 'bottomright' }).addTo(this.map);

    this.linePane = this.map.createPane('linesPane');
    this.linePane.style.zIndex = 410;
    this.stationPane = this.map.createPane('stationsPane');
    this.stationPane.style.zIndex = 420;
    this.routePane = this.map.createPane('routePane');
    this.routePane.style.zIndex = 430;
    this.routePane.style.pointerEvents = 'none';

    this.groups = { metro: L.layerGroup(), mcc: L.layerGroup(), mcd: L.layerGroup(), project: L.layerGroup() };
    for (const g of Object.values(this.groups)) g.addTo(this.map);
    this.stationGroup = L.layerGroup().addTo(this.map);
    this.routeGroup = L.layerGroup().addTo(this.map);
    this.labelGroup = L.layerGroup().addTo(this.map);

    this.visibleTypes = new Set(['metro', 'mcc', 'mcd', 'project']);
    this.showLabels = true;
    this.placeMarkers = new Map();
    this.lineBars = new Map();
    this.network = null;
    this.route = null;
    this.labelsTimer = null;

    this.initTiles();
    this.map.on('zoomend', () => this.scheduleLabels());
    this.map.on('moveend', () => this.scheduleLabels());
  }

  /* ---------- подложка ---------- */
  initTiles() {
    let index = 0;
    const attach = () => {
      const source = TILE_SOURCES[index];
      if (!source) return;
      const layer = L.tileLayer(source.url, { ...source.options, className: 'base-tiles' });
      let errors = 0;
      layer.on('tileerror', () => {
        errors += 1;
        if (errors === 14 && index + 1 < TILE_SOURCES.length) {
          this.map.removeLayer(layer);
          index += 1;
          this.hooks.onStatus?.(`Источник подложки «${source.name}» недоступен — включён запасной «${TILE_SOURCES[index].name}». Линии и станции работают независимо.`, 'warn');
          attach();
        }
      });
      layer.on('tileload', () => {
        errors = 0;
        if (index > 0 && !this._notifiedFallback) {
          this._notifiedFallback = true;
        }
      });
      layer.addTo(this.map);
      this.tiles = layer;
    };
    attach();
  }

  /* ---------- отрисовка сети ---------- */
  render(network) {
    this.network = network;
    for (const type of Object.keys(this.groups)) {
      this.groups[type].clearLayers();
    }
    this.placeMarkers.clear();
    this.lineBars.clear();

    // линии: тёмная обводка + цветной штрих
    for (const line of network.lines) {
      const coords = line.stations.map((s) => [s.lat, s.lon]);
      if (coords.length < 2) continue;
      const group = this.groups[line.type] || this.groups.metro;
      const isProject = line.type === 'project';

      const casing = L.polyline(coords, {
        pane: 'linesPane',
        color: '#05080d',
        weight: isProject ? 8 : 10,
        opacity: 0.85,
        lineCap: 'round',
        lineJoin: 'round',
        interactive: false,
      });
      const stroke = L.polyline(coords, {
        pane: 'linesPane',
        color: line.color,
        weight: isProject ? 3.4 : 4.6,
        opacity: isProject ? 0.9 : 0.98,
        dashArray: isProject ? '7 7' : null,
        lineCap: 'round',
        lineJoin: 'round',
      });
      stroke.on('click', (e) => { L.DomEvent.stop(e); this.focusLine(line); });
      stroke.bindTooltip(`${line.name}${line.ref ? ' · ' + line.ref : ''}`, { sticky: true, className: 'st-label st-label--major' });
      casing.addTo(group);
      stroke.addTo(group);
      this.lineBars.set(line.id, { stroke, casing, group });
    }

    // станции: одна точка на название станции
    for (const place of network.places) {
      const primary = place.lineRefs[0];
      const isProject = place.type === 'project';
      const radius = place.isTransfer ? 4.6 : 3.7;
      const marker = L.circleMarker([place.lat, place.lon], {
        pane: 'stationsPane',
        radius,
        color: place.isTransfer ? '#f6f8fc' : '#0a0e15',
        weight: place.isTransfer ? 2.2 : 1.6,
        fillColor: place.isTransfer ? '#0f1620' : (primary ? primary.color : '#8b95a7'),
        fillOpacity: 1,
        dashArray: isProject ? '2 2' : null,
      });
      marker.on('click', (e) => {
        L.DomEvent.stop(e);
        this.hooks.onStationClick?.(place);
        this.selectPlace(place);
      });
      marker.addTo(this.stationGroup);
      this.placeMarkers.set(place.name, { marker, place });
    }

    this.applyVisibility();
    this.scheduleLabels();
  }

  /* ---------- видимость слоёв ---------- */
  setVisibleTypes(types) {
    this.visibleTypes = new Set(types);
    this.applyVisibility();
    this.scheduleLabels();
  }

  applyVisibility() {
    for (const type of Object.keys(this.groups)) {
      const group = this.groups[type];
      const on = this.visibleTypes.has(type);
      if (on && !this.map.hasLayer(group)) group.addTo(this.map);
      if (!on && this.map.hasLayer(group)) this.map.removeLayer(group);
    }
    // станция видна, если видна хотя бы одна из её линий
    for (const entry of this.placeMarkers.values()) {
      const visible = entry.place.lineRefs.some((l) => this.visibleTypes.has(l.type));
      const attached = this.stationGroup.hasLayer(entry.marker);
      if (visible && !attached) this.stationGroup.addLayer(entry.marker);
      if (!visible && attached) this.stationGroup.removeLayer(entry.marker);
    }
    this.scheduleLabels();
  }

  setLabels(on) {
    this.showLabels = on;
    this.scheduleLabels(true);
  }

  scheduleLabels(force) {
    if (this.labelsTimer) clearTimeout(this.labelsTimer);
    const run = () => { this.labelsTimer = null; this.drawLabels(); };
    if (force) run();
    else this.labelsTimer = setTimeout(run, 90);
  }

  drawLabels() {
    this.labelGroup.clearLayers();
    if (!this.showLabels || !this.network) return;
    const zoom = this.map.getZoom();
    if (zoom < LABEL_MIN_ZOOM) return;

    // чем мельче масштаб, тем строже отбор подписей — иначе карта «шумит»
    const showAll = zoom >= 12.5;
    const showTerminals = zoom >= 11;
    const bounds = this.map.getBounds().pad(0.08);

    const candidates = [];
    for (const place of this.network.places) {
      // станция видна, если видна хотя бы одна из её линий
      if (!place.lineRefs.some((l) => this.visibleTypes.has(l.type))) continue;
      const rank = place.isTransfer ? 0 : place.isTerminal ? 1 : 2;
      if (rank === 2 && !showAll) continue;
      if (rank === 1 && !showTerminals) continue;
      if (!bounds.contains([place.lat, place.lon])) continue;
      candidates.push({ place, rank });
    }

    // сначала самые важные; подпись ставим, только если она не наезжает на уже поставленные
    candidates.sort((a, b) => a.rank - b.rank || a.place.name.length - b.place.name.length);
    const placed = [];
    const overlaps = (r) => placed.some((p) => !(r.x > p.x + p.w || r.x + r.w < p.x || r.y > p.y + p.h || r.y + r.h < p.y));

    for (const { place, rank } of candidates) {
      const pt = this.map.latLngToContainerPoint([place.lat, place.lon]);
      const width = place.name.length * 6.6 + 14;
      const rect = { x: pt.x + 8, y: pt.y - 9, w: width, h: 18 };
      if (overlaps(rect)) continue;
      placed.push(rect);

      L.tooltip({
        permanent: true,
        direction: 'right',
        offset: [8, 0],
        className: 'st-label' + (rank < 2 ? ' st-label--major' : ''),
        interactive: false,
      })
        .setLatLng([place.lat, place.lon])
        .setContent(place.name)
        .addTo(this.labelGroup);
    }
  }

  /* ---------- выделение и переходы ---------- */
  selectPlace(place) {
    if (this.selected) {
      const prev = this.placeMarkers.get(this.selected.name);
      if (prev) prev.marker.setStyle({ weight: prev.place.isTransfer ? 2.2 : 1.6, color: prev.place.isTransfer ? '#f6f8fc' : '#0a0e15' });
    }
    this.selected = place;
    const entry = this.placeMarkers.get(place.name);
    if (entry) entry.marker.setStyle({ weight: 3, color: '#ff2f4c' });
  }

  flyTo(place, zoom = 13) {
    if (!place) return;
    this.map.flyTo([place.lat, place.lon], Math.max(zoom, this.map.getZoom()), { duration: 0.6 });
    this.selectPlace(place);
  }

  focusLine(line) {
    const coords = line.stations.map((s) => [s.lat, s.lon]);
    if (!coords.length) return;
    this.map.fitBounds(L.latLngBounds(coords).pad(0.12), { maxZoom: 12, animate: true });
  }

  fit(types) {
    const lines = this.network ? this.network.lines.filter((l) => types.has(l.type)) : [];
    const use = lines.length ? lines : (this.network ? this.network.lines : []);
    const b = boundsOf(use);
    if (!b) return;
    this.map.fitBounds([[b.minLat, b.minLon], [b.maxLat, b.maxLon]], { padding: [28, 28], maxZoom: 11 });
  }

  /** Стартовый кадр: на широком экране — ядро сети, на телефоне — центр Москвы. */
  fitInitial(mobile) {
    if (mobile) {
      this.map.setView([55.7512, 37.6400], 10.5, { animate: false });
      return;
    }
    this.fit(new Set(['metro', 'mcc']));
  }

  /* ---------- маршрут ---------- */
  setRoute(route) {
    this.route = route;
    this.routeGroup.clearLayers();
    if (!route || !route.ok) return;

    const path = route.path;
    if (path.length > 1) {
      L.polyline(path, { pane: 'routePane', color: '#05080d', weight: 12, opacity: 0.9, lineCap: 'round', lineJoin: 'round' }).addTo(this.routeGroup);
      L.polyline(path, { pane: 'routePane', color: '#ff2f4c', weight: 5.5, opacity: 1, lineCap: 'round', lineJoin: 'round' }).addTo(this.routeGroup);
    }
    const start = path[0];
    const end = path[path.length - 1];
    L.circleMarker(start, { pane: 'routePane', radius: 7, color: '#0a0e15', weight: 3, fillColor: '#2fbf71', fillOpacity: 1 }).addTo(this.routeGroup);
    L.circleMarker(end, { pane: 'routePane', radius: 7, color: '#0a0e15', weight: 3, fillColor: '#ff2f4c', fillOpacity: 1 }).addTo(this.routeGroup);
    if (path.length > 1) {
      this.map.fitBounds(L.latLngBounds(path).pad(0.15), { maxZoom: 12 });
    }
  }

  invalidateSize() {
    // вызывается при смене режима: Leaflet должен пересчитать размеры контейнера
    requestAnimationFrame(() => this.map.invalidateSize({ animate: false }));
  }
}
