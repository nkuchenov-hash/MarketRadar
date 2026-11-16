package com.marketradar.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.WindowInsets;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONTokener;
import android.widget.Button;
import android.widget.CheckBox;
import android.content.res.ColorStateList;
import java.util.List;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Local-only Android prototype. Sign-in occurs on official marketplace sites in WebView. */
public final class MainActivity extends Activity implements ScanEngine.Listener {
    private static final int BG=0xFFF6F9FE, SURFACE=0xFFFFFFFF, BORDER=0xFFDCE6F4;
    private static final int TEXT=0xFF152844, MUTED=0xFF647791, ACCENT=0xFF2369E8;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ArrayList<ScanEngine.Offer> offers=new ArrayList<>();
    private final Map<String,TextView> statuses=new HashMap<>();
    private FrameLayout root;
    private LinearLayout mainPane, loginBar, results;
    private ScrollView searchScroll, accountsScroll;
    private Button navButton, depthButton;
    private boolean accountsVisible=false, fastSearch=false;
    private TextView bestPrice, priceSummary, marketProgress;
    private final Map<String,String> marketStates=new HashMap<>();
    private final Map<String,CheckBox> marketChoices=new HashMap<>();
    private List<Shop> lastSelectedMarkets=Shop.ALL;
    private WebView browser;
    private ScanEngine engine;
    private EditText queryInput;
    private Button searchButton;
    private TextView progress, count, loginCaption;
    private SharedPreferences prefs;
    private boolean loginMode=false;
    private Shop currentShop;
    private ScanEngine.Offer inspectedOffer;

