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

    @Test public void crossYearDateIsUnambiguous(){
        DeliveryPolicy policy=new DeliveryPolicy(5,false,15,LocalDate.of(2026,12,30));
        assertEquals("Доставка до 4 января 2027 · 5 дн.",OfferPresentation.deadline(policy));
        assertEquals("Доставка 2 января 2027",
            OfferPresentation.deliveryDate(policy,"2 января"));
    }
}
