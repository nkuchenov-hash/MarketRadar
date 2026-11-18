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
        void session(Shop shop,SessionProbe.Verdict evidence);
        void offer(Offer offer);
        // Called before a candidate can change the price ceiling.
        // null indicates local-AI failure, not an identity mismatch.
        void assess(Offer offer,java.util.function.Consumer<Boolean> verdict);
        void done();
    }
    static final class Offer {
        final Shop shop;
        final String url, title, note, deliveryHint, priceCondition, imageUrl;
        final int price;
        Offer(Shop shop,String url,String title,int price,String note) {
            this(shop,url,title,price,note,"","");
        }
        Offer(Shop shop,String url,String title,int price,String note,String deliveryHint) {
            this(shop,url,title,price,note,deliveryHint,"");
        }
        Offer(Shop shop,String url,String title,int price,String note,
              String deliveryHint,String priceCondition) {
            this(shop,url,title,price,note,deliveryHint,priceCondition,"");
        }
        Offer(Shop shop,String url,String title,int price,String note,
              String deliveryHint,String priceCondition,String imageUrl) {
            this.shop=shop;this.url=url;this.title=title;this.price=price;
            this.note=note;this.deliveryHint=deliveryHint;
            this.priceCondition=priceCondition==null?"":priceCondition;
            this.imageUrl=imageUrl==null?"":imageUrl;
        }
    }
    static final class Candidate {
        final String url,deliveryHint,priceCondition,imageUrl;
        final int listingPrice;
        Candidate(String url,int listingPrice){this(url,listingPrice,"","");}
        Candidate(String url,int listingPrice,String deliveryHint){
            this(url,listingPrice,deliveryHint,"");
        }
        Candidate(String url,int listingPrice,String deliveryHint,String priceCondition){
            this(url,listingPrice,deliveryHint,priceCondition,"");
        }
        Candidate(String url,int listingPrice,String deliveryHint,
                  String priceCondition,String imageUrl){
            this.url=url;this.listingPrice=listingPrice;
            this.deliveryHint=deliveryHint==null?"":deliveryHint;
            this.priceCondition=priceCondition==null?"":priceCondition;
            this.imageUrl=imageUrl==null?"":imageUrl;
        }
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
            // Delivery may live in a sibling widget OUTSIDE the price label.
            // Only inspect ancestors containing one distinct product URL.
            const tileDelivery=a=>{
              const date=/(?:сегодня|завтра|послезавтра|через\\s*\\d{1,2}\\s*(?:дн|сут)|\\d{1,2}\\s*(?:января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря|янв\\.?|фев\\.?|мар\\.?|апр\\.?|июн\\.?|июл\\.?|авг\\.?|сен\\.?|окт\\.?|ноя\\.?|дек\\.?))/i;
              for(let node=a,level=0;node&&node!==body&&level<9;node=node.parentElement,level++){
                const productLinks=new Set([...node.querySelectorAll(
                  'a[href*="/catalog/"],a[href*="/product/"],a[href*="/product--"],a[href*="/card/"],a[href*="/offer/"]'
                )].map(link=>{
                  try{const u=new URL(link.href);
                    return u.origin+u.pathname.replace(/\\/$/,'');
                  }catch(e){return link.href;}
                }));
                if(productLinks.size>1)break;
                const specific=node.querySelectorAll(
                  '[class*="deliver"],[class*="Deliver"],[data-testid*="deliver"],[class*="pickup"],[class*="arrival"]');
                for(const el of [...specific].slice(0,15)){
                  const text=(el.innerText||el.textContent||'').slice(0,200);
                  const match=text.match(date);
                  if(match)return match[0];
                }
                const text=(node.innerText||'').slice(0,1300);
                const match=text.match(date);
                if(match&&productLinks.size===1)return match[0];
              }
              return '';
            };
            const tileText=a=>{
              // Search cards often keep the image, title and wallet price in
              // SIBLING elements, not inside the product anchor itself.
              // Do not require an image: WB lazy loading may omit it.
              const semantic=a.closest('[data-nm-id],.product-card,.product-card__wrapper,article,li,[data-testid*="product"]');
              let node=semantic||a;
              for(let level=0;level<9&&node&&node!==body;level++,node=node.parentElement){
                const text=(node.innerText||node.textContent||'').trim();
                const productLinks=new Set([...node.querySelectorAll('a[href*="/catalog/"],a[href*="/product/"],a[href*="/product--"],a[href*="/card/"],a[href*="/offer/"]')].map(link=>{try{const u=new URL(link.href);return u.origin+u.pathname.replace(/\\/$/,'');}catch(e){return link.href;}})).size;
                if(text.length>=8&&text.length<=1600&&pricePattern.test(text)&&productLinks<=1)
                  return text.slice(0,1300);
              }
              return (a.parentElement?.innerText||a.innerText||'').trim().slice(0,1300);
            };
            for(const a of document.querySelectorAll('a[href]')){
              let url;
              try{url=new URL(a.href)}catch(e){continue;}
              if(url.protocol!=='https:'||!productPath.test(url.pathname))continue;
              // Layout measurements force reflow. Do them for product links
              // only, not thousands of menu/filter/navigation anchors.
              const img=a.querySelector('img')||a.closest('.product-card,article,li')?.querySelector('img');
              const title=(a.getAttribute('title')||a.getAttribute('aria-label')||
                           a.innerText||img?.alt||'').trim().slice(0,300);
              const context=tileText(a);
              const imageAlt=(img?.alt||a.querySelector('picture img')?.alt||'')
                  .trim().slice(0,300);
              const priceCondition=/(?:с\\s+wb\\s+кошельком|wb\\s+кошелек|wb\\s+wallet)/i
                  .test(context)?'с WB Кошельком':
                  /(?:яндекс\\s*п[эе]й|яндекс\\s*pay|\\bpay\\b|\\bп[эе]й\\b)/i.test(context)?
                  'с Яндекс Пэй':'';
              const rawImage=img?.currentSrc||img?.getAttribute('data-src')||
                   img?.getAttribute('data-original')||img?.src||'';
              let imageUrl='';
              try{const u=new URL(rawImage,location.href);
                  if(u.protocol==='https:')imageUrl=u.href;}catch(e){}
              links.push({url:url.href,title,context:context.slice(0,1300),
                          imageAlt,imageUrl,priceCondition,
                          deliveryHint:tileDelivery(a)});
            }
            // WB's own search XHR can be ready before cards render as DOM.
            // Reuse only the response already seen by this same WebView.
            const networkCards=Array.isArray(window.__marketRadarNetworkCards)?
                window.__marketRadarNetworkCards:[];
            for(const card of networkCards){
              if(!card?.url||!card?.title||!Number.isFinite(card.price))continue;
              links.push({
                url:card.url,
                title:card.title,
                imageAlt:card.title,
                imageUrl:card.imageUrl||'',
                context:card.title+' '+card.price+' ₽',
                priceCondition:''
              });
            }
            // Marketplace grids can scroll inside an element instead of window.
            // Follow the actual product container so lazy-loaded cheaper cards
            // become visible exactly as they do during a manual scroll.
            const scrolling=(()=>{
              const doc=document.scrollingElement||document.documentElement;
              let target=null;
              const productAnchors=[...document.querySelectorAll(
                'a[href*="/catalog/"],a[href*="/product/"],a[href*="/card/"]')].slice(0,70);
              for(const a of productAnchors){
                for(let el=a.parentElement;el&&el!==body;el=el.parentElement){
                  const css=getComputedStyle(el);
                  if(el.scrollHeight>el.clientHeight+180&&el.clientHeight>250&&
                     /auto|scroll/.test(css.overflowY)){
                    target=el;break;
                  }
                }
                if(target)break;
              }
              if(!target){
                for(const el of document.querySelectorAll(
                  'main,[class*="scroll"],[data-scroll],.product-card-list')){
                  const css=getComputedStyle(el);
                  if(el.scrollHeight>el.clientHeight+180&&el.clientHeight>300&&
                     /auto|scroll/.test(css.overflowY)){target=el;break;}
                }
              }
              const element=target||doc;
              window.__marketRadarScrollTarget=target;
              const isDocument=!target;
              return {
                y:Math.round(isDocument?(window.scrollY||doc.scrollTop):element.scrollTop),
                height:Math.round(element.scrollHeight),
                viewport:Math.round(isDocument?window.innerHeight:element.clientHeight),
                kind:isDocument?'document':'container'
              };
            })();
            return JSON.stringify({
              url:location.href,
              title:document.title,
              networkResponses:window.__marketRadarNetworkResponses||0,
              networkCandidates:networkCards.length,
              allAnchors:document.querySelectorAll('a[href]').length,
              text:(body?.innerText||'').slice(0,1800),
              links,
              scrollY:scrolling.y,
              scrollHeight:scrolling.height,
              viewportHeight:scrolling.viewport,
              scrollKind:scrolling.kind,
              // Treat an explicit next-page URL as another result page,
              // never as a new marketplace or an unrelated product URL.
              nextPage:(()=>{
                const nodes=[...document.querySelectorAll('a[rel="next"],a[aria-label],nav a[href]')];
                for(const node of nodes){
                  const label=(node.getAttribute('aria-label')||node.textContent||'').trim();
                  if(node.rel==='next'||/^(?:следующ(?:ая|ий|ую|ее)|далее|next|›|»|>)$/i.test(label)){
                    try{const target=new URL(node.href,location.href);
                      if(target.origin===location.origin&&target.href!==location.href)
                        return target.href;
                    }catch(e){}
                  }
                }
                return '';
              })(),
              loadMore:(()=>{
                for(const button of document.querySelectorAll('button,[role="button"]')){
                  if(/^(?:показать ещё|показать еще|загрузить ещё|загрузить еще|show more|load more)$/i
                       .test((button.textContent||'').trim())&&visible(button))
                     return true;
                }
                return false;
              })()
            });
          }catch(e){
            return JSON.stringify({error:'Не удалось прочитать выдачу'});
          }
        })()
        """;

    private static final String SCROLL_SEARCH = """
        (() => {
          const el=window.__marketRadarScrollTarget;
          if(el&&el.isConnected){
            const before=el.scrollTop;
            el.scrollBy(0,Math.max(el.clientHeight*0.80,420));
            return JSON.stringify({kind:'container',before,after:el.scrollTop});
          }
          const doc=document.scrollingElement||document.documentElement;
          const before=window.scrollY||doc.scrollTop;
          window.scrollBy(0,Math.max(window.innerHeight*0.80,420));
          return JSON.stringify({kind:'document',before,
                                 after:window.scrollY||doc.scrollTop});
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
            const mainPhoto=document.querySelector(
              'main img[src], [data-testid*="gallery"] img, img[itemprop="image"]');
            const rawPhoto=meta('meta[property="og:image"]')||
                 meta('meta[name="twitter:image"]')||
                 mainPhoto?.currentSrc||mainPhoto?.getAttribute('data-src')||
                 mainPhoto?.src||'';
            let imageUrl='';
            try{const u=new URL(rawPhoto,location.href);
                if(u.protocol==='https:')imageUrl=u.href;}catch(e){}
            // Product pages sometimes render delivery only after card opening.
            // Avoid reading unrelated date mentions from the entire page.
            let deliveryHint='';
            const deliveryDate=/(?:сегодня|завтра|послезавтра|через\\s*\\d{1,2}\\s*(?:дн|сут)|\\d{1,2}\\s*(?:января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря|янв\\.?|фев\\.?|мар\\.?|апр\\.?|июн\\.?|июл\\.?|авг\\.?|сен\\.?|окт\\.?|ноя\\.?|дек\\.?))/i;
            const widgets=document.querySelectorAll(
              '[data-testid*="deliver"],[class*="delivery"],[class*="Delivery"],[class*="arrival"],[class*="pickup"]');
            for(const node of [...widgets].slice(0,40)){
              const text=(node.innerText||node.textContent||'').trim().slice(0,300);
              const match=text.match(deliveryDate);
              if(match){deliveryHint=match[0];break;}
            }
            return JSON.stringify({
              url:location.href,
              title:document.title,
              heading:document.querySelector('h1')?.innerText||'',
              text:(body?.innerText||'').slice(0,1800),
              imageUrl,
              deliveryHint,
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
    private DynamicPriceFrontier frontier=new DynamicPriceFrontier();
    private int skippedAboveBest=0;
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
    private String currentSearchUrl="";
    private List<Shop> activeMarkets=Shop.ALL;
    // Search-result grids virtualize offscreen items. Collect several viewport
    // snapshots before applying the candidate limit; otherwise an exact,
    // cheaper listing below the first fold can never enter the comparison.
    private final Map<String,Candidate> searchPool=new LinkedHashMap<>();
    private final PriceOrderProbe sortEvidence=new PriceOrderProbe();
    // Multiple delivery choices can lead to the same product URL. Open the
    // page once and reuse its verified product metadata for each choice.
    private final Map<String,JSONObject> detailCache=new LinkedHashMap<>();
    private boolean searchSampling=false;
    private int stableBottom=0;
    private int lastPoolSize=-1;
    private long lastHeight=-1;
    private int loadMoreAttempts=0;
    private boolean coverageIncomplete=false;
    private final Set<String> visitedSearchPages=new HashSet<>();
    private int hydrationRetries=0;
    private int detailHydrationRetries=0;
    private int retryCount=0;
    private long lastProgressAtMs=0;
    private long lastObservedScrollY=-1;
    private long lastObservedScrollHeight=-1;
    private int lastObservedPoolCount=-1;
    private int stalledScrollChecks=0;
    private boolean awaitingAi=false;
    private int browserCheckRetries=0;
    private boolean sessionProbeRequested=false;
    private int lastDetailCount=0;
    private long lastSearchGrowthMs=0;
    private String lastSearchPageDiagnostics="";
    private int listingSnapshots=0,firstExactSnapshot=-1;
    private int verifiedMarket=0,verifiedTimelyMarket=0;
    private int wrongModelMarket=0,missingPriceMarket=0,aiRejectedMarket=0;
    private int redirectedMarket=0,listingCandidatesMarket=0;
    private String firstRejectionMarket="";
    private void recordRejection(String cause,String title){
        if(!firstRejectionMarket.isEmpty())return;
        String safe=(title==null?"":title).replaceAll("[\\r\\n\\t]"," ").trim();
        firstRejectionMarket=cause+(safe.isEmpty()?"":" · "+safe.substring(0,Math.min(100,safe.length())));
    }
    String resultBreakdown(){
        return "товарных кандидатов из выдачи: "+listingCandidatesMarket+
            "; открыто карточек: "+checkedMarket+
            "; модель не совпала: "+wrongModelMarket+
            "; цена не извлечена: "+missingPriceMarket+
            "; отклонено ИИ: "+aiRejectedMarket+
            "; передано в предложения: "+verifiedMarket+
            "; дорогих отложено: "+skippedAboveBest+
            "; сортировка: "+sortEvidence.description(
                shop!=null&&shop.priceAscendingRequested(currentSearchUrl))+
            (firstRejectionMarket.isEmpty()?"":"; пример: "+firstRejectionMarket);
    }
    private boolean stoppedForFastCheck=false,fullPassFallback=false;
    private static final int MAX_EXPANDED_LISTING_SNAPSHOTS=400;
    private final Set<String> inspectedCandidateKeys=new HashSet<>();
    private boolean plainSearchFallbackAttempted=false;

    ScanEngine(WebView browser, Handler handler, Listener listener) {
        this.browser=browser;this.handler=handler;this.listener=listener;
    }
    boolean isRunning(){return running;}
    boolean wasTimeLimited(){return timeLimitReached;}
    boolean coverageIncomplete(){return coverageIncomplete||timeLimitReached;}
    int totalCandidates(){return checked;}
    void setPriceFrontier(DynamicPriceFrontier shared){
        if(shared!=null)frontier=shared;
    }
    static boolean fastPricedCandidate(String query,String title,
                                      String context,String alt){
        return CandidateDiscovery.shouldInspect(query,title,context,alt) &&
            readCardPrice(context)>0;
    }
    static boolean fastCheckReady(int snapshotCount,int firstMatchSnapshot,
                                  int candidateCount,boolean fullFallback){
        return !fullFallback && firstMatchSnapshot>=0 &&
            snapshotCount>=firstMatchSnapshot+2 && candidateCount>0;
    }
    static boolean mustCompleteListingAfterQuickPass(
            Shop shop,boolean stoppedEarly,boolean alreadyExpanded,int timelyVerified){
        if(!stoppedEarly||alreadyExpanded||shop==null)return false;
        // Every source, including Ozon, can show later matching listings
        // with lower checkout prices or different payment conditions.
        // A quick match is not sufficient: inspect the remaining listing.
        return true;
    }
    static boolean expandedScanLimitReached(boolean expanded,int snapshots){
        return expanded&&snapshots>=MAX_EXPANDED_LISTING_SNAPSHOTS;
    }
    static boolean canPrunePriceCandidate(Shop shop,boolean observedAscending){
        // Even a visually ascending listing may mix wallet/checkout prices
        // and change as the site renders. Skipping a card can miss a cheaper
        // genuine listing. Retain ALL collected candidates for inspection.
        return false;
    }
    static boolean settledAtBottom(boolean bottom,long height,long previousHeight,
                                   int count,int previousCount){
        return bottom&&height==previousHeight&&count==previousCount;
    }
    static boolean atPageEnd(long y,long height,long viewport){
        return height>0&&viewport>0&&y+viewport>=height-75;
    }
    static boolean browserCheckPage(String title,String body){
        String text=body==null?"":body.toLowerCase(Locale.ROOT);
        return text.contains("проверяем браузер") ||
            text.contains("checking your browser") ||
            ("...".equals(title) && text.length()<90);
    }
    static boolean explicitAccessChallenge(String title,String body,int productLinks){
        // Marketplace pages sometimes contain an incidental mention of
        // "captcha" in ordinary UI text. A page with product cards is NOT
        // evidence that a marketplace blocked the session.
        if(productLinks>0)return false;
        String headline=title==null?"":title.trim().toLowerCase(Locale.ROOT);
        String text=body==null?"":body.toLowerCase(Locale.ROOT);
        return headline.equals("403")||headline.equals("access denied")||
            text.contains("подозрительная активность")||
            text.contains("вы не робот")||
            text.contains("access denied")||
            (text.contains("captcha")&&
             (text.contains("пройдите проверку")||text.contains("verify you are human")));
    }

    static boolean exceededIdle(long lastProgress,long now,long idleLimit){
        return now-lastProgress>=idleLimit;
    }
    static boolean scrollAdvanced(long y,long previousY,long height,long previousHeight,
                                  int poolSize,int previousPoolSize){
        return y!=previousY||height!=previousHeight||poolSize!=previousPoolSize;
    }
    private void noteProgress(){
        lastProgressAtMs=android.os.SystemClock.elapsedRealtime();
    }
    private void monitorIdle(int request){
        // This watchdog is NOT a scan duration cap. It resets whenever
        // the page actually advances, even if the crawl takes many minutes.
        handler.postDelayed(()->{
            if(!running||request!=token)return;
            if(!awaitingAi&&exceededIdle(lastProgressAtMs,
                    android.os.SystemClock.elapsedRealtime(),18000)){
                failPage("Нет прогресса загрузки или прокрутки");
                return;
            }
            monitorIdle(request);
        },4500);
    }
    void start(String q) { start(q,8,Shop.ALL); }
    void start(String q,int limit) { start(q,limit,Shop.ALL); }
    void start(String q,int limit,List<Shop> markets) {
        stop();
        activeMarkets=new ArrayList<>(markets);
        query=q.trim();
        // No arbitrary count limit: collect and visit every exposed candidate.
        maxCards=Integer.MAX_VALUE;
        running=true;
        marketIndex=0;
        checked=0;
        timeLimitReached=false;
        coverageIncomplete=false;
        skippedAboveBest=0;
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
        candidates.clear();seen.clear();inspectedCandidateKeys.clear();
        sortEvidence.reset();
        cardIndex=0;details=false;checkedMarket=0;expectedUrl="";
        searchSampling=false;searchPool.clear();detailCache.clear();
        stableBottom=0;lastPoolSize=-1;lastHeight=-1;
        loadMoreAttempts=0;visitedSearchPages.clear();
        currentSearchUrl=shop.searchUrl(query);
        visitedSearchPages.add(currentSearchUrl);
        hydrationRetries=0;
        detailHydrationRetries=0;
        retryCount=0;
        awaitingAi=false;
        browserCheckRetries=0;
        sessionProbeRequested=false;
        lastObservedScrollY=-1;lastObservedScrollHeight=-1;
        lastObservedPoolCount=-1;stalledScrollChecks=0;lastDetailCount=0;
        lastSearchGrowthMs=android.os.SystemClock.elapsedRealtime();
        listingSnapshots=0;firstExactSnapshot=-1;
        lastSearchPageDiagnostics="";
        verifiedMarket=0;verifiedTimelyMarket=0;
        wrongModelMarket=0;missingPriceMarket=0;aiRejectedMarket=0;
        redirectedMarket=0;listingCandidatesMarket=0;firstRejectionMarket="";
        stoppedForFastCheck=false;fullPassFallback=false;
        plainSearchFallbackAttempted=false;
        listener.status(shop,"Ищем точную модель…");
        load(currentSearchUrl);
    }
    private void load(String url) {
        int request=++token;
        awaitingAi=false;
        noteProgress();
        browser.loadUrl(url);
        monitorIdle(request);
    }
    void onPageFinished(String url) {
        if(!running)return;
        noteProgress();
        // A valid sign-in redirect is a session failure, not an empty
        // catalogue and not a generic WebView timeout.
        if(shop!=null&&shop.isLoginUrl(url)){
            coverageIncomplete=true;
            token++;
            listener.session(shop,new SessionProbe.Verdict(
                SessionProbe.State.SIGN_IN_REQUIRED,
                "Поиск переадресован на страницу входа"));
            listener.status(shop,"Необходим повторный вход в аккаунт магазина");
            nextMarket();
            return;
        }
        // Ignore completion callbacks from the previous marketplace. Both
        // search and product pages share one WebView, so a prior host can
        // finish after the next shop has already started loading.
        if(!details && !shop.allowed(url)){
            // Unexpected search redirects must be recorded, not ignored until
            // the watchdog fires and the user gets no result or explanation.
            final int redirectedToken=token;
            handler.postDelayed(()->{
                if(running&&redirectedToken==token&&!details&&
                   !shop.allowed(browser.getUrl())&&
                   !shop.isLoginUrl(browser.getUrl()))
                    failPage("Магазин перенаправил поиск на другой адрес");
            },900);
            return;
        }
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
    void onHttpError(String url,int statusCode){
        if(!running||shop==null||!shop.allowed(url))return;
        if(statusCode==401||statusCode==403||statusCode==429){
            if(!details&&tryPlainSearchFallback("HTTP "+statusCode))return;
            coverageIncomplete=true;
            token++;
            listener.status(shop,"Магазин ограничил доступ (HTTP "+statusCode+")");
            nextMarket();
        }else if(statusCode>=500){
            failPage("Сервер магазина ответил HTTP "+statusCode);
        }
    }
    void onPageError(String url) {
        if(!running||!shop.allowed(url))return;
        int request=token;
        handler.postDelayed(() -> {
            if(running&&request==token)failPage("Ошибка загрузки");
        },1500);
    }
    private void failPage(String message) {
        // Retry intermittent network failures before declaring incomplete
        // coverage. Page timeouts are NOT a global search completion rule.
        if((message.contains("не ответила")||message.contains("Ошибка загрузки")) &&
                retryCount<2){
            int attempt=++retryCount;
            String destination=details?expectedUrl:currentSearchUrl;
            final int retryToken=++token;
            listener.status(shop,"Повторяем недоступную страницу: "+attempt+"/2");
            handler.postDelayed(()->{
                if(running&&token==retryToken&&shop.allowed(destination))
                    load(destination);
            },300L*attempt);
            return;
        }
        if(!details&&searchPool.isEmpty()&&tryPlainSearchFallback(message))
            return;
        coverageIncomplete=true;
        if(message.contains("Нет прогресса"))timeLimitReached=true;
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
        if(shop.isLoginUrl(url)){
            coverageIncomplete=true;
            token++;
            listener.session(shop,new SessionProbe.Verdict(
                SessionProbe.State.SIGN_IN_REQUIRED,
                "Вместо выдачи открылась авторизация"));
            listener.status(shop,"Необходим повторный вход в аккаунт магазина");
            nextMarket();return;
        }
        String text=data.optString("text","");
        String title=data.optString("title","");
        String body=(title+" "+text).toLowerCase(Locale.ROOT);
        if(!shop.allowed(url)){
            coverageIncomplete=true;
            if(shop.isLoginUrl(url)){
                listener.session(shop,new SessionProbe.Verdict(
                    SessionProbe.State.SIGN_IN_REQUIRED,
                    "Поиск перенаправлен на форму авторизации"));
                listener.status(shop,"Требуется повторно войти в аккаунт магазина");
            }else{
                // The price-sorted URL can redirect to a generic landing page.
                // Attempt the site's own ordinary search URL once before
                // declaring this marketplace unavailable.
                if(tryPlainSearchFallback("Неожиданная переадресация сайта"))
                    return;
                listener.status(shop,"Неожиданная переадресация сайта");
            }
            nextMarket();return;
        }
        if(!details && browserCheckPage(title,body)){
            // WB often initially returns only "Проверяем браузер"; its
            // challenge or actual catalog may appear several seconds later.
            // Do not mistake this placeholder for an empty product search.
            if(browserCheckRetries++<12){
                noteProgress();
                listener.status(shop,"Сайт проверяет браузер · ждём выдачу, а не скрываем товары");
                final int request=token;
                handler.postDelayed(()->{
                    if(!running||request!=token||details)return;
                    browser.evaluateJavascript(SEARCH_SNAPSHOT,value->{
                        if(!running||request!=token||details)return;
                        try{
                            Object decoded=new JSONTokener(value).nextValue();
                            JSONObject snapshot=new JSONObject(String.valueOf(decoded));
                            consume(snapshot);
                        }catch(Exception e){failPage("Не удалось проверить браузер магазина");}
                    });
                },900);
            }else{
                if(tryPlainSearchFallback("Проверка браузера"))return;
                coverageIncomplete=true;
                listener.status(shop,"Магазин не выдал карточки: проверка браузера не завершилась");
                nextMarket();
            }
            return;
        }
        if(!details&&body.contains("похоже, нет соединения")){
            failPage("Ошибка загрузки: магазин сообщает о проблеме соединения");
            return;
        }
        JSONArray pageLinks=data.optJSONArray("links");
        if(explicitAccessChallenge(title,body,
            pageLinks==null?0:pageLinks.length())){
            if(tryPlainSearchFallback("Страница проверки доступа"))return;
            coverageIncomplete=true;
            listener.status(shop,"Магазин показал страницу проверки доступа");
            nextMarket();return;
        }
        if(details){
            noteProgress();
            readDetail(data,url);
        }
        else readSearch(data);
    }
    private void readSearch(JSONObject data) {
        // Probe session in the EXACT WebView used by this marketplace's
        // search. A cookie from an earlier sign-in screen is not evidence.
        if(!sessionProbeRequested){
            sessionProbeRequested=true;
            final int requestedToken=token;
            final Shop expectedShop=shop;
            browser.evaluateJavascript(SessionProbe.SCRIPT,value->{
                if(!running||requestedToken!=token||shop!=expectedShop)return;
                try{
                    JSONObject evidence=new JSONObject(
                        String.valueOf(new JSONTokener(value).nextValue()));
                    listener.session(expectedShop,
                        SessionProbe.evaluate(expectedShop,evidence));
                }catch(Exception ignored){
                    listener.session(expectedShop,new SessionProbe.Verdict(
                        SessionProbe.State.UNKNOWN,"Не удалось проверить вход"));
                }
            });
        }
        // Combine all scroll snapshots, not only the initial screen and not
        // only the first ten DOM links. Each candidate still needs its own
        // product page verification before it can become a visible offer.
        JSONArray visibleLinks=data.optJSONArray("links");
        lastSearchPageDiagnostics="страница: "+data.optString("title","").replace("\\n"," ")+
            " · ссылок: "+data.optInt("allAnchors",0)+
            " · товарных ссылок: "+(visibleLinks==null?0:visibleLinks.length())+
            " · сетевых ответов WB: "+data.optInt("networkResponses",0)+
            " · сетевых товаров WB: "+data.optInt("networkCandidates",0)+
            " · прокрутка: "+data.optString("scrollKind","не определена");
        mergeSearchSnapshot(searchPool,visibleLinks,shop,query);
        listingCandidatesMarket=Math.max(listingCandidatesMarket,searchPool.size());
        sortEvidence.observe(visibleLinks,shop);
        listingSnapshots++;
        if(firstExactSnapshot<0 && visibleLinks!=null){
            for(int i=0;i<visibleLinks.length();i++){
                JSONObject tile=visibleLinks.optJSONObject(i);
                if(tile==null)continue;
                String title=tile.optString("title","");
                String alt=tile.optString("imageAlt","");
                String context=tile.optString("context","");
                if(fastPricedCandidate(query,title,context,alt)){
                    firstExactSnapshot=listingSnapshots;
                    break;
                }
            }
        }
        // Update the optimistic global target AS SOON AS another search
        // viewport yields a cheaper exact-model listing. This does not
        // discard fallback candidates before AI checks the cheapest one.
        int oldTarget=frontier.observedCeiling();
        for(Candidate candidate:searchPool.values())
            frontier.observe(candidate.listingPrice);
        int newTarget=frontier.observedCeiling();
        if(newTarget>0 && (oldTarget==0||newTarget<oldTarget))
            listener.status(shop,"Новый предварительный минимум: "+newTarget+
                " ₽ · ищем ещё дешевле");
        // Marketplaces are SPAs. A page may finish loading before product
        // cards are rendered; poll only when the relevant DOM is still empty.
        if(searchPool.isEmpty() && hydrationRetries<5){
            hydrationRetries++;
            final int request=token;
            handler.postDelayed(()->{
                if(!running||request!=token||details)return;
                browser.evaluateJavascript(SEARCH_SNAPSHOT,value->{
                    if(!running||request!=token||details)return;
                    try {
                        Object decoded=new JSONTokener(value).nextValue();
                        JSONObject snapshot=new JSONObject(String.valueOf(decoded));
                        if(snapshot.has("error")){failPage("Не удалось прочитать выдачу");return;}
                        if(!shop.allowed(snapshot.optString("url",""))){
                            failPage("Магазин перенаправил выдачу");return;
                        }
                        readSearch(snapshot);
                    }catch(Exception e){
                        failPage("Не удалось прочитать выдачу");
                    }
                });
            },330);
            return;
        }
        long y=data.optLong("scrollY",0);
        long height=data.optLong("scrollHeight",0);
        long viewport=data.optLong("viewportHeight",0);
        boolean bottom=atPageEnd(y,height,viewport);
        boolean advanced=scrollAdvanced(y,lastObservedScrollY,height,
            lastObservedScrollHeight,searchPool.size(),lastObservedPoolCount);
        if(advanced){
            noteProgress();
            stalledScrollChecks=0;
        }else if(!bottom){
            stalledScrollChecks++;
        }
        lastObservedScrollY=y;
        lastObservedScrollHeight=height;
        lastObservedPoolCount=searchPool.size();
        if(stalledScrollChecks>=4){
            // A blocked/non-window scroll container cannot be treated
            // as complete even if the WebView keeps returning valid JS.
            coverageIncomplete=true;
            listener.status(shop,"Выдачу не удалось прокрутить полностью");
            finishListingCollection();
            return;
        }
        // Reset "show more" retries whenever fresh results actually appear:
        // there is no fixed cap on how many successful load-more pages we open.
        if(height>lastHeight||searchPool.size()>lastPoolSize){
            loadMoreAttempts=0;
            lastSearchGrowthMs=android.os.SystemClock.elapsedRealtime();
        }
        if(settledAtBottom(bottom,height,lastHeight,
                searchPool.size(),lastPoolSize))stableBottom++;
        else stableBottom=0;
        lastHeight=height;lastPoolSize=searchPool.size();

        if(height<=0||viewport<=0){
            failPage("Нет данных о конце выдачи, полноту не подтвердить");
            return;
        }
        // The search is requested in increasing-price order. As soon as
        // we see an exact, priced item, sample two more viewports and CHECK
        // the cheapest candidates immediately. A full crawl is the fallback
        // only when those candidates fail or have no eligible delivery.
        // Until a full crawl, never claim that the global minimum is proven.
        if(expandedScanLimitReached(fullPassFallback,listingSnapshots)){
            // The marketplace may be infinite-scroll. Fail as incomplete
            // rather than running indefinitely or claiming the global minimum.
            coverageIncomplete=true;
            listener.status(shop,"Расширенный поиск достиг предела прокрутки · "+
                searchPool.size()+" кандидатов");
            finishListingCollection();
            return;
        }
        if(fastCheckReady(listingSnapshots,firstExactSnapshot,
                          searchPool.size(),fullPassFallback)){
            stoppedForFastCheck=true;
            // Fast pass never proves a marketplace is fully scanned.
            // Every selected marketplace receives the expanded follow-up.
            listener.status(shop,"Найдена точная модель в выдаче · сразу проверяем цены");
            finishListingCollection();
            return;
        }
        // Marketplace cards can hydrate AFTER the window reaches the bottom.
        // Require the DOM to remain settled before calling a listing exhausted.
        if(stableBottom<3 ||
            !exceededIdle(lastSearchGrowthMs,
                android.os.SystemClock.elapsedRealtime(),1800)){
            listener.status(shop,"Собрано "+searchPool.size()+
                " кандидатов · просматриваем выдачу");
            scrollSearchPage();
            return;
        }
        String next=data.optString("nextPage","");
        if(isNextSearchPage(shop,next,visitedSearchPages)){
            visitedSearchPages.add(next);
            stableBottom=0;lastHeight=-1;lastPoolSize=-1;
            hydrationRetries=0;searchSampling=false;
            retryCount=0;
            listener.status(shop,"Следующая страница · кандидатов "+searchPool.size());
            currentSearchUrl=next;
            load(next);
            return;
        }
        if(!next.isBlank()&&!isNextSearchPage(shop,next,visitedSearchPages)){
            coverageIncomplete=true;
            listener.status(shop,"Следующая страница недоступна или повторяется");
        }
        if(data.optBoolean("loadMore",false)){
            if(loadMoreAttempts++<2){
                stableBottom=0;
                final int request=token;
                browser.evaluateJavascript(
                    "(()=>{for(const b of document.querySelectorAll('button,[role=button]')){"+
                    "const t=(b.textContent||'').trim();"+
                    "if(/^(показать ещё|показать еще|загрузить ещё|загрузить еще|show more|load more)$/i.test(t)){"+
                    "b.click();return true;}}return false;})()",
                    ignored->handler.postDelayed(()->{
                        if(running&&request==token&&!details)
                            browser.evaluateJavascript(SEARCH_SNAPSHOT,value->{
                                if(!running||request!=token||details)return;
                                try{
                                    Object decoded=new JSONTokener(value).nextValue();
                                    readSearch(new JSONObject(String.valueOf(decoded)));
                                }catch(Exception e){failPage("Не удалось подгрузить товары");}
                            });
                    },450));
                return;
            }
            // "Show more" still exists but did not extend the listing:
            // cannot honestly claim the entire catalogue was exhausted.
            coverageIncomplete=true;
            listener.status(shop,"Не удалось загрузить оставшиеся предложения");
        }
        finishListingCollection();
    }

    /**
     * A price-sorted marketplace URL can be served as an empty SPA shell,
     * even when the ordinary search returns product tiles. This is a single
     * site-supported navigation fallback, not an HTTP/captcha bypass.
     */
    private boolean tryPlainSearchFallback(String reason){
        if(running&&!details&&searchPool.isEmpty()&&
           !plainSearchFallbackAttempted&&shop!=null&&
           (shop.id.equals("ozon")||shop.id.equals("ym"))){
            String plain=shop.searchBase+
                java.net.URLEncoder.encode(query,java.nio.charset.StandardCharsets.UTF_8);
            if(plain.equals(currentSearchUrl))return false;
            plainSearchFallbackAttempted=true;
            currentSearchUrl=plain;
            searchPool.clear();seen.clear();visitedSearchPages.clear();
            visitedSearchPages.add(currentSearchUrl);
            stableBottom=0;lastPoolSize=-1;lastHeight=-1;loadMoreAttempts=0;
            lastObservedScrollY=-1;lastObservedScrollHeight=-1;
            lastObservedPoolCount=-1;stalledScrollChecks=0;
            sortEvidence.reset();
            hydrationRetries=0;retryCount=0;browserCheckRetries=0;
            searchSampling=false;listingSnapshots=0;firstExactSnapshot=-1;
            listener.status(shop,"Повторяем поиск без сортировки по цене");
            load(currentSearchUrl);
            return true;
        }
        return false;
    }

    private void finishListingCollection(){
        if(details)return;
        candidates.clear();
        candidates.addAll(rankCandidates(searchPool.values(),Integer.MAX_VALUE));
        listener.status(shop,sortEvidence.description(
            shop.priceAscendingRequested(currentSearchUrl))+
            " · просмотрено цен: "+sortEvidence.inspected());
        // The expanded crawl can change the ascending price order. Starting
        // at the old cardIndex skipped all newly found CHEAPER candidates.
        cardIndex=0;
        searchSampling=false;
        if(candidates.isEmpty()){
            if(tryPlainSearchFallback("Пустая выдача"))return;
            coverageIncomplete=true;
            listener.status(shop,"Нет подходящих карточек · "+lastSearchPageDiagnostics+
                " · полнота выдачи не подтверждена");
            nextMarket();
            return;
        }
        details=true;
        listener.status(shop,(coverageIncomplete?
            "Доступная выдача неполна; ":"Доступная выдача просмотрена; ")+
            "проверяем "+candidates.size()+" кандидатов");
        nextCandidate();
    }
    static boolean isNextSearchPage(Shop shop,String next,Set<String> visited){
        if(shop==null||next==null||next.isBlank()||visited.contains(next)||
           !shop.allowed(next)||shop.productLink(next))return false;
        try{
            java.net.URI uri=new java.net.URI(next);
            String path=uri.getPath().toLowerCase(Locale.ROOT);
            return path.contains("search")||
                (shop.id.equals("wb")&&path.contains("/catalog/0"));
        }catch(Exception ignored){return false;}
    }
    private void scrollSearchPage(){
        int request=token;
        browser.evaluateJavascript(
            SCROLL_SEARCH,
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
        for(Candidate candidate:selectCandidates(links,shop,query,Integer.MAX_VALUE)){
            String key=candidate.url+"\u0001"+candidate.deliveryHint+
                "\u0001"+candidate.priceCondition;
            Candidate old=pool.get(key);
            if(old==null || (candidate.listingPrice>0 &&
                (old.listingPrice<=0 || candidate.listingPrice<old.listingPrice)))
                pool.put(key,candidate);
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
            String imageAlt=item.optString("imageAlt","").trim();
            if(!CandidateDiscovery.shouldInspect(query,title,context,imageAlt))continue;
            String clean=cleanUrl(url);
            int listingPrice=readCardPrice(context);
            String delivery=readDeliveryHint(item.optString("deliveryHint",""));
            if(delivery.isBlank())delivery=readDeliveryHint(context);
            String priceCondition=priceConditionForCard(
                context,item.optString("priceCondition",""),shop);
            String key=clean+"\u0001"+delivery+"\u0001"+priceCondition;
            Candidate previous=unique.get(key);
            if(previous==null || (listingPrice>0 && (previous.listingPrice==0 ||
               listingPrice<previous.listingPrice))){
                unique.put(key,new Candidate(clean,listingPrice,delivery,
                    priceCondition,item.optString("imageUrl","")));
            }
        }
        return rankCandidates(unique.values(),maxCards);
    }

    private void nextCandidate() {
        if(!running)return;
        // Do not let a cheaper Ozon quote suppress WB candidates whose
        // personalized WB-app prices may differ from website snippets.
        // Only prune by scraped listing price when this session actually
        // demonstrates stable ascending order. In personalized WB/Yandex
        // listings the same item may be cheaper under an account condition.
        // A URL parameter by itself is not sufficient evidence.
        boolean safeToPrune=canPrunePriceCandidate(
            shop,sortEvidence.ascendingObserved());
        int next=safeToPrune?
            DynamicPriceFrontier.nextUnpruned(candidates,cardIndex,frontier,shop):
            cardIndex;
        skippedAboveBest+=next-cardIndex;
        cardIndex=next;
        if(cardIndex>=candidates.size()){
            if(mustCompleteListingAfterQuickPass(shop,stoppedForFastCheck,
                fullPassFallback,verifiedTimelyMarket)){
                // Do not stop after a wrongly identified item or only a
                // slow-delivery quote: retry with the complete listing.
                fullPassFallback=true;
                stoppedForFastCheck=false;
                details=false;
                searchSampling=false;
                stableBottom=0;lastHeight=-1;lastPoolSize=-1;
                lastObservedScrollY=-1;lastObservedScrollHeight=-1;
                lastObservedPoolCount=-1;stalledScrollChecks=0;
                listingSnapshots=0;firstExactSnapshot=-1;
                listener.status(shop,"Проверяем оставшуюся выдачу на более дешёвые товары");
                load(currentSearchUrl);
                return;
            }
            listener.status(shop,"Обработано "+checkedMarket+
                " карточек из "+candidates.size()+
                (coverageIncomplete?". Проверка неполная":" · поиск завершён")+
                (skippedAboveBest>0?" · отложено дорогих: "+skippedAboveBest:""));
            nextMarket();return;
        }
        Candidate current=candidates.get(cardIndex++);
        String key=current.url+"\u0001"+current.listingPrice+
            "\u0001"+current.deliveryHint+"\u0001"+current.priceCondition;
        if(!inspectedCandidateKeys.add(key)){
            nextCandidate();
            return;
        }
        expectedUrl=current.url;
        detailHydrationRetries=0;
        retryCount=0;
        JSONObject cached=detailCache.get(expectedUrl);
        if(cached!=null){
            final String candidateUrl=expectedUrl;
            final int request=token;
            handler.post(()->{
                if(running&&request==token&&details&&expectedUrl.equals(candidateUrl))
                    readDetail(cached,candidateUrl);
            });
            return;
        }
        load(expectedUrl);
    }
    private void readDetail(JSONObject data,String url) {
        // A blank product heading often means hydration is still in progress.
        // Reactively retry rather than sleeping a fixed second on EVERY card.
        if(sameProductPage(shop,expectedUrl,url) &&
           data.optString("heading","").isBlank() &&
           detailHydrationRetries<4){
            detailHydrationRetries++;
            final int request=token;
            handler.postDelayed(()->{
                if(!running||request!=token||!details)return;
                browser.evaluateJavascript(DETAIL_SNAPSHOT,value->{
                    if(!running||request!=token||!details)return;
                    try{
                        Object decoded=new JSONTokener(value).nextValue();
                        JSONObject snapshot=new JSONObject(String.valueOf(decoded));
                        if(snapshot.has("error")){failPage("Не удалось прочитать товар");return;}
                        readDetail(snapshot,snapshot.optString("url",""));
                    }catch(Exception e){failPage("Не удалось прочитать товар");}
                });
            },330);
            return;
        }
        checked++;checkedMarket++;
        if(sameProductPage(shop,expectedUrl,url) &&
            !data.optString("heading","").isBlank())
            detailCache.put(expectedUrl,data);
        // Never report a price from a redirect or a different product URL.
        if(sameProductPage(shop,expectedUrl,url)){
            String title=data.optString("heading","").trim();
            if(title.isEmpty())title=data.optString("title","").trim();
            if(exact(query,title)){
                int detailPrice=readStructuredPrice(data.optJSONArray("structuredPrices"),query,title);
                Candidate selected=candidates.get(cardIndex-1);
                int listingPrice=selected.listingPrice;
                // WB Wallet / verified promotional prices may be MUCH lower
                // than the regular page price (e.g. 10 772 vs 19 640 RUB).
                // These aren't necessarily financing teasers; payment conditions
                // must survive the comparison and appear in the app.
                int safeListing=validListingPrice(
                    detailPrice,listingPrice,selected.priceCondition);
                int price=compareCardAndListingPrice(
                    detailPrice,listingPrice,selected.priceCondition);
                String note;
                if(price>0&&safeListing>0&&safeListing<detailPrice)
                    note="Цена из выдачи · в данных товара "+detailPrice+" ₽ · проверяйте корзину";
                else if(price>0&&safeListing>0&&detailPrice==0)
                    note="Цена из выдачи · товар совпадает · корзина не проверена";
                else if(price>0&&detailPrice>0)
                    note="Цена из данных товара · условия карты и доставки не проверены";
                else
                    note="Модель совпадает · цена не найдена";
                String listingDelivery=candidates.get(cardIndex-1).deliveryHint;
                final String delivery=listingDelivery.isBlank()?
                    readDeliveryHint(data.optString("deliveryHint","")):listingDelivery;
                String priceCondition=candidates.get(cardIndex-1).priceCondition;
                String image=data.optString("imageUrl","");
                if(image.isBlank())image=selected.imageUrl;
                if(price<=0){
                    missingPriceMarket++;
                    recordRejection("Цена товара отсутствует",title);
                    nextCandidate();
                    return;
                }
                Offer proposal=new Offer(shop,cleanUrl(url),title,price,note,
                    delivery,priceCondition,image);
                final int request=token;
                final String candidateUrl=expectedUrl;
                listener.status(shop,"Проверяем найденный товар локальным ИИ…");
                awaitingAi=true;
                // The network watchdog intentionally skips AI work, but the
                // model can stall at startup or under Android memory pressure.
                // Never leave a market worker waiting forever for its callback.
                final java.util.concurrent.atomic.AtomicBoolean settled=
                    new java.util.concurrent.atomic.AtomicBoolean(false);
                handler.postDelayed(()->{
                    if(!running||request!=token||!candidateUrl.equals(expectedUrl)||
                       !settled.compareAndSet(false,true))return;
                    awaitingAi=false;
                    coverageIncomplete=true;
                    noteProgress();
                    listener.status(shop,"Локальная проверка не ответила за 45 с · продолжаем");
                    nextCandidate();
                },45000);
                listener.assess(proposal,verdict->handler.post(()->{
                    if(!settled.compareAndSet(false,true))return;
                    if(!running||request!=token||!candidateUrl.equals(expectedUrl))return;
                    awaitingAi=false;
                    noteProgress();
                    if(verdict==null){
                        coverageIncomplete=true;
                        recordRejection("Ошибка локальной ИИ-проверки",proposal.title);
                        listener.status(shop,"Локальный ИИ не подтвердил проверку");
                    }else if(verdict.booleanValue()){
                        verifiedMarket++;
                        if(price>0&&frontier.policy().timely(delivery))verifiedTimelyMarket++;
                        if(price>0&&frontier.confirm(price,delivery,shop))
                            listener.status(shop,"Новый минимум с подходящей доставкой: "+price+" ₽");
                        listener.offer(proposal);
                    }else{
                        aiRejectedMarket++;
                        recordRejection("ИИ отклонил модель",proposal.title);
                    }
                    nextCandidate();
                }));
                return;
            }else{
                wrongModelMarket++;
                recordRejection("Название не соответствует модели",title);
            }
        }else{
            redirectedMarket++;
            recordRejection("Карточка перенаправлена",url);
        }
        listener.status(shop,"Карточек проверено: "+checkedMarket+" / "+candidates.size());
        nextCandidate();
    }
    static String priceConditionForCard(String context,String supplied,Shop shop){
        if(shop==null)return "";
        String both=(context==null?"":context)+" "+(supplied==null?"":supplied);
        if(shop.id.equals("wb"))return java.util.regex.Pattern.compile(
            "(?iu)(?:с\\s*wb\\s*кошельком|wb\\s*кошелек|wb\\s*wallet)")
            .matcher(both).find()?"с WB Кошельком":"";
        if(shop.id.equals("ym"))return java.util.regex.Pattern.compile(
            "(?iu)(?:яндекс\\s*п[эе]й|яндекс\\s*pay|\\bpay\\b|\\bп[эе]й\\b)")
            .matcher(both).find()?"с Яндекс Пэй":"";
        return "";
    }
    static String readDeliveryHint(String context){
        if(context==null||context.isBlank())return "";
        // Parse only a date captured from this product's own listing or
        // delivery widget. Abbreviations are common on mobile marketplaces.
        java.util.regex.Pattern p=java.util.regex.Pattern.compile(
            "(?iu)(?:за\\s+час|сегодня|послезавтра|завтра|"+
            "через\\s*\\d{1,2}\\s*(?:дн(?:я|ей|ь)|сут(?:ок|ки))|"+
            "(?:в\\s+)?(?:понедельник|вторник|среду|четверг|пятницу|субботу|воскресенье)|"+
            "\\d{1,2}\\s*(?:января|февраля|марта|апреля|мая|июня|июля|августа|"+
            "сентября|октября|ноября|декабря|янв\\.?|фев\\.?|мар\\.?|апр\\.?|"+
            "июн\\.?|июл\\.?|авг\\.?|сен\\.?|окт\\.?|ноя\\.?|дек\\.?)|самовывоз)");
        Matcher match=p.matcher(context);
        return match.find()?match.group().trim():"";
    }
    static int validListingPrice(int pagePrice,int listingPrice){
        return validListingPrice(pagePrice,listingPrice,"");
    }
    static int validListingPrice(int pagePrice,int listingPrice,
                                 String priceCondition){
        if(listingPrice<=0)return 0;
        // Explicit WB Wallet conditions explain otherwise extreme differences
        // from standard prices. Financing and cashback amounts are filtered
        // by readCardPrice before reaching this method.
        if("с WB Кошельком".equals(priceCondition)||
           "с Яндекс Пэй".equals(priceCondition))return listingPrice;
        return pagePrice>0&&listingPrice<pagePrice*0.65?0:listingPrice;
    }

    static int compareCardAndListingPrice(int pagePrice,int listingPrice){
        return compareCardAndListingPrice(pagePrice,listingPrice,"");
    }
    static int compareCardAndListingPrice(int pagePrice,int listingPrice,
                                          String priceCondition){
        int listing=validListingPrice(pagePrice,listingPrice,priceCondition);
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
        if(context==null||context.isBlank()||context.length()>1400)return 0;
        // A single WB card line can contain BOTH sale and crossed-out prices,
        // a cashback mention and the delivery slot. Never discard the whole
        // line merely because one non-payable amount appears on it.
        JSONArray lines=new JSONArray();
        for(String line:context.split("\\R"))lines.put(line);
        return readPrice(lines);
    }
    private static boolean nonPayableAmount(String line,int start,int end){
        String before=line.substring(Math.max(0,start-35),start).trim();
        String after=line.substring(end,Math.min(line.length(),end+30)).trim();
        if(before.matches("(?s).*(?:к[эе]шб[эе]к|балл(?:ов|а|ы)?|бонус(?:ы|ов)?|скидк[а-я]*|экономи[а-я]*)(?:\\s+(?:до|около))?\\s*:?"))
            return true;
        return after.matches("(?s)^(?:/\\s*мес|в\\s+месяц|к[эе]шб[эе]к|балл(?:ов|а|ы)?|бонус(?:ы|ов)?|рассроч[а-я]*|долями|сплит)(?:\\b|\\s).*");
    }
    static int readPrice(JSONArray snippets){
        if(snippets==null)return 0;
        int best=Integer.MAX_VALUE;
        for(int i=0;i<snippets.length();i++){
            String line=snippets.optString(i,"").toLowerCase(Locale.ROOT);
            Matcher match=PRICE.matcher(line);
            while(match.find()){
                if(nonPayableAmount(line,match.start(),match.end()))continue;
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
