"""Conservative public HTML product evidence extraction.

No model inference or prices attributed across multiple products.
Used only when a public storefront serves HTML but Chromium receives an
HTTP denial from the same network. A single SSR page is never labelled a
complete search.
"""
from __future__ import annotations

from html.parser import HTMLParser
from urllib.parse import urljoin
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


def fetch_public_search_html(url: str) -> dict:
    """Normal public HTTPS transport, used if Chromium is denied.

    No accounts, credentials, CAPTCHA solving, or proxy. Preserve every
    visible search-card URL and mark the page as a partial snapshot.
    """
    import urllib.request
    from .core import host_allowed
    if not host_allowed(url):
        raise ValueError("Storefront URL required")
    request = urllib.request.Request(url, headers={
        "User-Agent":("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"),
        "Accept":"text/html,application/xhtml+xml;q=0.9,*/*;q=0.8",
        "Accept-Language":"ru-RU,ru;q=0.9"
    })
    try:
        with urllib.request.urlopen(request,timeout=18) as response:
            if response.status != 200 or not host_allowed(response.url):
                return {}
            if "text/html" not in response.headers.get("Content-Type","").lower():
                return {}
            raw = response.read(8_000_001)
            if len(raw) > 8_000_000:
                return {}
            parsed = extract_public_html(raw.decode("utf-8","replace"),response.url)
            if not parsed["links"]:
                return {}
            return {
                "state":"ok", "url":response.url,
                "title":"Публичная выдача",
                "links":parsed["links"],
                "price_fragments":parsed["price_fragments"],
                "text":"Публичная поисковая выдача магазина",
                "coverage_complete":False,
                "diagnostic":"HTML-выдача: список товаров, охват неполный",
                "audit":{
                    "source":"public_html","http_status":200,
                    "raw_seen":parsed["observed_product_anchors"],
                    "unique_products":parsed["unique_products"],
                    "priced_products":sum(x["price"]>0 for x in parsed["links"]),
                    "dom_snapshots":0,
                    "sort_requested":True,
                    "coverage_complete":False,
                }
            }
    except (OSError,ValueError,UnicodeError):
        return {}


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
        a_text = _text(anchor, 240).strip()
        title = (anchor.attrs.get("aria-label") or
                 anchor.attrs.get("title") or
                 a_text or "")
        if not title:
            todo = [card]
            while todo:
                child = todo.pop()
                if child.tag == "img" and child.attrs.get("alt"):
                    title = child.attrs["alt"]
                    break
                todo.extend(reversed(child.children))
        if not title and context:
            title = context[:130]
        date = DATE.search(context)
        price = min(raw_prices) if raw_prices else 0
        if price:
            priced += 1
        proposal = {
            "url":url, "title":title.strip()[:240],
            "price":price, "context":context[:1100],
            "delivery_hint":date.group(0) if date else "",
            "image_url":_image(card,origin),
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
