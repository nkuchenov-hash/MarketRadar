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
}
