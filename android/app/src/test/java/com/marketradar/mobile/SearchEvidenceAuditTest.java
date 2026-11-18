package com.marketradar.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SearchEvidenceAuditTest {
    @Test public void identifiesWhetherLowYandexPriceReachedRawFeedOrWasFiltered()
            throws Exception {
        Shop ym=Shop.byId("ym");
        SearchEvidenceAudit audit=new SearchEvidenceAudit();
        String cheapUrl="https://market.yandex.ru/card/yandex-stantsiya-mini-3-pro/4620658532?promo=example";
        JSONArray result=new JSONArray()
            .put(new JSONObject().put("url",cheapUrl)
                .put("title","Умная колонка Яндекс Станция Мини 3 Про")
                .put("context","11\u2006687\u2006₽ Пэй\n21 ноября"))
            .put(new JSONObject().put("url",
                "https://market.yandex.ru/card/mini-3/4620658001")
                .put("title","Яндекс Станция Мини 3 с Алисой")
                .put("context","9\u2006757\u2006₽ Пэй\n20 ноября"))
            .put(new JSONObject().put("url",
                "https://market.yandex.ru/card/mini-3-pro/4620658002")
                .put("title","Умная колонка Станция Мини 3 Про")
                .put("context","12\u2006447\u2006₽ Пэй\n21 ноября"));
        audit.observe(result,ym,"Яндекс Станция Мини 3 Про");
        audit.observe(result,ym,"Яндекс Станция Мини 3 Про");
        audit.stage(cheapUrl,"ПОДТВЕРЖДЕНА · 11687 ₽");
        String report=audit.summary();
        assertTrue(report,report.contains("11687 ₽"));
        assertTrue(report,report.contains("ПОДТВЕРЖДЕНА"));
        assertTrue(report,report.contains("ID 4620658532"));
        assertTrue(report,report.contains("9757 ₽"));
        assertTrue(report,report.contains("исключённых") ||
            report.contains("исключённые"));
        assertTrue(report,report.contains("уникальных URL 3"));
        assertFalse(report,report.contains("promo=example"));
    }
}
