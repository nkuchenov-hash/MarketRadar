const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const html=fs.readFileSync('web/index.html','utf8');
const elements=new Map([...html.matchAll(/\bid="([^"]+)"/g)].map(m=>[m[1],{value:'',checked:false,hidden:false,style:{},classList:{toggle(){}},addEventListener(){},showModal(){this.open=true},close(){this.open=false},reportValidity(){return true},querySelectorAll(){return []}}]));
const memory=new Map();
const storage={getItem:k=>memory.get(k)??null,setItem:(k,v)=>memory.set(k,v),removeItem:k=>memory.delete(k)};
const context={document:{getElementById:id=>{assert(elements.has(id),'Missing HTML element: '+id);return elements.get(id)},documentElement:{dataset:{}}},localStorage:storage,sessionStorage:storage,matchMedia:()=>({matches:false,addEventListener(){}}),Intl,Date,URL,console,setTimeout,clearTimeout,alert:()=>{}};
context.globalThis=context;
const js=fs.readFileSync('web/app.js','utf8').replace(/\}\)\(\);\s*$/, 'globalThis.testApi={chooseOffers,displayKey,days,marketStage,emptyMarket,progressInfo};})();');
vm.createContext(context);vm.runInContext(js,context);
const {chooseOffers}=context.testApi;
const row=(market_id,price,delivery_days,title,verified_detail=true)=>({market_id,price,delivery_days,title,verified_detail,condition:''});
const list=[row('ozon',11630,1,'Mini 3 Pro'),row('wb',11542,3,'Mini 3 Pro'),row('ym',12998,3,'Mini 3 Pro'),row('wb',11542,3,'Mini 3 Pro 123456789'),row('wb',11600,2,'Mini 3 Pro Blue'),row('wb',11650,3,'Mini 3 Pro Green'),row('wb',11700,3,'Mini 3 Pro Gray'),row('ym',13000,3,'Mini 3 Pro Blue'),row('ozon',9000,8,'Mini 3 Pro Red'),row('wb',8000,null,'Mini 3 Pro Black')];
const policy={maxDays:3,allowLate:false,discount:20};
let compact=chooseOffers(list,policy,false);assert.equal(compact.visible.length,3);assert.equal(compact.best.price,11542);assert.equal(compact.outside.price,8000);
let expanded=chooseOffers(list,policy,true);assert.equal(expanded.visible.length,5);assert(!expanded.visible.some(x=>x.price===13000));
assert.equal(chooseOffers(list,{...policy,allowLate:true},false).best.price,9000);
assert.equal(chooseOffers(list,{...policy,maxDays:1},false).best.price,11630);
assert.equal(chooseOffers(list,{...policy,allowLate:true,discount:25},false).best.price,11542);
assert.equal(chooseOffers([row('wb',8000,8,'Mini 3 Pro',false),row('ozon',11630,1,'Mini 3 Pro')],{...policy,allowLate:true},false).best.price,11630);
assert(!html.includes('id="email"'));assert(!html.includes('id="accessKey"'));assert(!html.includes('id="accountButton"'));assert(!js.includes('showAuth'));assert(js.includes("X-MarketRadar-Guest"));
console.log('Android parity: compact results, duplicate removal, 5% expansion, delivery exceptions, guest access passed');

assert.equal(context.testApi.emptyMarket({state:'blocked'},false),'Магазин ограничил доступ');
assert.equal(context.testApi.emptyMarket({state:'partial'},false),'Не найдено в проверенной части выдачи');
assert.equal(context.testApi.marketStage({state:'checking',checked:3,detail_total:8},true),'Проверяем 3 из 8');
assert.equal(context.testApi.progressInfo({wb:{state:'partial'},ym:{state:'checking'}},['wb','ym']).done,1);
assert(html.includes('id="searchProgress"'));

assert(!html.includes('id="acceptSuggestion"'));assert(html.includes('id="interpretedQuery"'));
