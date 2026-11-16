const $=id=>document.getElementById(id);
chrome.storage.local.get(['server','token'],v=>{
 if(v.server)$('server').value=v.server;
 if(v.token)$('msg').textContent='Этот браузер уже подключён';
});
$('connect').onclick=async()=>{
 try{
   const p=new URL($('server').value.trim());
   if(p.protocol!=='https:' && !(p.protocol==='http:'&&['localhost','127.0.0.1'].includes(p.hostname)))
     throw Error('Для удалённого сервера нужен HTTPS');
   const origin=p.origin;
   const perm=await chrome.permissions.request({origins:[origin+'/*']});
   if(!perm)throw Error('Нет разрешения на соединение с сервером');
   const r=await fetch(origin+'/api/device/pair',{method:'POST',headers:{'Content-Type':'application/json'},
                         body:JSON.stringify({code:$('code').value.trim(),name:'Браузер MarketRadar'})});
   const data=await r.json();if(!r.ok)throw Error(data.detail||r.status);
   await chrome.storage.local.set({server:origin,token:data.device_token});
   $('code').value='';$('msg').textContent='Браузер подключён';
   chrome.runtime.sendMessage({type:'START'});
 }catch(e){$('msg').textContent=String(e.message||e)}
};
$('open').onclick=()=>chrome.tabs.create({url:$('server').value.trim()});
