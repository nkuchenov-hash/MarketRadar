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
    # Stable parameter ordering prevents duplicate page visits when the storefront
    # reorders query arguments (e.g. page=2&how=aprice vs how=aprice&page=2).
    return urlunparse(p._replace(query=urlencode(sorted(params.items()))))


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
        # Decimal JSON amounts are rubles, not integer strings of kopecks.
        raw = value.strip()
        decimal = re.fullmatch(r"([0-9\s.,]+)[.,]([0-9]{2})", raw)
        if decimal:
            digits = re.sub(r"[^0-9]", "", decimal.group(1))
        else:
            digits = re.sub(r"[^0-9]", "", raw)
        n = int(digits) if digits else 0
        return n if 100 <= n <= 50_000_000 else 0
    return 0


def network_products(payload, origin: str, max_nodes=60000, market: str | None = None):
    """Preserve first-party listings, including WB's documented numeric product route."""
    market = market or market_of(origin)
    products = []
    visited = 0
    todo = [(payload, 0)]
    while todo and visited < max_nodes:
        node, depth = todo.pop()
        visited += 1
        if isinstance(node, list):
            # JSON is acyclic; max_nodes bounds traversal even for deep feeds.
            # A fixed depth cutoff silently loses genuine product records.
            todo.extend((x, depth + 1) for x in reversed(node))
            continue
        if not isinstance(node, dict):
            continue
        todo.extend((x, depth + 1) for x in node.values() if isinstance(x, (list, dict)))
        title = next((node.get(k) for k in ("title", "name", "productName", "displayName")
                      if isinstance(node.get(k), str) and len(node[k]) > 7), "")
        if not title:
            continue
        raw = next((node.get(k) for k in ("url", "productUrl", "link", "shareUrl", "webUrl")
                    if isinstance(node.get(k), str) and node[k]), "")
        if not raw and market == "Wildberries":
            product_id = node.get("id")
            if isinstance(product_id, int) and 100000 <= product_id < 10**12:
                raw = f"/catalog/{product_id}/detail.aspx"
        if not raw:
            continue
        storefront = ("https://www.wildberries.ru/" if market == "Wildberries"
                      else "https://market.yandex.ru/" if market == "Яндекс Маркет"
                      else "https://www.ozon.ru/")
        candidate_url = urljoin(storefront, raw) if raw.startswith("/") else urljoin(origin, raw)
        if not is_product_link(market, candidate_url):
            continue
        amount = 0
        if market == "Wildberries":
            # WB first-party JSON amounts ending in U and sizes[].price.total
            # are provided in kopecks, unlike storefront ruble prices.
            for key in ("salePriceU", "priceU"):
                value = node.get(key)
                if isinstance(value, (int, float)) and value >= 10000:
                    amount = _amount(round(value / 100))
                    if amount:break
            if not amount:
                sizes = node.get("sizes", [])
                for size in sizes if isinstance(sizes,list) else []:
                    if not isinstance(size,dict):continue
                    price_data = size.get("price",{})
                    if not isinstance(price_data,dict):continue
                    value = price_data.get("total") or price_data.get("product")
                    if isinstance(value,(int,float)) and value >= 10000:
                        amount = _amount(round(value / 100))
                        if amount:break
        if not amount:
            for key in ("discountedPrice", "finalPrice", "salePrice", "price", "minPrice"):
                if key in node:
                    amount = _amount(node[key])
                    if amount:
                        break
        # Keep first-party JSON image evidence when the virtualized DOM card
        # has not yet appeared.
        image_url = ""
        for image_key in ("imageUrl", "image_url", "pictureUrl", "thumbnailUrl", "photoUrl"):
            candidate = node.get(image_key)
            if isinstance(candidate, str) and candidate:
                image = urljoin(origin, candidate)
                parsed_image = urlparse(image)
                if parsed_image.scheme == "https" and parsed_image.hostname:
                    image_url = image[:900]
                    break
        products.append({"url": canonical(candidate_url), "title": title[:240],
                         "context": "", "price": amount, "source": "network",
                         "image_url": image_url})
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
        const uniqueProducts=node=>new Set(
            [...node.querySelectorAll('a[href]')].filter(a=>{
                try{return path.test(new URL(a.href).pathname);}catch(e){return false;}
            }).map(a=>{
                try{const u=new URL(a.href);return u.origin+u.pathname.replace(/\/$/,'');}
                catch(e){return a.href;}
            })
        ).size;
        let tile = a.closest('[data-testid*="product"],[data-nm-id],article,li,.product-card');
        if(tile && uniqueProducts(tile)>1)tile=null;
        if (!tile) {
            let node = a;
            for(let i = 0; i < 8 && node.parentElement; i++) {
                node = node.parentElement;
                const txt = (node.innerText || '').trim();
                if(uniqueProducts(node)>1)break;
                price.lastIndex = 0;
                if(txt.length > 12 && txt.length < 2200 && price.test(txt)){
                    tile = node; break;
                }
            }
        }
        // Never attribute the cheapest price of a multi-product list to
        // every child anchor. Unknown price is preferable to a false minimum.
        const fallback = uniqueProducts(a.parentElement||a)>1 ? a.innerText :
            (a.parentElement?.innerText||a.innerText);
        const context = (tile?.innerText || fallback || '')
            .trim().slice(0,2200);
        const titleCandidates = [
            a.getAttribute('title'), a.getAttribute('aria-label'), a.innerText,
            tile?.querySelector('h3,h2,[data-auto*="snippet-title"],[data-testid*="title"]')?.innerText,
            a.querySelector('img')?.alt, tile?.querySelector('img')?.alt
        ];
        const properTitle = s => {
            const v = (s||'').trim();
            return v.length >= 8 && !/(?:₽|руб\.?|в\s+сплит)/i.test(v) &&
                !/^(?:цена\s+с\s+картой|сплит|пэй|в\s+корзину|купить)/i.test(v);
        };
        const title = (titleCandidates.find(properTitle) ||
            titleCandidates.find(s=>!!(s||'').trim()) || '').trim().slice(0,300);
        price.lastIndex = 0;
        const matches = [...context.matchAll(price)];
        // A split/instalment payment is not the full purchase price.
        // WB and Yandex may show "Оплата частями 3 577 ₽" beside the
        // complete 12 990 ₽ price. Choosing the lowest monetary number
        // would falsely turn a payment into the cheapest product.
        const upfront = matches.filter(m => {
            const before = context.slice(
                context.lastIndexOf('\n',m.index)+1, m.index).trim();
            const after = context.slice(m.index+m[0].length,
                                        m.index+m[0].length+40);
            const fullPriceCue = /(?:цена(?: товара)?|стоимость|итого|к оплате|с кошельком|без скидки)\s*[:—-]?\s*$/i.test(before);
            const partBefore = /(?:оплата\s+частями|плат[её]ж(?:а|ей|и)?\s+по|в\s+сплит|в\s+рассрочк[а-я]*|рассрочк[а-я]*|в\s+кредит|ежемесячн|за\s+месяц|в\s+мес|частями\s+по)(?:\s|$|[:—-])/i.test(before);
            const multiplied = /\d{1,2}\s*[×xх*]\s*$/i.test(before);
            const partAfter = /^\s*(?:в\s+сплит|сплит|в\s+рассрочк[а-я]*|за\s+месяц|\/\s*мес|в\s+мес|ежемесячно)/i.test(after);
            return !multiplied && !partAfter && (!partBefore || fullPriceCue);
        });
        const prices = upfront.map(m => Number(m[1].replace(/\D/g,'')))
            .filter(n => n >= 100 && n <= 50000000);
        // Product images may be lazy-loaded, protocol-relative or stored
        // in <picture> source/srcset rather than a populated <img src>.
        // Only inspect a node containing at most this one product, never
        // steal a neighbouring product's image from the whole grid.
        const imageRoot = tile || (uniqueProducts(a.parentElement || a) <= 1
            ? a.parentElement : a);
        function safeImage(raw) {
            if (!raw || /^data:|^blob:/i.test(raw)) return '';
            try {
                const u = new URL(raw.startsWith('//') ? 'https:' + raw : raw,
                                  document.baseURI);
                return u.protocol === 'https:' ? u.href : '';
            } catch(e) { return ''; }
        }
        let imageUrl = '';
        const imageNodes = [
            ...(imageRoot?.querySelectorAll('img') || []),
            ...(imageRoot?.querySelectorAll('picture source') || [])
        ].slice(0, 15);
        for (const img of imageNodes) {
            const srcset = s => (s || '').split(',')[0].trim().split(/\s+/)[0];
            const possibilities = [
                img.getAttribute('data-src'),
                img.getAttribute('data-original'),
                img.getAttribute('data-lazy-src'),
                img.currentSrc,
                img.getAttribute('src'),
                srcset(img.getAttribute('srcset')),
                srcset(img.getAttribute('data-srcset'))
            ];
            for (const raw of possibilities) {
                imageUrl = safeImage(raw);
                if (imageUrl) break;
            }
            if (imageUrl) break;
        }
        if (!imageUrl && imageRoot) {
            const backgrounds = [imageRoot,
                ...imageRoot.querySelectorAll('[style*="background"]')].slice(0,15);
            for (const el of backgrounds) {
                const css = getComputedStyle(el).backgroundImage || '';
                const match = css.match(/url\(["']?([^"')]+)["']?\)/i);
                if (match) imageUrl = safeImage(match[1]);
                if (imageUrl) break;
            }
        }
        const date = context.match(/(?:сегодня|завтра|послезавтра|через\s*\d{1,2}\s*дн|\d{1,2}\s*(?:января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря|янв\.?|фев\.?|мар\.?|апр\.?|июн\.?|июл\.?|авг\.?|сен\.?|окт\.?|нояб?\.?|дек\.?))/i);
        products.push({url:a.href, title, context,
            price:prices.length ? Math.min(...prices) : 0,
            image_url:/^https:\/\//i.test(imageUrl)?imageUrl:'',
            delivery_hint:date?date[0]:'',
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
    const h1=(document.querySelector('h1')?.innerText||'').trim().slice(0,300);
    const primaryImg=document.querySelector('meta[property="og:image"]')?.content||'';
    let primaryPrice=0;
    for(const node of document.querySelectorAll(
        '[itemprop="price"][content],meta[property="product:price:amount"],meta[property="og:price:amount"]')){
        const n=Number((node.content||'').replace(',', '.'));
        if(n>=100&&n<=50000000){primaryPrice=primaryPrice?Math.min(primaryPrice,n):n;}
    }
    for(const node of document.querySelectorAll('script[type="application/ld+json"]')){
        try{
            const input=JSON.parse(node.textContent||'null');
            const todo=[input];let checked=0;
            while(todo.length&&checked++<80){
                const item=todo.pop();
                if(Array.isArray(item)){todo.push(...item.slice(0,80));continue;}
                if(!item||typeof item!=='object')continue;
                if(item.offers)todo.push(item.offers);
                const type=String(item['@type']||'').toLowerCase();
                if(type==='offer'||type==='aggregateoffer'){
                    const n=Number(item.price||item.lowPrice);
                    if(n>=100&&n<=50000000)
                        primaryPrice=primaryPrice?Math.min(primaryPrice,n):n;
                }
                if(item['@graph'])todo.push(item['@graph']);
            }
        }catch(e){}
    }
    return {title:document.title || '', heading:h1, primary_price:primaryPrice,
        primary_image:primaryImg,body,
        products, visible_prices:[...new Set(prices)].sort((a,b)=>a-b).slice(0,100),
        scroll_y:scroll.scrollTop, scroll_height:scroll.scrollHeight,
        viewport:window.innerHeight, controls};
}"""


def browser_proxy(market: str | None):
    """Optional per-store proxy for server-side Chromium.

    Credentials are read exclusively from the VPS environment and never sent
    to the Android client or inserted into browser diagnostics.
    Unconfigured stores keep their existing direct connection.
    """
    slug = {"Ozon": "OZON", "Wildberries": "WB",
            "Яндекс Маркет": "YM"}.get(market)
    if not slug:
        return None
    prefix = "MARKETRADAR_" + slug + "_PROXY"
    server = os.getenv(prefix + "_SERVER", "").strip()
    if not server:
        return None
    parsed = urlparse(server)
    if (parsed.scheme not in ("http", "https", "socks5") or
            not parsed.hostname or parsed.username or parsed.password or
            parsed.path not in ("", "/") or parsed.query or parsed.fragment):
        raise ValueError("Неверный формат адреса браузерного прокси")
    proxy = {"server": server}
    username = os.getenv(prefix + "_USERNAME", "")
    password = os.getenv(prefix + "_PASSWORD", "")
    if username:
        proxy["username"] = username
        proxy["password"] = password
    elif password:
        raise ValueError("Для прокси задан пароль без имени пользователя")
    return proxy


def merge_browser_and_public_evidence(fallback: dict, browser_records: dict) -> dict:
    """Retain observed Chromium cards when public HTML is used after a denial.

    A browser record must never be erased by a later HTTP failure. The merged
    result remains partial and cannot be advertised as a successful UI crawl.
    """
    if not browser_records:
        return fallback
    combined = {}
    for item in fallback.get("links", []):
        url = item.get("url", "")
        if url:
            combined[canonical(url)] = dict(item)
    for item in browser_records.values():
        url = item.get("url", "")
        if not url:
            continue
        key = canonical(url)
        previous = combined.get(key)
        if previous is None:
            combined[key] = dict(item)
            continue
        for field in ("title", "context", "image_url", "delivery_hint"):
            if not previous.get(field) and item.get(field):
                previous[field] = item[field]
        price = _amount(item.get("price"))
        old_price = _amount(previous.get("price"))
        if price and (not old_price or price < old_price):
            previous["price"] = price
            previous["source"] = item.get("source", "network")
    records = sorted(combined.values(), key=lambda x: (
        not bool(x.get("price")), x.get("price") or 10**12, x["url"]))
    fallback["links"] = records
    fallback["coverage_complete"] = False
    fallback["state"] = "partial"
    audit = fallback.setdefault("audit", {})
    audit["chromium_preserved_products"] = len(browser_records)
    audit["merged_unique_products"] = len(records)
    audit["output_truncated_products"] = 0
    return fallback


class CloudBrowser:
    """Limited parallel Chromium workers, full listing scans and evidence ledger."""
    _slots = asyncio.Semaphore(max(1, min(int(os.getenv("CLOUD_BROWSER_PARALLEL","2")), 3)))
    # Persistent Chromium refuses to launch two processes using one profile.
    # Serialize only searches sharing a storefront profile; other stores remain parallel.
    _profile_locks = {key: asyncio.Lock() for key in ("wb", "ozon", "ym", "diagnostic")}

    def __init__(self, market=None):
        self.playwright = self.browser = self.context = None
        self.acquired = False
        self.profile_lock = None
        self.market = market
        self.headful = os.getenv("CLOUD_BROWSER_HEADFUL", "false").lower() in ("true","1","yes")
        self.persistent = os.getenv("CLOUD_BROWSER_PERSISTENT", "false").lower() in ("true","1","yes")

    async def start(self):
        ok, reason = available()
        if not ok:
            raise RuntimeError(reason)
        await self._slots.acquire()
        self.acquired = True
        try:
            if self.persistent and not os.getenv("MARKETRADAR_CHROMIUM_CDP_URL", "").strip():
                profile = {"Wildberries": "wb", "Ozon": "ozon",
                           "Яндекс Маркет": "ym"}.get(self.market, "diagnostic")
                self.profile_lock = self._profile_locks[profile]
                await self.profile_lock.acquire()
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
            elif self.persistent:
                # Preserve first-party storefront state on the VPS between
                # searches, as a normal installed browser does. One Chromium
                # process at a time is required for each profile directory.
                profile = {"Wildberries":"wb","Ozon":"ozon",
                           "Яндекс Маркет":"ym"}.get(self.market, "diagnostic")
                profile_dir = os.path.join(
                    os.getenv("CLOUD_BROWSER_PROFILE_DIR", "/app/data/browser-profiles"),profile)
                os.makedirs(profile_dir,mode=0o700,exist_ok=True)
                self.context = await self.playwright.chromium.launch_persistent_context(
                    profile_dir, headless=not self.headful,
                    locale="ru-RU", viewport={"width": 1280, "height": 900},
                    service_workers="allow", proxy=browser_proxy(self.market),
                    args=["--disable-dev-shm-usage"])
            else:
                self.browser = await self.playwright.chromium.launch(
                    headless=not self.headful, proxy=browser_proxy(self.market),
                    args=["--disable-dev-shm-usage"])
            if self.context is None:
                # Apply per-store network routing to remote CDP contexts too.
                # Previously proxy configuration only affected local launches,
                # leaving remote Chromium on its potentially blocked exit IP.
                context_options = {
                    "locale": "ru-RU",
                    "viewport": {"width": 1280, "height": 900},
                    "service_workers": "allow",
                }
                if cdp:
                    proxy = browser_proxy(self.market)
                    if proxy:
                        context_options["proxy"] = proxy
                self.context = await self.browser.new_context(**context_options)
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
            if self.profile_lock is not None:
                lock = self.profile_lock
                self.profile_lock = None
                if lock.locked():
                    lock.release()
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
        use_shop_ui = (search and market_of(target) in ("Wildberries","Ozon","Яндекс Маркет")
                       and os.getenv("CLOUD_BROWSER_HUMAN_FLOW","true").lower()
                       in ("true","yes","1"))
        capturing_search = [not use_shop_ui]
        navigation = {}
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
        ssr_attempted = False
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
                               "price": amount, "source": item.get("source","unknown"),
                               "image_url": str(item.get("image_url",""))[:900],
                               "delivery_hint": str(item.get("delivery_hint",""))[:100]}
            else:
                new_title=str(item.get("title",""))[:300]
                def quality(s):
                    if not s.strip():
                        return -1000
                    if (re.search(r"₽|\bруб\b",s,re.I) or
                            re.search(r"^(цена с картой|сплит|пэй|купить|в корзину)",s,re.I)):
                        return -100
                    return min(200,len(s.strip()))
                if quality(new_title) > quality(existing["title"]):
                    existing["title"] = new_title
                if len(str(item.get("context",""))) > len(existing["context"]):
                    existing["context"] = str(item["context"])[:1100]
                if not existing.get("image_url") and item.get("image_url"):
                    existing["image_url"] = str(item["image_url"])[:900]
                if not existing.get("delivery_hint") and item.get("delivery_hint"):
                    existing["delivery_hint"] = str(item["delivery_hint"])[:100]
                if amount and (not existing["price"] or amount < existing["price"]):
                    existing["price"] = amount
                    existing["source"] = item.get("source", existing["source"])

        network_truncated = [0]
        network_parse_errors = [0]
        network_pending = [0]

        async def parse_response(response):
            nonlocal network_count
            try:
                if not capturing_search[0]:
                    return
                resource = response.request.resource_type
                if response.status in (403, 429, 498, 503) and (
                    resource == "document" or (market_of(response.url) == market_of(target)
                    and resource in ("xhr", "fetch") and
                    any(part in response.url.lower() for part in
                        ("search", "catalog", "products", "feed")))):
                    blocked_http.append(response.status)
                target_market = market_of(target)
                source_market = market_of(response.url)
                if target_market == "Wildberries" and source_market is None:
                    source_host = urlparse(response.url).hostname or ""
                    if source_host == "wb.ru" or source_host.endswith(".wb.ru"):
                        source_market = "Wildberries"
                if not search or source_market != target_market:
                    return
                if resource not in ("xhr", "fetch"):
                    return
                headers = response.headers
                if "json" not in headers.get("content-type","").lower():
                    return
                # Content-Length is often absent or reports the compressed transfer
                # size. Parse the response instead of silently dropping large feeds.
                # An incomplete traversal must be visible in the coverage audit.
                data = await response.json()
                found, visited = network_products(data, response.url, market=source_market)
                if visited >= 60000:
                    network_truncated[0] += 1
                network_count += len(found)
                for item in found:
                    preserve(item)
            except Exception:
                # Failed JSON decoding must invalidate completeness, not vanish.
                network_parse_errors[0] += 1

        def on_response(response):
            task = asyncio.create_task(parse_response(response))
            network_tasks.add(task)
            task.add_done_callback(network_tasks.discard)

        async def browser_evidence(fallback,response):
            # Public HTML may still contain real cards, but cannot pretend
            # that the shopper-style browser workflow actually succeeded.
            audit=fallback.setdefault("audit",{})
            audit["browser_headful"]=self.headful
            audit["browser_persistent"]=self.persistent
            audit["browser_navigation"]=dict(navigation)
            audit["browser_http_status"]=response.status if response else None
            try:
                audit["browser_page_title"]=(await page.title())[:120]
            except Exception:
                audit["browser_page_title"]=""
            audit["browser_page_path"]=urlparse(page.url).path[:180]
            audit["sort_ui_confirmed"]=bool(navigation.get("sort_ui_selected"))
            return fallback

        page.on("response", on_response)
        try:
            if use_shop_ui:
                from .human_navigation import open_search_via_ui
                response,navigation = await open_search_via_ui(
                    page,market_of(target),target,
                    on_search_start=lambda:capturing_search.__setitem__(0,True))
            else:
                response = await page.goto(target, wait_until="domcontentloaded", timeout=35000)
            if (search and response
                    and response.status in (403,429,498,503)):
                # Try the ordinary public search in Chromium BEFORE SSR.
                # Some storefronts reject price-sort query parameters while
                # serving the unsorted listing. SSR must not preempt this
                # browser recovery path merely because HTML is available.
                blocked_sorted_response = response
                if (market_of(target) in ("Wildberries", "Ozon")
                        and url != target):
                    try:
                        plain = await page.goto(
                            url, wait_until="domcontentloaded", timeout=22000)
                        navigation["plain_search_fallback"] = True
                        navigation["plain_search_http_status"] = (
                            plain.status if plain else None)
                        if plain:
                            response = plain
                    except Exception as exc:
                        navigation["plain_search_error"] = type(exc).__name__
                if not response or response.status in (403,429,498,503):
                    ssr_attempted = True
                    from .public_html import fetch_public_search_html
                    fallback = await asyncio.to_thread(fetch_public_search_html,target)
                    if fallback:
                        if network_tasks:
                            _, pending = await asyncio.wait(network_tasks, timeout=4)
                            network_pending[0] = len(pending)
                        return await browser_evidence(
                            merge_browser_and_public_evidence(fallback, ledger),
                            blocked_sorted_response)
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
                        if not candidate or not host_allowed(candidate):
                            continue
                        if (market_of(candidate) != market_of(target) or
                                "search" not in urlparse(candidate).path.lower()):
                            continue
                        # Normalize before checking visited pages: stores can
                        # reorder query parameters on the same next-page URL.
                        normalized = sorted_search_url(candidate)
                        if normalized not in visited_pages:
                            next_url = normalized
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
                # Scroll like a desktop shopper. Some storefronts use nested
                # virtualized grids, so retain a DOM fallback only when the
                # viewport and loaded product list stop advancing.
                await page.mouse.move(960, 710)
                await page.mouse.wheel(0, 780)
                await page.wait_for_timeout(500)
                if unchanged >= 1:
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
                    await page.wait_for_timeout(250)
            if network_tasks:
                _, pending = await asyncio.wait(network_tasks, timeout=4)
                network_pending[0] = len(pending)
            all_products = list(ledger.values())
            blocked = ((bool(blocked_http) and not all_products) or
                any(t in page_text.lower() for t in BLOCK_WORDS))
            # HTTP 200 can be a challenge shell or an empty hydrated grid.
            # Treat zero observed product links as missing browser evidence,
            # and try public SSR without claiming Chromium search succeeded.
            empty_search = bool(search and not all_products)
            if empty_search and not diagnostic:
                diagnostic = "Chromium returned no product cards (HTTP status alone is insufficient)"
            if (blocked or empty_search) and search and not ssr_attempted:
                ssr_attempted = True
                from .public_html import fetch_public_search_html
                fallback = await asyncio.to_thread(fetch_public_search_html,target)
                if fallback:
                    if network_tasks:
                        _, pending = await asyncio.wait(network_tasks, timeout=4)
                        network_pending[0] = len(pending)
                    return await browser_evidence(
                        merge_browser_and_public_evidence(fallback, ledger),
                        response)
            all_products.sort(key=lambda c:(c["price"] <= 0,
                                      c["price"] if c["price"] else 10**12,c["url"]))
            links = all_products
            seen_prices = {v["price"] for v in links if v["price"]}
            orphans = sorted(p for p in visual_prices if p not in seen_prices)[:20]
            return {
                "state": "blocked" if blocked else ("partial" if empty_search else "ok"),
                "url": page.url, "title": title[:250],
                "heading": str(last_snapshot.get("heading",""))[:300],
                "primary_price": _amount(last_snapshot.get("primary_price",0)),
                "primary_image": str(last_snapshot.get("primary_image",""))[:900],
                "text": page_text[:60000],
                "links": links[:6000],
                "price_fragments": [str(n)+" ₽" for n in sorted(visual_prices)[:70]],
                "diagnostic": diagnostic,
                "coverage_complete": bool(complete and not blocked
                                          and (not search or bool(links))
                                          and not navigation.get("plain_search_fallback")
                                          and (not use_shop_ui or navigation.get("sort_ui_selected"))
                                           and not network_truncated[0]
                                          and not network_parse_errors[0]
                                          and not network_pending[0]
                                          and len(links) <= 6000),
                "audit": {
                    "raw_seen": raw_count, "unique_products": len(ledger),
                    "network_products": network_count,
                     "network_truncated_responses": network_truncated[0],
                    "network_parse_errors": network_parse_errors[0],
                    "network_pending_responses": network_pending[0],
                    "output_truncated_products": max(0, len(links) - 6000),
                    "dom_snapshots": visited_snapshots, "pages": page_count,
                    "load_more_clicks": load_more_count,
                    "priced_products": sum(x["price"] > 0 for x in links),
                    "unlinked_low_prices": orphans,
                    "sort_requested": bool(search),
                    "sort_ui_confirmed": bool(navigation.get("sort_ui_selected")),
                    "plain_search_fallback": bool(navigation.get("plain_search_fallback")),
                    "http_status": response.status if response else None,
                    # Explicit provenance for live acceptance: this ledger is
                    # populated by Chromium DOM and network responses, not SSR.
                    "source": "chromium",
                    "browser_http_status": response.status if response else None,
                    "blocked_statuses": blocked_http[:5],
                    "browser_headful": self.headful,
                    "browser_persistent": self.persistent,
                    "navigation": navigation,
                },
            }
        except Exception as exc:
            # A failed page turn, navigation or DOM evaluation must not discard
            # product evidence already captured from earlier pages or JSON feeds.
            # Keep the failure visible and never claim full search coverage.
            if network_tasks:
                _, pending = await asyncio.wait(network_tasks, timeout=2)
                network_pending[0] = len(pending)
            preserved = sorted(
                ledger.values(),
                key=lambda item: (
                    not bool(item.get("price")),
                    item.get("price") or 10**12,
                    item["url"],
                ),
            )
            return {
                "state": "timeout" if "Timeout" in type(exc).__name__ else "error",
                "url": page.url, "title": title, "text": page_text[:60000],
                "links": preserved[:6000],
                "diagnostic": type(exc).__name__ + ": " + str(exc)[:250],
                "coverage_complete": False,
                "audit": {
                    "failure_stage": "browser_snapshot",
                    "raw_seen": raw_count,
                    "unique_products": len(ledger),
                    "network_products": network_count,
                    "network_truncated_responses": network_truncated[0],
                    "network_parse_errors": network_parse_errors[0],
                    "network_pending_responses": network_pending[0],
                    "output_truncated_products": max(0, len(preserved) - 6000),
                    "dom_snapshots": visited_snapshots,
                    "pages": page_count,
                    "blocked_statuses": blocked_http[:5],
                    "navigation": navigation,
                },
            }
        finally:
            page.remove_listener("response", on_response)
            for task in network_tasks:
                if not task.done():
                    task.cancel()
            await page.close()
