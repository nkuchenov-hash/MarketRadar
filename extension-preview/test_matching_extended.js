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
assert.equal(prices.length,3);
assert.equal(prices[0].price,11542);
assert.equal(prices[0].kind,'bank');
assert.equal(prices[1].kind,'other-banks');
assert.equal(prices[2].price,13590);
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
