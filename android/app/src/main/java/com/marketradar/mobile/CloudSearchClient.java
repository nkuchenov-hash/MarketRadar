package com.marketradar.mobile;

import android.os.Handler;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android -> real server-side Chromium. No marketplace cookies, browser
 * HTML, credentials or authorization state leave this device.
 */
final class CloudSearchClient {
    interface Listener {
        void onMarket(Shop shop, String state, String breakdown, boolean incomplete);
        void onOffer(ScanEngine.Offer offer);
        void onComplete(boolean incomplete, boolean cancelled);
    }

    private final Handler ui;
    private final Listener listener;
    private final SharedPreferences prefs;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private volatile int generation=0;
    private volatile boolean running=false;
    private volatile String currentJob="";
    private volatile String baseUrl="";
    private volatile String accessKey="";
    private volatile boolean coverageIncomplete=true;
    private final Map<String,String> statuses=new HashMap<>();
    private final Set<String> received=new HashSet<>();

    CloudSearchClient(Handler ui,SharedPreferences prefs,Listener listener){
        this.ui=ui;
        this.prefs=prefs;
        this.listener=listener;
    }

    static String validBase(String raw){
        try{
            URI uri=new URI(raw.trim());
            if(!"https".equalsIgnoreCase(uri.getScheme())||
                uri.getHost()==null||uri.getUserInfo()!=null||
                uri.getRawQuery()!=null||uri.getRawFragment()!=null)
                return "";
            String path=uri.getRawPath();
            if(path!=null&&!path.isEmpty()&&!path.equals("/"))return "";
            return "https://"+uri.getHost()+
                (uri.getPort()>0?":"+uri.getPort():"");
        }catch(Exception ex){return "";}
    }

    boolean isRunning(){return running;}
    boolean coverageIncomplete(){return coverageIncomplete;}
    String progressSummary(){
        if(running)return "Облачный Chromium · поиск выполняется";
        return "Облачный Chromium · поиск завершён";
    }

    void start(String url,String query,List<Shop> markets){
        stop();
        final int request=++generation;
        baseUrl=validBase(url);
        accessKey=prefs.getString("cloud_access_key","").trim();
        if(baseUrl.isEmpty())throw new IllegalArgumentException("Требуется HTTPS-адрес сервера");
        running=true;
        coverageIncomplete=true;
        statuses.clear();
        received.clear();
        String prior=prefs.getString("cloud_pending_job_id","");
        if(!query.equals(prefs.getString("cloud_pending_query","")) ||
            !baseUrl.equals(prefs.getString("cloud_pending_server","")))
            prior="";
        final String resumeJob=prior;
        executor.execute(()->run(request,query,new ArrayList<>(markets),resumeJob));
    }

    void stop(){
        final String job=currentJob;
        final String server=baseUrl;
        running=false;
        generation++;
        currentJob="";
        if(!job.isBlank()&&!server.isBlank()){
            clearJob(job);
            executor.execute(()->{
                try{request("DELETE",server+"/api/mobile/browser/jobs/"+job,null);}catch(Exception ignored){}
            });
        }
    }

    /** Activity destroyed: leave the server working and resume after reopening. */
    void detach(){
        running=false;
        generation++;
        currentJob="";
        executor.shutdownNow();
    }

    private void clearJob(String id){
        if(id.equals(prefs.getString("cloud_pending_job_id","")))
            prefs.edit().remove("cloud_pending_job_id")
                .remove("cloud_pending_query").remove("cloud_pending_server").apply();
    }

    private boolean active(int request){
        return running && generation==request;
    }

