// Store-agnostic price/delivery mechanics; Ozon browser functions are injected.
const months = "января февраля марта апреля мая июня июля августа сентября октября ноября декабря".split(" ");
const canon = s => String(s??"").normalize("NFKC").toLowerCase().replace(/ё/g,"е")
  .replace(/\bmini\b/g,"мини").replace(/\bpro\b/g,"про").replace(/\bmax\b/g,"макс")
  .replace(/[^\p{L}\p{N}]+/gu," ").trim();
const words = s => canon(s).split(" ").filter(Boolean);
const editions = new Set(["про","макс","лайт","lite","ультра","ultra","plus","плюс"]);
const accessories = /^(?:чехол|кронштейн|держатель|подставка|крепление|кабель|адаптер|блок питания|защитное стекло|климатический модуль|аккумулятор|пульт|сумка)(?:\s|$)/u;
export function matches(query,title) {
  const q=words(query), t=words(title), ts=new Set(t), qs=new Set(q);
  if (!q.length || !t.length) return false;
  if (accessories.test(canon(title)) && !accessories.test(canon(query))) return false;
  if (q.some(w=>(/\d/.test(w)||editions.has(w))&&!ts.has(w))) return false;
  if (t.some(w=>editions.has(w)&&!qs.has(w))) return false;
  if (q.length===1) return ts.has(q[0]);
  return q.filter(w=>ts.has(w)).length >= Math.max(2,q.length-1);
}
export function shippingCost(value) {
  const s=String(value??"").replace(/\s/g," ").trim();
  if (/входит в заказ|бесплатно/i.test(s)) return 0;
  const m=s.match(/^\+?\s*(\d[\d ]*)\s*₽$/u);
  return m?Number(m[1].replace(/ /g,"")):null;
}
export function deliveryDays(value, today) {
  const s=canon(value);
  // A range is not a guaranteed arrival date. "From" is an earliest date.
  if (/[–—-]/u.test(String(value??"")) || /(?:^|\s)до(?:\s|$)/u.test(s) || /^с\s/u.test(s)) return null;
  if (s.startsWith("послезавтра")) return 2;
  if (s.startsWith("завтра")) return 1;
  if (s.startsWith("сегодня")) return 0;
  const m=s.match(/^(\d{1,2})\s+([а-я]+)$/u);
  if (!m||!months.includes(m[2])) return null;
  const [y,mo,d]=today.split("-").map(Number);
  const start=Date.UTC(y,mo-1,d);
  const month=months.indexOf(m[2]), day=Number(m[1]);
  let end=Date.UTC(y,month,day);
  if(end<start&&mo===12&&month===0)end=Date.UTC(y+1,month,day);
  if(new Date(end).getUTCDate()!==day||end<start)return null;
  return (end-start)/86400000;
}
const price = v => Number.isFinite(v)&&v>0;
const dateMoscow = () => new Intl.DateTimeFormat("sv-SE",{timeZone:"Europe/Moscow",year:"numeric",month:"2-digit",day:"2-digit"}).format(new Date());
export async function findOffers({query,alternateQuery,city,maxDays=3,lateRatio=0.2,pages=5},api,emit=()=>{}) {
  const start=Date.now(), found=new Map(), issues=[];
  const today=dateMoscow();
  let scanned=0,checked=0,skipped=0;
  let location=await api.getLocation();
  if(city && canon(location?.city)!==canon(city)) {await api.setCity(city);location=await api.getLocation();}
  if(city && canon(location?.city)!==canon(city)) throw new Error('Requested delivery city not confirmed');
  async function scan(label,opts) {
    try {
      const r=await api.search({query,sort:"price",pages,limit:180,compact:false,dedupeVariants:false,dedupeConfigs:false,...opts});
      const rows=r.items??[];scanned+=rows.length;
      const hit=rows.filter(x=>x.sku&&price(x.price)&&matches(query,x.name));
      for(const x of hit) {
        const old=found.get(x.sku);if(!old||x.price<old.price)found.set(x.sku,x);
      }
      emit({event:"scan",label,scanned:rows.length,matches:hit.length});
      return hit;
    }catch(e){issues.push(label+": "+String(e.message).slice(0,180));return [];}
  }
  const seed=await scan("reference",{sort:"popular",pages:1,limit:36});
  const p=seed.map(x=>x.price).sort((a,b)=>a-b), n=p.length;
  const lower=n>=3?Math.floor((p[Math.floor((n-1)/2)]+p[Math.floor(n/2)])/4):0;
  await scan("main",lower?{priceMin:lower}:{});
  if(alternateQuery)await scan("alternate",{query:alternateQuery});
  if(lower)await scan("low-price-audit",{priceMax:lower});
  const candidates=[...found.values()].sort((a,b)=>a.price-b.price);
  const offers=[];let best=null;
  // Listing price is not guaranteed to equal detail price. The price frontier
  // is a bounded heuristic only; never report exhaustive coverage.
  for(const x of candidates) {
    if(checked>=60){skipped++;continue;}
    if(best!==null&&x.price>best*1.05){skipped++;continue;}
    checked++;
    try {
      const d=await api.details({product:x.sku,compact:false});
      if(!d.available||!price(d.price)||!matches(query,d.name))continue;
      const options=d.delivery?.options?.length?d.delivery.options:[d.delivery?.best].filter(Boolean);
      const deliveries=options.map(o=>({label:o.title,date:o.dateText,kind:o.kind,days:deliveryDays(o.dateText,today),fee:shippingCost(o.priceBadge),badge:o.priceBadge})).filter(o=>o.date);
      deliveries.sort((a,b)=>(a.days===null)-(b.days===null)||((a.days??999)<=maxDays?0:1)-((b.days??999)<=maxDays?0:1)||(a.fee??Infinity)-(b.fee??Infinity)||(a.days??999)-(b.days??999));
      const selected=deliveries[0]??null;
      const total=selected?.fee!=null?d.price+selected.fee:null;
      if(total!==null&&selected.days!==null&&selected.days<=maxDays)best=best===null?total:Math.min(best,total);
      const offer={sku:String(d.sku),name:d.name,url:d.url,price:d.price,regular:d.priceRegular??null,listingPrice:x.price,
        image:d.images?.[0]??"",city:d.location?.city??location?.city??null,delivery:selected,deliveries,total};
      offers.push(offer);emit({event:"offer",offer});
    }catch(e){issues.push(String(x.sku)+": "+String(e.message).slice(0,180));}
  }
  for(const o of offers) {
    const days=o.delivery?.days;
    o.eligibility=days==null||o.total==null?"unknown":days<=maxDays?"timely":best!==null&&o.total<=best*(1-lateRatio)?"late-discount":best===null?"no-timely-reference":"late";
  }
  return {offers:offers.sort((a,b)=>(a.total??a.price)-(b.total??b.price)),location,
    audit:{source:"ozon_mcp",seconds:(Date.now()-start)/1000,scanned,candidates:candidates.length,checked,skipped,lower,issues,coverageComplete:false}};
}
