# Product earning ranges and claims

Admin requests now use `minimumDailyIncome` and `maximumDailyIncome` (positive rupees with at most two decimal places, minimum <= maximum). Existing fixed-rate products migrate to equal bounds. Legacy zero-rate products must be edited before another purchase is allowed.

A new purchase snapshots the range and pre-generates one secure-random integer-paise amount per day in `app_private.product_daily_amounts`. These rows are immutable and never supplied by the browser. Admin edits cannot change sold terms. Amounts are credited only by a successful claim, in the same database transaction as the claim record. User and purchase locks plus unique purchase/day and ledger source constraints prevent duplicate credits. A failed claim rolls back its credit.

Day 1 opens at successful payment. Day 2 starts at the next midnight in Asia/Kolkata. An N-day purchase ends at midnight after the Nth calendar earning day. Missed daily claims expire; there is no catch-up claim. Existing paid purchases retain their historical rates and schedules. The final successful claim completes the purchase immediately.

Today's Profit is the persisted daily amount, including after it has been claimed that day; Current Claim becomes zero after claiming. After the cycle ends, the overview reports the final generated daily profit separately from total actually claimed. Remaining earning potential is the currently claimable amount plus the maximum for future days, excluding expired claims. The illustrative chart uses arbitrary index units and has no path into wallet or claim arithmetic.

## Claim progress and catalog settings

Admin uses `totalClaims` (1?3650) to retain the earning cap, with no start, end, or duration input. The existing database `duration_days` column stores this count so purchase earning rules remain compatible. My Products displays successful claims / total claims and actually credited earnings. Progress refreshes after claims and every ten seconds while visible; missed claims and elapsed time never advance the bar. A completed purchase with missed claims can correctly show less than 100%.

## Daily Earning Cycle

Each Daily Earning Cycle uses midnight through 23:59:59 Asia/Kolkata. The backend computes `dailyEarningCycle.accruedPaise` using integer-paise arithmetic from the saved final amount and server time. It is zero at midnight and reaches the exact saved amount at 23:59:59. On the purchase day, display progress reflects elapsed time in that calendar day; purchase eligibility still begins only after successful payment. Refreshing at the same server time gives the same amount and progression.

This is display progress, not another wallet balance or a partial-claim limit. A claim always credits the full saved final amount immediately when eligible, including the first day. The display can keep progressing after that day's claim without creating another credit. `completedDays` always counts successful claim records; `remainingDays` is duration minus that count, even if missed days have expired. The earning-period status separately indicates expiry.

`GET /api/purchases/{id}/performance?interval=15&range=24` is owner-authenticated and returns deterministic backend OHLC index samples, cycle metadata, and server time. Supported intervals are 1/5/15/60 minutes, and ranges are 1/6/24 hours. Every interval aggregates the same cycle-derived signal. Closed candles remain fixed; the developing candle changes with server time. The browser polls every five seconds, animates index transitions, and offers range selection, zoom, hover/touch and keyboard inspection. It does not calculate monetary progression or change a claim amount.

Catalog availability depends only on the active flag. Admin can Stop or Activate a product without dates or timers. Stopped products with paid purchase history remain visible with SOLD OUT. Inactive unsold drafts remain hidden. A sold count alone does not exhaust inventory: the existing catalog has no quantity limit. Purchase transactions continue to lock and recheck availability before debiting. Each user can purchase a product only once, including after completion. Repeat POST /api/purchases requests return HTTP 409 before any debit. The existing V8 UNIQUE(user_id, product_id) constraint and user transaction locks enforce this under concurrency. Catalog responses include only the authenticated user's paid purchase IDs; those products show SOLD and disabled BUY controls. Other users may still buy the same available product. Existing purchase detail/checkout endpoints remain available for receipt recovery.

## Deployment

Flyway V13 adds range snapshots, persistent daily amounts, migration backfill, and claim guards. V14 replaces the former hardcoded withdrawal trigger. Apply through the normal application startup migration process before using the new application version. Do not edit or rerun historical migrations. V20 makes legacy catalog start/end columns optional. They are retained only as historical data and are no longer read or written by catalog or availability logic. Existing purchase schedules and claim guards are unchanged.

`withdrawal.minimum-amount=${WITHDRAWAL_MINIMUM_AMOUNT:10}` in application.properties defaults to INR 10. Change the property or environment variable to 100 and restart to raise it without a code change. The API supplies this minimum to the UI and sets it transaction-locally for database validation. Existing idempotent withdrawal retries remain recoverable after changing the minimum.

## Validation

The Maven suite uses disposable PostgreSQL databases and covers purchase debit, first claim, IST midnight boundaries, decimal/equal bounds, min/max guards, refresh consistency, simultaneous purchase/claim/withdrawal requests, rollback, completion, minimum configuration, referrals, Razorpay verification and withdrawal lifecycle.

`src/test/browser/withdrawal.cjs` uses the disposable HTTP/database fixture for compact cards, overview interactions/reload, real claim ledger credits, admin range edits, INR 10 withdrawal, lost-response recovery, completion/refund and responsive layouts. Set PLAYWRIGHT_MODULE to an installed Playwright package and generate target/wallet-classpath.txt with Maven dependency:build-classpath before running it. Test fixtures never connect to live payment or bank APIs.

Verified 2026-09-26: 153 JVM tests pass in the full regression suite, with zero remaining failures or errors. Coverage includes exact midnight/23:59:59 boundaries, fixed decimal final amounts, monotonic server-calculated progression, one claim out of 40 correctly reporting 1 completed / 39 remaining, persistent closed candles, interval aggregation, changing live candles, ownership checks, sold-out API rejection, claim/withdrawal concurrency, and rollback.

The final headless browser flow passed at 1440, 768, 390, and 320 pixels. It checks live backend cycle progression without changing Today's Profit, range/zoom controls, reload consistency, successful claims, SOLD OUT listing/detail controls and API rejection, admin range editing, INR 10 withdrawals, response-loss recovery, completion and refunds. No live payment, bank, or production database was used.

The purchase.cjs browser flow verifies BUY to SOLD on listing and detail pages, reload persistence, duplicate POST rejection, unchanged Recharge Balance after rejection, My Products, and a second account independently buying the same product. PostgreSQL tests verify the unique user/product constraint directly, concurrent purchases producing one success and one HTTP 409, and rejection after product completion.
