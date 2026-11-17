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
    // Search and product details use DISTINCT, minimal DOM extraction paths.
    // A previous universal scraper walked thousands of links and returned
    // 42,000 characters of page text for EVERY opened product page.
    private static final String SEARCH_SNAPSHOT = """
        (() => {
          try {
            const body=document.body;
            const links=[];
            const pricePattern=/[0-9][0-9\\s\\u00a0\\u202f]*\\s*(?:₽|руб)/i;
            const productPath=/(?:\\/product\\/|\\/product--|\\/catalog\\/\\d+\\/detail|\\/card\\/|\\/offer\\/)/i;
            const visible=e=>{
              const r=e.getBoundingClientRect();
              return r.width>0&&r.height>0;
            };
            const tileText=a=>{
              let node=a;
              for(let level=0;level<7&&node&&node!==body;level++,node=node.parentElement){
                const text=(node.innerText||'').trim();
                if(text.length>12&&text.length<=700&&pricePattern.test(text)&&
                   (node.querySelector('img')||node.querySelector('picture')))
                  return text;
              }
              return '';
            };
            for(const a of document.querySelectorAll('a[href]')){
              if(links.length>=1600)break;
              if(!visible(a))continue;
              let url;
              try{url=new URL(a.href)}catch(e){continue;}
              if(url.protocol!=='https:'||!productPath.test(url.pathname))continue;
              const img=a.querySelector('img');
              const title=(a.getAttribute('title')||a.innerText||img?.alt||
                           a.getAttribute('aria-label')||'').trim().slice(0,300);
              const context=tileText(a);
              links.push({url:url.href,title,context:context.slice(0,700)});
            }
            return JSON.stringify({
              url:location.href,
              title:document.title,
              text:(body?.innerText||'').slice(0,1800),
              links
            });
          }catch(e){
            return JSON.stringify({error:'Не удалось прочитать выдачу'});
          }
        })()
        """;

    private static final String DETAIL_SNAPSHOT = """
        (() => {
          try {
            const body=document.body;
            const structuredPrices=[];
            const put=(value,currency,name,source)=>{
              const amount=Number(String(value??'').replace(/\\s/g,'').replace(',','.'));
              if(Number.isFinite(amount)&&amount>=100&&amount<=50000000)
                structuredPrices.push({
                  amount,currency:String(currency||''),
                  productName:String(name||''),source
                });
            };
            const scanOffer=(offer,name)=>{
              if(!offer)return;
              if(Array.isArray(offer)){offer.forEach(v=>scanOffer(v,name));return;}
              if(typeof offer!=='object')return;
              if(offer.price!=null)put(offer.price,offer.priceCurrency||'RUB',
                                      name,'schema.org/Offer');
              const specifications=Array.isArray(offer.priceSpecification)?
                offer.priceSpecification:[offer.priceSpecification];
              for(const item of specifications){
                if(item&&item.price!=null)
                  put(item.price,item.priceCurrency||offer.priceCurrency||'RUB',
                      name,'schema.org/PriceSpecification');
              }
            };
            const visit=(node,depth)=>{
              if(!node||depth>5)return;
              if(Array.isArray(node)){node.forEach(v=>visit(v,depth+1));return;}
              if(typeof node!=='object')return;
              const types=Array.isArray(node['@type'])?node['@type']:[node['@type']];
              if(types.some(t=>String(t||'').toLowerCase().split('/').pop()==='product'))
                scanOffer(node.offers,node.name||'');
              if(node['@graph'])visit(node['@graph'],depth+1);
            };
            for(const script of document.querySelectorAll('script[type="application/ld+json"]')){
              try{visit(JSON.parse(script.textContent),0)}catch(e){}
            }
            const meta=selector=>document.querySelector(selector)?.getAttribute('content')||'';
            const price=meta('meta[property="product:price:amount"]')||
                        meta('meta[itemprop="price"]')||
                        meta('meta[property="og:price:amount"]');
            const currency=meta('meta[property="product:price:currency"]')||
                           meta('meta[itemprop="priceCurrency"]')||
                           meta('meta[property="og:price:currency"]');
            if(price&&currency)
              put(price,currency,document.querySelector('h1')?.innerText||'',
                  'product-price-meta');
            return JSON.stringify({
              url:location.href,
              title:document.title,
              heading:document.querySelector('h1')?.innerText||'',
              text:(body?.innerText||'').slice(0,1800),
              structuredPrices
            });
          }catch(e){
            return JSON.stringify({error:'Не удалось прочитать карточку товара'});
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
    private boolean timeLimitReached=false;
    private String query="";
    private Shop shop=null;
    private int marketIndex=0, cardIndex=0, token=0, checked=0;
    private boolean details=false;
    private int maxCards=8, checkedMarket=0;
    private String expectedUrl="";
    private List<Shop> activeMarkets=Shop.ALL;
    // Search-result grids virtualize offscreen items. Collect several viewport
    // snapshots before applying the candidate limit; otherwise an exact,
    // cheaper listing below the first fold can never enter the comparison.
    private final Map<String,Candidate> searchPool=new LinkedHashMap<>();
    private int scrollPass=0;
    private boolean searchSampling=false;
    private int hydrationRetries=0;

    ScanEngine(WebView browser, Handler handler, Listener listener) {
        this.browser=browser;this.handler=handler;this.listener=listener;
    }
    boolean isRunning(){return running;}
    boolean wasTimeLimited(){return timeLimitReached;}
    void start(String q) { start(q,8,Shop.ALL); }
    void start(String q,int limit) { start(q,limit,Shop.ALL); }
    void start(String q,int limit,List<Shop> markets) {
        stop();
        activeMarkets=new ArrayList<>(markets);
        query=q.trim();
        maxCards=Math.max(1,Math.min(40,limit));
        running=true;
        marketIndex=0;
        checked=0;
        timeLimitReached=false;
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
        scrollPass=0;searchSampling=false;searchPool.clear();
        hydrationRetries=0;
        listener.status(shop,"Ищем точную модель…");
        load(shop.searchUrl(query));
    }
    private void load(String url) {
        int request=++token;
        browser.loadUrl(url);
        handler.postDelayed(() -> {
            if(running&&request==token)failPage("Страница не ответила");
        },18000);
    }
    void onPageFinished(String url) {
        if(!running)return;
        // Ignore completion callbacks from the previous marketplace. Both
        // search and product pages share one WebView, so a prior host can
        // finish after the next shop has already started loading.
        if(!details && !shop.allowed(url))return;
        // A stale completion callback from a previous card must not be
        // interpreted as a result for the card currently being loaded.
        if(!details&&searchSampling)return;
        if(details&&!sameProductPage(shop,expectedUrl,url)){
            // Marketplace may redirect a product link to search or a login page.
            // Check the actual WebView destination before timing out this card.
            final int request=token;
            handler.postDelayed(()->{
                if(running&&request==token&&details&&
                   !sameProductPage(shop,expectedUrl,browser.getUrl()))
                    failPage("Магазин перенаправил карточку");
            },1300);
            return;
        }
        int request=token;
        if(!details)searchSampling=true;
        handler.postDelayed(() -> {
            if(running&&request==token) {
                int sample=token;
                browser.evaluateJavascript(details?DETAIL_SNAPSHOT:SEARCH_SNAPSHOT, value -> {
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
        },160);
    }
    void onPageError(String url) {
        if(!running||!shop.allowed(url))return;
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
        // Combine all scroll snapshots, not only the initial screen and not
        // only the first ten DOM links. Each candidate still needs its own
        // product page verification before it can become a visible offer.
        mergeSearchSnapshot(searchPool,data.optJSONArray("links"),shop,query);
        final int maxScrolls=maxCards<=12?3:8;
        if(scrollPass<maxScrolls){
            scrollPass++;
            listener.status(shop,"Просматриваем выдачу: "+scrollPass+"/"+maxScrolls+
                " · подходящих ссылок "+searchPool.size());
            scrollSearchPage();
            return;
        }
        candidates.clear();
        candidates.addAll(rankCandidates(searchPool.values(),maxCards));
        searchSampling=false;
        if(candidates.isEmpty()){
            listener.status(shop,"Подходящие ссылки не найдены в просмотренной выдаче");
            nextMarket();return;
        }
        details=true;
        listener.status(shop,"В выдаче "+searchPool.size()+" подходящих ссылок. Проверяем "+
            candidates.size()+" карточек товара…");
        nextCandidate();
    }

    private void scrollSearchPage(){
        int request=token;
        browser.evaluateJavascript(
            "window.scrollBy(0, Math.max(window.innerHeight * 1.35, 850));",
            ignored -> handler.postDelayed(()->{
                if(!running||request!=token||details)return;
                browser.evaluateJavascript(SEARCH_SNAPSHOT,value->{
                    if(!running||request!=token||details)return;
                    try{
                        Object decoded=new JSONTokener(value).nextValue();
                        JSONObject data=new JSONObject(String.valueOf(decoded));
                        if(data.has("error")){failPage("Не удалось прочитать выдачу");return;}
                        String url=data.optString("url","");
                        if(!shop.allowed(url)){failPage("Магазин перенаправил выдачу");return;}
                        readSearch(data);
                    }catch(Exception e){
                        failPage("Не удалось прочитать результаты прокрутки");
                    }
                });
            },450)
        );
    }

    static void mergeSearchSnapshot(Map<String,Candidate> pool,JSONArray links,
            Shop shop,String query){
        for(Candidate candidate:selectCandidates(links,shop,query,250)){
            Candidate old=pool.get(candidate.url);
            if(old==null || (candidate.listingPrice>0 &&
                (old.listingPrice<=0 || candidate.listingPrice<old.listingPrice)))
                pool.put(candidate.url,candidate);
        }
    }

    static ArrayList<Candidate> rankCandidates(
            java.util.Collection<Candidate> values,int limit){
        ArrayList<Candidate> ordered=new ArrayList<>(values);
        ordered.sort(Comparator.comparingInt(c->c.listingPrice<=0?
            Integer.MAX_VALUE:c.listingPrice));
        if(ordered.size()>limit)
            return new ArrayList<>(ordered.subList(0,limit));
        return ordered;
    }

    /**
     * A marketplace may load product cards only while scrolling. Collect all
     * exact-model links across snapshots and rank by the visible listing
     * price before opening the limited number of candidate detail pages.
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
            if(!exact(query,title) && !(title.length()<8&&exact(query,context)))continue;
            String clean=cleanUrl(url);
            int listingPrice=readCardPrice(context);
            Candidate previous=unique.get(clean);
            if(previous==null || (listingPrice>0 && (previous.listingPrice==0 ||
               listingPrice<previous.listingPrice))){
                unique.put(clean,new Candidate(clean,listingPrice));
            }
        }
        return rankCandidates(unique.values(),maxCards);
    }

    private void nextCandidate() {
        if(!running)return;
        if(cardIndex>=candidates.size()){
            listener.status(shop,"Обработано "+checkedMarket+
                " карточек из "+candidates.size()+
                ". Покрытие рынка не гарантируется");
            nextMarket();return;
        }
        expectedUrl=candidates.get(cardIndex++).url;
        load(expectedUrl);
    }
    private void readDetail(JSONObject data,String url) {
        checked++;checkedMarket++;
        // Never report a price from a redirect or a different product URL.
        if(sameProductPage(shop,expectedUrl,url)){
            String title=data.optString("heading","").trim();
            if(title.isEmpty())title=data.optString("title","").trim();
            if(exact(query,title)){
                int detailPrice=readStructuredPrice(data.optJSONArray("structuredPrices"),query,title);
                int listingPrice=candidates.get(cardIndex-1).listingPrice;
                // A drastically cheaper listing amount is frequently a financing
                // teaser, an accessory, or a different card in the DOM. Do not
                // replace a product-page price with a suspiciously low amount.
                int safeListing=validListingPrice(detailPrice,listingPrice);
                int price=compareCardAndListingPrice(detailPrice,listingPrice);
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
    static int validListingPrice(int pagePrice,int listingPrice){
        if(listingPrice<=0)return 0;
        return pagePrice>0&&listingPrice<pagePrice*0.65?0:listingPrice;
    }

    static int compareCardAndListingPrice(int pagePrice,int listingPrice){
        int listing=validListingPrice(pagePrice,listingPrice);
        if(pagePrice>0&&listing>0)return Math.min(pagePrice,listing);
        return pagePrice>0?pagePrice:listing;
    }

    static boolean sameProductPage(Shop shop,String expected,String actual){
        if(shop==null||!shop.productLink(expected)||!shop.productLink(actual))return false;
        if(cleanUrl(expected).equals(cleanUrl(actual)))return true;
        try{
            String first=new java.net.URI(expected).getPath();
            String second=new java.net.URI(actual).getPath();
            if(shop.id.equals("wb")){
                java.util.regex.Pattern id=java.util.regex.Pattern.compile("/catalog/(\\d+)/detail(?:\\.aspx)?/?",java.util.regex.Pattern.CASE_INSENSITIVE);
                Matcher a=id.matcher(first),b=id.matcher(second);
                return a.find()&&b.find()&&a.group(1).equals(b.group(1));
            }
            if(shop.id.equals("ozon")){
                java.util.regex.Pattern id=java.util.regex.Pattern.compile("(?:-|/)(\\d{6,})/?$");
                Matcher a=id.matcher(first),b=id.matcher(second);
                return a.find()&&b.find()&&a.group(1).equals(b.group(1));
            }
            if(shop.id.equals("ym")){
                java.util.regex.Pattern id=java.util.regex.Pattern.compile("/(\\d{6,})/?$");
                Matcher a=id.matcher(first),b=id.matcher(second);
                return a.find()&&b.find()&&a.group(1).equals(b.group(1));
            }
        }catch(Exception ignored){}
        return false;
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
        // A matching model name inside "accessory FOR model" is NOT the
        // requested product. Generic containment caused a 2 137 ₽ climate
        // accessory to be ranked above the actual Mini 3 Pro speaker.
        String[] accessories={"чехол","крепление","кронштейн","подставка",
             "кабель","адаптер","держатель","пульт","наклейка","корпус",
             "запчасть","модуль","аккумулятор","блок","зарядка","зарядное",
             "батарея","подставки","переходник","кейс","футляр",
             "насадка","крышка","плата","разъем","крепеж","ремешок",
             "защита","пленка","стекло","комплект","термостат","датчик",
             "климатический","настенный","коробка","крепления",
             "аксессуар","аксессуары","набор","совместимый","совместимая"};
        for(String word:accessories){
            if(titleWords.contains(word)&&!queryWords.contains(word))return false;
        }
        if((t.contains("для станции")||t.contains("для яндекс")||
            t.contains("совместим")||t.contains("подходит для")) &&
           !(q.contains("для станции")||q.contains("для яндекс")||
             q.contains("совместим")||q.contains("подходит для")))return false;
        // Protect all exact-model queries from obvious product-family upsells.
        for(String unrelated:new String[]{"мини 3 с","мини 3 без про"}){
            if(t.contains(unrelated)&&!q.contains(unrelated))return false;
        }
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
