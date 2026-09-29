# Implementation and verification report

## Startup fix (25 September 2026)

The source tree contained both `V11__claim_and_withdrawal_guards.sql` and `V11__customer_support.sql`. Flyway requires unique migration versions and rejects this combination before startup can finish. The shared-database initialization wrapper hides the original exception behind a generic startup error.

Renamed the newer customer-support migration to `src/main/resources/db/migration/V12__customer_support.sql`, preserving its SQL and the original V11 safeguards. A clean Maven build removes the stale V11 customer-support copy from `target/classes`. Added `src/test/java/com/iare/hackathon/database/MigrationResourceTests.java` to reject duplicate migration versions in packaged resources during future builds. Existing V1-V11 SQL and credentials were not edited for this fix. No schema-history repair, baseline, checksum reset, or bean overriding was enabled.

The earlier security bean collision remains fixed: the configuration bean is `purchaseWebhookSecurity`, and the filter-chain bean is `purchaseWebhookFilterChain`.

## Invitation/profile update

The current backend already retrieves the inviter's ID, name and photo from the saved referral relationship and user profile through `CommerceService.invitations` and `CommerceRepository.inviterProfile`. Both GET `/api/invitations` and POST `/api/invitations/bind` return this authoritative profile. No schema or API shape change was needed for this update.

Refined `src/main/resources/static/assets/invitation.js` and `invitation.css`:

- Success and errors use the existing bottom-corner toast, with no native alerts or top-of-page success/error text.
- Saving renders the POST response immediately: "Invitation code saved successfully." and "You were invited by", followed by the stored inviter's name, photo and ID.
- No extra GET must succeed before displaying the saved relationship. Subsequent refreshes still retrieve it from the backend.
- HTTPS profile images use no-referrer and an initials fallback. Profile text is inserted as text, never HTML.
- Hidden profile cards stay hidden, long names/IDs wrap on mobile, and notifications respect reduced-motion preferences.
- Binding controls stay locked during confirmation/submission. Notification timers cannot prematurely hide a newer notification.

Extended `WithdrawalBrowserFixture.java` with two synthetic invitation users and added `src/test/browser/invitation.cjs` for the full two-user, save, forged-profile, photo, refresh, history and mobile flow. The fixture uses disposable PostgreSQL and simulated Firebase sessions; it does not contact live payment services.

## Earlier product/withdrawal work retained

- Compact My Products cards use server-calculated daily claim amount, claimed/expected earnings and progress. Final claims complete immediately, missed days expire, and daily earnings affect Winning Cash only.
- Withdraw displays current recharge principal, current total Winning Cash and available winnings. Minimum withdrawal is INR 100. Reservations, completion and exact refunds remain transactional and replay-safe.
- Bank details come from the user form. Account numbers are encrypted with server-managed persistent key material and remain masked except for audited admin reveal.
- Admin withdrawals include nickname, full processing details, amount/date/status/user filters, totals and pagination. Purchases expose lifecycle/claim details; invitations expose both reward ledgers.
- Existing raw Razorpay key/secret properties are preserved. No separate commerce webhook secret is required. Legacy webhook notifications only trigger authenticated provider reconciliation, with payment/order/amount/currency/capture/refund checks.
- Recharge history excludes purchase debits. Optional provider timestamps deserialize safely.

Principal implementation files for that work are in `commerce/` and `withdrawal/`, plus `wallet/ProductPaymentGateway.java`, `RazorpayGateway.java`, `WalletRepository.java`, migration V11, the My Products/Withdraw/admin templates and associated JavaScript/CSS. `confirm-modal.js` replaces browser confirmation dialogs. Home product wiring, admin purchase/referral views and product image-upload controls use the existing APIs.

Detailed database, API, balance, claim and manual-payout behavior is documented in [WITHDRAWALS.md](WITHDRAWALS.md).

## Verification and deployment limits

- Clean Maven `verify`: BUILD SUCCESS, 138 tests, zero failures/errors/skips (`startup-fix-build.log`). This includes migrations against disposable PostgreSQL, unique migration versions, authentication/admin authorization, claims, concurrent/idempotent finance operations, refunds and Razorpay verification.
- Final invitation UI packaging: BUILD SUCCESS (`invitation-package.log`).
- Full HTTP startup: Tomcat started successfully with all 12 migrations applied to disposable PostgreSQL. No duplicate-bean or migration-version error occurred.
- Invitation Playwright test: passed two-user binding, success/error toasts, backend-derived name/photo/ID, forged client profile rejection, reload persistence, inviter history, and 320/390/768/1440 pixel layouts (`invitation-browser.log`). Mobile screenshot was inspected.
- Product/withdrawal Playwright test: passed final compact-card progress/completion, INR 100 validation, bank setup, lost-response replay, admin full-bank reveal/completion/refund, sales lifecycle, image upload and mobile layouts (`withdrawal-browser.log`).
- Shared-database read-only check: existing successful history is V1 through V11, with V11 being `V11__claim_and_withdrawal_guards.sql`. The new V12 support migration has not been applied to that database (`migration-history-check.log`). Only migration version/script names were queried; credentials and application records were not printed.
 The local application was successfully started on localhost with external services disabled. Full PostgreSQL migration and financial tests use disposable databases.

Automatic approval review blocked starting the application against the configured shared database because that startup can apply Flyway migrations. The configured database was not changed. The normal application configuration remains enabled as before; isolated overrides were command-line options only.

Persist and back up the server's private bank-key directory; existing encrypted accounts require their original key. No live Razorpay charge or manual bank transfer was executed during verification.
