package com.marketradar.mobile;

import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

/**
 * A shared cookie jar is necessary for sessions but NOT proof of login.
 * Verify actual page evidence without ever handling passwords, tokens or PII.
 */
public final class SessionProbeTest {
    @Test public void noAccountSignalsMustNotBeCalledLoggedIn() throws Exception {
        Shop ozon=Shop.byId("ozon");
        SessionProbe.Verdict evidence=SessionProbe.evaluate(ozon,
            new JSONObject().put("url","https://www.ozon.ru/search/?text=mini")
                .put("signOut",false).put("loginForm",false));
        assertEquals(SessionProbe.State.UNKNOWN,evidence.state);
    }

    @Test public void explicitLogoutControlConfirmsActiveWebPage() throws Exception {
        Shop wb=Shop.byId("wb");
        SessionProbe.Verdict evidence=SessionProbe.evaluate(wb,
            new JSONObject().put("url","https://www.wildberries.ru/")
                .put("signOut",true).put("loginForm",false));
        assertEquals(SessionProbe.State.SIGNED_IN,evidence.state);
    }

    @Test public void signInFormOverridesStaleLogoutSignal() throws Exception {
        Shop market=Shop.byId("ym");
        SessionProbe.Verdict evidence=SessionProbe.evaluate(market,
            new JSONObject().put("url","https://market.yandex.ru/")
                .put("signOut",true).put("loginForm",true));
        assertEquals(SessionProbe.State.SIGN_IN_REQUIRED,evidence.state);
    }

    @Test public void marketplaceLoginRedirectsMustBeReportedAsAuthentication() throws Exception {
        assertTrue(Shop.byId("ozon").isLoginUrl("https://id.ozon.ru/"));
        assertTrue(Shop.byId("wb").isLoginUrl("https://id.wildberries.ru/"));
        assertTrue(Shop.byId("ym").isLoginUrl("https://passport.yandex.ru/auth"));
        assertTrue(Shop.byId("ym").isLoginUrl("https://market.yandex.ru/login"));
        assertEquals(SessionProbe.State.SIGN_IN_REQUIRED,
            SessionProbe.evaluate(Shop.byId("ym"),
                "https://passport.yandex.ru/auth",new JSONObject()).state);
    }

    @Test public void thirdPartyOrLookalikeUrlMustNeverClaimAccountLogin() throws Exception {
        Shop ozon=Shop.byId("ozon");
        assertFalse(ozon.isLoginUrl("https://id.ozon.ru.badhost.example/auth"));
        assertFalse(Shop.byId("ym").isLoginUrl(
            "https://passport.yandex.ru.example.com/auth"));
        assertEquals(SessionProbe.State.UNKNOWN,
            SessionProbe.evaluate(ozon,
                new JSONObject().put("url","https://evil.example/")
                    .put("signOut",true)).state);
        assertEquals(SessionProbe.State.UNKNOWN,
            SessionProbe.evaluate(ozon,new JSONObject().put("error","unknown")).state);
    }

    @Test public void embeddedScriptMustNeverExtractPrivateAccountValues(){
        assertFalse(SessionProbe.SCRIPT.contains("document.cookie"));
        assertFalse(SessionProbe.SCRIPT.contains("localStorage"));
        assertFalse(SessionProbe.SCRIPT.contains("sessionStorage"));
        assertFalse(SessionProbe.SCRIPT.contains("input.value"));
        assertTrue(SessionProbe.SCRIPT.contains("signOut"));
        assertTrue(SessionProbe.SCRIPT.contains("loginForm"));
    }
}
