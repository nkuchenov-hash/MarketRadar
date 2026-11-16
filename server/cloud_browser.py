"""Best-effort, isolated, unauthenticated browser for server-side searches.

No marketplace account, password, browser profile, or cookies are copied from a
user device. Challenges and 403/429 responses are reported, never bypassed.
"""
from __future__ import annotations

import asyncio
import os
from .core import host_allowed, BLOCK_WORDS

def available():
    if os.getenv("CLOUD_BROWSER_ENABLED", "false").lower() not in ("1", "true", "yes"):
        return False, "Облачный браузер ещё не развёрнут на сервере"
    try:
        from playwright.async_api import async_playwright  # noqa: F401
    except ImportError:
        return False, "На сервере не установлен Playwright"
    return True, "Облачный поиск: публичные цены (без входа в аккаунты магазинов)"

class CloudBrowser:
    """One isolated Chromium context per search job. Limited simultaneous workers."""
    _slots = asyncio.Semaphore(2)

    def __init__(self):
        self.playwright = None
        self.browser = None
        self.context = None
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
        page = await self.context.new_page()
        try:
            response = await page.goto(url, wait_until="domcontentloaded", timeout=35000)
            await page.wait_for_timeout(1900)
            current = page.url
            if not host_allowed(current):
                return {"state": "error", "url": url, "text": "Запрещённый редирект", "links": []}
            view = await page.evaluate("""() => {
                const body = document.body;
                const raw = body ? body.innerText : '';
                const fragments = raw.split('\\n').map(x => x.trim())
                    .filter(x => x.length < 240 && /[₽]|руб\\.?|\\bRUB\\b/i.test(x)).slice(0, 70);
                const links = [...document.querySelectorAll('a[href]')].slice(0, 1500)
                    .map(a => ({
                        url: a.href,
                        title: (a.innerText || a.getAttribute('aria-label') || '').trim().slice(0, 220),
                        context: (a.parentElement?.innerText || '').trim().slice(0, 240)
                    }));
                return {
                    title: document.title || '',
                    text: (fragments.join('\\n') + '\\n' + raw).slice(0, 60000),
                    price_fragments: fragments,
                    links
                };
            }""")
            text = str(view.get("text", ""))
            blocked = (response and response.status in (403, 429, 503)) or any(
                term in text.lower() for term in BLOCK_WORDS)
            return {
                "state": "blocked" if blocked else "ok",
                "url": current, "title": str(view.get("title", ""))[:250],
                "text": text, "links": view.get("links", [])[:1500],
                "price_fragments": view.get("price_fragments", [])[:70],
            }
        except Exception as exc:
            name = type(exc).__name__
            return {"state": "timeout" if "Timeout" in name else "error", "url": url,
                    "title": "", "text": "", "links": [], "diagnostic": name}
        finally:
            await page.close()
