const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const ctx=vm.createContext({URL,chrome:{runtime:{onMessage:{addListener(){}}}}});
vm.runInContext(fs.readFileSync(__dirname+'/worker.js','utf8'),ctx);
const run=s=>vm.runInContext(s,ctx);
const quote=x=>JSON.stringify(x);
const cases=[
 ['Яндекс Станция Мини 3 Про','Яндекс Станция Мини 3 Про с Алисой 18 Вт, черный',true],
 ['Яндекс Станция Мини 3 Про','Яндекс Станция 3',false],
 ['Яндекс Станция Мини 3 Про','Яндекс Станция Мини 3 с Алисой',false],
 ['Яндекс Станция Мини 3 Про','Модуль Яндекс Станция 3 Про',false],
 ['Яндекс Станция Мини 3 Про','Умная колонка Яндекс Станция Мини 3 Pro YNDX-00059BLU',true],
 ['Samsung Galaxy A56 256 ГБ','Samsung Galaxy A56 256 ГБ 8GB черный',true],
 ['Samsung Galaxy A56 256 ГБ','Samsung Galaxy A56 128 ГБ',false],
];
for(const [q,title,want] of cases)assert.equal(run(`scoreMatch(${quote(q)},${quote(title)})>=72`),want,title);
const prices=run(`variants({priceBlocks:[{source:'webPrice',text:'11 542 ₽ С банками 11 899 ₽ С другими банками 13 590 ₽'}]})`);
assert.equal(prices.length,2);
assert.equal(prices[0].price,11542);
assert.equal(prices[0].kind,'bank');
assert.equal(prices[1].kind,'other-banks');
assert.equal(prices[1].price,11899);
assert.equal(run(`variants({priceBlocks:[{source:'webPrice',text:'11 542 ₽ С банками 11 899 ₽ С другими банками 13 590 ₽'}],crossedOutPrices:[13590]}).length`),2);
assert.equal(run(`productURL('wb','https://www.wildberries.ru/catalog/1161744814/detail.aspx?size=1715823328')`),true);
assert.equal(run(`productURL('ozon','https://www.ozon.ru/product/mini3pro-2399149089/')`),true);
assert.equal(run(`productURL('ym','https://market.yandex.ru/product--mini-3-pro/123')`),true);
assert.equal(run(`safeURL('https://example.org/')`),false);
assert.equal(run(`searchQueries('Яндекс Станция Мини 3 Про').includes('YNDX-00059')`),true);
console.log('14 extended checks passed');
// Guard against accessories that reproduce the full name of the compatible device.
for(const misleading of [
 'Климатический модуль Яндекс для Станции Мини 3 Про, с Алисой (YNDX-00652)',
 'Модуль для Яндекс Станция Мини 3 Про',
 'Крепление для Яндекс Станция Мини 3 Про',
 'Чехол Яндекс Станция Мини 3 Про',
 'Яндекс Станция Мини 3',
 'Яндекс Станция 3 Про',
])assert.equal(run(`scoreMatch("Яндекс Станция Мини 3 Про",${quote(misleading)})`),0,misleading);
assert.equal(run(`scoreMatch("Яндекс Станция Мини 3 Про","Умная колонка Яндекс Станция Мини 3 Про с Алисой, Zigbee, 18 Вт")`),100);
console.log('7 strict-model and accessory checks passed');

// Simulated actual Ozon product card: purchase buttons and delivery panel both show timings.
const fakeButtons=[
 {innerText:'В корзину За час',getAttribute(){return null}},
 {innerText:'В корзину Завтра',getAttribute(){return null}}
];
const fakeDelivery=[{innerText:'Доставка и возврат Курьером Ozon Express Сегодня, с 19:40 Пункты выдачи и постаматы Завтра, 17 ноября',getAttribute(){return null}}];
ctx.document={title:'Станция Мини 3 Про',body:{innerText:'Умная колонка Станция Мини 3 Про'},querySelector(sel){if(sel==='h1')return {innerText:'Умная колонка Станция Мини 3 Про'};return null},querySelectorAll(sel){if(sel==='button')return fakeButtons;if(sel.includes('delivery'))return fakeDelivery;return []}};
ctx.location={href:'https://www.ozon.ru/product/mini3pro-2399149089/'};
const snap=run('snapshot()');
const choices=snap.deliveryOptions.map(o=>o.label);
assert.ok(choices.includes('За час'),JSON.stringify(choices));
assert.ok(choices.includes('Завтра'),JSON.stringify(choices));
assert.ok(choices.includes('Сегодня с 19:40'),JSON.stringify(choices));
assert.ok(choices.includes('Завтра, 17 ноября'),JSON.stringify(choices));
console.log('Delivery button and courier/pickup date tests passed');

// Browser challenges must never be reported as a valid empty product listing.
for(const challenge of [
 {documentTitle:'Проверяем браузер',text:''},
 {title:'Just a moment...',text:'Checking your browser before accessing Wildberries'},
 {text:'Cloudflare Ray ID: 1234'},
])assert.equal(run(`blocked(${JSON.stringify(challenge)})`),true,JSON.stringify(challenge));
assert.equal(run(`blocked({title:'Яндекс Станция Мини 3 Про',text:'Товары и цены'})`),false);
console.log('Browser challenge classification checks passed');
