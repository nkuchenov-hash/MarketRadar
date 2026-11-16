(()=>{
'use strict';
const $=id=>document.getElementById(id);
const markets=[['ozon','Ozon'],['wb','Wildberries'],['ym','Яндекс Маркет']];
let connected=false,working=false,requestId=0,offers=[],states={},activeQuery="";
let toastTimer=null;
const norm=s=>String(s||'').toLowerCase().replace(/ё/g,'е').replace(/\bmini\b/g,'мини').replace(/\bpro\b/g,'про').replace(/[^\p{L}\p{N}]+/gu,' ').trim().replace(/\s+/g,' ');
const accessory=/(?:^|\s)(?:климатическ[\p{L}]*|модул[\p{L}]*|датчик[\p{L}]*|чехол[\p{L}]*|креплен[\p{L}]*|кронштейн[\p{L}]*|держател[\p{L}]*|адаптер[\p{L}]*|кабел[\p{L}]*|насадк[\p{L}]*|подставк[\p{L}]*|корпус[\p{L}]*|аккумулятор[\p{L}]*|запчаст[\p{L}]*|зарядн[\p{L}]*|футляр[\p{L}]*|наклейк[\p{L}]*|пульт[\p{L}]*)(?:\s|$)/u;
const ignored=new Set(['умная','умный','колонка','станция','яндекс','алиса','для','с','и','на','в','серый','черный','белый','синий','красный','зеленый','цвет','bluetooth','zigbee','вт','беспроводная','новый','оригинал']);
function exactMatch(query,title){
 const q=norm(query),t=norm(title);if(!q||!t)return false;
 // Accessories may name a compatible device in full: they are not the device itself.
 if(accessory.test(t)&&!accessory.test(q))return false;
 const targetMini=/(?:^|\s)мини\s*3\s*про(?:\s|$)/u.test(q)||/yndx\s*00059\b/u.test(q);
 if(targetMini){
   if(/yndx\s*00652|(?:^|\s)мини\s*3\s*(?:лайт|lite)(?:\s|$)/u.test(t))return false;
   return /(?:^|\s)мини\s*3\s*про(?:\s|$)/u.test(t)||/yndx\s*00059(?:blk|blu|gry|grn)?\b/u.test(t);
 }
 const qt=q.split(' ').filter(w=>w.length>=3||/\d/.test(w)).filter(w=>!ignored.has(w));
 if(!qt.length)return t.includes(q);
 const tt=new Set(t.split(' '));
 if(qt.some(x=>/\d/.test(x)&&!tt.has(x)))return false;
 return qt.filter(x=>tt.has(x)).length/qt.length>=0.85;
}
function safeURL(url){try{const u=new URL(url);return u.protocol==='https:'&&['ozon.ru','www.ozon.ru','www.wildberries.ru','wildberries.ru','market.yandex.ru'].includes(u.hostname)?u.href:null;}catch{return null;}}
function canonical(url){try{const u=new URL(url);u.search='';u.hash='';return u.href;}catch{return String(url||'');}}
function rub(n){return Number(n).toLocaleString('ru-RU')+' ₽';}
function txt(el,val){el.textContent=String(val??'');}
function notify(msg){const t=$('toast');txt(t,msg);t.hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>t.hidden=true,6000);}
function connect(yes){connected=!!yes;txt($('connection'),connected?'Расширение подключено':'Нужно расширение');$('connection').classList.toggle('connected',connected);$('setup').hidden=connected;$('go').title=connected?'Начать поиск':'Для поиска установите расширение';}
function send(type,extra){window.postMessage({__marketRadarPage:true,type,...extra},window.location.origin);}
function marketStatus(m,state,detail,amount){txt($('status-'+m),state);txt($('detail-'+m),detail);txt($('count-'+m),amount===null?'—':amount+' предложений');}
function kind(o){return ['bank','other-banks','subscription','unverified','standard'].includes(o.variant)?o.variant:'unverified';}
function offerIdentity(o){return [o.market,canonical(o.url),o.price,kind(o)].join('|');}
function sameListing(o){return [o.market,norm(o.title),o.price,kind(o)].join('|');}
function uniq(all){
 const identities=new Set(),listings=new Set(),out=[];
 for(const o of all){
  if(!safeURL(o.url)||!Number.isFinite(o.price)||o.price<=0||!exactMatch(activeQuery||$('query').value,o.title))continue;
  const key=offerIdentity(o),listing=sameListing(o);
  if(identities.has(key)||listings.has(listing))continue;
  identities.add(key);listings.add(listing);out.push(o);
 }
 return out;
}
function deliveryLabel(o){return o.delivery?.label||o.deliveryOptions?.[0]?.label||'Не определён';}
function deliveryChoices(o){const list=Array.isArray(o.deliveryOptions)&&o.deliveryOptions.length?o.deliveryOptions:o.delivery?[o.delivery]:[];return [...new Set(list.map(d=>(d.method?d.method+' — ':'')+d.label))].slice(0,4);}
function rankDelivery(o){if(Number.isInteger(o.delivery?.days)&&o.delivery.days>=0)return o.delivery.days;return 99999;}
function knownDelivery(o){return typeof o.delivery?.label==='string'&&o.delivery.label.length>0;}
function selection(){const cond=$('payment-filter').value,shop=$('market-filter').value,sort=$('sort-filter').value;
 let result=uniq(offers);
 if(cond==='standard')result=result.filter(o=>kind(o)==='standard');
 if(cond==='other-banks')result=result.filter(o=>kind(o)==='other-banks');
 if(cond==='unverified')result=result.filter(o=>kind(o)==='unverified');
 if(cond==='bank')result=result.filter(o=>kind(o)==='bank');
 if(cond==='no-bank')result=result.filter(o=>kind(o)==='standard');
 if(shop!=='all')result=result.filter(o=>o.market===shop);
 if($('known-delivery').checked)result=result.filter(knownDelivery);
 return result.sort((a,b)=>sort==='delivery'?(rankDelivery(a)-rankDelivery(b)||a.price-b.price):sort==='market'?(a.market.localeCompare(b.market)||a.price-b.price):(a.price-b.price));
}
function priceConditions(o){if(kind(o)==='bank')return 'Цена с банковскими условиями';if(kind(o)==='other-banks')return 'Цена с другими банками';if(kind(o)==='subscription')return 'Цена с подпиской';if(kind(o)==='standard')return 'Обычная цена';return 'Условия цены не подтверждены';}
function card(o){
 const art=document.createElement('article');art.className='offer';
 const left=document.createElement('div');left.className='offer-main';
 const title=document.createElement('h4');txt(title,o.title);left.appendChild(title);
 const labels=document.createElement('div');labels.className='offer-tags';
 for(const value of [markets.find(x=>x[0]===o.market)?.[1]||o.market,priceConditions(o)]){const el=document.createElement('span');txt(el,value);if(kind(o)==='bank')el.className='tag-bank';labels.appendChild(el);}left.appendChild(labels);
 const shipping=document.createElement('p');shipping.className='shipping';txt(shipping,deliveryChoices(o).length?'Варианты доставки/получения: '+deliveryChoices(o).join(' · '):'Доставка: срок не получен');left.appendChild(shipping);
 const note=document.createElement('p');note.className='offer-note';txt(note,'Цена считана со страницы; условия и итог в корзине не подтверждены.');left.appendChild(note);
 const right=document.createElement('div');right.className='offer-price';const price=document.createElement('strong');txt(price,rub(o.price));right.appendChild(price);
 const url=safeURL(o.url);if(url){const link=document.createElement('a');link.href=url;link.target='_blank';link.rel='noopener noreferrer';link.textContent='Открыть предложение →';right.appendChild(link);}
 art.append(left,right);return art;
}
function summaryItem(label,amount,desc){const a=document.createElement('div');a.className='summary-item';const h=document.createElement('span');txt(h,label);const p=document.createElement('strong');txt(p,amount);const n=document.createElement('small');txt(n,desc);a.append(h,p,n);return a;}
function render(){
 const unique=uniq(offers),visible=selection(),container=$('offers');container.replaceChildren();
 for(const o of visible)container.appendChild(card(o));
 if(!visible.length){const blank=document.createElement('div');blank.className='empty';txt(blank,offers.length?'По выбранным критериям совпадений нет. Измените фильтры.':'Точных предложений пока нет.');container.appendChild(blank);}
 txt($('total'),`${visible.length} из ${unique.length} уникальных предложений`);
 const sum=$('summary');sum.replaceChildren();
 const all=unique.slice().sort((a,b)=>a.price-b.price),otherBanks=all.filter(o=>kind(o)==='other-banks'),fast=all.filter(o=>rankDelivery(o)!==99999).sort((a,b)=>rankDelivery(a)-rankDelivery(b)||a.price-b.price);
 if(all.length){const o=all[0];sum.appendChild(summaryItem('Минимальная найденная цена',rub(o.price),`${markets.find(x=>x[0]===o.market)?.[1]||''} · ${priceConditions(o).toLowerCase()}`));}
 else sum.appendChild(summaryItem('Минимальная найденная цена','—','Точные предложения не обнаружены'));
 if(otherBanks.length){const o=otherBanks[0];sum.appendChild(summaryItem('Цена с другими банками',rub(o.price),`${markets.find(x=>x[0]===o.market)?.[1]||''} · условия в корзине не проверены`));}
 else sum.appendChild(summaryItem('Цена с другими банками','—','Такая цена не найдена')); 
 if(fast.length){const o=fast[0];sum.appendChild(summaryItem('Самое быстрое указанное получение',deliveryLabel(o),`${markets.find(x=>x[0]===o.market)?.[1]||''} · возможные варианты: ${deliveryChoices(o).join('; ')} · без подтверждения в корзине`));}
 else sum.appendChild(summaryItem('Сроки доставки','Не получены','Не подставляем предполагаемые даты'));
 for(const [m] of markets){
  const cheapest=all.filter(o=>o.market===m)[0],target=$('best-'+m);
  if(cheapest)txt(target,`От ${rub(cheapest.price)} · ${priceConditions(cheapest).toLowerCase()}`);
  else txt(target,'Подтверждённых цен нет');
 }
 const blocked=Object.values(states).filter(s=>s!=='ok').length;
 txt($('analysis-note'),unique.length?`Проверено уникальных предложений: ${unique.length}. ${blocked?'Охват площадок неполный; ':''}Минимум рынка не гарантирован. Цены не подтверждены в корзине.`:'Поиск ещё не дал подтверждённых совпадений. Отсутствие предложения не означает отсутствия товара.');
}
function progress(){const vals=Object.values(states);if(vals.length===3&&vals.every(x=>x!=='pending')){txt($('scan-state'),'Поиск завершён · результат не гарантирует минимум');}}
window.addEventListener('message',e=>{
 if(e.source!==window||e.origin!==window.location.origin||!e.data?.__marketRadarExtension)return;
 const d=e.data;
 if(d.type==='MR_CONNECTED')connect(true);
 if(d.type==='MR_RESULT'&&d.id===requestId){
  const m=d.market,result=d.result||{},incoming=Array.isArray(result.offers)?result.offers:[],accepted=incoming.filter(o=>safeURL(o.url)&&Number.isFinite(o.price)&&o.price>0&&exactMatch(activeQuery||$('query').value,o.title)).map(o=>({...o,market:m}));
  const rejected=incoming.length-accepted.length;offers.push(...accepted);const state=result.state||'error';states[m]=state;
  const label=state==='ok'?'Просмотрено':state==='partial'?'Частично проверено':state==='blocked'?'Доступ ограничен':state==='no_matches'?'Совпадений нет':'Ошибка';
  const detail=(result.diagnostic||'')+(rejected?` · Исключено неверных моделей: ${rejected}`:'');
  marketStatus(m,label,detail,uniq(accepted).length);render();progress();
 }
});
for(const id of ['payment-filter','sort-filter','market-filter','known-delivery'])$(id).addEventListener('change',render);
$('recheck').addEventListener('click',()=>{send('MR_HELLO');notify('Проверяем подключение. Если расширение установлено — обновите страницу.');});
send('MR_HELLO');setTimeout(()=>{if(!connected){txt($('connection'),'Расширение не найдено');$('setup').hidden=false;}},1400);
$('search-form').addEventListener('submit',async e=>{
 e.preventDefault();if(working)return;
 if(!connected){$('setup').hidden=false;$('setup').scrollIntoView({behavior:'smooth'});notify('Установите расширение, чтобы запустить поиск.');return;}
 const query=$('query').value.trim();if(query.length<3)return;
 working=true;activeQuery=query;requestId++;const id=requestId;offers=[];states={ozon:'pending',wb:'pending',ym:'pending'};
 $('results').hidden=false;txt($('result-title'),query);txt($('scan-state'),'Поиск в браузере');$('go').disabled=true;
 for(const [m]of markets)marketStatus(m,'В очереди','Ожидание',null);
 render();$('results').scrollIntoView({behavior:'smooth'});
 for(const [market] of markets){
  marketStatus(market,'Проверяем','Поиск и просмотр карточек',null);send('MR_SCAN',{id,query,market});
  await new Promise(resolve=>{
   const timer=setTimeout(()=>{window.removeEventListener('message',handler);states[market]='timeout';marketStatus(market,'Не ответил','Время ожидания истекло',null);render();resolve();},110000);
   function handler(ev){if(ev.source===window&&ev.origin===window.location.origin&&ev.data?.__marketRadarExtension&&ev.data.type==='MR_RESULT'&&ev.data.id===id&&ev.data.market===market){clearTimeout(timer);window.removeEventListener('message',handler);resolve();}}
   window.addEventListener('message',handler);
  });
 }
 working=false;$('go').disabled=false;progress();
});
if(typeof window!=='undefined')window.MarketRadarTest={exactMatch,uniq};
})();
