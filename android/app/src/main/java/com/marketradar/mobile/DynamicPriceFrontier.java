package com.marketradar.mobile;

/**
 * Shared live frontier across concurrent marketplace scans.
 *
 * A short-delivery verified product may lower the global ceiling; a cheap
 * 8-day product may NEVER suppress tomorrow's eligible higher-price product.
 * Unknown-delivery listings priced ABOVE a confirmed timely offer can
 * safely be deferred: the arrival date cannot make a higher price cheaper.
 */
final class DynamicPriceFrontier {
    private int observed=Integer.MAX_VALUE;
    private int confirmed=Integer.MAX_VALUE;
    private DeliveryPolicy policy=DeliveryPolicy.standard();
    // Independent confirmed thresholds per website. A price from Ozon
    // must never suppress WB cards: native WB app prices, account discounts,
    // and the scraped web listing can be materially different.
    private final java.util.Map<String,Integer> confirmedPerShop=
        new java.util.HashMap<>();

    synchronized void reset(){
        observed=Integer.MAX_VALUE;
        confirmed=Integer.MAX_VALUE;
        confirmedPerShop.clear();
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
        return confirm(verifiedPrice,deliveryHint,null);
    }
    synchronized boolean confirm(int verifiedPrice,String deliveryHint,Shop shop){
        if(!policy.timely(deliveryHint)||verifiedPrice<=0)return false;
        boolean improved=verifiedPrice<confirmed;
        confirmed=Math.min(confirmed,verifiedPrice);
        if(shop!=null){
            int existing=confirmedPerShop.getOrDefault(shop.id,Integer.MAX_VALUE);
            confirmedPerShop.put(shop.id,Math.min(existing,verifiedPrice));
        }
        return improved;
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
        if(days<0)return listingPrice>confirmed; // price ceiling still applies
        if(days<=policy.maxDays)return listingPrice>confirmed;
        // The optional long-delivery rule is an EXCEPTION and only applies
        // if the late offer is X% cheaper than the best timely offer.
        if(policy.allowSlowDiscount)
            return (long)listingPrice*100 >
                (long)confirmed*(100-policy.slowDiscountPercent);
        return true;
    }

    synchronized boolean deferHigher(int listingPrice,String deliveryHint,Shop shop){
        if(shop==null)return deferHigher(listingPrice,deliveryHint);
        int shopBest=confirmedPerShop.getOrDefault(shop.id,Integer.MAX_VALUE);
        // There is NO safe confirmed price baseline for this marketplace yet.
        if(shopBest==Integer.MAX_VALUE)return false;
        if(!policy.potentiallyEligible(deliveryHint))return true;
        int days=policy.days(deliveryHint);
        if(listingPrice<=0)return false;
        if(days<0)return listingPrice>shopBest;
        if(days<=policy.maxDays)return listingPrice>shopBest;
        if(policy.allowSlowDiscount)
            return (long)listingPrice*100 >
                (long)shopBest*(100-policy.slowDiscountPercent);
        return true;
    }

    static int nextUnpruned(java.util.List<ScanEngine.Candidate> ordered,
                            int index,DynamicPriceFrontier frontier){
        return nextUnpruned(ordered,index,frontier,null);
    }
    static int nextUnpruned(java.util.List<ScanEngine.Candidate> ordered,
                            int index,DynamicPriceFrontier frontier,Shop shop){
        int next=index;
        while(next<ordered.size() &&
              (shop==null?
                  frontier.deferHigher(ordered.get(next).listingPrice,
                                       ordered.get(next).deliveryHint):
                  frontier.deferHigher(ordered.get(next).listingPrice,
                                       ordered.get(next).deliveryHint,shop)))next++;
        return next;
    }
}
