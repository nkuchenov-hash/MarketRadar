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
function priceFragments(text){const found=[];for(const match of String(text).matchAll(/(?:\d{1,3}(?:[\s\u00a0\u202f]\d{3})+|\d{4,7})\s*(?:₽|руб(?:\.|лей)?)/gi)){const raw=match[0],value=Number(raw.replace(/\D/g,''));if(value<100||value>20000000)continue;const left=Math.max(0,match.index-55),right=Math.min(text.length,match.index+115);const excerpt=text.slice(left,right).replace(/\s+/g,' ').trim();found.push({price:value,excerpt})}return found;}
function variants(snap){let chunks=(snap.priceFragments||[]).filter(s=>s.length>3&&s.length<600).slice(0,30);let fromWidget=[];for(const f of chunks){const values=priceFragments(f);for(const p of values){if(/(?:старая цена|зачеркнут|было\s|в рассрочку|в месяц|похожие товары)/i.test(p.excerpt))continue;fromWidget.push({...p,source:'price-widget'});}if(fromWidget.length>=6)break;}
 if(!fromWidget.length){const text=(snap.text||'').slice(0,2500);fromWidget=priceFragments(text).slice(0,4).map(p=>({...p,source:'text'}));}
 const seen=new Set();const prices=[];
 for(const p of fromWidget){const nearby=p.excerpt.toLowerCase();let cond='Цена на странице, условия не подтверждены';let kind='unknown';if(/банками|картой|ozon банк|ozon карт|озон банк|пэй|pay/i.test(nearby)){kind='bank';cond='Возможны банковские условия — проверьте в корзине';}else if(/подписк|плюс|premium|prime/i.test(nearby)){kind='subscription';cond='Возможна подписка — проверьте условия';}else if(/другими банками|без подписки|обычная цена/i.test(nearby)){kind='standard';cond='Обычная цена — проверьте в корзине';}
 if(seen.has(p.price))continue;seen.add(p.price);prices.push({price:p.price,condition:cond,kind,source:p.source});if(prices.length>=3)break;}
 return prices;
}
async function loaded(id,timeout=34000){const t=await chrome.tabs.get(id);if(t.status==='complete')return;return new Promise((resolve,reject)=>{const timer=setTimeout(()=>{clean();reject(new Error('Не удалось загрузить страницу'));},timeout);const upd=(tab,info)=>{if(tab===id&&info.status==='complete'){clean();resolve()}};const removed=tab=>{if(tab===id){clean();reject(new Error('Вкладка закрыта'))}};function clean(){clearTimeout(timer);chrome.tabs.onUpdated.removeListener(upd);chrome.tabs.onRemoved.removeListener(removed)}chrome.tabs.onUpdated.addListener(upd);chrome.tabs.onRemoved.addListener(removed);});}
async function tabFor(url){let exists=false;if(tabId!==null)try{await chrome.tabs.get(tabId);exists=true}catch{tabId=null;}if(exists)await chrome.tabs.update(tabId,{url});else{try{const win=await chrome.windows.create({url,focused:false,state:'minimized'});tabId=win.tabs[0].id;}catch{const tab=await chrome.tabs.create({url,active:false});tabId=tab.id;}}
 await loaded(tabId);await sleep(1800);const t=await chrome.tabs.get(tabId);if(!safeURL(t.url||''))throw new Error('Магазин перенаправил на постороннюю страницу');return tabId;}
function snapshot(){const body=document.body;const text=(body?.innerText||'').slice(0,50000);const anchors=Array.from(document.querySelectorAll('a[href]')).slice(0,2200);const links=anchors.map(a=>({url:a.href,title:(a.getAttribute('aria-label')||a.innerText||a.querySelector('img')?.alt||'').replace(/\s+/g,' ').slice(0,250),context:(a.closest('article,[data-widget],li')?.innerText||a.parentElement?.innerText||'').replace(/\s+/g,' ').slice(0,240)}));const pf=Array.from(document.querySelectorAll('[data-widget*="price" i], [class*="price" i], [aria-label*="₽"], [itemprop="price"]')).slice(0,100).map(x=>(x.innerText||x.getAttribute('aria-label')||x.getAttribute('content')||'').replace(/\s+/g,' ').trim()).filter(s=>s.length>1&&s.length<500);
 return {url:location.href,title:document.title,text,links,priceFragments:pf};}
async function read(url,scroll){const id=await tabFor(url);if(scroll){await chrome.scripting.executeScript({target:{tabId:id},func:()=>scrollTo(0,document.body.scrollHeight*.55)});await sleep(700);}const data=await chrome.scripting.executeScript({target:{tabId:id},func:snapshot});const o=data?.[0]?.result;if(!o)throw new Error('Не удалось прочитать данные со страницы');return o;}
function blocked(snap){const s=(snap.text||'').slice(0,3800).toLowerCase();return ['подозрительная активность','captcha-support@','похоже, нет соединения','докажите, что вы не робот','access denied','вы не робот'].some(x=>s.includes(x));}
async function scan(query,market){if(!MARKET[market])return {state:'error',offers:[],diagnostic:'Неизвестная площадка'};
 const result={state:'ok',offers:[],diagnostic:''};let listing;try{listing=await read(MARKET[market].url(query),true);}catch(e){return {state:'error',offers:[],diagnostic:String(e).slice(0,180)}}
 if(blocked(listing))return {state:'blocked',offers:[],diagnostic:'Магазин ограничил автоматический просмотр. Откройте сайт вручную.'};
 const candidateByURL=new Map();for(const a of listing.links){if(!productURL(market,a.url))continue;const t=a.title+' '+a.context;const score=scoreMatch(query,t);if(score<72)continue;const key=canonical(a.url),prev=candidateByURL.get(key);if(!prev||prev.score<score)candidateByURL.set(key,{url:a.url,title:a.title||a.context,score});}
 const candidates=[...candidateByURL.values()].sort((a,b)=>b.score-a.score).slice(0,7);if(!candidates.length)return {state:'no_matches',offers:[],diagnostic:'В поисковой выдаче не обнаружены карточки точной модели'};
 let blockedCount=0,readCount=0;for(const c of candidates){let card;try{card=await read(c.url,false)}catch{continue;}if(blocked(card)){blockedCount++;continue;}readCount++;if(scoreMatch(query,card.title)<72)continue;const pv=variants(card);for(const p of pv){result.offers.push({title:card.title,price:p.price,condition:p.condition,variant:p.kind,verified:false,url:card.url});}}
 result.diagnostic='Проверено карточек: '+readCount+'; подходящих в поиске: '+candidates.length;
 if(blockedCount)result.diagnostic+='; ограничено защитой: '+blockedCount;
 if(!readCount&&blockedCount)result.state='blocked';else if(!result.offers.length)result.state='no_matches';return result;}
chrome.runtime.onMessage.addListener((message,sender,sendResponse)=>{
 if(message?.type!=='SCAN')return false;
 // Only accept requests from our published page bridge, never from marketplace tabs.
 if(!sender.tab||!sender.tab.url?.startsWith('https://nkuchenov-hash.github.io/MarketRadar/')){sendResponse({state:'error',offers:[],diagnostic:'Недопустимый источник'});return true;}
 const q=String(message.query||'').trim().slice(0,160),m=message.market;
 if(q.length<3||!MARKET[m]){sendResponse({state:'error',offers:[],diagnostic:'Неверный поисковый запрос'});return true;}
 scan(q,m).then(sendResponse).catch(e=>sendResponse({state:'error',offers:[],diagnostic:String(e).slice(0,180)}));return true;
});
