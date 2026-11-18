package com.marketradar.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/**
 * Observes actual DOM product-tile order instead of trusting a query
 * parameter as proof that the marketplace applied a price ascending sort.
 * This is evidence for diagnostics, NOT grounds to skip unseen products.
 */
final class PriceOrderProbe {
    private final Set<String> sampledUrls=new HashSet<>();
    private int previousPrice=0;
    private int inspected=0;
    private int falls=0;
    private int snapshots=0;

    void reset(){
        sampledUrls.clear();
        previousPrice=0;
        inspected=0;
        falls=0;
        snapshots=0;
    }
    void observe(JSONArray links,Shop shop){
        snapshots++;
        if(links==null||shop==null)return;
        for(int i=0;i<links.length();i++){
            JSONObject tile=links.optJSONObject(i);
            if(tile==null)continue;
            String url=tile.optString("url","");
            if(!shop.productLink(url))continue;
            int price=ScanEngine.readCardPrice(tile.optString("context",""));
            if(price<=0||!sampledUrls.add(url))continue;
            // A substantially cheaper new listing later in the grid
            // disproves a reliable ascending order for this session.
            if(previousPrice>0&&price*100L<previousPrice*98L)falls++;
            previousPrice=price;
            inspected++;
        }
    }
    boolean ascendingObserved(){
        // Five viewports and twenty distinct priced items are conservative
        // minimum evidence. Even then the site may inject promotions or
        // offer personalized payment prices, so never infer completeness.
        return snapshots>=5&&inspected>=20&&falls==0;
    }
    boolean ascendingContradicted(){return falls>0;}
    int inspected(){return inspected;}
    int falls(){return falls;}
    String description(boolean requested){
        if(!requested)return "Сортировка по цене не включена · проверяем всю выдачу";
        if(ascendingContradicted())
            return "Сортировка по цене не соблюдается · продолжаем поиск";
        if(ascendingObserved())
            return "Возрастание цены наблюдается · полнота ещё не доказана";
        return "Возрастание цены запрошено · ещё не подтверждено";
    }
}
