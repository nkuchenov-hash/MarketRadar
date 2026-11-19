"""Navigate supported public storefronts with the actual browser UI.

No HTTP replay, captcha solving, proxy routing or fabricated offers.
Records whether the browser reached a live search and requested price sorting.
"""
from __future__ import annotations

import re
from urllib.parse import parse_qs, urlparse

STOREFRONTS = {
    "Wildberries": "https://www.wildberries.ru/",
    "Ozon": "https://www.ozon.ru/",
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
}
SORT_MENU = re.compile(r"популярност|сортиров|по цене", re.IGNORECASE)
SORT_ASC = re.compile(
    r"сначала деш[её]в|по возрастанию цен|цен[аы]:?\s*по возрастанию",
    re.IGNORECASE,
)


def search_term(target: str, market: str) -> str:
    params = parse_qs(urlparse(target).query)
    field = "search" if market == "Wildberries" else "text"
    return (params.get(field) or [""])[0][:180]


def is_store_search(url: str, market: str) -> bool:
    p = urlparse(url)
    host = p.hostname or ""
    expected = "wildberries.ru" if market == "Wildberries" else "ozon.ru"
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
            if on_search_start: on_search_start()
            await search_input.press("Enter")
            await page.wait_for_timeout(1700)
            result["search_ui_submitted"] = is_store_search(page.url,market)
            if result["search_ui_submitted"]:
                try:
                    menu=page.get_by_text(SORT_MENU).first
                    if await menu.is_visible(timeout=900):
                        await menu.click(timeout=3500)
                        await page.wait_for_timeout(350)
                        choice=page.get_by_text(SORT_ASC).first
                        if await choice.is_visible(timeout=900):
                            await choice.click(timeout=3500)
                            await page.wait_for_timeout(1150)
                            result["sort_ui_selected"] = True
                except Exception as e:
                    result["sort_ui_error"] = type(e).__name__
        except Exception as e:
            result["search_ui_error"] = type(e).__name__

    # The sorted URL remains an important explicit request; a website may
    # ignore it, so the audit never equates query string with proven sorting.
    if not result["search_ui_submitted"] or not result["sort_ui_selected"]:
        if on_search_start: on_search_start()
        response=await page.goto(target,wait_until="domcontentloaded",timeout=35000)
        result["sort_url_requested"]=True
        result["strategy"]=("ui_search_sorted_url" if result["search_ui_submitted"]
                            else "home_then_sorted_url")
    else:
        response = None
        result["strategy"]="ui_search_sorted_ui"
    result["final_url"] = page.url
    # A page reached through UI has no reliable Playwright Response object
    # after the navigation has completed. HTTP errors are observed elsewhere.
    result["search_http_status"] = (
        response.status if hasattr(response,"status") and isinstance(response.status,int)
        else None)
    return (response if hasattr(response,"status") and
            isinstance(response.status,int) else None),result
