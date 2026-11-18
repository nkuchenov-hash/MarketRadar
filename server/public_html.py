"""Conservative public HTML product evidence extraction.

No model inference or prices attributed across multiple products.
Used only when a public storefront serves HTML but Chromium receives an
HTTP denial from the same network. A single SSR page is never labelled a
complete search.
"""
from __future__ import annotations

from html.parser import HTMLParser
from urllib.parse import urljoin, urlparse, unquote
import json
from datetime import date
from .core import market_of, is_product_link, canonical
import re

PRICE = re.compile(
    r"(?<!\d)(\d{1,3}(?:[\s\u00a0\u202f]\d{3})+|\d{3,7})"
    r"\s*(?:₽|руб(?:лей|\.)?)", re.IGNORECASE)
DATE = re.compile(
    r"сегодня|завтра|послезавтра|через\s*\d{1,2}\s*дн(?:я|ей|ь)?"
    r"|\b\d{1,2}\s*(?:января|февраля|марта|апреля|мая|июня|июля"
    r"|августа|сентября|октября|ноября|декабря)\b",
    re.IGNORECASE)
VOID = frozenset((
    "area","base","br","col","embed","hr","img","input","link",
    "meta","param","source","track","wbr"
))
HIDDEN = frozenset(("script","style","noscript","svg"))


class _Node:
    __slots__ = ("tag", "attrs", "children", "parent", "texts")
    def __init__(self, tag: str, attrs=None, parent=None):
        self.tag = tag
        self.attrs = dict(attrs or ())
        self.children = []
        self.parent = parent
        self.texts = []


class _Parser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.root = _Node("_root")
        self.stack = [self.root]
        self.anchors = []
        self.heading = ""

    def handle_starttag(self, tag, attrs):
        node = _Node(tag, attrs, self.stack[-1])
        node.parent.children.append(node)
        if tag == "a":
            self.anchors.append(node)
        if tag not in VOID:
            self.stack.append(node)

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in VOID:
            self.handle_endtag(tag)

    def handle_endtag(self, tag):
        for i in range(len(self.stack)-1, 0, -1):
            if self.stack[i].tag == tag:
                del self.stack[i:]
                return

    def handle_data(self, data):
        if self.stack[-1].tag not in HIDDEN and data.strip():
            self.stack[-1].texts.append(data[:3000])


def _text(node: _Node, limit: int = 2200) -> str:
    chunks = []
    remaining = limit
    todo = [node]
    while todo and remaining > 0:
        current = todo.pop()
        if current.tag in HIDDEN:
            continue
        for part in current.texts:
            if remaining <= 0:
                break
            chunks.append(part[:remaining])
            remaining -= min(len(part), remaining)
        todo.extend(reversed(current.children))
    return " ".join(chunks).strip()


def _links(node: _Node, origin: str, market: str) -> set[str]:
    found = set()
    todo = [node]
    checked = 0
    while todo and checked < 3000 and len(found) < 2:
        current = todo.pop()
        checked += 1
        if current.tag == "a":
            href = urljoin(origin, current.attrs.get("href", ""))
            if is_product_link(market, href):
                found.add(canonical(href))
        todo.extend(reversed(current.children))
    return found


def _image(node: _Node, origin: str) -> str:
    todo = [node]
    while todo:
        current = todo.pop()
        if current.tag == "img":
            source = current.attrs.get("src") or current.attrs.get("data-src") or ""
            if not source and current.attrs.get("srcset"):
                source = current.attrs["srcset"].split(",")[0].split(" ")[0]
            if source.startswith(("https://","//","/")):
                url = urljoin(origin, source)
                if url.startswith("https://"):
                    return url[:900]
        todo.extend(reversed(current.children))
    return ""


def _is_product_title(text: str) -> bool:
    text = re.sub(r"\s+", " ",text or "").strip()
    if len(text) < 9 or len(text) > 250 or "₽" in text:
        return False
    if re.match(r"^(?:цена|пэй|оригинал|скидка|доставка|отзывы|в корзину|"
                r"в рассрочку|реклама|купить|карта)\b", text,re.I):
        return False
    return bool(re.search(r"[A-Za-zА-Яа-яЁё]",text))


_TRANSLITERATION = {
    "yandeks":"Яндекс", "stantsiya":"Станция", "mini":"Мини",
    "pro":"Про", "umnaya":"Умная", "kolonka":"колонка",
    "yndx":"YNDX", "s":"с", "alisoy":"Алисой", "zigbee":"Zigbee",
    "chernyy":"чёрный", "seryy":"серый", "siniy":"синий",
    "zelenyy":"зелёный", "black":"black", "grey":"grey"
}


