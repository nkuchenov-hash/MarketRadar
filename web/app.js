(()=>{
'use strict';
const $=id=>document.getElementById(id);
const markets=[['ozon','Ozon'],['wb','Wildberries'],['ym','Яндекс Маркет']];
const rub=n=>new Intl.NumberFormat('ru-RU').format(n)+' ₽';
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const stored=(key,fallback)=>{try{return localStorage.getItem(key)??fallback}catch{return fallback}};
const store=(key,v)=>{try{localStorage.setItem(key,v)}catch{}};
let user=null,csrf='',mode='login',jobId=null,job=null,timer=null,pendingSearch=false,starting=false,generation=0;
const states={pending:'Ожидание',searching:'Ищем',checking:'Проверяем',complete:'Завершено',partial:'Частично',blocked:'Доступ ограничен',error:'Ошибка',empty:'Нет результатов',ok:'Завершено'};
function msg(text){$('searchError').textContent=text||'';$('searchError').hidden=!text}
async function api(path,method='GET',body){
 const r=await fetch(path,{method,credentials:'same-origin',headers:{'Content-Type':'application/json',...(csrf?{'X-CSRF-Token':csrf}:{})},...(body?{body:JSON.stringify(body)}:{})});
 let data={};try{data=await r.json()}catch{}
 if(!r.ok){const e=Error(typeof data.detail==='string'?data.detail:'Ошибка HTTP '+r.status);e.status=r.status;throw e}return data;
}
function setTheme(v){const value=v==='dark'?'dark':'light';document.documentElement.dataset.theme=value;$('theme').value=value;store('mr_theme',value)}
setTheme(stored('mr_theme','light'));$('consent').checked=stored('mr_consent','yes')==='yes';
$('theme').onchange=()=>setTheme($('theme').value);
$('settingsButton').onclick=()=>$('settings').showModal();
$('closeSettings').onclick=()=>$('settings').close();
$('saveSettings').onclick=()=>{store('mr_consent',$('consent').checked?'yes':'no');$('settings').close()};
function showAuth(){if(!$('auth').open)$('auth').showModal();$('authError').textContent=''}
$('closeAuth').onclick=()=>{pendingSearch=false;$('auth').close()};
$('auth').addEventListener('cancel',()=>{pendingSearch=false});
$('switchAuth').onclick=()=>{mode=mode==='login'?'register':'login';$('authHeading').textContent=mode==='login'?'Войти':'Создать аккаунт';$('authSubmit').textContent=mode==='login'?'Войти':'Зарегистрироваться';$('switchAuth').textContent=mode==='login'?'Создать аккаунт':'Уже есть аккаунт? Войти';$('password').autocomplete=mode==='login'?'current-password':'new-password';$('authError').textContent=''};
function setAccount(){ $('accountButton').textContent=user?'Выйти':'Войти';$('headStatus').textContent=user?(user.cloud_available?'Облачный поиск доступен':'Сервер недоступен'):'Облачный поиск';}
async function getUser(){try{user=await api('/api/me');csrf=user.csrf}catch(e){user=null;csrf=''}setAccount()}
$('authForm').onsubmit=async e=>{e.preventDefault();$('authSubmit').disabled=true;try{await api('/api/'+mode,'POST',{email:$('email').value,password:$('password').value});await getUser();if(!user)throw Error('Не удалось открыть сессию');$('auth').close();$('password').value='';if(pendingSearch){pendingSearch=false;await startSearch()}else if(!$('historyView').hidden)await loadHistory();}catch(e){$('authError').textContent=e.message}finally{$('authSubmit').disabled=false}};
$('accountButton').onclick=async()=>{if(!user){showAuth();return}try{await api('/api/logout','POST');location.reload()}catch(e){msg(e.message)}};
function view(name){$('searchView').hidden=name!=='search';$('historyView').hidden=name!=='history';$('searchTab').classList.toggle('active',name==='search');$('historyTab').classList.toggle('active',name==='history')}
$('searchTab').onclick=()=>view('search');
$('historyTab').onclick=()=>{view('history');loadHistory()};
async function loadHistory(){if(!user){$('history').innerHTML='<div class="empty">Войдите, чтобы открыть историю поисков.</div>';showAuth();return}try{const rows=await api('/api/jobs');$('history').innerHTML=rows.map(j=>'<button class="historyrow" data-id="'+esc(j.id)+'"><strong>'+esc(j.query)+'</strong><span>'+esc(new Date(j.created*1000).toLocaleDateString('ru-RU'))+'</span></button>').join('')||'<div class="empty">История пока пуста.</div>';for(const el of $('history').querySelectorAll('button'))el.onclick=()=>{view('search');openJob(el.dataset.id)}}catch(e){$('history').textContent=e.message}}
$('searchForm').onsubmit=e=>{e.preventDefault();startSearch()};
async function startSearch(){
 if(starting)return;msg('');
 const q=$('query').value.trim();if(q.length<3)return;
 if(!user){pendingSearch=true;showAuth();return}
 if(!$('consent').checked){msg('Разрешите обработку сведений о товарах в настройках.');$('settings').showModal();return}
 if(!user.cloud_available){msg('Сервер поиска недоступен.');return}
 starting=true;$('searchButton').disabled=true;
 try{const x=await api('/api/search','POST',{query:q,depth:'fast',mode:'cloud',consent:true});openJob(x.job_id)}
 catch(e){msg(e.message);if(e.status===401){user=null;setAccount();pendingSearch=true;showAuth()}}
 finally{starting=false;$('searchButton').disabled=false}
}
function openJob(id){clearTimeout(timer);jobId=id;job=null;generation++;$('results').hidden=false;$('jobState').textContent='Ищем предложения';$('resultsHeading').textContent=$('query').value.trim()||'Результаты';$('offers').innerHTML='<div class="empty">Поиск выполняется...</div>';$('marketStatuses').innerHTML='';$('count').textContent='';poll(generation)}
async function poll(g){try{const data=await api('/api/jobs/'+encodeURIComponent(jobId));if(g!==generation)return;job=data;render();if(['running','queued'].includes(job.state))timer=setTimeout(()=>poll(g),1000)}catch(e){if(g!==generation)return;$('jobState').textContent=e.message}}
function link(url){try{const u=new URL(url);return u.protocol==='https:'&&markets.some(([m])=>m==='ozon'?/(^|\.)ozon\.ru$/.test(u.hostname):m==='wb'?/(^|\.)wildberries\.ru$/.test(u.hostname):u.hostname==='market.yandex.ru')?u.href:''}catch{return ''}}
function image(url){try{const u=new URL(url);return u.protocol==='https:'?u.href:''}catch{return ''}}
function shop(o){return o.market_id||markets.find(x=>x[1]===o.market)?.[0]||''}
function days(o){return Number.isInteger(o.delivery_days)&&o.delivery_days>=0?o.delivery_days:9999}
function offersOf(j){const shared=Array.isArray(j.progress?.retail_offers);return (shared?j.progress.retail_offers:j.offers||[]).filter(o=>link(o.url)&&Number.isFinite(o.price)&&o.price>0).map(o=>({...o,market_id:shop(o),verified_detail:shared?!!o.verified_detail:true}))}
function img(o,cls){const u=image(o.image_url);return u?'<img class="'+cls+'" src="'+esc(u)+'" alt="" loading="lazy" referrerpolicy="no-referrer">':''}
function render(){
 if(!job)return;
 $('resultsHeading').textContent=job.query;$('jobState').textContent=job.state==='running'?'Поиск выполняется':job.state==='queued'?'В очереди':job.state==='finished'?'Поиск завершён':job.error||'Поиск остановлен';
 const all=offersOf(job).sort((a,b)=>a.price-b.price);const status=job.progress?.markets||{};
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
getUser().then(async()=>{if(user){try{const rows=await api('/api/jobs');if(rows[0]&&rows[0].state==='running'){ $('query').value=rows[0].query;openJob(rows[0].id)}}catch{}}});
})();
