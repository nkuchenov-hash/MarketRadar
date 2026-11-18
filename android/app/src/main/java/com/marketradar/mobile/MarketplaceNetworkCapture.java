package com.marketradar.mobile;

import android.webkit.WebView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.util.Collections;

/**
 * Reads first-party search data already delivered to the user's WebView.
 * No extra requests, token extraction, challenge bypass, or external upload.
 * DOM extraction remains available when document-start scripting is unsupported.
 */
final class MarketplaceNetworkCapture {
    private MarketplaceNetworkCapture() {}

    private static final String SCRIPT = """
        (() => {
          if (window.__marketRadarNetworkCaptureInstalled) return;
          window.__marketRadarNetworkCaptureInstalled = true;
          window.__marketRadarNetworkCards = [];
          window.__marketRadarNetworkResponses = 0;
          const isSearchResponse = url => {
            const value = String(url || '');
            return value.includes('wildberries.ru/') &&
                   value.includes('/exactmatch/') && value.includes('/search');
          };
          const capture = json => {
            try {
              const products = json?.data?.products || json?.products ||
                               json?.data?.data?.products;
              if (!Array.isArray(products)) return;
              window.__marketRadarNetworkResponses++;
              for (const product of products.slice(0, 500)) {
                const id = Number(product?.id || product?.nmId);
                const title = String(product?.name || product?.title || '').trim();
                if (!Number.isSafeInteger(id) || id <= 0 || !title) continue;
                const values = [];
                const put = value => {
                  const n = Number(value);
                  // WB catalog price fields are denominated in kopecks.
                  if (Number.isFinite(n) && n >= 10000 && n <= 5000000000)
                    values.push(Math.round(n / 100));
                };
                for (const size of (product.sizes || [])) {
                  put(size?.price?.total);
                  put(size?.price?.product);
                }
                put(product.salePriceU);
                put(product.priceU);
                if (!values.length) continue;
                const price = Math.min(...values);
                const url = 'https://www.wildberries.ru/catalog/' + id + '/detail.aspx';
                const cards = window.__marketRadarNetworkCards;
                const old = cards.find(x => x.url === url);
                if (old) {
                  if (price < old.price) old.price = price;
                } else if (cards.length < 500) {
                  cards.push({url, title, price});
                }
              }
            } catch (ignored) {}
          };
          const originalFetch = window.fetch;
          if (typeof originalFetch === 'function') {
            window.fetch = function(...args) {
              const result = originalFetch.apply(this,args);
              Promise.resolve(result).then(response => {
                try {
                  if (response && response.ok && isSearchResponse(response.url))
                    response.clone().json().then(capture).catch(() => {});
                } catch (ignored) {}
              }).catch(() => {});
              return result;
            };
          }
          if (window.XMLHttpRequest) {
            const originalOpen = XMLHttpRequest.prototype.open;
            const originalSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(method,url,...rest) {
              this.__marketRadarSearchResponse = isSearchResponse(url);
              return originalOpen.call(this,method,url,...rest);
            };
            XMLHttpRequest.prototype.send = function(...args) {
              if (this.__marketRadarSearchResponse) {
                this.addEventListener('load', () => {
                  try {
                    const payload = this.responseType === 'json' ? this.response :
                        (this.responseType === '' || this.responseType === 'text') ?
                        JSON.parse(this.responseText) : null;
                    if (payload && this.status === 200) capture(payload);
                  } catch (ignored) {}
                });
              }
              return originalSend.apply(this,args);
            };
          }
        })();
        """;

    static void install(WebView browser) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            return;
        try {
            WebViewCompat.addDocumentStartJavaScript(browser, SCRIPT,
                Collections.singleton("https://*.wildberries.ru"));
        } catch (RuntimeException ignored) {
            // Compatibility fallback: scan the ordinary visible HTML instead.
        }
    }
}
