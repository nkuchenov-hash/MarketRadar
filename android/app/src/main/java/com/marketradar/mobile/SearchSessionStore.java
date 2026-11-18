package com.marketradar.mobile;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores verified offers locally, atomically, before Android may reclaim the process.
 * Does not store browser cookies, logins, API keys, or unverified candidates.
 */
final class SearchSessionStore {
    static final String KEY="saved_search_results_v1";
    private SearchSessionStore(){}

    static final class Snapshot {
        final String query;
        final DeliveryPolicy policy;
        final ArrayList<ScanEngine.Offer> offers;
        final boolean interrupted;
        final String message;
        Snapshot(String query,DeliveryPolicy policy,
                 ArrayList<ScanEngine.Offer> offers,boolean interrupted,String message){
            this.query=query;this.policy=policy;this.offers=offers;
            this.interrupted=interrupted;this.message=message;
        }
    }

    static String encode(String query,DeliveryPolicy policy,List<ScanEngine.Offer> offers,
                         boolean searching,String message){
        try {
            JSONObject state=new JSONObject();
            state.put("version",1);
            state.put("query",query==null?"":query);
            state.put("scanDate",policy.today.toString());
            state.put("maxDays",policy.maxDays);
            state.put("lateDiscount",policy.allowSlowDiscount);
            state.put("discountPercent",policy.slowDiscountPercent);
            state.put("searching",searching);
            state.put("message",message==null?"":message);
            JSONArray records=new JSONArray();
            for(ScanEngine.Offer o:offers){
                if(o==null||o.shop==null||!o.shop.productLink(o.url))continue;
                JSONObject item=new JSONObject();
                item.put("shop",o.shop.id);
                item.put("url",o.url);
                item.put("title",o.title);
                item.put("price",o.price);
                item.put("note",o.note);
                item.put("delivery",o.deliveryHint);
                item.put("condition",o.priceCondition);
                item.put("imageUrl",o.imageUrl);
                records.put(item);
            }
            state.put("offers",records);
            return state.toString();
        }catch(Exception ignored){return "";}
    }

    static Snapshot decode(String serialized){
        if(serialized==null||serialized.isBlank())return null;
        try{
            JSONObject obj=new JSONObject(serialized);
            if(obj.optInt("version")!=1)return null;
            String query=obj.optString("query","");
            if(query.isBlank())return null;
            DeliveryPolicy policy=new DeliveryPolicy(
                obj.optInt("maxDays",3),obj.optBoolean("lateDiscount",false),
                obj.optInt("discountPercent",15),
                LocalDate.parse(obj.getString("scanDate")));
            ArrayList<ScanEngine.Offer> found=new ArrayList<>();
            JSONArray items=obj.optJSONArray("offers");
            if(items!=null)for(int i=0;i<items.length();i++){
                JSONObject o=items.optJSONObject(i);
                if(o==null)continue;
                Shop shop=Shop.byId(o.optString("shop",""));
                String url=o.optString("url","");
                if(shop==null||!shop.productLink(url))continue;
                found.add(new ScanEngine.Offer(shop,url,o.optString("title",""),
                    o.optInt("price",0),o.optString("note",""),
                    o.optString("delivery",""),o.optString("condition",""),
                    o.optString("imageUrl","")));
            }
            return new Snapshot(query,policy,found,obj.optBoolean("searching",false),
                obj.optString("message",""));
        }catch(Exception ignored){return null;}
    }

    static void save(SharedPreferences preferences,String query,DeliveryPolicy policy,
                     List<ScanEngine.Offer> offers,boolean searching,String message){
        String json=encode(query,policy,offers,searching,message);
        if(!json.isBlank())preferences.edit().putString(KEY,json).commit();
    }

    static Snapshot load(SharedPreferences preferences){
        return decode(preferences.getString(KEY,""));
    }
}
