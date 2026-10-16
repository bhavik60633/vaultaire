'use strict';
// Service worker for the Vaultaire UI prototype.
// Network first, so a phone on the same network always gets the latest layout;
// falls back to the cache when offline. The prototype holds no secrets.

const CACHE = 'vaultaire-prototype-v8';
const FILES = [
  './',
  './index.html',
  './styles.css',
  './app.js',
  './grain.svg',
  './manifest.webmanifest',
  './brand/lockup.png',
  './brand/mark.png',
  './fonts/ibm-plex-sans-latin.woff2',
  './fonts/ibm-plex-mono-400-latin.woff2',
  './fonts/ibm-plex-mono-500-latin.woff2',
  './fonts/newsreader-latin.woff2',
  './fonts/schibsted-grotesk-latin.woff2',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/icon-maskable-512.png',
  './icons/apple-touch-icon.png'
];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(FILES)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET' || new URL(request.url).origin !== self.location.origin) return;
  event.respondWith(
    fetch(request)
      .then((response) => {
        if (response.ok) {
          const copy = response.clone();
          caches.open(CACHE).then((cache) => cache.put(request, copy));
        }
        return response;
      })
      .catch(() => caches.match(request, { ignoreSearch: true }).then((hit) => hit || caches.match('./index.html')))
  );
});
