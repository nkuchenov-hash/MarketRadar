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
        return !wanted.isBlank()&&wanted.equals(ScanEngine.norm(title))&&
            ScanEngine.exact(query,title);
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
