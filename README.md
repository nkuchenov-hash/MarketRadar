# MarketRadar

MarketRadar compares exact product models across Ozon, Wildberries, and Yandex Market. It is an **in-development browser-search test**, not a commercially validated price-comparison service.

## Android cloud Chromium search (v0.35.0)

The Android app can use the FastAPI server as its primary search engine.
Open **Settings → Облачный браузер**, enter the *deployed* HTTPS root URL,
save, then search normally. The UI receives live job progress and verified
product cards with images, prices and delivery hints. No marketplace login
is needed. Without a configured server URL the previous on-phone search is
retained as a fallback.

Server entry points:

- `POST /api/mobile/browser/start`: `{"query":"...","shops":["ym","wb","ozon"]}`
- `GET /api/mobile/browser/jobs/{id}`: status, per-shop audit and live offers
- `DELETE /api/mobile/browser/jobs/{id}`: cancel the server search

Jobs run asynchronously in real Playwright Chromium. If Android is destroyed,
it retains the task ID and attempts to resume the server result when reopened.
A cancelled job is not resumed. The server runs with one Uvicorn worker, since
the current live job registry is in-memory, and uses per-IP limits to prevent
unauthenticated abuse. These public-only mobile endpoints do not require an
OpenAI API key; actual product-page title matching uses deterministic rules.

Deployment prerequisites:

1. Deploy the FastAPI server with the repository Dockerfile on a server with
   working storefront connectivity and set `CLOUD_BROWSER_ENABLED=true`.
2. Optionally use `MARKETRADAR_CHROMIUM_CDP_URL=wss://...` to connect the
   server to a persistent remote Chromium browser instead of launching the
   local container Chromium. Store endpoint credentials only server-side.
3. Check `/health`, then run an actual query and inspect the per-market audit.
4. Set that service's public HTTPS root URL in Android Settings.

**Live evidence from GitHub-hosted Chromium (Nov 18, 2026):** Yandex Market
returns HTTP 403, Wildberries HTTP 498, and Ozon HTTP 403. These server
egress IPs cannot obtain product listings. Successful offline Chromium tests
prove the extraction path but do not establish real marketplace coverage.
The Render blueprint is configuration only; it is not evidence of a running
service. Do not distribute an APK as a successful cloud-search release until
a deployed service returns actual prices.

## Web test

- The GitHub Pages publication workflow serves everything in `live-preview/`.
- GitHub Pages must first be enabled once by the repository owner under **Settings → Pages → Source: GitHub Actions**. After that, every relevant push redeploys the site.
- Web address after activation: `https://nkuchenov-hash.github.io/MarketRadar/`.
- Until Pages is enabled, the raw.githack.com preview may show an external content warning and is not a production hosting solution.
- Install `live-preview/MarketRadar_Browser_Extension.zip` unpacked in Chrome or Yandex Browser using `browser://extensions` in developer mode. After installation refresh the page. The extension includes a functional popup with connection diagnostics.

**Known limitations:** cloud browsing and extension browsing may be blocked by marketplace anti-bot restrictions. Only observed page prices are displayed, not guaranteed final checkout costs or universal minimums. Automatic search through an existing authorized browser session is experimental. The browser test does not use an LLM; it must not be marketed as a finished AI shopping agent.

## Server staging

The `render.yaml` describes an optional cloud server with user accounts and task coordination. It has not been deployed or accepted for commercial use. The `downloads/marketradar-extension.zip` is included for the server's extension download endpoint. Marketplace sessions and API keys must never be committed. AI provider credentials are server-only.

## Tests

`python -m pip install -r requirements.txt pytest && python -m pytest -q tests`

`node extension-preview/test_match.js && node extension-preview/test_matching_extended.js`

CI executes backend tests and extension syntax checks. Live marketplace testing is a separate acceptance step.
