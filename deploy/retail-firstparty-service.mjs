// Own warm Chromium session per marketplace, not a ParserData proxy.
import http from "node:http";
import {readFileSync} from "node:fs";
import {chromium} from "playwright";
import {searchFirstParty,productKey} from "./retail-firstparty-core.mjs";
import {fetchYandexPublicHTML} from "./retail-yandex-public-html.mjs";
const shop=process.env.RETAIL_SHOP;
if(!["wb","ym"].includes(shop))throw Error("Set RETAIL_SHOP=wb or ym");
const host=shop==="wb"?"wildberries.ru":"market.yandex.ru";
const sameShop=value=>{try{const u=new URL(value);return u.protocol==="https:"&&!u.username&&!u.password&&(shop==="wb"?["wildberries.ru","www.wildberries.ru"].includes(u.hostname):u.hostname===host);}catch{return false;}};
// Reuse the reviewed storefront DOM extractor, avoiding divergent selector code.
// This file must be mounted read-only from the trusted application release.
const source=readFileSync(process.env.RETAIL_DOM_SOURCE||new URL("../server/cloud_browser.py",import.meta.url),"utf8");
const extractor=source.match(/_DOM_SNAPSHOT = r"""([\s\S]*?)"""/)?.[1];
if(!extractor)throw Error("Missing DOM extractor");
let context,busy=false;
const timeout=(end,cap)=>Math.max(1,Math.min(cap,end-Date.now()));
// Keep only status, route category and challenge state; never log cookies,
// full redirect URLs, account text, or query values.
async function open(page,url,end){
 if(!sameShop(url))throw Error("Invalid URL");
 const r=await page.goto(url,{waitUntil:"domcontentloaded",timeout:timeout(end,10000)});
 const status=r?.status()??null;
 const within=sameShop(page.url());
 const title=within?await page.title().catch(()=>""):"";
 const challenge=/вы не робот|подтвердите, что вы человек|подтвердите, что запросы отправляли вы|access denied|captcha|проверяем ваш браузер|подозрительная активность|похоже, нет соединения/i.test(title);
 const reason=!within?"redirect_outside_storefront":challenge?"challenge_title":[401,403,429,498,503].includes(status)?"http_denial":"";
 return {state:reason?"blocked":"ok",http_status:status,reason,transport:"warm_chromium_node"};
}
async function snap(page){
 const result=await page.evaluate('('+extractor+')()');
 const challenge=/вы не робот|подтвердите, что вы человек|access denied|captcha|проверяем ваш браузер/i.test((result.title||"")+" "+(result.body||"").slice(0,2500));
 return {...result,state:challenge?"blocked":"ok",url:page.url()};
}
async function browserListing(url,ms,emit){
 const page=await context.newPage(),end=Date.now()+ms;
 try{
  const entry=await open(page,url,end);
  if(entry.state==="blocked")return {...entry,products:[]};
  let last;
  for(let i=0;i<5&&Date.now()<end-250;i++){
   last=await snap(page);
   if(last.state==="blocked")return {state:"blocked",reason:"challenge_body",http_status:entry.http_status,transport:entry.transport,products:[]};
   emit(last.products);
   if(i>=2&&last.products?.length&&last.scroll_y+last.viewport>=last.scroll_height-100)break;
   if(i<4){await page.mouse.wheel(0,900);await page.waitForTimeout(timeout(end,450));}
  }
  return {...(last||{state:"partial",products:[]}),transport:"warm_chromium_node",http_status:entry.http_status};
 }finally{await page.close();}
}
async function listing(url,ms,emit){
 if(shop==="ym"&&process.env.RETAIL_DIRECT_HTML!=="false"){
  const start=Date.now();let directAudit={};
  try{
   const direct=await fetchYandexPublicHTML(url,ms);
   directAudit={transport:"public_ssr_node",state:direct.state,http_status:direct.http_status??null,products:direct.products?.length??0};
   if(direct.state==="blocked")return {...direct,attempts:[directAudit]};
   if(direct.products?.length){emit(direct.products);return {...direct,attempts:[directAudit]};}
  }catch(e){directAudit={transport:"public_ssr_node",state:"error",error_type:e?.name||"Error"};}
  ms-=Date.now()-start;
  if(ms<=250)return {state:"partial",products:[],transport:"public_ssr_node",attempts:[directAudit]};
  const result=await browserListing(url,ms,emit);
  return {...result,attempts:[directAudit,{transport:result.transport,state:result.state,http_status:result.http_status??null,reason:result.reason||""}]};
 }
 return browserListing(url,ms,emit);
}
async function detail(url,ms){
 const page=await context.newPage(),end=Date.now()+ms;
 try{
  const entry=await open(page,url,end);
  if(entry.state==="blocked")return entry;
  if(productKey(shop,page.url())!==productKey(shop,url))return {state:"partial"};
  let last;
  for(let i=0;i<6&&Date.now()<end-100;i++){
   last=await snap(page);
   if(last.state==="blocked"||last.heading&&last.primary_price&&last.detail_delivery)return last;
   await page.waitForTimeout(timeout(end,400));
  }
  return last||{state:"partial"};
 }finally{await page.close();}
}
const server=http.createServer(async(req,res)=>{
 const reply=(status,data)=>{res.writeHead(status,{"Content-Type":"application/json","Cache-Control":"no-store"});res.end(JSON.stringify(data));};
 if(req.method==="GET"&&req.url==="/health")return reply(context?200:503,{ok:!!context,shop,busy,source:"firstparty_node"});
 if(req.method!=="POST"||req.url!=="/search")return reply(404,{error:"not found"});
 if(!context||busy)return reply(429,{error:"not ready or busy"});
 busy=true;
 const timer=setTimeout(()=>process.exit(1),45000);
 try{
  let body="",bytes=0;for await(const chunk of req){bytes+=chunk.length;if(bytes>8192)return reply(413,{error:"too large"});body+=chunk.toString("utf8");}
  let d;try{d=JSON.parse(body);}catch{return reply(400,{error:"invalid JSON"});}
  if(typeof d?.query!=="string"||d.query.trim().length<3||d.query.length>180)return reply(400,{error:"invalid query"});
  const result=await searchFirstParty({shop,query:d.query.trim(),maxDays:Number.isInteger(d.max_days)?Math.max(0,Math.min(90,d.max_days)):3},{listing,detail});
  return reply(200,result);
 }catch{return reply(502,{error:"first-party worker unavailable"});}
 finally{clearTimeout(timer);busy=false;}
});
server.requestTimeout=15000;
const browser=await chromium.launch({headless:process.env.RETAIL_HEADFUL!=="true"});
context=await browser.newContext({locale:"ru-RU",timezoneId:"Europe/Moscow",viewport:{width:1440,height:900}});
server.listen(8765,process.env.RETAIL_BIND||"127.0.0.1");
async function stop(){server.close();await browser.close();process.exit(0);}
process.on("SIGTERM",stop);process.on("SIGINT",stop);
