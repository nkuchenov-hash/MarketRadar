package com.marketradar.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
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
import android.widget.Button;
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
    private static final int BG=0xFF0B1525, SURFACE=0xFF15263B, BORDER=0xFF2B4159;
    private static final int TEXT=0xFFF4F7FB, MUTED=0xFF9FB3C9, ACCENT=0xFFDFF16A;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ArrayList<ScanEngine.Offer> offers=new ArrayList<>();
    private final Map<String,TextView> statuses=new HashMap<>();
    private FrameLayout root;
    private LinearLayout mainPane, loginBar, results;
    private WebView browser;
    private ScanEngine engine;
    private EditText queryInput;
    private Button searchButton;
    private TextView progress, count, loginCaption;
    private SharedPreferences prefs;
    private boolean loginMode=false;
    private Shop currentShop;

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
        b.setTextColor(primary?BG:TEXT);
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
        prefs=getSharedPreferences("market_sessions",MODE_PRIVATE);
        buildBrowser();
        buildInterface();
        engine=new ScanEngine(browser,ui,this);
        for(Shop shop:Shop.ALL)refreshShop(shop);
    }
    private void buildBrowser(){
        root=new FrameLayout(this);root.setBackgroundColor(BG);
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
        top.setPadding(dp(20),dp(12),dp(20),dp(12));
        TextView brand=text("M  MarketRadar",21,TEXT,true);
        top.addView(brand,new LinearLayout.LayoutParams(0,dp(45),1));
        TextView alpha=text("ANDROID · 0.1",10,ACCENT,true);
        add(top,alpha,-2,42);add(mainPane,top,-1,68);
        View line=new View(this);line.setBackgroundColor(BORDER);add(mainPane,line,-1,1);

        ScrollView scrolling=new ScrollView(this);
        scrolling.setFillViewport(true);
        scrolling.setClipToPadding(false);
        add(mainPane,scrolling,-1,-1);
        LinearLayout page=linear(LinearLayout.VERTICAL);
        page.setPadding(dp(18),dp(22),dp(18),dp(60));
        scrolling.addView(page);
        add(page,text("ПОИСК ПО МАРКЕТПЛЕЙСАМ",11,ACCENT,true),-1,22);
        gap(page,7);
        add(page,text("Один товар.\nТри магазина.",35,TEXT,true),-1,90);
        gap(page,8);
        add(page,text("Ищем точную модель через ваши собственные сессии магазинов. Без API-ключа и расширений.",14,MUTED,false),-1,-2);
        gap(page,23);

        queryInput=new EditText(this);
        queryInput.setSingleLine(true);
        queryInput.setTextSize(16);queryInput.setTextColor(TEXT);queryInput.setHintTextColor(MUTED);
        queryInput.setBackground(bg(SURFACE,13,BORDER));
        queryInput.setPadding(dp(15),0,dp(15),0);
        queryInput.setHint("Точная модель товара");
        queryInput.setText(prefs.getString("last_query","Яндекс Станция Мини 3 Про"));
        add(page,queryInput,-1,58);
        gap(page,10);
        searchButton=button("Найти предложения",true);
        add(page,searchButton,-1,54);
        searchButton.setOnClickListener(v->beginSearch());
        gap(page,10);
        progress=text("Поиск пока не запущен.",13,MUTED,false);
        add(page,progress,-1,-2);
        gap(page,28);

        add(page,text("МОИ МАРКЕТПЛЕЙСЫ",12,ACCENT,true),-1,24);
        gap(page,5);
        add(page,text("Войдите на официальном сайте каждого магазина. Данные входа остаются в Android WebView.",13,MUTED,false),-1,-2);
        gap(page,13);
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
            add(page,card,-1,-2);
            gap(page,11);
        }
        Button clear=button("Сбросить все входы на этом телефоне",false);
        add(page,clear,-1,46);
        clear.setOnClickListener(v->confirmClear());
        gap(page,24);

        add(page,text("РЕЗУЛЬТАТЫ ПОИСКА",12,ACCENT,true),-1,22);
        gap(page,6);
        count=text("Результаты появятся здесь",13,MUTED,false);
        add(page,count,-1,-2);
        gap(page,10);
        results=linear(LinearLayout.VERTICAL);add(page,results,-1,-2);
        gap(page,18);
        add(page,text("Тестовая версия. Цены, прочитанные со страницы, могут быть старыми или зависеть от карты. Итоговую сумму проверяйте в корзине. При сворачивании телефона поиск может остановиться.",12,MUTED,false),-1,-2);

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
        if(engine!=null&&engine.isRunning())engine.stop();
        searchButton.setEnabled(true);
        currentShop=shop;loginMode=true;
        browser.stopLoading();
        mainPane.setVisibility(View.GONE);
        loginBar.setVisibility(View.VISIBLE);
        loginCaption.setText(shop.name+" · официальный сайт");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if(!shop.allowed(url))url=shop.home;
        browser.loadUrl(url);
    }
    private void finishShop(){
        if(!loginMode)return;
        CookieManager.getInstance().flush();
        if(currentShop!=null){
            prefs.edit().putBoolean("user_finished_"+currentShop.id,true).apply();
            refreshShop(currentShop);
        }
        loginMode=false;currentShop=null;
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
        offers.clear();results.removeAllViews();
        count.setText("Предложения: 0");
        searchButton.setText("Остановить поиск");
        progress.setText("Поиск на трёх площадках…");
        engine.start(q);
    }
    @Override public void status(Shop shop,String state){
        runOnUiThread(()->{
            progress.setText(shop.name+": "+state);
            TextView label=statuses.get(shop.id);
            if(label!=null&&engine.isRunning())label.setText(state);
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
    private void renderResults(){
        results.removeAllViews();
        offers.sort(Comparator.comparingInt(o->o.price==0?Integer.MAX_VALUE:o.price));
        count.setText("Найдено карточек точной модели: "+offers.size());
        for(ScanEngine.Offer o:offers){
            LinearLayout card=panel();
            add(card,text(o.shop.name,12,o.shop.accent,true),-1,22);
            add(card,text(o.title,15,TEXT,true),-1,-2);
            gap(card,9);
            String amount=o.price>0?
                NumberFormat.getNumberInstance(new Locale("ru","RU")).format(o.price)+" ₽":
                "Цена не прочитана";
            add(card,text(amount,24,TEXT,true),-1,-2);
            gap(card,6);
            add(card,text(o.note,12,MUTED,false),-1,-2);
            gap(card,8);
            Button inspect=button("Открыть карточку магазина",false);
            add(card,inspect,-1,40);
            inspect.setOnClickListener(v->showShop(o.shop,o.url));
            add(results,card,-1,-2);
            gap(results,10);
        }
    }
    @Override public void done(){
        runOnUiThread(()->{
            searchButton.setText("Найти предложения");
            progress.setText("Поиск завершён. Охват рынка не гарантирован.");
            for(Shop shop:Shop.ALL)refreshShop(shop);
            if(offers.isEmpty())count.setText("Нет прочитанных карточек. Возможны блокировки магазинов.");
        });
    }
    @Override public void onBackPressed(){
        if(loginMode){
            if(browser.canGoBack())browser.goBack();else finishShop();
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
