"""Server-side Chromium search. Preserve every observed product before ranking.

Only public pages are used. Blocked or partial pages remain explicitly partial.
No account or browser cookies are transported from Android.
"""
from __future__ import annotations

import asyncio
import os
import re
from urllib.parse import urljoin, urlparse, parse_qsl, urlencode, urlunparse
from .core import host_allowed, market_of, is_product_link, canonical, BLOCK_WORDS


def available():
    if os.getenv("CLOUD_BROWSER_ENABLED", "false").lower() not in ("1", "true", "yes"):
        return False, "Облачный браузер ещё не развёрнут на сервере"
    try:
        from playwright.async_api import async_playwright  # noqa: F401
    except ImportError:
        return False, "На сервере не установлен Playwright"
    return True, "Облачный Chromium: публичный поиск по цене"


def sorted_search_url(url: str) -> str:
    """Request storefront price sorting; verify/record it separately."""
    market = market_of(url)
    p = urlparse(url)
    if not market or not (p.path.endswith("/search") or "search" in p.path):
        return url
    params = dict(parse_qsl(p.query, keep_blank_values=True))
    if market == "Яндекс Маркет":
        params["how"] = "aprice"
    elif market == "Wildberries":
        params["sort"] = "priceup"
    elif market == "Ozon":
        params["sorting"] = "price"
    return urlunparse(p._replace(query=urlencode(params)))


def _amount(value):
    if isinstance(value, dict):
        for key in ("value", "amount", "price", "minPrice"):
            if key in value:
                return _amount(value[key])
        return 0
    if isinstance(value, bool):
        return 0
    if isinstance(value, (int, float)):
        n = int(value)
        return n if 100 <= n <= 50_000_000 else 0
    if isinstance(value, str):
        cleaned = re.sub(r"[^0-9]", "", value)
        if not cleaned:
            return 0
        n = int(cleaned)
        return n if 100 <= n <= 50_000_000 else 0
    return 0


def network_products(payload, origin: str, max_nodes=12000):
    """Extract explicit product URLs without inventing URL from an opaque ID."""
    market = market_of(origin)
    products = []
    visited = 0
    todo = [(payload, 0)]
    while todo and visited < max_nodes:
        node, depth = todo.pop()
        visited += 1
        if isinstance(node, list):
            if depth < 20:
                todo.extend((x, depth + 1) for x in node[:1000])
            continue
        if not isinstance(node, dict):
            continue
        if depth < 20:
            todo.extend((x, depth + 1) for x in node.values() if isinstance(x, (list, dict)))
        title = next((node.get(k) for k in ("title", "name", "productName", "displayName")
                      if isinstance(node.get(k), str) and len(node[k]) > 7), "")
        if not title:
            continue
        raw = next((node.get(k) for k in ("url", "productUrl", "link", "shareUrl", "webUrl")
                    if isinstance(node.get(k), str) and node[k]), "")
        if not raw:
            continue
        candidate_url = urljoin(origin, raw)
        if not is_product_link(market, candidate_url):
            continue
        amount = 0
        for key in ("discountedPrice", "finalPrice", "salePrice", "price", "minPrice"):
            if key in node:
                amount = _amount(node[key])
                if amount:
                    break
        products.append({"url": canonical(candidate_url), "title": title[:240],
                         "context": "", "price": amount, "source": "network"})
    return products, visited


