# ClinicScheduler

A specialty-practice appointment-scheduling system: an Angular single-page app talking to two
independent Spring Boot microservices backed by MySQL, with real double-booking prevention,
measured index tuning, a browser-level conflict UX, and one-command Docker Compose startup.

## What it does

Patients (or front-desk staff acting for them) search open 30-minute slots by specialty, clinic
location and date range, then book one. The core requirement is the **double-booking invariant**:
a slot can produce at most one active appointment, even when many clients submit the same slot at
the same instant. The system enforces it in two independent layers:

1. **provider-service** flips `OPEN → BOOKED` with JPA `@Version` optimistic locking; concurrent
   losers get `409 SLOT_ALREADY_TAKEN`.
2. **appointment-service** has database unique keys `uk_slot_active (slot_id)` and
   `uk_patient_start (patient_id, start_time)`; the database is the final authority, and a failed
   insert releases the reservation (compensating action).

`provider-service` (port 8081) serves the provider directory, availability search and slot state
transitions. `appointment-service` (port 8082) owns booking, cancellation, patient history and the
demo JWT identity provider. The Angular app (port 4200 in dev, 8080 through nginx in Compose) is
the only client.

Demo accounts:

| Email | Password | Role | Patient id |
| --- | --- | --- | --- |
| `staff@clinic.test` | `staff123` | STAFF | — |
| `patient@clinic.test` | `patient123` | PATIENT | `patient-1` |

## Architecture

```
                    +-----------------------------+
                    |  Angular 20 SPA (web/)      |
                    |  standalone components      |
                    |  reactive forms, Router     |
                    |  JWT HttpInterceptor        |
                    +--------------+--------------+
                                   |
                 same-origin /api (dev proxy or nginx)
                                   |
          +------------------------+-------------------------+
          |                                                  |
          v                                                  v
+--------------------------+                     +---------------------------+
| appointment-service:8082 |  RestClient (HTTP)  |  provider-service:8081    |
| booking workflow         | ------------------> |  provider directory       |
| JWT issue + validation   |  reserve / release  |  availability search      |
| appointments table       |                     |  providers + slots        |
+------------+-------------+                     +-------------+-------------+
             |                                                 |
             v                                                 v
      MySQL appointment_db                             MySQL provider_db
      Flyway V1                                        Flyway V1..V3
```

**Why two services.** The provider domain is read-heavy, changes slowly, and owns the tuned
availability index; the appointment domain is write-heavy and owns the database constraints that
settle booking races. Separate schemas let each side scale, deploy and be tested independently,
at the cost of one network hop and the reserve-then-compensate protocol. The trade-off is
documented in the limitations.

### Booking request flow

```
SPA POST /api/appointments (Bearer JWT)
  -> JwtAuthFilter validates HS256 token, role + patientId claims
  -> BookingService validates request and patient ownership
  -> provider-service POST /api/availability/{slotId}/reserve   (JWT forwarded)
        OPEN --@Version--> BOOKED, or 409 if already taken
  -> INSERT appointment (uk_slot_active, uk_patient_start)
        success -> 200 with the appointment
        duplicate key -> POST release (compensation) -> 409 DOUBLE_BOOKING_BLOCKED
```

Cancellation is the reverse: `DELETE /api/appointments/{id}` sets status `CANCELLED` and calls
provider-service `release`.

### Data model

- `providers`: id, full_name, specialty, npi_number (unique), clinic_location, active, created_at.
- `availability_slots`: id, provider_id (FK), start_time, end_time,
  status ENUM(`OPEN`,`HELD`,`BOOKED`), `version` for optimistic locking, created_at; unique
  `(provider_id, start_time)`.
- `appointments`: id, patient_id, patient_name, provider_id, slot_id, start_time, end_time,
  status ENUM(`BOOKED`,`CANCELLED`,`COMPLETED`), `version`, timestamps; unique
  `uk_slot_active (slot_id)` and `uk_patient_start (patient_id, start_time)`.

### Auth flow

`POST /api/auth/login` checks the two demo users and issues an HS256 JWT (`sub`, `name`, `role`,
`patientId`, `iat`, `exp`) signed with the shared `JWT_SECRET`. The Angular functional
`authInterceptor` attaches `Authorization: Bearer <token>`; `provider-service` mirrors the
`JwtAuthFilter` so reserve/release require a valid token, and appointment-service enforces
ownership (a PATIENT can only read/book/cancel their own `patientId`). Route guards:
`authGuard` protects `/search`, `/book`, `/appointments`; `staffGuard` protects the staff-only
`/staff/appointments` view. `/login` is public.

## Tech stack (versions actually used)

