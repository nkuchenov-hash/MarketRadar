import { search, details } from "/app/src/ozon.js";
import { shutdown } from "/app/src/browser.js";
import { performance } from "node:perf_hooks";

const input = [];
let bytes = 0;
for await (const chunk of process.stdin) {
  bytes += chunk.length;
  if (bytes > 8192) throw new Error("Config too large");
  input.push(chunk);
}
const cfg = JSON.parse(Buffer.concat(input).toString("utf8"));
const boundedText = x => typeof x === "string" && x.trim().length > 0 && x.length <= 160;
if (!boundedText(cfg.query) || !Array.isArray(cfg.matchAny) ||
    cfg.matchAny.length < 1 || cfg.matchAny.length > 12 ||
    !cfg.matchAny.every(boundedText) ||
    (cfg.alternateQuery != null && !boundedText(cfg.alternateQuery))) {
  throw new Error("Invalid query/model configuration");
}
const pages = Math.min(5, Math.max(1, Number(cfg.pages) || 5));
const ratio = Number(cfg.lowerRatio ?? 0.5);
if (!Number.isFinite(ratio) || ratio < 0 || ratio > 0.8) throw new Error("Invalid lowerRatio");
const normalize = x => String(x ?? "").normalize("NFKC").toLowerCase().replace(/ё/g,"е").replace(/[^\p{L}\p{N}]/gu,"");
const matches = x => cfg.matchAny.some(t => normalize(x.name).includes(normalize(t)));
const priced = x => Number.isFinite(x.price) && x.price > 0;
const found = new Map();
const log = x => console.log(JSON.stringify(x));
const allStart = performance.now();
let incomplete = false;
async function scan(label, options) {
  const start = performance.now();
  try {
    const r = await search({query:cfg.query, sort:"price", pages, limit:180,
      compact:false, dedupeVariants:false, dedupeConfigs:false, ...options});
    const items = r.items ?? [];
    const candidates = items.filter(matches).filter(priced);
    for (const x of candidates) {
      const old = found.get(x.sku);
      if (!old || x.price < old.price) found.set(x.sku,x);
    }
    const prices = items.filter(priced).map(x=>x.price);
    log({event:"search",label,seconds:(performance.now()-start)/1000,options,
      scanned:items.length,matching:candidates.length,
      min:prices.length?Math.min(...prices):null,max:prices.length?Math.max(...prices):null,
      hint:r.hint??null,sort:r.sort??null,sponsoredOnly:r.sponsoredOnly??null,
      candidates:candidates.map(x=>({sku:x.sku,name:x.name,price:x.price}))});
    return candidates;
  } catch(e) {
    incomplete = true;
    log({event:"error",label,message:String(e.message)});
    return [];
  }
}
try {
  const seed = await scan("reference",{sort:"popular",pages:1,limit:36});
  const prices = seed.map(x=>x.price).sort((a,b)=>a-b);
  const n = prices.length;
  const median = n>=3?(prices[Math.floor((n-1)/2)]+prices[Math.floor(n/2)])/2:null;
  const lower = median===null?0:Math.floor(median*ratio);
  log({event:"threshold",referenceCount:n,median,lower,ratio,hardExclusion:false});
  await scan("main",lower>0?{priceMin:lower}:{});
  if (cfg.alternateQuery) await scan("model-code",{query:cfg.alternateQuery});
  if (lower>0) await scan("below-threshold",{priceMax:lower});
  const candidates=[...found.values()].sort((a,b)=>a.price-b.price);
  const checked=[];
  let bestTotal=null;
  for (const x of candidates.slice(0,20)) {
    const start=performance.now();
    try {
      const d=await details({product:x.sku,compact:false});
      const b=d.delivery?.best;
      const shippingText=String(b?.priceBadge??"");
      const free=/бесплат/i.test(shippingText);
      const digits=shippingText.replace(/[^0-9]/g,"");
      const shipping=free?0:digits?Number(digits):null;
      const verified=matches(d)&&d.available===true&&priced(d);
      const total=verified&&shipping!==null?d.price+shipping:null;
      const record={event:"detail",sku:d.sku,name:d.name,price:d.price,
        regular:d.priceRegular??null,available:d.available,verified,shipping,total,
        date:b?.dateText??null,delivery:b??null,city:d.location?.city??null,
        url:d.url,seconds:(performance.now()-start)/1000};
      checked.push(record);log(record);
      if(total!==null&&b?.dateText&&(bestTotal===null||total<bestTotal))bestTotal=total;
    }catch(e){incomplete=true;log({event:"error",sku:x.sku,message:String(e.message)});}
  }
  const valid=checked.filter(x=>x.verified);
  log({event:"summary",seconds:(performance.now()-allStart)/1000,
    candidates:candidates.length,checked:checked.length,verified:valid.length,
    lowestProductPrice:valid.length?Math.min(...valid.map(x=>x.price)):null,
    bestKnownTotalWithDate:bestTotal,
    errors:incomplete,coverageComplete:false,
    note:"Diagnostic only: page and detail caps; delivery text not yet normalized to 3-day eligibility."});
  if(!valid.length)process.exitCode=2;
} finally {await shutdown();}
