// Independent first-party Node worker for WB and Market. No paid provider.
// Bounded read-only search. No access challenge bypass; no checkout actions.
import {matches,deliveryDays} from "./ozon-service-core.mjs";
const hosts={wb:"www.wildberries.ru",ym:"market.yandex.ru"};
const moscowDate=()=>new Intl.DateTimeFormat("sv-SE",{timeZone:"Europe/Moscow",year:"numeric",month:"2-digit",day:"2-digit"}).format(new Date());
export function productKey(shop,value){
 try{
   const u=new URL(value);if(u.protocol!=="https:"||u.username||u.password||u.port)return null;
   if(shop==="wb"&&/^(www\.)?wildberries\.ru$/.test(u.hostname)){
     const m=u.pathname.match(/^\/catalog\/(\d+)\/detail\.aspx\/?$/);return m?"wb:"+m[1]:null;
   }
   if(shop==="ym"&&u.hostname===hosts.ym){
     const m=u.pathname.match(/^\/(?:card\/[^/]+|product--[^/]+|product\/[^/]+)\/(\d+)\/?$/);return m?"ym:"+m[1]:null;
   }
 }catch{}
 return null;
}
export function searchURL(shop,query){
 if(!hosts[shop])throw Error("Unknown marketplace");
 const u=new URL(shop==="wb"?"https://www.wildberries.ru/catalog/0/search.aspx":"https://market.yandex.ru/search");
 u.search=new URLSearchParams(shop==="wb"?{search:query,sort:"priceup"}:{text:query,how:"aprice"});
 return u.href;
}
export function parseDays(value,today=moscowDate()){
 const s=String(value??"").trim().toLowerCase();
 if(!/^(?:сегодня|завтра|послезавтра|\d{1,2} [а-яё]+|через \d{1,2} (?:день|дня|дней))$/u.test(s))return null;
 const n=s.match(/^через (\d{1,2}) /);return n?Number(n[1]):deliveryDays(s,today);
}
const amount=x=>typeof x==="number"&&Number.isFinite(x)&&x>=100&&x<=50_000_000;
const cleanImage=s=>typeof s==="string"&&s.startsWith("https://")?s.slice(0,900):"";
export async function searchFirstParty({shop,query,maxDays=3,deadlineMs=20000,detailLimit=4},transport,clock=Date.now){
 if(!hosts[shop]||typeof query!=="string"||query.trim().length<3||query.length>180)throw Error("Invalid search");
 const start=clock(),end=start+deadlineMs,today=moscowDate(),rows=new Map(),issues=[];
 let checked=0,detailObserved=0,firstOfferSeconds=null,blocked=false;
 const audit={source:"firstparty_node",coverageComplete:false,issues};
 const eligible=()=>[...rows.values()].filter(o=>o.delivery_days!==null&&o.delivery_days<=maxDays).sort((a,b)=>a.price-b.price);
 const updateFirst=()=>{if(firstOfferSeconds===null&&eligible().length)firstOfferSeconds=(clock()-start)/1000;};
 const merge=xs=>{
  for(const x of xs||[]){
   const key=productKey(shop,x.url);
   if(!key||!amount(x.price)||!matches(query,x.title))continue;
   const o={url:x.url,title:x.title,price:x.price,listing_price:x.price,image_url:cleanImage(x.image_url),
      condition:String(x.price_condition??"").slice(0,100),delivery:String(x.delivery_hint??""),delivery_days:parseDays(x.delivery_hint,today),
      verified_detail:false,delivery_fee:null,total_price:null,source:"firstparty_node"};
   const old=rows.get(key);
   if(!old||(old.delivery_days===null&&o.delivery_days!==null)||((old.delivery_days===null)===(o.delivery_days===null)&&o.price<old.price))rows.set(key,o);
  }
  updateFirst();
 };
 try{
  const result=await transport.listing(searchURL(shop,query),Math.max(1,end-clock()),merge);
  audit.listing_transport=result?.transport||"unknown";
  audit.listing_http_status=result?.http_status??null;
  audit.listing_reason=result?.reason||"";
  if(result?.navigation)audit.navigation=result.navigation;
  audit.transport_attempts=result?.attempts||[{transport:audit.listing_transport,state:result?.state||"unknown",http_status:audit.listing_http_status,reason:audit.listing_reason}];
  blocked=result?.state==="blocked";
  merge(result?.products);
  if(blocked)issues.push("storefront access challenge or denial");
  // Unknown-delivery low-priced candidates are checked, not discarded.
  const sorted=[...rows.entries()].sort((a,b)=>a[1].price-b[1].price);
  for(const [key,o] of sorted){
   if(blocked||clock()>=end||checked>=detailLimit)break;
   if(o.delivery_days!==null&&o.delivery_days>maxDays)continue;
   checked++;
   try{
    const d=await transport.detail(o.url,Math.max(1,end-clock()));
    if(d?.state==="blocked"){issues.push("product access denied");audit.detail_denial={transport:d.transport||"unknown",http_status:d.http_status??null,reason:d.reason||""};break;}
    if(d?.state!=="ok"||productKey(shop,d.url)!==key)continue;
    if(d.heading&&!matches(query,d.heading)){rows.delete(key);continue;}
    const days=parseDays(d.detail_delivery,today);
    if(!d.heading||!amount(d.primary_price)||days===null)continue;
    // Do not attribute metadata or another variant's price to a visible buybox.
    // The Yandex DOM currently lacks a validated visible price selector.
    if(shop!=="wb"||d.detail_price_source!=="wb_visible_buybox"){
      o.observed_detail_price=d.primary_price;o.observed_detail_delivery=d.detail_delivery;continue;
    }
    detailObserved++;
    // Wallet/regular prices may differ. Preserve the observed condition,
    // but do not claim independent confirmation of the discounted amount.
    if(o.condition&&o.price!==d.primary_price){
      o.observed_detail_price=d.primary_price;o.observed_detail_delivery=d.detail_delivery;continue;
    }
    Object.assign(o,{price:d.primary_price,delivery:d.detail_delivery,delivery_days:days,
      detail_price:d.primary_price,detail_price_source:d.detail_price_source,
      detail_observed:true,verified_detail:false,image_url:cleanImage(d.primary_image)||o.image_url});
    updateFirst();
   }catch{issues.push("detail unavailable");}
  }
 }catch(e){issues.push("listing unavailable");audit.listing_error_type=e?.name||"Error";}
 return {offers:eligible(),audit:{...audit,blocked,scanned:rows.size,checked,detail_observed:detailObserved,first_offer_seconds:firstOfferSeconds,seconds:(clock()-start)/1000,region_confirmed:false}};
}
