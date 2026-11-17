package com.marketradar.mobile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

final class Shop {
    final String id, name, domain, home, searchBase;
    final int accent;
    Shop(String id, String name, String domain, String home, String searchBase, int accent) {
        this.id=id; this.name=name; this.domain=domain; this.home=home; this.searchBase=searchBase; this.accent=accent;
    }
    String searchUrl(String query) {
        String encoded=URLEncoder.encode(query, StandardCharsets.UTF_8);
        // Prefer price ordering where the native marketplace UI supports it.
        // Match IDs and product type anyway; cheap accessories are not offers.
        if(id.equals("wb"))return searchBase+encoded+"&sort=priceup";
        if(id.equals("ym"))return searchBase+encoded+"&how=aprice";
        return searchBase+encoded;
    }
    static final List<Shop> ALL=Arrays.asList(
        new Shop("ozon","Ozon","ozon.ru","https://www.ozon.ru/",
            "https://www.ozon.ru/search/?text=",0xFF3684F6),
        new Shop("wb","Wildberries","wildberries.ru","https://www.wildberries.ru/",
            "https://www.wildberries.ru/catalog/0/search.aspx?search=",0xFFC05BDE),
        new Shop("ym","Яндекс Маркет","market.yandex.ru","https://market.yandex.ru/",
            "https://market.yandex.ru/search?text=",0xFFFFD153)
    );
    static Shop byId(String id) {
        for (Shop shop:ALL) if(shop.id.equals(id))return shop;
        return null;
    }
    boolean allowed(String url) {
        if(url==null)return false;
        try {
            java.net.URI uri = new java.net.URI(url);
            String hostname=uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())&&hostname!=null
                &&(hostname.equalsIgnoreCase(domain)||hostname.toLowerCase().endsWith("."+domain));
        }catch(Exception e){return false;}
    }
    boolean productLink(String url) {
        if(!allowed(url))return false;
        try{
            String path = new java.net.URI(url).getPath().toLowerCase();
            if(id.equals("ozon")) return path.contains("/product/");
            if(id.equals("wb")) return path.matches(".*?/catalog/\\d+/detail.*");
            return path.contains("/product--")||path.contains("/product/")||
                    path.contains("/card/")||path.contains("/offer/");
        }catch(Exception e){return false;}
    }
}
