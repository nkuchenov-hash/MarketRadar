import http from "node:http";
import { search, details } from "/app/src/ozon.js";
import { warmup, getLocation, setCity, shutdown } from "/app/src/browser.js";
import { findOffers } from "./ozon-service-core.mjs";
let busy=false, warmed=false;
const api={search,details,getLocation,setCity};
const server=http.createServer(async(req,res)=>{
  const reply=(code,body)=>{res.writeHead(code,{"Content-Type":"application/json; charset=utf-8","Cache-Control":"no-store"});res.end(JSON.stringify(body));};
  if(req.method==="GET"&&req.url==="/health")return reply(200,{ok:true,busy,warmed});
  if(req.method!=="POST"||req.url!=="/search")return reply(404,{error:"not found"});
  if(busy)return reply(429,{error:"busy"});
  busy=true;
  // Restart only this sidecar if an upstream browser call never returns.
  const timer=setTimeout(()=>process.exit(1),120000);
  try {
    let data="",bytes=0;
    for await(const chunk of req){bytes+=chunk.length;if(bytes>8192)return reply(413,{error:"request too large"});data+=chunk.toString("utf8");}
    let cfg;try{cfg=JSON.parse(data);}catch{return reply(400,{error:"invalid JSON"});}
    if(typeof cfg?.query!=="string"||cfg.query.trim().length<3||cfg.query.length>180||
      (cfg.alternateQuery!=null&&(typeof cfg.alternateQuery!=="string"||cfg.alternateQuery.length>80))||
      (cfg.city!=null&&(typeof cfg.city!=="string"||cfg.city.length>100)))return reply(400,{error:"invalid search"});
    const result=await findOffers({query:cfg.query.trim(),alternateQuery:cfg.alternateQuery,
      city:cfg.city??"Санкт-Петербург",maxDays:3,lateRatio:.2,pages:5},api);
    warmed=true;
    reply(200,result);
  }catch(e){console.error(e);reply(502,{error:"upstream search failed"});}
  finally{clearTimeout(timer);busy=false;}
});
server.requestTimeout=15000;
server.listen(8765,"0.0.0.0",()=>{
  busy=true;
  const timer=setTimeout(()=>process.exit(1),90000);
  warmup().then(()=>{warmed=true;}).catch(e=>console.error("warmup failed",e)).finally(()=>{clearTimeout(timer);busy=false;});
});
async function stop(){server.close();await shutdown();process.exit(0);}
process.on("SIGTERM",stop);process.on("SIGINT",stop);
