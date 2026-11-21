/* ===========================================================
   Режим «Схема»: топологическая схема в духе Яндекс.Метро.

   Геометрия приходит из data/scheme_layout.json — её считает
   tools/build-scheme.mjs (линии под 45°/90°, равные интервалы,
   копии станций стянуты в пересадочные узлы).

   Здесь только отрисовка: линии, станции, пересадки, номера линий
   на концах, подписи с разрешением наложений, панорамирование и зум.
   =========================================================== */

const NS = 'http://www.w3.org/2000/svg';

const STYLE = {
  casing: 3.6,
  lineWidth: { metro: 7, mcc: 6, mcd: 6, project: 5 },
  stationR: 3.6,
  transferR: 5.4,
  labelFont: 11,
  labelFontMajor: 11.5,
  roundelR: 9,
};

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
    this.layout = null;
    this.view = { x: 0, y: 0, w: 100, h: 100 };
    this.base = null;
    this.visibleTypes = new Set(['metro', 'mcc', 'mcd', 'project']);
    this.showLabels = true;
    this.unit = 1;
    this.fitted = false;

    this.defs = el('defs');
    this.svg.append(this.defs);

    this.gCasing = el('g', { class: 'scheme-casing' });
    this.gLines = el('g', { class: 'scheme-lines' });
    this.gTies = el('g', { class: 'scheme-ties' });
    this.gStations = el('g', { class: 'scheme-stations' });
    this.gLabels = el('g', { class: 'scheme-labels' });
    this.gRoundels = el('g', { class: 'scheme-roundels' });
    this.svg.append(this.gCasing, this.gLines, this.gTies, this.gStations, this.gLabels, this.gRoundels);

    this.bindInteraction();
  }

  /* ---------- данные ---------- */
  async load() {
    const res = await fetch('data/scheme_layout.json', { cache: 'no-cache' });
    if (!res.ok) throw new Error(`схема: HTTP ${res.status}`);
    this.layout = await res.json();
    this.base = { x: 0, y: 0, w: this.layout.meta.width, h: this.layout.meta.height };
    this.view = { ...this.base };
    this.render();
  }

  applyView() {
    const v = this.view;
    this.svg.setAttribute('viewBox', `${v.x} ${v.y} ${v.w} ${v.h}`);
    this.updateScale();
  }

  updateScale() {
    // пока контейнер скрыт, clientWidth = 0 — берём разумную заглушку,
    // иначе масштаб получается абсурдным и все подписи «сталкиваются»
    const pxWidth = this.svg.clientWidth || this.container.clientWidth || 1000;
    this.unit = this.view.w / pxWidth;
    this.styleAll();
  }

  styleAll() {
    const u = this.unit;
    for (const path of this.gLines.children) {
      const type = path.dataset.type;
      const w = (STYLE.lineWidth[type] || 6) * u;
      path.setAttribute('stroke-width', w.toFixed(2));
      path.setAttribute('stroke-dasharray', type === 'project' ? `${6 * u} ${5 * u}` : '');
    }
    for (const path of this.gCasing.children) {
      const type = path.dataset.type;
      const w = ((STYLE.lineWidth[type] || 6) + STYLE.casing) * u;
      path.setAttribute('stroke-width', w.toFixed(2));
    }
    for (const node of this.gStations.children) {
      const r = Number(node.dataset.r) || STYLE.stationR;
      node.setAttribute('r', (r * u).toFixed(2));
      node.setAttribute('stroke-width', ((node.dataset.sw || 1.6) * u).toFixed(2));
    }
    for (const tie of this.gTies.children) {
      tie.setAttribute('stroke-width', (2 * u).toFixed(2));
    }
    for (const label of this.gLabels.children) {
      const fs = (Number(label.dataset.fs) || STYLE.labelFont) * u;
      label.setAttribute('font-size', fs.toFixed(2));
      label.setAttribute('stroke-width', (3.2 * u).toFixed(2));
      label.setAttribute('dx', (Number(label.dataset.dx) * u).toFixed(2));
      label.setAttribute('dy', (Number(label.dataset.dy) * u).toFixed(2));
    }
    for (const g of this.gRoundels.children) {
      const c = g.querySelector('circle');
      const t = g.querySelector('text');
      if (c) {
        c.setAttribute('r', (STYLE.roundelR * u).toFixed(2));
        c.setAttribute('stroke-width', (2.4 * u).toFixed(2));
      }
      if (t) t.setAttribute('font-size', (9.5 * u).toFixed(2));
    }
  }

  /* ---------- отрисовка ---------- */
  render() {
    if (!this.layout) return;
    const { stations, lines } = this.layout;

    this.gCasing.replaceChildren();
    this.gLines.replaceChildren();
    this.gTies.replaceChildren();
    this.gStations.replaceChildren();
    this.gLabels.replaceChildren();
    this.gRoundels.replaceChildren();

    const d = (pts) => pts.map((p, i) => `${i ? 'L' : 'M'}${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join(' ');

    for (const line of lines) {
      if (line.points.length < 2) continue;
      const path = d(line.points);
      const casing = el('path', {
        d: path + (line.ring ? ' Z' : ''),
        fill: 'none',
        stroke: '#05080d',
        'stroke-linejoin': 'round',
        'stroke-linecap': 'round',
        'data-type': line.type,
        'data-line': line.id,
      });
      const stroke = el('path', {
        d: path + (line.ring ? ' Z' : ''),
        fill: 'none',
        stroke: line.color,
        'stroke-linejoin': 'round',
        'stroke-linecap': 'round',
        'data-type': line.type,
        'data-line': line.id,
        class: 'scheme__line',
      });
      stroke.addEventListener('mouseenter', () => this.highlight(line.id));
      stroke.addEventListener('mouseleave', () => this.highlight(null));
      stroke.addEventListener('click', () => this.hooks.onLineClick?.(line));
      this.gCasing.appendChild(casing);
      this.gLines.appendChild(stroke);
    }

    // пересадочные узлы: если копии разошлись, рисуем переход
    for (const st of stations) {
      if (st.copies.length < 2) continue;
      const uniq = [];
      for (const [, x, y] of st.copies) {
        if (!uniq.some((u) => Math.hypot(u[0] - x, u[1] - y) < 0.4)) uniq.push([x, y]);
      }
      for (let i = 1; i < uniq.length; i++) {
        this.gTies.appendChild(el('line', {
          x1: uniq[i - 1][0], y1: uniq[i - 1][1],
          x2: uniq[i][0], y2: uniq[i][1],
          stroke: '#e8eef8',
          'stroke-linecap': 'round',
          opacity: 0.85,
        }));
      }
    }

    for (const st of stations) {
      const isTransfer = st.transfer;
      const r = isTransfer ? Math.min(9, STYLE.transferR + st.spread * 0.35) : STYLE.stationR;
      const node = el('circle', {
        cx: st.x,
        cy: st.y,
        fill: isTransfer ? '#0b1119' : (this.lineColor(st.lines[0]) || '#8b95a7'),
        stroke: isTransfer ? '#f4f7fc' : '#0a0e15',
        'data-name': st.name,
        'data-r': r,
        'data-sw': isTransfer ? 2.4 : 1.6,
        'data-project': st.project ? '1' : '0',
        class: 'scheme__dot',
      });
      node.addEventListener('click', (e) => {
        e.stopPropagation();
        this.hooks.onStationClick?.(this.toPlace(st));
      });
      node.addEventListener('mouseenter', () => this.showTooltip(st, node));
      node.addEventListener('mouseleave', () => this.hideTooltip());
      this.gStations.appendChild(node);
    }

    // номера линий на концах
    for (const line of lines) {
      if (!line.ref) continue;
      const ends = [line.points[0], line.points[line.points.length - 1]];
      for (const [x, y] of ends) {
        const g = el('g', { 'data-line': line.id, class: 'scheme__roundel' });
        g.append(
          el('circle', { cx: x, cy: y, r: STYLE.roundelR, fill: line.color, stroke: '#0b1119' }),
          el('text', {
            x, y,
            'text-anchor': 'middle',
            'dominant-baseline': 'central',
            fill: '#fff',
            'font-weight': '700',
            'font-size': 9.5,
            'paint-order': 'stroke',
            stroke: 'rgba(0,0,0,.25)',
            'stroke-width': 1,
          }),
        );
        g.lastChild.textContent = line.ref;
        this.gRoundels.appendChild(g);
      }
    }

    this.stationByName = new Map(stations.map((s) => [s.name, s]));
    this.applyVisibility();
    this.applyView();
    this.layoutLabels();
  }

  lineColor(id) {
    const line = this.layout?.lines.find((l) => l.id === id);
    return line ? line.color : null;
  }

  toPlace(st) {
    return {
      name: st.name,
      lat: null,
      lon: null,
      isTransfer: st.transfer,
      isTerminal: false,
      hasProject: !!st.project,
      hasActive: !st.project,
      lineRefs: st.lines.map((id) => {
        const line = this.layout.lines.find((l) => l.id === id);
        return line ? { id, name: line.name, ref: line.ref, color: line.color, type: line.type } : null;
      }).filter(Boolean),
    };
  }

  showTooltip(st, node) {
    const box = this.container.getBoundingClientRect();
    const pt = node.getBoundingClientRect();
    this.hideTooltip();
    const tip = document.createElement('div');
    tip.className = 'scheme-tip';
    tip.textContent = st.name;
    tip.style.left = `${pt.left - box.left + pt.width / 2}px`;
    tip.style.top = `${pt.top - box.top - 10}px`;
    this.container.appendChild(tip);
    this.tip = tip;
  }

  hideTooltip() {
    if (this.tip) { this.tip.remove(); this.tip = null; }
  }

  /* ---------- слои ---------- */
  setVisibleTypes(types) {
    this.visibleTypes = new Set(types);
    this.applyVisibility();
  }

  setLabels(on) {
    this.showLabels = on;
    this.applyVisibility();
    this.layoutLabels();
  }

  applyVisibility() {
    if (!this.layout) return;
    for (const p of this.gLines.children) p.style.display = this.visibleTypes.has(p.dataset.type) ? '' : 'none';
    for (const p of this.gCasing.children) p.style.display = this.visibleTypes.has(p.dataset.type) ? '' : 'none';
    for (const g of this.gRoundels.children) {
      const line = this.layout.lines.find((l) => l.id === g.dataset.line);
      g.style.display = line && this.visibleTypes.has(line.type) ? '' : 'none';
    }
    for (const node of this.gStations.children) {
      const st = this.stationByName?.get(node.dataset.name);
      const visible = st ? st.lines.some((id) => {
        const line = this.layout.lines.find((l) => l.id === id);
        return line && this.visibleTypes.has(line.type);
      }) : true;
      node.style.display = visible ? '' : 'none';
    }
    this.layoutLabels();
  }

  /* ---------- подписи ---------- */
  layoutLabels() {
    if (!this.layout) return;
    this.gLabels.replaceChildren();
    if (!this.showLabels) return;

    const u = this.unit;
    const zoom = this.base.w / this.view.w;
    const showAll = zoom >= 1.55;
    const fs = STYLE.labelFont * u;
    const pad = 2 * u;

    const visible = (st) => st.lines.some((id) => {
      const line = this.layout.lines.find((l) => l.id === id);
      return line && this.visibleTypes.has(line.type);
    });

    const candidates = this.layout.stations
      .filter((st) => visible(st))
      .filter((st) => showAll || st.transfer || st.lines.length > 1 || this.isLineEnd(st))
      .sort((a, b) => (b.transfer ? 1 : 0) - (a.transfer ? 1 : 0) || a.name.length - b.name.length);

    const placed = [];
    const pxToUnit = (px) => px * u;
    const margin = pxToUnit(2);
    const hit = (r) => placed.some((p) => !(
      r.x - margin > p.x + p.w || r.x + r.w + margin < p.x ||
      r.y - margin > p.y + p.h || r.y + r.h + margin < p.y
    ));

    for (const st of candidates) {
      const isMajor = st.transfer;
      const fsPx = isMajor ? STYLE.labelFontMajor : STYLE.labelFont;
      // размеры калиброваны по реальному браузеру: рамка текста шире глифов
      const wText = pxToUnit(st.name.length * (isMajor ? 6.7 : 6.4) + 16);
      const hText = pxToUnit(fsPx * 1.95);
      const off = pxToUnit(7);
      const baseShift = pxToUnit(fsPx * 0.36);

      const variants = [
        { x: st.x + off, y: st.y - hText / 2, rect: { x: st.x + off, y: st.y - hText / 2, w: wText, h: hText }, anchor: 'start', dx: off, dy: baseShift },
        { x: st.x - off, y: st.y - hText / 2, rect: { x: st.x - off - wText, y: st.y - hText / 2, w: wText, h: hText }, anchor: 'end', dx: -off, dy: baseShift },
        { x: st.x, y: st.y - off, rect: { x: st.x - wText / 2, y: st.y - off - hText, w: wText, h: hText }, anchor: 'middle', dx: 0, dy: -pxToUnit(fsPx * 0.25) },
        { x: st.x, y: st.y + off, rect: { x: st.x - wText / 2, y: st.y + off, w: wText, h: hText }, anchor: 'middle', dx: 0, dy: pxToUnit(fsPx * 1.15) },
      ];

      let chosen = null;
      for (const v of variants) {
        if (!hit(v.rect)) { chosen = v; break; }
      }
      if (!chosen) continue;
      placed.push(chosen.rect);

      const text = el('text', {
        x: chosen.x,
        y: chosen.y,
        class: 'scheme__label' + (isMajor ? ' scheme__label--major' : ''),
        'text-anchor': chosen.anchor,
        'data-fs': fsPx,
        'data-dx': +(chosen.dx / u).toFixed(2),
        'data-dy': +(chosen.dy / u).toFixed(2),
        'data-name': st.name,
      });
      text.textContent = st.name;
      this.gLabels.appendChild(text);
    }
    this.styleAll();
  }

  isLineEnd(st) {
    for (const line of this.layout.lines) {
      const s = line.stations;
      if (s[0] === st.name || s[s.length - 1] === st.name) return true;
    }
    return false;
  }

  highlight(lineId) {
    for (const p of this.gLines.children) {
      p.style.opacity = !lineId || p.dataset.line === lineId ? '' : '0.18';
    }
    for (const p of this.gCasing.children) {
      p.style.opacity = !lineId || p.dataset.line === lineId ? '' : '0.18';
    }
    for (const node of this.gStations.children) {
      if (!lineId) { node.style.opacity = ''; continue; }
      const st = this.stationByName.get(node.dataset.name);
      node.style.opacity = st && st.lines.includes(lineId) ? '' : '0.25';
    }
  }

  /* ---------- вид ---------- */
  fit(view = null) {
    if (!this.base) return;
    this.view = view ? { ...view } : { ...this.base };
    this.applyView();
    this.layoutLabels();
  }

  /** Границы ядра сети (метро + МЦК) — стартовый кадр на телефоне. */
  coreBox(padding = 0.05) {
    if (!this.base) return null;
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (const line of this.layout.lines) {
      if (line.type === 'project' || line.type === 'mcd') continue;
      for (const [x, y] of line.points) {
        if (x < minX) minX = x;
        if (y < minY) minY = y;
        if (x > maxX) maxX = x;
        if (y > maxY) maxY = y;
      }
    }
    if (minX > maxX) return this.base;
    const w = maxX - minX;
    const h = maxY - minY;
    return { x: minX - w * padding, y: minY - h * padding, w: w * (1 + padding * 2), h: h * (1 + padding * 2) };
  }

  zoomBy(factor, cx, cy) {
    const v = this.view;
    const minW = this.base.w / 12;
    const maxW = this.base.w * 1.6;
    const nextW = Math.min(maxW, Math.max(minW, v.w * factor));
    const k = nextW / v.w;
    const ax = cx ?? v.x + v.w / 2;
    const ay = cy ?? v.y + v.h / 2;
    this.view = {
      x: ax - (ax - v.x) * k,
      y: ay - (ay - v.y) * k,
      w: nextW,
      h: v.h * k,
    };
    this.applyView();
    this.requestLabelRefresh();
    if (this.hooks.onViewChange) this.hooks.onViewChange(this.view, this.base);
  }

  requestLabelRefresh() {
    if (this.labelTimer) clearTimeout(this.labelTimer);
    this.labelTimer = setTimeout(() => this.layoutLabels(), 120);
  }

  clientToView(clientX, clientY) {
    const rect = this.svg.getBoundingClientRect();
    const pxW = rect.width || 1;
    const pxH = rect.height || 1;
    const scale = Math.min(pxW / this.view.w, pxH / this.view.h);
    const offX = (pxW - this.view.w * scale) / 2;
    const offY = (pxH - this.view.h * scale) / 2;
    return {
      x: this.view.x + (clientX - rect.left - offX) / scale,
      y: this.view.y + (clientY - rect.top - offY) / scale,
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
      const scale = Math.min(rect.width / dragging.view.w, rect.height / dragging.view.h) || 1;
      this.view = {
        ...dragging.view,
        x: dragging.view.x - (e.clientX - dragging.x) / scale,
        y: dragging.view.y - (e.clientY - dragging.y) / scale,
      };
      this.applyView();
    });
    const stop = (e) => {
      if (!dragging) return;
      dragging = null;
      try { svg.releasePointerCapture(e.pointerId); } catch { /* уже отпущен */ }
      this.requestLabelRefresh();
      if (this.hooks.onViewChange) this.hooks.onViewChange(this.view, this.base);
    };
    svg.addEventListener('pointerup', stop);
    svg.addEventListener('pointercancel', stop);
    svg.addEventListener('dblclick', () => this.fit());
    svg.addEventListener('click', () => this.hideTooltip());
  }

  resize() { this.updateScale(); this.layoutLabels(); }
  zoomIn() { this.zoomBy(0.8); }
  zoomOut() { this.zoomBy(1.25); }
}
