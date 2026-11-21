/* Service worker: офлайн-доступ и мгновенный сброс старого кэша.
   Важно: HTML отдаётся по принципу «сеть, потом кэш»,
   иначе обновления сайта никогда не доезжают до браузера. */

const CACHE = 'transport-podmoskovye-v4';

const PRECACHE = [
  './',
  './index.html',
  './css/app.css',
  './js/main.js',
  './js/network.js',
  './js/mapview.js',
  './js/schemeview.js',
  './js/router.js',
  './vendor/leaflet/leaflet.js',
  './vendor/leaflet/leaflet.css',
  './data/lines.json',
  './data/project_lines.json',
  './data/scheme_layout.json',
  './manifest.json',
  './icon.svg',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE)
      .then((cache) => cache.addAll(PRECACHE))
      .catch(() => undefined)
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);
  const sameOrigin = url.origin === self.location.origin;

  // HTML — всегда сначала сеть
  if (request.mode === 'navigate' || (sameOrigin && request.destination === 'document')) {
    event.respondWith(
      fetch(request)
        .then((res) => {
          const copy = res.clone();
          caches.open(CACHE).then((cache) => cache.put(request, copy)).catch(() => undefined);
          return res;
        })
        .catch(() => caches.match(request).then((hit) => hit || caches.match('./index.html')))
    );
    return;
  }

  // свои данные и код — кэш, но с обновлением в фоне
  if (sameOrigin && /\/data\/|\/js\/|\/css\/|\/vendor\//.test(url.pathname)) {
    event.respondWith(
      caches.match(request).then((hit) => {
        const network = fetch(request)
          .then((res) => {
            const copy = res.clone();
            caches.open(CACHE).then((cache) => cache.put(request, copy)).catch(() => undefined);
            return res;
          })
          .catch(() => hit);
        return hit || network;
      })
    );
    return;
  }

  // тайлы и всё внешнее — сеть, без кэша
});
