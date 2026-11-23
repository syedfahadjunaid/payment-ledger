# AGENTS.md: guide for coding agents working on this repository

A payment and wallet system on a double-entry ledger: wallets, transfers, card top-ups, refunds,
signed webhooks from a (simulated) payment provider, and a daily reconciliation job. It's an
enterprise-style reference project, and the code doubles as learning material (the owner is
learning Java), so keep explanatory comments and idiomatic code. The README is the human-facing
overview and holds the design decisions; this file is the operating manual. When they disagree,
check the code and fix the doc.

Sections: [Status](#status-whats-built-and-whats-left) · [Map](#map) · [Run and verify](#run-and-verify)
· [Run it on a new machine](#run-it-on-a-new-machine) · [Request flow](#request-flow) ·
[Invariants](#invariants-do-not-break-these) · [Conventions](#conventions) ·
[Things that already bit us](#things-that-already-bit-us) · [Definition of done](#definition-of-done) ·
[Learning guide](#learning-guide)

## Status: what's built and what's left

### Done (milestone 1, "full backend core", November 2026)

1. **Ledger core**: accounts, transactions and entries (`V1__ledger_core.sql`), with the
   invariants enforced by Postgres, and `LedgerService.post` as the only code that moves money.
2. **Merchants and auth**: hashed API keys for merchants, an admin token for operators, and
   least-privilege database logins (`ledger_owner` and `ledger_app`).
3. **Wallets and statements**: one currency per wallet; the balance and history come from the ledger.
4. **Transfers**: wallet to wallet in one ACID transaction, with an idempotency key, sorted locks
   and no overdraft.
5. **Top-up saga**: card charge through the provider, credited by a signed webhook; duplicate,
   stale and conflicting webhooks are handled; `SagaRecoveryJob` resubmits and polls.
6. **Refund saga**: the amount is reserved at once and settled by webhook, or compensated with a
   reversal when the provider refuses.
7. **Audit trail**: an append-only `audit_events` table, written in the same transaction as the change.
8. **Reconciliation**: 10 checks, internal plus the provider's settlement report; nightly at
   00:15 UTC and on demand; reports stored as JSON.
9. **provider-sim**: async charges and refunds, at-least-once signed webhooks, test cards
   including a chaos card, and a daily settlement report.
10. **Quality**: 79 + 5 tests (Testcontainers, concurrency over real HTTP), Checkstyle, javac
    `-Xlint:all -Werror`, Docker Compose, `scripts/demo.ps1`, a CI workflow, the README and this file.

### Left to build, in order

Each item lists where the change goes and what "done" means. They follow the README's
"Trade-offs and next steps", ordered so each one is useful alone and the smaller ones come
first. Follow the invariants and the definition of done below for every item.

1. **Run CI for the first time.** `.github/workflows/ci.yml` is written but has never run,
   because the project isn't a git repository and has no remote yet. Wait for the owner to ask
   for a repository and push. Then make all four jobs green: both Maven builds, Trivy, and the
   end-to-end job (`docker compose up --wait`, then `pwsh ./scripts/demo.ps1`). Likely fixes are
   small: an action version, or a Trivy finding to upgrade.
2. **Return 409, not 500, when a row lock times out or deadlocks.** Found on 2026-11-23:
   - The cause: `LedgerService.lockAccount` calls `entityManager.refresh(..., PESSIMISTIC_WRITE)`
     directly. On `lock_timeout` or a deadlock that throws `jakarta.persistence.LockTimeoutException`
     / `PessimisticLockException`, and Spring translates those into `ConcurrencyFailureException`
     only for repositories. So `GlobalExceptionHandler.concurrentUpdate` never sees them, and the
     client gets a 500.
   - How it was found: forcing deadlocks (locking in leg order instead of sorted order) produced
     500s. With the sorted order, deadlocks can't happen, but a 5 s lock timeout on a hot account can.
   - Fix: handle both `jakarta.persistence` exceptions in `concurrentUpdate`, or catch them in
     `lockAccount` and rethrow `PessimisticLockingFailureException`. Also fix the comment in
     `ConcurrencyTests` that says a deadlock would show as 409.
   - Test: hold a wallet account row with `SELECT ... FOR UPDATE` on a separate `Superuser`
     connection for 6 s, POST a transfer from that wallet, and expect 409 `concurrent_update`
     with nothing written.
3. **API key revocation.** The schema already has it: `api_keys.revoked_at`, the app login has
   UPDATE on `api_keys`, and `ApiKeyRepository.findByKeyHashAndRevokedAtIsNull` already ignores
   revoked keys.
   - Add `POST /admin/merchants/{merchantId}/api-keys/{keyId}/revoke` in `MerchantAdminController`.
   - In the service, load the key by id *and* merchant id (404 otherwise) and set `revokedAt`
     through a new `ApiKey.revoke(Instant)` method. Revoking twice is a no-op 200.
   - Audit `API_KEY_REVOKED`.
   - Tests in `SecurityTests`: a revoked key gets 401, the merchant's other key still works, and
     another merchant's key id gives 404.
   - Optional: a `GET /admin/merchants/{id}/api-keys` list showing prefixes only.
4. **Wallet freezing and closing.**
   - Migration `V4__wallet_status.sql`: `status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN
     ('ACTIVE','FROZEN','CLOSED'))`, `version BIGINT NOT NULL DEFAULT 0`, and `GRANT UPDATE ON wallets`.
   - In `Wallet`, remove `@Immutable` and add `@Version` and the status.
   - Rules: a FROZEN or CLOSED wallet can't send (transfers out, refund reservations), and the
     API answers 409 `wallet_frozen`. CLOSED needs a zero balance (409 otherwise) and can't
     receive transfers.
   - Decide and document what a top-up webhook does for a frozen wallet. Recommended: credit it
     anyway, because the money really arrived.
   - Race-freedom: read the wallet status under a lock. Lock the wallet rows (`FOR SHARE` for
     senders) after payment/refund rows and before ledger accounts, and update the lock order in
     the invariants.
   - Endpoints: `POST /api/wallets/{id}/freeze|unfreeze|close` (merchant) with audit events.
   - Tests: the rules, plus a `ConcurrencyTests` case racing freeze against transfers.
5. **Rate limiting per API key.** Copy the HRMS pattern (`C:\Projects\New folder (3)\services\core-service\...\RateLimitFilter.java`):
   a Redis fixed window per key with a 429, `Retry-After` and `X-RateLimit-*` headers.
   - Add a `redis:8.8-alpine` service to compose. Check the port first with
     `Get-NetTCPConnection` and add it to `.env.example`. Give Redis a password from `.env`.
   - Add a Redis Testcontainer to `TestcontainersConfiguration`.
   - Decide fail-open or fail-closed when Redis is down. For a payments API, fail closed on
     money-moving endpoints, and write the decision in the README.
6. **Transactional outbox for provider calls.** This replaces `AfterCommit` plus the "resubmit"
   half of `SagaRecoveryJob`.
   - New table `outbox_commands(id, type, aggregate_id, payload, created_at, attempts,
     next_attempt_at, processed_at)`, inserted in the same transaction as the payment or refund.
   - A scheduled dispatcher claims due rows with `SELECT ... FOR UPDATE SKIP LOCKED LIMIT n`,
     calls `PaymentSaga.submit` / `RefundSaga.submit`, and applies exponential backoff on
     "outcome unknown".
   - Tests: a command is processed exactly once with two dispatchers running, and a command that
     keeps failing backs off.
   - Show outbox lag in metrics (item 8).
7. **Payouts (wallet to bank).** A third saga, shaped like refunds.
   - New account type `PAYOUTS_IN_TRANSIT` and transaction types `PAYOUT_RESERVE` / `PAYOUT_SETTLE`
     / `PAYOUT_REVERSAL`. The CHECK constraints on `ledger_accounts.type` and
     `ledger_transactions.type` need a new migration that drops and re-adds them.
   - New table `payouts`, shaped like `refunds` but referencing a wallet.
   - provider-sim: `POST /v1/payouts` plus `payout.*` webhooks, and payouts in the settlement report.
   - Reconciliation: assets = wallets + refunds in transit + payouts in transit; add a
     `payouts_match_ledger` check and extend `SettlementComparison`.
   - Tests mirror `RefundSagaTests`.
8. **Metrics.** Micrometer is already on the classpath, and Prometheus is exposed on management
   port 8081, which is never published.
   - Gauges: payments and refunds per status, and the age of the oldest CREATED/PROCESSING row
     (saga lag).
   - Counters: webhook outcomes (APPLIED/IGNORED/DUPLICATE/CONFLICT), and findings per check
     from the last reconciliation.
   - Test with `/actuator/prometheus` in an integration test that uses the management port.
9. **OpenAPI docs.** Either add springdoc-openapi (pick a release that supports Spring Boot 4.2;
   check Maven Central and its compatibility table first) or write `docs/openapi.yaml` by hand.
   - Document the `Idempotency-Key` header, problem codes and the webhook signature.
   - Expose the spec without auth only if it contains no secrets.
10. **Hot-account sharding.** Every top-up of a merchant and currency locks the one
   `PROVIDER_CLEARING` row.
   - Add `shard SMALLINT NOT NULL DEFAULT 0` to `ledger_accounts`, and make the unique index
     `(merchant_id, type, currency, shard)`.
   - `LedgerService.systemAccount` picks a shard from a hash of the payment id; reconciliation
     and positions sum across shards.
   - Prove it with a benchmark-style test: N parallel top-ups, before and after.
11. **Operator UI.** React + TypeScript + Vite like HRMS's `web/`: merchants, wallets, payments,
    reconciliation reports, and a new read-only `GET /admin/webhook-events`.
    - The admin token must not live in a browser: add a BFF with a server-side session (as HRMS
      did) or an OIDC login for operators first.
12. **Multi-currency / FX.** This is the largest change.
    - A transaction may then hold two currencies: balance per currency inside the transaction, so
      the deferred trigger groups by currency, and the single-currency foreign key from entries
      to transactions must go.
    - Add FX accounts per currency and a rate source (stored with each FX transaction), and
      reconciliation per currency.
    - Write a design note in the README before starting.

## Map

| Path | What | Stack |
|---|---|---|
| `services/ledger-service/` | The API: ledger, wallets, transfers, top-up and refund sagas, webhooks, idempotency, audit, reconciliation | Java 25, Spring Boot 4.2, Spring Security 7, Hibernate 7, Flyway, Jackson 3, Maven wrapper |
| `services/provider-sim/` | Simulated card provider: charges, refunds, HMAC-signed webhooks with retries, settlement report. In-memory | Java 25, Spring Boot 4.2 (web only) |
| `services/*/config/checkstyle.xml` | Lint rules (run in `validate`; javac also runs with `-Xlint:all -Werror`) | Checkstyle 14 |
| `infra/postgres/init/01-init.sh` | Creates the `ledger_owner` (migrations) and `ledger_app` (runtime) logins and schema `ledger` | Postgres 18 |
| `docker-compose.yml` | Postgres + provider-sim + ledger-service | |
| `scripts/demo.ps1` | End-to-end demo against the running stack (also run by CI) | PowerShell 5.1 / 7 |
| `.github/workflows/ci.yml`, `.github/dependabot.yml` | CI (both builds, Trivy, compose + demo), dependency updates | |

Inside `ledger-service` (`com.paymentledger`): `ledger` (the only code that moves money),
`wallet`, `transfer`, `payment` (top-ups, refunds, both sagas, `SagaRecoveryJob`), `webhook`,
`provider` (client interface, HTTP client, signature check), `idempotency`, `audit`,
`reconciliation`, `merchant`, `security`, `common`, `config`.

## Run and verify

The dev machine is Windows (PowerShell 5.1 and Git Bash) with Docker Desktop. **There is no local
JDK or Maven**: Java is built and tested inside Docker, with the Maven cache in volume `payment-ledger-m2`.

```powershell
Copy-Item .env.example .env                 # once; .env is git-ignored
docker compose up -d --build --wait         # API on http://localhost:8480
.\scripts\demo.ps1                          # top-up, transfer, refund, reconciliation
```

| What | Check before you call a change done |
|---|---|
| ledger-service | From the repo root: `docker run --rm -v "${PWD}\services\ledger-service:/workspace" -v payment-ledger-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /workspace eclipse-temurin:25-jdk sh mvnw -B verify` (~3-4 min with a warm Maven cache, 15-30 min the very first time; Testcontainers starts Postgres) |
| provider-sim | Same with `services\provider-sim` (no Docker socket needed) |
| Running stack | `docker compose up -d --build <service>`, then `.\scripts\demo.ps1` |

`verify` runs Checkstyle, compiles with `-Xlint:all -Werror` (a javac warning fails the build),
and runs every test. Run Docker commands from PowerShell; in Git Bash, prefix with
`MSYS_NO_PATHCONV=1` or the `/var/run/docker.sock` path gets mangled.

Ports (all on 127.0.0.1, chosen to avoid the other local stacks): ledger API **8480**,
provider-sim **8481**, Postgres **35432**. Management/actuator is 8081 inside each container and
never published. Before adding a port, check `Get-NetTCPConnection -State Listen -LocalPort <port>`.

## Run it on a new machine

The project folder is self-contained. A copy without `.env`, `target/`, `node_modules/`,
`dist/` and caches works: the images and the test containers download everything they need.
There are no `node_modules` or `dist` folders in this project at all (there is no web UI yet).
`.env.example` lists every variable: the required ones Compose uses, plus commented optional
ones for running a service from an IDE.

**Verified on 2026-11-23 from such a copy** (`.env`, `target/` and the Maven cache left out):
- `docker compose build --no-cache`, then `up --wait`: all three containers healthy.
- `demo.ps1`: passed, reconciliation BALANCED.
- Both test suites, each with a brand-new empty Maven cache volume: ledger-service 79/79 and
  provider-sim 5/5, BUILD SUCCESS. Those first runs took 26 and 16 minutes, almost all of it
  downloads, on a busy machine.

The one deviation was running Compose with `-p` under another project name, so it didn't touch
this machine's existing database volume.

### Prerequisites

| Need | Version | Why |
|---|---|---|
| Docker Desktop (Windows/macOS) or Docker Engine + Compose plugin (Linux) | Compose v2.20 or later, for `up --wait` and the top-level `name:`. Verified with Docker 29.9 / Compose v5.6 | Builds and runs everything, including the Java builds and tests |
| PowerShell | Windows PowerShell 5.1 (built into Windows) or PowerShell 7 | `scripts/demo.ps1` (CI uses PowerShell 7 on Linux) |
| Internet access on the first run | | Docker Hub images (`postgres:18-alpine`, `eclipse-temurin:25-jdk`/`-jre`, the Testcontainers helper) and Maven Central (Maven itself plus all dependencies) |
| Memory for Docker | about 4 GB | Two JVMs and Postgres. The tests add a third JVM and another Postgres |
| Free ports | 8480, 8481, 35432 on 127.0.0.1 | Change them in `.env` if they are taken (see below) |

**Not** needed: a JDK, Maven, Node.js or a local Postgres. Java 25 and Maven 3.10.1 (through the
Maven wrapper) run inside `eclipse-temurin:25-jdk` containers. You only need a local JDK 25 to
run or debug a service from an IDE, and then the app reads the same `.env`.

### From a clean copy, step by step (Windows, PowerShell)

```powershell
# 1. Unzip, then open PowerShell in the project folder
Set-Location C:\Projects\payment-ledger

# 2. Only if the zip was downloaded through a browser: drop the "from the internet" mark so scripts may run
Get-ChildItem -Recurse | Unblock-File

# 3. Local secrets (dev values only; .env is git-ignored and never zipped)
Copy-Item .env.example .env

# 4. Build the images and start Postgres, provider-sim and the ledger API; waits until all are healthy.
#    The first build downloads base images and every Maven dependency: 5-20 min depending on the
#    connection. Later builds are cached.
docker compose up -d --build --wait

# 5. The end-to-end demo: top-up, idempotent retry, chaos webhooks, transfer, overdraft 422, refund, reconciliation
powershell -ExecutionPolicy Bypass -File .\scripts\demo.ps1
```

`-ExecutionPolicy Bypass` applies to that one command. It's needed because Windows blocks `.ps1`
scripts by default; if your policy already allows scripts, `.\scripts\demo.ps1` is enough. The
demo ends with `overall status: BALANCED` and `Demo complete.`

What runs where:

| URL / address | What |
|---|---|
| http://localhost:8480 | Ledger API. JSON only, no UI: `/api/**` needs a merchant key, `/admin/**` needs `ADMIN_API_TOKEN` from `.env`. Endpoints are in the README's API table |
| http://localhost:8481 | provider-sim API (`Authorization: Bearer <PROVIDER_API_KEY>`), e.g. `GET /v1/settlements?date=YYYY-MM-DD` |
| `127.0.0.1:35432`, database `payments` | Postgres. Easiest way in: `docker compose exec postgres psql -U postgres -d payments`, then `SET search_path = ledger;` |

Try the API by hand (the token is read from `.env`, not typed):

```powershell
$admin = ((Get-Content .env | Select-String '^ADMIN_API_TOKEN=').Line -split '=', 2)[1]
$m = Invoke-RestMethod -Method Post -Uri http://localhost:8480/admin/merchants -Headers @{ Authorization = "Bearer $admin" } -ContentType 'application/json' -Body '{"name":"My shop"}'
Invoke-RestMethod -Method Post -Uri http://localhost:8480/api/wallets -Headers @{ Authorization = "Bearer $($m.apiKey)" } -ContentType 'application/json' -Body '{"customerRef":"me","currency":"EUR"}'
```

### Running every test suite

Run these from the project root. Docker must be running, because the ledger tests start their
own Postgres with Testcontainers.

```powershell
# ledger-service: Checkstyle + javac -Xlint:all -Werror + 79 tests
docker run --rm -v "${PWD}\services\ledger-service:/workspace" -v payment-ledger-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /workspace eclipse-temurin:25-jdk sh mvnw -B verify

# provider-sim: Checkstyle + javac -Xlint:all -Werror + 5 tests
docker run --rm -v "${PWD}\services\provider-sim:/workspace" -v payment-ledger-m2:/root/.m2 -w /workspace eclipse-temurin:25-jdk sh mvnw -B verify
```

- The named volume `payment-ledger-m2` is the Maven cache. Docker creates it on first use, and
  that first run downloads Maven and every dependency. Later runs reuse it.
- Test reports go to `services/<service>/target/surefire-reports/`.
- To run one class, add `"-Dtest=ConcurrencyTests"` (or any test class name) after `verify`.
  Quote `-D` flags in PowerShell: one with a dot in it gets split otherwise.

macOS/Linux (bash), not verified on this machine: use `"$PWD/services/ledger-service:/workspace"`.
On Linux Engine, also add `--add-host=host.docker.internal:host-gateway`, because
`host.docker.internal` only exists on Docker Desktop.

### Day-to-day

| Task | Command |
|---|---|
| Rebuild one service after a code change | `docker compose up -d --build --wait ledger-service` |
| Logs (webhook outcomes, saga retries) | `docker compose logs -f ledger-service` |
| Stop, keeping the database | `docker compose down` |
| Stop and wipe the database | `docker compose down -v` (provider-sim forgets everything on restart anyway) |
| Ports taken | Set `POSTGRES_HOST_PORT`, `LEDGER_HOST_PORT`, `PROVIDER_HOST_PORT` in `.env`; then run the demo with `-BaseUrl http://localhost:<LEDGER_HOST_PORT>` |

### Troubleshooting

- **`docker compose up` says a variable is missing**: `.env` doesn't exist yet (step 3).
- **`running scripts is disabled on this system`**: use the `-ExecutionPolicy Bypass` form of step 5.
- **`port is already allocated`**: another stack uses 8480, 8481 or 35432. Change the port in
  `.env`, as in the Day-to-day table.
- **Reconciliation says DISCREPANCIES for other merchants after a restart**: provider-sim keeps
  its records in memory. Restarting it forgets earlier charges, and reconciliation correctly
  reports them as missing at the provider. `docker compose down -v` starts both sides fresh.
- **`up --wait` fails with "container ... is unhealthy" on a slow or very busy machine**: the
  health checks allow about 2.5 minutes for a service to start. With about 25 other containers on
  4 CPUs, a JVM took 4-6 minutes here (normally under 30 s). The containers keep starting
  anyway: run `docker compose up -d --wait` again, or check `docker compose ps` until all are healthy.
- **Testcontainers can't find Docker** (ledger tests): Docker Desktop must be running, and the
  test command must keep `-v /var/run/docker.sock:/var/run/docker.sock` and
  `-e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`.
- **Changed `.env` passwords after the first start**: Postgres created its logins on first start
  only. Run `docker compose down -v` (wipes the data) and start again.

## Request flow

Merchant → `Authorization: Bearer sk_test_...` → `ApiKeyAuthenticationFilter` (SHA-256 lookup) →
controller → service → `LedgerService.post` → Postgres schema `ledger` (as login `ledger_app`).
Top-ups and refunds: the request commits step 1 and answers 202; after commit, the saga calls
provider-sim (`Idempotency-Key: payment-{id}` / `refund-{id}`); provider-sim later POSTs a signed
webhook to `/webhooks/provider`. Operators use `/admin/**` with `ADMIN_API_TOKEN`.

## Invariants: do not break these

**Money**
- Amounts are `long` minor units plus a 3-letter currency. Never `double`/`float`, never
  `BigDecimal` for storage. Use `Math.addExact`/`negateExact`. JSON floats for amounts are a 400
  (`accept-float-as-int: false`).
- Only `LedgerService.post(PostingRequest)` writes ledger entries. Each posting is balanced
  (checked in `PostingRequest`, and again by the deferred trigger at COMMIT), single-currency, and
  runs inside the caller's transaction (`Propagation.MANDATORY`).
- Corrections are new transactions in the opposite direction (see `REFUND_REVERSAL`). Never
  UPDATE or DELETE history; the app login has no privilege to, and triggers stop the owner.
- Balances are maintained by the `trg_ledger_entries_apply_balance` trigger. `LedgerAccount` is
  never updated by Hibernate. After posting, read balances with `LedgerService.balanceOf` (the
  loaded entity is stale).

**Concurrency**
- Lock order is always: idempotency key row → payment row → refund row → ledger accounts sorted
  by id (`PostingRequest.netAmountByAccount`). A new flow must follow it, or it can deadlock.
- Lock a row as the first load in the transaction (`findByIdForUpdate`); a JPQL lock query returns
  an already-loaded entity without refreshing it. `LedgerService` uses `refresh(..., PESSIMISTIC_WRITE)`.
- `@Version` stays on `Payment` and `Refund`. `lock_timeout` is 5 s (Hikari init SQL).

**Idempotency, sagas, webhooks**
- Every money-moving endpoint takes `Idempotency-Key` and goes through `IdempotencyService.execute`,
  so the key row, the money movement and the stored response commit together.
- Never call the provider inside a database transaction. Saga steps that call it run after commit
  (`AfterCommit`) and must be safe to repeat; `SagaRecoveryJob` repeats them.
- A provider timeout/5xx is "outcome unknown" (`ProviderUnavailableException`): never mark the
  operation failed on it. Only a 4xx (`ProviderRejectedException`) is a definite no.
- All provider news (API response, webhook, poll) goes through `PaymentSaga.apply` /
  `RefundSaga.apply`, and every status change through `ProcessingStatus.on` (exhaustive switch).
  Final states never move.
- Webhooks: verify the HMAC over the raw bytes before parsing; dedup by inserting the event id
  first (`webhook_events`, UNIQUE); answer 200 to anything accepted (duplicates included).

**Tenancy and security**
- The merchant comes only from the API key (`@AuthenticationPrincipal MerchantPrincipal`), never
  from a request body. Merchant-facing queries filter by `merchantId` (`findByIdAndMerchantId`).
  Native SQL must add `merchant_id` explicitly. Cross-merchant code is limited to operator jobs
  (reconciliation, saga recovery, webhooks).
- New tables carry `merchant_id` and link with composite foreign keys `(merchant_id, id[, currency])`.
- Status codes: 400 malformed, 401 no/bad credentials, 403 wrong credentials for the endpoint,
  **404 not found or not yours**, 409 state forbids it, 422 business rule (with a `code`).
- API keys are stored as SHA-256 only; the plain key appears once, in the create response.
- Secrets only in `.env` (git-ignored). Compose uses `${VAR:?...}`; the app refuses to start
  without a 32+ character admin token and webhook secret. Don't repeat `.env` values in chat or docs.

**Schema**
- Flyway migrations are immutable once applied: add `V<n+1>__description.sql`. Hibernate runs
  with `ddl-auto: validate`; use `VARCHAR`, not `CHAR`, for strings mapped to `String`.
- Every new table needs an explicit `GRANT ... TO ${appRole}` in its migration (least privilege;
  the tests run as `ledger_app`, so a missing grant fails them). Append-only tables get the
  `forbid_change()` trigger. Functions use `SET search_path FROM CURRENT`.

## Conventions

- Java: constructor injection; records for DTOs and config (`LedgerProperties`); services are
  `@Transactional(readOnly = true)` with `@Transactional` on writers; thin controllers; errors as
  RFC 9457 `ProblemDetail` via `GlobalExceptionHandler`; `Clock` injected for time.
- Spring Boot 4 specifics: `spring-boot-starter-webmvc`; `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`;
  Jackson 3 (`tools.jackson.databind.json.JsonMapper`; annotations still `com.fasterxml.jackson.annotation`);
  Testcontainers 2 (`org.testcontainers.postgresql.PostgreSQLContainer`); `JdbcClient` for SQL.
- Tests: plain JUnit for pure logic; `@IntegrationTest` (one shared context, real Postgres, fake
  provider) for everything else. Each test creates its own merchant with `TestApi`, and checks
  only its own merchant's reconciliation findings. Async saga steps: use the `await*` helpers,
  never fixed sleeps. Concurrency tests go over real HTTP (`ConcurrencyTests`).

## Things that already bit us

- Hibernate `validate` rejects `CHAR(n)` columns mapped to `String` (it expects varchar).
- `SELECT ... FOR UPDATE` needs the UPDATE privilege, which is why `ledger_app` has UPDATE on
  `ledger_accounts`; `trg_ledger_accounts_guard` (using `pg_trigger_depth()`) is what stops
  direct balance edits.
- The JDK `HttpServer` (used in tests) normalises header names (`Idempotency-key`); look them up
  with `getFirst`, don't copy the map.
- Checkstyle allows the constant name `log` for SLF4J loggers; line length is 130.
- PowerShell 5.1: `Invoke-WebRequest -Method Post` without a body sends a form content type
  (send JSON). For an `application/problem+json` error, `$_.ErrorDetails.Message` stays empty
  in 5.1 (PowerShell 7 fills it); `demo.ps1` falls back to reading `$_.Exception.Response.GetResponseStream()`.
- `curl http://localhost:...` on Windows first tries IPv6 (`::1`), and the ports are bound to
  127.0.0.1 only, which adds about 200 ms per call. Use `127.0.0.1` when timing anything.
- Windows PowerShell splits a native-command argument at a dot: `-Dcheckstyle.skip` reaches Maven
  as `-Dcheckstyle` + `.skip` ("Unknown lifecycle phase"). Quote such flags: `"-Dcheckstyle.skip=true"`.
- `entityManager` calls outside a repository aren't exception-translated by Spring: a lock
  failure in `LedgerService` is a `jakarta.persistence` exception and currently becomes a 500
  (Status item 2).
- Docker Desktop's builder can fail with `frontend grpc server closed unexpectedly` when the
  machine is busy (two Maven test runs plus an image build). It's transient: run the build again.
- Test durations depend heavily on load. `ConcurrencyTests` takes about 90 s on a quiet machine
  (mostly context and container start-up) and 7 minutes on a busy one. Neither is a failure.
- provider-sim is in memory: restart it and reconciliation reports earlier charges as "settled by
  us but not by the provider". That's the check working, not a bug.

## Definition of done

1. `mvnw -B verify` passes for every service you touched (commands above).
2. Money-flow changes: a test for the invariant (DB-level if possible) and, if concurrency is
   involved, a case in `ConcurrencyTests`.
3. Schema changes: a new Flyway migration with grants for `${appRole}`.
4. The stack is rebuilt and `.\scripts\demo.ps1` passes.
5. README and this file updated if behaviour, commands, ports or decisions changed.
6. Don't commit or push unless the owner asks.

## Learning guide

This project is written to be read. Paths below are short: `ls/` means
`services/ledger-service/src/main/java/com/paymentledger/`, `lt/` its tests
(`src/test/java/com/paymentledger/`), `mig/` its Flyway folder (`src/main/resources/db/migration/`),
and `sim/` `services/provider-sim/src/main/java/com/paymentledger/providersim/`.

### Concepts, in reading order

Read them in this order: each step builds on the one before. For each concept, read the files
listed, then the test that proves it.

1. **A Spring Boot service in one picture.** `@SpringBootApplication` starts an embedded Tomcat
   and creates the beans (objects Spring builds and wires together). Constructor injection passes
   dependencies in, and records hold immutable data. `@ConfigurationProperties` + `@Validated`
   turns `application.yml` into a typed object, so the app refuses to start with a bad config.
   - Read: `ls/PaymentLedgerApplication.java`, `ls/config/LedgerProperties.java`,
     `src/main/resources/application.yml`, `ls/config/AppConfig.java`.
2. **Money as integers.** `0.1 + 0.2 != 0.3` in binary floating point, so amounts are `long`
   minor units (1250 = 12.50 EUR) plus a currency code. `Math.addExact` throws on overflow
   instead of silently wrapping around, and the JSON parser rejects `12.5` rather than
   truncating it.
   - Read: `ls/common/Money.java`, `ls/common/Amounts.java`; `accept-float-as-int` in `application.yml`.
   - Test: `lt/transfer/TransferApiTests.java` (`validatesTheRequest`).
3. **Double-entry bookkeeping.** Every movement is a transaction of entries that sum to zero
   (+ debit, - credit). Accounts have a *normal balance*: assets grow with debits, liabilities
   with credits. A wallet is a liability (we owe the customer); cash at the provider is an asset.
   Money is never created or lost, only moved.
   - Read: `ls/ledger/NormalBalance.java`, `AccountType.java`, `TransactionType.java`, `Leg.java`,
     `PostingRequest.java`, then `LedgerService.java`.
   - Test: `lt/ledger/PostingRequestTests.java`.
4. **Letting the database enforce the rules.** Java checks can be bypassed by a bug or a manual
   SQL script; constraints can't.
   - Read `mig/V1__ledger_core.sql` top to bottom. It contains:
     - composite foreign keys (same merchant, same currency);
     - a `CHECK` against overdraft;
     - append-only triggers;
     - a *deferred* constraint trigger, which checks "sums to zero" at COMMIT, after all
       entries are inserted;
     - an `xid8` check that seals committed transactions;
     - a balance trigger, plus a guard that uses `pg_trigger_depth()`.
   - Least privilege: `infra/postgres/init/01-init.sh` and the `GRANT` lines at the end of each
     migration.
   - Test: `lt/ledger/LedgerDatabaseTests.java`, which writes raw SQL and expects Postgres to say no.
5. **Transactions in Spring.** `@Transactional` wraps a method in BEGIN/COMMIT, and a
   RuntimeException means ROLLBACK. Several settings matter here:
   - propagation `REQUIRED` joins an existing transaction; `MANDATORY` insists on one
     (`LedgerService.post`, `AuditService.record`), so money and audit commit together;
   - `readOnly = true` marks read-only services;
   - `TransactionTemplate` starts a transaction in code, where you want explicit boundaries (sagas);
   - isolation `REPEATABLE_READ` gives one snapshot for a whole report (`ls/reconciliation/LedgerChecks.java`).
   - Gotcha: calling an annotated method from the same class skips the proxy, so its annotation
     doesn't apply.
6. **Concurrency control.** Two requests that read a balance, check it and write it can both
   pass the check: that is a lost update or an overdraft. The tools used here:
   - pessimistic locks: `SELECT ... FOR UPDATE` via `entityManager.refresh(account,
     PESSIMISTIC_WRITE)` and `@Lock` repository methods;
   - a fixed lock order (accounts sorted by id), so two transfers in opposite directions can't
     deadlock;
   - optimistic locking with `@Version` on `Payment`/`Refund`, as a safety net;
   - `lock_timeout`;
   - an advisory lock, so only one reconciliation runs at a time.
   - Read: `ls/ledger/LedgerService.java` (`post`, `lockAccount`), `ls/payment/PaymentRepository.java`.
   - Test: `lt/ConcurrencyTests.java`. Note the start gate (`CountDownLatch`) that releases all
     threads at once.
7. **Idempotency keys.** Networks fail after the server did the work, so clients retry. The
   service makes the retry harmless:
   - it inserts the key first, so a concurrent duplicate waits on the primary key;
   - it does the work;
   - it stores the response in the same transaction, and replays it for the same key.
   - A different request under the same key is a client bug: 422.
   - Read: `ls/idempotency/IdempotencyService.java`, `IdempotencyKeyExpiryJob.java`,
     `mig/V3__idempotency_webhooks_audit_reconciliation.sql`.
   - Tests: `TransferApiTests` (`retryingWithTheSameKeyMovesMoneyOnce`) and `ConcurrencyTests`.
8. **State machines with exhaustive switches.** A payment's status may only move forward.
   `ProcessingStatus.on(event)` is a `switch` expression over every status and event, so the
   compiler rejects a missing case. Duplicates and stale events become "ignore", and
   contradictions become "conflict".
   - Read: `ls/payment/ProcessingStatus.java`, `Transition.java`, `ProviderEvent.java`, and
     `Payment.apply`.
   - Test: `lt/payment/ProcessingStatusTests.java` (parameterized).
9. **Sagas and compensation.** A card payment spans two systems that can't share a database
   transaction. Each step is a local transaction, and the persisted status remembers progress.
   - The remote call happens only *after* commit (`AfterCommit`), and is repeatable because it
     uses an idempotency key.
   - A timeout means "outcome unknown", never "failed".
   - A failed refund is undone with a new compensating transaction, never by editing history.
   - A recovery job finishes what crashed half-way.
   - Read: `ls/payment/PaymentService.java`, then `PaymentSaga.java`, `RefundSaga.java`,
     `AfterCommit.java`, `SagaRecoveryJob.java`.
   - Tests: `TopUpWebhookTests`, `RefundSagaTests`.
10. **Calling another service safely.** Set explicit connect and read timeouts. Map a 4xx to a
    definite "no" and a 5xx or timeout to "unknown". Send an idempotency key on every POST, and
    hide the client behind an interface so tests can swap in a fake.
    - Read: `ls/provider/PaymentProviderClient.java`, `HttpPaymentProviderClient.java`.
    - Tests: `lt/provider/HttpPaymentProviderClientTests.java` (a real JDK web server),
      `lt/FakePaymentProvider.java`.
11. **Webhooks.**
    - Authenticity: an HMAC-SHA256 over the timestamp plus the raw body, compared in constant
      time, and accepted only within 5 minutes (replay protection).
    - Exactly-once effect: the event id goes into an inbox table with a UNIQUE key, in the same
      transaction as the effect.
    - Out-of-order delivery: the state machine from concept 8.
    - Read: `ls/provider/WebhookSignature.java`, `ls/webhook/WebhookService.java`, and the
      sender side, `sim/WebhookSender.java` and `sim/Signature.java`.
    - Tests: `WebhookSignatureTests` (a shared test vector computed with openssl), `TopUpWebhookTests`.
12. **Reconciliation.** Prevent what you can, detect everything else.
    - Internal checks are set-based SQL over one consistent snapshot. Examples: does every
      transaction balance? does every cached balance equal its entries? do the assets equal the
      liabilities?
    - The external check compares our settled items with the provider's statement, in both
      directions.
    - Read: `ls/reconciliation/LedgerChecks.java`, `SettlementComparison.java`,
      `ReconciliationService.java`, `ReconciliationScheduler.java`.
    - Tests: `ReconciliationTests` (tampering with triggers off), `SettlementComparisonTests`.
13. **API security.**
    - Random API keys stored as SHA-256 (fine for random secrets; passwords need bcrypt or argon2).
    - A servlet filter that builds the Spring Security principal.
    - One `SecurityFilterChain` with URL rules.
    - 404 rather than 403 for other merchants' data, so ids don't leak.
    - Read: `ls/security/ApiKeys.java`, `ApiKeyAuthenticationFilter.java`, `MerchantPrincipal.java`,
      `ls/config/SecurityConfig.java`.
    - Test: `lt/security/SecurityTests.java`.
14. **Errors and audit.** Every error is an RFC 9457 `ProblemDetail` with a stable `code`. The
    audit trail is append-only, `@Immutable` in Hibernate, and written with `MANDATORY`
    propagation.
    - Read: `ls/common/GlobalExceptionHandler.java`, `ls/audit/AuditService.java`.
15. **Testing like production.**
    - Testcontainers runs a real Postgres, and the tests log in as the same restricted user
      production uses.
    - One meta-annotation, `@IntegrationTest`, so every class shares one Spring context.
    - A fake provider, and a `TestApi` helper so tests read like client code.
    - Polling helpers instead of sleeps for asynchronous steps.
    - Read: `lt/IntegrationTest.java`, `TestcontainersConfiguration.java`, `Superuser.java`,
      `TestApi.java`.
16. **Build and delivery.**
    - Maven wrapper (no local Maven), Checkstyle, and `-Xlint:all -Werror` (`pom.xml`).
    - Multi-stage Dockerfile: the JDK builds, the JRE runs, layered jar, non-root user.
    - Compose health checks and `depends_on: service_healthy`.
    - CI in `.github/workflows/ci.yml`.

Last, run `scripts/demo.ps1` and read its output next to README "Design decisions". Then read
`sim/Simulator.java` to see how the chaos test card produces duplicates and out-of-order events.

### Glossary

| Term | Meaning here |
|---|---|
| Account | A bucket of money in the ledger (a wallet, provider clearing, refunds in transit) |
| Advisory lock | A Postgres lock on an arbitrary number rather than a row; used so only one reconciliation runs |
| Append-only | Rows can be inserted, never updated or deleted (enforced by triggers and missing grants) |
| At-least-once delivery | The sender retries until acknowledged, so the receiver must expect duplicates |
| Clearing account | `PROVIDER_CLEARING`: money the payment provider holds for the merchant |
| Compensating transaction | A new posting that undoes an earlier step of a saga (`REFUND_REVERSAL`) |
| Debit / credit | The two sides of an entry; stored as + / - amounts. Which one "increases" depends on the normal balance |
| Deadlock | Two transactions each waiting for a lock the other holds; prevented here by a fixed lock order |
| Deferred constraint | A check that runs at COMMIT instead of after each statement |
| Double-entry | Every movement is recorded on at least two accounts, and the entries sum to zero |
| Entry / leg | One line of a ledger transaction: an account and a signed amount |
| Fingerprint | SHA-256 of method, path and body, stored with an idempotency key to detect key reuse |
| HMAC | A keyed hash: proves a message came from someone holding the shared secret, unaltered |
| Hot account | One row that many transactions must lock, which limits throughput |
| Idempotency key | A client-chosen id that makes retrying a request safe: it takes effect at most once |
| Inbox table | `webhook_events`: a UNIQUE event id recorded with its effect, for exactly-once processing |
| Isolation level | What a transaction sees of concurrent ones. READ COMMITTED (default) vs REPEATABLE READ (one snapshot) |
| Least privilege | Each login gets only the rights it needs; `ledger_app` can't UPDATE ledger history |
| Lost update | Two writers read the same value and the second write silently overwrites the first |
| Minor units | The smallest unit of a currency (cents); amounts are whole numbers of them |
| Normal balance | The side (debit or credit) that increases an account: debit for assets, credit for liabilities |
| Optimistic locking | `@Version`: detect a concurrent change at UPDATE time and fail instead of overwriting |
| Outcome unknown | A timeout or 5xx: the remote side may or may not have acted; retry safely, never assume failure |
| Pessimistic locking | `SELECT ... FOR UPDATE`: block other writers until this transaction ends |
| Posting | Recording one balanced ledger transaction (`LedgerService.post`) |
| ProblemDetail | RFC 9457 JSON error body: `type`, `title`, `status`, `detail`, plus our `code` |
| Reconciliation | Proving two records agree: the ledger with itself, and the ledger with the provider |
| Replay attack | Re-sending a captured, validly signed message; stopped by the timestamp window and dedup |
| Saga | A multi-step flow across systems, made of local transactions plus compensations |
| Settlement report | The provider's statement of what it actually processed on a day |
| Tenant / merchant | The customer of this API; all data is scoped to one merchant |
| Transactional outbox | A table of commands written in the business transaction and sent later (Status item 6) |
| Trial balance | Sum of all debits minus credits; must be zero |

### Exercises

Each one is small enough for an evening. Run the ledger-service `verify` command afterwards.

1. **Prove keys are per merchant (tests only).** In `lt/transfer/TransferApiTests.java`, add a
   test where two merchants use the *same* `Idempotency-Key` string for their own transfers.
   Both must get 201 with `Idempotent-Replayed: false`. Why does that work? Look at the primary
   key of `idempotency_keys`.
2. **Break the lock order on purpose, then fix what it reveals.** In `ls/ledger/LedgerService.post`,
   lock the accounts in *leg order* instead of `request.netAmountByAccount()` order: build a
   `LinkedHashMap` from `request.legs()`. Run only the concurrency tests: in PowerShell, add
   `"-Dtest=ConcurrencyTests"` (quoted) to the `verify` command.
   - `crossingTransfersNeitherDeadlockNorLoseUpdates` fails. The log says `deadlock detected`:
     A→B holds A and waits for B, while B→A holds B and waits for A. Draw the cycle.
   - The API answered **500**, not 409. Why? (See Status item 2: Spring didn't translate the
     JPA exception.)
   - Do Status item 2 so these become 409 `concurrent_update`, then restore the sorted order and
     watch the test pass again. (This was tried on 2026-11-23: 73 of the 80 transfers got 500.)
3. **Revoke an API key.** Build Status item 3: endpoint, service method, audit event, and three
   tests in `SecurityTests`.
4. **Add a reconciliation check.** Add `stuck_sagas` to `LedgerChecks`: payments or refunds still
   CREATED or PROCESSING more than one hour after `updated_at`. Make it report a finding rather
   than fail. Test it in `ReconciliationTests` by backdating `updated_at` with
   `Superuser.jdbc()`. Payments and refunds aren't append-only, so no trigger blocks that.
5. **Freeze a wallet.** Build Status item 4, including the migration, the
   409 rules and a `ConcurrencyTests` case that freezes a wallet while 20 transfers run. After
   the freeze commits, no transfer out of it may succeed.
