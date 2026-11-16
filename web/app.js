const $=id=>document.getElementById(id);
const fmt=v=>new Intl.NumberFormat('ru-RU').format(v)+' ₽';
const kindNames={standard:'Обычная',bank:'По банковским условиям',subscription:'С подпиской',unknown:'Условия уточняются'};
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let csrf='',user=null,mode='login',jobId=null,interval=null;
async function api(path,method='GET',data){const r=await fetch(path,{method,headers:{'Content-Type':'application/json',...(csrf?{'X-CSRF-Token':csrf}:{})},...(data?{body:JSON.stringify(data)}:{})});let x;try{x=await r.json()}catch{x={}}if(!r.ok)throw Error(x.detail||`Ошибка HTTP ${r.status}`);return x;}
async function initialize(){
 try{user=await api('/api/me');csrf=user.csrf;$('auth').hidden=true;$('mainApp').hidden=false;$('logout').hidden=false;
 $('browserIndicator').textContent=user.device_online?'Браузер подключён':'Браузер не подключён';$('planIndicator').textContent=`${user.plan==='paid'?'Plus':`Пробный тариф · ${user.used}/${user.free_limit}`}`;
 $('pairBox').hidden=!!user.device_online;$('buy').disabled=!user.billing_ready;
 $('buy').textContent=user.billing_ready?'Подключить тариф':'Оплата настраивается';
 if(!user.ai_ready){$('headStatus').textContent=user.ai_status||'ИИ не настроен';}else{$('headStatus').textContent=user.ai_status||'ИИ готов к поиску';}
 let jobs=await api('/api/jobs');$('history').innerHTML=jobs.map(j=>`<button class="historyrow" data-job="${esc(j.id)}"><strong>${esc(j.query)}</strong><span>${esc(j.state)} · ${new Date(j.created*1000).toLocaleDateString('ru-RU')}</span></button>`).join('')||'<p class="muted">Пока нет поисков</p>';
 document.querySelectorAll('[data-job]').forEach(el=>el.onclick=()=>showJob(el.dataset.job));
 if(jobs[0] && ['running'].includes(jobs[0].state))showJob(jobs[0].id);
 }catch(e){$('auth').hidden=false;$('mainApp').hidden=true;$('logout').hidden=true;}
}
$('switchAuth').onclick=()=>{mode=mode==='login'?'register':'login';$('authHeading').textContent=mode==='register'?'Создать аккаунт':'Войти';$('authSubmit').textContent=mode==='register'?'Зарегистрироваться':'Войти';$('switchAuth').textContent=mode==='register'?'Уже есть аккаунт? Войти':'Создать аккаунт';};
$('authForm').onsubmit=async e=>{e.preventDefault();try{await api('/api/'+mode,'POST',{email:$('email').value,password:$('password').value});await initialize();}catch(err){$('authError').textContent=err.message;}};
$('logout').onclick=async()=>{try{await api('/api/logout','POST');location.reload();}catch(e){alert(e.message)}};
$('makePairCode').onclick=async()=>{try{let x=await api('/api/pairing','POST');$('pairCode').textContent=x.code;}catch(e){alert(e.message)}};
$('searchForm').onsubmit=async e=>{e.preventDefault();try{
 if(!$('consent').checked)throw Error('Необходимо согласие на анализ сведений о товаре');
 const q=$('query').value.trim();const x=await api('/api/search','POST',{query:q,depth:$('depth').value,consent:true});
 showJob(x.job_id);await initialize();
 }catch(err){alert(err.message);}};
$('showBatch').onclick=()=>{$('batchBox').hidden=!$('batchBox').hidden;};
$('runBatch').onclick=async()=>{try{
 if(!$('consent').checked)throw Error('Необходимо согласие на анализ сведений о товарах');
 const items=$('batchItems').value.split('\n').map(x=>x.trim()).filter(Boolean);
 if(!items.length)throw Error('Введите названия товаров');
 const r=await api('/api/batch','POST',{items,depth:$('depth').value,consent:true});
 $('batchBox').hidden=true;showJob(r.job_ids[0]);await initialize();
 }catch(err){alert(err.message)}};
$('buy').onclick=async()=>{try{const x=await api('/api/billing/checkout','POST');location.href=x.url;}catch(e){alert(e.message)}};
function showJob(id){jobId=id;$('results').hidden=false;clearInterval(interval);refresh();interval=setInterval(refresh,2000);}
async function refresh(){
 if(!jobId)return;try{
 let job=await api('/api/jobs/'+jobId);$('resultsHeading').textContent=job.query;
 $('jobState').textContent=job.state==='running'?'Поиск в фоне':job.state==='finished'?'Поиск завершён':job.state==='error'?'Ошибка':'Прерван';
 const markets=job.progress.markets||{};
 $('marketStatuses').innerHTML=Object.entries(markets).map(([k,v])=>`<div class="market ${esc(v.state)}"><strong>${esc(k)}</strong><div class="state">${esc(v.state)}</div><p>Поисков: ${Number(v.queries||0)} · Проверено карточек: ${Number(v.cards||0)}<br>${esc(v.message||'')}</p></div>`).join('');
 const arr=job.offers||[];const std=arr.filter(x=>x.kind==='standard');const conditional=arr.filter(x=>x.kind!=='standard');
 $('summary').innerHTML=`<div><small>Минимальная подтверждённая обычная цена</small><strong>${std.length?fmt(std[0].price):'—'}</strong></div><div><small>Минимальная с условиями</small><strong>${conditional.length?fmt(conditional[0].price):'—'}</strong></div><div><small>Проверенных вариантов цены</small><strong>${arr.length}</strong></div>`;
 $('count').textContent=arr.length+' предложений';
 $('offers').innerHTML=arr.map(x=>`<article class="offer"><div><div class="vendor">${esc(x.market)}</div><div class="name">${esc(x.title)}</div><div class="details">${esc(x.condition)} · ${esc(x.evidence)} · ${esc(x.checked)}</div></div><div><div class="value">${fmt(x.price)}</div><div class="kind">${esc(kindNames[x.kind]||'Условия уточняются')}</div></div><a target="_blank" rel="noopener noreferrer" href="${esc(x.url)}">Открыть ↗</a></article>`).join('') || '<p class="muted">Пока нет подтверждённых цен. Проверьте статус площадок выше.</p>';
 if(job.error)$('jobState').textContent='Ошибка: '+job.error;
 if(job.state!=='running'){
   clearInterval(interval);
   const row=[...document.querySelectorAll('[data-job]')].find(el=>el.dataset.job===jobId);
   if(row){const info=row.querySelector('span');if(info){
       const date=new Date(job.created*1000).toLocaleDateString('ru-RU');
       info.textContent=(job.state==='error'?'Ошибка':job.state==='finished'?'Завершён':job.state)+' · '+date;
   }}
 }
 }catch(err){$('jobState').textContent=err.message;clearInterval(interval);}}
initialize();setInterval(async()=>{if(user){try{const now=await api('/api/me');if(now.device_online!==user.device_online){user=now;initialize();}}catch{}}},20000);