def _title_from_slug(url: str) -> str:
    path = urlparse(url).path.split("/")
    if len(path) < 3:
        return ""
    slug = unquote(path[2])
    return " ".join(_TRANSLITERATION.get(word,word)
                    for word in re.split(r"-+", slug) if word)[:240]


def _snippet(anchor: _Node) -> _Node | None:
    node=anchor
    for _ in range(23):
        if not node or node.parent is None:
            break
        if node.attrs.get("data-baobab-name") == "productSnippet":
            return node
        node=node.parent
    return None


def _best_title(anchor: _Node, card: _Node, url: str) -> str:
    snippet = _snippet(anchor)
    bases = [b for b in (snippet,card) if b is not None]
    for root in bases:
        nodes=[root]
        titles=[]
        scanned=0
        while nodes and scanned < 2500:
            node=nodes.pop()
            scanned+=1
            token=(node.attrs.get("data-auto") or "").lower()
            name=(node.attrs.get("aria-label") or node.attrs.get("title") or "")
            if ("title" in token or token in ("snippet-name","product-name") or
                node.tag in ("h2","h3","h4")):
                title=_text(node,250)
                if _is_product_title(title):
                    titles.append((0 if "title" in token else 1,len(title),title))
            if node.tag == "img" and _is_product_title(node.attrs.get("alt","")):
                titles.append((2,len(node.attrs["alt"]),node.attrs["alt"]))
            if node.tag == "a" and _is_product_title(name):
                titles.append((3,len(name),name))
            nodes.extend(reversed(node.children))
        if titles:
            titles.sort(key=lambda x:(x[0],-x[1]))
            return titles[0][2].strip()[:240]
    original=(anchor.attrs.get("aria-label") or anchor.attrs.get("title")
              or _text(anchor,240))
    if _is_product_title(original):
        return original.strip()[:240]
    # Last resort: Yandex's product slug carries an actual model and SKU,
    # unlike a pricing-label link such as "Цена с картой Яндекс Пэй".
    return _title_from_slug(url)


_MONTHS = ("января","февраля","марта","апреля","мая","июня",
           "июля","августа","сентября","октября","ноября","декабря")


def _delivery_from_snippet(anchor: _Node) -> str:
    snippet=_snippet(anchor)
    if snippet is None:
        return ""
    for key in ("data-zone-data","data-zone-params"):
        serialized=snippet.attrs.get(key)
        if not serialized:
            continue
        try:
            data=json.loads(serialized)
        except (ValueError,TypeError):
            continue
        queue=[data]
        days=[]
        while queue:
            obj=queue.pop()
            if isinstance(obj,list):
                queue.extend(obj[:120])
            elif isinstance(obj,dict):
                for k,v in obj.items():
                    if k == "dateFrom" and isinstance(v,str):
                        try: days.append(date.fromisoformat(v[:10]))
                        except ValueError: pass
                    if isinstance(v,(dict,list)):
                        queue.append(v)
        if days:
            earliest=min(days)
            return f"{earliest.day} {_MONTHS[earliest.month-1]}"
    return ""


