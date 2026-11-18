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
