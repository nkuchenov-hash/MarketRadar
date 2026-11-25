import http from "node:http";
import {findRetailOffers} from "./retail-specialist-core.mjs";
const shop=process.env.RETAIL_SHOP,key=process.env.APISYSTEM_API_KEY;
let busy=false;
if(!["wb","ym"].includes(shop)||!key)throw Error("Configure RETAIL_SHOP and API key");
const server=http.createServer(async(req,res)=>{
 const reply=(code,body)=>{res.writeHead(code,{"Content-Type":"application/json","Cache-Control":"no-store"});res.end(JSON.stringify(body));};
 if(req.method==="GET"&&req.url==="/health")return reply(200,{ok:true,shop,busy});
 if(req.method!=="POST"||req.url!=="/search")return reply(404,{error:"not found"});
 if(busy)return reply(429,{error:"busy"});
 busy=true;
 try{
  let input="";for await(const part of req){input+=part;if(input.length>8192)return reply(413,{error:"too large"});}
  const d=JSON.parse(input);
  if(typeof d.query!=="string"||d.query.trim().length<3||d.query.length>180)return reply(400,{error:"invalid query"});
  const result=await findRetailOffers({shop,key,query:d.query,geoId:Number(process.env.APISYSTEM_YM_REGION_ID),gps:process.env.APISYSTEM_WB_GPS,maxPages:Math.min(10,Math.max(1,Number(process.env.APISYSTEM_MAX_PAGES)||5)),maxDays:Math.min(30,Math.max(0,Number.isInteger(d.max_days)?d.max_days:3)),allowLate:d.allow_late===true,discountPercent:Math.min(90,Math.max(1,Number(d.discount_percent)||20))});
  return reply(200,result);
 }catch{return reply(502,{error:"specialist search failed"});}
 finally{busy=false;}
});
server.requestTimeout=15000;
server.listen(8765,"0.0.0.0");
