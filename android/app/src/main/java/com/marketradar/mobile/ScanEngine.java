package com.marketradar.mobile;

import android.os.Handler;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Experimental browsing within the phone's own WebView profile. No server receives cookies. */
final class ScanEngine {
    interface Listener {
        void status(Shop shop, String text);
        void offer(Offer offer);
        void done();
    }
    static final class Offer {
        final Shop shop;
        final String url, title, note;
        final int price;
        Offer(Shop shop,String url,String title,int price,String note) {
            this.shop=shop;this.url=url;this.title=title;this.price=price;this.note=note;
        }
    }
    static final class Candidate {
        final String url;
        final int listingPrice;
        Candidate(String url,int listingPrice){this.url=url;this.listingPrice=listingPrice;}
    }
    private static final String SNAPSHOT = """
        (() => {
          try {
            const body = document.body;
            const visible = e => {
              const r = e.getBoundingClientRect();
              return r.width > 0 && r.height > 0;
            };
            const allLinks = [...document.querySelectorAll('a[href]')].slice(0, 1800);
            const links = [];
            for (const a of allLinks) {
              if (!visible(a)) continue;
              const card = a.closest('article,li,[data-widget],[data-testid]') ||
                           a.parentElement?.parentElement;
              links.push({
                url: a.href,
                title: (a.getAttribute('title') || a.innerText || a.querySelector('img')?.alt || '').trim().slice(0, 260),
                context: (card?.innerText || '').trim().slice(0, 450)
              });
            }
            const structuredPrices=[];
            const put=(v,c,n,source)=>{
              const amount=Number(String(v??'').replace(/\\s/g,'').replace(',','.'));
              if(Number.isFinite(amount)&&amount>=100&&amount<=50000000)
                structuredPrices.push({amount,currency:String(c||''),productName:String(n||''),source});
            };
            const scanOffer=(o,n)=>{
              if(!o)return;
              if(Array.isArray(o)){o.forEach(v=>scanOffer(v,n));return;}
              if(typeof o!=='object')return;
              if(o.price!=null)put(o.price,o.priceCurrency||'RUB',n,'schema.org/Offer');
              const specs=Array.isArray(o.priceSpecification)?o.priceSpecification:[o.priceSpecification];
              for(const p of specs)if(p&&p.price!=null)
                put(p.price,p.priceCurrency||o.priceCurrency||'RUB',n,'schema.org/PriceSpecification');
            };
            const visit=(o,depth)=>{
              if(!o||depth>5)return;
              if(Array.isArray(o)){o.forEach(v=>visit(v,depth+1));return;}
              if(typeof o!=='object')return;
              const types=Array.isArray(o['@type'])?o['@type']:[o['@type']];
              if(types.some(t=>String(t||'').toLowerCase().split('/').pop()==='product'))
                scanOffer(o.offers,o.name||'');
              if(o['@graph'])visit(o['@graph'],depth+1);
            };
            for(const script of [...document.querySelectorAll('script[type="application/ld+json"]')].slice(0,25)){
              try{visit(JSON.parse(script.textContent),0)}catch(e){}
            }
            const meta=sel=>document.querySelector(sel)?.getAttribute('content')||'';
            const price=meta('meta[property="product:price:amount"]')||
                        meta('meta[itemprop="price"]')||
                        meta('meta[property="og:price:amount"]');
            const currency=meta('meta[property="product:price:currency"]')||
                           meta('meta[itemprop="priceCurrency"]')||
                           meta('meta[property="og:price:currency"]');
            if(price&&currency)put(price,currency,document.querySelector('h1')?.innerText||'','product-price-meta');
            return JSON.stringify({
              url: location.href, title: document.title,
              heading: document.querySelector('h1')?.innerText || '',
              text: (body?.innerText || '').slice(0, 42000),
              links: links.slice(0, 1200),
              structuredPrices
            });
          } catch (err) {
            return JSON.stringify({error:'Не удалось прочитать страницу'});
          }
        })()
        """;