    private void run(int request,String query,List<Shop> markets,String resumeJob){
        boolean incomplete=true;
        String job=resumeJob;
        try{
            if(!job.isBlank()){
                try{
                    request("GET",baseUrl+"/api/mobile/browser/jobs/"+job,null);
                }catch(Exception unavailable){
                    clearJob(job);
                    job="";
                }
            }
            if(job.isBlank()){
                JSONArray shops=new JSONArray();
                for(Shop shop:markets)shops.put(shop.id);
                JSONObject payload=new JSONObject()
                    .put("query",query).put("shops",shops);
                JSONObject started=request("POST",
                    baseUrl+"/api/mobile/browser/start",payload);
                job=started.optString("id","");
                if(job.length()<20)throw new IllegalStateException("Сервер не вернул ID поиска");
            }
            if(!active(request)){
                // User pressed Stop while POST was still in flight.
                try{request("DELETE",baseUrl+"/api/mobile/browser/jobs/"+job,null);}
                catch(Exception ignored){}
                return;
            }
            currentJob=job;
            prefs.edit().putString("cloud_pending_job_id",job)
                .putString("cloud_pending_query",query)
                .putString("cloud_pending_server",baseUrl).apply();
            int rounds=0;
            JSONObject latest=null;
            while(active(request)&&rounds++<150){
                latest=request("GET",
                    baseUrl+"/api/mobile/browser/jobs/"+job,null);
                final JSONObject update=latest;
                ui.post(()->{if(active(request))deliver(update,markets);});
                if(!"running".equals(latest.optString("state","running")))break;
                Thread.sleep(1300);
            }
            if(!active(request))return;
            if(latest==null||"running".equals(latest.optString("state","running")))
                throw new IllegalStateException("Сервер не завершил поиск в установленный срок");
            incomplete=false;
            JSONObject all=latest.optJSONObject("markets");
            for(Shop shop:markets){
                JSONObject part=all==null?null:all.optJSONObject(shop.id);
                if(part==null || !"complete".equals(part.optString("state")))
                    incomplete=true;
            }
        }catch(InterruptedException ex){
            Thread.currentThread().interrupt();
            return;
        }catch(Exception ex){
            final String message=ex.getMessage()==null?ex.getClass().getSimpleName():
                ex.getMessage().replaceAll("[\\r\\n]"," ").substring(0,
                Math.min(175,ex.getMessage().length()));
            ui.post(()->{
                if(!active(request))return;
                for(Shop shop:markets)
                    listener.onMarket(shop,
                        "Облачный поиск недоступен: "+message,
                        "Ошибка: "+message,true);
            });
        }finally{
            if(active(request)&&!currentJob.isBlank())clearJob(currentJob);
            final boolean fail=incomplete;
            ui.post(()->{
                if(!active(request))return;
                running=false;
                currentJob="";
                coverageIncomplete=fail;
                listener.onComplete(fail,false);
            });
        }
    }

    private void deliver(JSONObject data,List<Shop> markets){
        JSONObject all=data.optJSONObject("markets");
        for(Shop shop:markets){
            JSONObject item=all==null?null:all.optJSONObject(shop.id);
            if(item==null)continue;
            String state=item.optString("state","pending");
            String message=item.optString("message","");
            String summary=shop.name+": "+state+" · "+message;
            String report="найдено ссылок: "+item.optInt("candidates")+
                "; проверено карточек: "+item.optInt("checked")+
                "; совпадений в выдаче: "+item.optInt("matching_cards")+
                "; полный обход: "+item.optBoolean("coverage_complete")+
                "; аудит: "+item.optJSONObject("audit");
            if(!summary.equals(statuses.get(shop.id))){
                statuses.put(shop.id,summary);
                listener.onMarket(shop,summary,report,
                    !"complete".equals(state));
            }
        }
        JSONArray offers=data.optJSONArray("offers");
        if(offers==null)return;
        for(int i=0;i<offers.length();i++){
            JSONObject item=offers.optJSONObject(i);
            if(item==null)continue;
            Shop shop=Shop.byId(item.optString("market_id",""));
            if(shop==null)continue;
            String url=item.optString("url","");
            int price=item.optInt("price",0);
            if(!shop.productLink(url)||price<=0)continue;
            String key=url+"\u0001"+price+"\u0001"+item.optString("delivery","");
            if(!received.add(key))continue;
            listener.onOffer(new ScanEngine.Offer(shop,url,
                item.optString("title","Товар"),price,
                item.optString("note","Цена из облачного поиска"),
                item.optString("delivery",""),
                item.optString("condition",""),
                item.optString("image_url","")));
        }
    }

    private JSONObject request(String method,String address,JSONObject body)
            throws Exception {
        URL url=new URL(address);
        if(!"https".equalsIgnoreCase(url.getProtocol()))
            throw new IllegalArgumentException("Сервер должен использовать HTTPS");
        HttpURLConnection conn=(HttpURLConnection)url.openConnection();
        try{
            conn.setRequestMethod(method);
            conn.setConnectTimeout(8500);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Accept","application/json");
            if(!accessKey.isBlank())
                conn.setRequestProperty("X-MarketRadar-Key",accessKey);
            if(body!=null){
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
                try(OutputStream output=conn.getOutputStream()){
                    output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status=conn.getResponseCode();
            if(status>=300&&status<400)
                throw new IllegalStateException("Редирект сервера HTTP "+status);
            try(InputStream stream=status<400?conn.getInputStream():conn.getErrorStream()){
                if(stream==null)throw new IllegalStateException("HTTP "+status);
                ByteArrayOutputStream output=new ByteArrayOutputStream();
                byte[] buf=new byte[8192];int n;
                while((n=stream.read(buf))!=-1){
                    if(output.size()+n>1024*1024)throw new IllegalStateException("Большой ответ сервера");
                    output.write(buf,0,n);
                }
                JSONObject result=new JSONObject(output.toString(StandardCharsets.UTF_8));
                if(status>=400)
                    throw new IllegalStateException("HTTP "+status+" · "+
                        result.optString("detail","Ошибка сервера").substring(0,Math.min(100,
                        result.optString("detail","Ошибка сервера").length())));
                return result;
            }
        }finally{conn.disconnect();}
    }
}
