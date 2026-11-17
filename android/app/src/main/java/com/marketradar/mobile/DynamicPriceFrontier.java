package com.marketradar.mobile;

/**
 * Shared live frontier across concurrent marketplace scans.
 *
 * A short-delivery verified product may lower the global ceiling; a cheap
 * 8-day product may NEVER suppress tomorrow's eligible higher-price product.
 * Unknown-delivery listings remain unpruned until their delivery is resolved.
 */
final class DynamicPriceFrontier {
    private int observed=Integer.MAX_VALUE;
    private int confirmed=Integer.MAX_VALUE;
    private DeliveryPolicy policy=DeliveryPolicy.standard();

    synchronized void reset(){
        observed=Integer.MAX_VALUE;
        confirmed=Integer.MAX_VALUE;
    }

    synchronized void configure(DeliveryPolicy policy){
        this.policy=policy==null?DeliveryPolicy.standard():policy;
        reset();
    }

    synchronized DeliveryPolicy policy(){return policy;}

    synchronized boolean observe(int listingPrice){
        if(listingPrice<=0||listingPrice>=observed)return false;
        observed=listingPrice;
        return true;
    }

    synchronized boolean confirm(int verifiedPrice){
        return confirm(verifiedPrice,"завтра");
    }

    synchronized boolean confirm(int verifiedPrice,String deliveryHint){
        // Confirmed threshold is only comparable for deliveries that meet
        // the requested deadline. A slower exception has a separate rule.
        if(!policy.timely(deliveryHint)||
           verifiedPrice<=0||verifiedPrice>=confirmed)return false;
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
        return deferHigher(listingPrice,"завтра");
    }

    synchronized boolean deferHigher(int listingPrice,String deliveryHint){
        if(!policy.potentiallyEligible(deliveryHint))return true;
        if(listingPrice<=0||confirmed==Integer.MAX_VALUE)return false;
        int days=policy.days(deliveryHint);
        if(days<0)return false;  // unknown date cannot be safely pruned
        if(days<=policy.maxDays)return listingPrice>confirmed;
        // The optional long-delivery rule is an EXCEPTION and only applies
        // if the late offer is X% cheaper than the best timely offer.
        if(policy.allowSlowDiscount)
            return (long)listingPrice*100 >
                (long)confirmed*(100-policy.slowDiscountPercent);
        return true;
    }

    static int nextUnpruned(java.util.List<ScanEngine.Candidate> ordered,
                            int index,DynamicPriceFrontier frontier){
        int next=index;
        while(next<ordered.size() &&
              frontier.deferHigher(ordered.get(next).listingPrice,
                                   ordered.get(next).deliveryHint))next++;
        return next;
    }
}
