"""Navigate supported public storefronts with the actual browser UI.

No HTTP replay, captcha solving, proxy routing or fabricated offers.
Records whether the browser reached a live search and requested price sorting.
"""
from __future__ import annotations

import re
from urllib.parse import parse_qs, parse_qsl, urlencode, urlparse, urlunparse

STOREFRONTS = {
    "Wildberries": "https://www.wildberries.ru/",
    "Ozon": "https://www.ozon.ru/",
    "Яндекс Маркет": "https://market.yandex.ru/",
}
SEARCH_INPUTS = {
    "Wildberries": (
        "#searchInput", "input[name='search']", "input[type='search']",
        "input[placeholder*='Найти']", "input[placeholder*='Поиск']",
        "input[aria-label*='Поиск']",
    ),
    "Ozon": (
        "input[name='text']", "input[type='search']",
        "input[placeholder*='Искать']", "input[placeholder*='Найти']",
        "input[aria-label*='Поиск']",
    ),
    "Яндекс Маркет": (
        "input#header-search", "input[name='text']", "input[type='search']",
        "input[placeholder*='Найти']", "input[placeholder*='Поиск']",
        "input[placeholder*='Искать']", "input[aria-label*='Поиск']",
    ),
}
SORT_MENU = re.compile(r"популярност|сортиров|по цене|по умолчанию|подешевле|подороже|рейтинг", re.IGNORECASE)
SORT_ASC = re.compile(
    r"подешевле|сначала деш[её]в|по возрастанию цен|цен[аы]:?\s*по возрастанию",
    re.IGNORECASE,
)


def search_term(target: str, market: str) -> str:
    params = parse_qs(urlparse(target).query)
    field = "search" if market == "Wildberries" else "text"
    return (params.get(field) or [""])[0][:180]


def is_store_search(url: str, market: str) -> bool:
    p = urlparse(url)
    host = p.hostname or ""
    expected = {"Wildberries":"wildberries.ru", "Ozon":"ozon.ru",
                "Яндекс Маркет":"market.yandex.ru"}.get(market, "")
    if not expected:
        return False
    return (host == expected or host.endswith("." + expected)) and (
        "search" in p.path.lower())


