// Optional licensed catalog source; never represents search API data as product-page confirmation.
// Contract: https://www.apisystem.name/docs (WB and YM text search).
import {deliveryDays} from "./ozon-service-core.mjs";
const hosts={wb:"wb.apisystem.name",ym:"market.apisystem.name"};
const wait=ms=>new Promise(r=>setTimeout(r,ms));
const positive=x=>typeof x==="number"&&Number.isFinite(x)&&x>0;
const feeValue=x=>typeof x==="number"&&Number.isFinite(x)&&x>=0?x:null;
const todayMoscow=()=>new Intl.DateTimeFormat("sv-SE",{timeZone:"Europe/Moscow",year:"numeric",month:"2-digit",day:"2-digit"}).format(new Date());
function storeLink(shop,value){
 try{
  const u=new URL(value);
  return u.protocol==="https:"&&!u.username&&!u.password&&(shop==="wb"?/^(www\.)?wildberries\.ru$/.test(u.hostname)&&/^\/catalog\/\d+\/detail\.aspx$/.test(u.pathname):u.hostname==="market.yandex.ru"&&/^\/(card\/|product--)/.test(u.pathname))?u.href:null;
 }catch{return null;}
}
const months=["января","февраля","марта","апреля","мая","июня","июля","августа","сентября","октября","ноября","декабря"];
const shortMonths=["янв","фев","мар","апр","май","июн","июл","авг","сен","окт","ноя","дек"];
export function providerDeliveryDays(raw,today=todayMoscow()){
 if(Number.isInteger(raw)&&raw>=0)return raw;
 if(typeof raw!=="string")return null;
 const label=raw.split(",")[0].trim().toLowerCase().replace(/\.$/,"");
 if(["сегодня","завтра","послезавтра"].includes(label))return deliveryDays(label,today);
 const m=label.match(/^(\d{1,2})\s+([а-яё]+)$/u);
 if(!m)return null;
 const aliases={мая:4,сент:8,нояб:10,февр:1};
 let i=months.indexOf(m[2]);if(i<0)i=shortMonths.indexOf(m[2]);if(i<0&&Object.hasOwn(aliases,m[2]))i=aliases[m[2]];
 if(i<0)return null;
 return deliveryDays(m[1]+" "+months[i],today);
}
// Retail identity is independent of the deployed Ozon implementation.
// Normalize only equivalent units and spellings; never invent manufacturer codes.
function identityTokens(value){
 return String(value??"").normalize("NFKC").toLowerCase().replace(/ё/g,"е")
  .replace(/(?<![\p{L}\p{N}])(\d{1,3})[ \u00a0\u202f](\d{3})(?=\s*(?:лм|lm|люмен|вт|w)\b)/gu,"$1$2")
  .replace(/(\d+(?:[.,]\d+)?)\s*(?:tb|тб)(?![\p{L}\p{N}])/gu,(_,n)=>Number(n.replace(",","."))+"tb")
  .replace(/(\d+(?:[.,]\d+)?)\s*(?:gb|гб)(?![\p{L}\p{N}])/gu,(_,n)=>Number(n.replace(",","."))+"gb")
  .replace(/(\d+(?:[.,]\d+)?)\s*(?:лм|lm|люмен(?:а|ов|ы)?)(?![\p{L}\p{N}])/gu,(_,n)=>Number(n.replace(",","."))+" лм")
  .replace(/(\d+(?:[.,]\d+)?)\s*(?:квт|kw)(?![\p{L}\p{N}])/gu,(_,n)=>Number(n.replace(",","."))*1000+" вт")
  .replace(/(\d+(?:[.,]\d+)?)\s*(?:вт|w|ватт(?:а|ов)?)(?![\p{L}\p{N}])/gu,(_,n)=>Number(n.replace(",","."))+" вт")
  .replace(/(?<!\p{L})ламп(?:очка|очки|очку|а|ы|у)(?!\p{L})/gu,"лампа")
  .replace(/\bmini\b/g,"мини").replace(/\bpro\b/g,"про").replace(/\bmax\b/g,"макс")
  .replace(/[^\p{L}\p{N}]+/gu," ").trim().split(/\s+/).filter(Boolean);
}
export function retailMatches(query,title,brand=""){
 const q=new Set(identityTokens(query)),t=new Set(identityTokens(title+" "+brand));
 const connectors=new Set(["с","со","в","на","и","из","для","with","and","for","of","the"]);
 const editions=["про","макс","лайт","lite","ультра","ultra","plus","плюс","mini","мини"];
 const accessory=["чехол","держатель","кронштейн","подставка","крепление","кабель","адаптер","модуль","аккумулятор","пульт","запчасть","муляж","макет","реплика","аналог","уценка","refurbished"];
 if(!q.size||!t.size)return false;
 if([...q].some(w=>!connectors.has(w)&&!t.has(w)))return false;
 if([...editions,...accessory].some(w=>t.has(w)&&!q.has(w)))return false;
 return true;
}
export function normalizeOffer(shop,row,query,today=todayMoscow()){
 if(!row||typeof row!=="object")return null;
 const title=String(row.offer_name??"");
 const brand=typeof row.brand==="string"?row.brand.trim().slice(0,120):"";
 const url=storeLink(shop,row.url);
 if(!url||!retailMatches(query,title,brand)||row.resale===true||row.on_stock===0||!positive(row.price))return null;
 const pay=shop==="wb"?row.price_wb_pay:row.price_yandex_pay;
 const conditional=positive(pay)&&pay<row.price;
 const price=conditional?pay:row.price;
 const raw=row.delivery_time;
 const days=providerDeliveryDays(raw,today);
 const fee=feeValue(row.delivery_cost);
 return {title,brand,url,price,price_regular:row.price,condition:conditional?(shop==="wb"?"Цена с WB Кошельком":"Цена с Яндекс Пэй"):"",
 delivery:String(raw??"").slice(0,100),delivery_days:days,delivery_fee:fee,total_price:fee===null?null:price+fee,
 verified_detail:false,source:"apisystem",image_url:""};
}
// The same query is sent to both sort orders. A popular result is a price
// reference, not permission to skip cheaper pages or assume exhaustive coverage.
export async function findRetailOffers({shop,query,key,geoId,gps,maxPages=5,maxDays=3,allowLate=false,discountPercent=20},fetcher=fetch,pause=wait){
 if(!hosts[shop]||typeof query!=="string"||query.trim().length<3||query.length>180||!key)throw Error("Invalid configuration");
 if(shop==="wb"&&!/^[-+]?\d{1,2}(?:\.\d+)?,[-+]?\d{1,3}(?:\.\d+)?$/.test(gps||""))throw Error("WB delivery location required");
 if(shop==="ym"&&(!Number.isInteger(geoId)||geoId<=0))throw Error("Market region required");
 const start=Date.now(),found=new Map(),issues=[],ledger=[];
 const budget=Math.min(12,Math.max(1,Math.floor(Number(maxPages)||5)));
 const popular=shop==="wb"?"popular":"dpop",cheap=shop==="wb"?"priceup":"aprice";
 let scanned=0,pages=0,regionConfirmed=false,attempts=0,firstOfferSeconds=null,pricePages=0,priceExhausted=false,popularUsed=false;
 const bestTimely=()=>Math.min(...[...found.values()].filter(o=>o.delivery_days!==null&&o.delivery_days<=maxDays).map(o=>o.price));
 async function scan(sort,page){
  if(attempts>=budget)return null;
  if(attempts)await pause(1100);
  attempts++;
  const url=new URL("https://"+hosts[shop]+"/search");
  // Ten rows is the proven interoperable size; WB's 30-row initial request failed.
  url.search=new URLSearchParams({text:query,format:"json",api_key:key,page:String(page),count:"10",sort,...(shop==="wb"?{gps}:{geo_id:String(geoId),resale:"0"})});
  try{
   const res=await fetcher(url,{redirect:"error",signal:AbortSignal.timeout(12000),headers:{Accept:"application/json"}});
   if(!res.ok){issues.push("HTTP "+res.status);return null;}
   const data=await res.json();
   if(!data||typeof data!=="object"||data.status==="ERROR"||!Array.isArray(data.offers)){issues.push("Provider rejected request or invalid schema");return null;}
   if(shop==="ym"&&Number(data.region_id)!==geoId){issues.push("Region mismatch");return null;}
   if(Number(data.page||page)!==page){issues.push("Unexpected response page");return null;}
   if(shop==="ym")regionConfirmed=true;
   pages++;scanned+=data.offers.length;
   let matchesCount=0,newCount=0,unknownDelivery=0,lateDelivery=0;
   for(const x of data.offers){
    const o=normalizeOffer(shop,x,query);
    if(!o)continue;
    matchesCount++;
    if(o.delivery_days===null)unknownDelivery++;else if(o.delivery_days>maxDays)lateDelivery++;
    // Preserve separate price/delivery observations for the same seller.
    const id=JSON.stringify([x.offer_id??x.model_id??o.url,x.shop_id??x.business_id??"",o.url,o.price,o.delivery_days,o.condition]);
    if(!found.has(id)){found.set(id,o);newCount++;}
   }
   const best=bestTimely();
   if(firstOfferSeconds===null&&Number.isFinite(best))firstOfferSeconds=(Date.now()-start)/1000;
   ledger.push({sort,page,rows:data.offers.length,matches:matchesCount,unknown_delivery:unknownDelivery,late_delivery:lateDelivery,new_observations:newCount,best_timely:Number.isFinite(best)?best:null,total_pages:Number.isInteger(data.total_pages)?data.total_pages:null});
   return data;
  }catch{issues.push("Provider transport or parse failure");return null;}
 }
 // Price order is primary. Popularity is optional recovery, not a mandatory
 // extra call. Reserve the next price page whenever possible.
 for(let page=1;attempts<budget;page++){
  const data=await scan(cheap,page);
  if(!data)break;
  pricePages++;
  if(!data.offers.length||(Number.isInteger(data.total_pages)&&page>=data.total_pages)){priceExhausted=true;break;}
  if(pricePages===2&&!Number.isFinite(bestTimely())&&!popularUsed&&budget-attempts>=2){
   popularUsed=true;
   if(!await scan(popular,1))break;
  }
 }
 const rows=[...found.values()];
 const timely=rows.filter(o=>Number.isInteger(o.delivery_days)&&o.delivery_days<=maxDays);
 const knownTotalBest=Math.min(...timely.filter(o=>o.total_price!==null).map(o=>o.total_price));
 const offers=rows.filter(o=>Number.isInteger(o.delivery_days)&&(o.delivery_days<=maxDays||(allowLate&&Number.isFinite(knownTotalBest)&&o.total_price!==null&&o.total_price<=knownTotalBest*(1-discountPercent/100))));
 offers.sort((a,b)=>(a.total_price??a.price)-(b.total_price??b.price));
 const best=bestTimely();
 return {offers,audit:{source:"apisystem",seconds:(Date.now()-start)/1000,attempts,pages,price_pages:pricePages,popular_fallback_used:popularUsed,unknown_delivery:rows.filter(o=>o.delivery_days===null).length,late_delivery:rows.filter(o=>o.delivery_days!==null&&o.delivery_days>maxDays).length,price_exhausted:priceExhausted,scanned,candidates:rows.length,first_offer_seconds:firstOfferSeconds,best_timely:Number.isFinite(best)?best:null,region_confirmed:regionConfirmed,requested_region:shop==="ym"?geoId:null,delivery_verified:0,issues,ledger,coverageComplete:false,stop_reason:issues.length?"provider_error":priceExhausted?"provider_end":"request_budget"}};
}
