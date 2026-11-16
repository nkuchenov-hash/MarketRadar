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
}
