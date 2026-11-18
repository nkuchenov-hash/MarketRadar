package com.marketradar.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class MarketplaceRecoveryTest {
    @Test public void yandexCardLinksAreUsableSearchCandidates() throws Exception {
        String query="Яндекс Станция Мини 3 Про";
        JSONArray market=new JSONArray().put(new JSONObject()
            .put("url","https://market.yandex.ru/card/yandeks-stantsiya-mini-3-pro-black/4620658532")
            .put("title","Яндекс Станция Мини 3 Про Black")
            .put("context","Умная колонка Яндекс Станция Мини 3 Про 12 420 ₽ Завтра")
            .put("imageAlt","Яндекс Станция Мини 3 Про"));
        java.util.ArrayList<ScanEngine.Candidate> candidates=
            ScanEngine.selectCandidates(market,Shop.byId("ym"),query,10);
        assertEquals(1,candidates.size());
        assertEquals(12420,candidates.get(0).listingPrice);
        assertEquals("Завтра",candidates.get(0).deliveryHint);
    }
    @Test public void ozonProductLinksAreUsableSearchCandidates() throws Exception {
        String query="Яндекс Станция Мини 3 Про";
        JSONArray ozon=new JSONArray().put(new JSONObject()
            .put("url","https://www.ozon.ru/product/umnaya-kolonka-yandeks-stantsiya-mini-3-pro-2399150118/")
            .put("title","Яндекс Станция Мини 3 Про, серый")
            .put("context","Яндекс Станция Мини 3 Про, серый 12 166 ₽")
            .put("imageAlt","Умная колонка Яндекс Станция Мини 3 Про"));
        java.util.ArrayList<ScanEngine.Candidate> candidates=
            ScanEngine.selectCandidates(ozon,Shop.byId("ozon"),query,10);
        assertEquals(1,candidates.size());
        assertEquals(12166,candidates.get(0).listingPrice);
    }
    @Test public void missingStoreReasonNeverPretendsToHaveCheckedItsPrices() {
        assertEquals("ответ сервера HTTP 403",
            StoreResultMessage.summarize("Ozon: Магазин ограничил доступ (HTTP 403)"));
        assertEquals("требуется проверка сайта",
            StoreResultMessage.summarize("Магазин показал страницу проверки доступа"));
        assertEquals("сайт перенаправил поиск",
            StoreResultMessage.summarize("Магазин перенаправил поиск на другой адрес"));
        assertEquals("выдача не загрузилась",
            StoreResultMessage.summarize("Нет прогресса загрузки или прокрутки"));
        assertEquals("товары не подтверждены",
            StoreResultMessage.summarize("Нет подходящих карточек"));
        assertEquals("предложения не получены",StoreResultMessage.summarize(null));
    }
    @Test public void incidentalCaptchaMentionDoesNotMeanYandexBlocked(){
        assertFalse(ScanEngine.explicitAccessChallenge(
            "Яндекс Маркет — каталог",
            "Яндекс Станция Мини 3 Про, купить. captcha is a widget.",
            10));
        assertFalse(ScanEngine.explicitAccessChallenge(
            "Яндекс Маркет — каталог",
            "Обычная выдача с отметкой captcha в коде",0));
        assertTrue(ScanEngine.explicitAccessChallenge(
            "Проверка доступа","Вы не робот. Пройдите проверку",0));
        assertTrue(ScanEngine.explicitAccessChallenge(
            "403","Access denied",0));
    }

    @Test public void marketplaceUrlsHaveOrdinarySearchFallbacks() {
        String query="Яндекс Станция Мини 3 Про";
        for(String market:new String[]{"ozon","ym"}){
            Shop shop=Shop.byId(market);
            assertNotNull(shop);
            assertNotEquals(shop.searchUrl(query),
                shop.searchBase+java.net.URLEncoder.encode(query,
                    java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
