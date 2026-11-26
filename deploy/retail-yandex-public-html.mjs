// First-party public HTML transport for Yandex Market. No credentials,
// undocumented protected endpoints, or access challenge retries.
import {load} from "cheerio";
import {productKey} from "./retail-firstparty-core.mjs";
const host="market.yandex.ru";
const sameShop=value=>{try{const u=new URL(value);return u.protocol==="https:"&&u.hostname===host&&!u.username&&!u.password&&!u.port;}catch{return false;}};
const rePrice=/(\d{1,3}(?:[\s\u00a0\u202f]\d{3})+|\d{3,7})\s*(?:₽|руб(?:лей|\.)?)/giu;
const reDate=/\b(?:через\s+\d{1,2}\s+(?:день|дня|дней)|\d{1,2}\s+(?:января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря))\b/iu;
const tidy=t=>String(t??"").replace(/\s+/gu," ").trim();
const months="января февраля марта апреля мая июня июля августа сентября октября ноября декабря".split(" ");
function link(raw,origin){
 try{const u=new URL(raw,origin);if(!sameShop(u.href)||!productKey("ym",u.href))return "";u.search="";u.hash="";return u.href;}catch{return "";}
}
function delivery($,root){
 const data=root.attr("data-zone-data")||root.attr("data-zone-params");
 if(data){
  try{
   const q=[JSON.parse(data)],dates=new Set();let count=0;
   while(q.length&&count++<2000){
    const v=q.pop();if(!v||typeof v!=="object")continue;
    for(const [k,x] of Object.entries(v)){
     if(k==="dateFrom"&&typeof x==="string"&&/^\d{4}-\d{2}-\d{2}$/.test(x))dates.add(x);
     if(x&&typeof x==="object")q.push(x);
    }
   }
   if(dates.size===1){const [,m,d]=[...dates][0].split("-").map(Number);if(m>=1&&m<=12&&d>=1&&d<=31)return d+" "+months[m-1];}
  }catch{}
 }
 const t=tidy(root.text());
 if(/(?:\d{1,2}\s*[-–—]\s*\d{1,2}|(?:^|\s)с\s+\d{1,2}\s+)/u.test(t))return "";
 const dates=new Set();
 for(const x of t.matchAll(/послезавтра|сегодня|завтра/giu))dates.add(x[0].toLowerCase());
 // Use a Unicode-aware lookaround: JS \b is ASCII-only for Cyrillic.
 for(const x of t.matchAll(/(?<![\p{L}\p{N}])(?:через\s+\d{1,2}\s+(?:день|дня|дней)|\d{1,2}\s+(?:января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря))(?![\p{L}\p{N}])/giu))dates.add(x[0].toLowerCase());
 return dates.size===1?[...dates][0]:"";
}
export function extractYandexHTML(html,origin){
 const $=load(html),results=new Map();
 $("script,style,noscript,svg").remove();
 if(/вы не робот|подтвердите, что вы человек|access denied|captcha/i.test($("title").text()+" "+$("body").text().slice(0,2000)))return {state:"blocked",products:[]};
 const anchors=$("a[href]").toArray().slice(0,5000);
 for(const a of anchors){
  const u=link($(a).attr("href"),origin);if(!u)continue;
  const key=productKey("ym",u);
  let root=$(a).closest('[data-baobab-name="productSnippet"],[data-auto="snippet"],article');
  if(!root.length)root=$(a);
  let selected=null;
  for(let i=0;i<9&&root.length;i++,root=root.parent()){
   const keys=new Set(root.find("a[href]").addBack("a[href]").toArray().map(el=>link($(el).attr("href"),origin)).filter(Boolean).map(x=>productKey("ym",x)));
   if(keys.size>1||!keys.has(key))break;
   const title=tidy(root.find('[data-auto*="snippet-title"],[data-auto="title"],h2,h3').first().text()||$(a).attr("aria-label")||$(a).attr("title")||root.find("img[alt]").first().attr("alt")||$(a).text());
   const t=tidy(root.text());
   if(!title||title.length>250||/₽|руб\.|^цена|^купить/iu.test(title)||t.length>2600)continue;
   const amounts=new Set();let boundary=0;
   for(const m of t.matchAll(rePrice)){
    const before=t.slice(Math.max(boundary,m.index-50),m.index),after=t.slice(m.index+m[0].length,m.index+m[0].length+35);
    const tail=after.match(/^\s*(?:в\s+сплит|сплит|в\s+рассрочку|\/\s*мес)/iu);
    boundary=m.index+m[0].length+(tail?.[0].length||0);
    if(/(?:частями|плат[её]ж\s+по|в\s+сплит|рассрочк[\p{L}]*|\d+\s*[×хx*])\s*$/iu.test(before)||tail)continue;
    const p=Number(m[1].replace(/\D/g,""));if(p>=100&&p<=50000000)amounts.add(p);
   }
   // Multiple unattributed amounts can be seller variants, crossed-out prices,
   // discounts or payments. Do not select an arbitrary minimum.
   if(amounts.size!==1)continue;
   let image="";try{const raw=root.find("img").first().attr("src")||root.find("img").first().attr("data-src");if(raw){const x=new URL(raw,origin);if(x.protocol==="https:")image=x.href;}}catch{}
   selected={url:u,title,price:[...amounts][0],delivery_hint:delivery($,root),image_url:image,price_condition:/цен[аы]\s+с\s+картой\s+яндекс\s+пэй/iu.test(t)?"Яндекс Пэй":"",source:"public_ssr_node"};
   if(selected.delivery_hint)break;
  }
  if(selected){const old=results.get(key);if(!old||(!old.delivery_hint&&selected.delivery_hint))results.set(key,selected);}
 }
 return {state:"ok",products:[...results.values()]};
}
export async function fetchYandexPublicHTML(url,ms,fetcher=fetch){
 if(!sameShop(url)||new URL(url).pathname!=="/search")throw Error("Invalid search URL");
 const res=await fetcher(url,{redirect:"manual",signal:AbortSignal.timeout(Math.max(1,Math.min(ms,6000))),headers:{Accept:"text/html","Accept-Language":"ru-RU,ru;q=0.9"}});
 if([401,403,429,498,503].includes(res.status)||res.status>=300&&res.status<400)return {state:"blocked",products:[],transport:"public_ssr_node",http_status:res.status};
 if(!res.ok||!res.headers.get("content-type")?.includes("text/html"))return {state:"partial",products:[],transport:"public_ssr_node",http_status:res.status};
 const reader=res.body.getReader(),chunks=[];let size=0;
 try{for(;;){const {done,value}=await reader.read();if(done)break;size+=value.length;if(size>8_000_000){await reader.cancel();return {state:"partial",products:[],transport:"public_ssr_node",http_status:res.status};}chunks.push(value);}}
 finally{reader.releaseLock();}
 return {...extractYandexHTML(Buffer.concat(chunks).toString("utf8"),url),transport:"public_ssr_node",http_status:res.status};
}
