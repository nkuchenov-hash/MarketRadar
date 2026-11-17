package com.marketradar.mobile;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * On-device delivery deadline and explicit exception rule.
 *
 * An UNKNOWN delivery date never qualifies for the "delivered by" best
 * price and must not be used to prune known eligible alternatives.
 * Times/dates in a marketplace listing are hints, NOT checkout confirmation.
 */
final class DeliveryPolicy {
    final int maxDays;
    final boolean allowSlowDiscount;
    final int slowDiscountPercent;
    final LocalDate today;

    DeliveryPolicy(int maxDays,boolean allowSlowDiscount,int slowDiscountPercent,
                   LocalDate today){
        this.maxDays=Math.max(0,Math.min(90,maxDays));
        this.allowSlowDiscount=allowSlowDiscount;
        this.slowDiscountPercent=Math.max(1,Math.min(90,slowDiscountPercent));
        this.today=today;
    }

    static DeliveryPolicy standard(){
        return new DeliveryPolicy(3,false,15,LocalDate.now());
    }

    int days(String delivery){
        if(delivery==null||delivery.isBlank())return -1;
        String s=delivery.toLowerCase(Locale.forLanguageTag("ru"))
            .replace('ё','е').trim();
        if(s.contains("послезавтра"))return 2;
        if(s.contains("завтра"))return 1;
        if(s.contains("сегодня")||s.contains("за час")||
           s.contains("в течение часа"))return 0;
        Matcher relative=Pattern.compile("(?:через\\s*)?(\\d{1,2})\\s*(?:дн(?:я|ей|ь)|сут(?:ок|ки))")
            .matcher(s);
        if(relative.find())return Integer.parseInt(relative.group(1));
        Matcher explicit=Pattern.compile("(\\d{1,2})\\s*"+
             "(января|февраля|марта|апреля|мая|июня|июля|августа|"+
             "сентября|октября|ноября|декабря)").matcher(s);
        if(explicit.find()){
            String[] months={"января","февраля","марта","апреля","мая","июня",
                "июля","августа","сентября","октября","ноября","декабря"};
            int month=0;
            for(int i=0;i<months.length;i++)
                if(months[i].equals(explicit.group(2)))month=i+1;
            if(month==0)return -1;
            try{
                LocalDate date=LocalDate.of(today.getYear(),month,
                    Integer.parseInt(explicit.group(1)));
                if(date.isBefore(today))date=date.plusYears(1);
                return (int)ChronoUnit.DAYS.between(today,date);
            }catch(Exception ignored){return -1;}
        }
        String[] weekdays={"понедельник","вторник","среду","четверг",
            "пятницу","субботу","воскресенье"};
        for(int i=0;i<weekdays.length;i++){
            if(s.contains(weekdays[i])){
                int current=today.getDayOfWeek().getValue();
                return (i+1-current+7)%7;
            }
        }
        return -1;
    }

    boolean timely(String hint){
        int d=days(hint);
        return d>=0&&d<=maxDays;
    }

    boolean lateException(int price,String hint,int bestTimelyPrice){
        int d=days(hint);
        return allowSlowDiscount&&d>maxDays&&price>0&&
            bestTimelyPrice>0&&
            (long)price*100 <=
                (long)bestTimelyPrice*(100-slowDiscountPercent);
    }

    boolean eligible(int price,String hint,int bestTimelyPrice){
        return price>0&&(timely(hint)||
            lateException(price,hint,bestTimelyPrice));
    }

    boolean potentiallyEligible(String hint){
        int d=days(hint);
        return d<0||d<=maxDays||allowSlowDiscount;
    }
}
