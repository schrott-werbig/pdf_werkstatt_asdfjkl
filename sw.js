// PDF Werkstatt – Service Worker: Offline-Cache + Teilen-Ziel (Android)
const C='pdfwerkstatt-v50', SHARE='pdfwerkstatt-share';
const CORE=['./pdf-werkstatt.html','./manifest.webmanifest','./icon-192.png','./icon-512.png'];
self.addEventListener('install',e=>{ self.skipWaiting(); e.waitUntil(caches.open(C).then(c=>Promise.all(CORE.map(u=>c.add(u).catch(()=>{}))))); });
self.addEventListener('activate',e=>e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>x!==C&&x!==SHARE).map(x=>caches.delete(x)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',e=>{
  const url=new URL(e.request.url);
  if(e.request.method==='POST' && url.pathname.endsWith('/share-target')){
    e.respondWith((async()=>{
      const fd=await e.request.formData(); const c=await caches.open(SHARE); let i=0;
      for(const f of fd.getAll('file')){ if(!(f instanceof File)) continue; await c.put('./shared-'+(Date.now()+i++), new Response(f,{headers:{'content-type':f.type||'application/pdf','x-name':encodeURIComponent(f.name||'Geteilt.pdf')}})); }
      return Response.redirect('./pdf-werkstatt.html?share=1',303);
    })()); return;
  }
  // Sicherheit: Anfragen an fremde Server werden grundsätzlich blockiert
  if(url.origin!==location.origin){ e.respondWith(Response.error()); return; }
  if(e.request.method!=='GET') return;
  // immer beim Server nachfragen (ETag/304) – sonst liefert der Browser-Cache bis 10 Min. eine alte Version
  e.respondWith(fetch(e.request,{cache:'no-cache'}).then(r=>{ if(r.ok&&url.origin===location.origin){ const cp=r.clone(); caches.open(C).then(c=>c.put(e.request,cp)); } return r; })
    .catch(()=>caches.match(e.request,{ignoreSearch:true}).then(r=>r||caches.match('./pdf-werkstatt.html'))));
});