# Independent of site CSS frameworks. Each product link is always preserved,
# even when price and title cannot be associated with its tile.
_DOM_SNAPSHOT = r"""() => {
    const products = [];
    const path = /\/(?:product\/|product--|card\/|offer\/|catalog\/\d+\/detail)/i;
    const price = /(\d{1,3}(?:[\s\u00a0\u202f]\d{3})+|\d{3,6})\s*(?:₽|руб)/gi;
    const productLinks = [...document.querySelectorAll('a[href]')]
        .filter(a => { try {return new URL(a.href).protocol === 'https:' &&
            path.test(new URL(a.href).pathname);} catch(e) {return false;} });
    for (const a of productLinks) {
        let tile = a.closest('[data-testid*="product"],[data-nm-id],article,li,.product-card');
        if (!tile) {
            let node = a;
            for(let i = 0; i < 8 && node.parentElement; i++) {
                node = node.parentElement;
                const txt = (node.innerText || '').trim();
                const links = node.querySelectorAll('a[href]').length;
                price.lastIndex = 0;
                if(txt.length > 12 && txt.length < 2200 &&
                    (price.test(txt) || links > 1)) {tile = node; break;}
                price.lastIndex = 0;
            }
        }
        const context = (tile?.innerText || a.parentElement?.innerText || a.innerText || '')
            .trim().slice(0,2200);
        const title = (a.getAttribute('title') || a.getAttribute('aria-label') ||
            a.innerText || a.querySelector('img')?.alt ||
            tile?.querySelector('img')?.alt || '').trim().slice(0,300);
        price.lastIndex = 0;
        const matches = [...context.matchAll(price)];
        const prices = matches.map(m => Number(m[1].replace(/\D/g,'')))
            .filter(n => n >= 100 && n <= 50000000);
        products.push({url:a.href, title, context,
            price:prices.length ? Math.min(...prices) : 0,
            source:'dom'});
    }
    const body = (document.body?.innerText || '').slice(0,120000);
    price.lastIndex = 0;
    const prices = [...body.matchAll(price)].map(m => Number(m[1].replace(/\D/g,'')))
        .filter(n => n >= 100 && n <= 50000000);
    const scroll = document.scrollingElement || document.documentElement;
    const controls = [...document.querySelectorAll('a[rel="next"],nav a[href],a[aria-label],button,[role="button"]')]
        .filter(el => /^(?:показать ещё|показать еще|загрузить ещё|загрузить еще|следующая|далее|next|›)$/i
            .test((el.innerText || el.getAttribute('aria-label') || '').trim()))
        .slice(0,5).map(el => ({tag:el.tagName, url:el.href || '',
            label:(el.innerText || '').slice(0,70)}));
    return {title:document.title || '', body,
        products, visible_prices:[...new Set(prices)].sort((a,b)=>a-b).slice(0,100),
        scroll_y:scroll.scrollTop, scroll_height:scroll.scrollHeight,
        viewport:window.innerHeight, controls};
}"""


