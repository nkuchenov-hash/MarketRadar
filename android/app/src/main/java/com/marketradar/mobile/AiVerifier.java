package com.marketradar.mobile;

import android.os.Handler;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

/**
 * Real language-model verification of product identity.
 *
 * A backend runs the model with a SERVER-OWNED API key. No API keys or
 * marketplace authentication cookies are embedded in or exported by the APK.
 * Unknown verdicts and any network failure fail CLOSED.
 */
final class AiVerifier {
    interface Callback {
        void verified(List<ScanEngine.Offer> confirmed, String error);
    }

    static String endpoint(String configured) {
        if(configured==null)return null;
        try {
            URI uri=new URI(configured.trim());
            if(!"https".equalsIgnoreCase(uri.getScheme()) ||
                uri.getHost()==null || uri.getUserInfo()!=null ||
                uri.getQuery()!=null || uri.getFragment()!=null ||
                (uri.getPort()!=-1 && uri.getPort()!=443) ||
                (uri.getPath()!=null && !uri.getPath().isEmpty() &&
                 !uri.getPath().equals("/"))) return null;
            return "https://"+uri.getHost()+"/api/mobile/verify";
        }catch(Exception e){return null;}
    }

    static void verify(String server, String query, List<ScanEngine.Offer> all,
                       Handler ui, Callback callback){
        String url=endpoint(server);
        if(url==null){
            ui.post(()->callback.verified(new ArrayList<>(),
                "Не задан адрес HTTPS-сервера ИИ в разделе «Магазины»"));
            return;
        }
        // Limit the number of products per verification request, not the
        // amount of the untrusted marketplace content we transmit.
        List<ScanEngine.Offer> candidates=new ArrayList<>(
            all.subList(0,Math.min(24,all.size())));
        new Thread(()->{
            HttpsURLConnection connection=null;
            try{
                JSONArray items=new JSONArray();
                for(int i=0;i<candidates.size();i++){
                    ScanEngine.Offer offer=candidates.get(i);
                    JSONObject item=new JSONObject();
                    item.put("id",Integer.toString(i));
                    item.put("title",offer.title.length()>350?
                        offer.title.substring(0,350):offer.title);
                    // The short card note is price metadata, NOT a substitute
                    // for the actual product title. Do not transmit cookies.
                    item.put("context","");
                    items.put(item);
                }
                JSONObject body=new JSONObject();
                body.put("query",query);
                body.put("candidates",items);
                connection=(HttpsURLConnection)new URI(url).toURL().openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(4500);
                connection.setReadTimeout(18000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
                connection.setRequestProperty("Accept","application/json");
                byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try(OutputStream stream=connection.getOutputStream()){
                    stream.write(bytes);
                }
                if(connection.getResponseCode()!=200)
                    throw new IllegalStateException("AI service unavailable");
                byte[] response;
                try(InputStream stream=connection.getInputStream()){
                    response=stream.readNBytes(64_000);
                }
                JSONObject parsed=new JSONObject(new String(response,StandardCharsets.UTF_8));
                if(!parsed.optBoolean("verified",false))
                    throw new IllegalStateException("No verification");
                JSONArray decisions=parsed.getJSONArray("decisions");
                Map<String,Boolean> verdicts=new HashMap<>();
                for(int i=0;i<decisions.length();i++){
                    JSONObject d=decisions.optJSONObject(i);
                    if(d==null)continue;
                    String id=d.optString("id","");
                    if(!id.matches("[0-9]{1,2}"))continue;
                    if(!d.optBoolean("match",false) ||
                       !"same_product".equals(d.optString("relation",""))){
                        verdicts.put(id,false);
                        continue;
                    }
                    verdicts.put(id,true);
                }
                ArrayList<ScanEngine.Offer> good=new ArrayList<>();
                for(int i=0;i<candidates.size();i++)
                    if(Boolean.TRUE.equals(verdicts.get(Integer.toString(i))))
                        good.add(candidates.get(i));
                ui.post(()->callback.verified(good,null));
            }catch(Exception error){
                ui.post(()->callback.verified(new ArrayList<>(),
                    "ИИ не подтвердил результаты. Проверьте доступность сервера."));
            }finally{
                if(connection!=null)connection.disconnect();
            }
        },"MarketRadar-AI-Verify").start();
    }
}
