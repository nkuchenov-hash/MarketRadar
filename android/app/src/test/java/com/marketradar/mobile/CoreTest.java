package com.marketradar.mobile;

import org.json.JSONArray;
import org.junit.Test;
import static org.junit.Assert.*;

public class CoreTest {
    @Test public void exactMiniThreeProRejectsOtherModels() {
        String q="Яндекс Станция Мини 3 Про";
        assertTrue(ScanEngine.exact(q, "Яндекс Станция Мини 3 Про, синий"));
        assertTrue(ScanEngine.exact(q, "Yandex Mini 3 Pro, чёрный"));
        assertFalse(ScanEngine.exact(q, "Яндекс Станция Мини 3"));
        assertFalse(ScanEngine.exact(q, "Яндекс Станция Мини 3 Лайт"));
        assertFalse(ScanEngine.exact(q, "Чехол для Яндекс Станции Мини 3 Про"));
    }
    @Test public void genericNumericModelMustMatch() {
        assertTrue(ScanEngine.exact("Samsung Galaxy A56 256 ГБ","Смартфон Samsung Galaxy A56, 256 ГБ"));
        assertFalse(ScanEngine.exact("Samsung Galaxy A56 256 ГБ","Смартфон Samsung Galaxy A55, 256 ГБ"));
        assertFalse(ScanEngine.exact("Samsung Galaxy A56 256 ГБ","Смартфон Samsung Galaxy A56, 128 ГБ"));
    }
    @Test public void priceIsProvisionalAndRejectsMonthlyPayments() {
        JSONArray snippets=new JSONArray();
        snippets.put("1 130 ₽ / мес");
        snippets.put("11 542 ₽");
        snippets.put("12 185 ₽");
        assertEquals(11542,ScanEngine.readPrice(snippets));
        assertEquals(0,ScanEngine.readPrice(new JSONArray().put("Нет цены")));
    }
    @Test public void cardPriceIsUsedOnlyWhenNotCashbackOrCredit() {
        String card="Яндекс Станция Мини 3 Про\n11 542 ₽\n16 999 ₽\n" +
            "133 ₽ кешбэк\n1 130 ₽ / мес\nДоставка завтра";
        assertEquals(11542,ScanEngine.readCardPrice(card));
        assertEquals(0,ScanEngine.readCardPrice("133 ₽ кешбэк\n1 130 ₽ / мес"));
        assertEquals(0,ScanEngine.readCardPrice("Одна страница с товарами\n".repeat(45)));
    }
    @Test public void specificationsCannotOverridePayablePrice() throws Exception {
        String q="Яндекс Станция Мини 3 Про";
        JSONArray prices=new JSONArray()
            .put(new org.json.JSONObject().put("amount",390).put("currency","RUB")
                .put("productName",q).put("source","schema.org/PriceSpecification"))
            .put(new org.json.JSONObject().put("amount",11900).put("currency","RUB")
                .put("productName",q).put("source","schema.org/Offer"));
        assertEquals(11900,ScanEngine.readStructuredPrice(prices,q,q));
    }
    @Test public void onlyHttpsMarketplaceProducts() {
        Shop ozon=Shop.byId("ozon");
        assertTrue(ozon.productLink("https://www.ozon.ru/product/yndx-00059-123/"));
        assertFalse(ozon.productLink("https://www.ozon.ru/search/?text=mini"));
        assertFalse(ozon.productLink("https://ozon.ru.evil.example/product/123"));
        assertFalse(ozon.productLink("javascript:alert(1)"));
    }
    @Test public void unrelatedLowPriceIsNotTreatedAsProductPrice() throws Exception {
        String name="Яндекс Станция Мини 3 Про";
        org.json.JSONObject unrelated=new org.json.JSONObject()
            .put("amount",390).put("currency","RUB")
            .put("productName","Чехол для Яндекс Станции Мини 3 Про")
            .put("source","schema.org/Offer");
        org.json.JSONObject actual=new org.json.JSONObject()
            .put("amount",11542).put("currency","RUB")
            .put("productName",name)
            .put("source","schema.org/Offer");
        JSONArray data=new JSONArray().put(unrelated).put(actual);
        assertEquals(11542,ScanEngine.readStructuredPrice(data,name,name));
        assertEquals(0,ScanEngine.readStructuredPrice(new JSONArray().put(unrelated),name,name));
    }
    @Test public void aPriceMustBelongToExactNamedProductAndRubles() throws Exception {
        String query="Яндекс Станция Мини 3 Про";
        JSONArray data=new JSONArray()
            .put(new org.json.JSONObject().put("amount",390).put("currency","RUB")
                .put("productName","Яндекс Станция Мини 3").put("source","schema.org/Offer"))
            .put(new org.json.JSONObject().put("amount",9999).put("currency","USD")
                .put("productName",query).put("source","schema.org/Offer"));
        assertEquals(0,ScanEngine.readStructuredPrice(data,query,query));
        assertFalse(ScanEngine.exact(query+" синий",query+" чёрный"));
    }
    @Test public void distinctProductVariantsMustNotBeMixed() {
        assertTrue(ScanEngine.exact("iPhone 16 Pro","Apple iPhone 16 Pro 256 ГБ"));
        assertFalse(ScanEngine.exact("iPhone 16 Pro","Apple iPhone 16 Pro Max 256 ГБ"));
        assertFalse(ScanEngine.exact("Samsung Galaxy S25","Samsung Galaxy S25 Ultra"));
        assertFalse(ScanEngine.exact("Xiaomi 14","Xiaomi 14 Lite"));
        assertTrue(ScanEngine.exact("iPhone 16 Pro Max","Apple iPhone 16 Pro Max"));
    }
    @Test public void queryColorIsNotReplacedByDifferentVariant() {
        assertTrue(ScanEngine.exact("Яндекс Станция Мини 3 Про синий",
            "Яндекс Станция Мини 3 Про, синий (YNDX-00059BLU)"));
        assertFalse(ScanEngine.exact("Яндекс Станция Мини 3 Про синий",
            "Яндекс Станция Мини 3 Про, серый (YNDX-00059GRY)"));
        assertFalse(ScanEngine.exact("Samsung Galaxy A56 синий",
            "Samsung Galaxy A56 чёрный"));
        assertTrue(ScanEngine.exact("Samsung Galaxy A56 синий",
            "Samsung Galaxy A56 Blue"));
        assertFalse(ScanEngine.exact("Samsung Galaxy A56 синий",
            "Samsung Galaxy A56 Blue, Black"));
    }
    @Test public void usedItemsAreNotPassedOffAsNew() {
        assertFalse(ScanEngine.exact("Samsung Galaxy A56 256 ГБ",
            "Samsung Galaxy A56 256 ГБ восстановленный"));
        assertFalse(ScanEngine.exact("Samsung Galaxy A56 256 ГБ",
            "Samsung Galaxy A56 256 ГБ бу"));
        assertTrue(ScanEngine.exact("Samsung Galaxy A56 256 ГБ",
            "Samsung Galaxy A56 256 ГБ, оригинал"));
    }
    @Test public void cheapestRelevantProductBeyondFirstTenLinksIsStillSelected() throws Exception {
        JSONArray links=new JSONArray();
        String query="Яндекс Станция Мини 3 Про";
        Shop shop=Shop.byId("wb");
        for(int n=0;n<14;n++){
            int price=n==13?11542:13000+n*100;
            links.put(new org.json.JSONObject()
                .put("url","https://www.wildberries.ru/catalog/"+(100000+n)+"/detail.aspx")
                .put("title",query+" серебристый")
                .put("context",query+"\\n"+price+" ₽"));
        }
        java.util.ArrayList<ScanEngine.Candidate> selected=
            ScanEngine.selectCandidates(links,shop,query,10);
        assertEquals(10,selected.size());
        assertEquals(11542,selected.get(0).listingPrice);
        assertTrue(selected.get(0).url.contains("/100013/detail.aspx"));
    }
    @Test public void unrelatedProductAndSearchPageDoNotBecomeOffers() throws Exception {
        JSONArray links=new JSONArray()
          .put(new org.json.JSONObject()
             .put("url","https://www.wildberries.ru/catalog/111/detail.aspx")
             .put("title","Яндекс Станция Мини 3")
             .put("context","Яндекс Станция Мини 3\\n8 500 ₽"))
          .put(new org.json.JSONObject()
             .put("url","https://www.wildberries.ru/catalog/0/search.aspx?search=test")
             .put("title","Яндекс Станция Мини 3 Про")
             .put("context","Яндекс Станция Мини 3 Про\\n11 542 ₽"));
        assertEquals(0,ScanEngine.selectCandidates(links,Shop.byId("wb"),
            "Яндекс Станция Мини 3 Про",10).size());
    }
    @Test public void rejectImplausibleListingTeaserButRetainRealDiscount() {
        assertEquals(11899,ScanEngine.compareCardAndListingPrice(11899,390));
        assertEquals(11542,ScanEngine.compareCardAndListingPrice(11899,11542));
        assertEquals(11542,ScanEngine.compareCardAndListingPrice(0,11542));
        assertEquals(11899,ScanEngine.compareCardAndListingPrice(11899,0));
    }
    @Test public void cardRedirectionMayChangeSlugButNotProductId() {
        assertTrue(ScanEngine.sameProductPage(Shop.byId("ozon"),
            "https://www.ozon.ru/product/old-title-2399149089/",
            "https://www.ozon.ru/product/new-title-2399149089/?at=abc"));
        assertFalse(ScanEngine.sameProductPage(Shop.byId("ozon"),
            "https://www.ozon.ru/product/title-2399149089/",
            "https://www.ozon.ru/product/title-2399149090/"));
        assertTrue(ScanEngine.sameProductPage(Shop.byId("wb"),
            "https://www.wildberries.ru/catalog/24599111/detail.aspx?targetUrl=EX",
            "https://www.wildberries.ru/catalog/24599111/detail"));
        assertFalse(ScanEngine.sameProductPage(Shop.byId("wb"),
            "https://www.wildberries.ru/catalog/24599111/detail.aspx",
            "https://www.wildberries.ru/catalog/0/search.aspx?search=mini"));
    }
    @Test public void scrollingCanDiscoverCheaperProOfferBelowFirstViewport() throws Exception {
        String q="Яндекс Станция Мини 3 Про";
        Shop ozon=Shop.byId("ozon");
        java.util.Map<String,ScanEngine.Candidate> pool=new java.util.LinkedHashMap<>();
        JSONArray firstScreen=new JSONArray();
        for(int n=0;n<10;n++)
            firstScreen.put(new org.json.JSONObject()
                .put("url","https://www.ozon.ru/product/mini-3-pro-"+(2399000000L+n)+"/")
                .put("title",q+" синий")
                .put("context",q+" синий\\n"+(11899+n*35)+" ₽"));
        ScanEngine.mergeSearchSnapshot(pool,firstScreen,ozon,q);
        assertEquals(10,pool.size());
        JSONArray afterScroll=new JSONArray()
            .put(new org.json.JSONObject()
                .put("url","https://www.ozon.ru/product/mini-3-pro-2399149089/")
                .put("title",q+" синий")
                .put("context",q+" синий\\n11 542 ₽\\n13 590 ₽"))
            .put(new org.json.JSONObject()
                .put("url","https://www.ozon.ru/product/mini-3-2399149999/")
                .put("title","Яндекс Станция Мини 3")
                .put("context","Яндекс Станция Мини 3\\n11 507 ₽"));
        ScanEngine.mergeSearchSnapshot(pool,afterScroll,ozon,q);
        assertEquals(11,pool.size());
        java.util.ArrayList<ScanEngine.Candidate> selected=
            ScanEngine.rankCandidates(pool.values(),10);
        assertEquals(11542,selected.get(0).listingPrice);
        assertTrue(selected.get(0).url.contains("2399149089"));
    }
    @Test public void duplicateProductAcrossScrollsKeepsLowestAssociatedPrice() throws Exception {
        String q="Яндекс Станция Мини 3 Про";
        Shop ozon=Shop.byId("ozon");
        java.util.Map<String,ScanEngine.Candidate> pool=new java.util.LinkedHashMap<>();
        String url="https://www.ozon.ru/product/mini-3-pro-2399149089/";
        JSONArray a=new JSONArray().put(new org.json.JSONObject()
            .put("url",url).put("title",q).put("context",q+"\\n11 899 ₽"));
        JSONArray b=new JSONArray().put(new org.json.JSONObject()
            .put("url",url+"?utm_source=widget").put("title",q)
            .put("context",q+"\\n11 542 ₽"));
        ScanEngine.mergeSearchSnapshot(pool,a,ozon,q);
        ScanEngine.mergeSearchSnapshot(pool,b,ozon,q);
        assertEquals(1,pool.size());
        assertEquals(11542,ScanEngine.rankCandidates(pool.values(),10).get(0).listingPrice);
    }
    @Test public void climateAccessoryMustNeverWinSmartSpeakerPriceComparison() throws Exception {
        String query="Яндекс Станция Мини 3 Про";
        assertFalse(ScanEngine.exact(query,
            "Климатический модуль для Станции Мини 3 Про с Алисой Яндекс 981125674 купить за 3 161 ₽"));
        assertFalse(ScanEngine.exact(query,
            "Климатический модуль для Яндекс Станции Мини 3 Про"));
        assertFalse(ScanEngine.exact(query,
            "Комплект крепления для Яндекс Станции Мини 3 Про"));
        assertTrue(ScanEngine.exact(query,
            "Умная колонка Яндекс Станция Мини 3 Про с Алисой, Zigbee, 18 Вт, синий"));
        JSONArray listing=new JSONArray()
            .put(new org.json.JSONObject().put("url","https://www.wildberries.ru/catalog/321211/detail.aspx")
                .put("title","Климатический модуль для Станции Мини 3 Про с Алисой Яндекс")
                .put("context","Климатический модуль для Станции Мини 3 Про\\n2 137 ₽"))
            .put(new org.json.JSONObject().put("url","https://www.wildberries.ru/catalog/321212/detail.aspx")
                .put("title","Комплект для Яндекс Станции Мини 3 Про")
                .put("context","Комплект для Станции Мини 3 Про\\n2 450 ₽"))
            .put(new org.json.JSONObject().put("url","https://www.wildberries.ru/catalog/321213/detail.aspx")
                .put("title","Умная колонка Яндекс Станция Мини 3 Про")
                .put("context","Умная колонка Яндекс Станция Мини 3 Про\\n11 542 ₽"));
        java.util.ArrayList<ScanEngine.Candidate> result=
            ScanEngine.selectCandidates(listing,Shop.byId("wb"),query,3);
        assertEquals(1,result.size());
        assertEquals(11542,result.get(0).listingPrice);
    }
    @Test public void realSearchSortingPrioritizesCheapProductCandidates() {
        assertTrue(Shop.byId("wb")
            .searchUrl("Яндекс Станция Мини 3 Про").contains("&sort=priceup"));
        assertTrue(Shop.byId("ym")
            .searchUrl("Яндекс Станция Мини 3 Про").contains("&how=aprice"));
        assertTrue(Shop.byId("ozon")
            .searchUrl("Яндекс Станция Мини 3 Про").contains("&sorting=price"));
    }