class CloudBrowser:
    """Limited parallel Chromium workers, full listing scans and evidence ledger."""
    _slots = asyncio.Semaphore(2)

    def __init__(self):
        self.playwright = self.browser = self.context = None
        self.acquired = False

    async def start(self):
        ok, reason = available()
        if not ok:
            raise RuntimeError(reason)
        await self._slots.acquire()
        self.acquired = True
        try:
            from playwright.async_api import async_playwright
            self.playwright = await async_playwright().start()
            cdp = os.getenv("MARKETRADAR_CHROMIUM_CDP_URL","").strip()
            if cdp:
                # A persistent Chromium worker may run in a network where
                # storefronts actually serve public pages. Endpoint credentials
                # are server-side secrets and are never logged or sent to mobile.
                if not (cdp.startswith("wss://") or
                        cdp.startswith("ws://127.0.0.1:") or
                        cdp.startswith("http://127.0.0.1:")):
                    raise RuntimeError("Удалённый Chromium должен использовать WSS")
                self.browser = await self.playwright.chromium.connect_over_cdp(
                    cdp, timeout=30000)
            else:
                self.browser = await self.playwright.chromium.launch(headless=True,
                                          args=["--disable-dev-shm-usage"])
            self.context = await self.browser.new_context(
                locale="ru-RU", viewport={"width": 1280, "height": 900},
                service_workers="allow")
        except Exception:
            await self.close()
            raise

    async def close(self):
        try:
            if self.context:
                await self.context.close()
            if self.browser:
                await self.browser.close()
            if self.playwright:
                await self.playwright.stop()
        finally:
            self.context = self.browser = self.playwright = None
            if self.acquired:
                self.acquired = False
                self._slots.release()

    async def snapshot(self, url: str) -> dict:
        if not host_allowed(url):
            raise ValueError("Недопустимый URL для облачного браузера")
        if not self.context:
            return {"state": "error", "url": url, "text": "", "links": []}
        search = "search" in urlparse(url).path.lower()
        target = sorted_search_url(url) if search else url
        page = await self.context.new_page()
        ledger = {}
        raw_count = 0
        network_count = 0
        visited_snapshots = 0
        blocked_http = []
        network_tasks = set()
        page_text = ""
        visual_prices = set()
        title = ""
        complete = False
        diagnostic = ""
        last_snapshot = {}
        visited_pages = {target}
        page_count = 1
        load_more_count = 0
        max_scrolls = int(os.getenv("CLOUD_SEARCH_SCROLLS", "28")) if search else 0
        max_scrolls = min(80, max(0, max_scrolls))

        def preserve(item):
            nonlocal raw_count
            raw_count += 1
            raw_url = item.get("url", "")
            if not is_product_link(market_of(target), raw_url):
                return
            key = canonical(raw_url)
            existing = ledger.get(key)
            amount = _amount(item.get("price", 0))
            if existing is None:
                ledger[key] = {"url": key, "title": str(item.get("title",""))[:300],
                               "context": str(item.get("context",""))[:1100],
                               "price": amount, "source": item.get("source","unknown")}
            else:
                if len(str(item.get("title",""))) > len(existing["title"]):
                    existing["title"] = str(item["title"])[:300]
                if len(str(item.get("context",""))) > len(existing["context"]):
                    existing["context"] = str(item["context"])[:1100]
                if amount and (not existing["price"] or amount < existing["price"]):
                    existing["price"] = amount
                    existing["source"] = item.get("source", existing["source"])

        async def parse_response(response):
            nonlocal network_count
            try:
                resource = response.request.resource_type
                if response.status in (403, 429, 498, 503) and (
                    resource == "document" or (market_of(response.url) == market_of(target)
                    and resource in ("xhr", "fetch") and
                    any(part in response.url.lower() for part in
                        ("search", "catalog", "products", "feed")))):
                    blocked_http.append(response.status)
                if not search or market_of(response.url) != market_of(target):
                    return
                if resource not in ("xhr", "fetch"):
                    return
                headers = response.headers
                if "json" not in headers.get("content-type","").lower():
                    return
                if int(headers.get("content-length","0") or 0) > 2_500_000:
                    return
                data = await response.json()
                found, _ = network_products(data, response.url)
                network_count += len(found)
                for item in found:
                    preserve(item)
            except Exception:
                pass

        def on_response(response):
            task = asyncio.create_task(parse_response(response))
            network_tasks.add(task)
            task.add_done_callback(network_tasks.discard)

        page.on("response", on_response)
        try:
            response = await page.goto(target, wait_until="domcontentloaded", timeout=35000)
            await page.wait_for_timeout(1800)
            if not host_allowed(page.url) or market_of(page.url) != market_of(url):
                return {"state": "error", "url": url, "title": "", "text": "",
                        "links": [], "diagnostic": "Недопустимый редирект"}
            unchanged = 0
            previous = (-1, -1, -1)
            for step in range(max_scrolls + 1):
                try:
                    snapshot = await page.evaluate(_DOM_SNAPSHOT)
                except Exception as e:
                    diagnostic = type(e).__name__
                    break
                last_snapshot = snapshot
                visited_snapshots += 1
                title = snapshot.get("title", "")
                if not page_text or len(snapshot.get("body","")) > len(page_text):
                    page_text = snapshot.get("body","")
                visual_prices.update(snapshot.get("visible_prices", []))
                for product in snapshot.get("products", []):
                    preserve(product)
                progress = (len(ledger), int(snapshot.get("scroll_height", 0)),
                            len(snapshot.get("body","")))
                if progress == previous:
                    unchanged += 1
                else:
                    unchanged = 0
                previous = progress
                if not search:
                    complete = True
                    break
                if step >= max_scrolls:
                    diagnostic = "Достигнут предел прокруток"
                    break
                at_end = snapshot.get("scroll_y",0) + snapshot.get("viewport",0) >= \
                    snapshot.get("scroll_height",0) - 100
                if at_end and unchanged >= 2:
                    controls = snapshot.get("controls", [])
                    next_url = ""
                    for control in controls:
                        candidate = control.get("url", "")
                        if (candidate and candidate not in visited_pages and
                            host_allowed(candidate) and
                            market_of(candidate) == market_of(target) and
                            "search" in urlparse(candidate).path.lower()):
                            next_url = sorted_search_url(candidate)
                            break
                    if next_url and page_count < 6:
                        visited_pages.add(next_url)
                        page_count += 1
                        await page.goto(next_url, wait_until="domcontentloaded", timeout=25000)
                        await page.wait_for_timeout(1200)
                        unchanged = 0
                        previous = (-1, -1, -1)
                        continue
                    more = next((x for x in controls if x.get("tag") in ("BUTTON", "DIV")
                                 and "ещ" in x.get("label","").lower()), None)
                    if more and load_more_count < 12:
                        label = more["label"]
                        clicked = await page.evaluate("""label => {
                            for(const el of document.querySelectorAll('button,[role="button"]')){
                                if((el.innerText||'').trim()===label){el.click();return true;}
                            }
                            return false;
                        }""", label)
                        if clicked:
                            load_more_count += 1
                            unchanged = 0
                            previous = (-1, -1, -1)
                            await page.wait_for_timeout(950)
                            continue
                    complete = not bool(next_url or more)
                    if not complete:
                        diagnostic = "Дополнительные страницы выдачи не завершены"
                    break
                # Scroll the actual page; fallback to the largest scrollable panel
                # used by virtualized storefront grids.
                await page.evaluate("""() => {
                    const doc=document.scrollingElement||document.documentElement;
                    let scrollTarget=doc;
                    for(const el of document.querySelectorAll('main,[class*="scroll"],[data-scroll]')){
                        if(el.scrollHeight>el.clientHeight+350&&el.clientHeight>300 &&
                            /auto|scroll/.test(getComputedStyle(el).overflowY)){
                            if(el.scrollHeight-el.clientHeight>scrollTarget.scrollHeight-scrollTarget.clientHeight)
                                scrollTarget=el;
                        }
                    }
                    scrollTarget.scrollBy(0,Math.max(700,window.innerHeight*0.85));
                }""")
                await page.wait_for_timeout(600)
            if network_tasks:
                await asyncio.wait(network_tasks, timeout=4)
            all_products = list(ledger.values())
            blocked = ((bool(blocked_http) and not all_products) or
                any(t in page_text.lower() for t in BLOCK_WORDS))
            all_products.sort(key=lambda c:(c["price"] <= 0,
                                      c["price"] if c["price"] else 10**12,c["url"]))
            links = all_products
            seen_prices = {v["price"] for v in links if v["price"]}
            orphans = sorted(p for p in visual_prices if p not in seen_prices)[:20]
            return {
                "state": "blocked" if blocked else "ok",
                "url": page.url, "title": title[:250],
                "text": page_text[:60000],
                "links": links[:6000],
                "price_fragments": [str(n)+" ₽" for n in sorted(visual_prices)[:70]],
                "diagnostic": diagnostic,
                "coverage_complete": bool(complete and not blocked),
                "audit": {
                    "raw_seen": raw_count, "unique_products": len(ledger),
                    "network_products": network_count,
                    "dom_snapshots": visited_snapshots, "pages": page_count,
                    "load_more_clicks": load_more_count,
                    "priced_products": sum(x["price"] > 0 for x in links),
                    "unlinked_low_prices": orphans,
                    "sort_requested": bool(search),
                    "http_status": response.status if response else None,
                    "blocked_statuses": blocked_http[:5]
                },
            }
        except Exception as exc:
            return {"state": "timeout" if "Timeout" in type(exc).__name__ else "error",
                    "url": url, "title": title, "text": page_text[:60000],
                    "links": list(ledger.values())[:6000],
                    "diagnostic": type(exc).__name__,
                    "coverage_complete": False}
        finally:
            page.remove_listener("response", on_response)
            for task in network_tasks:
                if not task.done():
                    task.cancel()
            await page.close()
