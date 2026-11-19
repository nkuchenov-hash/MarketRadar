package com.marketradar.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.widget.Toast;
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
    private LinearLayout summaryPanel, filterPanel, sortRow;
    private Button sortToggleButton;
    private TextView deadlineLabel, marketNotice;
    private Button retryMissingButton;
    private TextView deadlinePreview;
    private boolean sortByDelivery=false;
    private boolean showAllOffers=false;
    private final Map<String,String> marketStates=new HashMap<>();
    private final Map<String,SessionProbe.Verdict> accountEvidence=new HashMap<>();
    private final Map<String,CheckBox> marketChoices=new HashMap<>();
    private List<Shop> lastSelectedMarkets=Shop.ALL;
    private WebView browser;
    private ParallelScanCoordinator engine;
    private EditText queryInput;
    private Button searchButton;
    private EditText deliveryDaysInput, discountPercentInput, cloudUrlInput, cloudAccessInput;
    private CheckBox allowLateDiscount;
    private DeliveryPolicy deliveryPolicy=DeliveryPolicy.standard();
    private TextView progress, count, loginCaption;
    private SharedPreferences prefs;
    private boolean loginMode=false;
    private boolean aiChecking=false;
    private int aiGeneration=0;
    private String activeQuery="";
    private int resumeAttempts=0;
    private boolean progressTickerRunning=false;
    private long searchStartedAt=0L;
    private static final ExecutorService PHOTO_POOL=Executors.newFixedThreadPool(3);
    private static final LruCache<String,Bitmap> PHOTO_CACHE=new LruCache<String,Bitmap>(24*1024*1024){
        @Override protected int sizeOf(String url,Bitmap bitmap){return bitmap.getByteCount();}
    };

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
        sortByDelivery=prefs.getBoolean("sort_by_delivery",false);
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
        restoreSavedSearch();
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
        // Capture existing first-party WB result JSON before site's JS loads.
        MarketplaceNetworkCapture.install(browser);
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

        LinearLayout deliveryRow=linear(LinearLayout.HORIZONTAL);
        deliveryRow.setGravity(Gravity.CENTER_VERTICAL);
        deadlineLabel=text("",14,TEXT,false);
        deliveryRow.addView(deadlineLabel,new LinearLayout.LayoutParams(0,-2,1));
        TextView editDeadline=text("Изменить",13,ACCENT,true);
        editDeadline.setPadding(dp(7),dp(7),0,dp(7));
        editDeadline.setOnClickListener(v->showScreen(SCREEN_SETTINGS));
        add(deliveryRow,editDeadline,-2,-2);
        add(page,deliveryRow,-1,-2);
        updateDeadlineButton();
        gap(page,9);
        progress=text("",13,MUTED,false);
        add(page,progress,-1,-2);

        gap(page,16);
        summaryPanel=panel();
        summaryPanel.setVisibility(View.GONE);
        bestPriceLabel=text("Минимальная найденная цена",14,MUTED,true);
        add(summaryPanel,bestPriceLabel,-1,-2);
        gap(summaryPanel,4);
        bestPrice=text("—",31,TEXT,true);
        add(summaryPanel,bestPrice,-1,47);
        priceSummary=text("",12,MUTED,false);
        priceSummary.setVisibility(View.GONE);
        add(summaryPanel,priceSummary,-1,-2);
        marketProgress=text("",14,TEXT,false);
        add(page,summaryPanel,-1,-2);
        gap(page,12);

        resultsHeading=text("Предложения",19,TEXT,true);
        sortRow=linear(LinearLayout.HORIZONTAL);
        sortRow.setGravity(Gravity.CENTER_VERTICAL);
        sortRow.addView(resultsHeading,new LinearLayout.LayoutParams(0,dp(40),1));
        sortToggleButton=button("",false);
        sortToggleButton.setTextSize(12);
        sortToggleButton.setMinWidth(0);
        sortToggleButton.setMinimumWidth(0);
        add(sortRow,sortToggleButton,113,38);
        sortToggleButton.setOnClickListener(v->setSortByDelivery(!sortByDelivery));
        sortRow.setVisibility(View.GONE);
        add(page,sortRow,-1,-2);
        updateSortButtons();
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

        add(settingsPage,text("Облачный браузер",19,TEXT,true),-1,34);
        LinearLayout cloudPanel=panel();
        add(cloudPanel,text("Поиск выполняет серверный Chromium",14,TEXT,true),-1,26);
        add(cloudPanel,text("Укажи адрес развернутого сервера MarketRadar. После сохранения приложение будет искать товары через сервер, а не скрытый браузер телефона.",12,MUTED,false),-1,-2);
        gap(cloudPanel,10);
        cloudUrlInput=new EditText(this);
        cloudUrlInput.setSingleLine(true);
        cloudUrlInput.setTextSize(14);
        cloudUrlInput.setTextColor(TEXT);
        cloudUrlInput.setHintTextColor(MUTED);
        cloudUrlInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT|
            android.text.InputType.TYPE_TEXT_VARIATION_URI);
        cloudUrlInput.setBackground(bg(SURFACE,13,BORDER));
        cloudUrlInput.setPadding(dp(13),0,dp(13),0);
        cloudUrlInput.setHint("https://адрес-твоего-сервера");
        cloudUrlInput.setText(prefs.getString("cloud_browser_url",""));
        add(cloudPanel,cloudUrlInput,-1,52);
        gap(cloudPanel,8);
        cloudAccessInput=new EditText(this);
        cloudAccessInput.setSingleLine(true);
        cloudAccessInput.setTextSize(14);
        cloudAccessInput.setTextColor(TEXT);
        cloudAccessInput.setHintTextColor(MUTED);
        cloudAccessInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT|
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        cloudAccessInput.setBackground(bg(SURFACE,13,BORDER));
        cloudAccessInput.setPadding(dp(13),0,dp(13),0);
        cloudAccessInput.setHint("Код доступа к серверу");
        cloudAccessInput.setText(prefs.getString("cloud_access_key",""));
        add(cloudPanel,cloudAccessInput,-1,52);
        add(cloudPanel,text("Код выдаётся при первом запуске сервера. Он защищает поиск от чужих запросов. Без адреса сервера работает прежний поиск на телефоне.",12,MUTED,false),-1,-2);
        add(settingsPage,cloudPanel,-1,-2);
        gap(settingsPage,20);

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
        deadlinePreview=text("",13,ACCENT,true);
        gap(filterPanel,5);
        add(filterPanel,deadlinePreview,-1,-2);
        deliveryDaysInput.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){
                updateDeadlinePreview();
            }
            @Override public void afterTextChanged(Editable e){}
        });
        updateDeadlinePreview();
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
        updateDeadlineButton();
        gap(filterPanel,10);
        add(filterPanel,text("Поиск просматривает доступную выдачу целиком; цены и сроки без проверки корзины предварительные.",12,MUTED,false),-1,-2);
        add(settingsPage,filterPanel,-1,-2);
        gap(settingsPage,14);
        Button saveOptions=button("Сохранить настройки",true);
        add(settingsPage,saveOptions,-1,51);
        saveOptions.setOnClickListener(v->{
            String raw=cloudUrlInput.getText().toString().trim();
            if(!raw.isEmpty()&&CloudSearchClient.validBase(raw).isEmpty()){
                cloudUrlInput.setError("Укажи корневой HTTPS-адрес облачного сервера");
                return;
            }
            persistSearchOptions();
            showScreen(SCREEN_SEARCH);
        });
        gap(settingsPage,18);
        add(settingsPage,text("Проверка модели работает локально на устройстве. Итоговые цены и сроки доставки требуют проверки условий корзины магазина.",12,MUTED,false),-1,-2);
        gap(settingsPage,14);
        Button lastReport=button("Отчёт о последнем поиске",false);
        add(settingsPage,lastReport,-1,44);
        lastReport.setOnClickListener(v->showDiagnostics());
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
        // Marketplace diagnostics and retry controls belong in "Магазины",
        // not between the price comparison and the product list.
        marketNotice=text("",13,MUTED,false);
        marketNotice.setVisibility(View.GONE);
        add(marketPage,marketNotice,-1,-2);
        retryMissingButton=button("Повторить поиск по недостающим",false);
        retryMissingButton.setTextSize(12);
        retryMissingButton.setVisibility(View.GONE);
        add(marketPage,retryMissingButton,-1,42);
        retryMissingButton.setOnClickListener(v->retryMissingMarkets());
        gap(marketPage,12);
        Button report=button("Отчёт о последнем поиске",false);
        add(marketPage,report,-1,44);
        report.setOnClickListener(v->showDiagnostics());
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
        if(screen==SCREEN_SEARCH)updateDeadlineButton();
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
        String cloudRaw=cloudUrlInput==null?"":cloudUrlInput.getText().toString().trim();
        String cloudBase=cloudRaw.isEmpty()?"":CloudSearchClient.validBase(cloudRaw);
        prefs.edit()
            .putString("cloud_browser_url",cloudBase)
            .putString("cloud_access_key",cloudAccessInput==null?"":cloudAccessInput.getText().toString().trim())
            .putInt("delivery_days",days)
            .putInt("late_discount_percent",discount)
            .putBoolean("delivery_late_discount",allowSlow)
            .apply();
        updateDeadlinePreview();
        if(engine==null||!engine.isRunning()){
            deliveryPolicy=new DeliveryPolicy(days,allowSlow,discount,
                java.time.LocalDate.now());
            if(!offers.isEmpty())renderResults();
        }
        updateDeadlineButton();
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
    private void startSearchProtection(){
        try{
            android.content.Intent intent=new android.content.Intent(
                this,SearchProtectionService.class);
            startForegroundService(intent);
        }catch(Exception e){
            // Search and persisted session still work if the OS denies a service.
            android.util.Log.w("MarketRadar","Foreground protection unavailable",e);
        }
    }
    private void stopSearchProtection(){
        stopService(new android.content.Intent(this,SearchProtectionService.class));
    }
    private void saveCurrentSearch(boolean searching){
        if(prefs==null||deliveryPolicy==null)return;
        String query=activeQuery;
        if(query.isBlank())return;
        SearchSessionStore.save(prefs,query,deliveryPolicy,offers,searching,
            progress==null?"":progress.getText().toString(),
            lastSelectedMarkets,resumeAttempts);
    }
    private void restoreSavedSearch(){
        SearchSessionStore.Snapshot snapshot=SearchSessionStore.load(prefs);
        if(snapshot==null)return;
        activeQuery=snapshot.query;
        deliveryPolicy=snapshot.policy;
        lastSelectedMarkets=snapshot.markets.isEmpty()?
            new ArrayList<>(Shop.ALL):snapshot.markets;
        resumeAttempts=snapshot.resumeAttempts;
        queryInput.setText(snapshot.query);
        offers.clear();
        offers.addAll(snapshot.offers);
        summaryPanel.setVisibility(View.VISIBLE);
        renderResults();
        searchButton.setText("Найти лучшую цену");
        updateDeadlineButton();
        if(!snapshot.interrupted&&!offers.isEmpty())
            progress.setVisibility(View.GONE);
        // A server-side cloud job survives repeated Android Activity deaths.
        // Recovery must not inherit the two-attempt limit intended for WebViews.
        boolean cloudJobPending=!prefs.getString("cloud_pending_job_id","").isBlank();
        if(snapshot.interrupted&&(cloudJobPending||
                SearchSessionStore.canAutoResume(snapshot))){
            // Android may reclaim the Activity/WebViews when the user switches apps.
            // Restart only the scan workers, not the UI or the saved offers.
            resumeAttempts++;
            searchButton.setText("Остановить");
            progress.setText(offers.isEmpty()?
                "Автоматически возобновляем поиск…":
                "Предыдущие предложения сохранены · продолжаем поиск…");
            // Mark the recovery attempt BEFORE launching browsers, preventing
            // crash/restart loops on devices with extremely low memory.
            saveCurrentSearch(true);
            ui.post(()->{
                if(isFinishing()||isDestroyed()||engine==null||engine.isRunning())return;
                try{
                    startSearchProtection();
                    searchStartedAt=android.os.SystemClock.elapsedRealtime();
                    engine.start(activeQuery,Integer.MAX_VALUE,lastSelectedMarkets,
                        deliveryPolicy);
                    progress.setText(engine.progressSummary());
                    startProgressTicker();
                }catch(Exception error){
                    stopSearchProtection();
                    progress.setText("Не удалось продолжить поиск автоматически");
                    searchButton.setText("Найти лучшую цену");
                    saveCurrentSearch(false);
                }
            });
        }else if(snapshot.interrupted){
            progress.setText(offers.isEmpty()?
                "Не удалось восстановить поиск. Нажмите «Найти лучшую цену».":
                "Предыдущие предложения сохранены · поиск можно повторить");
            searchButton.setText("Найти лучшую цену");
        }else{
            progress.setText(snapshot.message.isBlank()?
                "Восстановлены результаты предыдущего поиска":snapshot.message);
        }
    }
    private void beginSearch(){
        if(engine.isRunning()||aiChecking){
            aiGeneration++;
            aiChecking=false;
            engine.stop();
            stopSearchProtection();
            // Stopping an unfinished scan must not discard the offers already found.
            pendingOffers.clear();
            searchButton.setText("Найти лучшую цену");
            progress.setText("Поиск остановлен · результаты сохранены");
            summaryPanel.setVisibility(View.VISIBLE);
            renderResults();
            saveCurrentSearch(false);
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
        updateDeadlineButton();
        bestPriceLabel.setText("Самая низкая найденная цена");
        ++aiGeneration;
        resumeAttempts=0;
        lastSelectedMarkets=chosen;
        setAccountsVisible(false);
        offers.clear();pendingOffers.clear();results.removeAllViews();marketStates.clear();
        showAllOffers=false;
        summaryPanel.setVisibility(View.VISIBLE);
        resultsHeading.setVisibility(View.GONE);
        sortRow.setVisibility(View.GONE);
        bestPrice.setText("—");
        marketProgress.setText("Ищем предложения…");
        count.setVisibility(View.GONE);
        searchButton.setText("Остановить");
        marketNotice.setVisibility(View.GONE);
        retryMissingButton.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        progress.setText("Поиск…");
        searchStartedAt=android.os.SystemClock.elapsedRealtime();
        saveCurrentSearch(true);
        startSearchProtection();
        engine.start(q,Integer.MAX_VALUE,chosen,deliveryPolicy);
        progress.setVisibility(View.VISIBLE);
        progress.setText(engine.progressSummary());
        startProgressTicker();
    }
    @Override public void status(Shop shop,String state){
        runOnUiThread(()->{
            marketStates.put(shop.id,state);
            // A late WebView status callback must not make a completed
            // search look as though Ozon (or another store) is still running.
            if(engine!=null&&engine.isRunning()){
                progress.setVisibility(View.VISIBLE);
                progress.setText(engine.progressSummary());
            }
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
                progress.setText(engine.progressSummary());
                // Android may kill a background WebView process at any point.
                saveCurrentSearch(true);
            }
        });
    }
    private DeliveryPolicy pendingPolicy(){
        if(engine!=null&&engine.isRunning())return deliveryPolicy;
        return new DeliveryPolicy(
            boundedField(deliveryDaysInput,prefs.getInt("delivery_days",3),0,90),
            allowLateDiscount!=null&&allowLateDiscount.isChecked(),
            boundedField(discountPercentInput,prefs.getInt("late_discount_percent",15),1,90),
            java.time.LocalDate.now());
    }
    private void updateDeadlineButton(){
        if(deadlineLabel==null||deliveryDaysInput==null)return;
        deadlineLabel.setText(OfferPresentation.deadline(pendingPolicy()));
    }
    private void updateDeadlinePreview(){
        if(deadlinePreview==null||deliveryDaysInput==null)return;
        DeliveryPolicy policy=new DeliveryPolicy(
            boundedField(deliveryDaysInput,3,0,90),false,15,java.time.LocalDate.now());
        deadlinePreview.setText("Крайняя дата: "+
            OfferPresentation.deadline(policy).replace("Доставка до ",""));
    }
    private void setSortByDelivery(boolean delivery){
        if(sortByDelivery==delivery)return;
        sortByDelivery=delivery;
        prefs.edit().putBoolean("sort_by_delivery",delivery).apply();
        updateSortButtons();
        renderResults(); // Sorting changes displayed cards; it never starts a new search.
    }
    private void updateSortButtons(){
        if(sortToggleButton==null)return;
        sortToggleButton.setText(sortByDelivery?"Доставка ↑  ▾":"Цена ↑  ▾");
        sortToggleButton.setContentDescription(sortByDelivery?
            "Сортировка по ближайшей доставке. Нажмите для сортировки по цене":
            "Сортировка по минимальной цене. Нажмите для сортировки по доставке");
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
        // Respect the configured exception for a substantially cheaper
        // offer arriving after the deadline; unknown delivery is never eligible.
        return deliveryPolicy.eligible(o.price,o.deliveryHint,timelyBest);
    }
    private void updateSummary(){
        int timelyBest=bestTimelyPrice();
        ScanEngine.Offer bestEligible=null;
        for(ScanEngine.Offer o:offers){
            if(o.price<=0)continue;
            if(qualifies(o,timelyBest)){
                if(bestEligible==null||o.price<bestEligible.price)bestEligible=o;
            }
        }
        boolean scanning=engine!=null&&engine.isRunning();
        boolean incomplete=engine!=null&&engine.coverageIncomplete();
        bestPriceLabel.setText(scanning?
            "Лучшая найденная цена · поиск идёт":
            incomplete?"Лучшая найденная цена · поиск неполный":
                "Лучшая цена с доставкой в срок");
        if(bestEligible==null){
            boolean active=engine!=null&&engine.isRunning();
            bestPrice.setText(offers.isEmpty()?
                (active?"Ищем…":"Предложений нет"):"Пока не найдено");
            bestPrice.setTextSize(20);
            priceSummary.setText(offers.isEmpty()?
                (active?"Проверяем выбранные площадки":
                    "Поиск окончен без подтверждённых товаров"):
                "Подходящие по сроку предложения ещё не найдены");
            priceSummary.setVisibility(View.VISIBLE);
        }else{
            bestPrice.setText(rubles(bestEligible.price));
            bestPrice.setTextSize(31);
            String delivery=OfferPresentation.deliveryDate(
                deliveryPolicy,bestEligible.deliveryHint);
            String wallet=bestEligible.priceCondition.isBlank()?"":
                " · "+bestEligible.priceCondition;
            String exception=deliveryPolicy.timely(bestEligible.deliveryHint)?"":
                " · выгоднее по разрешённому исключению";
            priceSummary.setText(bestEligible.shop.name+" · "+delivery+wallet+exception);
            priceSummary.setVisibility(View.VISIBLE);
        }
        if(sortRow!=null)
            sortRow.setVisibility(offers.isEmpty()?View.GONE:View.VISIBLE);
        count.setVisibility(View.GONE);
    }
    // Kept off the main search screen. Explicit per-shop evidence helps
    // diagnose access blocks and early timeouts without leaking cookies.
    private String currentDiagnosticReport(){
        if(engine==null||!engine.isRunning())
            return prefs.getString("last_search_diagnostics",
                "Завершённых поисков пока не было");
        StringBuilder report=new StringBuilder("MarketRadar · текущий поиск\n");
        report.append("Время: ").append(
            java.text.DateFormat.getDateTimeInstance().format(new java.util.Date())).append('\n');
        report.append("Запрос: ").append(activeQuery).append('\n');
        report.append(OfferPresentation.deadline(deliveryPolicy)).append('\n');
        report.append("Продолжительность: ")
            .append(Math.max(0,(android.os.SystemClock.elapsedRealtime()-searchStartedAt)/1000))
            .append(" с\n");
        report.append("Подтверждено предложений: ").append(offers.size()).append('\n');
        report.append("Передано на локальную проверку: ").append(pendingOffers.size()).append('\n');
        report.append(engine.detailedProgress());
        report.append("\nПоследние сообщения по площадкам:\n");
        for(Shop shop:lastSelectedMarkets){
            report.append(shop.name).append(": ")
                .append(marketStates.getOrDefault(shop.id,"нет сообщений"))
                .append('\n');
        }
        report.append("\nАудит найденных дешёвых карточек и причин исключения:\n");
        for(Shop shop:lastSelectedMarkets){
            report.append(shop.name).append(":\n  ")
                .append(engine.marketBreakdown(shop.id)).append('\n');
        }
        report.append("\nЛогины, cookies и ключи в отчёт не включаются.");
        return report.toString();
    }
    private void showDiagnostics(){
        final String full=currentDiagnosticReport();
        String preview=full.length()>1600?full.substring(0,1600)+
            "\n…\nПолный отчёт доступен через «Копировать» или «Поделиться».":full;
        new AlertDialog.Builder(this)
            .setTitle(engine!=null&&engine.isRunning()?
                "Диагностика текущего поиска":"Последняя диагностика")
            .setMessage(preview)
            .setNeutralButton("Копировать",(dialog,which)->{
                ClipboardManager clipboard=(ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText(
                    "MarketRadar — диагностика",full));
                Toast.makeText(this,"Диагностика скопирована",Toast.LENGTH_SHORT).show();
            })
            .setPositiveButton("Поделиться",(dialog,which)->{
                Intent share=new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_SUBJECT,"Диагностика MarketRadar");
                share.putExtra(Intent.EXTRA_TEXT,full);
                startActivity(Intent.createChooser(share,"Отправить диагностику"));
            })
            .setNegativeButton("Закрыть",null)
            .show();
    }
    private void startProgressTicker(){
        if(progressTickerRunning)return;
        progressTickerRunning=true;
        ui.postDelayed(new Runnable(){
            @Override public void run(){
                if(isDestroyed()||isFinishing()||
                    engine==null||!engine.isRunning()){
                    progressTickerRunning=false;
                    return;
                }
                progress.setVisibility(View.VISIBLE);
                progress.setText(engine.progressSummary());
                ui.postDelayed(this,4000);
            }
        },4000);
    }
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
            report.append("\n  Последний статус: ")
                .append(marketStates.containsKey(shop.id)?
                    marketStates.get(shop.id):"магазин не проверялся");
            int candidates=0,confirmed=0;
            for(ScanEngine.Offer o:provisional)
                if(o.shop.id.equals(shop.id))candidates++;
            for(ScanEngine.Offer o:offers)
                if(o.shop.id.equals(shop.id))confirmed++;
            report.append("\n  Предложений передано на проверку: ").append(candidates);
            report.append("; подтверждено локальной моделью: ").append(confirmed);
            report.append("\n  Этапы поиска: ").append(engine.marketBreakdown(shop.id));
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
    private String compactTitle(String original){
        String clean=(original==null?"":original).replaceAll(
            "(?iu)\\s+(?:купить\\s+за|в\\s+интернет-магазине|заказать\\s+за)\\b.*$","");
        clean=clean.replaceAll("(?iu)\\s+\\d[\\d\\s]*\\s*₽.*$","").trim();
        return clean.isEmpty()?"Товар":clean;
    }
    private Bitmap downloadPhoto(String source){
        try{
            URL url=new URL(source);
            if(!"https".equalsIgnoreCase(url.getProtocol()))return null;
            for(int redirect=0;redirect<3;redirect++){
                HttpURLConnection connection=(HttpURLConnection)url.openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(6000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept","image/avif,image/webp,image/jpeg,image/png");
                try{
                    int code=connection.getResponseCode();
                    if(code>=300&&code<400){
                        String location=connection.getHeaderField("Location");
                        if(location==null)return null;
                        url=new URL(url,location);
                        if(!"https".equalsIgnoreCase(url.getProtocol()))return null;
                        continue;
                    }
                    if(code!=200||!connection.getContentType().toLowerCase(Locale.ROOT).startsWith("image/"))
                        return null;
                    try(InputStream stream=connection.getInputStream();
                        ByteArrayOutputStream data=new ByteArrayOutputStream()){
                        byte[] chunk=new byte[8192];int n;
                        while((n=stream.read(chunk))!=-1){
                            if(data.size()+n>3*1024*1024)return null;
                            data.write(chunk,0,n);
                        }
                        byte[] bytes=data.toByteArray();
                        BitmapFactory.Options options=new BitmapFactory.Options();
                        options.inJustDecodeBounds=true;
                        BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                        options.inSampleSize=1;
                        while(options.outWidth/options.inSampleSize>360||
                              options.outHeight/options.inSampleSize>360)
                            options.inSampleSize*=2;
                        options.inJustDecodeBounds=false;
                        return BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                    }
                }finally{connection.disconnect();}
            }
        }catch(Exception ignored){}
        return null;
    }
    private View photo(ScanEngine.Offer offer){
        if(offer.imageUrl.isBlank()){
            TextView placeholder=text("Нет фото",11,MUTED,false);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setBackground(bg(BG,12,BORDER));
            return placeholder;
        }
        ImageView image=new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding(dp(4),dp(4),dp(4),dp(4));
        image.setBackground(bg(BG,12,BORDER));
        String source=offer.imageUrl;
        image.setTag(source);
        Bitmap cached=PHOTO_CACHE.get(source);
        if(cached!=null)image.setImageBitmap(cached);
        else PHOTO_POOL.execute(()->{
            Bitmap bitmap=downloadPhoto(source);
            if(bitmap!=null)PHOTO_CACHE.put(source,bitmap);
            runOnUiThread(()->{
                if(!isDestroyed()&&source.equals(image.getTag())&&bitmap!=null)
                    image.setImageBitmap(bitmap);
            });
        });
        return image;
    }
    private void renderResults(){
        final int timelyBest=bestTimelyPrice();
        offers.sort(OfferPresentation.priorityComparator(
            deliveryPolicy,sortByDelivery,timelyBest));
        results.removeAllViews();
        updateSummary();
        // The compact list is a decision aid, not a catalog dump.
        // Keep one cheapest on-time offer per marketplace; other products
        // are available only when the user chooses to expand the list.
        ArrayList<ScanEngine.Offer> visible=OfferPresentation.visibleOffers(
            offers,deliveryPolicy,showAllOffers,sortByDelivery);
        int distinctCount=OfferPresentation.visibleOffers(
            offers,deliveryPolicy,true,sortByDelivery).size();
        int eligibleCount=0;
        int alternatives=0;
        for(ScanEngine.Offer offer:visible){
            if(deliveryPolicy.timely(offer.deliveryHint))eligibleCount++;
            else alternatives++;
        }
        resultsHeading.setText(showAllOffers?
            "Выгодные варианты · "+visible.size():
            "Лучшие по площадкам · "+eligibleCount);
        resultsHeading.setVisibility(View.VISIBLE);
        boolean otherHeadingAdded=false;
        for(ScanEngine.Offer o:visible){
            boolean secondary=OfferPresentation.priority(deliveryPolicy,o,timelyBest)!=0;
            if(secondary&&!otherHeadingAdded&&eligibleCount>0){
                otherHeadingAdded=true;
                gap(results,11);
                add(results,text("Позже, но дешевле по условиям · "+alternatives,
                    16,MUTED,true),-1,-2);
                gap(results,12);
            }
            LinearLayout card=panel();
            if(secondary)card.setPadding(dp(13),dp(12),dp(13),dp(12));
            LinearLayout row=linear(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            View picture=photo(o);
            int pictureWidth=secondary?76:104;
            row.addView(picture,new LinearLayout.LayoutParams(
                dp(pictureWidth),dp(secondary?84:111)));
            LinearLayout details=linear(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(0,-2,1);
            layout.leftMargin=dp(secondary?10:13);
            row.addView(details,layout);
            add(details,text(o.shop.name,12,ACCENT,true),-1,-2);
            gap(details,secondary?2:3);
            add(details,text(o.price>0?rubles(o.price):"Цена уточняется",
                secondary?18:23,TEXT,true),-1,-2);
            gap(details,secondary?2:4);
            TextView title=text(compactTitle(o.title),secondary?12:13,TEXT,false);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            add(details,title,-1,-2);
            gap(details,secondary?4:7);
            boolean late=OfferPresentation.afterDeadline(deliveryPolicy,o.deliveryHint);
            int dateColor=late?(themePalette.dark?0xFFFFC087:0xFFAA501C):
                secondary?MUTED:TEXT;
            add(details,text(OfferPresentation.deliveryDate(
                deliveryPolicy,o.deliveryHint),secondary?13:15,dateColor,!secondary),-1,-2);
            gap(details,2);
            add(details,text(OfferPresentation.deliveryNote(
                deliveryPolicy,o.deliveryHint),secondary?11:12,
                late?dateColor:MUTED,false),-1,-2);
            if(!o.priceCondition.isBlank()){
                gap(details,secondary?2:4);
                add(details,text(o.priceCondition,11,ACCENT,false),-1,-2);
            }
            add(card,row,-1,-2);
            card.setClickable(true);
            card.setOnClickListener(v->inspectOffer(o));
            add(results,card,-1,-2);
            gap(results,secondary?6:9);
        }
        int concealed=Math.max(0,distinctCount-OfferPresentation.visibleOffers(
            offers,deliveryPolicy,false,false).size());
        if(concealed>0){
            Button more=button(showAllOffers?
                "Скрыть дополнительные предложения":
                "Показать выгодные варианты · "+concealed,false);
            more.setTextSize(14);
            add(results,more,-1,46);
            more.setOnClickListener(v->{
                showAllOffers=!showAllOffers;
                renderResults();
            });
        }
        sortRow.setVisibility(offers.isEmpty()?View.GONE:View.VISIBLE);
    }
    private ArrayList<Shop> unresolvedMarkets(){
        ArrayList<Shop> missing=new ArrayList<>();
        for(Shop shop:lastSelectedMarkets){
            boolean found=false;
            for(ScanEngine.Offer offer:offers)
                if(offer.shop.id.equals(shop.id)){found=true;break;}
            if(!found)missing.add(shop);
        }
        return missing;
    }
    private void retryMissingMarkets(){
        if(engine==null||engine.isRunning()||activeQuery.isBlank())return;
        ArrayList<Shop> missing=unresolvedMarkets();
        if(missing.isEmpty())return;
        marketNotice.setVisibility(View.GONE);
        retryMissingButton.setVisibility(View.GONE);
        marketStates.keySet().removeIf(key->{
            for(Shop shop:missing)if(shop.id.equals(key))return true;
            return false;
        });
        pendingOffers.clear();
        aiGeneration++;
        resumeAttempts=0;
        progress.setText("Ищем предложения на недостающих площадках…");
        progress.setVisibility(View.VISIBLE);
        showScreen(SCREEN_SEARCH);
        searchButton.setText("Остановить");
        // Keep the previously confirmed WB/Yandex/Ozon cards. Only repeat
        // sites for which the current search yielded no verified products.
        saveCurrentSearch(true);
        startSearchProtection();
        searchStartedAt=android.os.SystemClock.elapsedRealtime();
        engine.start(activeQuery,Integer.MAX_VALUE,missing,deliveryPolicy);
        progress.setText(engine.progressSummary());
        startProgressTicker();
    }
    private void updateMarketplaceNotice(){
        if(marketNotice==null)return;
        ArrayList<Shop> missing=unresolvedMarkets();
        if(missing.isEmpty()){
            marketNotice.setVisibility(View.GONE);
            retryMissingButton.setVisibility(View.GONE);
        }else{
            ArrayList<String> reasons=new ArrayList<>();
            for(Shop shop:missing)
                reasons.add(shop.name+": "+
                    StoreResultMessage.summarize(marketStates.get(shop.id)));
            marketNotice.setText(String.join(" · ",reasons));
            marketNotice.setVisibility(View.VISIBLE);
            retryMissingButton.setVisibility(View.VISIBLE);
        }
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
            updateMarketplaceNotice();
            if(offers.isEmpty()){
                summaryPanel.setVisibility(View.VISIBLE);
                updateSummary();
                progress.setText((engine.coverageIncomplete()?
                    "Поиск завершился без результатов · причина ниже":
                    "Не найдены подтверждённые предложения")+
                    "\n"+engine.progressSummary());
            }else{
                progress.setText((engine.coverageIncomplete()?
                    "Поиск неполный · окончательный минимум не подтверждён":
                    "Поиск завершён · найденные предложения ниже")+
                    "\n"+engine.progressSummary());
            }
            saveCurrentSearch(false);
            // Never hide the incomplete-search warning under the product cards:
            // a confirmed offer is NOT proof that every store was checked.
            // Keep each shop's final status visible even on a completed search.
            progress.setVisibility(View.VISIBLE);
            stopSearchProtection();
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
                    engine.stop();stopSearchProtection();
                    searchButton.setText("Найти предложения");
                    progress.setText("Поиск остановлен · результаты сохранены");
                    saveCurrentSearch(false);
                }).show();
        }else super.onBackPressed();
    }
    @Override protected void onPause(){
        saveCurrentSearch(engine!=null&&engine.isRunning());
        super.onPause();
    }
    @Override protected void onStop(){
        saveCurrentSearch(engine!=null&&engine.isRunning());
        super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle saved){
        saveCurrentSearch(engine!=null&&engine.isRunning());
        saved.putInt("screen",currentScreen);
        if(queryInput!=null)
            saved.putString("query_draft",queryInput.getText().toString());
        super.onSaveInstanceState(saved);
    }
    @Override protected void onDestroy(){
        saveCurrentSearch(engine!=null&&engine.isRunning());
        // onPause/onStop keep the running search protected. If the Activity
        // actually dies, its WebViews are being destroyed, so avoid leaving
        // a foreground service with no worker behind.
        stopSearchProtection();
        aiGeneration++;
        if(engine!=null)engine.destroy();
        browser.destroy();
        super.onDestroy();
    }
}
