(()=>{
'use strict';
const $=id=>document.getElementById(id);
const markets=[['ozon','Ozon'],['wb','Wildberries'],['ym','Яндекс Маркет']];
const rub=n=>new Intl.NumberFormat('ru-RU').format(n)+' ₽';
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const stored=(key,fallback)=>{try{return localStorage.getItem(key)??fallback}catch{return fallback}};
const store=(key,v)=>{try{localStorage.setItem(key,v)}catch{}};
let accessKey='',jobId=null,job=null,timer=null,pendingSearch=false,starting=false,generation=0,activeQuery='',expanded=false,activeShops=[],retainedOffers=[],retainedMarkets={},running=false;
const defaults={maxDays:3,allowLate:false,discount:20,shops:['ozon','wb','ym']};
const bounded=(v,min,max,fallback)=>Number.isFinite(Number(v))&&String(v).trim()!==''?Math.min(max,Math.max(min,Math.trunc(Number(v)))):fallback;
function readPolicy(){try{const p=JSON.parse(stored('mr_policy','{}'));return {maxDays:bounded(p.maxDays,0,90,3),allowLate:!!p.allowLate,discount:bounded(p.discount,1,90,20),shops:Array.isArray(p.shops)&&p.shops.some(x=>defaults.shops.includes(x))?defaults.shops.filter(x=>p.shops.includes(x)):defaults.shops.slice()}}catch{return {...defaults,shops:defaults.shops.slice()}}}
let policy=readPolicy(),activePolicy={...policy},scanDate=new Date().toISOString();
const shopIds={ozon:'scanOzon',wb:'scanWb',ym:'scanYm'};
function deadline(p,base=new Date()){const d=new Date(base);d.setDate(d.getDate()+p.maxDays);return 'Доставка до '+d.toLocaleDateString('ru-RU',{day:'numeric',month:'long'})+' · '+p.maxDays+' дн.'}
function fillSettings(){for(const [m,id] of Object.entries(shopIds))$(id).checked=policy.shops.includes(m);$('maxDays').value=policy.maxDays;$('allowLate').checked=policy.allowLate;$('discount').value=policy.discount;previewDeadline();}
function previewDeadline(){$('discount').disabled=!$('allowLate').checked;$('deadlinePreview').textContent=deadline({maxDays:bounded($('maxDays').value,0,90,3)});}
function updateDeadline(){$('deadlineLabel').textContent=deadline(policy);}
function openSettings(){fillSettings();$('settings').showModal();}

try{accessKey=sessionStorage.getItem('mr_access_key')||''}catch{}
const sessionKey=v=>{accessKey=v;try{if(v)sessionStorage.setItem('mr_access_key',v);else sessionStorage.removeItem('mr_access_key')}catch{}};
const states={pending:'Ожидание',searching:'Ищем',checking:'Проверяем',complete:'Завершено',partial:'Частично',blocked:'Доступ ограничен',error:'Ошибка',empty:'Нет результатов',ok:'Завершено'};
function msg(text){$('searchError').textContent=text||'';$('searchError').hidden=!text}
async function api(path,method='GET',body){
 const r=await fetch(path,{method,credentials:'omit',cache:'no-store',headers:{'Content-Type':'application/json',...(accessKey&&path.startsWith('/api/mobile/browser/')?{'X-MarketRadar-Key':accessKey}:{})},...(body?{body:JSON.stringify(body)}:{})});
 let data={};try{data=await r.json()}catch{}
 if(!r.ok){const e=Error(typeof data.detail==='string'?data.detail:'Ошибка HTTP '+r.status);e.status=r.status;throw e}return data;
}
const systemTheme=matchMedia('(prefers-color-scheme: dark)');
function setTheme(v){const value=['system','dark','light'].includes(v)?v:'system';document.documentElement.dataset.theme=value==='system'?(systemTheme.matches?'dark':'light'):value;$('theme').value=value;store('mr_theme',value)}
systemTheme.addEventListener('change',()=>{if($('theme').value==='system')setTheme('system')});
setTheme(stored('mr_theme','system'));updateDeadline();fillSettings();$('consent').checked=stored('mr_consent','yes')==='yes';
$('theme').onchange=()=>setTheme($('theme').value);
$('settingsButton').onclick=openSettings;$('editDeadline').onclick=openSettings;$('maxDays').oninput=previewDeadline;$('allowLate').onchange=previewDeadline;
$('closeSettings').onclick=()=>$('settings').close();
$('saveSettings').onclick=()=>{const shops=Object.keys(shopIds).filter(m=>$(shopIds[m]).checked);if(!shops.length){alert('Выберите хотя бы один магазин');return}if(!$('maxDays').reportValidity()||!$('discount').reportValidity())return;policy={maxDays:bounded($('maxDays').value,0,90,3),allowLate:$('allowLate').checked,discount:bounded($('discount').value,1,90,20),shops};store('mr_policy',JSON.stringify(policy));store('mr_consent',$('consent').checked?'yes':'no');updateDeadline();$('settings').close()};
function showAuth(){if(!$('auth').open)$('auth').showModal();$('accessKey').value=accessKey;$('authError').textContent=''}
$('closeAuth').onclick=()=>{pendingSearch=false;$('auth').close()};
$('auth').addEventListener('cancel',()=>{pendingSearch=false});
function setAccount(){ $('accountButton').textContent=accessKey?'Код доступа ✓':'Код доступа';}
$('authForm').onsubmit=async e=>{e.preventDefault();const key=$('accessKey').value.trim();if(!key)return;sessionKey(key);setAccount();$('auth').close();$('accessKey').value='';if(pendingSearch){pendingSearch=false;await startSearch()}else msg('Код сохранён для этой вкладки. Проверим при поиске.')};
$('forgetKey').onclick=()=>{sessionKey('');setAccount();$('accessKey').value='';pendingSearch=false;$('auth').close();msg('Код доступа удалён.')};
$('accountButton').onclick=showAuth;
function view(name){for(const v of ['search','history','shops']){$(v+'View').hidden=v!==name;$(v+'Tab').classList.toggle('active',v===name)}if(name==='shops')renderShops()}
$('searchTab').onclick=()=>view('search');
$('historyTab').onclick=()=>{view('history');loadHistory()};$('shopsTab').onclick=()=>view('shops');
function historyRows(){try{const rows=JSON.parse(stored('mr_local_history_v22','[]'));return Array.isArray(rows)?rows:[]}catch{return []}}
function saveHistory(){if(!job||!jobId)return;const rows=historyRows().filter(x=>x.id!==jobId);const old=historyRows().find(x=>x.id===jobId);rows.unshift({id:jobId,query:activeQuery,created:old?.created||Date.now(),policy:activePolicy,shops:activeShops,scanDate,result:{...job,offers:(job.offers||[]).slice(0,100)}});store('mr_local_history_v22',JSON.stringify(rows.slice(0,15)))}
function loadHistory(){const rows=historyRows();$('history').innerHTML=rows.map(j=>'<button class="historyrow" data-id="'+esc(j.id)+'"><strong>'+esc(j.query)+'</strong><span>'+esc(new Date(j.created).toLocaleDateString('ru-RU'))+'</span></button>').join('')||'<div class="empty">История в этом браузере пока пуста.</div>';for(const el of $('history').querySelectorAll('button'))el.onclick=()=>{const row=rows.find(x=>x.id===el.dataset.id);if(!row)return;view('search');$('query').value=row.query;activeQuery=row.query;clearTimeout(timer);generation++;jobId=row.id;job=row.result;activePolicy=row.policy||readPolicy();activeShops=row.shops||Object.keys(job.markets||{});scanDate=row.scanDate||new Date(row.created).toISOString();retainedOffers=[];retainedMarkets={};running=job.state==='running';expanded=false;updateSearchButton();$('results').hidden=false;if(job.state==='running'&&accessKey){poll(generation)}else{render();if(job.state==='running')$('jobState').textContent='Последний сохранённый результат'}}}
$('searchForm').onsubmit=e=>{e.preventDefault();if(running)stopSearch();else startSearch()};
function updateSearchButton(){$('searchButton').textContent=running?'Остановить':'Найти';}
async function stopSearch(){if(!jobId||starting)return;try{await api('/api/mobile/browser/jobs/'+encodeURIComponent(jobId),'DELETE');clearTimeout(timer);generation++;running=false;job={...(job||{}),query:activeQuery,state:'cancelled'};render();saveHistory();updateSearchButton()}catch(e){msg(e.message)}}
async function startSearch(retry=null){
 if(starting)return;msg('');
 const q=retry?activeQuery:$('query').value.trim();if(q.length<3)return;
 if(!accessKey){pendingSearch=true;showAuth();return}
 if(!$('consent').checked){msg('Разрешите обработку сведений о товарах в настройках.');openSettings();return}
 starting=true;$('searchButton').disabled=true;
 try{const shops=retry||policy.shops;const x=await api('/api/mobile/browser/start','POST',{query:q,shops});if(!retry){activePolicy={...policy};activeShops=shops.slice();scanDate=new Date().toISOString();retainedOffers=[];retainedMarkets={};expanded=false;}else{retainedOffers=(job?.offers||[]).filter(o=>!shops.includes(shop(o)));retainedMarkets=Object.fromEntries(Object.entries(job?.markets||{}).filter(([id])=>!shops.includes(id)));}activeQuery=q;view('search');openJob(x.id)}
 catch(e){msg(e.message);if(e.status===401){sessionKey('');setAccount();pendingSearch=true;showAuth();$('authError').textContent=e.message}}
 finally{starting=false;$('searchButton').disabled=false}
}
function openJob(id){clearTimeout(timer);jobId=id;job=null;running=true;updateSearchButton();generation++;$('results').hidden=false;$('jobState').textContent='Ищем предложения';$('resultsHeading').textContent=$('query').value.trim()||'Результаты';$('offers').innerHTML='<div class="empty">Поиск выполняется...</div>';$('marketStatuses').innerHTML='';$('count').textContent='';poll(generation)}
async function poll(g){try{const data=await api('/api/mobile/browser/jobs/'+encodeURIComponent(jobId));if(g!==generation)return;job={...data,query:activeQuery,offers:[...retainedOffers,...(data.offers||[])],markets:{...retainedMarkets,...data.markets}};running=job.state==='running';updateSearchButton();render();saveHistory();if(running)timer=setTimeout(()=>poll(g),1500)}catch(e){if(g!==generation)return;$('jobState').textContent=e.message;running=false;updateSearchButton()}}
function link(url){try{const u=new URL(url);return u.protocol==='https:'&&markets.some(([m])=>m==='ozon'?/(^|\.)ozon\.ru$/.test(u.hostname):m==='wb'?/(^|\.)wildberries\.ru$/.test(u.hostname):u.hostname==='market.yandex.ru')?u.href:''}catch{return ''}}
function image(url){try{const u=new URL(url);return u.protocol==='https:'?u.href:''}catch{return ''}}
function shop(o){return o.market_id||markets.find(x=>x[1]===o.market)?.[0]||''}
function days(o){return Number.isInteger(o.delivery_days)&&o.delivery_days>=0?o.delivery_days:9999}
function offersOf(j){return (j.offers||[]).filter(o=>link(o.url)&&Number.isFinite(o.price)&&o.price>0).map(o=>({...o,market_id:shop(o),verified_detail:!!o.verified_detail}))}
function img(o,cls){const u=image(o.image_url);return u?'<img class="'+cls+'" src="'+esc(u)+'" alt="" loading="lazy" referrerpolicy="no-referrer">':''}
function deliveryText(o){const n=days(o);if(n===9999)return 'Дата доставки неизвестна';const d=new Date(scanDate);d.setDate(d.getDate()+n);return 'Доставка '+d.toLocaleDateString('ru-RU',{day:'numeric',month:'long'})+(n>activePolicy.maxDays?' · позже срока':'')}
function displayKey(o){return [shop(o),o.price,days(o),o.condition||'',String(o.title||'').toLowerCase().replace(/ё/g,'е').replace(/\s+(?:купить\s+за|в\s+интернет-магазине).*$/iu,'').replace(/\b\d{8,}\b/g,' ').replace(/[^\p{L}\p{N}]+/gu,' ').trim()].join('|')}
function chooseOffers(all,p,more,deliveryFirst=false){
 const sorted=all.slice().sort((a,b)=>a.price-b.price||days(a)-days(b)||Number(b.verified_detail)-Number(a.verified_detail));
 const timely=sorted.filter(o=>days(o)<=p.maxDays),best=timely[0]?.price||0,confirmedBest=timely.find(o=>o.verified_detail)?.price||0;
 const visible=[],seen=new Set(),shops=new Set(),extras={};
 function add(o){const k=displayKey(o);if(seen.has(k))return false;seen.add(k);visible.push(o);return true}
 for(const o of timely)if(!shops.has(shop(o))){shops.add(shop(o));add(o)}
 if(more&&best)for(const o of timely){if(visible.length>=12)break;if(o.price*100>best*105)break;if((extras[shop(o)]||0)>=2)continue;if(add(o))extras[shop(o)]=(extras[shop(o)]||0)+1}
 const late=[];if(p.allowLate&&confirmedBest)for(const o of sorted){if(late.length>=2)break;if(!o.verified_detail||days(o)<=p.maxDays||days(o)===9999||o.price*100>confirmedBest*(100-p.discount))continue;if(add(o))late.push(o)}
 // An absolute minimum outside the delivery window is not a recommended offer.
 const absolute=sorted[0];const outside=absolute&&days(absolute)>p.maxDays&&!seen.has(displayKey(absolute))?absolute:null;
 visible.sort((a,b)=>Number(days(a)>p.maxDays)-Number(days(b)>p.maxDays)||(deliveryFirst?days(a)-days(b)||a.price-b.price:a.price-b.price||days(a)-days(b)));
 return {visible,best:visible.filter(o=>days(o)<=p.maxDays||late.includes(o)).sort((a,b)=>a.price-b.price)[0],outside};
}
function renderCards(filtered){return filtered.map(o=>'<article class="offer">'+(img(o,'product-image')||'<div class="product-image blank">Фото</div>')+'<div class="offer-main"><div class="offer-meta">'+esc(markets.find(x=>x[0]===o.market_id)?.[1]||o.market)+'</div><h3>'+esc(o.title)+'</h3><div class="offer-meta">'+esc(deliveryText(o))+' · '+(o.verified_detail?'Цена подтверждена':'Цена из выдачи')+'</div></div><div class="offer-price"><strong>'+rub(o.price)+'</strong><small>'+esc(o.condition||'')+'</small><a href="'+esc(link(o.url))+'" target="_blank" rel="noopener noreferrer">В магазин ↗</a></div></article>').join('')||'<div class="empty">'+(job.state==='running'?'Поиск выполняется…':'Подходящих предложений нет.')+'</div>';}
function render(){
 if(!job)return;
 $('resultsHeading').textContent=job.query;$('jobState').textContent=job.state==='running'?'Поиск выполняется':job.state==='finished'?'Поиск завершён':job.state==='cancelled'?'Поиск остановлен':job.error||'Поиск завершён с ошибкой';
 const all=offersOf(job).sort((a,b)=>a.price-b.price);const status=job.markets||{};
 const summary=chooseOffers(all,activePolicy,false);
 $('bestSummary').hidden=false;$('bestSummary').innerHTML='<span class="muted">Лучшая найденная цена · '+esc(deadline(activePolicy,new Date(scanDate)))+'</span><strong>'+ (summary.best?rub(summary.best.price):'Пока не найдено')+'</strong>'+(summary.best?'<span class="muted">'+esc(markets.find(x=>x[0]===shop(summary.best))?.[1]||'')+' · '+esc(deliveryText(summary.best))+' · '+(summary.best.verified_detail?'Цена подтверждена':'Цена из выдачи, требует проверки')+'</span>':'');
 $('marketStatuses').innerHTML=markets.filter(([id])=>activeShops.includes(id)||id in status).map(([id,name])=>{
 const group=summary.visible.filter(o=>shop(o)===id&&days(o)<=activePolicy.maxDays),best=group[0],m=status[id]||{};
 return '<article class="market-card"><div class="market-top"><strong>'+name+'</strong><span class="muted">'+esc(states[m.state]||'Ожидание')+'</span></div>'+(best?img(best,'')+'<div class="market-price">'+rub(best.price)+'</div><div class="market-detail">'+esc(deliveryText(best))+'</div><div class="market-detail">'+(best.verified_detail?'Подтверждено':'Цена из выдачи')+'</div><a href="'+esc(link(best.url))+'" target="_blank" rel="noopener noreferrer">Открыть товар ↗</a>':'<div class="market-price">—</div><div class="market-detail">'+(['pending','searching','checking'].includes(m.state)||!m.state&&running?'Ищем предложения…':'Нет подходящего предложения')+'</div>')+'</article>';
 }).join('');
 const filtered=all.filter(o=>($('marketFilter').value==='all'||shop(o)===$('marketFilter').value)&&(!$('verifiedFilter').checked||o.verified_detail));
 const selected=chooseOffers(filtered,activePolicy,expanded,$('sortFilter').value==='delivery'),compact=chooseOffers(filtered,activePolicy,false),full=chooseOffers(filtered,activePolicy,true);
 const timely=selected.visible.filter(o=>days(o)<=activePolicy.maxDays),late=selected.visible.filter(o=>days(o)>activePolicy.maxDays);
 $('offersHeading').textContent=expanded?'Выгодные варианты':'Лучшие по площадкам';$('count').textContent=selected.visible.length+' предлож.';
 $('offers').innerHTML=(timely.length?renderCards(timely):'<div class="empty">'+(running?'Поиск выполняется…':'Нет предложений с подходящим сроком.')+'</div>')+(late.length?'<h3 class="group-title">Позже, но дешевле</h3>'+renderCards(late):'')+(selected.outside?'<h3 class="group-title">Минимальная цена вне условий поиска</h3>'+renderCards([selected.outside]):'');
 const extra=full.visible.length-compact.visible.length;$('moreOffers').hidden=extra<=0;$('moreOffers').textContent=expanded?'Скрыть дополнительные предложения':'Показать выгодные варианты · '+extra;
 renderShops();
}
function missingShops(){return activeShops.filter(id=>!offersOf(job||{}).some(o=>shop(o)===id&&days(o)<=activePolicy.maxDays&&o.verified_detail))}
function renderShops(){const status=job?.markets||{};const homes={ozon:'https://www.ozon.ru/',wb:'https://www.wildberries.ru/',ym:'https://market.yandex.ru/'};
 $('shopDetails').innerHTML=markets.map(([id,name])=>'<article class="historyrow"><div><strong>'+name+'</strong><p class="muted">'+esc(states[status[id]?.state]||'Поиск не запускался')+' · '+esc(status[id]?.message||'Публичный поиск')+'</p></div><a href="'+homes[id]+'" target="_blank" rel="noopener noreferrer">Открыть</a></article>').join('');
 $('retryMissing').hidden=!job||running||!missingShops().length;
}
$('moreOffers').onclick=()=>{expanded=!expanded;render()};
$('retryMissing').onclick=()=>{if(!running&&missingShops().length)startSearch(missingShops())};
$('showReport').onclick=()=>{if(!job){alert('Поиск ещё не запускался');return}const report=JSON.stringify({query:activeQuery,policy:activePolicy,markets:job.markets,errors:job.errors},null,2);const url=URL.createObjectURL(new Blob([report],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download='marketradar-search-report.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)};

for(const id of ['marketFilter','sortFilter','verifiedFilter'])$(id).onchange=render;
setAccount();const last=historyRows()[0];if(last){$('query').value=last.query;activeQuery=last.query;activePolicy=last.policy||readPolicy();activeShops=last.shops||Object.keys(last.result?.markets||{});scanDate=last.scanDate||new Date(last.created).toISOString();jobId=last.id;job=last.result;$('results').hidden=false;render();if(last.result?.state==='running'&&accessKey){running=true;updateSearchButton();poll(++generation)}}
})();
