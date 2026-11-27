import assert from "node:assert/strict";
import {normalizeOffer,findRetailOffers} from "./retail-specialist-core.mjs";
const q="Яндекс Станция Мини 3 Про",wb={offer_name:q,price:12000,price_wb_pay:11000,delivery_time:"Завтра",delivery_cost:0,on_stock:1,url:"https://www.wildberries.ru/catalog/123456/detail.aspx",model_id:123456};
const a=normalizeOffer("wb",wb,q);
assert.equal(a.price,11000);assert.equal(a.total_price,11000);assert.equal(a.delivery_days,1);assert.equal(a.verified_detail,false);
assert.equal(normalizeOffer("wb",{...wb,on_stock:0},q),null);
assert.equal(normalizeOffer("wb",{...wb,url:"https://example.com/catalog/123456/detail.aspx"},q),null);
assert.equal(normalizeOffer("wb",{...wb,offer_name:"Чехол для "+q},q),null);
assert.equal(normalizeOffer("wb",{...wb,delivery_time:"с 1 декабря"},q).delivery_days,null);
const ym={offer_name:q,price:12000,price_yandex_pay:12500,delivery_time:"Послезавтра, ПВЗ По клику",url:"https://market.yandex.ru/card/market/123456",offer_id:"x"};
assert.equal(normalizeOffer("ym",ym,q).price,12000);assert.equal(normalizeOffer("ym",ym,q).delivery_days,2);
let calls=0;
const f=async(u,opts)=>{calls++;assert.equal(u.protocol,"https:");assert.equal(opts.redirect,"error");assert.equal(u.searchParams.get("sort"),"priceup");return {ok:true,json:async()=>({offers:[wb],total_pages:1})};};
const result=await findRetailOffers({shop:"wb",query:q,key:"fixture",gps:"59.93,30.31",maxPages:1},f,async()=>{});
assert.equal(calls,1);assert.equal(result.offers.length,1);assert.equal(result.audit.region_confirmed,false);
const mismatch=await findRetailOffers({shop:"ym",query:q,key:"fixture",geoId:2},async()=>({ok:true,json:async()=>({region_id:213,offers:[ym]})}),async()=>{});
assert.equal(mismatch.offers.length,0);assert.equal(mismatch.audit.issues[0],"Region mismatch");
console.log("Specialist catalog normalization and safety tests passed");

const liveDate="2026-11-27";
const observed={offer_name:q,price:13422,price_yandex_pay:12702,delivery_time:"Завтра, по клику ПВЗ",url:"https://market.yandex.ru/card/market/4554883832"};
const sample=normalizeOffer("ym",observed,q,liveDate);
assert.equal(sample.price,12702);assert.equal(sample.delivery_days,1);assert.equal(sample.total_price,null);assert.equal(sample.verified_detail,false);
assert.equal(normalizeOffer("ym",{...observed,delivery_time:"3 дек, по клику ПВЗ"},q,liveDate).delivery_days,6);
assert.equal(normalizeOffer("ym",{...observed,delivery_time:"2 дек, курьер Доставка магазина"},q,liveDate).delivery_days,5);
assert.equal(normalizeOffer("ym",{...observed,delivery_time:"с 28 ноября"},q,liveDate).delivery_days,null);
assert.equal(normalizeOffer("ym",{...observed,delivery_time:"завтра или позднее"},q,liveDate).delivery_days,null);
assert.equal(normalizeOffer("ym",null,q,liveDate),null);
console.log("Live-response delivery and price fixtures passed");

for(const shop of ["wb","ym"]){
 const query="Samsung SSD 990 Pro 2TB";
 const url=n=>shop==="wb"?"https://www.wildberries.ru/catalog/"+n+"/detail.aspx":"https://market.yandex.ru/card/market/"+n;
 const item=(n,price,title=query,delivery_time="Завтра")=>({offer_name:title,url:url(n),model_id:n,price,delivery_time,delivery_cost:0,on_stock:1});
 const calls=[];
 const res=await findRetailOffers({shop,query,key:"fixture",gps:"59.93,30.31",geoId:2,maxPages:4},async u=>{
  const sort=u.searchParams.get("sort"), page=Number(u.searchParams.get("page"));
  assert.equal(u.searchParams.get("text"),query);
  assert.equal(u.searchParams.get("count"),"10");
  calls.push([sort,page]);
  const offers=sort==="popular"||sort==="dpop"?[item(10001,20000)]:page===1?[item(10002,1500,"Чехол для "+query)]:page===2?[item(10003,16000,query,8)]:[item(10004,18000)];
  return {ok:true,json:async()=>({page,region_id:2,total_pages:4,offers})};
 },async()=>{});
 assert.equal(calls.length,4);
 assert.deepEqual(calls,[[shop==="wb"?"priceup":"aprice",1],[shop==="wb"?"priceup":"aprice",2],[shop==="wb"?"popular":"dpop",1],[shop==="wb"?"priceup":"aprice",3]]);
 assert.equal(res.audit.popular_fallback_used,true);
 assert.equal(res.audit.late_delivery,1);
 assert.equal(res.audit.unknown_delivery,0);
 assert.equal(res.audit.price_pages,3);
 assert.equal(res.audit.coverageComplete,false);
 assert.equal(res.audit.stop_reason,"request_budget");
 assert.equal(res.audit.ledger[0].matches,0);
 assert.equal(res.audit.best_timely,18000);
 assert.equal(res.offers.some(x=>x.price===1500),false);
}
console.log("Generic query and multi-page discovery tests passed");

const orderedCalls=[];
const ordered=await findRetailOffers({shop:"wb",query:q,key:"fixture",gps:"59.93,30.31",maxPages:5},async u=>{
 orderedCalls.push(u.searchParams.get("sort"));
 return {ok:true,json:async()=>({page:Number(u.searchParams.get("page")),total_pages:2,offers:[wb]})};
},async()=>{});
assert.deepEqual(orderedCalls,["priceup","priceup"]);
assert.equal(ordered.audit.popular_fallback_used,false);
assert.equal(ordered.audit.price_exhausted,true);
const deniedCalls=[];
const denied=await findRetailOffers({shop:"wb",query:q,key:"fixture",gps:"59.93,30.31",maxPages:5},async u=>{
 deniedCalls.push(u.searchParams.get("sort"));
 return {ok:false,status:403};
},async()=>{});
assert.deepEqual(deniedCalls,["priceup"]);
assert.equal(denied.audit.stop_reason,"provider_error");
assert.equal(denied.audit.popular_fallback_used,false);
console.log("Price-first, fallback, and refusal tests passed");
