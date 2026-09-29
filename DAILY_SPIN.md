# Daily Spin / Spin & Earn

The existing `/features/renib-bonus` shortcut now opens Spin & Earn. The dashboard label is updated; existing bookmarks keep working.

## Rules and configuration

- One free spin per authenticated user per calendar date in **Asia/Kolkata**, resetting at **00:00 IST**. No browser clock or client-supplied reward determines eligibility or value.
- Prizes are fixed at ₹1, ₹2, ₹5, ₹10, ₹20 and ₹30. All displayed prizes have a positive chance.
- `spin.reward-weights=${SPIN_REWARD_WEIGHTS:50,25,12,7,4,2}` supplies relative integer weights in that prize order. Defaults are 50%, 25%, 12%, 7%, 4% and 2%. Six positive, strictly decreasing weights are required; invalid configuration fails startup. Each weight is limited to 1,000,000.
- The backend uses `SecureRandom.nextInt(totalWeight)` and weighted intervals. The frontend displays the configured odds; equally sized wheel segments do not imply equal probability.

## API and accounting

`GET /api/spin` returns the session user's eligibility, server time, IST spin date, next midnight, Available Winning Cash, configured prizes, today's receipt and latest 20 reward records. Reads are uncached.

`POST /api/spin` accepts `{ "requestId": "UUID", "spinDay": "YYYY-MM-DD" }` using the date from the latest status response. Authentication and the existing CSRF protection are required. User identity and reward amounts cannot be supplied by the client.

The transaction locks the existing user row, reads the server clock after obtaining the lock, chooses a prize, inserts an `EARNING` entry into the existing Winning Cash ledger, and inserts the linked spin receipt. The existing ledger trigger updates `public.users.winning_balance_paise`; recharge principal is untouched. Balance/history refresh signals are published only after commit.

An identical retry returns the original receipt. A different request on an already-used date returns that date's receipt with `alreadySpun=true`, without another draw or credit. A recovered request from yesterday cannot consume today's spin. Fresh requests carrying a stale or future date are rejected; clients must refresh eligibility and explicitly submit a new request.

## Migration and safeguards

Flyway `V16__daily_spin_rewards.sql` adds immutable reward receipts containing user ID, request ID, reward amount, timestamp, generated IST date, unique transaction ID, ledger ID and the weights used at the time of award.

Database constraints enforce `(user_id, spin_day)` uniqueness, request-key uniqueness, a unique linked ledger entry and the six allowed amounts. A receipt validation trigger checks its ledger's owner, source and amount. A deferred trigger rejects a spin ledger credit without a receipt at commit, so either both records and the balance change commit or none do. Records remain permanently stored; the UI shows the latest 20.

Apply V16 through normal application startup against the configured database. No live payments or production data are used by the tests.

## Browser behavior and verification

The wheel lands on the server-awarded prize; balance and receipt update as soon as the server confirms the credit. Refresh restores the wheel's position from the saved receipt. A server-relative live countdown resynchronizes on visibility changes, reconnects and midnight. Existing Winning Cash live notifications refresh other tabs, with the existing five-second cross-instance reconciliation fallback.

The browser saves a pending request before submission to recover interrupted requests. Loading, unavailable, retry, success and already-spun states use inline messages. Animation respects reduced motion. No native alerts are used.

`SpinPrizesTests`, `SpinServiceTests` and `SpinSecurityTests` cover prize boundaries, configuration validation, every reward amount, concurrency, IST midnight, old-request recovery, rollback, immutable records, database uniqueness, CSRF and ownership. `src/test/browser/spin.cjs` exercises the actual HTTP application and a disposable PostgreSQL fixture, including responsive layouts, cross-tab dashboard updates, live countdown, midnight rollover and lost-response recovery. Set `PLAYWRIGHT_MODULE` to an installed Playwright package; use the existing `target/wallet-classpath.txt` fixture classpath.
