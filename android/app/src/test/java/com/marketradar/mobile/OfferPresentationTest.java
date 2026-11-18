package com.marketradar.mobile;

import org.junit.Test;
import static org.junit.Assert.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;

public class OfferPresentationTest {
    private static ScanEngine.Offer item(int price,String delivery){
        return new ScanEngine.Offer(Shop.byId("wb"),
            "https://www.wildberries.ru/catalog/"+price+"/detail.aspx",
            "Яндекс Станция Мини 3 Про",price,"из выдачи",delivery,"");
    }

    @Test public void showActualConfiguredDeliveryDeadline(){
        DeliveryPolicy policy=new DeliveryPolicy(3,false,15,LocalDate.of(2026,11,18));
        assertEquals("Доставка до 21 ноября · 3 дн.",OfferPresentation.deadline(policy));
        assertEquals("Доставка 28 ноября",OfferPresentation.deliveryDate(policy,"28 ноября"));
        assertEquals("Позже срока на 7 дн.",OfferPresentation.deliveryNote(policy,"28 ноября"));
        assertEquals("Доставка 19 ноября",OfferPresentation.deliveryDate(policy,"завтра"));
        assertEquals("Завтра · в срок",OfferPresentation.deliveryNote(policy,"завтра"));
        assertTrue(OfferPresentation.afterDeadline(policy,"28 ноября"));
        assertFalse(OfferPresentation.afterDeadline(policy,"21 ноября"));
    }

    @Test public void sortingDeliveryFirstUsesActualDateThenPriceAndUnknownLast(){
        DeliveryPolicy policy=new DeliveryPolicy(3,false,15,LocalDate.of(2026,11,18));
        ScanEngine.Offer cheapLate=item(10772,"28 ноября");
        ScanEngine.Offer early=item(12420,"20 ноября");
        ScanEngine.Offer sameDayCheaper=item(11990,"20 ноября");
        ScanEngine.Offer unknown=item(9000,"");
        ArrayList<ScanEngine.Offer> offers=new ArrayList<>(
            Arrays.asList(cheapLate,early,unknown,sameDayCheaper));
        offers.sort(OfferPresentation.comparator(policy,true));
        assertEquals(Arrays.asList(sameDayCheaper,early,cheapLate,unknown),offers);
        offers.sort(OfferPresentation.comparator(policy,false));
        assertEquals(Arrays.asList(unknown,cheapLate,sameDayCheaper,early),offers);
    }

    @Test public void displayLaterCheaperItemsEvenOutsideDeadline(){
        DeliveryPolicy policy=new DeliveryPolicy(3,false,15,LocalDate.of(2026,11,18));
        ScanEngine.Offer cheapest=item(10772,"28 ноября");
        ScanEngine.Offer timely=item(11316,"20 ноября");
        ArrayList<ScanEngine.Offer> offers=new ArrayList<>(Arrays.asList(timely,cheapest));
        offers.sort(OfferPresentation.comparator(policy,false));
        assertSame(cheapest,offers.get(0));
        offers.sort(OfferPresentation.comparator(policy,true));
        assertSame(timely,offers.get(0));
        assertEquals(2,offers.size()); // Sort never filters or runs a marketplace scan.
    }

    @Test public void eligibleAlwaysRanksAboveCheaperLateAndUnknown(){
        DeliveryPolicy policy=new DeliveryPolicy(3,false,15,LocalDate.of(2026,11,18));
        ScanEngine.Offer onTime=item(11479,"20 ноября");
        ScanEngine.Offer lateCheap=item(10893,"28 ноября");
        ScanEngine.Offer unknownCheap=item(10000,"");
        ArrayList<ScanEngine.Offer> offers=new ArrayList<>(
            Arrays.asList(lateCheap,unknownCheap,onTime));
        int bestTimely=11479;
        offers.sort(OfferPresentation.priorityComparator(policy,false,bestTimely));
        assertSame(onTime,offers.get(0));
        assertSame(lateCheap,offers.get(1));
        assertSame(unknownCheap,offers.get(2));
        offers.sort(OfferPresentation.priorityComparator(policy,true,bestTimely));
        assertSame(onTime,offers.get(0));
        assertSame(lateCheap,offers.get(1));
        assertSame(unknownCheap,offers.get(2));
    }

    @Test public void explicitlyAllowedLateDiscountIsPrimary(){
        DeliveryPolicy policy=new DeliveryPolicy(3,true,15,LocalDate.of(2026,11,18));
        ScanEngine.Offer normal=item(12000,"20 ноября");
        ScanEngine.Offer discounted=item(9900,"28 ноября");
        assertEquals(0,OfferPresentation.priority(policy,discounted,12000));
        assertEquals(0,OfferPresentation.priority(policy,normal,12000));
        assertEquals(1,OfferPresentation.priority(policy,item(11000,"28 ноября"),12000));
    }

    @Test public void crossYearDateIsUnambiguous(){
        DeliveryPolicy policy=new DeliveryPolicy(5,false,15,LocalDate.of(2026,12,30));
        assertEquals("Доставка до 4 января 2027 · 5 дн.",OfferPresentation.deadline(policy));
        assertEquals("Доставка 2 января 2027",
            OfferPresentation.deliveryDate(policy,"2 января"));
    }
}
