// Diagnostic only: no credentials, no purchase actions, no production access.
import { fetchSearch, fetchDetail } from "../upstream-wb/src/wb.js";
import { parseSearch, parseDetail } from "../upstream-wb/src/parse.js";
const start=Date.now();
const query="Яндекс Станция Мини 3 Про";
const exact=t=>/станци[яию]\s+мини\s+3\s+про/i.test(t||"")&&!/^(чехол|держатель|крепление|кронштейн|подставка|климатический модуль|кабель)/i.test(t||"");
try {
 const listing=parseSearch(await fetchSearch(query,100),100);
 const candidates=listing.items.filter(x=>exact(x.name)&&x.price>0).sort((a,b)=>a.price-b.price);
 console.log(JSON.stringify({event:"listing",shop:"wb",query,seconds:(Date.now()-start)/1000,region:"Moscow (upstream default)",scanned:listing.count,candidates:candidates.length,cheapest:candidates.slice(0,3)}));
 if(!candidates.length)throw Error("No exact matching listings");
 const confirmed=[];
 for(const x of candidates.slice(0,3)){
   try {
    const d=parseDetail(await fetchDetail(x.nm));
    console.log(JSON.stringify({event:"detail",shop:"wb",nm:x.nm,detail:d}));
    if(d&&d.nm===x.nm&&exact(d.name)&&d.inStock&&d.price>0)confirmed.push(d);
   }catch(e){console.log(JSON.stringify({event:"detail_error",nm:x.nm,error:String(e.message)}));break;}
 }
 console.log(JSON.stringify({event:"summary",shop:"wb",seconds:(Date.now()-start)/1000,verified_prices:confirmed.length,delivery_verified:0,region_verified:false,ready:false,reason:"Upstream parser does not provide delivery or requested region"}));
 process.exitCode=2;
}catch(e){console.log(JSON.stringify({event:"failure",shop:"wb",seconds:(Date.now()-start)/1000,error:String(e.message)}));process.exitCode=2;}
