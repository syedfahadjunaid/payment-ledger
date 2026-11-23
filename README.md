# Payment Ledger

A payment and wallet system built on a **double-entry ledger** in Postgres. Merchants hold
customer wallets, top them up by card through a payment provider, move money between wallets,
refund card payments, and a nightly job **reconciles** the books against the provider's
settlement report.

The point of the project is **keeping money correct** under retries, crashes, duplicate and
out-of-order webhooks, and many requests hitting the same wallet at once. Most of the guarantees
are enforced by the database itself, and the tests attack them directly.

| | |
|---|---|
| **Stack** | Java 25, Spring Boot 4.2, Spring Security 7, Hibernate 7, Flyway, Postgres 18, Testcontainers 2, Docker Compose, GitHub Actions |
| **Shows** | ACID transactions, double-entry bookkeeping, DB-enforced invariants, idempotency keys, sagas with compensation, signed webhooks, pessimistic + optimistic locking, deadlock-free lock ordering, reconciliation |
| **Services** | `ledger-service` (the API) and `provider-sim` (a simulated card provider that sends signed webhooks) |

## Contents

- [Quick start](#quick-start)
- [Architecture](#architecture)
- [API](#api)
- [Design decisions](#design-decisions)
- [Invariants and where they are enforced](#invariants-and-where-they-are-enforced)
- [Tests](#tests)
- [Trade-offs and next steps](#trade-offs-and-next-steps)
- [Project layout](#project-layout)

## Quick start

Needs Docker Desktop. No local JDK required: everything builds inside containers.

```powershell
Copy-Item .env.example .env          # local dev secrets, git-ignored
docker compose up -d --build --wait  # Postgres + provider-sim + ledger-service, waits until healthy
.\scripts\demo.ps1                   # top up, transfer, refund, reconcile
```

If Windows blocks the script ("running scripts is disabled"), run
`powershell -ExecutionPolicy Bypass -File .\scripts\demo.ps1` instead. Setting up from a fresh
copy on another computer (prerequisites, every test suite, troubleshooting) is covered in
[AGENTS.md: Run it on a new machine](AGENTS.md#run-it-on-a-new-machine).

The demo creates a merchant and two wallets, then:

1. tops up Alice with 100.00 EUR by card. The API answers `202 Accepted`, and the wallet is
   credited when the provider's signed webhook arrives;
2. retries that top-up with the same `Idempotency-Key`. The original response is replayed and the
   card isn't charged twice;
3. tops up 20.00 EUR with `pm_card_chaos`, a test card whose final webhook is delivered twice and
   followed by a stale "processing" event. The wallet is credited exactly once;
4. transfers 30.00 EUR to Bob, then shows that an overdraft attempt is refused with `422 insufficient_funds`;
5. refunds 25.00 EUR. The money is reserved immediately, then settled when the provider confirms;
6. runs reconciliation and prints every check plus the balance sheet: cash at the provider
   (95.00) = wallets (65.00 + 30.00) + refunds in transit (0.00).

Ports, all bound to 127.0.0.1: API **8480**, provider-sim **8481**, Postgres **35432**.

## Architecture

```
 merchant backend                         ledger-service (Spring Boot)                   Postgres 18
 ────────────────    Bearer sk_test_…    ┌──────────────────────────────────┐   ┌──────────────────────┐
  POST /api/…    ───────────────────────▶│ API key filter → controllers     │   │ schema "ledger"      │
  Idempotency-Key                        │ IdempotencyService (1 tx)        │──▶│  ledger_accounts     │
                                         │ LedgerService.post  ◀── only     │   │  ledger_transactions │
 operator                                │   code that moves money          │   │  ledger_entries      │
  /admin/… (admin token) ───────────────▶│ PaymentSaga / RefundSaga         │   │  + triggers enforcing│
                                         │ ReconciliationService (nightly)  │   │    the invariants    │
                                         └───────┬──────────────▲───────────┘   └──────────────────────┘
                     POST /v1/charges, /v1/refunds│              │ POST /webhooks/provider
                     Idempotency-Key, after commit│              │ Provider-Signature: t=…,v1=HMAC
                                         ┌────────▼──────────────┴───────────┐
                                         │ provider-sim: async outcome,       │
                                         │ at-least-once webhooks w/ backoff, │
                                         │ daily settlement report            │
                                         └────────────────────────────────────┘
```

The service runs as Postgres login `ledger_app`, which can only `SELECT`/`INSERT` ledger
history. Migrations run as `ledger_owner`.

## API

| Endpoint | Who | What |
|---|---|---|
| `POST /admin/merchants` | operator | create a merchant; the response holds its API key (shown once) |
| `POST /admin/merchants/{id}/api-keys` | operator | an additional key (rotation) |
| `POST /api/wallets` · `GET /api/wallets/{id}` | merchant | create a wallet (one currency) · balance |
| `GET /api/wallets/{id}/entries` | merchant | statement, newest first |
| `POST /api/wallets/{id}/top-ups` | merchant, **Idempotency-Key** | card top-up → `202`, completes by webhook |
| `POST /api/transfers` · `GET /api/transfers/{id}` | merchant, **Idempotency-Key** | wallet → wallet, synchronous → `201` |
| `GET /api/payments/{id}` | merchant | top-up status, with its refunds |
| `POST /api/payments/{id}/refunds` · `GET /api/refunds/{id}` | merchant, **Idempotency-Key** | refund (part of) a top-up → `202` |
| `GET /api/audit-events` | merchant | the merchant's audit trail |
| `POST /webhooks/provider` | provider | HMAC-signed events |
| `POST /admin/reconciliations?date=` · `GET /admin/reconciliations[/{id}]` | operator | run / read reconciliation reports |

Amounts are integers in minor units with an explicit currency: `{"amountMinor": 2550,
"currency": "EUR"}` is 25.50 EUR. Responses add a display string (`"formatted": "25.50"`).
Errors are RFC 9457 problem documents. Business-rule failures have a stable `code`
(`insufficient_funds`, `currency_mismatch`, `refund_exceeds_payment`, `idempotency_key_reused`, ...).
The status codes are deliberate: **404** if it isn't yours (no existence leak), **409** if the
current state forbids it, **422** if a business rule says no.

Test cards for the simulator: `pm_card_ok`, `pm_card_declined` (fails by webhook),
`pm_card_invalid` (rejected at once), `pm_card_chaos` (duplicate + out-of-order webhooks),
`pm_card_refund_fails`.

## Design decisions

### 1. Double-entry ledger

Every money movement is a **ledger transaction** with two or more **entries**. Each entry has a
signed amount on one account (positive = debit, negative = credit), and a transaction's entries
always **sum to zero**. Money is never created or destroyed, only moved, and every movement has
both sides on record.

Accounts have a *normal balance*. Assets grow with debits, and liabilities grow with credits.

| Account | Normal | Meaning |
|---|---|---|
| `WALLET` (one per wallet) | credit | what we owe a customer: a liability |
| `PROVIDER_CLEARING` (per merchant + currency) | debit | cash the provider holds for us: an asset |
| `REFUNDS_IN_TRANSIT` (per merchant + currency) | credit | refunds taken from a wallet, not yet paid out |

| Transaction | Debit | Credit |
|---|---|---|
| `TOP_UP` | provider clearing | wallet |
| `TRANSFER` | source wallet | target wallet |
| `REFUND_RESERVE` | wallet | refunds in transit |
| `REFUND_SETTLE` | refunds in transit | provider clearing |
| `REFUND_REVERSAL` (compensation) | refunds in transit | wallet |

So the balance sheet always holds: **provider clearing = wallets + refunds in transit**.
Reconciliation checks that equation for every merchant and currency.

### 2. Money is integers

`long` minor units plus an ISO 4217 currency, everywhere: Java, JSON and `BIGINT` columns.
Floating point can't represent 0.10 exactly, so `{"amountMinor": 12.5}` is rejected with a 400
rather than truncated. Arithmetic uses `Math.addExact`, so an overflow throws instead of wrapping
around. There is no currency conversion: a transaction has exactly one currency, and the foreign
keys make an entry's currency match both its transaction and its account.

### 3. Balances: cached, maintained by the database, and checked

Each account has a `balance_minor` column, updated by an `AFTER INSERT` trigger on
`ledger_entries`. A code path can't forget to update it, and reading a balance is O(1). A guard
trigger rejects any other change to an account, and uses `pg_trigger_depth()` to tell a direct
`UPDATE` from the entry trigger's. The reconciliation job still recomputes every balance from its
entries: the database prevents what it cheaply can, and reconciliation detects everything else,
including a DBA who switched triggers off.

### 4. Concurrency: lock, check, write

`LedgerService.post` locks every account it touches with `SELECT … FOR UPDATE`, **sorted by
account id**, then checks funds, then inserts the entries. Locking gives no lost updates and no
overdraft: the balance can't change between the check and the write. The fixed order rules out
deadlocks, because two transfers in opposite directions (A→B and B→A) lock in the same order. The
lock hierarchy is fixed: idempotency key → payment → refund → accounts. A `CHECK
(allow_negative OR balance_minor >= 0)` constraint is the last line of defence. Payments and
refunds also carry `@Version` (optimistic locking) as a second safety net, and `lock_timeout = 5s`
makes a stuck lock fail fast instead of hanging requests.

### 5. Idempotency keys

Every money-moving endpoint requires `Idempotency-Key`. In **one** database transaction, the
service:

1. inserts `(merchant, key, fingerprint)`;
2. moves the money;
3. stores the response.

A concurrent retry with the same key blocks on the primary key until the first request commits,
then replays the stored response (`Idempotent-Replayed: true`). No "in progress" state can get
stuck. The fingerprint is a SHA-256 of method, path and body, so reusing a key for a *different*
request is a 422. A failed request rolls back its key too, so the client can fix the problem and
retry with the same key. Keys expire after 24 hours.

### 6. Sagas for flows that span two systems

A card payment involves our database and the provider, and they can't share one ACID
transaction. Each step is therefore a local transaction, and the persisted status records how far
the flow got:

```
top-up:  [tx] CREATED ──after commit──▶ POST /v1/charges (Idempotency-Key: payment-{id})
              [tx] → PROCESSING ──webhook──▶ [tx] → SUCCEEDED + TOP_UP posting   (or → FAILED, nothing to undo)

refund:  [tx] REFUND_RESERVE (wallet → in transit), CREATED ──after commit──▶ POST /v1/refunds
              ──webhook──▶ [tx] SUCCEEDED + REFUND_SETTLE
                        or [tx] FAILED  + REFUND_REVERSAL   ← compensating transaction
```

- The provider is called **after commit**, never inside a transaction. Calling it inside would
  hold locks during a slow network call, and could charge a card for a row that then rolls back.
- A timeout or 5xx means **outcome unknown**. The operation stays `CREATED` and is never marked
  failed. Only a 4xx is a definite "no".
- `SagaRecoveryJob` resumes stuck sagas. It resubmits `CREATED` work with the same idempotency
  key (so the provider returns the same charge), and asks the provider about `PROCESSING` work in
  case a webhook was lost.
- Compensation never edits the reservation. It adds a reversal, so the ledger shows exactly what
  happened.

### 7. Webhooks: authentic, exactly-once, forward-only

- **Signature**: `Provider-Signature: t=<unix>,v1=<HMAC-SHA256(secret, t + "." + raw body)>`,
  verified over the raw bytes before any parsing. The comparison is constant-time, and the
  timestamp must be within 5 minutes (replay protection). Several `v1` values are accepted, for
  secret rotation.
- **Duplicates**: the event id is inserted into `webhook_events` (UNIQUE) in the same transaction
  that applies the event. A redelivery hits the key and changes nothing. Concurrent redeliveries
  are serialized by Postgres on that key.
- **Out of order**: all provider news goes through one state machine, `ProcessingStatus.on`, an
  exhaustive `switch` that only moves forward. A stale `processing` after `succeeded` is ignored.
  A contradiction (`succeeded` after `failed`, or a different amount) is flagged `CONFLICT`,
  audited, and moves no money.
- We answer 200 to everything we accepted, so the provider stops retrying. A bad signature is a
  401 and changes nothing.

### 8. Reconciliation

A nightly job (00:15 UTC, plus `POST /admin/reconciliations`) proves the books balance. All
internal checks run in **one `REPEATABLE READ` snapshot**, so a transfer committing mid-run can't
produce a false alarm. A Postgres advisory lock lets only one run happen at a time, across
instances.

| Check | Question |
|---|---|
| `entries_sum_to_zero` | does every transaction balance? |
| `transactions_have_two_entries` | no one-legged transactions? |
| `balances_match_entries` | does every cached balance equal the sum of its entries? |
| `no_negative_balances` | no wallet or in-transit account below zero? |
| `trial_balance` | do debits equal credits per merchant and currency? |
| `assets_equal_liabilities` | provider clearing = wallets + refunds in transit? |
| `payments_match_ledger` | does each SUCCEEDED top-up have exactly one matching posting, and every other top-up none? |
| `refunds_match_ledger` | does each refund have the postings its status implies? |
| `refunded_amounts_match` | does each payment's refunded total equal its non-failed refunds? |
| `provider_settlement` | do our settled charges and refunds for the day match the provider's report, in both directions? |

The report is stored as append-only JSON. The job only reports: correcting money is a human
decision, made with new, visible transactions.

### 9. Security

- Merchant API keys are 256-bit random (`sk_test_…`), stored only as SHA-256 hashes. A fast hash
  is fine because there is nothing to brute-force. The key is shown once.
- The merchant comes only from the key, never from a request. Someone else's wallet is a 404.
- Operators use a separate admin token (32+ characters, from `.env`; the app refuses to start
  without it).
- Least privilege in the database: the runtime login can't `UPDATE`/`DELETE` ledger history, run
  DDL, or disable triggers. The tests run as that same login, so a missing `GRANT` fails a test
  instead of failing in production.
- Secrets live only in `.env` (git-ignored). Compose uses `${VAR:?}`, so nothing has a default
  password.

## Invariants and where they are enforced

| Invariant | Database | Java | Proven by |
|---|---|---|---|
| A transaction's entries sum to zero | deferred constraint trigger at COMMIT | `PostingRequest` | `LedgerDatabaseTests`, `PostingRequestTests` |
| At least two entries | same trigger (also on the header) | `PostingRequest` | `LedgerDatabaseTests` |
| Entry currency/merchant = transaction's = account's | composite foreign keys | `LedgerService` | `LedgerDatabaseTests` |
| History is append-only; committed transactions are sealed | no grants + triggers + `created_xid` check | `@Immutable` entities | `LedgerDatabaseTests` |
| Balance = sum of entries | trigger maintains it, guard blocks edits | read via `balanceOf` | `LedgerDatabaseTests`, `ReconciliationTests` |
| No overdraft | `CHECK` on accounts | locked check in `LedgerService` | `ConcurrencyTests` |
| No lost update, no deadlock | row locks | sorted lock order | `ConcurrencyTests` |
| A request moves money at most once | idempotency PK in the same transaction | `IdempotencyService` | `TransferApiTests`, `ConcurrencyTests` |
| A webhook applies at most once | UNIQUE event id | `ProcessingStatus` forward-only | `TopUpWebhookTests`, `ConcurrencyTests` |
| Refunds never exceed the payment | `CHECK (refunded_minor BETWEEN 0 AND amount_minor)` | payment row lock | `RefundSagaTests`, `ConcurrencyTests` |
| Books agree with the provider | | `SettlementComparison` | `ReconciliationTests`, `SettlementComparisonTests` |

## Tests

```powershell
# ledger-service: Checkstyle, javac -Xlint:all -Werror, unit + integration + concurrency tests
# (~3-4 min with a warm Maven cache; the very first run downloads everything and can take 15-30 min)
docker run --rm -v "${PWD}\services\ledger-service:/workspace" -v payment-ledger-m2:/root/.m2 `
  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal `
  -w /workspace eclipse-temurin:25-jdk sh mvnw -B verify

# provider-sim
docker run --rm -v "${PWD}\services\provider-sim:/workspace" -v payment-ledger-m2:/root/.m2 `
  -w /workspace eclipse-temurin:25-jdk sh mvnw -B verify
```

79 tests in `ledger-service` and 5 in `provider-sim`. CI also brings up the Compose stack and
runs the demo script against it.

Integration tests run the whole app against a real Postgres 18 (Testcontainers), as the same
least-privileged login production uses, with a fake payment provider and webhooks signed with the
real algorithm. The highlights:

- **`ConcurrencyTests`** (real HTTP, 32 threads, released together by a start gate):
  - 40 simultaneous 1.00 transfers from a wallet holding 10.00: exactly 10 succeed, the balance
    ends at 0.00, and no request goes negative;
  - 80 crossing A↔B transfers: no deadlock, no lost update, money conserved;
  - one idempotency key from 20 threads: one transfer, 19 replays;
  - 12 simultaneous refunds of 3.00 on a 10.00 payment: exactly 3 succeed;
  - 20 concurrent webhooks: one is applied, the rest are duplicates or ignored.

  Every test ends with a reconciliation run that must find nothing.
- **`LedgerDatabaseTests`** bypasses the Java code and writes SQL directly, then checks that
  Postgres rejects:
  - unbalanced and one-legged transactions;
  - edits, deletes and truncates of ledger history;
  - entries added to a committed transaction;
  - direct balance edits;
  - overdrafts;
  - cross-currency and cross-merchant entries.
- **`TopUpWebhookTests`** covers:
  - bad, stale and tampered signatures;
  - duplicate and out-of-order webhooks;
  - declines, and conflicting news after one;
  - a wrong amount;
  - a webhook that overtakes the provider's own HTTP response;
  - provider outages, retried with the same key;
  - lost webhooks, recovered by polling.
- **`RefundSagaTests`** covers:
  - reservation, then settlement;
  - compensation after an asynchronous and after a synchronous failure;
  - over-refunds;
  - money the customer already spent;
  - other merchants' payments.
- **`ReconciliationTests`**: clean books balance; a balance a superuser edited with triggers
  switched off is caught; a credit the provider never settled is caught.

## Trade-offs and next steps

- **Hot accounts.** Every top-up for a merchant and currency locks the same `PROVIDER_CLEARING`
  row, which serializes them. At scale, shard system accounts (N sub-accounts, summed in reports)
  or post to them asynchronously.
- **Synchronous provider calls in the saga** run on a virtual thread after commit. A crash in
  between is covered by the recovery job. A transactional outbox table would make the handoff
  explicit and observable.
- **No FX.** One currency per transaction. Multi-currency would need FX accounts and rates.
- **provider-sim is in memory.** Restart it and the next reconciliation reports earlier charges
  as missing at the provider, which is correct behaviour for a provider that lost its records.
- **Not built yet:**
  - API key revocation endpoint;
  - wallet freezing/closing;
  - rate limiting;
  - payouts;
  - an operator UI (e.g. React over the admin API);
  - OpenAPI docs;
  - metrics on saga lag and reconciliation findings.
- **CI has not run yet.** The workflow is written, but the project has no git remote.
- **Lock failures in the ledger return 500, not 409.** A lock timeout or deadlock while
  `LedgerService` locks accounts surfaces as an untranslated JPA exception. The sorted lock order
  rules out deadlocks, but a 5 s lock timeout on a hot account is possible. It's a small fix,
  item 2 in the build plan.

The ordered build plan for all of these is in
[AGENTS.md: Status](AGENTS.md#status-whats-built-and-whats-left), with enough detail to pick up
the next item. The concepts behind the code, a reading order, a glossary and exercises are in
the [Learning guide](AGENTS.md#learning-guide).

## Project layout

```
services/ledger-service/   Spring Boot API (packages: ledger, wallet, transfer, payment, webhook,
                           provider, idempotency, audit, reconciliation, merchant, security)
  src/main/resources/db/migration/   Flyway: V1 ledger core, V2 wallets/payments/refunds, V3 ops tables
services/provider-sim/     simulated card provider (charges, refunds, signed webhooks, settlements)
infra/postgres/init/       creates the owner and app logins
scripts/demo.ps1           end-to-end demo (also run in CI)
AGENTS.md                  status + build plan, setup on a new machine, operating manual,
                           learning guide (CLAUDE.md imports it)
```
