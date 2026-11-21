/* ===========================================================
   Режим «Схема»: SVG-полотно, построенное из реальных координат
   (проекция Меркатора), с панорамированием и масштабированием.
   Не зависит от заранее посчитанных координат, поэтому не может
   «разъехаться» с данными.
   =========================================================== */

const NS = 'http://www.w3.org/2000/svg';
const K = 100000; // масштаб проекции, чтобы координаты были читаемыми числами

function project(lat, lon) {
  const x = (lon + 180) / 360;
  const s = Math.sin((lat * Math.PI) / 180);
  const y = 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI);
  return [x * K, y * K];
}

function el(name, attrs = {}) {
  const node = document.createElementNS(NS, name);
  for (const [k, v] of Object.entries(attrs)) {
    if (v !== null && v !== undefined) node.setAttribute(k, v);
  }
  return node;
}

export class SchemeView {
  constructor(container, svg, hooks = {}) {
    this.container = container;
    this.svg = svg;
    this.hooks = hooks;
    this.network = null;
    this.view = { x: 0, y: 0, w: 100, h: 100 };
    this.defaultView = null;
    this.visibleTypes = new Set(['metro', 'mcc', 'mcd', 'project']);
    this.scale = 1;
    this.gLines = el('g', { class: 'scheme__lines' });
    this.gStations = el('g', { class: 'scheme__stations' });
    this.gLabels = el('g', { class: 'scheme__labels' });
    this.svg.append(this.gLines, this.gStations, this.gLabels);
    this.bindInteraction();
  }