    @Test public void exhaustiveSearchDoesNotDiscardOffersAfterFortyCards() throws Exception {
        String query="Яндекс Станция Мини 3 Про";
        JSONArray links=new JSONArray();
        for(int index=0;index<95;index++){
            links.put(new org.json.JSONObject()
                .put("url","https://www.ozon.ru/product/model-"+(2000000+index)+"/")
                .put("title","Умная колонка Яндекс Станция Мини 3 Про")
                .put("context","Умная колонка Яндекс Станция Мини 3 Про\\n"+
                    (14000-index)+" ₽"));
        }
        java.util.Map<String,ScanEngine.Candidate> pool=new java.util.LinkedHashMap<>();
        ScanEngine.mergeSearchSnapshot(pool,links,Shop.byId("ozon"),query);
        assertEquals(95,pool.size());
        assertEquals(95,ScanEngine.rankCandidates(pool.values(),Integer.MAX_VALUE).size());
        assertEquals(13906,ScanEngine.rankCandidates(pool.values(),Integer.MAX_VALUE)
            .get(0).listingPrice);
    }
    @Test public void deliveryAlternativesOnOneProductAreNotCollapsed() throws Exception {
        String query="Яндекс Станция Мини 3 Про";
        String link="https://www.ozon.ru/product/station-pro-234567890/";
        JSONArray listing=new JSONArray()
            .put(new org.json.JSONObject()
                .put("url",link)
                .put("title","Умная колонка Яндекс Станция Мини 3 Про")
                .put("context","Умная колонка Яндекс Станция Мини 3 Про\n"+
                    "11 542 ₽\nДоставка завтра"))
            .put(new org.json.JSONObject()
                .put("url",link)
                .put("title","Умная колонка Яндекс Станция Мини 3 Про")
                .put("context","Умная колонка Яндекс Станция Мини 3 Про\n"+
                    "11 899 ₽\nДоставка 23 ноября"));
        java.util.Map<String,ScanEngine.Candidate> pool=new java.util.LinkedHashMap<>();
        ScanEngine.mergeSearchSnapshot(pool,listing,Shop.byId("ozon"),query);
        assertEquals(2,pool.size());
        java.util.ArrayList<ScanEngine.Candidate> ranked=
            ScanEngine.rankCandidates(pool.values(),Integer.MAX_VALUE);
        assertEquals(11542,ranked.get(0).listingPrice);
        assertEquals("завтра",ranked.get(0).deliveryHint);
        assertEquals("23 ноября",ranked.get(1).deliveryHint);
    }
    @Test public void listingEndAndPaginationAreCheckedByStateNotArbitraryPassCount() {
        assertFalse(ScanEngine.atPageEnd(100,10000,900));
        assertTrue(ScanEngine.atPageEnd(9100,10000,900));
        assertFalse(ScanEngine.settledAtBottom(false,10000,10000,20,20));
        assertFalse(ScanEngine.settledAtBottom(true,10001,10000,20,20));
        assertTrue(ScanEngine.settledAtBottom(true,10000,10000,20,20));
        java.util.Set<String> visited=new java.util.HashSet<>();
        String first="https://www.ozon.ru/search/?text=mini";
        String second="https://www.ozon.ru/search/?text=mini&page=2";
        visited.add(first);
        assertTrue(ScanEngine.isNextSearchPage(Shop.byId("ozon"),second,visited));
        visited.add(second);
        assertFalse(ScanEngine.isNextSearchPage(Shop.byId("ozon"),second,visited));
        assertFalse(ScanEngine.isNextSearchPage(Shop.byId("ozon"),
            "https://example.com/search/?text=mini",visited));
    }
    @Test public void priceFrontierLowersTargetOnEveryObservedBatch() {
        DynamicPriceFrontier frontier=new DynamicPriceFrontier();
        assertEquals(0,frontier.observedCeiling());
        frontier.observe(12000);
        frontier.observe(13000);
        frontier.observe(14000);
        assertEquals(12000,frontier.observedCeiling());
        frontier.observe(11000);
        assertEquals(11000,frontier.observedCeiling());
        assertEquals(0,frontier.confirmedCeiling());
    }