| Layer | Technology |
| --- | --- |
| Frontend | Angular 20.3 (standalone components, TypeScript 5.9 strict), RxJS 7.8, SCSS |
| Frontend tests | Karma 6.4.4, Jasmine 5.9, ChromeHeadless 157 |
| Backend | Java 21 (21.0.9 locally, Temurin 21 in Docker), Spring Boot 3.3.13 |
| Backend libs | Spring Web, Spring Data JPA, Spring Security, Bean Validation, Actuator, RestClient |
| Persistence | MySQL 8.0.46, Flyway 10.10.0 (`flyway-mysql`), Hibernate 6.5, HikariCP |
| Auth | `jjwt` 0.12.6 (HS256) |
| Build | Maven wrapper 3.9.9, Spring Boot Maven plugin |
| Backend tests | JUnit 5, MockMvc, Mockito, Testcontainers 1.21.3 (MySQL), WireMock 3.9.1, JaCoCo 0.8.12 |
| Packaging | Multi-stage Dockerfiles (Maven/Node → JRE 21/nginx 1.27-alpine), Docker Compose |
| Frontend tooling | Node 20 in Docker/CI (built locally on Node 26.13.0), npm 11, ESLint + angular-eslint 20 |
| CI | GitHub Actions: Java 21 temurin, Node 20, Docker Compose smoke |

## How to run

### a) One-command Docker Compose quickstart

```bash
cp .env.example .env          # edit secrets if you like; placeholders work locally
docker compose up --build
```

Then open **http://localhost:8080** and sign in with a demo account.

What starts:

1. `mysql` (MySQL 8, init script creates `provider_db` and `appointment_db`, healthcheck).
2. `provider-service` (Flyway migrates the provider schema, healthcheck).
3. `appointment-service` (Flyway migrates the appointment schema, healthcheck).
4. `seed` one-shot container: if the provider database is empty, loads **200 providers and
   60,000 slots**.
5. `web` (nginx serves the built SPA and proxies `/api/providers` + `/api/availability` to
   provider-service, `/api/appointments` + `/api/auth` to appointment-service).

Host ports: `8080` (SPA), `8081` (provider API), `8082` (appointment API), `3306` (MySQL).
Stop with `docker compose down`; wipe seeded data with `docker compose down -v`.

### b) Local development mode

```bash
cp .env.example .env

# MySQL only (the application containers would occupy 8081/8082)
docker compose up -d mysql

# Export DB_USER / DB_PASSWORD / JWT_SECRET from .env for both services
set -a; . ./.env; set +a

# Terminal 1
cd services/provider-service && ./mvnw spring-boot:run

# Terminal 2
cd services/appointment-service && ./mvnw spring-boot:run

# Terminal 3
cd web && npm ci && npm start
```

Open http://localhost:4200. `web/proxy.conf.json` forwards the four API prefixes to the two
services, so the SPA needs no CORS configuration. (If the Compose stack is already running, first
free the ports with `docker compose stop provider-service appointment-service web`.)

### c) Seed data

Compose seeds automatically when the database is empty. To reseed manually:

```bash
./scripts/seed.sh
```

The script applies Flyway migrations and loads `db/seed/generate_seed.sql`, then prints the real
counts:

```
providers                200
availability_slots       60000
open_slots               50800
distinct_specialties     8
distinct_locations       5
slot_date_span_days      89
```

### d) Run every test suite

```bash
# Backend: unit + MockMvc + Testcontainers integration + JaCoCo
cd services/provider-service && ./mvnw -B verify
cd ../appointment-service && ./mvnw -B verify

# Frontend: lint, Karma/ChromeHeadless with coverage, production build
cd ../../web
npm ci
npm run lint
npm test -- --watch=false --browsers=ChromeHeadless --code-coverage
npm run build

# Full-stack smoke through nginx (Compose stack must be up)
cd ..
./scripts/smoke.sh
```

