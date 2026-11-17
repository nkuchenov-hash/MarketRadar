package com.marketradar.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.content.res.Configuration;
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
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONTokener;
import org.json.JSONObject;
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
    private int BG, SURFACE, BORDER, TEXT, MUTED, ACCENT;
    private ThemePalette themePalette;
    private static final int SCREEN_SEARCH=0, SCREEN_SHOPS=1, SCREEN_SETTINGS=2;
    private int currentScreen=SCREEN_SEARCH;
    private final Handler ui=new Handler(Looper.getMainLooper());
    // Never mix provisional scraped prices with AI-confirmed offers.
    private final ArrayList<ScanEngine.Offer> offers=new ArrayList<>();
    private final ArrayList<ScanEngine.Offer> pendingOffers=new ArrayList<>();
    private final Map<String,TextView> statuses=new HashMap<>();
    private FrameLayout root;
    private LinearLayout mainPane, loginBar, results;
    private ScrollView searchScroll, accountsScroll, settingsScroll;
    private Button navButton, settingsNavButton;
    private boolean accountsVisible=false;
    private TextView bestPrice, priceSummary, marketProgress, resultsHeading, bestPriceLabel;
    private LinearLayout summaryPanel, filterPanel;
    private final Map<String,String> marketStates=new HashMap<>();
    private final Map<String,SessionProbe.Verdict> accountEvidence=new HashMap<>();
    private final Map<String,CheckBox> marketChoices=new HashMap<>();
    private List<Shop> lastSelectedMarkets=Shop.ALL;
    private WebView browser;
    private ParallelScanCoordinator engine;
    private EditText queryInput;
    private Button searchButton;
    private EditText deliveryDaysInput, discountPercentInput;
    private CheckBox allowLateDiscount;
    private DeliveryPolicy deliveryPolicy=DeliveryPolicy.standard();
    private TextView progress, count, loginCaption;
    private SharedPreferences prefs;
    private boolean loginMode=false;
    private boolean aiChecking=false;
    private int aiGeneration=0;
    private String activeQuery="";

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
        b.setTextColor(primary?(themePalette.dark?0xFF091A33:Color.WHITE):ACCENT);
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
        prefs=getSharedPreferences("market_sessions",MODE_PRIVATE);
        boolean deviceDark=(getResources().getConfiguration().uiMode&
            Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        themePalette=ThemePalette.resolve(
            prefs.getString("theme_mode",ThemePalette.SYSTEM),deviceDark);
        setTheme(themePalette.dark?
            android.R.style.Theme_Material_NoActionBar:
            android.R.style.Theme_Material_Light_NoActionBar);
        BG=themePalette.background;
        SURFACE=themePalette.surface;
        BORDER=themePalette.border;
        TEXT=themePalette.foreground;
        MUTED=themePalette.muted;
        ACCENT=themePalette.accent;
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(themePalette.dark?0:
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        buildBrowser();
        buildInterface();
        if(state!=null){
            int restored=state.getInt("screen",SCREEN_SEARCH);
            if(restored<SCREEN_SEARCH||restored>SCREEN_SETTINGS)restored=SCREEN_SEARCH;
            showScreen(restored);
            String draft=state.getString("query_draft");
            if(draft!=null)queryInput.setText(draft);
        }
        engine=new ParallelScanCoordinator(this,root,browser,ui,this);
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
                if(!loginMode&&engine!=null)engine.onPageFinished(view,url);
                else if(loginMode&&inspectedOffer!=null)inspectPageDestination(url);
                else if(loginMode&&currentShop!=null)checkCurrentAccountPage(url);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error){
                if(request.isForMainFrame()&&!loginMode&&engine!=null)
                    engine.onPageError(view,request.getUrl().toString());
            }
            @Override public void onReceivedHttpError(WebView view,
                    WebResourceRequest request,WebResourceResponse response){
                if(request.isForMainFrame()&&!loginMode&&engine!=null)
                    engine.onHttpError(view,request.getUrl().toString(),
                        response.getStatusCode());
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
        top.setPadding(dp(12),dp(9),dp(12),dp(9));
        TextView brand=text("MarketRadar",19,TEXT,true);
        top.addView(brand,new LinearLayout.LayoutParams(0,dp(45),1));
        navButton=button("Магазины",false);
        navButton.setTextSize(12);
        add(top,navButton,92,42);
        add(top,new View(this),6,1);
        settingsNavButton=button("Настройки",false);
        settingsNavButton.setTextSize(12);
        add(top,settingsNavButton,98,42);
        navButton.setOnClickListener(v->showScreen(
            currentScreen==SCREEN_SHOPS?SCREEN_SEARCH:SCREEN_SHOPS));
        settingsNavButton.setOnClickListener(v->showScreen(
            currentScreen==SCREEN_SETTINGS?SCREEN_SEARCH:SCREEN_SETTINGS));
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
        settingsScroll=new ScrollView(this);
        settingsScroll.setFillViewport(true);
        settingsScroll.setClipToPadding(false);
        mainPane.addView(settingsScroll,new LinearLayout.LayoutParams(-1,0,1));
        settingsScroll.setVisibility(View.GONE);
        LinearLayout settingsPage=linear(LinearLayout.VERTICAL);
        settingsPage.setPadding(dp(18),dp(16),dp(18),dp(40));
        settingsScroll.addView(settingsPage);
        LinearLayout page=linear(LinearLayout.VERTICAL);
        page.setPadding(dp(18),dp(16),dp(18),dp(36));
        searchScroll.addView(page);
        LinearLayout marketPage=linear(LinearLayout.VERTICAL);
        marketPage.setPadding(dp(18),dp(24),dp(18),dp(50));
        accountsScroll.addView(marketPage);
        add(page,text("Сравнить цены",27,TEXT,true),-1,48);
        gap(page,12);

        queryInput=new EditText(this);
        queryInput.setSingleLine(true);
        queryInput.setTextSize(16);queryInput.setTextColor(TEXT);queryInput.setHintTextColor(MUTED);
        queryInput.setBackground(bg(SURFACE,13,BORDER));
        queryInput.setPadding(dp(15),0,dp(15),0);
        queryInput.setHint("Название или точная модель");
        queryInput.setText(prefs.getString("last_query","Яндекс Станция Мини 3 Про"));
        add(page,queryInput,-1,58);
        gap(page,12);

        searchButton=button("Найти лучшую цену",true);
        add(page,searchButton,-1,54);
        searchButton.setOnClickListener(v->beginSearch());
        gap(page,9);

        Button settingsButton=button("Настроить условия поиска",false);
        add(page,settingsButton,-1,43);
        settingsButton.setOnClickListener(v->showScreen(SCREEN_SETTINGS));
        gap(page,9);
        progress=text("",13,MUTED,false);
        add(page,progress,-1,-2);

        gap(page,16);
        summaryPanel=panel();
        summaryPanel.setVisibility(View.GONE);
        bestPriceLabel=text("Лучшая цена с подходящей доставкой",14,MUTED,true);
        add(summaryPanel,bestPriceLabel,-1,24);
        gap(summaryPanel,4);
        bestPrice=text("—",31,TEXT,true);
        add(summaryPanel,bestPrice,-1,47);
        priceSummary=text("",12,MUTED,false);
        priceSummary.setVisibility(View.GONE);
        gap(summaryPanel,8);
        marketProgress=text("",14,TEXT,false);
        add(summaryPanel,marketProgress,-1,-2);
        add(page,summaryPanel,-1,-2);
        gap(page,12);

        resultsHeading=text("Предложения",19,TEXT,true);
        resultsHeading.setVisibility(View.GONE);
        add(page,resultsHeading,-1,28);
        count=text("",12,MUTED,false);
        count.setVisibility(View.GONE);
        add(page,count,-1,-2);
        gap(page,8);
        results=linear(LinearLayout.VERTICAL);add(page,results,-1,-2);
        gap(page,12);

        add(settingsPage,text("Настройки",27,TEXT,true),-1,48);
        add(settingsPage,text("Настрой условия сравнения цен и внешний вид приложения.",13,MUTED,false),-1,-2);
        gap(settingsPage,22);

        add(settingsPage,text("Оформление",19,TEXT,true),-1,34);
        LinearLayout appearance=panel();
        add(appearance,text("Тема приложения",15,TEXT,true),-1,27);
        add(appearance,text("Системная автоматически следует настройкам телефона.",12,MUTED,false),-1,-2);
        gap(appearance,12);
        LinearLayout themeRow=linear(LinearLayout.HORIZONTAL);
        String selectedMode=ThemePalette.normalize(
            prefs.getString("theme_mode",ThemePalette.SYSTEM));
        String[] themeKeys={ThemePalette.SYSTEM,ThemePalette.LIGHT,ThemePalette.DARK};
        String[] themeNames={"Системная","Светлая","Тёмная"};
        for(int i=0;i<themeKeys.length;i++){
            final String key=themeKeys[i];
            Button themeButton=button(themeNames[i],selectedMode.equals(key));
            themeButton.setTextSize(12);
            LinearLayout.LayoutParams option=new LinearLayout.LayoutParams(0,dp(46),1);
            if(i>0)option.leftMargin=dp(5);
            themeRow.addView(themeButton,option);
            themeButton.setOnClickListener(v->applyTheme(key));
        }
        add(appearance,themeRow,-1,-2);
        add(settingsPage,appearance,-1,-2);
        gap(settingsPage,22);

        add(settingsPage,text("Поиск и доставка",19,TEXT,true),-1,34);
        filterPanel=panel();
        add(filterPanel,text("Выбрать магазины",14,TEXT,true),-1,24);
        gap(filterPanel,5);
        LinearLayout marketsRow=linear(LinearLayout.HORIZONTAL);
        for(Shop shop:Shop.ALL){
            CheckBox cb=new CheckBox(this);
            cb.setText(shop.id.equals("ozon")?"Ozon":shop.id.equals("wb")?"WB":"Маркет");
            cb.setTextSize(12);cb.setTextColor(TEXT);
            cb.setButtonTintList(ColorStateList.valueOf(ACCENT));
            cb.setChecked(prefs.getBoolean("scan_"+shop.id,true));
            cb.setOnCheckedChangeListener((b,checked)->
                prefs.edit().putBoolean("scan_"+shop.id,checked).apply());
            marketChoices.put(shop.id,cb);
            marketsRow.addView(cb,new LinearLayout.LayoutParams(0,dp(42),1));
        }
        add(filterPanel,marketsRow,-1,-2);
        gap(filterPanel,12);
        add(filterPanel,text("Доставка максимум за сколько дней?",13,TEXT,true),-1,24);
        deliveryDaysInput=new EditText(this);
        deliveryDaysInput.setSingleLine(true);
        deliveryDaysInput.setTextSize(16);
        deliveryDaysInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        deliveryDaysInput.setTextColor(TEXT);
        deliveryDaysInput.setBackground(bg(SURFACE,13,BORDER));
        deliveryDaysInput.setPadding(dp(13),0,dp(13),0);
        deliveryDaysInput.setText(Integer.toString(prefs.getInt("delivery_days",3)));
        add(filterPanel,deliveryDaysInput,-1,45);
        gap(filterPanel,8);
        allowLateDiscount=new CheckBox(this);
        allowLateDiscount.setText("Допускать долгую доставку со скидкой");
        allowLateDiscount.setTextSize(13);
        allowLateDiscount.setTextColor(TEXT);
        allowLateDiscount.setButtonTintList(ColorStateList.valueOf(ACCENT));
        allowLateDiscount.setChecked(prefs.getBoolean("delivery_late_discount",false));
        add(filterPanel,allowLateDiscount,-1,45);
        add(filterPanel,text("Минимальная скидка относительно лучшей быстрой доставки, %",12,MUTED,false),-1,-2);
        discountPercentInput=new EditText(this);
        discountPercentInput.setSingleLine(true);
        discountPercentInput.setTextSize(16);
        discountPercentInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        discountPercentInput.setTextColor(TEXT);
        discountPercentInput.setBackground(bg(SURFACE,13,BORDER));
        discountPercentInput.setPadding(dp(13),0,dp(13),0);
        discountPercentInput.setText(Integer.toString(prefs.getInt("late_discount_percent",15)));
        discountPercentInput.setEnabled(allowLateDiscount.isChecked());
        allowLateDiscount.setOnCheckedChangeListener((v,checked)->
            discountPercentInput.setEnabled(checked));
        add(filterPanel,discountPercentInput,-1,45);
        gap(filterPanel,10);
        add(filterPanel,text("Поиск просматривает доступную выдачу целиком; цены и сроки без проверки корзины предварительные.",12,MUTED,false),-1,-2);
        add(settingsPage,filterPanel,-1,-2);
        gap(settingsPage,14);
        Button saveOptions=button("Сохранить настройки",true);
        add(settingsPage,saveOptions,-1,51);
        saveOptions.setOnClickListener(v->{
            persistSearchOptions();
            showScreen(SCREEN_SEARCH);
        });
        gap(settingsPage,18);
        add(settingsPage,text("Проверка модели работает локально на устройстве. Итоговые цены и сроки доставки требуют проверки условий корзины магазина.",12,MUTED,false),-1,-2);
        gap(settingsPage,14);
        Button lastReport=button("Отчёт о последнем поиске",false);
        add(settingsPage,lastReport,-1,44);
        lastReport.setOnClickListener(v->new AlertDialog.Builder(this)
            .setTitle("Отчёт о поиске")
            .setMessage(prefs.getString("last_search_diagnostics","Поисков пока не было"))
            .setPositiveButton("Закрыть",null).show());
        gap(settingsPage,20);

        add(marketPage,text("МОИ МАРКЕТПЛЕЙСЫ",11,ACCENT,true),-1,22);
        add(marketPage,text("Подключения и сессии магазинов",24,TEXT,true),-1,-2);
        gap(marketPage,8);
        add(marketPage,text("Вход выполняется на сайтах магазинов. MarketRadar проверяет признаки авторизации на странице; наличие cookies само по себе не подтверждает вход.",13,MUTED,false),-1,-2);
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
        Button report=button("Отчёт о последнем поиске",false);
        add(marketPage,report,-1,44);
        report.setOnClickListener(v->new AlertDialog.Builder(this)
            .setTitle("Что произошло при поиске")
            .setMessage(prefs.getString("last_search_diagnostics",
                "Поисков пока не было"))
            .setPositiveButton("Закрыть",null).show());
        gap(marketPage,15);
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
    private void showScreen(int screen){
        currentScreen=screen;
        accountsVisible=screen==SCREEN_SHOPS;
        searchScroll.setVisibility(screen==SCREEN_SEARCH?View.VISIBLE:View.GONE);
        accountsScroll.setVisibility(screen==SCREEN_SHOPS?View.VISIBLE:View.GONE);
        settingsScroll.setVisibility(screen==SCREEN_SETTINGS?View.VISIBLE:View.GONE);
        navButton.setText(screen==SCREEN_SHOPS?"Поиск":"Магазины");
        settingsNavButton.setText(screen==SCREEN_SETTINGS?"Поиск":"Настройки");
    }
    private void setAccountsVisible(boolean show){
        showScreen(show?SCREEN_SHOPS:SCREEN_SEARCH);
    }
    private void persistSearchOptions(){
        int days=boundedField(deliveryDaysInput,3,0,90);
        int discount=boundedField(discountPercentInput,15,1,90);
        boolean allowSlow=allowLateDiscount.isChecked();
        deliveryDaysInput.setText(Integer.toString(days));
        discountPercentInput.setText(Integer.toString(discount));
        prefs.edit()
            .putInt("delivery_days",days)
            .putInt("late_discount_percent",discount)
            .putBoolean("delivery_late_discount",allowSlow)
            .apply();
    }
    private void applyTheme(String nextMode){
        String chosen=ThemePalette.normalize(nextMode);
        if(chosen.equals(ThemePalette.normalize(
            prefs.getString("theme_mode",ThemePalette.SYSTEM))))return;
        Runnable commit=()->{
            persistSearchOptions();
            if(queryInput!=null)
                prefs.edit().putString("last_query",
                    queryInput.getText().toString()).apply();
            prefs.edit().putString("theme_mode",chosen).apply();
            // Recreating native UI preserves the app's WebView cookie jar,
            // saved marketplace settings and the latest user query.
            recreate();
        };
        if(engine!=null&&engine.isRunning()){
            new AlertDialog.Builder(this)
                .setTitle("Сменить оформление?")
                .setMessage("Для смены темы текущий поиск будет остановлен.")
                .setNegativeButton("Продолжить поиск",null)
                .setPositiveButton("Сменить", (d,w)->commit.run()).show();
        }else commit.run();
    }
    private void refreshShop(Shop shop){
        TextView label=statuses.get(shop.id);
        if(label==null)return;
        SessionProbe.Verdict observed=accountEvidence.get(shop.id);
        if(observed!=null){
            if(observed.state==SessionProbe.State.SIGNED_IN){
                label.setText("Вход обнаружен на последней странице");
                return;
            }
            if(observed.state==SessionProbe.State.SIGN_IN_REQUIRED){
                label.setText("Необходимо войти заново");
                return;
            }
        }
        String cookie=CookieManager.getInstance().getCookie(shop.home);
        if(cookie!=null&&!cookie.isEmpty()){
            label.setText("Есть cookies · вход не подтверждён");
        }else{
            label.setText("Вход не подтверждён · откройте магазин");
        }
    }
    private void recordAccountEvidence(Shop shop,SessionProbe.Verdict verdict){
        if(shop==null||verdict==null)return;
        accountEvidence.put(shop.id,verdict);
        refreshShop(shop);
    }
    @Override public void session(Shop shop,SessionProbe.Verdict evidence){
        runOnUiThread(()->{
            recordAccountEvidence(shop,evidence);
            if(evidence.state==SessionProbe.State.SIGN_IN_REQUIRED){
                marketStates.put(shop.id,"Требуется повторно войти в аккаунт");
                updateSummary();
            }
        });
    }
    private void checkCurrentAccountPage(String loadedUrl){
        final Shop shop=currentShop;
        if(shop==null)return;
        final String pageUrl=loadedUrl;
        ui.postDelayed(()->{
            if(!loginMode||currentShop!=shop||!pageUrl.equals(browser.getUrl()))return;
            if(shop.isLoginUrl(pageUrl)){
                recordAccountEvidence(shop,new SessionProbe.Verdict(
                    SessionProbe.State.SIGN_IN_REQUIRED,"Открыта авторизация"));
                return;
            }
            browser.evaluateJavascript(SessionProbe.SCRIPT,value->{
                if(!loginMode||currentShop!=shop||!pageUrl.equals(browser.getUrl()))return;
                try{
                    JSONObject signals=new JSONObject(
                        String.valueOf(new JSONTokener(value).nextValue()));
                    recordAccountEvidence(shop,SessionProbe.evaluate(shop,signals));
                }catch(Exception ignored){
                    recordAccountEvidence(shop,new SessionProbe.Verdict(
                        SessionProbe.State.UNKNOWN,"Не удалось проверить вход"));
                }
            });
        },550);
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
        final Shop leavingShop=currentShop;
        final String leavingUrl=browser.getUrl();
        // Read actual DOM account evidence BEFORE leaving the login screen.
        // Cookies are opaque and must never be treated as login proof.
        if(leavingShop!=null&&leavingUrl!=null){
            if(leavingShop.isLoginUrl(leavingUrl)){
                recordAccountEvidence(leavingShop,new SessionProbe.Verdict(
                    SessionProbe.State.SIGN_IN_REQUIRED,
                    "Вход ещё не завершён"));
            }else if(leavingShop.allowed(leavingUrl)){
                browser.evaluateJavascript(SessionProbe.SCRIPT,value->{
                    if(!leavingUrl.equals(browser.getUrl()))return;
                    try{
                        JSONObject signals=new JSONObject(
                            String.valueOf(new JSONTokener(value).nextValue()));
                        recordAccountEvidence(leavingShop,
                            SessionProbe.evaluate(leavingShop,signals));
                    }catch(Exception ignored){
                        recordAccountEvidence(leavingShop,new SessionProbe.Verdict(
                            SessionProbe.State.UNKNOWN,"Вход не удалось подтвердить"));
                    }
                });
            }
        }
        CookieManager.getInstance().flush();
        loginMode=false;currentShop=null;inspectedOffer=null;
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
                    accountEvidence.clear();
                    for(Shop shop:Shop.ALL)refreshShop(shop);
                    progress.setText("Входы очищены. Авторизуйтесь заново.");
                });
            }).show();
    }
    private int boundedField(EditText field,int fallback,int min,int max){
        try{
            return Math.max(min,Math.min(max,
                Integer.parseInt(field.getText().toString().trim())));
        }catch(Exception ignored){return fallback;}
    }
    private void beginSearch(){
        if(engine.isRunning()||aiChecking){
            aiGeneration++;
            aiChecking=false;
            engine.stop();
            offers.clear();
            pendingOffers.clear();
            results.removeAllViews();
            summaryPanel.setVisibility(View.GONE);
            searchButton.setText("Найти лучшую цену");
            progress.setText("Поиск остановлен");
            return;
        }
        String q=queryInput.getText().toString().trim();
        if(q.length()<3){queryInput.setError("Введите название или модель");return;}
        activeQuery=q;
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
        int days=boundedField(deliveryDaysInput,3,0,90);
        int discount=boundedField(discountPercentInput,15,1,90);
        boolean allowSlow=allowLateDiscount.isChecked();
        deliveryDaysInput.setText(Integer.toString(days));
        discountPercentInput.setText(Integer.toString(discount));
        prefs.edit().putInt("delivery_days",days)
            .putInt("late_discount_percent",discount)
            .putBoolean("delivery_late_discount",allowSlow).apply();
        deliveryPolicy=new DeliveryPolicy(days,allowSlow,discount,
            java.time.LocalDate.now());
        bestPriceLabel.setText("Минимум найденных · доставка до "+days+" дн.");
        ++aiGeneration;
        lastSelectedMarkets=chosen;
        setAccountsVisible(false);
        offers.clear();pendingOffers.clear();results.removeAllViews();marketStates.clear();
        summaryPanel.setVisibility(View.VISIBLE);
        resultsHeading.setVisibility(View.GONE);
        bestPrice.setText("—");
        marketProgress.setText("Ищем предложения…");
        count.setVisibility(View.GONE);
        searchButton.setText("Остановить");
        progress.setText("Поиск…");
        engine.start(q,Integer.MAX_VALUE,chosen,deliveryPolicy);
    }
    @Override public void status(Shop shop,String state){
        runOnUiThread(()->{
            progress.setText("Поиск: "+shop.name+"…");
            marketStates.put(shop.id,state);
            updateSummary();
        });
    }
    @Override public void assess(ScanEngine.Offer proposal,
            java.util.function.Consumer<Boolean> verdict){
        // The local model checks each candidate AS IT ARRIVES, not only after
        // all shops have exhausted their listings. The global price ceiling
        // changes immediately when the model confirms a better product.
        final int active=aiGeneration;
        if(isFinishing()||isDestroyed()){verdict.accept(false);return;}
        pendingOffers.add(proposal);
        LocalModelVerifier.verify(this,activeQuery,
            java.util.Collections.singletonList(proposal),ui,(approved,error)->{
                if(active!=aiGeneration||isFinishing()||isDestroyed()){
                    verdict.accept(false);
                    return;
                }
                if(error!=null){
                    marketStates.put(proposal.shop.id,"Локальная проверка недоступна");
                    verdict.accept(null);
                    return;
                }
                verdict.accept(!approved.isEmpty());
            });
    }
    @Override public void offer(ScanEngine.Offer o){
        runOnUiThread(()->{
            boolean exists=false;
            for(ScanEngine.Offer prior:offers)
                if(prior.url.equals(o.url)&&prior.deliveryHint.equals(o.deliveryHint)
                    &&prior.priceCondition.equals(o.priceCondition)
                    &&prior.price==o.price){exists=true;break;}
            if(!exists){
                offers.add(o);
                renderResults();
                progress.setText("Подбираем предложения дешевле найденной цены…");
            }
        });
    }
    private String rubles(int amount){
        return NumberFormat.getNumberInstance(new Locale("ru","RU")).format(amount)+" ₽";
    }
    private int bestTimelyPrice(){
        int best=Integer.MAX_VALUE;
        for(ScanEngine.Offer o:offers){
            if(o.price>0&&deliveryPolicy.timely(o.deliveryHint))
                best=Math.min(best,o.price);
        }
        return best==Integer.MAX_VALUE?0:best;
    }
    private boolean qualifies(ScanEngine.Offer o,int timelyBest){
        return deliveryPolicy.eligible(o.price,o.deliveryHint,timelyBest);
    }
    private void updateSummary(){
        int timelyBest=bestTimelyPrice();
        int minimum=Integer.MAX_VALUE;
        for(ScanEngine.Offer o:offers)
            if(qualifies(o,timelyBest))minimum=Math.min(minimum,o.price);
        bestPrice.setText(minimum==Integer.MAX_VALUE?
            "Нет цены с подтверждённой доставкой":rubles(minimum));
        bestPrice.setTextSize(minimum==Integer.MAX_VALUE?16:31);
        StringBuilder states=new StringBuilder();
        for(Shop shop:lastSelectedMarkets){
            int marketMin=Integer.MAX_VALUE;
            ScanEngine.Offer cheapestExcluded=null;
            for(ScanEngine.Offer o:offers)if(o.shop.id.equals(shop.id)){
                if(qualifies(o,timelyBest))marketMin=Math.min(marketMin,o.price);
                else if(o.price>0 && (cheapestExcluded==null ||
                        o.price<cheapestExcluded.price))cheapestExcluded=o;
            }
            if(states.length()>0)states.append("\n");
            states.append(shop.name).append("   ");
            if(marketMin<Integer.MAX_VALUE){
                states.append(rubles(marketMin));
            }else if(cheapestExcluded!=null){
                states.append(rubles(cheapestExcluded.price));
                if(!cheapestExcluded.priceCondition.isBlank())
                    states.append(" ").append(cheapestExcluded.priceCondition);
                states.append(" · ");
                if(deliveryPolicy.days(cheapestExcluded.deliveryHint)<0)
                    states.append("срок доставки неизвестен");
                else
                    states.append("доставка ").append(cheapestExcluded.deliveryHint)
                        .append(" не подходит");
            }else{
                String detail=marketStates.get(shop.id);
                if(detail==null)states.append("не проверен");
                else if(detail.contains("Нет прогресса")||
                        detail.contains("не ответила")||
                        detail.contains("Ошибка загрузки"))
                    states.append("поиск прерван: нет ответа");
                else if(detail.contains("ограничил доступ")||
                        detail.contains("Переадресация"))
                    states.append("доступ ограничен");
                else if(detail.contains("прокрутить полностью")||
                        detail.contains("Нет прогресса"))
                    states.append("выдача не загрузилась полностью");
                else if(detail.contains("отложено дорогих"))
                    states.append("нет подтверждённой цены · см. отчёт");
                else states.append("нет подтверждённых предложений");
            }
        }
        marketProgress.setText(states.toString());
        resultsHeading.setVisibility(offers.isEmpty()?View.GONE:View.VISIBLE);
        count.setVisibility(View.GONE);
    }
    // Kept off the main search screen. Explicit per-shop evidence helps
    // diagnose access blocks and early timeouts without leaking cookies.
    private void saveSearchReport(List<ScanEngine.Offer> provisional,String reason){
        StringBuilder report=new StringBuilder();
        report.append(java.text.DateFormat.getDateTimeInstance().format(new java.util.Date()));
        report.append("\nЗапрос: ").append(prefs.getString("last_query",""));
        report.append("\n").append(reason);
        report.append("\nВыдача проверена полностью: ")
              .append(engine.coverageIncomplete()?"нет":"только доступные страницы").append("\n");
        report.append("Доставка до ").append(deliveryPolicy.maxDays).append(" дней");
        if(deliveryPolicy.allowSlowDiscount)
            report.append("; исключение при скидке ").append(
                deliveryPolicy.slowDiscountPercent).append("%");
        report.append("\n");
        for(Shop shop:lastSelectedMarkets){
            report.append("\n").append(shop.name).append(": ");
            SessionProbe.Verdict evidence=accountEvidence.get(shop.id);
            report.append("\n  Авторизация: ").append(
                evidence==null?"не проверена":evidence.state.toString()+
                " ("+evidence.reason+")");
            report.append(marketStates.containsKey(shop.id)?
                marketStates.get(shop.id):"магазин не проверялся");
            int candidates=0,confirmed=0;
            for(ScanEngine.Offer o:provisional)
                if(o.shop.id.equals(shop.id))candidates++;
            for(ScanEngine.Offer o:offers)
                if(o.shop.id.equals(shop.id))confirmed++;
            report.append("\n  Найденных кандидатов: ").append(candidates);
            report.append("; подтверждено локальной моделью: ").append(confirmed);
            for(ScanEngine.Offer o:offers){
                if(o.shop.id.equals(shop.id)){
                    report.append("\n  ").append(o.price>0?rubles(o.price):"Цена неизвестна");
                    report.append(" — ").append(o.note);
                }
            }
            report.append("\n");
        }
        prefs.edit().putString("last_search_diagnostics",report.toString()).apply();
    }
    private void renderResults(){
        final int timelyBest=bestTimelyPrice();
        offers.sort(Comparator
            .comparingInt((ScanEngine.Offer o)->qualifies(o,timelyBest)?0:1)
            .thenComparingInt(o->o.price==0?Integer.MAX_VALUE:o.price));
        results.removeAllViews();
        updateSummary();
        int displayed=0;
        int excludedLate=0;
        for(ScanEngine.Offer o:offers){
            if(deliveryPolicy.days(o.deliveryHint)>deliveryPolicy.maxDays &&
               !qualifies(o,timelyBest)){
                excludedLate++;
                continue;
            }
            displayed++;
            LinearLayout card=panel();
            LinearLayout heading=linear(LinearLayout.HORIZONTAL);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            TextView shopName=text(o.shop.name,13,ACCENT,true);
            heading.addView(shopName,new LinearLayout.LayoutParams(0,dp(32),1));
            TextView price=text(o.price>0?rubles(o.price):"Цена неизвестна",21,TEXT,true);
            add(heading,price,-2,37);
            add(card,heading,-1,-2);
            gap(card,4);
            TextView title=text(o.title,14,TEXT,false);
            title.setMaxLines(3);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            add(card,title,-1,-2);
            if(!o.priceCondition.isBlank()){
                gap(card,5);
                add(card,text(o.priceCondition+" · требуется соответствующий способ оплаты",
                    12,ACCENT,false),-1,-2);
            }
            gap(card,5);
            String deliveryState=deliveryPolicy.days(o.deliveryHint)<0?
                "Дата доставки неизвестна":
                ("Доставка из выдачи: "+o.deliveryHint);
            if(!qualifies(o,timelyBest))
                deliveryState+=" · не включено в минимум";
            else deliveryState+=" · уточнить в магазине";
            add(card,text(deliveryState,12,MUTED,false),-1,-2);
            gap(card,5);
            String origin=o.note.startsWith("Цена из выдачи")?
                "Цена из поиска · без проверки корзины":
                (o.note.startsWith("Цена из данных товара")?
                    "Цена со страницы товара · без проверки корзины":
                    "Источник цены не подтверждён");
            add(card,text(origin,11,MUTED,false),-1,-2);
            gap(card,10);
            Button inspect=button("Открыть товар",false);
            add(card,inspect,-1,42);
            inspect.setOnClickListener(v->inspectOffer(o));
            add(results,card,-1,-2);
            gap(results,9);
        }
        if(excludedLate>0){
            add(results,text("Исключено из-за долгой доставки: "+
                excludedLate,12,MUTED,false),-1,-2);
        }
        resultsHeading.setVisibility(displayed==0?View.GONE:View.VISIBLE);
    }
    @Override public void done(){
        runOnUiThread(()->{
            for(Shop shop:Shop.ALL)refreshShop(shop);
            searchButton.setText("Найти лучшую цену");
            renderResults();
            String reason=engine.coverageIncomplete()?
                "Часть выдачи недоступна. Окончательный минимум не доказан.":
                "Обход доступной выдачи завершён; дорогие карточки отсечены.";
            saveSearchReport(new ArrayList<>(pendingOffers),reason);
            if(offers.isEmpty()){
                summaryPanel.setVisibility(View.VISIBLE);
                updateSummary();
                progress.setText(engine.coverageIncomplete()?
                    "Поиск неполный: часть выдачи недоступна":
                    "Не найдены подтверждённые предложения");
            }else{
                progress.setText(engine.coverageIncomplete()?
                    "Показана лучшая проверенная цена среди доступных результатов":
                    "Предварительные цены найдены · корзина и персональные скидки не проверены");
            }
        });
    }
    @Override public void onBackPressed(){
        if(loginMode){
            if(browser.canGoBack())browser.goBack();else finishShop();
        }else if(currentScreen==SCREEN_SETTINGS||accountsVisible){
            showScreen(SCREEN_SEARCH);
        }else if(engine!=null&&engine.isRunning()){
            new AlertDialog.Builder(this).setMessage("Остановить поиск?")
                .setNegativeButton("Продолжить",null)
                .setPositiveButton("Остановить",(d,w)->{
                    engine.stop();searchButton.setText("Найти предложения");
                }).show();
        }else super.onBackPressed();
    }
    @Override protected void onSaveInstanceState(Bundle saved){
        saved.putInt("screen",currentScreen);
        if(queryInput!=null)
            saved.putString("query_draft",queryInput.getText().toString());
        super.onSaveInstanceState(saved);
    }
    @Override protected void onDestroy(){
        aiGeneration++;
        if(engine!=null)engine.destroy();
        browser.destroy();
        super.onDestroy();
    }
}
