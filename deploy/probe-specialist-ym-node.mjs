// A fresh read-only profile. Do not retry an explicit access challenge.
import {yandexSearch,yandexCard} from "../upstream-ym-node/src/yandex.js";
import {shutdown} from "../upstream-ym-node/src/browser.js";
const started=Date.now(),q="Яндекс Станция Мини 3 Про";
const exact=t=>/станци[яию]\s+мини\s+3\s+про/i.test(t||"")&&!/^(чехол|держатель|крепление|кронштейн|подставка|климатический модуль|кабель)/i.test(t||"");
const emit=o=>console.log(JSON.stringify(o));
try{
 const data=await yandexSearch({query:q,pages:3,limit:100,sortBy:"price"});
 const hits=data.items.filter(x=>exact(x.name)&&x.price>0).sort((a,b)=>a.price-b.price);
 emit({event:"listing",shop:"ym",seconds:(Date.now()-started)/1000,scanned:data.scanned,candidates:hits.length,cheapest:hits.slice(0,3)});
 if(!hits.length)throw Error("No exact matching listings");
 let checked=0;
 for(const x of hits.slice(0,3)){
   try{
     const d=await yandexCard({product:x.url});
     emit({event:"detail",shop:"ym",id:x.id,detail:d});
     // The upstream uses body-wide prices; do not label as independently verified.
     if(d.price>0&&exact(d.name))checked++;
   }catch(e){emit({event:"detail_error",id:x.id,error:String(e.message).slice(0,300)});break;}
 }
 emit({event:"summary",shop:"ym",seconds:(Date.now()-started)/1000,details_with_price:checked,verified_prices:0,delivery_verified:0,ready:false,reason:"Upstream card uses body-wide prices and exposes no delivery"});
}catch(e){emit({event:"failure",shop:"ym",seconds:(Date.now()-started)/1000,error:String(e.message).slice(0,300)});}
finally{await shutdown();}
process.exitCode=2;
