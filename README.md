# Launchpad: Firebase authentication + Supabase PostgreSQL



Firebase handles identity, Google sign-in, passwords, reset emails, and signed session cookies. Supabase PostgreSQL is the main application database and stores user profiles. The existing authentication pages and dashboard design are unchanged. Java 17+ is required.



## Configure credentials directly



Open `src/main/resources/application.properties` and paste values after the empty `=` signs. No environment variables are required. Leave both `enabled` flags false until configured; the public UI starts without credentials, but database-backed sign-in is unavailable. Do not commit this file after adding real secrets.



### Firebase



1. Copy your Firebase Web app configuration from **Project settings > General > Your apps** into `app.firebase.api-key`, `auth-domain`, `project-id`, and `app-id`. Messaging sender ID and storage bucket are optional for authentication.

2. Enable **Google** and **Email/Password** in **Authentication > Sign-in method**. Add `localhost` and your deployed hostname to authorized domains. Use one account per email address.

3. Keep your Firebase Admin service-account JSON outside this repository. Paste its absolute path into `app.firebase.service-account-path` (forward slashes on Windows). Leaving the path blank uses Application Default Credentials. The server service account and web app must belong to the same Firebase project.

4. Set `app.firebase.enabled=true`. The public web configuration endpoint never exposes Admin or database credentials.



### Supabase database



1. Open **Supabase Dashboard > Connect**. For a typical IPv4 development machine, select **Session pooler**. Direct connections also work when the server has the required network access. Use session mode for Flyway migrations; do not use the transaction pooler for this configuration.

2. Copy the actual **host**, **port**, **database name**, **username**, and **database password** into the `app.supabase.*` placeholders. Pooler usernames include the project reference; use the full value from Connect. The port is commonly `5432` and database commonly `postgres`, but copy your project's values.

3. Alternatively set `app.supabase.jdbc-url=jdbc:postgresql://HOST:PORT/DATABASE` and leave host/port/database empty. Supply username/password separately. Do not paste the HTTPS project URL or a `postgres://user:password@...` URI. JDBC URLs with query parameters or embedded credentials are rejected; use the separate properties.

4. Leave `app.supabase.ssl-mode=require` for an encrypted connection. For certificate and hostname verification, use `verify-full` and set `ssl-root-cert` to the absolute path of the CA certificate downloaded from Supabase. No Supabase anon key, service-role key, or Supabase Auth configuration is required.

5. Set `app.supabase.enabled=true`. The database user must be able to create the `app_private` schema and its tables for the initial migration and own those tables for application access. Use the same dedicated backend database role for migration and runtime; the Connect panel's database owner also works. Never share these credentials with the browser.

6. Restart the application. Startup obtains a pooled JDBC connection, executes `SELECT 1`, and applies versioned Flyway migrations. Successful startup logs `Supabase PostgreSQL connection verified; application database migrations are current.` Invalid settings, connection failures, or migration failures stop startup with a diagnostic. Fix the settings/network/permissions and restart; there is no in-memory database fallback.



The database pool has bounded connection and socket timeouts and a default maximum of five connections. Production must serve HTTPS with `app.firebase.secure-cookie=true`. Keep `app_private` out of Supabase's exposed Data API schemas. It has no anonymous/browser access policies; application authorization is enforced by the Firebase session on the Spring backend.



## Run



```powershell

.\mvnw.cmd spring-boot:run

```



Open http://localhost:8080. An installed Maven can also run `mvn spring-boot:run`.



## Authentication and persistence flow



1. The browser uses the existing Firebase Google popup or email/password login.

2. Spring verifies the Firebase ID token, including revocation and recent authentication, and reads trusted identity details using Firebase Admin.

3. A single PostgreSQL `INSERT ... ON CONFLICT (firebase_uid) DO UPDATE ... RETURNING` stores the profile. The primary key prevents duplicate rows even when multiple first logins happen concurrently.

4. Only after that write succeeds does the backend send the existing HttpOnly Firebase session cookie. If storage fails, it returns 503 and issues no session cookie. The Firebase identity may already exist; retrying login safely creates/updates the same database row.

5. `/api/auth/me` verifies the session and reads the profile from Supabase using the **verified Firebase UID**, never an ID supplied by the browser. Older sessions without a database record are synchronized on first access.



Email is a searchable, updatable attribute, not an identity key. A changed email updates the same Firebase UID record. Distinct Firebase UIDs are never silently merged by email. Account linking remains Firebase's responsibility.



