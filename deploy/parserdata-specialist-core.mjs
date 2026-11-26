// ParserData documented read-only cards + offers API. No keys in logs or source.
import {matches,deliveryDays} from "./ozon-service-core.mjs";
const base="https://apimarket.parserdata.ru/api/v2";
const domains={wb:"wildberries.ru",ym:"market.yandex.ru"};
const wait=ms=>new Promise(r=>setTimeout(r,ms));
const positive=x=>typeof x==="number"&&Number.isFinite(x)&&x>0;
const today=()=>new Intl.DateTimeFormat("sv-SE",{timeZone:"Europe/Moscow",year:"numeric",month:"2-digit",day:"2-digit"}).format(new Date());
function safeLink(shop,value){
 try{
  const u=new URL(value);
  if(u.protocol!=="https:"||u.username||u.password||u.port)return null;
  if(shop==="wb"&&/^(www\.)?wildberries\.ru$/.test(u.hostname)&&/^\/catalog\/\d+\/detail\.aspx$/.test(u.pathname))return u.href;
  if(shop==="ym"&&u.hostname==="market.yandex.ru"&&/^\/(card\/[^/]+\/\d+\/?$|product--[^/]+\/\d+\/?$)/.test(u.pathname))return u.href;
 }catch{}
 return null;
}
function dateDays(value,now=today()){
 if(typeof value!=="string")return null;
 // A date only, without interpreting an unspecified timezone or a delivery range.
 if(/^\d{4}-\d{2}-\d{2}$/.test(value)){
  const d=Date.parse(value+"T00:00:00Z"),s=Date.parse(now+"T00:00:00Z");
  if(!Number.isFinite(d)||new Date(d).toISOString().slice(0,10)!==value)return null;
  const n=(d-s)/86400000;return Number.isInteger(n)&&n>=0?n:null;
 }
 return deliveryDays(value,now);
}
export async function findParserData({shop,query,key,region,maxDays=3,allowLate=false,discountPercent=20,maxCards=3,deadlineMs=65000},fetcher=fetch,pause=wait,clock=Date.now){
 if(!domains[shop]||typeof query!=="string"||query.trim().length<3||query.length>180||!key||!Number.isInteger(region)||region<=0)throw Error("Invalid ParserData configuration");
 if(!Number.isInteger(maxCards)||maxCards<1||maxCards>20)throw Error("Invalid card budget");
 const start=clock(),end=start+deadlineMs,issues=[],cards=new Map(),offers=[];
 const audit={source:"parserdata",search_tasks:0,detail_tasks:0,requested_cards:0,search_pages:0,detail_pages:0,region_requested:region,coverageComplete:false,issues};
 async function req(path,body){
  const remaining=end-clock();if(remaining<=0)throw Error("Deadline exceeded");
  const response=await fetcher(base+path,{method:body?"POST":"GET",headers:{Authorization:"Token "+key,Accept:"application/json",...(body?{"Content-Type":"application/json"}:{})},redirect:"error",signal:AbortSignal.timeout(Math.min(10000,remaining)),...(body?{body:JSON.stringify(body)}:{})});
  if(!response.ok)throw Error("HTTP "+response.status);
  return response.json();
 }
 async function task(kind,body,onPage){
  // Never resubmit a paid POST on timeout or an unknown response.
  const submitted=await req("/"+kind+"/",body);
  if(!Number.isInteger(submitted.task_id)||submitted.task_id<=0)throw Error("Missing task ID");
  const id=submitted.task_id;
  for(let page=1;page<=20;page++){
   let data;
   for(let attempt=0;attempt<30;attempt++){
    data=await req("/"+kind+"/"+id+"?page="+page);
    if(data.status==="OK"&&Array.isArray(data.results))break;
    if(!["PENDING","PROCESSING","IN_PROGRESS","RUNNING","WAITING","NEW","CREATED"].includes(String(data.status).toUpperCase()))throw Error("Unrecognized or failed task status");
    if(end-clock()<1000)throw Error("Deadline exceeded");
    await pause(1000);
   }
   if(data?.status!=="OK"||!Array.isArray(data.results))throw Error("Task not complete");
   onPage(data.results);
   if(!data.next)return;
  }
  issues.push(kind+" pagination capped");
 }
 try{
  audit.search_tasks=1;
  await task("cards",{products:[{name:query}],domain:[domains[shop]]},rows=>{
   audit.search_pages++;
   for(const row of rows){
    if(row.status!=="OK"||row.domain!==domains[shop]||!Array.isArray(row.cards))continue;
    for(const c of row.cards){
     const url=safeLink(shop,c.link);
     if(!url||!matches(query,c.title)||!positive(c.price))continue;
     const old=cards.get(url);
     if(!old||c.price<old.price)cards.set(url,{...c,link:url});
    }
   }
  });
  const ordered=[...cards.values()].sort((a,b)=>Math.min(a.price,positive(a.card_price)?a.card_price:a.price)-Math.min(b.price,positive(b.card_price)?b.card_price:b.price));
  audit.candidates=ordered.length;
  const selected=ordered.slice(0,maxCards);
  audit.requested_cards=selected.length;
  if(!selected.length)return {offers,audit:{...audit,seconds:(clock()-start)/1000}};
  const requested=new Map(selected.map(c=>[c.link,c]));
  audit.detail_tasks=1;
  await task("offers",{products:selected.map(c=>({link:c.link})),regions:[region]},rows=>{
   audit.detail_pages++;
   for(const row of rows){
    const url=safeLink(shop,row.link);
    if(row.status!=="OK"||row.domain!==domains[shop]||row.region!==region||!requested.has(url)||!row.data||row.data.out_of_stock===true||row.data.not_found===true||!matches(query,row.data.title))continue;
    for(const o of row.offers??[]){
     const days=dateDays(o.delivery_date);
     if(!positive(o.price)||days===null||o.quantity===0)continue;
     const conditional=positive(o.card_price)&&o.card_price<o.price;
     const p=conditional?o.card_price:o.price;
     offers.push({url,title:row.data.title,price:p,price_regular:o.price,condition:conditional?(shop==="wb"?"Цена с WB Кошельком":"Цена с Яндекс Пэй"):"",
      delivery:o.delivery_date,delivery_days:days,delivery_fee:null,total_price:null,image_url:typeof row.data.image==="string"&&row.data.image.startsWith("https://")?row.data.image:"",
      verified_detail:false,provider_detail:true,source:"parserdata",provider_region:region});
    }
   }
  });
 }catch(e){issues.push(/^HTTP \d{3}$/.test(e.message)?e.message:"Task incomplete or provider schema mismatch");}
 // Unknown delivery charges cannot justify a late-delivery discount exception.
 const eligible=offers.filter(o=>o.delivery_days<=maxDays).sort((a,b)=>a.price-b.price);
 return {offers:eligible,audit:{...audit,seconds:(clock()-start)/1000,provider_detail_count:eligible.length,region_confirmed:eligible.length>0,late_discount_not_assessed:allowLate}};
}
