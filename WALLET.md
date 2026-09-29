# Razorpay wallet recharge

The existing **Home → Recharge** shortcut opens `/features/recharge`. Firebase session authentication and the existing Supabase JDBC connection are reused. No separate user system or Razorpay Java SDK is needed.

## Configuration

Provide matching Razorpay Key ID and Key Secret through environment variables before starting the application:

```powershell
$env:RAZORPAY_KEY_ID = "rzp_test_..."
$env:RAZORPAY_KEY_SECRET = "..."
```

Start with matching Razorpay **Test Mode** keys. The backend reads both environment variables through Spring property injection. Only `key.id` is returned to Checkout; the Key Secret stays on the server. Empty keys disable recharge without preventing existing pages from starting. Amounts are entered in rupees, converted exactly to integer paise, and limited to INR 1–100,000. Currency and the recharge limit are backend constants, so no additional Razorpay properties are needed. Restart the application after setting credentials, and do not commit real secrets. Rotate the previously committed key pair before using Live Mode.

Keep the existing Firebase and Supabase settings configured and enabled. On startup, Flyway V5 adds `public.users.wallet_balance_paise` (zero for existing users) and creates `app_private.recharge_orders` and `app_private.wallet_transactions`. It preserves existing profiles and data. The private tables are backend-only and must remain outside Supabase's exposed API schemas.

Configure **automatic payment capture** in the Razorpay dashboard. The backend deliberately credits only payments whose Razorpay API status is `captured`, with `captured=true` and no refunded amount. An authorized payment produces a retryable verification message, never a credit.

All wallet operations require Firebase authentication, and all wallet POST requests require CSRF protection. The Checkout success callback submits payment details for backend verification. If verification is interrupted after the callback arrives, the same tab retains the details for **Retry payment verification** or automatic retry on reload. If the tab is closed before those details arrive or they are lost, there is no automatic background credit; the payment requires reconciliation before paying again.

## Flow and storage

1. `GET /api/wallet` returns the signed-in user's balance in paise and latest 50 successful recharge transactions.
2. `POST /api/wallet/orders` accepts `{ "amount": "123.45" }`. It validates the amount/account, creates a server-side Razorpay order for 12,345 paise, saves its owner/amount/currency, and returns only public Checkout values.
3. Checkout success calls `POST /api/wallet/verify` with `orderId`, `paymentId`, `signature`, and the original rupee `amount`. **No browser-supplied user ID is used**: the Firebase principal owns the request.
4. In a database transaction, the service locks the stored order, checks ownership and amount, verifies the payment HMAC using the stored order ID, and fetches the payment from Razorpay. It checks the payment ID, order ID, amount, currency, capture status and refunded amount.
5. The transaction inserts a unique credit record, atomically increments the existing user's balance, and marks the order credited. Any error rolls back all three writes. Unique constraints on both Razorpay payment and order IDs prevent double credits. Duplicate callbacks return the current balance without another credit.
6. The browser displays the confirmed balance immediately and refreshes history. Incomplete verification is saved per account in session storage and retried on reload or with **Retry payment verification**.

Unpaid/cancelled/failed attempts leave their order in `CREATED` and create no wallet credit. Transaction rows represent successful credits, not every provider attempt. Provider failures return safe errors without logging secrets or raw provider response bodies. Existing login profile upserts preserve wallet balances.

## Verification

```powershell
mvn test
mvn spring-boot:run
```

Automated tests cover credential property loading, PostgreSQL migrations, exact paise conversion, account ownership, HMAC failures, provider mismatches, uncaptured/failed/refunded payments, rollback after writes, simultaneous callbacks, simultaneous separate recharges, duplicate payment protection, existing-user balance preservation, API authentication/CSRF, and provider HTTP requests. All database tests use disposable local PostgreSQL instances; Razorpay calls use controlled test responses.

`src/test/java/com/iare/hackathon/wallet/WalletBrowserFixture.java` and `src/test/browser/recharge.cjs` provide a local browser integration test with a real Spring server and disposable database. Firebase identity and Razorpay are simulated; this does not prove live Checkout/account configuration. See the browser script's header for running it.

Before switching to Live Mode, use your Test Mode account to make a preset and decimal custom recharge, cancel/fail a payment, and retry the same successful verification. Verify both the wallet and transaction record in the database. Enable production HTTPS and secure session cookies as described in the main README.

References: [Razorpay Standard Checkout](https://razorpay.com/docs/payments/payment-gateway/web-integration/standard/integration-steps/), [payment lookup](https://razorpay.com/docs/api/payments/fetch-with-id/).