The `public.users` table stores Firebase UID, display name, email, profile URL, verification flag, provider, Firebase account creation and last sign-in timestamps, plus database creation/update timestamps. The `created_at` and `last_login_at` columns are PostgreSQL timestamps, readable directly in Table Editor. No passwords, ID tokens, service-account secrets, or session cookies are stored. Firebase-owned profile fields refresh on login; reads otherwise use Supabase. Future module tables can reference `firebase_uid` as a foreign key.



The UI response fields remain compatible with the existing dashboard. Login/registration, Google account selection, password reset, session expiry/revocation checks, CSRF protection, protected routes, and logout remain in Firebase/Spring Security. Logout clears the browser cookie but keeps its persistent profile row. A database outage does not delete a session or prevent logout; profile requests return 503 until the database recovers.



New account registration remains Google-only. Existing email/password users must already exist in Firebase Authentication. Firebase's hosted action handler completes password resets, and account enumeration protection may return a combined incorrect-email-or-password message.



## Module layout



- `auth/`: Firebase identity verification, HTTP-only cookies, Spring Security, auth API/page controllers.

- `database/`: Supabase connection properties, Hikari connection pool, startup probe and Flyway migration setup.

- `user/`: immutable persisted profile model, repository interface, PostgreSQL JDBC implementation, profile service and safe storage error handling.

- `resources/db/migration/`: versioned database migrations. Add new migrations for future changes; do not edit applied versions.

- `templates/` and `static/assets/`: existing responsive UI and Firebase client flow.



## Verification



Run `mvn test` or `.\mvnw.cmd test`. Tests explicitly disable real Firebase/Supabase configuration, so they do not contact your configured project. Tests use mocked Firebase identities and a disposable real PostgreSQL process for migrations, concurrent upserts, email changes, SQL parameter binding, and persistence. The PostgreSQL test dependency is test-only, requires no Docker, and downloads native binaries through Maven. No test database is included in the deployed app.



Connection failure tests intentionally attempt an unavailable local port and assert safe startup failure. API tests verify that persistence happens before session cookies are issued, unavailable storage returns 503, old sessions synchronize missing records, reads use verified UID, and all existing authentication boundaries remain protected.



After inserting your real configuration:



1. Start the app and confirm the successful connection/migration log.

2. Register with Google, then inspect `public.users` in Supabase's SQL editor or table editor. The Firebase UID should match your authenticated account.

3. Log out and log in again with the same account. Confirm one row, updated login information, and the same UID.

4. Test an existing Firebase email/password user and a session issued before this integration.

5. Refresh the dashboard, test password reset, and log out. Protected pages must still redirect to login afterward.

6. Temporarily make the test database unavailable. New session creation must return 503 without issuing a cookie; existing profile reads must return 503 without disclosing connection details. Restore availability and retry.



Real Firebase sign-in, Supabase connectivity, and delivered reset emails require your real project credentials and must be verified after configuration.



