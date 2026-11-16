/* MarketRadar local browser search: no auth tokens, cookie access or remote MarketRadar server. */
const MARKET={
 ozon:{name:'Ozon',url:q=>'https://www.ozon.ru/search/?text='+encodeURIComponent(q)},
 wb:{name:'Wildberries',url:q=>'https://www.wildberries.ru/catalog/0/search.aspx?search='+encodeURIComponent(q)},
 ym:{name:'Яндекс Маркет',url:q=>'https://market.yandex.ru/search?text='+encodeURIComponent(q)}
};
let tabId=null;
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
function safeURL(url){try{const u=new URL(url);return u.protocol==='https:'&&(u.hostname==='market.yandex.ru'||u.hostname==='ozon.ru'||u.hostname.endsWith('.ozon.ru')||u.hostname==='wildberries.ru'||u.hostname.endsWith('.wildberries.ru'))}catch{return false;}}
function productURL(m,url){if(!safeURL(url))return false;try{const u=new URL(url);if(m==='ozon')return /\/product\/[^/]+/.test(u.pathname);if(m==='wb')return /\/catalog\/\d+\/detail\.aspx/.test(u.pathname);return /\/product--|\/product\/|\/card\//.test(u.pathname);}catch{return false;}}
function canonical(url){try{const u=new URL(url);u.search='';u.hash='';return u.href;}catch{return url;}}
function norm(s){return String(s||'').toLowerCase().replace(/ё/g,'е').replace(/mini/g,'мини').replace(/\bpro\b/g,'про').replace(/[^\p{L}\p{N}]+/gu,' ').trim().replace(/\s+/g,' ')}
const DROP=new Set(['яндекс','колонка','умная','умный','с','для','от','на','в','и','черный','белый','синий','серый','подсветка','алиса','станция','бренд','звук','bluetooth','wifi','мощность','про']);
function scoreMatch(query,title){const q=norm(query).split(' ').filter(Boolean),t=norm(title);if(!t)return 0;const ts=new Set(t.split(' '));const critical=q.filter(w=>/^\d+[a-zа-я]*$/u.test(w)||w==='про'||w==='мини'||w.length>=4&&!DROP.has(w));if(critical.some(w=>!ts.has(w)))return 0;
  const matched=q.filter(w=>ts.has(w)).length;const coverage=matched/q.length;
  if(/\b(чехол|крепление|защитный|модуль|кабель|запчасть|зарядное|кронштейн|держатель)\b/u.test(t)&&!/(чехол|крепление|модуль|кабель|запчасть)/.test(norm(query)))return 0;
  return coverage>=.72?Math.round(coverage*100):0;
}
function priceFragments(text){
 const found=[];
 for(const match of String(text).matchAll(/(?:\d{1,3}(?:[\s\u00a0\u202f]\d{3})+|\d{4,7})\s*(?:₽|руб(?:\.|лей)?)/gi)){
  const raw=match[0],price=Number(raw.replace(/\D/g,''));
  if(price<100||price>20000000)continue;
  const before=text.slice(Math.max(0,match.index-22),match.index).replace(/\s+/g,' ').trim();
  const after=text.slice(match.index+raw.length,Math.min(text.length,match.index+raw.length+65)).replace(/\s+/g,' ').split(/(?:\d{1,3}(?:[\s\u00a0\u202f]\d{3})+|\d{4,7})\s*(?:₽|руб)/i)[0].trim();
  found.push({price,raw,before,after,excerpt:before+' '+raw+' '+after});
 }
 return found;
}
function variants(snap){
 const blocks=(snap.priceBlocks||snap.priceFragments||[]).filter(b=>{const t=typeof b==='string'?b:b?.text||'';return t.length>3&&t.length<1000}).slice(0,35);
 const combined=[];
 const priority = blocks.filter(b=>/webPrice|product-price|price|₽|руб/i.test(typeof b==='string'?b:(b.source+' '+b.text))).slice(0,18);
 for(const block of priority){
  const t=typeof block==='string'?block:(block.text||'');
  const source=typeof block==='string'?'price-widget':block.source||'price-widget';
  for(const p of priceFragments(t))combined.push({...p,source});
  if(combined.length>=16)break;
 }
 // A price found somewhere in the body is not trustworthy: other products appear below the main offer.
 if(!combined.length && snap.structuredPrice){combined.push({price:Number(snap.structuredPrice),before:'',after:'',excerpt:'JSON-LD Product offer',source:'structured-product'});}
 if(!combined.length && (snap.mainText||'').length){
   for(const p of priceFragments(snap.mainText.slice(0,1800)).slice(0,3))combined.push({...p,source:'main-product-area'});
 }
 const prices=[];const seen=new Set();
 for(const p of combined){
  let kind='unverified',condition='Цена на карточке — проверить условия оплаты';
  const near=(p.after+' '+p.before).toLowerCase();
  if(/с другими банками|без ozon карт|обычная цена|без подписки|без карты/i.test(near)){
   kind='other-banks';condition='С другими банками / обычная цена — сверить в корзине';
  }else if(/с банками|ozon банк|ozon карт|озон банк|с картой|картой ozon|оплате.*карт/i.test(near)){
   kind='bank';condition='Цена при определённой оплате — сверить условия';
  }else if(/подписк|плюс|premium|prime/i.test(near)){
   kind='subscription';condition='Цена с подпиской — сверить условия';
  }
  if(p.source==='main-product-area')condition='Цена с основного блока страницы — условия не проверены';
  const key=p.price+'|'+kind;
  if(seen.has(key))continue;
  seen.add(key);
  prices.push({price:p.price,kind,condition,source:p.source});
  if(prices.length>=5)break;
 }
 return prices;
}
async function loaded(id,timeout=16000){const t=await chrome.tabs.get(id);if(t.status==='complete')return;return new Promise((resolve,reject)=>{const timer=setTimeout(()=>{clean();reject(new Error('Не удалось загрузить страницу'));},timeout);const upd=(tab,info)=>{if(tab===id&&info.status==='complete'){clean();resolve()}};const removed=tab=>{if(tab===id){clean();reject(new Error('Вкладка закрыта'))}};function clean(){clearTimeout(timer);chrome.tabs.onUpdated.removeListener(upd);chrome.tabs.onRemoved.removeListener(removed)}chrome.tabs.onUpdated.addListener(upd);chrome.tabs.onRemoved.addListener(removed);});}
async function tabFor(url){let exists=false;if(tabId!==null)try{await chrome.tabs.get(tabId);exists=true}catch{tabId=null;}if(exists)await chrome.tabs.update(tabId,{url});else{try{const win=await chrome.windows.create({url,focused:false,state:'minimized'});tabId=win.tabs[0].id;}catch{const tab=await chrome.tabs.create({url,active:false});tabId=tab.id;}}
 await loaded(tabId);await sleep(1400);const t=await chrome.tabs.get(tabId);if(!safeURL(t.url||''))throw new Error('Магазин перенаправил на постороннюю страницу');return tabId;}
function snapshot(){
 const body=document.body;
 const text=(body?.innerText||'').slice(0,60000);
 const productTitle=(document.querySelector('h1')?.innerText||document.querySelector('meta[property="og:title"]')?.content||document.title||'').replace(/\s+/g,' ').slice(0,320);
 const anchors=Array.from(document.querySelectorAll('a[href]')).slice(0,2600);
 const links=anchors.map(a=>{
  const ancestor=a.closest('article,li,[data-index],[data-card-index],[data-widget="searchResultsV2"]')||a.parentElement;
  return {url:a.href,title:(a.getAttribute('aria-label')||a.innerText||a.querySelector('img')?.alt||'').replace(/\s+/g,' ').slice(0,220),context:(ancestor?.innerText||a.parentElement?.innerText||'').replace(/\s+/g,' ').slice(0,360)};
 });
 const selectors=['[data-widget*="webPrice" i]','[data-widget*="price" i]','[class*="product-price" i]','[itemprop="offers"]','[itemprop="price"]'];
 const items=new Set();const blocks=[];
 for(const selector of selectors)for(const e of Array.from(document.querySelectorAll(selector)).slice(0,35)){
  const content=(e.innerText||e.getAttribute('content')||e.getAttribute('aria-label')||'').replace(/\s+/g,' ').trim();
  if(content.length>3&&content.length<1000&&!items.has(content)){items.add(content);blocks.push({text:content,source:selector.includes('webPrice')?'webPrice':'price-widget'});}
  if(blocks.length>=45)break;
 }
 let structuredPrice=null;
 for(const node of Array.from(document.querySelectorAll('script[type="application/ld+json"]')).slice(0,10))try{
   const data=JSON.parse(node.textContent||'{}');const arr=Array.isArray(data)?data:[data];
   for(const x of arr){if(!String(x?.['@type']||'').toLowerCase().includes('product'))continue;const offers=Array.isArray(x.offers)?x.offers[0]:x.offers;const n=Number(offers?.price);if(n>100&&n<20000000)structuredPrice=n;}
 }catch{}
 const main=document.querySelector('main,[role="main"],[data-widget="webProductHeading"]');
 return {url:location.href,title:productTitle,documentTitle:document.title,text,links,priceBlocks:blocks,structuredPrice,mainText:(main?.innerText||text).slice(0,3500)};
}
async function read(url,scroll){const id=await tabFor(url);if(scroll){await chrome.scripting.executeScript({target:{tabId:id},func:()=>scrollTo(0,document.body.scrollHeight*.55)});await sleep(700);}const data=await chrome.scripting.executeScript({target:{tabId:id},func:snapshot});const o=data?.[0]?.result;if(!o)throw new Error('Не удалось прочитать данные со страницы');return o;}
function blocked(snap){const s=(snap.text||'').slice(0,3800).toLowerCase();return ['подозрительная активность','captcha-support@','похоже, нет соединения','докажите, что вы не робот','access denied','вы не робот'].some(x=>s.includes(x));}
function searchQueries(query){
 const q=[query];
 // Alternate manufacturer code for the strict Mini 3 Pro family. Other models are not mixed.
 if(/(?:мини|mini)\s*3\s*(?:про|pro)/i.test(query)&&/яндекс|станци/i.test(query))q.push('YNDX-00059');
 return q;
}
async function scan(query,market){
 if(!MARKET[market])return {state:'error',offers:[],diagnostic:'Неизвестная площадка'};
 const result={state:'ok',offers:[],diagnostic:''};
 const deadline=Date.now()+90000;
 const candidateByURL=new Map();let inaccessible=0,searchPages=0;
 for(const q of searchQueries(query)){
  if(Date.now()>deadline-18000)break;
  let listing;
  try{listing=await read(MARKET[market].url(q),true);}catch(e){inaccessible++;continue;}
  if(blocked(listing)){inaccessible++;continue;}
  searchPages++;
  for(const a of listing.links){
   if(!productURL(market,a.url))continue;
   const title=(a.title+' '+a.context).trim();const score=scoreMatch(query,title);
   // For manufacturer-code searches some cards show only an SKU and no model name.
   const compatibleSku=/YNDX[-\s]?00059[A-Z0-9]*/i.test(title)&&/мини\s*3\s*про|mini\s*3\s*pro/i.test(query);
   if(score<72&&!compatibleSku)continue;
   const url=canonical(a.url),prev=candidateByURL.get(url);
   if(!prev||prev.score<score)candidateByURL.set(url,{url,title:a.title||a.context,score:compatibleSku?90:score});
  }
  if(candidateByURL.size>=15)break;
 }
 if(searchPages===0)return {state:'blocked',offers:[],diagnostic:'Магазин не открыл поисковую выдачу. Проверьте сайт в обычной вкладке.'};
 const candidates=[...candidateByURL.values()].sort((a,b)=>b.score-a.score).slice(0,13);
 if(!candidates.length)return {state:'no_matches',offers:[],diagnostic:'Не обнаружены карточки точной модели в просмотренных результатах. Полнота поиска не подтверждена.'};
 let blockedCount=0,readCount=0,wrongModel=0;
 for(const c of candidates){
  if(Date.now()>deadline-16000)break;
  let card;try{card=await read(c.url,false)}catch{blockedCount++;continue;}
  if(blocked(card)){blockedCount++;continue;}
  readCount++;
  const modelMatch=scoreMatch(query,card.title)>=72;
  const skuMatch=/YNDX[-\s]?00059/i.test(card.title)&&/мини\s*3\s*про|mini\s*3\s*pro/i.test(query);
  if(!modelMatch&&!skuMatch){wrongModel++;continue;}
  for(const p of variants(card)){
   result.offers.push({title:card.title,price:p.price,condition:p.condition,variant:p.kind,observed:true,verified:false,url:canonical(card.url),source:p.source});
  }
 }
 result.diagnostic=`Просмотрено карточек: ${readCount}; кандидатов: ${candidates.length}; ошибочных моделей: ${wrongModel}; недоступных: ${blockedCount}.`;
 if(inaccessible)result.diagnostic+=` Не открылось поисковых запросов: ${inaccessible}.`;
 if(!result.offers.length)result.state=blockedCount&&readCount===0?'blocked':'no_matches';
 else if(inaccessible||blockedCount)result.state='partial';
 return result;
}
chrome.runtime.onMessage.addListener((message,sender,sendResponse)=>{
 if(message?.type!=='SCAN')return false;
 // Only accept requests from our published page bridge, never from marketplace tabs.
 if(!sender.tab||!(sender.tab.url?.startsWith('https://nkuchenov-hash.github.io/MarketRadar/')||sender.tab.url?.startsWith('https://raw.githack.com/nkuchenov-hash/MarketRadar/main/live-preview/'))){sendResponse({state:'error',offers:[],diagnostic:'Недопустимый источник'});return true;}
 const q=String(message.query||'').trim().slice(0,160),m=message.market;
 if(q.length<3||!MARKET[m]){sendResponse({state:'error',offers:[],diagnostic:'Неверный поисковый запрос'});return true;}
 scan(q,m).then(sendResponse).catch(e=>sendResponse({state:'error',offers:[],diagnostic:String(e).slice(0,180)}));return true;
});
