# MarketRadar — cloud staging

Web app for comparing exact product models across Ozon, Wildberries, and Yandex Market using a browser extension in each user’s existing signed-in browser.

**Status: staging / not approved for commercial public use.** Live marketplace searches have not passed end-to-end acceptance testing. The service must not claim a universal lowest price when some stores cannot be checked.

## Deployment

The root [`render.yaml`](render.yaml) defines one Render web service and a persistent disk. From the Render Dashboard, choose **New → Blueprint**, connect this GitHub repository, and deploy `main` using `render.yaml`. Render's Starter web service with a persistent disk incurs charges.

Set `OPENAI_API_KEY` as a secret at Blueprint creation. This is a **server-side key**; customers do not supply a key. Without it, the web interface starts but AI-powered searches are unavailable. For test payments, configure Stripe variables separately and verify webhooks before enabling subscriptions.

The user must install the browser extension downloaded from `/download/extension`, pair it with the site, and remain logged in to their marketplace accounts in their normal browser. Automation is best-effort and may be blocked; it does not bypass site challenges.

Run checks with `python -m pip install -r requirements.txt pytest && python -m pytest -q tests`.

See [DEPLOY_RU.md](DEPLOY_RU.md) for deployment notes and remaining launch prerequisites.

**Do not put API keys, marketplace credentials, user browser profiles, SQLite files or payment secrets in this repository.**
