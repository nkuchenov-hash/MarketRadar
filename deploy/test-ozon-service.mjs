import assert from "node:assert/strict";
import { matches, shippingCost, deliveryDays, findOffers } from "./ozon-service-core.mjs";
const q="Яндекс Станция Мини 3 Про";
assert(matches(q,"Умная колонка Яндекс Станция Мини 3 Про, синяя"));
assert(!matches(q,"Яндекс Станция Мини 3, 12 Вт"));
assert(!matches(q,"Чехол для Яндекс Станция Мини 3 Про"));
assert.equal(shippingCost("Входит в заказ"),0);
assert.equal(shippingCost("+390 ₽"),390);
assert.equal(shippingCost("Уточняется"),null);
assert.equal(deliveryDays("Сегодня, 25 ноября","2026-11-25"),0);
assert.equal(deliveryDays("28 ноября","2026-11-25"),3);
assert.equal(deliveryDays("1 декабря","2026-11-25"),6);
assert.equal(deliveryDays("С 26 ноября","2026-11-25"),null);
assert.equal(deliveryDays("2 января","2026-12-30"),3);
const listing=(sku,price,name=q)=>({sku,price,name});
const call=[];
const api={
  getLocation:async()=>({city:"Санкт-Петербург"}),
  setCity:async()=>{throw Error("unnecessary city reset")},
  search:async opts=>{
    call.push(opts);
    if(opts.sort==="popular")return {items:[listing("a",13000),listing("b",14000),listing("c",15000)]};
    if(opts.priceMax)return {items:[listing("low",6000)]};
    return {items:[listing("a",13000),listing("b",14000),listing("c",15000),listing("d",11500),listing("e",11800),listing("f",12000,"Яндекс Станция Мини 3")]};
  },
  details:async sku=>({
    sku,name:q,price:{low:6000,d:11500,e:11800,a:13000,b:14000,c:15000}[sku],available:true,
    url:"https://www.ozon.ru/product/"+sku+"/",location:{city:"Санкт-Петербург"},
    delivery:{options:[{title:"ПВЗ",dateText:sku==="low"?"через неделю":"Сегодня",priceBadge:"Входит в заказ"}]},
  })
};
const result=await findOffers({query:q,city:"Санкт-Петербург"},api);
assert.equal(result.audit.lower,7000);
assert.equal(call[2].priceMax,7000);
assert.equal(result.offers.find(x=>x.sku==="d").eligibility,"timely");
assert.equal(result.offers.find(x=>x.sku==="low").eligibility,"unknown");
assert.equal(result.audit.coverageComplete,false);
assert(result.audit.skipped>0);
console.log("Ozon core tests passed");