    @Test public void unconfirmedLowListingMustNotHideRealFallback() {
        DynamicPriceFrontier frontier=new DynamicPriceFrontier();
        java.util.ArrayList<ScanEngine.Candidate> candidates=new java.util.ArrayList<>();
        candidates.add(new ScanEngine.Candidate("https://www.ozon.ru/product/pro-2000001/",12000));
        candidates.add(new ScanEngine.Candidate("https://www.ozon.ru/product/pro-2000002/",13000));
        candidates.add(new ScanEngine.Candidate("https://www.ozon.ru/product/pro-2000003/",14000));
        frontier.observe(12000);
        assertEquals(1,DynamicPriceFrontier.nextUnpruned(candidates,1,frontier));
        // The 12,000 ₽ teaser turned out to be an accessory: no hard ceiling.
        assertEquals(0,frontier.confirmedCeiling());
        assertEquals(1,DynamicPriceFrontier.nextUnpruned(candidates,1,frontier));
        // Only after model + product page verify can the expensive cards be deferred.
        frontier.confirm(12000);
        assertEquals(3,DynamicPriceFrontier.nextUnpruned(candidates,1,frontier));
    }

    @Test public void cheaperListingStillQualifiesAfterTrustedPrice() {
        DynamicPriceFrontier frontier=new DynamicPriceFrontier();
        frontier.observe(12000);
        frontier.confirm(12000);
        assertTrue(frontier.deferHigher(14000));
        assertFalse(frontier.deferHigher(11542));
        assertFalse(frontier.deferHigher(12000));
        frontier.observe(11000);
        frontier.confirm(11000);
        assertTrue(frontier.deferHigher(11542));
        assertEquals(11000,frontier.confirmedCeiling());
        frontier.reset();
        assertEquals(0,frontier.confirmedCeiling());
        assertFalse(frontier.deferHigher(14000));
    }
}
