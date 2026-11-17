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
