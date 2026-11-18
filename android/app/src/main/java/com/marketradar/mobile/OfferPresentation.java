package com.marketradar.mobile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;

/** Result presentation only. Never changes marketplace scanning or price verification. */
final class OfferPresentation {
    private static final Locale RU=Locale.forLanguageTag("ru-RU");
    private static final DateTimeFormatter DAY_MONTH=DateTimeFormatter.ofPattern("d MMMM",RU);
    private static final DateTimeFormatter FULL_DATE=DateTimeFormatter.ofPattern("d MMMM yyyy",RU);

    private OfferPresentation(){}

    private static int priceRank(ScanEngine.Offer offer){
        return offer.price>0?offer.price:Integer.MAX_VALUE;
    }

    private static int dateRank(DeliveryPolicy policy,ScanEngine.Offer offer){
        int days=policy.days(offer.deliveryHint);
        return days>=0?days:Integer.MAX_VALUE;
    }

    static Comparator<ScanEngine.Offer> comparator(DeliveryPolicy policy,boolean deliveryFirst){
        if(deliveryFirst)
            return Comparator.comparingInt((ScanEngine.Offer offer)->dateRank(policy,offer))
                .thenComparingInt(OfferPresentation::priceRank);
        return Comparator.comparingInt(OfferPresentation::priceRank)
            .thenComparingInt(offer->dateRank(policy,offer));
    }

    /** Priority is determined by search conditions, not by the absolute cheapest price. */
    static int priority(DeliveryPolicy policy,ScanEngine.Offer offer,int timelyBest){
        // Never put an out-of-deadline product in "delivered in time", even
        // when the user allows a sufficiently cheaper late alternative.
        if(offer.price>0&&policy.timely(offer.deliveryHint))return 0;
        return policy.days(offer.deliveryHint)>=0?1:2;
    }

    static boolean showAlternative(DeliveryPolicy policy,ScanEngine.Offer offer,
                                   int bestTimelyPrice){
        if(offer.price<=0||policy.timely(offer.deliveryHint))return false;
        // Only offers actually cheaper than the best on-time price add
        // decision value. Unknown delivery is never presented as on-time.
        return bestTimelyPrice<=0||offer.price<bestTimelyPrice;
    }

    static Comparator<ScanEngine.Offer> priorityComparator(
            DeliveryPolicy policy,boolean deliveryFirst,int timelyBest){
        return Comparator.comparingInt(
            (ScanEngine.Offer offer)->priority(policy,offer,timelyBest))
            .thenComparing(comparator(policy,deliveryFirst));
    }

    static String displayKey(ScanEngine.Offer offer,DeliveryPolicy policy){
        // Deduplicate equivalent marketplace tiles that carry different
        // seller/card IDs but show the same variant, date and price.
        // Keep different colours, sizes or delivery dates separate.
        String title=(offer.title==null?"":offer.title)
            .replaceAll("(?iu)\\s+(?:купить\\s+за|в\\s+интернет-магазине).*$","")
            .replaceAll("(?<!\\d)\\d{8,}(?!\\d)"," ");
        return offer.shop.id+"|"+offer.price+"|"+
            policy.days(offer.deliveryHint)+"|"+offer.priceCondition+"|"+
            ScanEngine.norm(title);
    }

    private static String dateText(LocalDate date,LocalDate base){
        return date.format(date.getYear()==base.getYear()?DAY_MONTH:FULL_DATE);
    }

    static String deadline(DeliveryPolicy policy){
        return "Доставка до "+dateText(policy.today.plusDays(policy.maxDays),policy.today)
            +" · "+policy.maxDays+" дн.";
    }

    static String deliveryDate(DeliveryPolicy policy,String hint){
        int days=policy.days(hint);
        if(days<0)return "Дата доставки неизвестна";
        return "Доставка "+dateText(policy.today.plusDays(days),policy.today);
    }

    static String deliveryNote(DeliveryPolicy policy,String hint){
        int days=policy.days(hint);
        if(days<0)return "Срок уточняется в магазине";
        if(days>policy.maxDays)return "Позже срока на "+(days-policy.maxDays)+" дн.";
        if(days==0)return "Сегодня · в срок";
        if(days==1)return "Завтра · в срок";
        return "Через "+days+" дн. · в срок";
    }

    static boolean afterDeadline(DeliveryPolicy policy,String hint){
        int days=policy.days(hint);
        return days>policy.maxDays;
    }
}