Measured totals are in [Results](#results) below.

### e) Re-run the benchmark and concurrency harness

```bash
# Engine-side EXPLAIN plans + 200 randomized searches for both query shapes
./scripts/bench_search.sh

# 50 simultaneous bookings x 10 distinct slots against both services + MySQL
./scripts/concurrency_test.sh

# Scaled-down variant (used for a quick check)
HARNESS_CONCURRENCY=20 HARNESS_SLOTS=3 ./scripts/concurrency_test.sh
```

`bench_search.sh` reads the current schema, so the "before" run requires dropping the two V3
indexes first (or checking out the pre-V3 migration); the "after" run is the default state.
Captured outputs live in `docs/bench/`. The harness sources `.env` for DB credentials.

## API reference

All request/response bodies are JSON. "Bearer" means a valid `Authorization: Bearer <JWT>` is
required. Error bodies use `{"error": "<STABLE_CODE>", "message": "...", "timestamp": "...",
"path": "..."}`.

### provider-service (`:8081`)

| Method | Path | Auth | Success | Error statuses |
| --- | --- | --- | --- | --- |
| GET | `/api/providers?specialty=&location=&page=&size=` | public | 200 paged providers | 400 (bad page/size) |
| GET | `/api/providers/{id}` | public | 200 provider | 404 |
| GET | `/api/providers/{id}/availability?from=&to=` | public | 200 open slots | 400 (missing/invalid/inverted range), 404 |
| GET | `/api/availability/search?specialty=&location=&from=&to=&page=&size=` | public | 200 paged open slots | 400 (missing/invalid/inverted range) |
| POST | `/api/availability/{slotId}/reserve` | Bearer | 200 slot `BOOKED` | 401, 404, 409 `SLOT_ALREADY_TAKEN` |
| POST | `/api/availability/{slotId}/release` | Bearer | 200 slot `OPEN` | 401, 404, 409 `SLOT_NOT_BOOKED` |
| GET | `/actuator/health` | public | 200 `{"status":"UP"}` | — |

### appointment-service (`:8082`)

| Method | Path | Auth | Success | Error statuses |
| --- | --- | --- | --- | --- |
| POST | `/api/auth/login` | public | 200 `{token, tokenType, expiresIn, user}` | 400 `VALIDATION_ERROR`, 401 `INVALID_CREDENTIALS` |
| POST | `/api/appointments` | Bearer; STAFF or owning PATIENT | 200 appointment | 400 `VALIDATION_ERROR`/`INVALID_BOOKING`, 401 `UNAUTHORIZED`, 403 `FORBIDDEN`, 409 `DOUBLE_BOOKING_BLOCKED`, 503 `PROVIDER_SERVICE_UNAVAILABLE` |
| GET | `/api/appointments?patientId=` | Bearer; PATIENT restricted to own id | 200 list | 401, 403 |
| GET | `/api/appointments/{id}` | Bearer; owner or STAFF | 200 appointment | 401, 403, 404 |
| DELETE | `/api/appointments/{id}` | Bearer; owner or STAFF | 200 `CANCELLED` | 401, 403, 404, 409 `APPOINTMENT_NOT_ACTIVE` |
| GET | `/actuator/health` | public | 200 `{"status":"UP"}` | — |

Example:

```bash
TOKEN=$(curl -s -X POST localhost:8082/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"staff@clinic.test","password":"staff123"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')

curl -s "localhost:8081/api/availability/search?from=2030-01-01T00:00:00&to=2030-01-31T23:59:59&page=0&size=5" \
  -H "Authorization: Bearer $TOKEN"
```

## Results

All figures below come from the committed raw outputs in `docs/bench/` and are summarized in
[docs/RESULTS.md](docs/RESULTS.md). Seeded volume: **200 providers**, **60,000 availability
slots**, **50,800 OPEN slots**, 8 specialties, 5 locations, 90-day span.

### Availability-search latency and rows examined

200 randomized queries per shape after 20 warmup queries, engine-side timing on MySQL 8.0.46.

| Query shape | p50 before → after | p95 before → after | max before → after | Rows examined before → after | Winning index |
| --- | --- | --- | --- | --- | --- |
| Filtered by specialty + location | 0.433 → **0.325 ms** (−24.9%) | 0.718 → **0.446 ms** (−37.9%) | 1.602 → 1.354 ms | 800 → **605** (−24.4%) | `providers (specialty, clinic_location)` |
| Browse all open slots | 14.614 → **0.084 ms** (−99.4%, 174×) | 17.993 → **0.118 ms** (−99.3%, 152×) | 28.060 → 0.357 ms | 60,020 → **40** (−99.93%) | `availability_slots (status, start_time, provider_id)` |

`EXPLAIN` access-type change:

- Browse-all: `Table scan on s` (60,000 rows, filesort) → **`Covering index range scan on s using
  idx_slots_status_start_provider`** (20 rows, no filesort).
- Filtered: `Table scan on p` (200 providers) → **`Covering index lookup on p using
  idx_providers_specialty_location`** (5 providers).

Winning plan excerpt (from `docs/bench/after.txt`):

```
-> Covering index range scan on s using idx_slots_status_start_provider over
   (status = 'OPEN' AND '2026-11-14 00:00:00.000000' <= start_time < '2026-12-14 00:00:00.000000')
   (cost=6096 rows=30016) (actual time=0.408..0.411 rows=20 loops=1)
```

### Double-booking under concurrency

`./scripts/concurrency_test.sh`: 50 simultaneous `POST /api/appointments` for the same slot,
repeated for 10 distinct slots, against both running services and MySQL.

```
successful bookings : 10 (expected 10)
409 conflicts       : 490 (expected 490)
other errors        : 0 (expected 0)
appointment rows    : 10 (expected 10)
invariant           : PASS - exactly one winner per slot
```

### Tests and coverage

| Suite | Tests | Result |
| --- | ---: | --- |
| provider-service (unit + MockMvc + Testcontainers) | 27 | 27 passed |
| appointment-service (unit + MockMvc + Testcontainers) | 37 | 37 passed |
| Angular / Karma / Jasmine | 19 | 19 passed |

JaCoCo line coverage: **provider-service 82.6%** (190/230), **appointment-service 84.6%**
(270/319). Angular Karma line coverage: **73.81%** (172/233 lines); statements 74.07%,
branches 55.69%, functions 60%.

### CI jobs

`.github/workflows/ci.yml` runs three jobs:

1. `backend` — matrix over both services, Java 21 temurin + Maven cache, `./mvnw -B verify`,
   uploads Surefire/Failsafe reports and JaCoCo.
2. `frontend` — Node 20, `npm ci`, `npm run lint`, ChromeHeadless tests with coverage,
   production build.
3. `compose-smoke` — `docker compose up -d --build`, waits for the stack, runs
   `scripts/smoke.sh` (login → search → book 200 → duplicate book 409), uploads logs, tears down
   with `docker compose down -v`.

## Limitations and next steps

- **No distributed transaction.** Reserve-then-insert spans two services. If appointment-service
  crashes after the provider reservation and before the insert (or before compensation), the slot
  can remain `BOOKED` with no appointment. Production would add a reservation TTL/outbox and a
  reconciliation job. Compensation failures are logged, not retried.
- **Cancelled slots cannot be rebooked under the literal `uk_slot_active (slot_id)` constraint.**
  The constraint prevents double-booking by design, but because cancelled rows keep their
  `slot_id`, a released slot still has an appointment row. The production fix is a generated
  nullable `active_slot_id` with a unique index (or a partial/filtered index); the schema here
  follows the required simple unique-key design, and this is the honest consequence.
- **Demo identity provider.** Two users, plaintext credentials in source, symmetric shared JWT
  secret, no refresh tokens, revocation, MFA or password storage. Replace with OIDC/Keycloak or
  an EHR identity provider.
- **Single MySQL instance.** Both schemas share one database server and one volume; there is no
  replication, failover or backup automation.
- **Wall-clock `DATETIME`.** Times are stored and returned without timezone conversion; the
  clinic's local time is assumed. A real deployment needs an explicit clinic timezone policy.
- **Synthetic data.** 200 providers and 60,000 generated slots are for demonstration and
  benchmarking, not real clinical records.
- **Benchmark scope.** Timings are engine-side (stored procedure) on one developer laptop with a
  warm buffer pool, so they show the plan change and relative improvement, not absolute
  production latency.
- **No TLS or secrets manager in Compose.** The EC2 runbook is HTTP-only for a demo; production
  needs HTTPS (ALB/Caddy), a secrets manager, and restricted security groups.
- **Next steps:** reservation TTL + outbox, active-slot generated column, real IdP, per-clinic
  timezones, pagination cursors for large result sets, and a read replica for availability search.

## AI-assisted development

This repository was generated and refactored with an AI coding agent (DeepSeek V4.1 Flash via
OpenCode), including the Spring Boot services, Angular components, Flyway migrations, tests,
scripts, Dockerfiles and documentation. The output was verified rather than trusted:

- `./mvnw -B verify` and `ng test` were run locally for every change; the counts and coverage
  above come from those reports.
- The before/after index claims come from executing `EXPLAIN ANALYZE` and 200-query loops against
  the real seeded MySQL, with raw stdout committed in `docs/bench/`.
- The concurrency claim comes from the live 50-way harness (10 slots, 500 requests) and the
  in-CI 20-way `ConcurrencyIT`s, not from a mock.
- The 409 conflict banner was exercised in a real browser with two tabs booking the same slot.
- The full Compose stack was built locally and `scripts/smoke.sh` exited 0 against it.

## Repository layout

```
services/provider-service/     Spring Boot, port 8081, Flyway V1..V3
services/appointment-service/  Spring Boot, port 8082, Flyway V1
web/                           Angular 20 standalone SPA + Karma specs + nginx config
db/init/                       MySQL database bootstrap
db/seed/                       deterministic 200-provider / 60k-slot seed + compose seeder
db/benchmark/                  EXPLAIN + 200-query benchmark SQL
scripts/                       seed, benchmark, concurrency harness, compose smoke
docs/                          ARCHITECTURE.md, RESULTS.md, DEPLOY.md, bench/ raw output
docker-compose.yml             mysql + provider + appointment + seed + web (nginx)
.env.example                   required secrets template
.github/workflows/ci.yml       backend, frontend, compose-smoke
```
