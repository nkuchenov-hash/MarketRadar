package com.marketradar.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * On-device evidence trail for the actual storefront search.
 *
 * Never copies cookies, tokens, raw page HTML or URL query strings into a
 * report. The card's public product path (short identifier), scraped price,
 * brief title and decision stage let us distinguish four independent causes
 * of a missed cheap offer: not fetched, not parsed, prefiltered, rejected.
 */
final class SearchEvidenceAudit {
    static final class Card {
        final String path;
        String title="",delivery="",stage="";
        int price=0;
        boolean eligible=false;
        Card(String path){this.path=path;}
    }

    private final LinkedHashMap<String,Card> cards=new LinkedHashMap<>();
    private int snapshots=0,rawLinks=0,rejected=0,noPrice=0,accepted=0;
    private final java.util.TreeSet<Integer> domPrices=new java.util.TreeSet<>();
    private int domPriceSnapshots=0;
    private static final int MAX_CARDS=4000;

    static String path(String url){
        try{
            URI uri=new URI(url);
            if(!"https".equalsIgnoreCase(uri.getScheme()))return "";
            String path=uri.getPath();
            return path==null?"":path.replaceAll("/+$","");
        }catch(Exception ignored){return "";}
    }

    private Card find(String url){
        return cards.get(path(url));
    }

    void observe(JSONArray links,Shop shop,String query){
        if(links==null||shop==null)return;
        snapshots++;
        for(int i=0;i<links.length();i++){
            JSONObject item=links.optJSONObject(i);
            if(item==null)continue;
            String url=item.optString("url","");
            if(!shop.productLink(url))continue;
            rawLinks++;
            String key=path(url);
            Card card=cards.get(key);
            if(card==null){
                if(cards.size()>=MAX_CARDS)continue;
                card=new Card(key);
                cards.put(key,card);
            }
            String title=item.optString("title","");
            String alt=item.optString("imageAlt","");
            String context=item.optString("context","");
            boolean candidate=CandidateDiscovery.shouldInspect(query,title,context,alt);
            int price=ScanEngine.readCardPrice(context);
            String delivery=ScanEngine.readDeliveryHint(item.optString("deliveryHint",""));
            if(delivery.isBlank())delivery=ScanEngine.readDeliveryHint(context);
            if(card.title.isBlank() || title.length()>card.title.length())
                card.title=title;
            if(price>0&&(card.price==0||price<card.price)){
                card.price=price;
                card.delivery=delivery;
            }else if(card.delivery.isBlank()&&!delivery.isBlank())card.delivery=delivery;
            if(candidate)card.eligible=true;
            if(card.stage.isEmpty()||card.stage.equals("в выдаче")||
               card.stage.equals("не отобрана")){
                card.stage=card.eligible?"отобрана из выдачи":"не отобрана";
            }
        }
        accepted=0;rejected=0;noPrice=0;
        for(Card card:cards.values()){
            if(card.eligible)accepted++;else rejected++;
            if(card.price==0)noPrice++;
        }
    }

    void observeDomPrices(JSONArray prices){
        if(prices==null)return;
        domPriceSnapshots++;
        for(int i=0;i<prices.length();i++){
            int price=prices.optInt(i,0);
            if(price>=100&&price<=50000000)domPrices.add(price);
        }
    }

    /**
     * Detect cheap visible price labels that are not associated with any
     * extracted product URL. These are investigation leads, not offers:
     * the DOM also contains installment, accessory and banner prices.
     * No price is hard-coded; every scan derives its own observed range.
     */
    String unmatchedLowPrices(){
        if(domPrices.isEmpty())return "Ценники на странице не извлечены";
        java.util.HashSet<Integer> linkedPrices=new java.util.HashSet<>();
        int lowestCandidate=Integer.MAX_VALUE;
        for(Card card:cards.values()){
            if(card.price<=0)continue;
            linkedPrices.add(card.price);
            if(card.eligible)lowestCandidate=Math.min(lowestCandidate,card.price);
        }
        // If no candidates were extracted, inspect the first ten DOM prices.
        // Otherwise inspect all price labels up to the cheapest candidate.
        StringBuilder out=new StringBuilder();
        int count=0;
        for(int amount:domPrices){
            if(lowestCandidate!=Integer.MAX_VALUE&&amount>lowestCandidate)break;
            if(linkedPrices.contains(amount))continue;
            if(count++>=10)break;
            if(out.length()>0)out.append(", ");
            out.append(amount).append(" ₽");
        }
        if(count==0)return "Нет непривязанных ценников дешевле найденных кандидатов";
        return "Низкие ценники без привязки к карточке (требуют проверки модели и типа цены): "+
            out;
    }

    void stage(String url,String stage){
        Card card=find(url);
        if(card!=null)card.stage=stage;
    }

    String summary(){
        ArrayList<Card> sorted=new ArrayList<>(cards.values());
        sorted.sort(Comparator
            .comparingInt((Card c)->c.price>0?c.price:Integer.MAX_VALUE)
            .thenComparing(c->c.path));
        StringBuilder out=new StringBuilder();
        out.append("Аудит видимой выдачи: снимков ").append(snapshots)
            .append("; товарных ссылок ").append(rawLinks)
            .append("; уникальных URL ").append(cards.size())
            .append("; отобрано до ИИ ").append(accepted)
            .append("; исключено до ИИ ").append(rejected)
            .append("; без цены ").append(noPrice);
        out.append("; ценников в тексте страницы ").append(domPrices.size());
        if(!domPrices.isEmpty()){
            out.append("\nСамые низкие ценники в DOM (не обязательно нужная модель): ");
            int shown=0;
            for(Integer value:domPrices){
                if(shown++>=12)break;
                if(shown>1)out.append(", ");
                out.append(value).append(" ₽");
            }
        }
        out.append("\n").append(unmatchedLowPrices());
        appendExamples(out,sorted,true,8,"Самые дешёвые подходящие кандидаты");
        appendExamples(out,sorted,false,3,"Дешёвые ссылки, исключённые до ИИ");
        return out.toString();
    }

    private static void appendExamples(StringBuilder out,ArrayList<Card> sorted,
            boolean candidates,int max,String heading){
        int examples=0;
        for(Card card:sorted){
            if(card.eligible!=candidates||card.price<=0)continue;
            if(examples++==0)out.append("\n").append(heading).append(':');
            if(examples>max)break;
            out.append("\n  ").append(card.price).append(" ₽")
                .append(" · ").append(card.delivery.isBlank()?"дата ?":card.delivery)
                .append(" · ").append(card.stage)
                .append(" · ").append(publicId(card.path))
                .append(" · ").append(trim(card.title,74));
        }
    }

    private static String publicId(String path){
        java.util.regex.Matcher id=java.util.regex.Pattern
            .compile("(\\d{6,})").matcher(path);
        if(id.find())return "ID "+id.group(1);
        String fragment=path.replaceAll("/+$","");
        int slash=fragment.lastIndexOf('/');
        if(slash>=0)fragment=fragment.substring(slash+1);
        return trim(fragment,24);
    }
    private static String trim(String value,int limit){
        if(value==null)return "";
        String clean=value.replaceAll("[\\r\\n\\t]"," ").trim();
        return clean.length()>limit?clean.substring(0,limit)+"…":clean;
    }
}
