package com.marketradar.mobile;

import org.junit.Test;
import static org.junit.Assert.*;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.ArrayList;

public class SearchSessionStoreTest {
    @Test public void retainsResultsAndSearchSettingsAfterProcessRestart(){
        DeliveryPolicy p=new DeliveryPolicy(3,true,15,LocalDate.of(2026,11,18));
        ScanEngine.Offer item=new ScanEngine.Offer(
            Shop.byId("wb"),"https://www.wildberries.ru/catalog/12345678/detail.aspx",
            "Яндекс Станция Мини 3 Про серая",10772,"Цена из выдачи",
            "28 ноября","с WB Кошельком","https://images.example.com/mini3.jpg");
        ScanEngine.Offer second=new ScanEngine.Offer(
            Shop.byId("ym"),"https://market.yandex.ru/product--station/12345678",
            "Яндекс Станция Мини 3 Про синяя",12420,"Цена из выдачи",
            "20 ноября","","");
        String json=SearchSessionStore.encode("Яндекс Станция Мини 3 Про",
            p,Arrays.asList(item,second),true,"Поиск…");
        SearchSessionStore.Snapshot restored=SearchSessionStore.decode(json);
        assertNotNull(restored);
        assertEquals("Яндекс Станция Мини 3 Про",restored.query);
        assertEquals(LocalDate.of(2026,11,18),restored.policy.today);
        assertEquals(3,restored.policy.maxDays);
        assertTrue(restored.policy.allowSlowDiscount);
        assertEquals(15,restored.policy.slowDiscountPercent);
        assertTrue(restored.interrupted);
        assertEquals(2,restored.offers.size());
        ScanEngine.Offer first=restored.offers.get(0);
        assertEquals(10772,first.price);
        assertEquals("28 ноября",first.deliveryHint);
        assertEquals("с WB Кошельком",first.priceCondition);
        assertEquals("https://images.example.com/mini3.jpg",first.imageUrl);
        assertEquals("wb",first.shop.id);
        assertEquals("Поиск…",restored.message);
    }

    @Test public void preservesCompletedAndStoppedScanData(){
        DeliveryPolicy p=new DeliveryPolicy(5,false,15,LocalDate.of(2026,11,18));
        ScanEngine.Offer offer=new ScanEngine.Offer(
            Shop.byId("ozon"),"https://www.ozon.ru/product/mini3-987654321/",
            "Станция Мини 3 Про",12100,"Подтверждённая модель","завтра","");
        String json=SearchSessionStore.encode("Станция Мини 3 Про",p,
            Arrays.asList(offer),false,"Поиск остановлен");
        SearchSessionStore.Snapshot snapshot=SearchSessionStore.decode(json);
        assertNotNull(snapshot);
        assertFalse(snapshot.interrupted);
        assertEquals(1,snapshot.offers.size());
        assertEquals(12100,snapshot.offers.get(0).price);
    }

    @Test public void discardsCorruptStateAndForeignLinks(){
        assertNull(SearchSessionStore.decode(null));
        assertNull(SearchSessionStore.decode("not json"));
        DeliveryPolicy p=new DeliveryPolicy(3,false,15,LocalDate.of(2026,11,18));
        ScanEngine.Offer invalid=new ScanEngine.Offer(Shop.byId("wb"),
            "https://evil.example/product/123","Fake",1000,"","завтра","");
        SearchSessionStore.Snapshot snapshot=SearchSessionStore.decode(
            SearchSessionStore.encode("Яндекс Станция Мини 3 Про",p,
                Arrays.asList(invalid),true,""));
        assertNotNull(snapshot);
        assertEquals(0,snapshot.offers.size());
    }
}
