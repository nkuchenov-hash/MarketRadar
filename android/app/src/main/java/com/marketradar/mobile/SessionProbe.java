package com.marketradar.mobile;

import org.json.JSONObject;

/**
 * Observes the ACTUAL page rendered inside MarketRadar's authenticated
 * WebView. Cookies alone never prove login. Only explicit account UI
 * signals can confirm a session; everything else is UNKNOWN.
 *
 * Never reads or stores account IDs, names, phone numbers, cookie values,
 * addresses, passwords or tokens.
 */
final class SessionProbe {
    enum State { SIGNED_IN, SIGN_IN_REQUIRED, UNKNOWN }

    static final class Verdict {
        final State state;
        final String reason;
        Verdict(State state,String reason){this.state=state;this.reason=reason;}
    }

    static final String SCRIPT = """
        (() => {
          try{
            const visible=e=>{if(!e)return false;
              const box=e.getBoundingClientRect();
              const style=getComputedStyle(e);
              return box.width>0&&box.height>0&&
                style.visibility!=='hidden'&&style.display!=='none';
            };
            const controls=[...document.querySelectorAll('button,a,[role="button"]')];
            const label=e=>(e.innerText||e.getAttribute('aria-label')||
              e.getAttribute('title')||'').trim().replace(/\\s+/g,' ').slice(0,100);
            const signOut=controls.some(e=>visible(e)&&
              /^(?:выйти|выход|выйти из аккаунта|log\\s*out|sign\\s*out)$/i.test(label(e)));
            const password=!![...document.querySelectorAll('input[type="password"]')]
              .find(visible);
            const phone=!![...document.querySelectorAll('input[type="tel"]')]
              .find(visible);
            const loginHeading=!![...document.querySelectorAll('h1,h2,[role="dialog"]')]
              .find(e=>visible(e)&&/вход|войти|авторизац|log\\s*in|sign\\s*in/i
                .test((e.innerText||'').trim().slice(0,130)));
            const loginForm=password||(phone&&loginHeading);
            return JSON.stringify({
              url:location.href,
              signOut:signOut,
              loginForm:loginForm
            });
          }catch(e){return JSON.stringify({error:'probe unavailable'});}
        })()
        """;

    static Verdict evaluate(Shop shop,String url,JSONObject signals){
        if(shop==null||url==null)
            return new Verdict(State.UNKNOWN,"Не удалось проверить страницу");
        if(shop.isLoginUrl(url))
            return new Verdict(State.SIGN_IN_REQUIRED,
                "Переадресовано на страницу авторизации");
        if(!shop.allowed(url))
            return new Verdict(State.UNKNOWN,"Открыта посторонняя страница");
        if(signals==null||signals.has("error"))
            return new Verdict(State.UNKNOWN,"Признаки входа недоступны");
        if(signals.optBoolean("signOut",false) &&
            !signals.optBoolean("loginForm",false))
            return new Verdict(State.SIGNED_IN,
                "На странице есть управление выходом из аккаунта");
        if(signals.optBoolean("loginForm",false))
            return new Verdict(State.SIGN_IN_REQUIRED,
                "На странице открыта форма входа");
        return new Verdict(State.UNKNOWN,
            "На странице нет надёжных признаков авторизации");
    }

    static Verdict evaluate(Shop shop,JSONObject signals){
        return evaluate(shop,signals==null?"":signals.optString("url",""),signals);
    }
}