async def open_search_via_ui(page, market: str, target: str, on_search_start=None):
    """Open home, type into the site's real search bar and choose cheap-first.

    Falls back to normal sorted search URL if selectors changed. Each outcome
    is recorded; it never claims to have passed a challenge or sorted prices.
    """
    result = {
        "strategy": "browser_ui",
        "home_http_status": None,
        "home_title": "",
        "search_ui_found": False,
        "search_ui_submitted": False,
        "search_query_typed": False,
        "brand_filter_requested": "",
        "brand_filter_selected": False,
        "sort_ui_selected": False,
        "sort_url_requested": False,
        "search_http_status": None,
        "final_url": "",
    }
    home = STOREFRONTS.get(market)
    if not home:
        if on_search_start: on_search_start()
        response = await page.goto(target, wait_until="domcontentloaded", timeout=35000)
        result["strategy"] = "direct"
        result["search_http_status"] = response.status if response else None
        result["final_url"] = page.url
        return response, result

    try:
        initial = await page.goto(home,wait_until="domcontentloaded",timeout=25000)
        result["home_http_status"] = initial.status if initial else None
        result["home_title"] = (await page.title())[:120]
        await page.wait_for_timeout(800)
    except Exception as e:
        result["home_error"] = type(e).__name__

    search_input = None
    for selector in SEARCH_INPUTS[market]:
        try:
            item = page.locator(selector).first
            if await item.is_visible(timeout=650):
                search_input=item
                break
        except Exception:
            continue

    if search_input is not None:
        result["search_ui_found"] = True
        try:
            query=search_term(target,market)
            await search_input.click(timeout=4000)
            await search_input.fill("")
            await search_input.press_sequentially(query,delay=65)
            result["search_query_typed"] = True
            if on_search_start: on_search_start()
            await search_input.press("Enter")
            await page.wait_for_timeout(1700)
            result["search_ui_submitted"] = is_store_search(page.url,market)
            if result["search_ui_submitted"]:
                # The screenshot's "Яндекс" brand filter is not the same as
                # an exact-name classifier. Apply it through the shop UI only
                # when the requested product explicitly names that brand.
                if market == "Яндекс Маркет" and query.casefold().startswith("яндекс "):
                    result["brand_filter_requested"] = "Яндекс"
                    try:
                        box=page.get_by_role("checkbox",name=re.compile(r"^\s*Яндекс\s*$",re.I)).first
                        if await box.is_visible(timeout=650):
                            if not await box.is_checked():
                                await box.check(timeout=3000)
                            result["brand_filter_selected"] = await box.is_checked()
                            await page.wait_for_timeout(700)
                        else:
                            label=page.locator("label").filter(
                                has_text=re.compile(r"^\s*Яндекс\s*$",re.I)).first
                            if await label.is_visible(timeout=500):
                                await label.click(timeout=2500)
                                await page.wait_for_timeout(700)
                                inner=label.locator('input[type="checkbox"]').first
                                if await inner.count():
                                    result["brand_filter_selected"] = await inner.is_checked()
                    except Exception as e:
                        result["brand_filter_error"] = type(e).__name__
                try:
                    # The current selected sort can already be "Подешевле".
                    selected=page.get_by_role("button",name=re.compile(
                        r"^\s*(?:Подешевле|Сначала деш[её]вые)\s*$",re.I)).first
                    if await selected.is_visible(timeout=650):
                        result["sort_ui_selected"] = True
                    else:
                        control=None
                        for item in (
                            page.get_by_role("button",name=SORT_MENU).first,
                            page.get_by_text(SORT_MENU).first,
                        ):
                            if await item.is_visible(timeout=600):
                                control=item
                                break
                        if control is not None:
                            await control.click(timeout=3500)
                            await page.wait_for_timeout(350)
                            for option in (
                                page.get_by_role("option",name=SORT_ASC).first,
                                page.get_by_role("menuitem",name=SORT_ASC).first,
                                page.get_by_text(SORT_ASC).last,
                            ):
                                if await option.is_visible(timeout=650):
                                    await option.click(timeout=3500)
                                    await page.wait_for_timeout(1150)
                                    # Verify the visible selection or a site-
                                    # generated ascending-sort navigation.
                                    chosen=page.get_by_role("button",name=re.compile(
                                        r"^\s*(?:Подешевле|Сначала деш[её]вые)\s*$",re.I)).first
                                    result["sort_ui_selected"] = (
                                        await chosen.is_visible(timeout=600) or
                                        ("how=aprice" in page.url and market=="Яндекс Маркет") or
                                        ("sort=priceup" in page.url and market=="Wildberries") or
                                        ("sorting=price" in page.url and market=="Ozon"))
                                    break
                except Exception as e:
                    result["sort_ui_error"] = type(e).__name__
        except Exception as e:
            result["search_ui_error"] = type(e).__name__

    # Storefronts may deny the homepage or a prebuilt sorted URL while
    # serving the ordinary search page. Try that page in Chromium first,
    # then use the visible search, filter and sort controls.
    # This distinction is audited: URL navigation is never a UI search.
    if not result["search_ui_submitted"] and market in ("Wildberries","Ozon","Яндекс Маркет"):
        parsed=urlparse(target)
        plain_params=[(k,v) for k,v in parse_qsl(parsed.query,keep_blank_values=True)
                      if k not in ("sort","sorting","how")]
        plain=urlunparse(parsed._replace(query=urlencode(plain_params)))
        if on_search_start: on_search_start()
        try:
            plain_response=await page.goto(
                plain,wait_until="domcontentloaded",timeout=30000)
            result["plain_search_fallback"]=True
            result["plain_search_http_status"]=(
                plain_response.status if plain_response else None)
            response=plain_response
            await page.wait_for_timeout(900)
            initial_plain_title=(await page.title()).lower()
            result["plain_blocked"]=any(x in initial_plain_title
                for x in ("похоже, нет соединения","вы не робот","access denied",
                          "подтвердите, что запросы отправляли вы"))
            if (plain_response and plain_response.status==200
                    and not result["plain_blocked"]):
                entry=None
                for selector in SEARCH_INPUTS[market]:
                    try:
                        item=page.locator(selector).first
                        if await item.is_visible(timeout=600):
                            entry=item
                            break
                    except Exception:
                        continue
                if entry is not None:
                    result["search_ui_found"]=True
                    try:
                        await entry.click(timeout=3500)
                        await entry.fill("")
                        await entry.press_sequentially(
                            search_term(target,market),delay=65)
                        result["search_query_typed"]=True
                        await entry.press("Enter")
                        await page.wait_for_timeout(1300)
                        plain_title=(await page.title()).lower()
                        result["search_ui_submitted"]=is_store_search(
                            page.url,market) and not any(x in plain_title
                            for x in ("похоже, нет соединения","вы не робот",
                                      "access denied"))
                    except Exception as exc:
                        result["plain_ui_error"]=type(exc).__name__
                if result["search_ui_submitted"]:
                    result["strategy"]="plain_then_ui_search"
                else:
                    result["strategy"]="plain_browser_search"
                if market=="Яндекс Маркет" and search_term(target,market).casefold().startswith("яндекс "):
                    result["brand_filter_requested"]="Яндекс"
                    try:
                        brand=page.get_by_role("checkbox",name=re.compile(r"^\s*Яндекс\s*$",re.I)).first
                        if await brand.is_visible(timeout=550):
                            if not await brand.is_checked():
                                await brand.check(timeout=3000)
                            result["brand_filter_selected"]=await brand.is_checked()
                            await page.wait_for_timeout(650)
                    except Exception as exc:
                        result["brand_filter_error"]=type(exc).__name__
                # Sort using the visible store control, not a guessed URL.
                try:
                    selected=page.get_by_role("button",name=re.compile(
                        r"^\s*(?:Подешевле|Сначала деш[её]вые)\s*$",re.I)).first
                    if await selected.is_visible(timeout=550):
                        result["sort_ui_selected"]=True
                    else:
                        control=page.get_by_text(SORT_MENU).first
                        if await control.is_visible(timeout=900):
                            await control.click(timeout=3500)
                            await page.wait_for_timeout(350)
                            choice=page.get_by_text(SORT_ASC).last
                            if await choice.is_visible(timeout=900):
                                await choice.click(timeout=3500)
                                await page.wait_for_timeout(950)
                                chosen=page.get_by_role("button",name=re.compile(
                                    r"^\s*(?:Подешевле|Сначала деш[её]вые)\s*$",re.I)).first
                                result["sort_ui_selected"]=(
                                    await chosen.is_visible(timeout=550) or
                                    ("sort=priceup" in page.url and
                                     market=="Wildberries") or
                                    ("sorting=price" in page.url and
                                     market=="Ozon") or
                                    ("how=aprice" in page.url and
                                     market=="Яндекс Маркет"))
                except Exception as exc:
                    result["plain_sort_ui_error"]=type(exc).__name__
                if result["sort_ui_selected"]:
                    result["strategy"]+="_sorted_ui"
                else:
                    result["strategy"]+="_sort_unconfirmed"
                # If re-submitting loses the search page, restore the
                # already accessible regular search before scanning cards.
                html_title=(await page.title()).lower()
                if any(x in html_title for x in (
                        "похоже, нет соединения","вы не робот","access denied")):
                    response=await page.goto(
                        plain,wait_until="domcontentloaded",timeout=22000)
                    result["plain_search_restored"]=True
                    result["search_ui_submitted"]=False
                    result["sort_ui_selected"]=False
                    result["strategy"]="plain_search_restored_unsorted"
        except Exception as exc:
            result["plain_search_error"]=type(exc).__name__

    # Only try the sorted URL after both visible-UI navigation and plain
    # browsing have failed. A working plain result must not be discarded.
    plain_working=(market in ("Wildberries","Ozon","Яндекс Маркет") and
                   result.get("plain_search_fallback") and
                   not result.get("plain_blocked") and
                   response is not None and response.status==200)
    if not result["search_ui_submitted"] and not plain_working:
        if on_search_start: on_search_start()
        response=await page.goto(target,wait_until="domcontentloaded",timeout=35000)
        result["sort_url_requested"]=True
        result["strategy"]="url_last_resort"
    elif not plain_working:
        response = None
        result["strategy"]=("ui_search_sorted_ui" if result["sort_ui_selected"]
                            else "ui_search_sort_unconfirmed")
    result["final_url"] = page.url
    # A page reached through UI has no reliable Playwright Response object
    # after the navigation has completed. HTTP errors are observed elsewhere.
    result["search_http_status"] = (
        response.status if hasattr(response,"status") and isinstance(response.status,int)
        else None)
    return (response if hasattr(response,"status") and
            isinstance(response.status,int) else None),result
