# Banking App — Microservices Platform

A Spring Boot microservices banking backend covering identity & 2FA, KYC, account management, internal/external transfers with fraud review, and event-driven notifications — built end-to-end from a real functional-requirements spec (see [Built from a real spec](#built-from-a-real-spec) below), not a toy CRUD demo.

## Architecture

```mermaid
graph TD
    Browser([Browser])
    Frontend[Angular frontend :4200]
    Client([Swagger UI])
    Ingress[nginx-ingress]

    Auth[auth-service :8081]
    Profile[profile-service :8082]
    Account[account-service :8083]
    Txn[transaction-service :8084]
    Notif[notification-service :8085]
    Audit[audit-service :8086]

    PG[(PostgreSQL)]
    Kafka[(Kafka)]
    Redis[(Redis)]

    Browser --> Frontend
    Frontend -- "REST, CORS-enabled" --> Auth
    Frontend -- "REST, CORS-enabled" --> Profile
    Frontend -- "REST, CORS-enabled" --> Account
    Frontend -- "REST, CORS-enabled" --> Txn
    Client --> Ingress
    Ingress --> Auth
    Ingress --> Profile
    Ingress --> Account
    Ingress --> Txn

    Txn -- "internal REST: transfer/debit/credit, account owner" --> Account
    Txn -- "internal REST: KYC status (sender and recipient)" --> Profile
    Account -- "internal REST: KYC status" --> Profile
    Profile -- "internal REST: phone number" --> Auth
    Notif -- "internal REST: preferences, balances-batch" --> Profile
    Notif -- "internal REST: balances-batch" --> Account

    Auth --> PG
    Profile --> PG
    Account --> PG
    Txn --> PG

    Profile -- "profile-events, kyc-events" --> Kafka
    Txn -- "successful-transfers, large-transfers-review" --> Kafka
    Auth -- "notification-events (SMS 2FA), user-events (registration)" --> Kafka
    Kafka --> Notif
    Kafka --> Audit
    Kafka -- "user-events" --> Profile
    Kafka -- "user-events" --> Account

    Notif --> Redis
```

`account-service` is the sole owner of the `accounts`/`transactions` tables (the balance ledger and dashboard history) — every other service that needs to move money or read balances goes through its internal API (`/api/v1/internal/**`) rather than touching those tables directly. `transaction-service` now keeps its own `wire_transactions` table for tracking external wire transfer status and fraud-review state, so the two services no longer share a table between them — `transaction-service` just calls `account-service`'s internal API to actually debit/credit an account once a wire clears.

`notification-service`'s internal Feign clients default to `localhost` (`:8083` for account-service, `:8082` for profile-service) so the whole stack works out of the box locally; the k8s `prod` profile overrides these via `PROFILE_SERVICE_URL`/`ACCOUNT_SERVICE_URL` env vars to reach the in-cluster service names instead.

## Tech stack

**Backend**
- **Java 17 / Spring Boot 3.3** — Spring Web, Spring Security (OAuth2 Resource Server), Spring Data JPA, Spring AOP, Spring Kafka, Spring Cache (Redis), OpenFeign
- **PostgreSQL 15** (Flyway migrations), **Apache Kafka** (KRaft mode, no ZooKeeper), **Redis 7**
- **JWT** (HS256, shared HMAC secret) — short-lived Full-Auth tokens, scoped Pre-Auth tokens for in-progress 2FA
- **JUnit 5 / Mockito / MockMvc / AssertJ** — every service has a full acceptance-test suite
- **Terraform** (AWS VPC/RDS/EKS) + **Helm** (Kafka/Redis/ingress-nginx) + **Kubernetes manifests** + **Docker** + **GitHub Actions** CI/CD

**Frontend** (`frontend/`)
- **Angular 22** (standalone components, signal-based state), **TypeScript**, plain CSS
- **Karma / Jasmine** for unit tests, **Cypress** for end-to-end tests
- JWT bearer auth with an HTTP interceptor (silent refresh-and-retry on 401), route guards on every authenticated page

## Built from a real spec

This system was implemented against 10 functional-requirement documents (FR1–FR10, covering 2FA, session management, KYC, profile updates, account overview, transaction history, internal/external transfers, and notifications) — not built ad hoc. Nearly every controller and service method carries a `FR#.# AC#` comment tracing it back to the specific user story and acceptance criterion it satisfies, so the spec-to-code mapping is auditable directly in the source.

## Services

| Service | Port | Responsibility |
|---|---|---|
| `01-auth-service` | 8081 | Login, registration, device fingerprinting, TOTP/SMS 2FA, refresh/logout, JWT issuance — sole owner of the phone number 2FA codes are sent to |
| `02-profile-service` | 8082 | KYC verification/webhook/admin override, identity & contact info, alert & daily-summary preferences; provisions a profile on registration |
| `03-account-service` | 8083 | Account dashboard, paginated transaction history, self-service open-account/add-funds — sole owner of the accounts ledger; provisions a starter account on registration |
| `03-transaction-service` | 8084 | Internal transfers, external wires, fraud-threshold review |
| `04-notification-service` | 8085 | Kafka-driven real-time alerts + scheduled daily balance summary; real SMS/email delivery via Twilio/SendGrid; exposes `/api/v1/notifications` |
| `05-audit-service` | 8086 | Immutable, insert-only audit log of profile/KYC changes (no REST API) |
| `frontend` | 4200 | Angular web client — login/2FA, dashboard, transactions, transfers, history, notifications, profile, alert preferences |

## Frontend

`frontend/` is an Angular 22 single-page app that talks to `auth-service`, `profile-service`, `account-service`, `transaction-service`, and `notification-service` directly over REST (`audit-service` has no REST API, so the frontend never calls it). Each of those services has a `CorsConfigurationSource` bean scoped to `http://localhost:4200` with credentials enabled, since the frontend and backend run on different ports locally.

**Pages:** `/signup` (self-service registration) → `/login` (credentials + SMS 2FA) → `/dashboard` (account list, plus opening a further account and adding funds — both KYC-gated) → `/accounts/:id/transactions` (paginated history) → `/transfer` (own-account transfers, paying another user by account number, and external wire — all KYC-gated) → `/history` (ledger entries and wires merged, filterable) → `/notifications` (delivered alert log) → `/profile` (identity verification, KYC status, account number/IBAN/SWIFT to receive money) → `/profile/alerts` (threshold + daily summary).

**Registration provisioning:** `POST /api/v1/auth/register` (or the `/signup` page) creates the auth-service credentials, then publishes a `user-events` Kafka event that `profile-service` and `account-service` each consume independently to provision their own initial row — a `PENDING_VERIFICATION` profile and a `$0` `CHECKING` account — so a freshly-registered user has a usable (if empty) dashboard and KYC status immediately, no manual seeding required. That starter account comes from the Kafka listener, not the self-service open-account path, so it is deliberately outside the KYC gate — otherwise a brand-new user would be verified-gated out of the very account they need in order to get verified. See [Identity verification (KYC)](#identity-verification-kyc) below for how a user gets verified so the rest unlocks.

Registration also rejects a phone number that is already registered to somebody else with a `409`, alongside the existing duplicate-username and duplicate-email checks. The number is what receives that account's 2FA codes, so letting two users share one hands the second of them the keys to the first one's login.

## Running this project

**Prerequisites:** Docker Desktop running, Java 17 (JDK), and ports `5432`, `9092`, `6379`, and `8081`-`8086` free. No `.env` file or secrets setup needed — each service's `application.yml` already has dev-profile defaults that match `docker-compose.yml`, so this runs with zero configuration.

**1. Clone and start the infra stack** (Postgres, Kafka in KRaft mode, Redis) from the repo root:

```bash
git clone <this-repo-url>
cd my-banking-project
docker-compose up -d
```

**2. Start each service**, one per terminal, from that service's own directory (Windows: use `mvnw.cmd spring-boot:run` instead of `./mvnw spring-boot:run`):

| # | Directory | Command | Port |
|---|---|---|---|
| 1 | `01-auth-service` | `./mvnw spring-boot:run` | 8081 |
| 2 | `02-profile-service` | `./mvnw spring-boot:run` | 8082 |
| 3 | `03-account-service` | `./mvnw spring-boot:run` | 8083 |
| 4 | `03-transaction-service` | `./mvnw spring-boot:run` | 8084 |
| 5 | `04-notification-service` | `./mvnw spring-boot:run` | 8085 |
| 6 | `05-audit-service` | `./mvnw spring-boot:run` | 8086 |

`transaction-service` and `notification-service` call `account-service`/`profile-service` internally, so it's cleanest to start those two first — but since those are just REST calls, starting all six in any order works too as long as they're all up before you exercise the API.

**3. Verify it's working** — open any of the Swagger UIs below and try an endpoint (e.g. `POST /api/v1/auth/login` on the auth-service docs).

**4. Run the backend tests** for any service:

```bash
./mvnw test                    # run from inside any one service's directory
```

**5. Create a test user** — register via `POST /api/v1/auth/register` (or the frontend's `/signup` page) with `{"username", "password", "phoneNumber", "email"}`. This is the preferred path: it also publishes the `user-events` Kafka event that provisions a `PENDING_VERIFICATION` profile and a `$0` checking account (with its own IBAN) automatically (see [Frontend](#frontend) above) — `auth-service`, `profile-service`, and `account-service` all need to be running for that to happen. Give each test user a distinct phone number: usernames, emails and phone numbers are all unique, so reusing a number from an earlier test user comes back as a `409` rather than creating the account.

If you'd rather skip the API and insert a user directly into Postgres (bcrypt hash below is for password `Password123!`), note that this bypasses the `user-events` publish entirely — you'll need to seed `user_profiles`/`accounts` rows yourself too:

```bash
docker exec banking-postgres-local psql -U dbadmin -d banking -c "
INSERT INTO users (username, password, phone_number, totp_enabled)
VALUES ('e2etest', '\$2b\$10\$FQ/4MWYZrC9XB.zJl1TFuemdJY2lMP7hFzpdHAkweAHHhZP2UBKme', '+15551234567', false);
"
```

Either way, KYC starts out `PENDING_VERIFICATION`, and transfers, opening a further account and adding funds are all gated on it — fill in the verification form on `/profile` to get approved (see [Identity verification (KYC)](#identity-verification-kyc)), or update `user_profiles` directly. The starter account itself arrives regardless, so an unverified user still has something to look at. To test paying another user you need **two** verified users: the recipient's own verification is checked as well as the sender's. First login from a new browser is a 2FA challenge — the SMS code is only published to Kafka (`notification-service` just logs it, since email/SMS providers are placeholders) — so for local testing without running `notification-service`, insert a `recognized_devices` row for that user (`device_hash` = base64(SHA-256(raw-device-id))) and send that raw value as a `Device-ID` cookie on login to skip 2FA entirely.

**6. Start the frontend** from `frontend/`:

```bash
npm install
npm run start                  # ng serve, http://localhost:4200
```

Run its tests with `npm test` (Karma/Jasmine) or `npx cypress run` (Cypress E2E — needs the full backend + `ng serve` already running, plus the seeded user above).

**7. Shut down** the infra stack when you're done:

```bash
docker-compose down
```

### API docs

`auth-service`, `profile-service`, `account-service`, and `transaction-service` each expose interactive Swagger UI once running locally:

- http://localhost:8081/swagger-ui.html
- http://localhost:8082/swagger-ui.html
- http://localhost:8083/swagger-ui.html
- http://localhost:8084/swagger-ui.html

### Identity verification (KYC)

Money movement is KYC-gated: `transaction-service`'s `KycEnforcementAspect` checks a user's status
before any money moves, and a new registration starts at `PENDING_VERIFICATION`.

`account-service` now applies the same gate, through its own `@RequiresKyc` aspect, to **opening an
account and adding funds**. Taking money in is as much a know-your-customer moment as sending it out,
and gating one without the other just meant an unverified identity could still put money into the
bank and then wait. The one deliberate exception is the starter account a brand-new user is given at
registration: that is built directly by `account-service`'s `user-events` Kafka listener, which never
goes through the self-service path, so a `PENDING_VERIFICATION` user still gets their `$0` checking
account and a dashboard to log into. Gating that would have been circular.

A user verifies themselves by submitting the identity form on `/profile` — full legal name, date of
birth, phone and address. The form **pre-fills from the contact information already on file**, which
is the fix for a specific failure: an empty phone field got retyped from memory, in a different
format or as a different number entirely, by a user who thought they were confirming what the bank
already had. On a valid submission `profile-service` promotes them straight to `APPROVED` and returns
the new status on the same response, so the page reflects it immediately and transfers unlock without
a reload.

This stands in for the identity vendor's callback (`POST /api/v1/webhooks/kyc-update`, HMAC-signed),
which is what would approve a user in a real deployment. It is therefore gated on `app.demo.enabled`
— `true` in `application.yml`, `false` in `application-prod.yml` — so a real deployment still has to
hear from the vendor. Two rules hold regardless of that flag:

- **Minimum age 18.** An underage date of birth is a `400`, not a silent non-approval.
- **A `REJECTED` applicant is never re-approved** by editing their details. Clearing a rejection
  belongs to the vendor or a compliance officer's override, not to the applicant.

An earlier "Simulate KYC Approval (Demo)" button approved a user on a click with no information
collected at all; it has been removed in favour of the form above.

**The phone number belongs to `auth-service`, not to the identity form.** `users.phone_number` is the
address 2FA codes are actually delivered to, so it is the only copy that can be authoritative. The
identity form no longer keeps an independently editable copy: `profile-service` writes the submitted
number through to `auth-service`'s internal phone-number endpoint first, stores whatever E.164 value
comes back, and reads that same endpoint when pre-filling. If `auth-service` rejects the number the
submission throws before anything is saved — no profile write, no Kafka event, and no KYC approval.
A unique index backs the rule on both sides (`uk_users_phone_number`, `uq_user_profiles_phone_number`),
so a code path that ever wrote the column directly would be refused by the database rather than
quietly recreating a duplicate. Before this, the identity form could claim a number already
registered to another user, and "changing" a number here left login codes still going to the old one
with nothing to indicate the two had drifted apart. Re-submitting your own unchanged number is a
success, not a conflict — otherwise nobody could edit their address without also changing their phone.

**The recipient has to be verified too.** A sender passing their own KYC check is only half of it:
`transaction-service`'s `RecipientKycValidator` also refuses a transfer whose destination belongs to a
user whose verification is not `APPROVED` — both for a payment by account number and for a wire whose
IBAN resolves to an account on this platform. The check runs before the sender is debited, so a
refused transfer reserves nothing. A wire to a genuinely external IBAN is untouched by this: the
lookup 404s, there is no local user behind it, and another bank's customer is not ours to verify. The
read-only recipient-confirmation lookup answers `verified: false` rather than a `403`, so the transfer
page can say why the button is disabled instead of failing at submit. A wire held for fraud review
re-checks the recipient at the moment a reviewer approves it, not just at submission: if they can no
longer receive funds the wire is reversed and refunded to the sender rather than credited or left
sitting in `PENDING_APPROVAL`, and the audit trail records the reviewer's actual `APPROVED` verdict
separately from the receiving-side reason the money went back.

### Notification providers

2FA codes and transaction alerts go out through swappable provider clients in
`04-notification-service`, selected by a single property each. Both default to `logging`, which
writes the message to the service log and needs no account or key — so everything above runs with
zero setup. Exactly one client bean matches the chosen value, so there is never any ambiguity.

| Property | Values | Delivers |
|---|---|---|
| `sms.provider` | `logging` (default), `textbelt`, `twilio` | 2FA codes |
| `email.provider` | `logging` (default), `twilio`, `sendgrid` | Balance summaries, transaction alerts, profile security notices |

The two real email options are different products, which is why both clients exist:

- **`twilio`** — Twilio Email (`POST https://comms.twilio.com/v1/Emails`). Authenticates with the
  same `TWILIO_ACCOUNT_SID`/`TWILIO_AUTH_TOKEN` pair the SMS client uses, so enabling email adds no
  new secret — only `TWILIO_FROM_EMAIL`. Requires the sender domain to be authenticated via DNS in
  the Twilio console.
- **`sendgrid`** — the classic SendGrid v3 API, a separate product with its own key. Its
  single-sender verification works without DNS access, which makes it the practical fallback when
  domain authentication isn't an option.

To send for real, copy the template and fill in your credentials:

```bash
cp .env.example .env      # .env is gitignored; .env.example holds variable names only
```

Then load it before starting `notification-service`:

```bash
# Git Bash / macOS / Linux
set -a && source .env && set +a
```

```powershell
# PowerShell
Get-Content .env | Where-Object { $_ -match '^\s*[^#].*=' } | ForEach-Object {
  $name, $value = $_ -split '=', 2
  [Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), 'Process')
}
```

Every credential is read from an environment variable with a blank fallback, so they are never
written into a tracked file. **Do not replace the `${VAR:}` placeholders in `application.yml` with
literal values** — that file is committed, and anything that reaches git stays in the history even
after it's deleted. A leaked Twilio auth token can be used to send messages billed to your account;
rotate it in the Twilio console immediately if that happens.

Credentials have no defaults on purpose: a real provider selected with a blank credential fails at
startup naming the missing property, rather than silently dropping notifications.

For Kubernetes, the notification-service deployment reads the same variable names from a
`banking-notification-secret` that is deliberately **not** in this repo — create it with
`kubectl create secret generic` (the exact command is in `k8s/07-notification-service.yaml`). Every
key is marked `optional: true`, so the pod still starts without it and falls back to logging.

`textbelt` is a middle option for SMS: its free tier needs no signup at all (1 text/day/IP), enough
to prove the 2FA path end-to-end without opening a Twilio account.

The notification feed records that a 2FA code was sent, not the code itself — the stored line is
`Verification code sent to ***4567.` while the real SMS carries the code. A one-time code written
into a page the user can reopen later is a credential sitting in an audit trail, so `V3` in
`04-notification-service` also redacts any historical rows.

Email is delivered to the address captured at registration and stored on the user's profile. A user
registered before that field existed has none, and is skipped with a logged warning rather than
being mailed at a fabricated address.

### Daily balance summary

`DailyBalanceSummaryJob` runs hourly, fetches everyone who opted in on `/profile/alerts`, and emails
each user whose own local time has reached the hour **they** chose. Both halves of "8am my time" are
per-user: the timezone and the delivery hour are picked on that page and stored on the user's
preferences (`daily_summary_hour`, whole hours, defaulting to `8`). The comparison is done with
`ZonedDateTime` against the user's zone, so an hour stays put across a daylight-saving switch.

There is deliberately no global hour setting any more. One existed (`notification.daily-summary.hour`
/ `DAILY_SUMMARY_HOUR`) back when every customer was mailed at the same hour, and keeping it would
have meant two mechanisms competing to answer the same question — an override that forced everyone
onto one hour would defeat the point of letting them choose. To watch the scheduled path run without
waiting for morning, use the manual trigger below, which skips the hour check entirely.

Whole hours only: the job is driven by an hourly cron, so a half-hour zone such as India cannot be
served a `:30` slot no matter what is stored.

For an immediate check, trigger it directly:

```bash
# One timezone, right now - skips the hour check entirely
curl -X POST "http://localhost:8085/api/v1/internal/notifications/daily-summary/run?timezone=America/New_York"

# Or the exact sweep the scheduler would run
curl -X POST "http://localhost:8085/api/v1/internal/notifications/daily-summary/run"
```

Like every other unauthenticated endpoint in this project it lives under `/api/v1/internal/`, the one
prefix `k8s/08-ingress-routes.yaml` deliberately does not route — it sends real email, so a routed
version would be reachable by anyone who could reach the load balancer.

Each summary is written to `notification_records` as a `DAILY_SUMMARY`, so it appears in
`/notifications` alongside the Kafka-driven alerts. A user who opted in but has no email address on
file is recorded `FAILED` rather than passed over silently.

## Infrastructure

Terraform (`terraform/`) defines the target AWS footprint (VPC, RDS Postgres, EKS with a managed node group); Helm values (`helm/`) configure Kafka/Redis/ingress-nginx on the cluster; `k8s/` holds the namespace, config/secrets, and per-service Deployment/Service manifests. `.github/workflows/build-and-test.yml` runs every service's test suite on every push/PR to `main`. `.github/workflows/deploy-to-eks.yml` (GHCR image build/push + `kubectl apply` to EKS) exists but is entirely commented out until the four AWS secrets it needs are actually configured — see the comment at the top of that file to re-enable it.

**This has been validated (`terraform validate` → `Success!`) but deliberately not applied** — standing up a real EKS/RDS environment costs real money on an ongoing basis, which isn't worthwhile for a portfolio project. Everything below the ingress has been exercised locally via `docker-compose` instead.

## Known limitations

Being upfront about what's intentionally not production-complete:

- **Notification providers default to logging.** Real delivery is wired and ready — Twilio for SMS, Twilio Email or SendGrid for email — but `sms.provider`/`email.provider` default to `logging` so the project runs with zero credentials. Set them to `twilio` and supply the keys (see [Notification providers](#notification-providers) below) to send for real.
- **Twilio Email sends are fire-and-forget.** The API is asynchronous: a `202 Accepted` means queued, not delivered, and returns an `operationId` the client logs. Polling `operationLocation` for the final per-message outcome isn't implemented, so a message accepted by Twilio and then bounced is recorded `SENT` here.
- **The daily summary has no distributed lock.** `k8s/07-notification-service.yaml` pins `replicas: 1` precisely because a second replica would run the same hourly sweep and double-send. Scaling that deployment out needs ShedLock or equivalent first.
- **No role-based authorization system yet.** Profile-service's admin KYC-override endpoint requires `ADMIN`/`COMPLIANCE_OFFICER` roles, but nothing in the system currently grants roles to a user — that endpoint is reachable in code but not yet in a real deployment.
- **Kafka-provisioned profiles/accounts are minimal.** The `user-events` consumer in `profile-service`/`account-service` (see [Frontend](#frontend) above) only sets the bare minimum — a `PENDING_VERIFICATION` profile with no address, and a single `$0` checking account. If Kafka is down when a user registers, they end up with credentials but no profile/account until manually backfilled (no dead-letter/retry queue yet, just a logged error). They aren't stranded — the KYC status lookup provisions a profile row on read, and once verified they can open an account through the self-service path — but the starter account they should have had never arrives on its own.
- **The phone-number write-through isn't a distributed transaction.** `profile-service` updates `auth-service` first and then saves its own mirror row, precisely so a rejected number never reaches KYC approval. The cost is the opposite ordering risk: if the local save or the Kafka publish fails afterwards, `profile-service` rolls back while `auth-service` has already accepted the new number, so the mirror is briefly stale against the real 2FA destination. Re-submitting the form reconciles it, and the pre-fill reads the authoritative copy so the user is shown the number that actually matters — but there's no outbox or compensating write behind it yet.
- **Recipient KYC only reaches this platform's users.** A wire to an IBAN that doesn't resolve to a local account is sent without any check on who receives it, which is correct — there is no user to look up and no basis to verify another bank's customer — but it does mean the recipient gate is a same-platform guarantee, not a general one.
- **IaC is validated, not deployed** (see [Infrastructure](#infrastructure) above).

**On endpoint exposure:** every unauthenticated endpoint lives under `/api/v1/internal/`, which is
deliberately absent from `k8s/08-ingress-routes.yaml` and so is unreachable from outside the
cluster. Everything the ingress does route requires a valid JWT and resolves the user from that
token rather than from a client-supplied id, so one user cannot read another's data by changing a
number in a URL. Adding a new unauthenticated endpoint anywhere outside that prefix would publish
it to the internet — that is the rule to keep in mind when extending any of the services.

## License

MIT — see [LICENSE](LICENSE).
