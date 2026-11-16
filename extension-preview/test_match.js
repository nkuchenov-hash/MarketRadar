const fs=require('node:fs');const vm=require('node:vm');const assert=require('node:assert/strict');
const context=vm.createContext({URL,chrome:{runtime:{onMessage:{addListener(){}}}}});
vm.runInContext(fs.readFileSync(__dirname+'/worker.js','utf8'),context);
function exec(expr){return vm.runInContext(expr,context)}
assert(exec('scoreMatch("Яндекс Станция Мини 3 Про","Умная колонка Яндекс Станция Мини 3 Про YNDX-00059BLU")')>=90);
assert.equal(exec('scoreMatch("Яндекс Станция Мини 3 Про","Модуль Яндекс Станция 3")'),0);
assert.equal(exec('scoreMatch("Яндекс Станция Мини 3 Про","Яндекс Станция Мини 3")'),0);
assert.equal(exec('scoreMatch("Яндекс Станция Мини 3 Про","Яндекс Станция 3 Про")'),0);
assert(exec('scoreMatch("Яндекс Станция Мини 3 Про","Умная колонка Яндекс Станция Мини 3 Pro, черный")')>=90);
assert(exec('priceFragments("11 542 ₽ С банками и 11 899 ₽ С другими банками").length')===2);
assert.equal(exec('productURL("wb","https://www.wildberries.ru/catalog/1161744814/detail.aspx?size=1715823328")'),true);
assert.equal(exec('safeURL("https://not-a-market.example.com/product/foo")'),false);
assert.equal(exec('productURL("ozon","https://www.ozon.ru/product/test-2399149089/")'),true);
console.log('9 bridge-matching checks passed');
