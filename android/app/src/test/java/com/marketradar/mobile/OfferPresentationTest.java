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
        // Discounts permit a late alternative, never an on-time headline.
        assertEquals(1,OfferPresentation.priority(policy,discounted,12000));
        assertEquals(0,OfferPresentation.priority(policy,normal,12000));
        assertEquals(1,OfferPresentation.priority(policy,item(11000,"28 ноября"),12000));
    }

    @Test public void lateAlternativeRequiresConfiguredTwentyPercentDiscount(){
        DeliveryPolicy policy=new DeliveryPolicy(3,true,20,LocalDate.of(2026,11,18));
        int timelyBest=11479;
        assertFalse(OfferPresentation.showAlternative(policy,item(10893,"28 ноября"),timelyBest));
        assertFalse(OfferPresentation.showAlternative(policy,item(11090,""),timelyBest));
        assertFalse(OfferPresentation.showAlternative(policy,item(11999,"28 ноября"),timelyBest));
        assertTrue(OfferPresentation.showAlternative(policy,item(9000,"28 ноября"),timelyBest));
        assertFalse(OfferPresentation.showAlternative(policy,item(9000,"28 ноября"),0));
        assertFalse(OfferPresentation.showAlternative(policy,item(11300,"20 ноября"),timelyBest));
        DeliveryPolicy strict=new DeliveryPolicy(3,false,20,LocalDate.of(2026,11,18));
        assertFalse(OfferPresentation.showAlternative(strict,item(8000,"28 ноября"),timelyBest));
    }

    @Test public void equivalentSellerCardsDoNotClutterResults(){
        DeliveryPolicy p=new DeliveryPolicy(3,false,15,LocalDate.of(2026,11,18));
        ScanEngine.Offer a=new ScanEngine.Offer(Shop.byId("wb"),
            "https://www.wildberries.ru/catalog/12345/detail.aspx",
            "Умная колонка Яндекс Станция Мини 3 Про с Алисой, Zigbee, серый Яндекс 2235040416",
            11835,"","20 ноября","");
        ScanEngine.Offer b=new ScanEngine.Offer(Shop.byId("wb"),
            "https://www.wildberries.ru/catalog/98765/detail.aspx",
            "Умная колонка Яндекс Станция Мини 3 Про с Алисой, Zigbee, серый Яндекс 2235041579",
            11835,"","20 ноября","");
        ScanEngine.Offer blue=new ScanEngine.Offer(Shop.byId("wb"),
            "https://www.wildberries.ru/catalog/88888/detail.aspx",
            "Умная колонка Яндекс Станция Мини 3 Про с Алисой, Zigbee, синий Яндекс 2235041579",
            11835,"","20 ноября","");
        assertEquals(OfferPresentation.displayKey(a,p),OfferPresentation.displayKey(b,p));
        assertNotEquals(OfferPresentation.displayKey(a,p),OfferPresentation.displayKey(blue,p));
    }

    @Test public void compactResultsShowCheapestPerMarketNotEveryPricierYandexCard(){
        DeliveryPolicy policy=new DeliveryPolicy(3,false,15,LocalDate.of(2026,11,18));
        Shop ym=Shop.byId("ym"),wb=Shop.byId("wb"),oz=Shop.byId("ozon");
        ScanEngine.Offer yandexExpensive=new ScanEngine.Offer(ym,
            "https://market.yandex.ru/card/a/4620658531",
            "Яндекс Станция Мини 3 Про",12420,"","21 ноября","");
        ScanEngine.Offer yandexCheaper=new ScanEngine.Offer(ym,
            "https://market.yandex.ru/card/b/4620658532",
            "Яндекс Станция Мини 3 Про",11687,"","21 ноября","с Яндекс Пэй");
        ScanEngine.Offer yandexCompetitive=new ScanEngine.Offer(ym,
            "https://market.yandex.ru/card/d/4620658534",
            "Яндекс Станция Мини 3 Про",12000,"","21 ноября","");
        ScanEngine.Offer yandexLate=new ScanEngine.Offer(ym,
            "https://market.yandex.ru/card/c/4620658533",
            "Яндекс Станция Мини 3 Про",12103,"","22 ноября","");
        ScanEngine.Offer wbBest=new ScanEngine.Offer(wb,
            "https://www.wildberries.ru/catalog/223500001/detail.aspx",
            "Яндекс Станция Мини 3 Про",11835,"","20 ноября","");
        ScanEngine.Offer ozBest=new ScanEngine.Offer(oz,
            "https://www.ozon.ru/product/test-235235223/",
            "Яндекс Станция Мини 3 Про",12990,"","21 ноября","");
        java.util.List<ScanEngine.Offer> all=Arrays.asList(
            yandexExpensive,yandexLate,wbBest,ozBest,yandexCheaper,
            yandexCompetitive);
        ArrayList<ScanEngine.Offer> compact=OfferPresentation.visibleOffers(
            all,policy,false,false);
        assertEquals(3,compact.size());
        assertSame(yandexCheaper,compact.get(0));
        assertSame(wbBest,compact.get(1));
        assertSame(ozBest,compact.get(2));
        assertFalse(compact.contains(yandexExpensive));
        assertFalse(compact.contains(yandexLate));
        ArrayList<ScanEngine.Offer> expanded=OfferPresentation.visibleOffers(
            all,policy,true,false);
        assertEquals(4,expanded.size());
        assertTrue(expanded.contains(yandexCompetitive));
        assertFalse(expanded.contains(yandexExpensive));
        assertFalse(expanded.contains(yandexLate));
    }

    @Test public void compactAlwaysKeepsAbsoluteMinimumWhenDeliveryIsLateOrUnknown(){
        DeliveryPolicy policy=new DeliveryPolicy(3,false,15,LocalDate.of(2026,11,18));
        ScanEngine.Offer timely=item(11835,"20 ноября");
        ScanEngine.Offer late=item(10772,"28 ноября");
        ScanEngine.Offer unknown=item(10893,"");
        ArrayList<ScanEngine.Offer> compact=OfferPresentation.visibleOffers(
            Arrays.asList(timely,late,unknown),policy,false,false);
        assertTrue(compact.contains(timely));
        assertTrue(compact.contains(late));
        assertFalse(compact.contains(unknown));
        assertEquals(2,compact.size());

        ArrayList<ScanEngine.Offer> onlyUnknown=OfferPresentation.visibleOffers(
            Arrays.asList(unknown),policy,false,false);
        assertEquals(1,onlyUnknown.size());
        assertSame(unknown,onlyUnknown.get(0));
    }

    @Test public void crossYearDateIsUnambiguous(){
        DeliveryPolicy policy=new DeliveryPolicy(5,false,15,LocalDate.of(2026,12,30));
        assertEquals("Доставка до 4 января 2027 · 5 дн.",OfferPresentation.deadline(policy));
        assertEquals("Доставка 2 января 2027",
            OfferPresentation.deliveryDate(policy,"2 января"));
    }
}
