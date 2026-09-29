# Product earnings and manual withdrawals

The application uses its existing users, Firebase/admin sessions, JDBC transactions, Flyway and wallet ledger. New purchases use Recharge Balance. Daily claims and eligible referral rewards credit the separate Winning Cash ledger. Bank transfers are performed manually outside the application.

## Balances and product claims

- Recharge Balance is the current `users.wallet_balance_paise`, including purchase debits. It is never withdrawable just because it exists.
- Total Winning Cash is currently available earnings plus reservations in PROCESSING withdrawals. Completed payouts are no longer included.
- Available to Withdraw is `users.winning_balance_paise`, after reservations. A refund restores it exactly once.
- Catalog price, daily income, duration and claim schedule are snapshotted when a purchase is created. Later catalog edits cannot change an existing purchase's earnings.
- Claims use the persisted `Asia/Kolkata` schedule by default. The first claim boundary is strictly after the later of purchase activation and configured start time. Each daily opportunity expires at the next boundary; missed claims never accumulate.
- A claim locks the user and purchase, checks ownership and eligibility, writes an immutable earning event and daily claim in one transaction. A unique `(purchase_id, day_number)` constraint and unique earning source prevent duplicate credits. Repeated requests within the same eligible day return the original claim.
- Progress is backend `claimedIncomePaise / totalExpectedPaise * 100`. A final claim immediately completes the product. Time expiry also completes it; a product with missed days can therefore be completed with earning progress below 100%. No money is invented to fill missed days.
- `remainingEarningsPaise` is expected minus claimed; `missedIncomePaise` distinguishes expired income from `remainingFuturePaise`. Lifecycle details expose elapsed days, claimed days, eligible current day and next claim time.
- One purchase per user/product is enforced. Referral binding is permanent and must precede the first purchase. A first paid purchase credits INR 50 each to inviter and invitee once, through the same winning ledger. Self-referrals, cycles and changing inviter are rejected.

## Configuration and bank storage

Existing `razorpay.key.id` and `razorpay.key.secret` remain raw properties. Their current values are preserved. There is no `app.commerce.webhook-secret` property or withdrawal webhook-secret dependency, and no bank account details belong in configuration.

Users enter bank information through Withdraw. The server creates its own persistent random AES-256 key on first bank enrollment in `<server-user-home>/.hackathon/security/bank-key.bin`, with owner-only file permissions. No manual encryption secret is required. Account numbers use AES-GCM with random nonces and owner/account context; keyed fingerprints prevent duplicate enrollment. User responses contain only the last four account digits.

Persist and back up this private key directory separately from database backups. All application instances serving the same database must use the same protected key. An optional `app.security.key-directory` deployment setting can point to a persistent protected volume; it is a directory path, not a credential. If bank rows exist but the key file is missing, enrollment/decryption fails safely instead of generating a replacement. For an upgrade with already encrypted accounts, the original `WITHDRAWAL_BANK_ENCRYPTION_KEY` environment value (or legacy property) is still accepted for compatibility. Do not rotate or discard it without re-encrypting the accounts. No automatic key rotation is implemented.

Bank input format is validated on the client and server: listed bank code, nonblank holder/nickname, matching 9-18 digit account numbers, not all zeros, and an 11-character IFSC belonging to the selected bank. This validates format, not bank ownership or branch existence. Admins verify destinations before manually paying.

## Withdrawals and admin processing

1. The user selects an active owned account and enters at least the configured minimum (INR 10 by default), within available Winning Cash.
2. An application modal shows the destination, amount and remaining balance. Submission persists an idempotency key before sending.
3. A transaction locks the user, validates the authoritative balance, creates the withdrawal and reserves the requested amount with an immutable ledger/audit entry.
4. User history and the admin Withdrawals section show PROCESSING. A lost response can be recovered using the same request key; a changed payload with the same key is rejected.
5. Authorized admin lists and details include full account numbers, bank, holder, IFSC and nickname for every request, including finalized requests. Each read is audited. User APIs and screens remain masked; all financial responses use no-store caching.
6. After an actual external transfer, admin records SUCCESSFUL and the bank reference. There is no second debit.
7. FAILED/ERROR or CANCELLED/REFUNDED (legacy REJECTED) becomes REFUNDED with the original failure kind/reason, restoring exactly the reservation. Terminal actions are immutable; identical retries are safe and conflicting actions are rejected.

Concurrent submissions serialize on the user's database row. Unique reservation/refund/completion events, nonnegative balance checks and terminal-state locks prevent overdrafts and double finalization. No automatic payout provider is called. Internal idempotency cannot prevent an administrator from independently repeating a manual external bank transfer; reconcile the bank's records before retrying an uncertain transfer.

## Database changes

Existing migrations are retained. V7 defines bank accounts, withdrawals, winning ledger and audit. V8 defines purchase snapshots, claim history, provider purchase transactions, referrals/rewards and product images. V9/V10 remain as previously applied, including wallet-funded purchases.

New `V11__claim_and_withdrawal_guards.sql`:

- Restores INR 100 minimum for new withdrawals while allowing historical smaller requests to be finalized/refunded.
- Makes withdrawal amounts immutable.
- Adds unique purchase debit and provider-payment indexes.
- Adds bank account `updated_at`.
- Validates claim owner, paid status, snapshot amount, daily window, product end and matching earning ledger at the database boundary.

All currency changes use integer paise, and changes to balance, ledger and business record commit or roll back together. Existing immutable ledger/audit triggers remain in place. Flyway applies migrations on application startup; tests use disposable PostgreSQL and do not migrate the configured live database.