    private static final Pattern PRICE = Pattern.compile(
        "(?<![0-9])([0-9]{1,3}(?:[\\s\\u00a0\\u202f][0-9]{3})+|[0-9]{3,7})\\s*(?:₽|руб)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private final WebView browser;
    private final Handler handler;
    private final Listener listener;
    private final ArrayList<Candidate> candidates = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    private boolean running=false;
    private String query="";
    private Shop shop=null;
    private int marketIndex=0, cardIndex=0, token=0, checked=0;
    private boolean details=false;
    private int maxCards=8, checkedMarket=0;
    private String expectedUrl="";
    private List<Shop> activeMarkets=Shop.ALL;

    ScanEngine(WebView browser, Handler handler, Listener listener) {
        this.browser=browser;this.handler=handler;this.listener=listener;
    }
    boolean isRunning(){return running;}
    void start(String q) { start(q,8,Shop.ALL); }
    void start(String q,int limit) { start(q,limit,Shop.ALL); }
    void start(String q,int limit,List<Shop> markets) {
        stop();
        activeMarkets=new ArrayList<>(markets);
        query=q.trim(); maxCards=Math.max(1,Math.min(12,limit)); running=true; marketIndex=0;checked=0;
        nextMarket();
    }
    void stop() { running=false;token++; }

    private void nextMarket() {
        if(!running)return;
        if(marketIndex>=activeMarkets.size()) {
            running=false;
            listener.done();
            return;
        }
        shop=activeMarkets.get(marketIndex++);
        candidates.clear();seen.clear();
        cardIndex=0;details=false;checkedMarket=0;expectedUrl="";
        listener.status(shop,"Ищем точную модель…");
        load(shop.searchUrl(query));
    }
    private void load(String url) {
        int request=++token;
        browser.loadUrl(url);
        handler.postDelayed(() -> {
            if(running&&request==token)failPage("Страница не ответила");
        },38000);
    }
    void onPageFinished(String url) {
        if(!running)return;
        // A stale completion callback from a previous card must not be
        // interpreted as a result for the card currently being loaded.
        if(details&&!cleanUrl(url).equals(cleanUrl(expectedUrl)))return;
        int request=token;
        handler.postDelayed(() -> {
            if(running&&request==token) {
                int sample=token;
                browser.evaluateJavascript(SNAPSHOT, value -> {
                    if(!running||sample!=token)return;
                    try {
                        Object decoded = new JSONTokener(value).nextValue();
                        JSONObject data = new JSONObject(String.valueOf(decoded));
                        consume(data);
                    }catch(Exception error){
                        failPage("Не удалось разобрать страницу");
                    }
                });
            }
        },2800);
    }
    void onPageError(String url) {
        if(!running)return;
        int request=token;
        handler.postDelayed(() -> {
            if(running&&request==token)failPage("Ошибка загрузки");
        },1500);
    }
    private void failPage(String message) {
        token++;
        if(details) {
            listener.status(shop,message+" · проверено карточек: "+checkedMarket);
            nextCandidate();
        } else {
            listener.status(shop,message);
            nextMarket();
        }
    }
    private void consume(JSONObject data) {
        if(!running)return;
        if(data.has("error")){failPage("Не удалось прочитать страницу");return;}
        String url=data.optString("url","");
        String text=data.optString("text","");
        String title=data.optString("title","");
        String body=(title+" "+text).toLowerCase(Locale.ROOT);
        if(!shop.allowed(url)){
            listener.status(shop,"Переадресация: возможно, требуется вход в магазин");
            nextMarket();return;
        }
        if(body.contains("подозрительная активность")||
            body.contains("похоже, нет соединения")||
            body.contains("вы не робот")||body.contains("captcha")||
            body.contains("access denied")||title.equals("403")) {
            listener.status(shop,"Магазин ограничил доступ");
            nextMarket();return;
        }
        if(details)readDetail(data,url);
        else readSearch(data);
    }
    private void readSearch(JSONObject data) {
        candidates.clear();
        candidates.addAll(selectCandidates(data.optJSONArray("links"),shop,query,maxCards));
        if(candidates.isEmpty()){
            listener.status(shop,"Точные карточки не обнаружены — это не значит, что товара нет");
            nextMarket();return;
        }
        details=true;
        listener.status(shop,"Отобрано "+candidates.size()+" карточек из выдачи. Проверяем товар…");
        nextCandidate();
    }

    /**
     * Do not just take the first N links in DOM order. Marketplaces show sponsored
     * cards and unrelated recommendations before the cheaper matching products.
     * Retain every exact-model product candidate, prefer cheaper listed offers,
     * then visit each selected product page to validate model and URL.
     */
    static ArrayList<Candidate> selectCandidates(JSONArray links,Shop shop,String query,int maxCards){
        Map<String,Candidate> unique=new LinkedHashMap<>();
        if(links==null||shop==null)return new ArrayList<>();
        for(int i=0;i<links.length();i++){
            JSONObject item=links.optJSONObject(i);
            if(item==null)continue;
            String url=item.optString("url","");
            if(!shop.productLink(url))continue;
            String title=item.optString("title","").trim();
            String context=item.optString("context","").trim();
            // Link text like 'Открыть' may require using its containing card.
            // The product page is always rechecked for the precise model.
            if(!exact(query,title) && !(title.length()<8&&exact(query,context)))continue;
            String clean=cleanUrl(url);
            int listingPrice=readCardPrice(context);
            Candidate previous=unique.get(clean);
            if(previous==null || (listingPrice>0 && (previous.listingPrice==0 ||
               listingPrice<previous.listingPrice))){
                unique.put(clean,new Candidate(clean,listingPrice));
            }
        }
        ArrayList<Candidate> ordered=new ArrayList<>(unique.values());
        ordered.sort(Comparator.comparingInt(c->c.listingPrice==0?
            Integer.MAX_VALUE:c.listingPrice));
        if(ordered.size()>maxCards)
            return new ArrayList<>(ordered.subList(0,maxCards));
        return ordered;
    }
    private void nextCandidate() {
        if(!running)return;
        if(cardIndex>=candidates.size()){
            listener.status(shop,"Проверено карточек: "+checkedMarket+". Покрытие неполное");
            nextMarket();return;
        }
        expectedUrl=candidates.get(cardIndex++).url;
        load(expectedUrl);
    }
    private void readDetail(JSONObject data,String url) {
        checked++;checkedMarket++;
        // Never report a price from a redirect or a different product URL.
        if(shop.productLink(url)&&cleanUrl(url).equals(cleanUrl(expectedUrl))){
            String title=data.optString("heading","").trim();
            if(title.isEmpty())title=data.optString("title","").trim();
            if(exact(query,title)){
                int detailPrice=readStructuredPrice(data.optJSONArray("structuredPrices"),query,title);
                int listingPrice=candidates.get(cardIndex-1).listingPrice;
                // A drastically cheaper listing amount is frequently a financing
                // teaser, an accessory, or a different card in the DOM. Do not
                // replace a product-page price with a suspiciously low amount.
                int safeListing=(listingPrice>0 &&
                    (detailPrice==0 || listingPrice>=detailPrice*0.65))?
                    listingPrice:0;
                int price=detailPrice>0&&safeListing>0?Math.min(detailPrice,safeListing):
                          detailPrice>0?detailPrice:safeListing;
                String note;
                if(price>0&&safeListing>0&&safeListing<detailPrice)
                    note="Цена из выдачи · в данных товара "+detailPrice+" ₽ · проверяйте корзину";
                else if(price>0&&safeListing>0&&detailPrice==0)
                    note="Цена из выдачи · товар совпадает · корзина не проверена";
                else if(price>0&&detailPrice>0)
                    note="Цена из данных товара · условия карты и доставки не проверены";
                else
                    note="Модель совпадает · цена не найдена";
                listener.offer(new Offer(shop,cleanUrl(url),title,price,note));
            }
        }
        listener.status(shop,"Карточек проверено: "+checkedMarket+" / "+candidates.size());
        nextCandidate();
    }
    private static String cleanUrl(String url){
        try{
            java.net.URI u=new java.net.URI(url);
            return new java.net.URI(u.getScheme(),u.getAuthority(),u.getPath(),null,null).toString();
        }catch(Exception e){return url;}
    }
    static int readStructuredPrice(JSONArray arr,String query,String pageTitle){
        if(arr==null||!exact(query,pageTitle))return 0;
        int best=Integer.MAX_VALUE;
        for(int i=0;i<arr.length();i++){
            JSONObject p=arr.optJSONObject(i);
            if(p==null)continue;
            String name=p.optString("productName","");
            String currency=p.optString("currency","");
            String source=p.optString("source","");
            if(!exact(query,name)||!exact(pageTitle,name))continue;
            if(!("RUB".equalsIgnoreCase(currency)||"RUR".equalsIgnoreCase(currency)))continue;
            // A PriceSpecification may describe installments, unit prices or financing.
            // It is not a comparable payable product price without more context.
            if(!source.equals("schema.org/Offer")&&!source.equals("product-price-meta"))continue;
            double value=p.optDouble("amount",0);
            if(value>=100&&value<=50000000)best=Math.min(best,(int)Math.round(value));
        }
        return best==Integer.MAX_VALUE?0:best;
    }
    static int readCardPrice(String context){
        if(context==null||context.isBlank()||context.length()>650)return 0;
        JSONArray priceLines=new JSONArray();
        for(String line:context.split("\\R")){
            String lower=line.toLowerCase(Locale.ROOT);
            if(lower.contains("кэшбэк")||lower.contains("кешбэк")||
               lower.contains("балл")||lower.contains("бонус")||
               lower.contains("рассроч")||lower.contains("кредит")||
               lower.contains("долями")||lower.contains("сплит")||
               lower.contains("/ мес")||lower.contains("в месяц")||
               lower.contains("доставка"))continue;
            priceLines.put(line);
        }
        return readPrice(priceLines);
    }
    static int readPrice(JSONArray snippets){
        if(snippets==null)return 0;
        int best=Integer.MAX_VALUE;
        for(int i=0;i<snippets.length();i++){
            String line=snippets.optString(i,"").toLowerCase(Locale.ROOT);
            if(line.contains("мес")||line.contains("в месяц")||
                line.contains("рассроч")||line.contains("кешбэк")||
                line.contains("кэшбэк")||line.contains("балл"))continue;
            Matcher match=PRICE.matcher(line);
            if(match.find()){
                try{
                    int value=Integer.parseInt(match.group(1).replaceAll("[^0-9]",""));
                    if(value>=100&&value<=50000000)best=Math.min(best,value);
                }catch(NumberFormatException ignored){}
            }
        }
        return best==Integer.MAX_VALUE?0:best;
    }
    static String norm(String input){
        return (input==null?"":input.toLowerCase(Locale.ROOT))
          .replace('ё','е').replace("mini","мини").replace("pro","про")
          .replace("ultra","ультра").replace("max","макс")
          .replace("plus","плюс").replace("lite","лайт")
          .replaceAll("[^\\p{L}\\p{N}]+"," ").trim().replaceAll("\\s+"," ");
    }
    static boolean exact(String query,String title){
        String q=norm(query),t=norm(title);
        if(q.isEmpty()||t.isEmpty())return false;
        Set<String> queryWords=new HashSet<>(java.util.Arrays.asList(q.split(" ")));
        Set<String> titleWords=new HashSet<>(java.util.Arrays.asList(t.split(" ")));
        // Edition, colour and condition are part of an exact product match.
        for(String variant:new String[]{"макс","ультра","плюс","лайт","fe"}){
            if(titleWords.contains(variant)&&!queryWords.contains(variant))return false;
        }
        for(String condition:new String[]{"уценка","восстановленный","refurbished","витринный"}){
            if(titleWords.contains(condition)&&!queryWords.contains(condition))return false;
        }
        if(titleWords.contains("бу")&&!queryWords.contains("бу"))return false;
        if(t.contains("б у")&&!q.contains("б у"))return false;
        String[][] colorNames={
            {"черный","черная","черное","black","blk"},
            {"белый","белая","белое","white","wht"},
            {"синий","синяя","синее","blue","blu"},
            {"серый","серая","серое","gray","grey","gry"},
            {"зеленый","зеленая","зеленое","green","grn"},
            {"красный","красная","красное","red"},
            {"розовый","розовая","розовое","pink"},
            {"фиолетовый","фиолетовая","фиолетовое","purple"}
        };
        for(String[] group:colorNames){
            boolean requested=false,matched=false;
            for(String alias:group){
                if(queryWords.contains(alias))requested=true;
                if(titleWords.contains(alias)||t.contains("00059"+alias))matched=true;
            }
            if(requested&&!matched)return false;
        }
        String[] accessories={"чехол","крепление","кронштейн","подставка","кабель","адаптер",
             "держатель","пульт","наклейка","защитное стекло","корпус","запчасть"};
        for(String word:accessories)if(t.contains(word)&&!q.contains(word))return false;
        boolean hasQueryColor=false;
        for(String[] group:colorNames){
            for(String alias:group)if(queryWords.contains(alias)){hasQueryColor=true;break;}
        }
        if(hasQueryColor){
            for(String[] group:colorNames){
                boolean thisRequested=false, thisInTitle=false;
                for(String alias:group){
                    if(queryWords.contains(alias))thisRequested=true;
                    if(titleWords.contains(alias))thisInTitle=true;
                }
                if(thisInTitle&&!thisRequested)return false;
            }
        }
        if(q.contains("мини 3 про")||q.contains("yndx 00059")){
            for(String color:new String[]{"синий","черный","серый","зеленый","белый"}){
                if(q.contains(color)&&!t.contains(color)&&
                   !(color.equals("синий")&&t.contains("blu"))&&
                   !(color.equals("черный")&&t.contains("blk"))&&
                   !(color.equals("серый")&&t.contains("gry"))&&
                   !(color.equals("зеленый")&&t.contains("grn")))return false;
            }
            return t.contains("мини 3 про")||
                 t.matches(".*yndx 0*0059(?:blu|blk|gry|grn)?.*");
        }
        String[] terms=q.split(" ");
        String[] words=t.split(" ");
        Set<String> all=new HashSet<>(java.util.Arrays.asList(words));
        int required=0,ok=0;
        for(String term:terms){
            if(term.length()<3&&!term.matches(".*[0-9].*"))continue;
            required++;
            boolean isColor=false,matchedColor=false;
            for(String[] group:colorNames){
                boolean alias=false;
                for(String word:group)if(word.equals(term))alias=true;
                if(alias){
                    isColor=true;
                    for(String word:group)
                        if(titleWords.contains(word)||t.contains("00059"+word))
                            matchedColor=true;
                    break;
                }
            }
            if(isColor){if(matchedColor)ok++;else return false;continue;}
            if(term.matches(".*[0-9].*")&&!all.contains(term))return false;
            if(all.contains(term))ok++;
        }
        return required>0&&ok>=Math.ceil(required*0.8);
    }
}
