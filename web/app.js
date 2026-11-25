(()=>{
'use strict';
const $=id=>document.getElementById(id);
const markets=[['ozon','Ozon'],['wb','Wildberries'],['ym','Яндекс Маркет']];
const rub=n=>new Intl.NumberFormat('ru-RU').format(n)+' ₽';
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const stored=(key,fallback)=>{try{return localStorage.getItem(key)??fallback}catch{return fallback}};
const store=(key,v)=>{try{localStorage.setItem(key,v)}catch{}};
let accessKey='',jobId=null,job=null,timer=null,pendingSearch=false,starting=false,generation=0,activeQuery='';
try{accessKey=sessionStorage.getItem('mr_access_key')||''}catch{}
const sessionKey=v=>{accessKey=v;try{if(v)sessionStorage.setItem('mr_access_key',v);else sessionStorage.removeItem('mr_access_key')}catch{}};
const states={pending:'Ожидание',searching:'Ищем',checking:'Проверяем',complete:'Завершено',partial:'Частично',blocked:'Доступ ограничен',error:'Ошибка',empty:'Нет результатов',ok:'Завершено'};
function msg(text){$('searchError').textContent=text||'';$('searchError').hidden=!text}
async function api(path,method='GET',body){
 const r=await fetch(path,{method,credentials:'omit',cache:'no-store',headers:{'Content-Type':'application/json',...(accessKey&&path.startsWith('/api/mobile/browser/')?{'X-MarketRadar-Key':accessKey}:{})},...(body?{body:JSON.stringify(body)}:{})});
 let data={};try{data=await r.json()}catch{}
 if(!r.ok){const e=Error(typeof data.detail==='string'?data.detail:'Ошибка HTTP '+r.status);e.status=r.status;throw e}return data;
}
function setTheme(v){const value=v==='dark'?'dark':'light';document.documentElement.dataset.theme=value;$('theme').value=value;store('mr_theme',value)}
setTheme(stored('mr_theme','light'));$('consent').checked=stored('mr_consent','yes')==='yes';
$('theme').onchange=()=>setTheme($('theme').value);
$('settingsButton').onclick=()=>$('settings').showModal();
$('closeSettings').onclick=()=>$('settings').close();
$('saveSettings').onclick=()=>{store('mr_consent',$('consent').checked?'yes':'no');$('settings').close()};
function showAuth(){if(!$('auth').open)$('auth').showModal();$('accessKey').value=accessKey;$('authError').textContent=''}
$('closeAuth').onclick=()=>{pendingSearch=false;$('auth').close()};
$('auth').addEventListener('cancel',()=>{pendingSearch=false});
function setAccount(){ $('accountButton').textContent=accessKey?'Код доступа ✓':'Код доступа';}
$('authForm').onsubmit=async e=>{e.preventDefault();const key=$('accessKey').value.trim();if(!key)return;sessionKey(key);setAccount();$('auth').close();$('accessKey').value='';if(pendingSearch){pendingSearch=false;await startSearch()}else msg('Код сохранён для этой вкладки. Проверим при поиске.')};
$('forgetKey').onclick=()=>{sessionKey('');setAccount();$('accessKey').value='';pendingSearch=false;$('auth').close();msg('Код доступа удалён.')};
$('accountButton').onclick=showAuth;
function view(name){$('searchView').hidden=name!=='search';$('historyView').hidden=name!=='history';$('searchTab').classList.toggle('active',name==='search');$('historyTab').classList.toggle('active',name==='history')}
$('searchTab').onclick=()=>view('search');
$('historyTab').onclick=()=>{view('history');loadHistory()};
function historyRows(){try{const rows=JSON.parse(stored('mr_local_history_v22','[]'));return Array.isArray(rows)?rows:[]}catch{return []}}
function saveHistory(){if(!job||!jobId)return;const rows=historyRows().filter(x=>x.id!==jobId);const old=historyRows().find(x=>x.id===jobId);rows.unshift({id:jobId,query:activeQuery,created:old?.created||Date.now(),result:{...job,offers:(job.offers||[]).slice(0,100)}});store('mr_local_history_v22',JSON.stringify(rows.slice(0,15)))}
function loadHistory(){const rows=historyRows();$('history').innerHTML=rows.map(j=>'<button class="historyrow" data-id="'+esc(j.id)+'"><strong>'+esc(j.query)+'</strong><span>'+esc(new Date(j.created).toLocaleDateString('ru-RU'))+'</span></button>').join('')||'<div class="empty">История в этом браузере пока пуста.</div>';for(const el of $('history').querySelectorAll('button'))el.onclick=()=>{const row=rows.find(x=>x.id===el.dataset.id);if(!row)return;view('search');$('query').value=row.query;activeQuery=row.query;clearTimeout(timer);generation++;jobId=row.id;job=row.result;$('results').hidden=false;if(job.state==='running'&&accessKey){poll(generation)}else{render();if(job.state==='running')$('jobState').textContent='Последний сохранённый результат'}}}
$('searchForm').onsubmit=e=>{e.preventDefault();startSearch()};
async function startSearch(){
 if(starting)return;msg('');
 const q=$('query').value.trim();if(q.length<3)return;
 if(!accessKey){pendingSearch=true;showAuth();return}
 if(!$('consent').checked){msg('Разрешите обработку сведений о товарах в настройках.');$('settings').showModal();return}
 starting=true;$('searchButton').disabled=true;
 try{const x=await api('/api/mobile/browser/start','POST',{query:q,shops:markets.map(x=>x[0])});activeQuery=q;openJob(x.id)}
 catch(e){msg(e.message);if(e.status===401){sessionKey('');setAccount();pendingSearch=true;showAuth();$('authError').textContent=e.message}}
 finally{starting=false;$('searchButton').disabled=false}
}
function openJob(id){clearTimeout(timer);jobId=id;job=null;generation++;$('results').hidden=false;$('jobState').textContent='Ищем предложения';$('resultsHeading').textContent=$('query').value.trim()||'Результаты';$('offers').innerHTML='<div class="empty">Поиск выполняется...</div>';$('marketStatuses').innerHTML='';$('count').textContent='';poll(generation)}
async function poll(g){try{const data=await api('/api/mobile/browser/jobs/'+encodeURIComponent(jobId));if(g!==generation)return;job={...data,query:activeQuery};render();saveHistory();if(job.state==='running')timer=setTimeout(()=>poll(g),1500)}catch(e){if(g!==generation)return;$('jobState').textContent=e.message}}
function link(url){try{const u=new URL(url);return u.protocol==='https:'&&markets.some(([m])=>m==='ozon'?/(^|\.)ozon\.ru$/.test(u.hostname):m==='wb'?/(^|\.)wildberries\.ru$/.test(u.hostname):u.hostname==='market.yandex.ru')?u.href:''}catch{return ''}}
function image(url){try{const u=new URL(url);return u.protocol==='https:'?u.href:''}catch{return ''}}
function shop(o){return o.market_id||markets.find(x=>x[1]===o.market)?.[0]||''}
function days(o){return Number.isInteger(o.delivery_days)&&o.delivery_days>=0?o.delivery_days:9999}
function offersOf(j){return (j.offers||[]).filter(o=>link(o.url)&&Number.isFinite(o.price)&&o.price>0).map(o=>({...o,market_id:shop(o),verified_detail:!!o.verified_detail}))}
function img(o,cls){const u=image(o.image_url);return u?'<img class="'+cls+'" src="'+esc(u)+'" alt="" loading="lazy" referrerpolicy="no-referrer">':''}
function render(){
 if(!job)return;
 $('resultsHeading').textContent=job.query;$('jobState').textContent=job.state==='running'?'Поиск выполняется':job.state==='queued'?'В очереди':job.state==='finished'?'Поиск завершён':job.error||'Поиск остановлен';
 const all=offersOf(job).sort((a,b)=>a.price-b.price);const status=job.markets||{};
 $('marketStatuses').innerHTML=markets.map(([id,name])=>{
   const group=all.filter(o=>o.market_id===id),best=group.find(o=>o.verified_detail)||group[0],m=status[name]||status[id]||{};
   return '<article class="market-card"><div class="market-top"><strong>'+name+'</strong><span class="muted">'+esc(states[m.state]||'Ожидание')+'</span></div>'+(best?img(best,'')+'<div class="market-price">'+rub(best.price)+'</div><div class="market-detail">'+esc(best.delivery||'Срок неизвестен')+'</div><div class="market-detail">'+(best.verified_detail?'Подтверждено':'Цена из выдачи')+'</div><a href="'+esc(link(best.url))+'" target="_blank" rel="noopener noreferrer">Открыть товар ↗</a>':'<div class="market-price">—</div><div class="market-detail">'+(['pending','searching','checking'].includes(m.state)?'Ищем предложения…':'Нет подтверждённой цены')+'</div>')+'</article>';
 }).join('');
 const filtered=all.filter(o=>($('marketFilter').value==='all'||o.market_id===$('marketFilter').value)&&(!$('verifiedFilter').checked||o.verified_detail));
 if($('sortFilter').value==='delivery')filtered.sort((a,b)=>days(a)-days(b)||a.price-b.price);
 $('count').textContent=filtered.length+' из '+all.length;
 $('offers').innerHTML=filtered.map(o=>'<article class="offer">'+(img(o,'product-image')||'<div class="product-image blank">Фото</div>')+'<div class="offer-main"><div class="offer-meta">'+esc(markets.find(x=>x[0]===o.market_id)?.[1]||o.market)+'</div><h3>'+esc(o.title)+'</h3><div class="offer-meta">'+esc(o.delivery||'Срок доставки неизвестен')+' · '+(o.verified_detail?'Цена подтверждена':'Цена из выдачи')+'</div></div><div class="offer-price"><strong>'+rub(o.price)+'</strong><small>'+esc(o.condition||'')+'</small><a href="'+esc(link(o.url))+'" target="_blank" rel="noopener noreferrer">В магазин ↗</a></div></article>').join('')||'<div class="empty">'+(job.state==='running'?'Поиск выполняется…':'Подходящих предложений нет.')+'</div>';
}
for(const id of ['marketFilter','sortFilter','verifiedFilter'])$(id).onchange=render;
setAccount();const last=historyRows()[0];if(last&&last.result?.state==='running'&&accessKey){$('query').value=last.query;activeQuery=last.query;openJob(last.id)}
})();
