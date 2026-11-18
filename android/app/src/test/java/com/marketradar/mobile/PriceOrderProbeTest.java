package com.marketradar.mobile;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.*;

public class PriceOrderProbeTest {
    private static JSONArray batch(int start,int count,int firstPrice,int step)
            throws Exception {
        JSONArray values=new JSONArray();
        for(int n=0;n<count;n++){
            int index=start+n;
            int price=firstPrice+n*step;
            values.put(new JSONObject()
                .put("url","https://www.wildberries.ru/catalog/"+(223500000+index)+
                    "/detail.aspx")
                .put("title","Яндекс Станция Мини 3 Про")
                .put("context","Яндекс Станция Мини 3 Про "+price+" ₽"));
        }
        return values;
    }

    @Test public void nativePriceAscendingSortParametersRequested(){
        for(Shop shop:Shop.ALL){
            String requested=shop.searchUrl("Яндекс Станция Мини 3 Про");
            assertTrue(shop.name,shop.priceAscendingRequested(requested));
            assertFalse(shop.name,shop.priceAscendingRequested(
                shop.searchBase+"Яндекс+Станция+Мини+3+Про"));
        }
    }

    @Test public void actualPriceOrderNeedsMultipleScreensNotJustUrl()
            throws Exception {
        Shop shop=Shop.byId("wb");
        PriceOrderProbe probe=new PriceOrderProbe();
        int price=9000;
        for(int i=0;i<5;i++){
            probe.observe(batch(i*5,5,price+i*500,100),shop);
            if(i<4)assertFalse(probe.ascendingObserved());
        }
        assertEquals(25,probe.inspected());
        assertTrue(probe.ascendingObserved());
        assertFalse(probe.ascendingContradicted());
        assertTrue(probe.description(true).contains("наблюдается"));
        probe.reset();
        assertEquals(0,probe.inspected());
        assertFalse(probe.ascendingObserved());
    }

    @Test public void lateCheaperCardDisprovesReliableNativeSorting()
            throws Exception {
        Shop shop=Shop.byId("wb");
        PriceOrderProbe probe=new PriceOrderProbe();
        probe.observe(batch(0,5,11700,100),shop);
        probe.observe(batch(5,5,10824,100),shop);
        assertTrue(probe.ascendingContradicted());
        assertEquals(1,probe.falls());
        assertFalse(probe.ascendingObserved());
        assertTrue(probe.description(true).contains("не соблюдается"));
    }

    @Test public void repeatedViewportDoesNotCreateFakeOrderDrops()
            throws Exception {
        Shop shop=Shop.byId("wb");
        PriceOrderProbe probe=new PriceOrderProbe();
        JSONArray first=batch(0,5,9000,100);
        probe.observe(first,shop);
        probe.observe(first,shop);
        assertEquals(5,probe.inspected());
        assertEquals(0,probe.falls());
    }
}
