// Optional licensed catalog source; never represents search API data as product-page confirmation.
// Contract: https://www.apisystem.name/docs (WB and YM text search).
import {matches,deliveryDays} from "./ozon-service-core.mjs";
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
export function normalizeOffer(shop,row,query,today=todayMoscow()){
 const title=String(row.offer_name??"");
 const url=storeLink(shop,row.url);
 if(!url||!matches(query,title)||row.resale===true||row.on_stock===0||!positive(row.price))return null;
 const pay=shop==="wb"?row.price_wb_pay:row.price_yandex_pay;
 const conditional=positive(pay)&&pay<row.price;
 const price=conditional?pay:row.price;
 const raw=row.delivery_time;
 let days=null;
 if(shop==="ym"&&Number.isInteger(raw)&&raw>=0)days=raw;
 else if(typeof raw==="string"){
   // Accept a single explicit date, optionally followed by a pickup annotation.
   const label=raw.split(",")[0].trim();
   days=deliveryDays(label,today);
 }
 const fee=feeValue(row.delivery_cost);
 return {title,url,price,price_regular:row.price,condition:conditional?(shop==="wb"?"Цена с WB Кошельком":"Цена с Яндекс Пэй"):"",
 delivery:String(raw??"").slice(0,100),delivery_days:days,delivery_fee:fee,total_price:fee===null?null:price+fee,
 verified_detail:false,source:"apisystem",image_url:""};
}
export async function findRetailOffers({shop,query,key,geoId,gps,maxPages=5,maxDays=3,allowLate=false,discountPercent=20},fetcher=fetch,pause=wait){
 if(!hosts[shop]||typeof query!=="string"||query.trim().length<3||query.length>180||!key)throw Error("Invalid configuration");
 if(shop==="wb"&&!/^[-+]?\d{1,2}(?:\.\d+)?,[-+]?\d{1,3}(?:\.\d+)?$/.test(gps||""))throw Error("WB delivery location required");
 if(shop==="ym"&&(!Number.isInteger(geoId)||geoId<=0))throw Error("Market region required");
 const start=Date.now(),found=new Map(),issues=[];
 let scanned=0,pages=0,regionConfirmed=false;
 for(let page=1;page<=Math.min(10,maxPages);page++){
  if(page>1)await pause(1100);
  const url=new URL("https://"+hosts[shop]+"/search");
  url.search=new URLSearchParams({text:query,format:"json",api_key:key,page:String(page),count:shop==="wb"?"30":"10",sort:shop==="wb"?"priceup":"aprice",...(shop==="wb"?{gps}:{geo_id:String(geoId),resale:"0"})});
  try{
   // Do not allow redirects, including downgrade to HTTP or leak of key.
   const res=await fetcher(url,{redirect:"error",signal:AbortSignal.timeout(12000),headers:{Accept:"application/json"}});
   if(!res.ok){issues.push("HTTP "+res.status);break;}
   const data=await res.json();
   if(data.status==="ERROR"||!Array.isArray(data.offers)){issues.push("Provider rejected request or invalid schema");break;}
   if(shop==="ym"&&Number(data.region_id)!==geoId){issues.push("Region mismatch");break;}
   if(shop==="ym")regionConfirmed=true;
   pages++;scanned+=data.offers.length;
   for(const x of data.offers){
    const o=normalizeOffer(shop,x,query);
    if(!o)continue;
    // Do not merge separate regional or seller price observations.
    const id=String(x.offer_id??x.model_id??o.url)+":"+String(x.shop_id??x.business_id??"");
    const old=found.get(id);
    if(!old||o.price<old.price)found.set(id,o);
   }
   if(!data.offers.length||(Number.isInteger(data.total_pages)&&page>=data.total_pages))break;
  }catch(e){issues.push("Provider transport or parse failure");break;}
 }
 const rows=[...found.values()];
 const timely=rows.filter(o=>Number.isInteger(o.delivery_days)&&o.delivery_days<=maxDays);
 const best= Math.min(...timely.map(o=>o.total_price??o.price));
 const offers=rows.filter(o=>Number.isInteger(o.delivery_days)&&(o.delivery_days<=maxDays||(allowLate&&Number.isFinite(best)&&o.total_price!==null&&o.total_price<=best*(1-discountPercent/100))));
 offers.sort((a,b)=>(a.total_price??a.price)-(b.total_price??b.price));
 return {offers,audit:{source:"apisystem",seconds:(Date.now()-start)/1000,pages,scanned,candidates:rows.length,region_confirmed:regionConfirmed,requested_region:shop==="ym"?geoId:null,delivery_verified:0,issues,coverageComplete:false}};
}
