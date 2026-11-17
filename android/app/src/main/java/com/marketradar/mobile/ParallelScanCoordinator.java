package com.marketradar.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
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
    private final List<Worker> workers=new ArrayList<>();
    private final Map<WebView,Worker> byBrowser=new IdentityHashMap<>();
    private boolean running=false;
    private boolean wasTimeLimited=false;
    private int generation=0;
    private int completed=0;

    private final class Worker implements ScanEngine.Listener {
        final WebView view;
        final ScanEngine scan;
        Shop shop;
        int generation;
        boolean finished;
        long startedAt;
        String lastStatus="";

        Worker(WebView view){
            this.view=view;
            this.scan=new ScanEngine(view,handler,this);
        }
        @Override public void status(Shop market,String text){
            if(isCurrent(this)){
                lastStatus=text;
                listener.status(market,text);
            }
        }
        @Override public void offer(ScanEngine.Offer offer){
            if(isCurrent(this))listener.offer(offer);
        }
        @Override public void done(){
            if(!isCurrent(this)||finished)return;
            finished=true;
            completed++;
            wasTimeLimited|=scan.wasTimeLimited();
            long duration=(android.os.SystemClock.elapsedRealtime()-startedAt)/1000L;
            String diagnostic="Проверка завершена за "+duration+" с";
            if(!lastStatus.isBlank())diagnostic+=" · "+lastStatus;
            listener.status(shop,diagnostic);
            if(completed==workersInSearch){
                running=false;
                listener.done();
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
        Worker first=new Worker(primaryBrowser);
        workers.add(first);
        byBrowser.put(primaryBrowser,first);
    }
    private boolean isCurrent(Worker worker){
        return running&&!worker.finished&&worker.generation==generation;
    }
    boolean isRunning(){return running;}
    boolean wasTimeLimited(){return wasTimeLimited;}
    void start(String query,int maxCards,List<Shop> markets){
        stop();
        if(markets==null||markets.isEmpty()){
            listener.done();
            return;
        }
        generation++;
        running=true;
        completed=0;
        workersInSearch=markets.size();
        wasTimeLimited=false;
        ensureWorkers(markets.size());
        // Launch all shop searches without waiting for another store to finish.
        for(int index=0;index<markets.size();index++){
            Worker worker=workers.get(index);
            worker.shop=markets.get(index);
            worker.generation=generation;
            worker.finished=false;
            worker.lastStatus="";
            worker.startedAt=android.os.SystemClock.elapsedRealtime();
            worker.scan.start(query,maxCards,
                java.util.Collections.singletonList(worker.shop));
        }
    }
    void onPageFinished(WebView view,String url){
        Worker worker=byBrowser.get(view);
        if(worker!=null&&isCurrent(worker))worker.scan.onPageFinished(url);
    }
    void onPageError(WebView view,String url){
        Worker worker=byBrowser.get(view);
        if(worker!=null&&isCurrent(worker))worker.scan.onPageError(url);
    }
    void stop(){
        generation++;
        running=false;
        for(Worker worker:workers){
            worker.scan.stop();
            worker.finished=true;
            if(worker.view!=primaryBrowser)worker.view.stopLoading();
        }
    }
    void destroy(){
        stop();
        for(Worker worker:workers){
            if(worker.view!=primaryBrowser){
                root.removeView(worker.view);
                worker.view.destroy();
            }
        }
        workers.clear();
        byBrowser.clear();
    }
    private void ensureWorkers(int count){
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
