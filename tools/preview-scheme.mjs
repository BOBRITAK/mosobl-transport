/* ASCII-превью схемы: рисует линии и станции из data/scheme_layout.json,
   чтобы оценить общий рисунок без картинки. */
import fs from 'node:fs';

const layout = JSON.parse(fs.readFileSync('data/scheme_layout.json', 'utf8'));
const COLS = Number(process.argv[2] || 118);

const { width, height } = layout.meta;
const aspect = (COLS * 0.5) / (COLS * height / width);
const ROWS = Math.max(20, Math.round(COLS * height / width * 0.5));

const grid = Array.from({ length: ROWS }, () => new Array(COLS).fill(' '));

const toCol = (x) => Math.round((x / width) * (COLS - 1));
const toRow = (y) => Math.round((y / height) * (ROWS - 1));

// линии
for (const line of layout.lines) {
  const ch = line.type === 'project' ? '-' : (line.type === 'mcd' ? '=' : (line.type === 'mcc' ? '~' : '*'));
  for (let i = 1; i < line.points.length; i++) {
    const [x0, y0] = line.points[i - 1];
    const [x1, y1] = line.points[i];
    const steps = Math.max(Math.abs(toCol(x1) - toCol(x0)), Math.abs(toRow(y1) - toRow(y0)), 1);
    for (let s = 0; s <= steps; s++) {
      const t = s / steps;
      const c = Math.round(toCol(x0) + (toCol(x1) - toCol(x0)) * t);
      const r = Math.round(toRow(y0) + (toRow(y1) - toRow(y0)) * t);
      if (r < 0 || r >= ROWS || c < 0 || c >= COLS) continue;
      if (grid[r][c] === ' ') grid[r][c] = ch;
    }
  }
}

// станции
for (const st of layout.stations) {
  const c = toCol(st.x), r = toRow(st.y);
  if (r < 0 || r >= ROWS || c < 0 || c >= COLS) continue;
  grid[r][c] = st.transfer ? 'O' : 'o';
}

console.log(`# ${width.toFixed(0)} x ${height.toFixed(0)} | станций ${layout.meta.stations} | линий ${layout.meta.lines}`);
console.log(`# углы: ${layout.meta.angleCompliance}% в пределах 1.5°, средн. отклонение ${layout.meta.avgAngleDeviation}°`);
console.log(`# пересадки: разброс копий средн. ${layout.meta.avgCopySpread}, макс ${layout.meta.maxCopySpread}`);
console.log('# * метро   = МЦД   ~ МЦК   - проектируемые   O пересадка   o станция');
console.log('');
for (const row of grid) {
  const s = row.join('').replace(/\s+$/, '');
  if (s.trim()) console.log(s);
}
