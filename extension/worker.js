/* User's ordinary browser profile. Never reads cookies, access tokens, or passwords. */
const sites=['ozon.ru','wildberries.ru','market.yandex.ru'];
let polling=false, tabId=null,windowId=null;
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const safeURL=input=>{
  try{const p=new URL(input);return p.protocol==='https:' && sites.some(s=>p.hostname===s||(s!=='market.yandex.ru'&&p.hostname.endsWith('.'+s)));}
  catch{return false;}
};
async function settings(){
  let {server,token}=await chrome.storage.local.get(['server','token']);
  if(!server||!token)return null;
  try{const p=new URL(server);if(!(['https:','http:'].includes(p.protocol))||
      (p.protocol==='http:' && p.hostname!=='127.0.0.1'&&p.hostname!=='localhost'))return null;
      return {server:p.origin,token};}catch{return null;}
}
async function api(cfg,path,body){
  let res=await fetch(cfg.server+path,{method:body?'POST':'GET',
    headers:{Authorization:'Bearer '+cfg.token,'Content-Type':'application/json'},
    ...(body?{body:JSON.stringify(body)}:{})});
  if(!res.ok)throw new Error('Сервер: '+res.status);
  return res.json();
}
async function waitLoad(id,timeout=35000){
  const t=await chrome.tabs.get(id);if(t.status==='complete')return;
  await new Promise((resolve,reject)=>{
    let timer=setTimeout(()=>{clean();reject(new Error('Загрузка заняла слишком много времени'));},timeout);
    const upd=(tab,info)=>{if(tab===id&&info.status==='complete'){clean();resolve()}};
    const rm=tab=>{if(tab===id){clean();reject(new Error('Вкладка закрыта'))}};
    function clean(){clearTimeout(timer);chrome.tabs.onUpdated.removeListener(upd);chrome.tabs.onRemoved.removeListener(rm)}
    chrome.tabs.onUpdated.addListener(upd);chrome.tabs.onRemoved.addListener(rm);
  });
}
async function ensureTab(url){
  let tab=null;
  if(tabId!==null)try{tab=await chrome.tabs.get(tabId)}catch{tabId=null;windowId=null;}
  if(!tab){
    // A separate minimized window avoids changing the user's current tab.
    try{
      const win=await chrome.windows.create({url,focused:false,state:'minimized',type:'normal'});
      tabId=win.tabs[0].id;windowId=win.id;
    }catch{
      // Browser may refuse minimized window creation; fallback to inactive tab.
      const created=await chrome.tabs.create({url,active:false});tabId=created.id;windowId=created.windowId;
    }
  }else await chrome.tabs.update(tabId,{url});
  await waitLoad(tabId);
  await sleep(1800);
  return tabId;
}
function readPage(){
  const body=document.body;
  const text=(body?.innerText||'').slice(0,65000);
  const anchors=Array.from(document.querySelectorAll('a[href]')).slice(0,1800);
  const links=anchors.map(a=>({url:a.href,
    title:(a.getAttribute('aria-label')||a.innerText||a.querySelector('img')?.alt||'').replace(/\s+/g,' ').trim().slice(0,220),
    context:(a.parentElement?.innerText||'').replace(/\s+/g,' ').trim().slice(0,240)}));
  const priceElements=Array.from(document.querySelectorAll('[data-widget*="price" i], [class*="price" i], [aria-label*="₽"], [itemprop="price"]'))
    .slice(0,180).map(x=>(x.innerText||x.getAttribute('aria-label')||x.getAttribute('content')||'').replace(/\s+/g,' ').trim())
    .filter(x=>x && x.length<230).slice(0,80);
  const redact=s=>s.replace(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi,'[email]');
  return {url:location.href,title:document.title,text:redact(text),links,
          price_fragments:priceElements, captured_at:new Date().toISOString()};
}
async function inspect(url){
  if(!safeURL(url))return {state:'error',url,text:'Запрещённый адрес',links:[]};
  try{
    let id=await ensureTab(url);
    const tab=await chrome.tabs.get(id);
    if(!safeURL(tab.url||''))return {state:'error',url,text:'Недопустимая переадресация',links:[]};
    // Dynamic listing: scroll in the background to expose lazy-loaded product cards.
    await chrome.scripting.executeScript({target:{tabId:id},func:()=>window.scrollTo(0,document.body.scrollHeight*0.45)});
    await sleep(850);
    await chrome.scripting.executeScript({target:{tabId:id},func:()=>window.scrollTo(0,document.body.scrollHeight*0.85)});
    await sleep(850);
    const raw=await chrome.scripting.executeScript({target:{tabId:id},func:readPage});
    const x=raw[0]?.result;
    if(!x)return {state:'error',url,text:'Невозможно прочитать страницу',links:[]};
    const lower=x.text.toLowerCase();
    const blocks=['подозрительная активность','captcha-support@','похоже, нет соединения','докажите, что вы не робот','access denied','вы не робот'];
    return {...x,state:blocks.some(s=>lower.includes(s))?'blocked':'ok'};
  }catch(e){return {state:'error',url,text:String(e).slice(0,220),links:[]};}
}
async function poll(){
  if(polling)return;
  polling=true;
  try{
    const cfg=await settings();if(!cfg)return;
    let empty=0;
    for(let i=0;i<120;i++){
      const item=await api(cfg,'/api/device/next');
      if(!item.task){empty++;if(empty>3)break;await sleep(1200);continue;}
      empty=0;
      const result=await inspect(item.task.url);
      try{await api(cfg,'/api/device/result',{task_id:item.task.id,payload:result});}
      catch(err){console.warn('MarketRadar result rejected',err);}
    }
  }catch(e){console.warn('MarketRadar: connection error',String(e));}
  finally{polling=false;}
}
chrome.runtime.onInstalled.addListener(()=>{chrome.alarms.create('market-radar-poll',{periodInMinutes:0.5});poll();});
chrome.runtime.onStartup.addListener(()=>{chrome.alarms.create('market-radar-poll',{periodInMinutes:0.5});poll();});
chrome.alarms.onAlarm.addListener(a=>{if(a.name==='market-radar-poll')poll();});
chrome.runtime.onMessage.addListener((msg,_sender,reply)=>{
  if(msg?.type==='START'){poll().then(()=>reply({ok:true}));return true;}
});
chrome.alarms.create('market-radar-poll',{periodInMinutes:0.5});
poll();
