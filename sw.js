// 설치용 서비스 워커: 항상 새 버전을 먼저 받고, 인터넷이 끊기면 저장해 둔 화면을 보여줌
const C = 'c4-v1';
self.addEventListener('install', e => self.skipWaiting());
self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));
self.addEventListener('fetch', e => {
  const u = new URL(e.request.url);
  if (e.request.method !== 'GET' || u.origin !== location.origin) return;
  e.respondWith(fetch(e.request).then(r => {
    const copy = r.clone(); caches.open(C).then(c => c.put(e.request, copy)); return r;
  }).catch(() => caches.match(e.request)));
});
