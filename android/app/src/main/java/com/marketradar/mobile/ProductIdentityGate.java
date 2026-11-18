package com.marketradar.mobile;

/**
 * Literal normalized title matches do not need AI approval.
 * Other candidates require BOTH structural model identity and the
 * local on-device natural language inference to agree.
 *
 * Structured matching is a fast conservative guard for model numbers and
 * variants; NLI identifies the semantic relation between an offer title and
 * the requested item. Neither name similarity nor NLI score alone is enough.
 */
final class ProductIdentityGate {
    private ProductIdentityGate(){}

    static boolean literalMatch(String query,String title){
        String wanted=ScanEngine.norm(query);
        if(wanted.isBlank()||!ScanEngine.exact(query,title))return false;
        String actual=ScanEngine.norm(title);
        // The store often omits the manufacturer from a complete model title:
        // "Умная колонка Станция Мини 3 Про с Алисой". Product family,
        // edition and requested colour were already validated by exact().
        // Do not let a generic NLI classifier discard this known model.
        if(wanted.contains("яндекс станция мини 3 про") &&
           (actual.contains("станция мини 3 про") ||
            (actual.contains("мини 3 про") &&
             (actual.contains("yandex")||actual.contains("яндекс")))) &&
           (actual.contains("колонка")||actual.contains("станция")||
            actual.contains("яндекс")||actual.contains("yandex")))return true;
        // A full requested model embedded in a longer retail heading
        // ("Умная колонка ... 18 Вт") is a literal match as well.
        // ScanEngine.exact rejects accessories, wrong model variants,
        // wrong requested colors and used/refurbished devices first.
        return actual.equals(wanted)||actual.startsWith(wanted+" ")||
            actual.endsWith(" "+wanted)||actual.contains(" "+wanted+" ");
    }

    static boolean confirms(String query,String title,
            float entailSame,float entailAccessory,float entailDifferent) {
        if(!ScanEngine.exact(query,title))return false;
        if(!Float.isFinite(entailSame)||!Float.isFinite(entailAccessory)||
           !Float.isFinite(entailDifferent))return false;
        // Raw NLI probabilities for product listings are often under 0.5
        // despite being correct. Calibrate relative to alternative hypotheses.
        return entailSame>=0.20f &&
               entailSame-entailAccessory>=0.12f &&
               entailSame-entailDifferent>=0.10f;
    }
}
