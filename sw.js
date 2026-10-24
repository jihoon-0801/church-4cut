// 설치용 서비스 워커: 항상 새 버전을 먼저 받고, 인터넷이 끊기면 저장해 둔 화면을 보여줌
const C = 'c4-v3';
self.addEventListener('install', e => self.skipWaiting());
self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));
self.addEventListener('fetch', e => {
  const u = new URL(e.request.url);
  if (e.request.method !== 'GET' || u.origin !== location.origin || u.pathname.includes('/__uvc/')) return;   // USB 웹캠 장면은 앱이 바로 줘요
  // GitHub Pages가 옛 화면을 10분쯤 붙잡고 있어서, 매번 새 버전인지 서버에 물어봐요
  e.respondWith(fetch(e.request.mode === 'navigate' ? new Request(e.request.url, {cache:'no-cache'}) : e.request, {cache:'no-cache'}).then(r => {
    const copy = r.clone(); caches.open(C).then(c => c.put(e.request, copy)); return r;
  }).catch(() => caches.match(e.request)));
});
