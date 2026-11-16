# MarketRadar

MarketRadar compares exact product models across Ozon, Wildberries, and Yandex Market. It is an **in-development browser-search test**, not a commercially validated price-comparison service.

## Mobile and cloud mode (1.8 staging)

- The FastAPI site in `web/` now has a **Cloud search** mode that can submit background jobs from Android without a browser extension. It uses server-side headless Playwright plus the server-paid AI model to browse public pages. It cannot access a user's Ozon/WB/Yandex Market app logins or guarantee full marketplace coverage.
- Set `CLOUD_BROWSER_ENABLED=true`, provision server-side `OPENAI_API_KEY`, install Chromium via Playwright, and deploy the Docker service for this mode. The `render.yaml` is a blueprint, **not proof that a service is deployed**. If absent, the API returns 503 and the UI reports cloud search unavailable.
- The currently published GitHub Pages address is **only a browser-extension test**. On mobile it must not claim to provide the cloud search. The public GitHub Pages site and the FastAPI site are different deployments until a single production domain is configured.

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