## APIs

All user endpoints derive ownership from the existing Firebase session. Mutations require the existing CSRF header from `/api/auth/csrf`. Admin endpoints require ROLE_ADMIN and admin CSRF for writes/reveal. Responses containing account data are not cached.

| Method | Endpoint | Behavior |
| --- | --- | --- |
| POST | `/api/purchases` | Authoritative wallet-funded purchase |
| GET | `/api/purchases` and `/{id}` | Own purchases, backend progress, lifecycle and claims |
| POST | `/api/purchases/{id}/claim` | Claim the eligible day once |
| POST | `/api/purchases/{id}/verify` | Verify legacy Razorpay signature and fetched payment |
| POST | `/api/purchases/{id}/reconcile` | Recover a legacy purchase from provider API evidence |
| POST | `/api/purchases/razorpay/webhook` | Untrusted notification hint; financial proof is fetched from Razorpay |
| GET / POST | `/api/invitations`, `/api/invitations/bind` | Invitation history and permanent binding |
| GET | `/api/withdrawals/dashboard` | Current principal, current total winnings, available winnings and masked banks |
| GET | `/api/withdrawals/banks`, `/bank-accounts` | Bank directory and owned masked accounts |
| POST / DELETE | `/api/withdrawals/bank-accounts`, `/{id}` | Save or deactivate owned bank |
| POST | `/api/withdrawals` | Reserve winnings/create or replay request |
| GET | `/api/withdrawals`, `/{id}` | Own withdrawal history/detail |
| GET | `/api/admin/withdrawals`, `/{id}` | Filtered admin summary/list/detail |
| POST | `/api/admin/withdrawals/{id}/payout-details` | Audited full-bank details for any request |
| POST | `/api/admin/withdrawals/{id}/status` | Manual completion or automatic reservation refund |
| GET | `/api/admin/product-sales`, `/api/admin/purchases`, `/api/admin/purchases/{id}` | Sales totals, purchases, lifecycle and claim ledger |
| GET | `/api/admin/invitations` | Filtered relationships and both reward ledgers |
| POST | `/api/admin/product-images` | Validate/re-encode uploaded PNG/JPEG |

Withdrawal list filters: `status`, `from`, `to`, `user`, `withdrawalId`, `minAmount`, `maxAmount`, `page`, `size`. Amount filters use rupees. The response includes pagination and filtered summary counts (`processing`, `completed`, `refunded`) plus `totalRequestedPaise`. Dates are inclusive UTC calendar dates. Failures/rejections can be filtered by original failure kind.

Purchase filters include product, user, date, lifecycle, payment status and date/amount sorting. Referral filters include inviter, invitee, code, registration dates, purchase/reward status and sorting. Lists paginate on the server.

## Razorpay and startup

The configuration bean `purchaseWebhookSecurity` and security filter chain bean `purchaseWebhookFilterChain` have distinct names. Bean-definition overriding is not enabled. The narrowly scoped webhook chain does not disable CSRF/authentication on purchase, withdrawal or admin APIs.

Without a separate webhook secret, the notification sender is not authenticated by a webhook signature. The payload is only a reconciliation hint: the server fetches the payment using the existing Razorpay API credentials and checks payment ID, stored order ID, amount, INR currency, captured status and zero refund before activation. Unknown/already-paid orders do no work; simultaneous provider reads are bounded. Forged notifications cannot supply proof or set a balance. Legacy checkout callback signatures remain verified with the existing key secret. New purchases use Recharge Balance; Razorpay remains the recharge provider.

The recharge gateway tolerates an absent optional `created_at` timestamp as zero. The existing captured-payment/signature validations remain enforced. Recharge history excludes purchase debit rows so a purchase cannot appear as a captured recharge.

See [IMPLEMENTATION_REPORT.md](IMPLEMENTATION_REPORT.md) for changed files and final verification results.

## Admin withdrawal update (V15)

Flyway `V15__admin_withdrawal_management.sql` upgrades existing COMPLETED rows to SUCCESSFUL, preserves immutable historical ledger/audit records, updates success constraints and transfer-reference uniqueness, and prohibits reopening terminal requests. A single terminal ledger index prevents both completion and refund for the same reservation. Legacy COMPLETED input maps to SUCCESSFUL; ERROR maps to FAILED; CANCELLED/REFUNDED map to the existing REJECTED failure kind. Summary `completed` and timestamp `completedAt` retain their field names for compatibility.

Failures and cancellations require a trimmed reason of 10?500 characters, for example ?Technical issue during payment processing?. Success requires a unique bank transfer reference. Repeated identical terminal actions return the stored result; conflicting terminal actions return HTTP 409. Repeated PROCESSING actions do not add audit entries or overwrite the first processing record.

`GET /api/withdrawals/events` is an authenticated SSE invalidation stream scoped to the session user. Events are sent only after transaction commit; clients reload balances/history using authenticated, uncached APIs. Refreshes received during another request are queued. Visibility restoration and reconnection also refresh data. A five-second reconciliation fallback handles missed events and changes from other application instances; immediate push is local to the application instance. Multi-instance deployments requiring instant cross-instance delivery need a shared event transport.

Admin actions use an explicit confirmation modal and accessible success/error messages. User bank numbers are never included in the event stream or user DTOs. The existing encrypted account storage and Winning Cash ledger are reused; recharge principal is unaffected.

The withdrawal page uses `GET /api/withdrawals/snapshot` to load balances and paginated history in one read-only REPEATABLE READ transaction, preventing a newly refunded status from appearing beside a pre-refund balance. Background refresh preserves the active form and bank selection.
