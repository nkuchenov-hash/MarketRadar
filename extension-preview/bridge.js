(() => {
 'use strict';
 const allowed = (location.hostname==='nkuchenov-hash.github.io' && location.pathname.startsWith('/MarketRadar/'))
     || (location.hostname==='raw.githack.com' && location.pathname.startsWith('/nkuchenov-hash/MarketRadar/main/live-preview/'));
 if (!allowed) return;
 const origin = location.origin;
 const send=(type,extra={})=>window.postMessage({__marketRadarExtension:true,type,...extra},origin);
 send('MR_CONNECTED');
 chrome.runtime.onMessage.addListener((msg,sender,sendResponse)=>{
   if(msg?.type==='MR_PING'){send('MR_CONNECTED');sendResponse({ok:true});return true;}
 });
 window.addEventListener('message',event=>{
   if(event.source!==window || event.origin!==origin || !event.data?.__marketRadarPage)return;
   const d=event.data;
   if(d.type==='MR_HELLO')send('MR_CONNECTED');
   if(d.type!=='MR_SCAN')return;
   if(!Number.isSafeInteger(d.id)||!['ozon','wb','ym'].includes(d.market)||typeof d.query!=='string'||d.query.trim().length<3||d.query.length>160)return;
   chrome.runtime.sendMessage({type:'SCAN',query:d.query.trim(),market:d.market})
     .then(result=>send('MR_RESULT',{id:d.id,market:d.market,result}))
     .catch(err=>send('MR_RESULT',{id:d.id,market:d.market,result:{state:'error',diagnostic:String(err).slice(0,180),offers:[]}}));
 });
})();
