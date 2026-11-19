const CACHE = "mosobl-transport-v1";
const ASSETS = ["./", "./index.html", "./manifest.json", "./icon.svg", "./data/lines.js", "./data/stations.js", "./data/lines.json"];
self.addEventListener("install", function(e){ e.waitUntil(caches.open(CACHE).then(function(c){ return c.addAll(ASSETS); }).then(function(){ return self.skipWaiting(); })); });
self.addEventListener("activate", function(e){ e.waitUntil(caches.keys().then(function(ks){ return Promise.all(ks.filter(function(k){ return k !== CACHE; }).map(function(k){ return caches.delete(k); })); })); });
self.addEventListener("fetch", function(e){ if(e.request.method !== "GET") return; e.respondWith(caches.match(e.request).then(function(r){ return r || fetch(e.request); })); });