  /* ---------- построение ---------- */
  render(network) {
    this.network = network;
    this.points = new Map();
    for (const place of network.places) {
      this.points.set(place.name, project(place.lat, place.lon));
    }

    this.gLines.replaceChildren();
    this.gStations.replaceChildren();
    this.gLabels.replaceChildren();
    this.stationNodes = new Map();

    // границы
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (const [x, y] of this.points.values()) {
      if (x < minX) minX = x;
      if (y < minY) minY = y;
      if (x > maxX) maxX = x;
      if (y > maxY) maxY = y;
    }
    const pad = Math.max(40, (maxX - minX) * 0.06);
    this.defaultView = { x: minX - pad, y: minY - pad, w: (maxX - minX) + pad * 2, h: (maxY - minY) + pad * 2 };
    this.view = { ...this.defaultView };

    // линии
    for (const line of network.lines) {
      const pts = line.stations
        .map((s) => this.points.get(s.name))
        .filter(Boolean);
      if (pts.length < 2) continue;
      const d = pts.map((p, i) => `${i ? 'L' : 'M'}${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join(' ');
      const isProject = line.type === 'project';
      const path = el('path', {
        d,
        class: 'scheme__line',
        stroke: line.color,
        'stroke-width': isProject ? 2.4 : 3.4,
        'stroke-dasharray': isProject ? '6 5' : null,
        'vector-effect': 'non-scaling-stroke',
        opacity: isProject ? 0.92 : 0.97,
        'data-type': line.type,
      });
      path.addEventListener('click', () => this.hooks.onLineClick?.(line));
      this.gLines.appendChild(path);
    }

    // станции + подписи с разрешением наложений
    const labelCandidates = [];
    for (const place of network.places) {
      const [x, y] = this.points.get(place.name);
      const isTransfer = place.isTransfer;
      const node = el('circle', {
        cx: x, cy: y,
        class: 'scheme__dot',
        fill: isTransfer ? '#0b1119' : '#0f1620',
        stroke: isTransfer ? '#f4f7fc' : (place.lineRefs[0]?.color || '#8b95a7'),
        'stroke-width': 1.5,
        'data-name': place.name,
      });
      node.addEventListener('click', (e) => {
        e.stopPropagation();
        this.hooks.onStationClick?.(place);
      });
      this.stationNodes.set(place.name, node);
      this.gStations.appendChild(node);

      if (place.isTransfer || place.isTerminal) {
        labelCandidates.push({
          place,
          x,
          y,
          rank: place.isTransfer ? 0 : 1,
        });
      }
    }

    // подпись ставим, только если она не перекрывает уже поставленные
    const NOMINAL_SCALE = Math.max(0.55, Math.min(3.2, ((this.defaultView?.w || 460) / 1068) * 1.15));
    const fs = 11 * NOMINAL_SCALE;
    const placed = [];
    const collides = (r) => placed.some((p) => !(r.x > p.x + p.w || r.x + r.w < p.x || r.y > p.y + p.h || r.y + r.h < p.y));

    labelCandidates.sort((a, b) => a.rank - b.rank || a.place.name.length - b.place.name.length);
    for (const cand of labelCandidates) {
      const anchor = this.anchorFor(cand.x);
      const width = cand.place.name.length * fs * 0.56;
      const height = fs * 1.15;
      const x = anchor === 'end' ? cand.x - 7 * NOMINAL_SCALE - width : cand.x + 7 * NOMINAL_SCALE;
      const rect = { x, y: cand.y - height / 2, w: width, h: height };
      if (collides(rect)) continue;
      placed.push(rect);

      const label = el('text', {
        x: cand.x, y: cand.y,
        class: 'scheme__label' + (cand.place.isTransfer ? ' scheme__label--major' : ''),
        'data-name': cand.place.name,
        'text-anchor': anchor,
        dx: anchor === 'end' ? -8 : 8,
        dy: 4,
      });
      label.textContent = cand.place.name;
      this.gLabels.appendChild(label);
    }

    this.applyVisibility();
    this.applyView();
  }

  anchorFor(x) {
    const mid = this.defaultView ? this.defaultView.x + this.defaultView.w / 2 : x;
    return x <= mid ? 'start' : 'end';
  }

  setVisibleTypes(types) {
    this.visibleTypes = new Set(types);
    this.applyVisibility();
  }

  applyVisibility() {
    if (!this.network) return;
    for (const path of this.gLines.children) {
      path.style.display = this.visibleTypes.has(path.dataset.type) ? '' : 'none';
    }
    for (const place of this.network.places) {
      const visible = place.lineRefs.some((l) => this.visibleTypes.has(l.type));
      const node = this.stationNodes.get(place.name);
      if (node) node.style.display = visible ? '' : 'none';
    }
    for (const label of this.gLabels.children) {
      const place = this.network.byName.get(label.dataset.name);
      const visible = place ? place.lineRefs.some((l) => this.visibleTypes.has(l.type)) : false;
      label.style.display = visible ? '' : 'none';
    }
  }

  /* ---------- вид ---------- */
  applyView() {
    const v = this.view;
    this.svg.setAttribute('viewBox', `${v.x} ${v.y} ${v.w} ${v.h}`);
    this.updateScales();
  }

  updateScales() {
    const width = this.svg.clientWidth || this.container.clientWidth || 1;
    this.scale = this.view.w / width;
    const s = this.scale;
    const base = Math.max(0.55, Math.min(3.2, s * 1.15));
    for (const node of this.stationNodes.values()) {
      const place = this.network.byName.get(node.dataset.name);
      const isTransfer = place && place.isTransfer;
      node.setAttribute('r', ((isTransfer ? 4.4 : 3.2) * base).toFixed(2));
      node.setAttribute('stroke-width', ((isTransfer ? 1.9 : 1.5) * base).toFixed(2));
    }
    for (const label of this.gLabels.children) {
      label.setAttribute('font-size', (11 * base).toFixed(2) + 'px');
      label.setAttribute('stroke-width', (3 * base).toFixed(2) + 'px');
      label.setAttribute('dx', label.getAttribute('text-anchor') === 'end' ? -7 * base : 7 * base);
    }
    for (const path of this.gLines.children) {
      path.setAttribute('stroke-width', (path.getAttribute('stroke-dasharray') ? 2.4 : 3.4) * base);
    }
  }

  fit() {
    if (!this.defaultView) return;
    this.view = { ...this.defaultView };
    this.applyView();
  }

  zoomBy(factor, cx, cy) {
    const v = this.view;
    const nextW = Math.min(this.defaultView.w * 2.2, Math.max(this.defaultView.w / 14, v.w * factor));
    const k = nextW / v.w;
    const anchorX = cx ?? v.x + v.w / 2;
    const anchorY = cy ?? v.y + v.h / 2;
    this.view = {
      x: anchorX - (anchorX - v.x) * k,
      y: anchorY - (anchorY - v.y) * k,
      w: nextW,
      h: v.h * k,
    };
    this.applyView();
  }

  clientToView(clientX, clientY) {
    const rect = this.svg.getBoundingClientRect();
    const aspect = rect.width / rect.height;
    const viewAspect = this.view.w / this.view.h;
    let x0 = this.view.x, y0 = this.view.y, w = this.view.w, h = this.view.h;
    if (viewAspect > aspect) {
      const newH = w / aspect;
      y0 -= (newH - h) / 2; h = newH;
    } else {
      const newW = h * aspect;
      x0 -= (newW - w) / 2; w = newW;
    }
    return {
      x: x0 + ((clientX - rect.left) / rect.width) * w,
      y: y0 + ((clientY - rect.top) / rect.height) * h,
    };
  }

  bindInteraction() {
    const svg = this.svg;
    svg.addEventListener('wheel', (e) => {
      e.preventDefault();
      const p = this.clientToView(e.clientX, e.clientY);
      this.zoomBy(e.deltaY > 0 ? 1.12 : 0.89, p.x, p.y);
    }, { passive: false });

    let dragging = null;
    svg.addEventListener('pointerdown', (e) => {
      if (e.button !== 0) return;
      dragging = { x: e.clientX, y: e.clientY, view: { ...this.view } };
      svg.setPointerCapture(e.pointerId);
    });
    svg.addEventListener('pointermove', (e) => {
      if (!dragging) return;
      const rect = svg.getBoundingClientRect();
      const dx = ((e.clientX - dragging.x) / rect.width) * dragging.view.w;
      const dy = ((e.clientY - dragging.y) / rect.height) * dragging.view.h;
      this.view = { ...dragging.view, x: dragging.view.x - dx, y: dragging.view.y - dy };
      this.applyView();
    });
    const stop = (e) => {
      if (!dragging) return;
      dragging = null;
      try { svg.releasePointerCapture(e.pointerId); } catch { /* уже отпущен */ }
    };
    svg.addEventListener('pointerup', stop);
    svg.addEventListener('pointercancel', stop);
    svg.addEventListener('dblclick', () => this.fit());
  }

  resize() { this.updateScales(); }

  zoomIn() { this.zoomBy(0.8); }
  zoomOut() { this.zoomBy(1.25); }
}
