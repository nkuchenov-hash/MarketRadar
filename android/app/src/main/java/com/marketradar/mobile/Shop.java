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
        if(id.equals("ozon"))return searchBase+encoded+"&sorting=price";
        return searchBase+encoded;
    }
    String plainSearchUrl(String query){
        return searchBase+URLEncoder.encode(query,StandardCharsets.UTF_8);
    }
    boolean priceAscendingRequested(String currentUrl){
        if(!allowed(currentUrl))return false;
        try{
            String raw=new java.net.URI(currentUrl).getRawQuery();
            if(raw==null)return false;
            String parameters=java.net.URLDecoder.decode(raw,StandardCharsets.UTF_8)
                .toLowerCase(java.util.Locale.ROOT);
            if(id.equals("wb"))return parameters.contains("sort=priceup");
            if(id.equals("ym"))return parameters.contains("how=aprice");
            if(id.equals("ozon"))return parameters.contains("sorting=price");
            return false;
        }catch(Exception ignored){return false;}
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
    static boolean isTransientDocument(String url){
        if(url==null)return true;
        String value=url.trim().toLowerCase(java.util.Locale.ROOT);
        return value.isEmpty()||value.equals("about:blank")||
            value.equals("about:srcdoc");
    }
    static String safeHost(String url){
        if(isTransientDocument(url))return "пустая страница";
        try{
            String host=new java.net.URI(url).getHost();
            return host==null?"неизвестный адрес":host;
        }catch(Exception ignored){return "неизвестный адрес";}
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
    boolean isLoginUrl(String url){
        if(url==null)return false;
        try{
            java.net.URI uri=new java.net.URI(url);
            if(!"https".equalsIgnoreCase(uri.getScheme()))return false;
            String host=uri.getHost();
            if(host==null)return false;
            host=host.toLowerCase(java.util.Locale.ROOT);
            String path=uri.getPath()==null?"":uri.getPath().toLowerCase(java.util.Locale.ROOT);
            if(id.equals("ozon") &&
               (host.equals("id.ozon.ru")||host.equals("login.ozon.ru")))
                return true;
            if(id.equals("wb") &&
               (host.equals("id.wildberries.ru")||
                host.equals("login.wildberries.ru")||
                host.equals("id.wb.ru")))return true;
            if(id.equals("ym") &&
               (host.equals("passport.yandex.ru")||
                host.equals("id.yandex.ru")||
                host.equals("oauth.yandex.ru")))return true;
            return allowed(url)&&(
                path.equals("/login")||path.equals("/auth")||
                path.startsWith("/login/")||path.startsWith("/auth/"));
        }catch(Exception ignored){return false;}
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
