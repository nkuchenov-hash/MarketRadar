"""One-off standard Edge compatibility test on the existing manual VPS container.

No profile/cookie reuse, no identity modifications, no retries after a denial.
A successful manual session does not imply permission or success in automation.
"""
import asyncio
import json
import time
from datetime import datetime, timezone
from playwright.async_api import async_playwright
from server.cloud_browser import _DOM_SNAPSHOT, canonical
from server.mobile_cloud import exact_product
from server.human_navigation import visible_search_input, visible_sort_control, SORT_ASC

QUERY = "Яндекс Станция Мини 3 Про"
BLOCK = ("antibot challenge", "похоже, нет соединения", "подозрительная активность",
         "access denied", "вы не робот", "captcha-support@", "докажите, что вы не робот")

async def main():
    started = time.monotonic()
    result = {"browser": "Microsoft Edge Stable / standard Playwright channel",
              "query": QUERY, "timestamp_utc": datetime.now(timezone.utc).isoformat(),
              "status": "error", "offers": [], "sort_confirmed": False}
    denied = []
    def stage(name):
        result["stage"] = name
        print(f"[{round(time.monotonic()-started,1)}s] {name}", flush=True)
    stage("Запуск Edge Stable")
    async with async_playwright() as pw:
        browser = await pw.chromium.launch(channel="msedge", headless=False,
                                           chromium_sandbox=True, timeout=15000)
        try:
            stage("Создание отдельного профиля")
            context = await browser.new_context(locale="ru-RU")
            page = await context.new_page()
            def response_received(response):
                if response.request.resource_type == "document" and response.status in (401,403,429,498,503):
                    denied.append(response.status)
            page.on("response", response_received)
            async def blocked():
                title = await page.title()
                body = await page.locator("body").inner_text(timeout=5000)
                return bool(denied or any(word in (title+" "+body[:5000]).lower() for word in BLOCK))
            stage("Открытие главной Ozon")
            response = await page.goto("https://www.ozon.ru/", wait_until="domcontentloaded", timeout=30000)
            result["home_http"] = response.status if response else None
            await page.wait_for_timeout(1200)
            if await blocked():
                result["status"] = "blocked"
                return result
            stage("Поиск поля ввода")
            field = await visible_search_input(page, "Ozon", wait_ms=6000)
            if field is None:
                result["status"] = "search_input_missing"
                return result
            stage("Поиск товара")
            await field.fill(QUERY, timeout=5000)
            await field.press("Enter", timeout=5000)
            await page.wait_for_timeout(2000)
            if await blocked():
                result["status"] = "blocked"
                return result
            stage("Сортировка по цене")
            sort = await visible_sort_control(page)
            if sort is not None:
                await sort.click(timeout=4000)
                choice = page.get_by_text(SORT_ASC).last
                await choice.wait_for(state="visible",timeout=3000)
                await choice.click(timeout=4000)
                await page.wait_for_timeout(1500)
                result["sort_confirmed"] = True
            if await blocked():
                result["status"] = "blocked"
                return result
            offers = {}
            for step in range(12):
                if await blocked():
                    result["status"] = "blocked"
                    result["offers"] = []
                    return result
                stage(f"Чтение выдачи: {step+1}/12")
                snap = await asyncio.wait_for(page.evaluate(_DOM_SNAPSHOT), timeout=8)
                for card in snap.get("products", []):
                    if card.get("price",0)>0 and exact_product(QUERY,card.get("title","")):
                        key = canonical(card["url"])
                        offers[key] = {k:card.get(k) for k in ("title","price","delivery_hint","image_url","url")}
                if snap.get("scroll_y",0)+snap.get("viewport",0)>=snap.get("scroll_height",0)-100:
                    break
                await page.mouse.wheel(0,780)
                await page.wait_for_timeout(600)
            result["offers"] = sorted(offers.values(),key=lambda o:o["price"])[:12]
            result["status"] = "listing_observed" if offers else "no_matching_cards"
            return result
        except Exception as exc:
            result["error"] = type(exc).__name__
            return result
        finally:
            result["blocked_http"] = denied
            result["elapsed_seconds"] = round(time.monotonic()-started,1)
            stage("Закрытие тестового браузера")
            await asyncio.wait_for(browser.close(), timeout=5)

if __name__ == "__main__":
    try:
        print(json.dumps(asyncio.run(asyncio.wait_for(main(), timeout=65)),ensure_ascii=False,indent=2))
    except Exception as exc:
        print(json.dumps({"status":"test_timeout_or_browser_error","error":type(exc).__name__},ensure_ascii=False))
