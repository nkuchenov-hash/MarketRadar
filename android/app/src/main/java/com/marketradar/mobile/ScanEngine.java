package com.marketradar.mobile;

import android.os.Handler;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.ArrayList;
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
            const prices = [];
            for (const e of [...document.querySelectorAll('span,strong,ins,b')].slice(0, 4500)) {
              if (!visible(e)) continue;
              const t = (e.innerText || '').trim();
              if (t.length > 1 && t.length < 95 && (t.includes('₽') || t.toLowerCase().includes('руб'))) {
                if (!prices.includes(t)) prices.push(t);
              }
              if (prices.length >= 60) break;
            }
            return JSON.stringify({
              url: location.href, title: document.title,
              heading: document.querySelector('h1')?.innerText || '',
              text: (body?.innerText || '').slice(0, 42000),
              links: links.slice(0, 1200),
              prices
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
    private final ArrayList<String> candidates = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    private boolean running=false;
    private String query="";
    private Shop shop=null;
    private int marketIndex=0, cardIndex=0, token=0, checked=0;
    private boolean details=false;

    ScanEngine(WebView browser, Handler handler, Listener listener) {
        this.browser=browser;this.handler=handler;this.listener=listener;
    }
    boolean isRunning(){return running;}
    void start(String q) {
        stop();
        query=q.trim(); running=true; marketIndex=0;checked=0;
        nextMarket();
    }
    void stop() { running=false;token++; }

    private void nextMarket() {
        if(!running)return;
        if(marketIndex>=Shop.ALL.size()) {
            running=false;
            listener.done();
            return;
        }
        shop=Shop.ALL.get(marketIndex++);
        candidates.clear();seen.clear();
        cardIndex=0;details=false;
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
        handler.postDelayed(() -> {
            if(running)failPage("Ошибка загрузки");
        },1500);
    }
    private void failPage(String message) {
        token++;
        if(details) {
            listener.status(shop,message+" · проверено карточек: "+checked);
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
        JSONArray links=data.optJSONArray("links");
        if(links!=null){
            for(int i=0;i<links.length();i++){
                JSONObject candidate=links.optJSONObject(i);
                if(candidate==null)continue;
                String url=candidate.optString("url","");
                String title=candidate.optString("title","");
                String context=candidate.optString("context","");
                if(shop.productLink(url)&&exact(query,title+" "+context)&&seen.add(url)){
                    candidates.add(url);
                }
                if(candidates.size()>=8)break;
            }
        }
        if(candidates.isEmpty()){
            listener.status(shop,"Точные карточки не обнаружены — это не значит, что товара нет");
            nextMarket();return;
        }
        details=true;
        listener.status(shop,"Найдено "+candidates.size()+" карточек. Проверяем цены…");
        nextCandidate();
    }
    private void nextCandidate() {
        if(!running)return;
        if(cardIndex>=candidates.size()){
            listener.status(shop,"Проверено карточек: "+checked+". Покрытие неполное");
            nextMarket();return;
        }
        load(candidates.get(cardIndex++));
    }
    private void readDetail(JSONObject data,String url) {
        checked++;
        String title=data.optString("heading","").trim();
        if(title.isEmpty())title=data.optString("title","").trim();
        if(exact(query,title)){
            int price=readPrice(data.optJSONArray("prices"));
            String note=price>0?"Цена обнаружена на карточке, условия оплаты не подтверждены":
                        "Карточка найдена, цена не прочитана";
            listener.offer(new Offer(shop,url,title,price,note));
        }
        listener.status(shop,"Карточек проверено: "+checked+" / "+candidates.size());
        nextCandidate();
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
          .replaceAll("[^\\p{L}\\p{N}]+"," ").trim().replaceAll("\\s+"," ");
    }
    static boolean exact(String query,String title){
        String q=norm(query),t=norm(title);
        if(q.isEmpty()||t.isEmpty())return false;
        String[] accessories={"чехол","крепление","кронштейн","подставка","кабель","адаптер",
             "держатель","пульт","наклейка","защитное стекло","корпус","запчасть"};
        for(String word:accessories)if(t.contains(word)&&!q.contains(word))return false;
        if(q.contains("мини 3 про")||q.contains("yndx 00059")){
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
            if(term.matches(".*[0-9].*")&&!all.contains(term))return false;
            if(all.contains(term))ok++;
        }
        return required>0&&ok>=Math.ceil(required*0.8);
    }
}
