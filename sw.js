const CACHE='snap-maps-shared-v1';
self.addEventListener('install',e=>e.waitUntil(self.skipWaiting()));
self.addEventListener('activate',e=>e.waitUntil(self.clients.claim()));
self.addEventListener('message',e=>{if(e.data?.type==='TAKE_SHARED')e.waitUntil((async()=>{const c=await caches.open(CACHE),r=await c.match('shared-image');if(r){const blob=await r.blob();await c.delete('shared-image');e.source.postMessage({type:'SHARED_IMAGE',file:new File([blob],'shared-screenshot.jpg',{type:blob.type||'image/jpeg'})})}else e.source.postMessage({type:'SHARED_IMAGE',file:null})})())});
self.addEventListener('fetch',e=>{const u=new URL(e.request.url);if(e.request.method==='POST'&&u.pathname.endsWith('/share-target'))e.respondWith((async()=>{const form=await e.request.formData();const file=form.get('image')||form.get('files');if(file&&file.size){const c=await caches.open(CACHE);await c.put('shared-image',new Response(file,{headers:{'Content-Type':file.type||'image/jpeg'}}))}return Response.redirect(new URL('./?shared=1',self.registration.scope),303)})())});

