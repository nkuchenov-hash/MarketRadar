from __future__ import annotations
from urllib.parse import urlparse, urlunparse
import re
from datetime import datetime, timezone

MARKETS = {
    'Ozon': {'host':'ozon.ru','search':'https://www.ozon.ru/search/?text={q}'},
    'Wildberries': {'host':'wildberries.ru','search':'https://www.wildberries.ru/catalog/0/search.aspx?search={q}'},
    'Яндекс Маркет': {'host':'market.yandex.ru','search':'https://market.yandex.ru/search?text={q}'}
}
BLOCK_WORDS = ('подозрительная активность','похоже, нет соединения',
               'captcha-support@','проверка браузера','докажите, что вы не робот',
               'access denied','слишком много запросов','вы не робот','robot check')

def host_allowed(url: str) -> bool:
    try:
        p=urlparse(url)
        if p.scheme!='https' or not p.hostname or p.username or p.password:
            return False
        h=p.hostname.lower()
        return any(h==v['host'] or h.endswith('.'+v['host']) for v in MARKETS.values())
    except (ValueError, TypeError):return False

def market_of(url: str) -> str|None:
    if not host_allowed(url):return None
    host=urlparse(url).hostname.lower()
    for name,v in MARKETS.items():
        if host==v['host'] or host.endswith('.'+v['host']):return name
    return None

def is_product_link(market: str,url: str) -> bool:
    if not host_allowed(url) or market_of(url)!=market:return False
    p=urlparse(url).path.lower()
    if market=='Ozon':return '/product/' in p
    if market=='Wildberries':return bool(re.search(r'/catalog/\d+/detail',p))
    return any(s in p for s in ('/product/','/card/','/offer/','/product--'))

def canonical(url: str) -> str:
    p=urlparse(url)
    return urlunparse((p.scheme,p.netloc,p.path.rstrip('/'),'', '', ''))

def normalize(value:str)->str:
    return re.sub(r'[^\w]+',' ',(value or '').lower().replace('ё','е')).strip()

def model_match(query:str, title:str)->bool:
    """Hard block obvious different models. AI must also mark an exact match."""
    q,t=normalize(query),normalize(title)
    forbidden=('чехол','защитное стекло','подставка','кронштейн','модуль','кабель',
               'адаптер','наклейка','пульт','держатель','аксессуар')
    if any(w in t for w in forbidden) and not any(w in q for w in forbidden):return False
    if 'мини 3 про' in q or 'mini 3 pro' in q or 'yndx 00059' in q:
        return bool(re.search(r'(?:мини|mini)\s*3\s*(?:про|pro)\b', t) or
                    re.search(r'yndx\s*0*0059(?:blk|blu|gry|grn)?\b',t))
    # For arbitrary products, AI handles the model match. Reject obvious numeric model conflict.
    keys=[x for x in q.split() if (any(ch.isdigit() for ch in x) and len(x)>2)]
    if keys and not any(x in t.split() for x in keys):return False
    return True

def price_in_text(amount: int, text: str) -> bool:
    if not isinstance(amount,int) or not 1<=amount<=50_000_000:return False
    s=str(amount)
    pat=(re.escape(s[:-3])+r'[\s\u00a0\u202f.,]?'+re.escape(s[-3:])) if len(s)>3 else re.escape(s)
    return re.search(r'(?<!\d)'+pat+r'(?!\d)',text) is not None

def evidence_in_text(evidence: str,text:str)->bool:
    def n(s):return re.sub(r'\s+',' ',(s or '').replace('\xa0',' ').replace('\u202f',' ')).strip().lower()
    return len(n(evidence))>=3 and n(evidence) in n(text)

def now():return datetime.now(timezone.utc).isoformat(timespec='seconds')
