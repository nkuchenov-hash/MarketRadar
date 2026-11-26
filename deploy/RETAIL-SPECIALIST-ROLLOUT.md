# Retail specialist rollout — WB / Yandex Market

## Current gate
- Production uses the existing Ozon sidecar and browser fallback.
- New Node workers are optional and disabled until actual acceptance.
- The current restricted managed deployment helper cannot install additional containers, modify compose or configure secrets. Do not expand it to arbitrary shell execution.
- A VPS administrator must authorize and execute the one-time staging script. It installs workers without changing production routing. It uses a root-owned configuration file, no public worker ports and no purchase actions.

## Provider configuration
Store actual keys only in `/etc/marketradar/retail-specialists.env` (root:root 0600), never in Git or the frontend.
Supported per-market providers: `WB_RETAIL_PROVIDER=apisystem|parserdata`, `YM_RETAIL_PROVIDER=apisystem|parserdata`.
ApiSystem requires `APISYSTEM_WB_KEY` / `APISYSTEM_YM_KEY`, requested `APISYSTEM_WB_GPS` / `APISYSTEM_YM_REGION_ID`.
ParserData requires `PARSERDATA_WB_KEY` / `PARSERDATA_YM_KEY` and `PARSERDATA_REGION_ID`. Set `PARSERDATA_MAX_CARDS` only after budget approval. Its default is three card lookups per search. Region support depends on the provider.
Provider costs and region support are not yet validated; health checks do not consume search quota.

## Acceptance
1. Stage workers using `deploy/stage-retail-specialists.sh` on the VPS from a reviewed release.
2. Run a bounded test on each worker from inside its own container. Explicitly approve the number of requests, including detail tasks, before submitting. Verify exact model, checkout price condition, delivery city and date, source URL, image and total cost if known.
3. Compare against manually observed offers in the same region and account/payment conditions. Unknown data is not a pass. A provider-supplied detail result is not independent marketplace verification.
4. Only after acceptance configure backend endpoints `MARKETRADAR_WB_SPECIALIST_URL=http://wb-specialist:8765` and `MARKETRADAR_YM_SPECIALIST_URL=http://ym-specialist:8765` using an authorized compose overlay; verify backend and existing Ozon health. Preserve the live overlay and allow rollback. The current root helper does not have this permission.
5. Run true fast, browser-deep and automatic searches separately. No browser result may count as a specialist success.
6. Run representative 100-product benchmark, then an approved 72-hour stability test. Do not execute paid calls on a recurring schedule without a quota/cost limit and explicit approval.

Source contracts: https://parserdata.ru/docs.html and https://www.apisystem.name/docs
