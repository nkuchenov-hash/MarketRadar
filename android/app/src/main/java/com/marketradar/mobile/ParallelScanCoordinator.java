package com.marketradar.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A separate authenticated WebView per selected marketplace.
 *
 * Previous app performed all three searches sequentially in one WebView,
 * navigating search -> product -> product before starting the next store.
 * Here the stores run concurrently while retaining the SAME Android WebView
 * cookie profile. No cookies are sent to an external service.
 *
 * Safety timeouts in ScanEngine guard stalled pages, not overall search speed.
 */
final class ParallelScanCoordinator {
    private final Activity activity;
    private final FrameLayout root;
    private final WebView primaryBrowser;
    private final Handler handler;
    private final ScanEngine.Listener listener;
    private final DynamicPriceFrontier frontier=new DynamicPriceFrontier();
    private final List<Worker> workers=new ArrayList<>();
    private final Map<WebView,Worker> byBrowser=new IdentityHashMap<>();
    private boolean running=false;
    private boolean wasTimeLimited=false;
    private boolean coverageIncomplete=false;
    private int generation=0;
    private int completed=0;
    // Start all three selected marketplaces at once. Each uses a separate
    // WebView and the shared Android cookie jar, not a serial queue.
    private static final int MAX_PARALLEL=3;
    private final ArrayList<Shop> pendingMarkets=new ArrayList<>();
    private final Map<String,String> completedBreakdown=new HashMap<>();
    // Track shops separately from reusable WebView workers: a finished shop
    // must never disappear or be shown as waiting when its worker is reused.
    private final Map<String,MarketProgress> marketProgress=new HashMap<>();
    private static final class MarketProgress {
        boolean started,finished,incomplete;
        String status="";
        long startedAt,updatedAt;
    }
    private int nextMarketIndex=0;
    private String activeQuery="";

    private final class Worker implements ScanEngine.Listener {
        final WebView view;
        final ScanEngine scan;
        Shop shop;
        int generation;
        boolean finished;
        long startedAt;
        long lastStatusAt;
        String lastStatus="";

        Worker(WebView view){
            this.view=view;
            this.scan=new ScanEngine(view,handler,this);
        }
        @Override public void status(Shop market,String text){
            if(isCurrent(this)){
                lastStatus=text;
                lastStatusAt=android.os.SystemClock.elapsedRealtime();
                MarketProgress record=marketProgress.get(market.id);
                if(record!=null){
                    record.status=text;
                    record.updatedAt=lastStatusAt;
                }
                listener.status(market,text);
            }
        }
        @Override public void session(Shop market,SessionProbe.Verdict verdict){
            if(isCurrent(this))listener.session(market,verdict);
        }
        @Override public void offer(ScanEngine.Offer offer){
            if(isCurrent(this))listener.offer(offer);
        }
        @Override public void assess(ScanEngine.Offer offer,
                java.util.function.Consumer<Boolean> verdict){
            if(isCurrent(this))
                listener.assess(offer,answer->verdict.accept(
                    isCurrent(this)?answer:Boolean.FALSE));
        }
        @Override public void done(){
            if(!isCurrent(this)||finished)return;
            finished=true;
            completed++;
            boolean incomplete=scan.coverageIncomplete();
            wasTimeLimited|=scan.wasTimeLimited();
            coverageIncomplete|=incomplete;
            completedBreakdown.put(shop.id,scan.resultBreakdown());
            long now=android.os.SystemClock.elapsedRealtime();
            long duration=(now-startedAt)/1000L;
            String diagnostic=(incomplete?
                "Выдача проверена не полностью":"Доступная выдача просмотрена")+
                " · "+duration+" с";
            if(!lastStatus.isBlank())diagnostic+=" · "+lastStatus;
            MarketProgress record=marketProgress.get(shop.id);
            if(record!=null){
                record.finished=true;
                record.incomplete=incomplete;
                record.status=diagnostic;
                record.updatedAt=now;
            }
            listener.status(shop,diagnostic);
            if(completed==workersInSearch){
                running=false;
                listener.done();
            }else if(nextMarketIndex<pendingMarkets.size()){
                startNextMarket(this);
            }
        }
    }
    private int workersInSearch=0;