References: [Supabase Spring Boot connection guide](https://supabase.com/docs/guides/getting-started/quickstarts/spring-boot), [database connection options](https://supabase.com/docs/guides/database/connecting-to-postgres), [Firebase session cookies](https://firebase.google.com/docs/auth/admin/manage-cookies).





## Users table in Supabase Table Editor



The backend automatically creates `public.users` through Flyway migration V2. Open **Table Editor > public > users** and refresh the rows after signing in. Writes commit before the login endpoint returns its session cookie, so the row is available immediately after successful login; Table Editor may need a manual refresh.



V2 copies existing `app_private.user_profiles` records without deleting the legacy table. The new backend exclusively reads/writes `public.users`. Never edit V1 or manually mark a migration applied. Restart every running application instance after upgrading so old instances do not keep writing to the legacy table.



Columns include `firebase_uid` (primary key), `name`, `email`, `photo_url`, `provider`, `created_at`, and `last_login_at`. Returning logins update the same row's name, email, photo, provider, verification and login time while preserving creation time. The dashboard JSON format is unchanged. Row-level security stays enabled and privileges for Supabase anonymous/authenticated browser roles are revoked; this table is accessed by the trusted Spring backend.



The backend integration test drives `/api/auth/session` with mocked Firebase verification and a real PostgreSQL database, then confirms the committed row exists before the response cookie is returned. It also tests a second login, concurrent inserts, updated profile fields, legacy migration, and migration reruns. An interactive Google sign-in still requires the account owner.


## Admin, advertisements and products

Set `app.admin.email` and `app.admin.password` directly in `application.properties`, then restart. Both ship empty: blank values disable admin login. Visit `/admin` to sign in and `/admin/dashboard` to manage the catalog. Credentials are checked on the backend, hashed in memory for password comparisons, and never returned to the browser. Admin uses a separate Spring Security session with a 30-minute idle timeout, session-fixation protection and CSRF checks. Firebase users cannot access admin APIs, and admin sessions do not impersonate Firebase users. Changing credentials requires a restart (which also clears admin sessions).

Flyway V3 automatically creates `public.products` and `public.advertisements`. Both have RLS enabled with browser-role access revoked. Mutations use parameterized backend JDBC operations; records remain in Supabase across application restarts. Product and advertisement IDs are UUIDs; timestamps are UTC.

Products support create, edit, active/inactive status and confirmed deletion. Prices and daily income use exact decimal storage; the displayed currency is INR. Admin enters Total claims (1?3650), with no date or duration fields. Availability depends only on Active/Stopped status. Earning potential is daily income multiplied by total claims; existing daily earning and claim schedules remain unchanged. Created time is preserved on edits. Discount price cannot exceed original price. Images must use HTTPS URLs; failed images show a fallback. Description text is rendered as plain text, never HTML.

Firebase-authenticated users visit `/products` or `/products/{id}`. Only active products are listed or returned by the details API. An expired active product remains visible with `Expired`; there are no purchasing or payout operations in this module. Countdown uses server time to compensate for the browser clock, updates every second, and clamps at zero.

Advertisements allow create, edit, activate/deactivate and confirmed deletion. Activation atomically replaces the previous active ad, protected by a database transaction lock and unique index. Users see it on their dashboard/products pages. The X button or Escape dismisses the ad only for the current page. Reloading, reopening or returning to the app shows it again. No dismissal is persisted. Changing or reactivating an ad shows its latest revision. Visible pages check every 15 seconds.

Endpoints:
- Admin session: `POST /api/admin/login`, `POST /api/admin/logout`, `GET /api/admin/csrf`.
- Admin CRUD: `/api/admin/products` and `/api/admin/advertisements` (GET/POST), with `/{id}` for PUT/DELETE.
- User reads: `GET /api/products`, `GET /api/products/{id}`, `GET /api/advertisements/current`.

Run `mvn test` for authentication boundary checks, actual admin login/logout and CSRF, product CRUD/computed values, ad activation concurrency/rollback, and the existing Firebase/profile regression suite. PostgreSQL tests use disposable local databases and do not modify the configured Supabase project.


## Festival Home and Task Bonus

Main navigation: Home, Product, News, Price, Profile. Home shortcuts use protected `/features/{feature}` placeholder routes. Profile retains account information and logout. Mobile navigation uses a hamburger menu. Decorative animations respect reduced-motion preferences.

Flyway V4 creates `public.task_machines` with RLS and browser-role access revoked, and seeds three editable starter machines once. Restart to apply it. Admin > Task Bonus / Machines supports add, edit, active status and confirmed deletion. Image URLs accept HTTPS or the provided `/assets/machine-[1-3].svg` starter illustrations. Records have UUIDs and UTC creation/update timestamps.

Admin API: `/api/admin/machines` (GET/POST), `/api/admin/machines/{id}` (PUT/DELETE). User API: `/api/machines`, `/api/machines/{id}` returns only active records. Home and machine detail pages refresh every 15 seconds while visible. All writes go through secured Spring JDBC to Supabase and persist after restarts. No Firebase or database credentials are exposed to the frontend.

## Wallet recharge

The Home Recharge shortcut now supports Razorpay wallet top-ups. See [WALLET.md](WALLET.md) for the two credential entries in application.properties, payment capture setup, database migration details, and test instructions.

## Platform activity and footer

`GET /api/activity` requires the normal Firebase session. When JDBC is configured, it returns real aggregate data: unique account rows with sign-ins in the preceding 24 hours, and product daily claim credits since midnight in `app.activity.timezone` (default Asia/Kolkata). Claims are income credits, not net profit or confirmed withdrawals. No user identity or individual transaction is exposed. Server snapshots are cached for 15 seconds; visible Home pages refresh every 20 seconds with reduced-motion support. Database errors display unavailable, never synthetic fallback data.

Demo requires `app.activity.demo-enabled=true` **and no configured database bean**. Real statistics always take priority, including zero values. Demo is labeled SIMULATED ACTIVITY next to a persistent explanation. Active users vary between 500 and 1,000. `app.activity.demo-start-rupees=50000`, `demo-target-min-rupees=200000`, and `demo-target-max-rupees=300000` configure a seeded daily target and a monotonic curve with variable speed. Values are calculated from server time, are consistent after reload, and reset at local midnight; they do not record or credit money.

The shared footer appears on user pages. `/information/about`, `/information/contact`, `/information/faq`, `/information/terms`, `/information/privacy`, `/information/refund-cancellation`, `/information/withdrawal`, and `/information/risk-disclaimer` are public information pages. Customer Support links to the existing authenticated support module. Configure optional public details through `app.site.company-name`, `support-email`, `instagram-url`, and `telegram-url`. Empty contacts/social links are omitted instead of inventing destinations. Company-specific contractual terms, retention periods, refund rules and withdrawal timelines must be supplied by the operator; current copy explicitly identifies these missing details.

## Take Photo verification task

Open **Home > Task Bonus > Take Photo**, or `/tasks/take-photo`. Instructions: ?Upload a photo while using the Launchpad on your mobile, then submit it for verification.? A user can have one pending submission at a time and earn one lifetime reward for this machine. Rejection permits a new submission; completed/rejected receipts remain immutable. Machine activation and presentation are managed by the existing machine editor; deactivate the task instead of deleting its audit history.

Flyway V17 links an existing machine named Take Photo (case-insensitive), or creates it if absent, using the stable `TAKE_PHOTO` task type. It creates `app_private.photo_tasks` with owner, machine, request ID, authenticated Cloudinary URL/public ID, original-file SHA-256, status, fixed 2000-paise reward, submission/review timestamps, administrator, rejection reason and ledger receipt. RLS blocks browser database access. No new wallet or mutable balance mechanism is introduced.

The existing `cloudinary.cloud-name`, `cloudinary.api-key`, `cloudinary.api-secret` configuration is reused. Uploads require authenticated Firebase requests and CSRF. JPEG/PNG photos are limited to 5 MB and 16 megapixels, decoded and re-encoded to JPEG to strip metadata and non-image payloads. Photos use [Cloudinary authenticated storage and time-limited signed previews](https://cloudinary.com/documentation/control_access_to_media). Only the owning user and admin APIs issue five-minute preview links; treat these links as temporary bearer links. Cloudinary secrets stay server-side. Orphan uploads proven unreferenced after a retry/failure are removed; if database availability prevents proving this, cleanup is deferred and the asset ID is logged for later reconciliation. Support-ticket uploads retain their existing behavior.

User API: `GET /api/photo-tasks`, multipart `POST /api/photo-tasks` with `requestId` (UUID) and `photo`, `GET /api/photo-tasks/{id}/image`. A repeated request with the same photo returns its receipt; a reused request with different content is rejected. Admin API: `GET /api/admin/photo-tasks?status=PENDING&page=0` (20 rows/page), `PUT /api/admin/photo-tasks/{id}` with `{ "status": "COMPLETED" }` or `{ "status": "REJECTED", "reason": "..." }`, and the corresponding `/{id}/image` preview endpoint.

Both machines require at least one PAID product purchase. Take Photo is locked before purchase. Approval creates a fixed ₹20 claim offer without crediting cash; rejection creates no reward. The user clicks Claim ₹20 to credit Available Withdrawable / Winning Cash. One lifetime photo reward is allowed, and rejected photos may be resubmitted.

Refer & Earn (`/tasks/refer-earn`) uses the existing immutable invitation binding and shareable registration link. Once both inviter and friend have purchased, with the friend's qualifying purchase after binding, the purchase transaction creates one ₹30 claim offer per friend. No administrator is involved. Self-referrals, referral cycles, rebinding, and duplicate rewards are rejected. New referrals no longer receive the previous automatic ₹50 payouts.

Flyway V18/V19 persist claim offers in `app_private.machine_reward_claims`. V19 adds purchase/approval guards and transaction-bound offer triggers. Claiming through `POST /api/machine-rewards/{id}/claim` locks the user and reward, records one matching winning-ledger entry, and marks the reward CLAIMED in the same transaction. Amounts and ownership are server-controlled. Unique sources, receipt validation, and transaction rollback prevent duplicate or partial credits under retries and concurrent requests. After-commit wallet events plus five-second reconciliation refresh statuses and balances across pages. UI states are LOCKED, PENDING, COMPLETED (ready to claim), CLAIMED, and REJECTED where applicable.

Restart the application to apply migrations before using this release. Historical photo credits remain claimed; pending duplicate offers for historical referral payouts are superseded, preserving balances without allowing another payment. V19 also rejects new ledger writes using the retired automatic-credit sources. Deploy backend and frontend together; do not continue running older application instances against the upgraded schema.

Tests: `mvn test` includes photo validation, Cloudinary URL signing, PostgreSQL concurrency/idempotency/rollback/receipt guards, ownership, CSRF and admin authorization. `PhotoTaskBrowserFixture` runs an isolated PostgreSQL server at port 8099 with test Firebase and Cloudinary boundaries; run `src/test/browser/photo-task.cjs` with `PLAYWRIGHT_MODULE` set to an installed Playwright module to check responsive end-to-end behavior. Press Enter in the fixture process to close it and its temporary database. Live Cloudinary verification uses only a generated disposable test image, not a customer photo or real wallet credit.
