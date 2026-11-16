(()=>{
  'use strict';
  if(!((location.hostname==='nkuchenov-hash.github.io'&&location.pathname.startsWith('/MarketRadar/'))||(location.hostname==='raw.githack.com'&&location.pathname.startsWith('/nkuchenov-hash/MarketRadar/main/live-preview/'))))return;
  const origin=location.origin;
  const send=(type,extra={})=>window.postMessage({__marketRadarExtension:true,type,...extra},origin);
  send('MR_CONNECTED');
  window.addEventListener('message',e=>{
    if(e.source!==window||e.origin!==origin||!e.data?.__marketRadarPage)return;
    const d=e.data;
    if(d.type==='MR_HELLO')send('MR_CONNECTED');
    if(d.type==='MR_SCAN'&&Number.isSafeInteger(d.id)&&['ozon','wb','ym'].includes(d.market)&&typeof d.query==='string'&&d.query.length>=3&&d.query.length<=160){
      chrome.runtime.sendMessage({type:'SCAN',query:d.query,market:d.market}).then(result=>send('MR_RESULT',{id:d.id,market:d.market,result})).catch(err=>send('MR_RESULT',{id:d.id,market:d.market,result:{state:'error',diagnostic:String(err).slice(0,180),offers:[]}}));
    }
  });
})();