    ParallelScanCoordinator(Activity activity,FrameLayout root,WebView primaryBrowser,
            Handler handler,ScanEngine.Listener listener){
        this.activity=activity;
        this.root=root;
        this.primaryBrowser=primaryBrowser;
        this.handler=handler;
        this.listener=listener;
        // Do not reuse the login/product-inspection browser as a scanner.
        // Its old load callbacks and navigations can race the active search,
        // producing immediate false redirects for unrelated marketplaces.
        // ensureWorkers() creates three isolated search WebViews instead.
    }
    private boolean isCurrent(Worker worker){
        return running&&!worker.finished&&worker.generation==generation;
    }
    boolean isRunning(){return running;}
    boolean wasTimeLimited(){return wasTimeLimited;}
    boolean coverageIncomplete(){return coverageIncomplete;}
    String marketBreakdown(String id){
        String completedReport=completedBreakdown.get(id);
        if(completedReport!=null)return completedReport;
        for(Worker worker:workers)
            if(worker.shop!=null&&worker.shop.id.equals(id)&&
                worker.generation==generation)
                return worker.scan.resultBreakdown();
        return "Этапы поиска ещё не начались";
    }
    String progressSummary(){
        StringBuilder report=new StringBuilder(running?
            "Поиск идёт · обработано площадок: ":
            "Поиск завершён · обработано площадок: ")
            .append(completed).append("/").append(workersInSearch);
        long now=android.os.SystemClock.elapsedRealtime();
        for(Shop shop:pendingMarkets){
            MarketProgress record=marketProgress.get(shop.id);
            report.append("\n").append(shop.name).append(": ");
            if(record==null||!record.started){
                report.append("подключаемся");
            }else if(record.finished){
                report.append(record.incomplete?"выдача неполна":"завершено");
            }else if(now-record.updatedAt>45000){
                report.append("ожидаем ответ магазина");
            }else if(record.status.isBlank()){
                report.append("ищем товары");
            }else{
                String status=record.status.replace('\n',' ').trim();
                report.append(status.length()>76?status.substring(0,76)+"…":status);
            }
        }
        return report.toString();
    }
    String detailedProgress(){
        StringBuilder report=new StringBuilder();
        long now=android.os.SystemClock.elapsedRealtime();
        report.append("Площадки: обработано ").append(completed)
            .append(" из ").append(workersInSearch).append('\n');
        for(Shop shop:pendingMarkets){
            MarketProgress record=marketProgress.get(shop.id);
            report.append(shop.name).append(": ");
            if(record==null||!record.started)report.append("ещё не запущена");
            else{
                report.append(record.finished?
                    (record.incomplete?"завершена с ограничениями":"завершена"):
                    "в работе");
                if(!record.status.isBlank())
                    report.append(" · ").append(record.status);
                report.append(" · последнее обновление ")
                    .append(Math.max(0,(now-record.updatedAt)/1000))
                    .append(" с назад");
            }
            report.append('\n');
        }
        return report.toString();
    }
    void start(String query,int maxCards,List<Shop> markets){
        start(query,maxCards,markets,DeliveryPolicy.standard());
    }
    void start(String query,int maxCards,List<Shop> markets,DeliveryPolicy delivery){
        stop();
        if(markets==null||markets.isEmpty()){
            listener.done();
            return;
        }
        // A login completed in the visible browser uses the same Android
        // WebView cookie jar as each search worker. Flush recent cookie
        // changes before starting the parallel navigation.
        CookieManager.getInstance().flush();
        generation++;
        running=true;
        completed=0;
        workersInSearch=markets.size();
        wasTimeLimited=false;
        coverageIncomplete=false;
        completedBreakdown.clear();
        frontier.configure(delivery);
        activeQuery=query;
        pendingMarkets.clear();
        pendingMarkets.addAll(markets);
        marketProgress.clear();
        for(Shop market:pendingMarkets)
            marketProgress.put(market.id,new MarketProgress());
        // Working WB and Yandex results should not be blocked behind a
        // challenge-heavy Ozon WebView. Order affects start only, not ranking.
        pendingMarkets.sort(java.util.Comparator.comparingInt(
            shop->shop.id.equals("wb")?0:shop.id.equals("ym")?1:2));
        nextMarketIndex=0;
        int workerCount=Math.min(MAX_PARALLEL,pendingMarkets.size());
        ensureWorkers(workerCount);
        for(int index=0;index<workerCount;index++)
            startNextMarket(workers.get(index));
    }
    private void startNextMarket(Worker worker){
        if(!running||nextMarketIndex>=pendingMarkets.size())return;
        worker.shop=pendingMarkets.get(nextMarketIndex++);
        worker.generation=generation;
        worker.finished=false;
        worker.lastStatus="";
        worker.startedAt=android.os.SystemClock.elapsedRealtime();
        worker.lastStatusAt=worker.startedAt;
        MarketProgress record=marketProgress.get(worker.shop.id);
        if(record!=null){
            record.started=true;
            record.finished=false;
            record.incomplete=false;
            record.status="Ищем точную модель…";
            record.startedAt=worker.startedAt;
            record.updatedAt=worker.startedAt;
        }
        worker.scan.setPriceFrontier(frontier);
        worker.scan.start(activeQuery,Integer.MAX_VALUE,
            java.util.Collections.singletonList(worker.shop));
    }
    void onPageFinished(WebView view,String url){
        Worker worker=byBrowser.get(view);
        if(worker!=null&&isCurrent(worker))worker.scan.onPageFinished(url);
    }
    void onPageError(WebView view,String url){
        Worker worker=byBrowser.get(view);
        if(worker!=null&&isCurrent(worker))worker.scan.onPageError(url);
    }
    void onHttpError(WebView view,String url,int statusCode){
        Worker worker=byBrowser.get(view);
        if(worker!=null&&isCurrent(worker))worker.scan.onHttpError(url,statusCode);
    }
    void stop(){
        generation++;
        running=false;
        pendingMarkets.clear();
        nextMarketIndex=0;
        for(Worker worker:workers){
            worker.scan.stop();
            worker.finished=true;
            worker.view.stopLoading();
        }
    }
    void destroy(){
        stop();
        for(Worker worker:workers){
            root.removeView(worker.view);
            worker.view.destroy();
        }
        workers.clear();
        byBrowser.clear();
    }
    private void ensureWorkers(int count){
        while(workers.size()>count){
            Worker unused=workers.remove(workers.size()-1);
            byBrowser.remove(unused.view);
            root.removeView(unused.view);
            unused.view.destroy();
        }
        while(workers.size()<count){
            WebView view=new WebView(activity);
            view.setBackgroundColor(Color.WHITE);
            WebSettings settings=view.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            settings.setJavaScriptCanOpenWindowsAutomatically(false);
            settings.setSupportMultipleWindows(false);
            settings.setMediaPlaybackRequiresUserGesture(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(view,true);
            MarketplaceNetworkCapture.install(view);
            // Same full-screen viewport as the main WebView is critical for
            // JavaScript-rendered lazy-loaded marketplace search grids.
            // The main app's opaque pane covers these extra WebViews.
            // Put each worker above the browser used for login, but still
            // behind the opaque main application pane. This keeps a real
            // full-screen viewport while avoiding a worker being occluded by
            // another WebView at the Android compositor level.
            int layer=Math.max(0,root.indexOfChild(primaryBrowser)+1);
            root.addView(view,layer,new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
            Worker worker=new Worker(view);
            view.setWebViewClient(new WebViewClient(){
                @Override public void onPageFinished(WebView browser,String url){
                    onPageFinishedFromWorker(worker,url);
                }
                @Override public void onReceivedError(WebView browser,
                        WebResourceRequest request,WebResourceError error){
                    if(request.isForMainFrame()&&isCurrent(worker))
                        worker.scan.onPageError(request.getUrl().toString());
                }
                @Override public void onReceivedHttpError(WebView browser,
                        WebResourceRequest request,WebResourceResponse response){
                    if(request.isForMainFrame()&&isCurrent(worker))
                        worker.scan.onHttpError(request.getUrl().toString(),
                            response.getStatusCode());
                }
                @Override public boolean shouldOverrideUrlLoading(WebView browser,
                        WebResourceRequest request){
                    return !"https".equalsIgnoreCase(request.getUrl().getScheme());
                }
            });
            workers.add(worker);
            byBrowser.put(view,worker);
        }
    }
    private void onPageFinishedFromWorker(Worker worker,String url){
        if(isCurrent(worker))worker.scan.onPageFinished(url);
    }
}