def fetch_public_search_html(url: str) -> dict:
    """Read publicly accessible SSR pages in price order, preserving every card.

    Chromium remains the primary browsing engine. This is a secondary
    first-party HTML transport for storefronts that serve public SSR to HTTP
    clients but deny Chromium on cloud egress. Never claim exhaustive access.
    """
    import os
    import urllib.request
    from urllib.parse import urlparse, parse_qsl, urlencode, urlunparse
    from concurrent.futures import ThreadPoolExecutor
    from .core import host_allowed

    if not host_allowed(url):
        raise ValueError("Storefront URL required")

    max_pages=max(1,min(24,int(os.getenv("CLOUD_SSR_PAGES","6"))))
    headers = {
        "User-Agent":("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"),
        "Accept":"text/html,application/xhtml+xml;q=0.9,*/*;q=0.8",
        "Accept-Language":"ru-RU,ru;q=0.9"
    }

    def fetch_one(page_num: int) -> dict:
        parts=urlparse(url)
        params=dict(parse_qsl(parts.query,keep_blank_values=True))
        if page_num>1:
            params["page"]=str(page_num)
        else:
            params.pop("page",None)
        target=urlunparse(parts._replace(query=urlencode(params)))
        request=urllib.request.Request(target,headers=headers)
        for attempt in range(2):
            try:
                with urllib.request.urlopen(request,timeout=18) as response:
                    if response.status == 200 and host_allowed(response.url):
                        if "text/html" in response.headers.get("Content-Type","").lower():
                            raw=response.read(8_000_001)
                            if len(raw)<=8_000_000:
                                result=extract_public_html(
                                    raw.decode("utf-8","replace"),response.url)
                                if result["links"]:
                                    result["retried"]=attempt>0
                                    return result
            except (OSError,ValueError,UnicodeError):
                pass
            if attempt==0:
                import time
                time.sleep(0.7)
        return {}

    first=fetch_one(1)
    if not first:
        return {}
    products={x["url"]:x for x in first["links"]}
    raw_seen=first["observed_product_anchors"]
    visited=[1]
    failed=[]
    recovered=[]
    with ThreadPoolExecutor(max_workers=3) as workers:
        # Fetch bounded batches; stop when an entire batch contributes no
        # new card. Different storefront pages may overlap in results.
        for begin in range(2,max_pages+1,3):
            pages=list(range(begin,min(max_pages,begin+2)+1))
            batch=list(workers.map(fetch_one,pages))
            newly_added=0
            for num,info in zip(pages,batch):
                if not info:
                    failed.append(num)
                    continue
                visited.append(num)
                if info.get("retried"):
                    recovered.append(num)
                raw_seen+=info["observed_product_anchors"]
                for item in info["links"]:
                    prior=products.get(item["url"])
                    if prior is None:
                        products[item["url"]]=item
                        newly_added+=1
                    elif item["price"] and (
                         not prior["price"] or item["price"]<prior["price"]):
                        products[item["url"]]=item
            # A failed page is not evidence that pagination is exhausted.
            # Continue probing the next bounded batch rather than silently
            # losing potentially cheaper products after a transient denial.
            if newly_added == 0 and not any(not info for info in batch):
                break

    links=sorted(products.values(),key=lambda item: (
        item["price"]<=0,item["price"] or 10**12,item["url"]))
    priced=[x for x in links if x["price"]>0]
    return {
        "state":"ok", "url":url, "title":"Публичная выдача",
        "links":links,
        "price_fragments":[f"{x['price']} ₽" for x in priced[:100]],
        "text":"Публичная поисковая выдача магазина",
        "coverage_complete":False,
        "diagnostic":f"HTML: просмотрено страниц {len(visited)}, охват неполный",
        "audit":{
            "source":"public_html","http_status":200,
            "raw_seen":raw_seen,"unique_products":len(products),
            "priced_products":len(priced),
            "pages_visited":visited,"pages_failed":failed,
            "pages_recovered_after_retry":recovered,
            "dom_snapshots":0,"sort_requested":True,
            "coverage_complete":False,
        }
    }


def extract_public_html(html: str, origin: str) -> dict:
    """Return unique, auditable product links and locally-associated prices."""
    market = market_of(origin)
    if not market:
        raise ValueError("Unknown storefront")
    parser = _Parser()
    parser.feed(html[:10_000_000])
    by_url = {}
    observed = 0
    priced = 0
    for anchor in parser.anchors:
        href = urljoin(origin, anchor.attrs.get("href",""))
        if not is_product_link(market, href):
            continue
        observed += 1
        url = canonical(href)
        node = anchor
        card = anchor
        context = ""
        raw_prices = []
        for depth in range(12):
            if _links(node, origin, market) != {url}:
                break
            text = _text(node)
            amounts = [int(re.sub(r"\D","",m.group(1)))
                       for m in PRICE.finditer(text)]
            amounts = [x for x in amounts if 100 <= x <= 50_000_000]
            if amounts:
                context = text[:1200]
                raw_prices = amounts
                card = node
                break
            if node.parent is None:
                break
            node = node.parent
        title = _best_title(anchor, card, url)
        date = DATE.search(context)
        delivery = _delivery_from_snippet(anchor) or (
            date.group(0) if date else "")
        price = min(raw_prices) if raw_prices else 0
        if price:
            priced += 1
        pay_condition = ("Яндекс Пэй" if re.search(
            r"цена\s+с\s+картой\s+яндекс\s+пэй",context,re.I) else "")
        proposal = {
            "url":url, "title":title.strip()[:240],
            "price":price, "context":context[:1100],
            "delivery_hint":delivery, "price_condition":pay_condition,
            "image_url":_image(_snippet(anchor) or card,origin),
            "source":"public_ssr"
        }
        previous = by_url.get(url)
        if previous is None or (proposal["price"] and
            (not previous["price"] or proposal["price"] < previous["price"])):
            by_url[url] = proposal
    products = sorted(by_url.values(),key=lambda x: (
        x["price"] == 0,x["price"] or 10**12,x["url"]))
    return {"links":products, "observed_product_anchors":observed,
            "unique_products":len(products),
            "priced_anchors":priced, "price_fragments":[
                f"{p['price']} ₽" for p in products if p["price"]][:100]}
