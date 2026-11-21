import fs from 'node:fs';

const layout = JSON.parse(fs.readFileSync('data/scheme_layout.json', 'utf8'));
const st = layout.stations;
let min = Infinity;
const close = [];
for (let i = 0; i < st.length; i++) {
  for (let j = i + 1; j < st.length; j++) {
    const d = Math.hypot(st[i].x - st[j].x, st[i].y - st[j].y);
    if (d < min) min = d;
    if (d < 9) close.push([st[i].name, st[j].name, +d.toFixed(1)]);
  }
}
console.log('станций:', st.length, '| минимальное расстояние:', min.toFixed(2), '| пар ближе 9:', close.length);
console.log('примеры:', JSON.stringify(close.sort((a, b) => a[2] - b[2]).slice(0, 12), null, 0));
console.log('мета:', JSON.stringify(layout.meta));
