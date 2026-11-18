package com.marketradar.mobile;

import org.junit.Test;
import static org.junit.Assert.*;
import java.time.LocalDate;
import java.util.Arrays;

/** Replays the November 17, 2026 screenshot examples with actual delivery days. */
public class DeliveryPolicyTest {
    @Test public void novemberScreenshotDeliveryDatesAreNotInterchangeable(){
        DeliveryPolicy p=new DeliveryPolicy(3,false,15,LocalDate.of(2026,11,17));
        assertEquals(0,p.days("За час"));
        assertEquals(1,p.days("Завтра"));
        assertEquals(2,p.days("Послезавтра"));
        assertEquals(3,p.days("20 ноября"));
        assertEquals(4,p.days("21 ноября"));
        assertEquals(8,p.days("25 ноября"));
        assertEquals(-1,p.days("Самовывоз"));
        assertTrue(p.timely("20 ноября"));
        assertFalse(p.timely("25 ноября"));
        assertFalse(p.timely(""));
        assertTrue(p.eligible(11316,"20 ноября",11316));
        assertFalse(p.eligible(10772,"25 ноября",11316));
        assertFalse(p.eligible(12518,"",11316));
    }

    @Test public void abbreviatedMarketplaceDatesAreRealDates(){
        DeliveryPolicy p=new DeliveryPolicy(3,false,15,LocalDate.of(2026,11,18));
        assertEquals(2,p.days("20 ноя."));
        assertEquals(3,p.days("21 ноя"));
        assertEquals(10,p.days("28 ноя."));
        assertEquals("20 ноя.",ScanEngine.readDeliveryHint(
            "Умная колонка 11 479 ₽ доставка 20 ноя."));
        assertEquals(2,p.days(ScanEngine.readDeliveryHint(
            "Умная колонка 11 479 ₽ доставка 20 ноя.")));
        assertEquals("через 2 дня",ScanEngine.readDeliveryHint(
            "Умная колонка 11 479 ₽ через 2 дня"));
        assertEquals(-1,p.days("Доставка уточняется"));
    }

    @Test public void lateCheapItemMustNeverPruneFastDelivery(){
        DeliveryPolicy p=new DeliveryPolicy(3,false,15,LocalDate.of(2026,11,17));
        DynamicPriceFrontier f=new DynamicPriceFrontier();
        f.configure(p);
        assertFalse(f.confirm(10772,"25 ноября"));
        assertEquals(0,f.confirmedCeiling());
        assertFalse(f.deferHigher(10772,"25 ноября")); // keep late cheapest for absolute price
        assertFalse(f.deferHigher(11542,"Завтра"));
        assertTrue(f.confirm(11316,"20 ноября"));
        assertEquals(11316,f.confirmedCeiling());
        assertTrue(f.deferHigher(11542,"Завтра"));
        assertFalse(f.deferHigher(10900,"Завтра"));
        assertFalse(f.deferHigher(0,""));
    }

    @Test public void optionalLateDeliveryRequiresSpecifiedPercentDiscount(){
        DeliveryPolicy policy=new DeliveryPolicy(3,true,15,LocalDate.of(2026,11,17));
        DynamicPriceFrontier f=new DynamicPriceFrontier();
        f.configure(policy);
        assertTrue(f.confirm(11316,"20 ноября"));
        assertFalse(policy.lateException(10772,"25 ноября",11316));
        assertTrue(policy.lateException(9000,"25 ноября",11316));
        assertFalse(policy.eligible(10772,"25 ноября",11316));
        assertTrue(policy.eligible(9000,"25 ноября",11316));
        assertFalse(f.deferHigher(10772,"25 ноября")); // late offers remain visible
        assertFalse(f.deferHigher(9000,"25 ноября"));
        assertTrue(f.deferHigher(12000,"")); // known timely offer is cheaper
    }

    @Test public void activeScrollDoesNotCountAsIdlePageTimeout(){
        assertFalse(ScanEngine.exceededIdle(10000,27999,18000));
        assertTrue(ScanEngine.exceededIdle(10000,28000,18000));
        assertFalse(ScanEngine.exceededIdle(27000,28000,18000));
        assertTrue(ScanEngine.scrollAdvanced(900,400,2500,2500,4,4));
        assertTrue(ScanEngine.scrollAdvanced(900,900,3000,2500,4,4));
        assertTrue(ScanEngine.scrollAdvanced(900,900,2500,2500,5,4));
        assertFalse(ScanEngine.scrollAdvanced(900,900,2500,2500,4,4));
    }
}
