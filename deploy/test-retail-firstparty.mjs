import assert from "node:assert/strict";
import {searchFirstParty,productKey,parseDays,searchURL} from "./retail-firstparty-core.mjs";
const query="Яндекс Станция Мини 3 Про";
const url=n=>"https://www.wildberries.ru/catalog/"+n+"/detail.aspx";
const row=(n,price,delivery_hint,title=query)=>({url:url(n),price,delivery_hint,title});
assert.equal(parseDays("завтра"),1);
assert.equal(parseDays("с 28 ноября"),null);
assert.equal(parseDays("через 5 дней"),5);
assert.equal(productKey("wb",url(123)+"?foo=bar"),"wb:123");
assert.equal(productKey("wb","https://evil.test/catalog/123/detail.aspx"),null);
assert.match(searchURL("ym",query),/how=aprice/);
const results=await searchFirstParty({shop:"wb",query},{
 listing:async(u,ms,emit)=>{const a=[row(1,1000,"завтра","Чехол для "+query),row(2,10000,""),row(3,12000,"завтра"),row(4,11000,"завтра"),row(5,9000,"через 5 дней")];emit(a);return {state:"ok",products:a};},
 detail:async(u)=>{
  if(u===url(2))return {state:"ok",url:u,heading:query,primary_price:10500,detail_delivery:"завтра",detail_price_source:"wb_visible_buybox"};
  if(u===url(4))return {state:"ok",url:u,heading:"Чехол для "+query,primary_price:1000,detail_delivery:"завтра"};
  return {state:"ok",url:url(99),heading:query,primary_price:1,detail_delivery:"завтра"};
 }
});
assert.deepEqual(results.offers.map(o=>o.price),[10500,12000]);
assert.equal(results.offers[0].detail_observed,true);
assert.equal(results.offers[0].verified_detail,false);
assert.equal(results.audit.coverageComplete,false);
assert.equal(results.audit.region_confirmed,false);
let detailCalled=false;
const denied=await searchFirstParty({shop:"wb",query},{listing:async()=>({state:"blocked",products:[]}),detail:async()=>{detailCalled=true;}});
assert.equal(detailCalled,false);assert.equal(denied.audit.blocked,true);
const ymURL="https://market.yandex.ru/card/example/12345";
const ym=await searchFirstParty({shop:"ym",query},{listing:async()=>({state:"ok",products:[{title:query,url:ymURL,price:11900,delivery_hint:"завтра"}]}),detail:async()=>({state:"ok",url:ymURL,heading:query,primary_price:10000,detail_delivery:"завтра"})});
assert.equal(ym.offers[0].price,11900);assert.equal(ym.offers[0].observed_detail_price,10000);assert.equal(ym.offers[0].verified_detail,false);
console.log("First-party Node core: exact-model guard, denied-access stop, price provenance, unknown delivery, SKU redirect checks passed.");
