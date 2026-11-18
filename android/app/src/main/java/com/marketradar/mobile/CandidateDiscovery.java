package com.marketradar.mobile;

import java.util.HashSet;
import java.util.Set;

/**
 * Search snippets are discovery hints, NOT the product identity decision.
 * Marketplace cards frequently truncate product titles. Do not discard a
 * potentially matching card until opening its full product page and running
 * local AI product-identity verification.
 */
final class CandidateDiscovery {
    private CandidateDiscovery(){}

    static boolean shouldInspect(String query,String title,
                                 String context,String imageAlt){
        if(ScanEngine.exact(query,title) ||
           ScanEngine.exact(query,imageAlt) ||
           ScanEngine.exact(query,context))return true;

        String q=ScanEngine.norm(query);
        String evidence=ScanEngine.norm(title+" "+imageAlt+" "+context);
        String mainTitle=ScanEngine.norm(title);
        // A clear accessory headline must not be sent to the model as a
        // plausible standalone device; avoid exploding the candidate count.
        if(mainTitle.startsWith("климатический модуль")||
           mainTitle.startsWith("модуль для")||
           mainTitle.startsWith("комплект для")||
           mainTitle.startsWith("комплект крепления")||
           mainTitle.startsWith("чехол для")||
           mainTitle.startsWith("крепление для")||
           mainTitle.startsWith("кейс для")||
           mainTitle.startsWith("чехол ")||
           mainTitle.startsWith("кейс ")||
           mainTitle.startsWith("пульт для")||
           mainTitle.startsWith("держатель для")||
           mainTitle.startsWith("подставка для")||
           mainTitle.startsWith("адаптер для")||
           mainTitle.startsWith("блок питания для")||
           mainTitle.startsWith("кабель для"))return false;
        // An explicit full non-Pro model isn't a truncated Pro listing.
        // An abbreviated "Умная колонка Станция Мини" still qualifies,
        // and an image ALT bearing "Про" was accepted above.
        if(q.contains("мини 3 про")&&
           mainTitle.matches(".*\\bмини 3\\b.*")&&
           !mainTitle.contains("мини 3 про")&&
           !mainTitle.contains("yndx 00059")&&
           !title.contains("…")&&!title.contains("..."))return false;
        if(q.isBlank()||evidence.isBlank())return false;
        Set<String> words=new HashSet<>(
            java.util.Arrays.asList(evidence.split(" ")));
        Set<String> queryWords=new HashSet<>(
            java.util.Arrays.asList(q.split(" ")));

        // For "Яндекс Станция Мини 3 Про", a WB search listing might expose
        // only "Умная колонка Станция Мини" until its detail page is opened.
        // This is a candidate, not a confirmed model.
        if(queryWords.contains("мини")&&queryWords.contains("станция")){
            return words.contains("мини") &&
                (words.contains("станция")||words.contains("яндекс")) &&
                !isObviousUnrelatedDevice(evidence);
        }

        int matched=0,significant=0;
        for(String term:queryWords){
            if(term.length()<3)continue;
            significant++;
            if(words.contains(term))matched++;
        }
        return significant>=2 && matched>=2 &&
            matched*2>=significant;
    }

    private static boolean isObviousUnrelatedDevice(String text){
        // Optional performance hint only. The full page + NLI is authoritative.
        return text.contains("смартфон")||text.contains("ноутбук")||
            text.contains("телевизор")||text.contains("стиральная машина");
    }
}
