const preview='https://raw.githack.com/nkuchenov-hash/MarketRadar/main/live-preview/index.html';
document.getElementById('open').addEventListener('click',()=>chrome.tabs.create({url:preview}));
document.getElementById('check').addEventListener('click',async()=>{
 const diag=document.getElementById('diagnostic');
 try{const tabs=await chrome.tabs.query({});const site=tabs.find(t=>/^https:\/\/(?:nkuchenov-hash\.github\.io\/MarketRadar\/|raw\.githack\.com\/nkuchenov-hash\/MarketRadar\/main\/live-preview\/)/.test(t.url||''));
 if(site){await chrome.tabs.sendMessage(site.id,{type:'MR_PING'});diag.textContent='Сайт найден. Расширение отвечает. Можно запускать поиск.';}
 else diag.textContent='Сначала нажмите «Открыть поиск товаров». После установки расширения обновите страницу сайта.';
 }catch(e){diag.textContent='Страница открыта, но соединения пока нет. Обновите вкладку с MarketRadar.';}
});
document.querySelectorAll('[data-store]').forEach(button=>button.addEventListener('click',()=>chrome.tabs.create({url:button.dataset.store})));
