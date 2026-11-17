package com.marketradar.mobile;

/**
 * Shared across concurrent marketplace scans.
 * An observed listing price is only an OPTIMISTIC TARGET; it is not proof that
 * the offer is the requested model or available for purchase at that price.
 * We therefore DEFER higher-priced detail pages until the low-priced item
 * passes on-device AI identity verification; if the low-priced item fails,
 * candidates at higher prices remain eligible as fallback.
 *
 * A confirmed price is the local AI-confirmed product's scraped offer price,
 * not a checkout guarantee. Store pages may change the payable total.
 */
final class DynamicPriceFrontier {
    private int observed=Integer.MAX_VALUE;
    private int confirmed=Integer.MAX_VALUE;

    synchronized void reset(){
        observed=Integer.MAX_VALUE;
        confirmed=Integer.MAX_VALUE;
    }

    synchronized boolean observe(int listingPrice){
        if(listingPrice<=0||listingPrice>=observed)return false;
        observed=listingPrice;
        return true;
    }

    synchronized boolean confirm(int verifiedPrice){
        if(verifiedPrice<=0||verifiedPrice>=confirmed)return false;
        confirmed=verifiedPrice;
        return true;
    }

    synchronized int observedCeiling(){
        return observed==Integer.MAX_VALUE?0:observed;
    }

    synchronized int confirmedCeiling(){
        return confirmed==Integer.MAX_VALUE?0:confirmed;
    }

    synchronized boolean deferHigher(int listingPrice){
        return listingPrice>0&&confirmed!=Integer.MAX_VALUE&&listingPrice>confirmed;
    }

    static int nextUnpruned(java.util.List<ScanEngine.Candidate> ordered,
                            int index,DynamicPriceFrontier frontier){
        int next=index;
        while(next<ordered.size() &&
              frontier.deferHigher(ordered.get(next).listingPrice))next++;
        return next;
    }
}
