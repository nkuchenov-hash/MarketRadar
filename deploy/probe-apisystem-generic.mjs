// One-off, user-authorized trial using generic algorithm, not a bespoke test query.
// Real provider results remain listing observations, not purchase verification.
import {findRetailOffers} from "./retail-specialist-core.mjs";
const key=process.env.APISYSTEM_TEST_KEY;
if(!key)throw Error("Missing test credential");
const queries=["Яндекс лампочка 1300 лм","Samsung SSD 990 Pro 2TB","Philips Sonicare 3100"];
let calls=0;
const fetchSample=async(u,opts)=>{
 if(calls>=24)throw Error("Sample request budget reached");
 if(!["wb.apisystem.name","market.apisystem.name"].includes(u.hostname))throw Error("Unexpected host");
 const v=new URL(u);
 v.protocol="http:";
 calls++;
 return fetch(v,{...opts,redirect:"error"});
};
const safeUrl=value=>{try{const u=new URL(value);u.search="";u.hash="";return u.href;}catch{return null;}};
const report=[];
for(const query of queries)for(const shop of ["wb","ym"]){
 if(calls)await new Promise(r=>setTimeout(r,1500));
 const result=await findRetailOffers({shop,query,key,geoId:2,gps:"59.9343,30.3351",maxPages:4},fetchSample);
 const offers=result.offers.slice(0,8).map(o=>({title:o.title,price:o.price,regular:o.price_regular,condition:o.condition,days:o.delivery_days,delivery:o.delivery,url:safeUrl(o.url)}));
 const data={shop,query,offers,audit:result.audit,calls};
 report.push(data);
 console.log(JSON.stringify(data).split(key).join("[redacted]"));
}
console.log("Requests:",calls);