    private int dp(float value){return (int)(getResources().getDisplayMetrics().density*value+0.5f);}
    private GradientDrawable bg(int color,int radius,int borderColor){
        GradientDrawable x=new GradientDrawable();
        x.setColor(color);x.setCornerRadius(dp(radius));
        if(borderColor!=0)x.setStroke(dp(1),borderColor);
        return x;
    }
    private TextView text(String value,int sp,int color,boolean bold){
        TextView t=new TextView(this);
        t.setText(value);t.setTextSize(sp);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }
    private LinearLayout linear(int orientation){
        LinearLayout l=new LinearLayout(this);l.setOrientation(orientation);return l;
    }
    private void add(LinearLayout target,View child,int w,int h){
        target.addView(child,new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h)));
    }
    private void gap(LinearLayout l,int height){add(l,new View(this),1,height);}
    private Button button(String title,boolean primary){
        Button b=new Button(this);
        b.setText(title);b.setTextSize(14);b.setAllCaps(false);
        b.setTextColor(primary?Color.WHITE:ACCENT);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setBackground(bg(primary?ACCENT:SURFACE,13,primary?0:BORDER));
        b.setMinHeight(0);b.setMinimumHeight(0);
        b.setPadding(dp(13),0,dp(13),0);return b;
    }
    private LinearLayout panel(){
        LinearLayout p=linear(LinearLayout.VERTICAL);
        p.setBackground(bg(SURFACE,18,BORDER));p.setPadding(dp(18),dp(17),dp(18),dp(17));
        return p;
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        prefs=getSharedPreferences("market_sessions",MODE_PRIVATE);
        buildBrowser();
        buildInterface();
        engine=new ScanEngine(browser,ui,this);
        for(Shop shop:Shop.ALL)refreshShop(shop);
    }
    private void buildBrowser(){
        root=new FrameLayout(this);root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            root.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        browser=new WebView(this);
        browser.setBackgroundColor(Color.WHITE);
        WebSettings s=browser.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSavePassword(false);
        browser.setWebContentsDebuggingEnabled(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser,true);
        browser.setWebChromeClient(new WebChromeClient());
        browser.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){
                if(!loginMode&&engine!=null)engine.onPageFinished(url);
                else if(loginMode&&inspectedOffer!=null)inspectPageDestination(url);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error){
                if(request.isForMainFrame()&&!loginMode&&engine!=null)engine.onPageError(request.getUrl().toString());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request){
                // Never load intent://, file:// or other non-HTTPS URLs in our authenticated browser.
                String scheme=request.getUrl().getScheme();
                if(!"https".equalsIgnoreCase(scheme))return true;
                return false;
            }
        });
        root.addView(browser,new FrameLayout.LayoutParams(-1,-1));
    }
    private void buildInterface(){
        mainPane=linear(LinearLayout.VERTICAL);mainPane.setBackgroundColor(BG);
        root.addView(mainPane,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout top=linear(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(18),dp(9),dp(18),dp(9));
        TextView brand=text("M  MarketRadar",21,TEXT,true);
        top.addView(brand,new LinearLayout.LayoutParams(0,dp(45),1));
        navButton=button("Магазины",false);
        add(top,navButton,105,42);
        navButton.setOnClickListener(v->setAccountsVisible(!accountsVisible));
        add(mainPane,top,-1,64);
        View line=new View(this);line.setBackgroundColor(BORDER);add(mainPane,line,-1,1);

        searchScroll=new ScrollView(this);
        searchScroll.setFillViewport(true);
        searchScroll.setClipToPadding(false);
        mainPane.addView(searchScroll,new LinearLayout.LayoutParams(-1,0,1));
        accountsScroll=new ScrollView(this);
        accountsScroll.setFillViewport(true);
        accountsScroll.setClipToPadding(false);
        mainPane.addView(accountsScroll,new LinearLayout.LayoutParams(-1,0,1));
        accountsScroll.setVisibility(View.GONE);
        LinearLayout page=linear(LinearLayout.VERTICAL);
        page.setPadding(dp(18),dp(16),dp(18),dp(36));
        searchScroll.addView(page);
        LinearLayout marketPage=linear(LinearLayout.VERTICAL);
        marketPage.setPadding(dp(18),dp(24),dp(18),dp(50));
        accountsScroll.addView(marketPage);
        add(page,text("СРАВНЕНИЕ ЦЕН",11,ACCENT,true),-1,19);
        gap(page,4);
        add(page,text("Найти лучшую цену",27,TEXT,true),-1,43);
        gap(page,3);
        add(page,text("Точная модель на Ozon, Wildberries и Яндекс Маркете.",13,MUTED,false),-1,-2);
        gap(page,16);

        queryInput=new EditText(this);
        queryInput.setSingleLine(true);
        queryInput.setTextSize(16);queryInput.setTextColor(TEXT);queryInput.setHintTextColor(MUTED);
        queryInput.setBackground(bg(SURFACE,13,BORDER));
        queryInput.setPadding(dp(15),0,dp(15),0);
        queryInput.setHint("Точная модель товара");
        queryInput.setText(prefs.getString("last_query","Яндекс Станция Мини 3 Про"));
        add(page,queryInput,-1,58);
        gap(page,15);
        LinearLayout criteria=panel();
        add(criteria,text("КРИТЕРИИ ПОИСКА",11,ACCENT,true),-1,20);
        add(criteria,text("Совпадение: точная модель и артикул",13,TEXT,true),-1,-2);
        gap(criteria,5);
        add(criteria,text("МАРКЕТПЛЕЙСЫ",11,MUTED,true),-1,19);
        LinearLayout marketsRow=linear(LinearLayout.HORIZONTAL);
        for(Shop shop:Shop.ALL){
            CheckBox cb=new CheckBox(this);
            cb.setText(shop.id.equals("ozon")?"Ozon":shop.id.equals("wb")?"WB":"Я.Маркет");
            cb.setTextSize(12);
            cb.setTextColor(TEXT);
            cb.setButtonTintList(ColorStateList.valueOf(ACCENT));
            cb.setChecked(prefs.getBoolean("scan_"+shop.id,true));
            cb.setOnCheckedChangeListener((b,checked)->
                prefs.edit().putBoolean("scan_"+shop.id,checked).apply());
            marketChoices.put(shop.id,cb);
            marketsRow.addView(cb,new LinearLayout.LayoutParams(0,dp(39),1));
        }
        add(criteria,marketsRow,-1,-2);
        gap(criteria,10);
        depthButton=button("Глубокий · до 10 карточек на магазин",false);
        add(criteria,depthButton,-1,44);
        depthButton.setOnClickListener(v->{
            fastSearch=!fastSearch;
            depthButton.setText(fastSearch?"Быстрый · до 3 карточек на магазин":
                "Глубокий · до 10 карточек на магазин");
        });
        add(page,criteria,-1,-2);
        gap(page,14);
        searchButton=button("Найти предложения",true);
        add(page,searchButton,-1,54);
        searchButton.setOnClickListener(v->beginSearch());
        gap(page,10);
        progress=text("Поиск пока не запущен.",13,MUTED,false);
        add(page,progress,-1,-2);
        gap(page,18);

        add(page,text("РЕЗУЛЬТАТЫ ПОИСКА",12,ACCENT,true),-1,22);
        gap(page,6);
        count=text("Результаты появятся здесь",13,MUTED,false);
        add(page,count,-1,-2);
        gap(page,11);
        LinearLayout summary=panel();
        add(summary,text("ИТОГ ПОИСКА",11,ACCENT,true),-1,19);
        gap(summary,4);
        bestPrice=text("—",29,TEXT,true);
        add(summary,bestPrice,-1,39);
        priceSummary=text("Минимальная цена по данным товара",12,MUTED,false);
        add(summary,priceSummary,-1,-2);
        gap(summary,11);
        marketProgress=text("Статусы магазинов появятся после поиска.",12,MUTED,false);
        add(summary,marketProgress,-1,-2);
        add(page,summary,-1,-2);
        gap(page,16);
        add(page,text("НАЙДЕННЫЕ КАРТОЧКИ",11,ACCENT,true),-1,22);
        gap(page,8);
        results=linear(LinearLayout.VERTICAL);add(page,results,-1,-2);
        gap(page,18);
        add(page,text("Тестовая версия. Цены, прочитанные со страницы, могут быть старыми или зависеть от карты. Итоговую сумму проверяйте в корзине. При сворачивании телефона поиск может остановиться.",12,MUTED,false),-1,-2);

        add(marketPage,text("МОИ МАРКЕТПЛЕЙСЫ",11,ACCENT,true),-1,22);
        add(marketPage,text("Подключения и сессии магазинов",24,TEXT,true),-1,-2);
        gap(marketPage,8);
        add(marketPage,text("Авторизуйтесь только на официальных сайтах. Cookies остаются в Android WebView. Сохранённая сессия не гарантирует активный вход.",13,MUTED,false),-1,-2);
        gap(marketPage,18);
        for(Shop shop:Shop.ALL){
            LinearLayout card=panel();
            LinearLayout header=linear(LinearLayout.HORIZONTAL);header.setGravity(Gravity.CENTER_VERTICAL);
            TextView mark=text(shop.name.substring(0,1),23,shop.accent,true);
            add(header,mark,35,45);
            LinearLayout col=linear(LinearLayout.VERTICAL);
            add(col,text(shop.name,16,TEXT,true),-1,25);
            TextView status=text("Не подключён",12,MUTED,false);
            statuses.put(shop.id,status);
            add(col,status,-1,-2);
            header.addView(col,new LinearLayout.LayoutParams(0,-2,1));
            Button open=button("Открыть",false);
            add(header,open,95,42);
            open.setOnClickListener(v->showShop(shop,shop.home));
            add(card,header,-1,-2);
            add(marketPage,card,-1,-2);
            gap(marketPage,11);
        }
        Button clear=button("Сбросить все входы на этом телефоне",false);
        add(marketPage,clear,-1,46);
        clear.setOnClickListener(v->confirmClear());
        gap(marketPage,24);


        loginBar=linear(LinearLayout.HORIZONTAL);
        loginBar.setGravity(Gravity.CENTER_VERTICAL);
        loginBar.setBackgroundColor(BG);
        loginBar.setPadding(dp(8),dp(7),dp(8),dp(7));
        Button back=button("Назад",false);
        add(loginBar,back,80,43);back.setOnClickListener(v->{
            if(browser.canGoBack())browser.goBack(); else finishShop();
        });
        loginCaption=text("Официальный сайт",14,TEXT,true);
        loginCaption.setGravity(Gravity.CENTER);
        loginBar.addView(loginCaption,new LinearLayout.LayoutParams(0,dp(43),1));
        Button done=button("Готово",true);
        add(loginBar,done,82,43);done.setOnClickListener(v->finishShop());
        FrameLayout.LayoutParams barParams=new FrameLayout.LayoutParams(-1,dp(65),Gravity.TOP);
        root.addView(loginBar,barParams);
        loginBar.setVisibility(View.GONE);
    }
    private void setAccountsVisible(boolean show){
        accountsVisible=show;
        if(engine!=null&&engine.isRunning()&&show){
            // Search continues only while the app is foregrounded; menus do not reset it.
        }
        searchScroll.setVisibility(show?View.GONE:View.VISIBLE);
        accountsScroll.setVisibility(show?View.VISIBLE:View.GONE);
        navButton.setText(show?"Поиск":"Магазины");
    }
    private void refreshShop(Shop shop){
        TextView label=statuses.get(shop.id);if(label==null)return;
        boolean marked=prefs.getBoolean("user_finished_"+shop.id,false);
        String cookie=CookieManager.getInstance().getCookie(shop.home);
        if(marked && cookie!=null&&!cookie.isEmpty())
            label.setText("Сессия сохранена · вход не проверен");
        else if(marked)label.setText("Вход не подтверждён · откройте магазин");
        else label.setText("Откройте магазин и войдите");
    }
    private void showShop(Shop shop,String url){
        showShopInternal(shop,url,null);
    }
    private void inspectOffer(ScanEngine.Offer offer){
        if(!offer.shop.productLink(offer.url)){
            new AlertDialog.Builder(this)
                .setMessage("Ссылка не ведёт прямо на карточку товара.")
                .setPositiveButton("ОК",null).show();
            return;
        }
        showShopInternal(offer.shop,offer.url,offer);
    }
    private void showShopInternal(Shop shop,String url,ScanEngine.Offer offer){
        if(engine!=null&&engine.isRunning()){
            engine.stop();
            searchButton.setText("Найти предложения");
        }
        searchButton.setEnabled(true);
        currentShop=shop;inspectedOffer=offer;loginMode=true;
        browser.stopLoading();
        mainPane.setVisibility(View.GONE);
        loginBar.setVisibility(View.VISIBLE);
        loginCaption.setText(offer==null?
            shop.name+" · официальный сайт":shop.name+" · открываем товар");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if(!shop.allowed(url))url=shop.home;
        browser.loadUrl(url);
    }
    private void inspectPageDestination(String url){
        if(inspectedOffer==null||currentShop==null)return;
        if(!currentShop.productLink(url)){
            loginCaption.setText("Не карточка — магазин перенаправил");
            return;
        }
        final String requested=prefs.getString("last_query","");
        final String currentUrl=url;
        ui.postDelayed(()->{
            if(!loginMode||inspectedOffer==null||!currentUrl.equals(browser.getUrl()))return;
            browser.evaluateJavascript(
                "(() => (document.querySelector('h1')?.innerText || '').trim().slice(0,250))()",
                value->{
                    if(!loginMode||inspectedOffer==null||!currentUrl.equals(browser.getUrl()))return;
                    try{
                        String heading=String.valueOf(new JSONTokener(value).nextValue());
                        if(!heading.isBlank()&&!ScanEngine.exact(requested,heading)){
                            loginCaption.setText("Другая модель — проверьте товар");
                        }else loginCaption.setText(currentShop.name+" · карточка товара");
                    }catch(Exception ignored){
                        loginCaption.setText("Проверьте модель на странице");
                    }
                });
        },1200);
    }
    private void finishShop(){
        if(!loginMode)return;
        CookieManager.getInstance().flush();
        if(currentShop!=null&&inspectedOffer==null){
            prefs.edit().putBoolean("user_finished_"+currentShop.id,true).apply();
            refreshShop(currentShop);
        }
        loginMode=false;currentShop=null;inspectedOffer=null;
        browser.stopLoading();
        loginBar.setVisibility(View.GONE);
        mainPane.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void confirmClear(){
        new AlertDialog.Builder(this).setTitle("Сбросить входы?")
            .setMessage("Все cookies магазинов внутри MarketRadar будут удалены. Это не затронет другие приложения.")
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Сбросить",(d,w)->{
                CookieManager.getInstance().removeAllCookies(value->{
                    CookieManager.getInstance().flush();
                    SharedPreferences.Editor e=prefs.edit();
                    for(Shop shop:Shop.ALL)e.remove("user_finished_"+shop.id);
                    e.apply();
                    for(Shop shop:Shop.ALL)refreshShop(shop);
                    progress.setText("Входы очищены. Авторизуйтесь заново.");
                });
            }).show();
    }
    private void beginSearch(){
        if(engine.isRunning()){engine.stop();searchButton.setText("Найти предложения");progress.setText("Поиск остановлен.");return;}
        String q=queryInput.getText().toString().trim();
        if(q.length()<3){queryInput.setError("Введите название или модель");return;}
        prefs.edit().putString("last_query",q).apply();
        ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(queryInput.getWindowToken(),0);
        queryInput.clearFocus();
        ArrayList<Shop> chosen=new ArrayList<>();
        for(Shop shop:Shop.ALL)
            if(marketChoices.get(shop.id).isChecked())chosen.add(shop);
        if(chosen.isEmpty()){
            new AlertDialog.Builder(this).setMessage("Выберите хотя бы один маркетплейс")
                .setPositiveButton("ОК",null).show();
            return;
        }
        lastSelectedMarkets=chosen;
        setAccountsVisible(false);
        offers.clear();results.removeAllViews();marketStates.clear();
        bestPrice.setText("—");
        priceSummary.setText("Идёт проверка цен в данных товаров");
        marketProgress.setText("Начинаем проверку "+chosen.size()+" магазинов…");
        count.setText("Проверенных карточек: 0");
        searchButton.setText("Остановить поиск");
        progress.setText("Поиск на выбранных площадках…");
        engine.start(q,fastSearch?3:10,chosen);
    }
    @Override public void status(Shop shop,String state){
        runOnUiThread(()->{
            progress.setText(shop.name+": "+state);
            marketStates.put(shop.id,shop.name+": "+state);
            updateSummary();
        });
    }
    @Override public void offer(ScanEngine.Offer o){
        runOnUiThread(()->{
            boolean exists=false;
            for(ScanEngine.Offer prior:offers)if(prior.url.equals(o.url)){exists=true;break;}
            if(!exists)offers.add(o);
            renderResults();
        });
    }
    private void updateSummary(){
        int priced=0,best=Integer.MAX_VALUE;
        for(ScanEngine.Offer o:offers){
            if(o.price>0){priced++;best=Math.min(best,o.price);}
        }
        bestPrice.setText(priced>0?
            NumberFormat.getNumberInstance(new Locale("ru","RU")).format(best)+" ₽":"—");
        boolean includesListingPrice=false;
        for(ScanEngine.Offer o:offers)
            if(o.price>0&&o.note.startsWith("Цена из выдачи"))includesListingPrice=true;
        priceSummary.setText(priced>0?
            (includesListingPrice?
                "Минимум среди цен в выдаче и данных товара · итог в корзине не проверен":
                "Минимальная цена из данных товара · условия оплаты не подтверждены"):
            "Нет цены, надёжно связанной с товаром");
        count.setText("Карточек по запросу: "+offers.size()+" · найденных цен: "+priced);
        StringBuilder states=new StringBuilder();
        for(Shop market:lastSelectedMarkets){
            if(states.length()>0)states.append("\n");
            int shopMin=Integer.MAX_VALUE;
            for(ScanEngine.Offer o:offers)
                if(o.shop.id.equals(market.id)&&o.price>0)shopMin=Math.min(shopMin,o.price);
            states.append(market.name).append(": ");
            if(shopMin!=Integer.MAX_VALUE) {
                boolean fromListing=false;
                for(ScanEngine.Offer o:offers)
                    if(o.shop.id.equals(market.id)&&o.price==shopMin&&
                       o.note.startsWith("Цена из выдачи"))fromListing=true;
                states.append(NumberFormat.getNumberInstance(new Locale("ru","RU")).format(shopMin))
                    .append(fromListing?" ₽ · из выдачи, проверьте корзину":
                        " ₽ · условия не подтверждены");
            }
            else states.append("цена не подтверждена");
            if(!marketStates.containsKey(market.id))states.append(" · ожидает проверки");
            else if(shopMin==Integer.MAX_VALUE)
                states.append(" · ").append(marketStates.get(market.id).replace(market.name+": ",""));
        }
        marketProgress.setText(states.toString());
    }
    private void renderResults(){
        results.removeAllViews();
        offers.sort(Comparator.comparingInt(o->o.price==0?Integer.MAX_VALUE:o.price));
        updateSummary();
        for(ScanEngine.Offer o:offers){
            LinearLayout card=panel();
            add(card,text(o.shop.name,12,ACCENT,true),-1,22);
            gap(card,2);
            add(card,text(o.title,15,TEXT,true),-1,-2);
            gap(card,12);
            String amount=o.price>0?
                NumberFormat.getNumberInstance(new Locale("ru","RU")).format(o.price)+" ₽":
                "Цена не подтверждена";
            add(card,text(amount,o.price>0?24:18,TEXT,true),-1,-2);
            gap(card,6);
            add(card,text(o.note,12,MUTED,false),-1,-2);
            gap(card,12);
            Button inspect=button("Проверить в магазине",false);
            add(card,inspect,-1,44);
            inspect.setOnClickListener(v->inspectOffer(o));
            add(results,card,-1,-2);
            gap(results,12);
        }
    }
    @Override public void done(){
        runOnUiThread(()->{
            searchButton.setText("Найти предложения");
            progress.setText("Поиск завершён. Охват рынка не гарантирован.");
            for(Shop shop:Shop.ALL)refreshShop(shop);
            if(offers.isEmpty())count.setText("Карточки не подтверждены: проверьте статусы магазинов.");
            updateSummary();
        });
    }
    @Override public void onBackPressed(){
        if(loginMode){
            if(browser.canGoBack())browser.goBack();else finishShop();
        }else if(accountsVisible){
            setAccountsVisible(false);
        }else if(engine!=null&&engine.isRunning()){
            new AlertDialog.Builder(this).setMessage("Остановить поиск?")
                .setNegativeButton("Продолжить",null)
                .setPositiveButton("Остановить",(d,w)->{
                    engine.stop();searchButton.setText("Найти предложения");
                }).show();
        }else super.onBackPressed();
    }
    @Override protected void onDestroy(){
        if(engine!=null)engine.stop();
        browser.destroy();
        super.onDestroy();
    }
}
