# Measured Results

Every number in this document comes from the captured output in `docs/bench/`:

| File | Produced by |
| --- | --- |
| `docs/bench/before.txt` | `./scripts/bench_search.sh` before `V3__availability_indexes.sql` |
| `docs/bench/after.txt` | the same command after `V3__availability_indexes.sql` |
| `docs/bench/concurrency.txt` | `./scripts/concurrency_test.sh` against both running services + MySQL |

The database contained **200 providers**, **60,000 availability slots**, **50,800 OPEN slots**,
8 specialties, 5 clinic locations and a 90-day slot span (counts printed in both benchmark files).

## 1. Availability-search benchmark

Method: MySQL 8.0.46, fully seeded `provider_db`, engine-side timing inside a stored procedure.
Each shape runs **200 randomized queries after 20 unmeasured warmup queries**; latency is
milliseconds measured with `SYSDATE(6)` around the real query shape (same predicates,
`ORDER BY start_time, provider_id`, `LIMIT 20`). "Rows examined" is the sum of
`actual rows × loops` over every table-access node in `EXPLAIN ANALYZE`, printed by
`scripts/bench_search.sh` as `actual_rows_examined_total`.

Two shapes are measured because the API supports both:

- **Shape A (filtered):** `specialty=Cardiology`, `location=Downtown Clinic`, 30-day window.
- **Shape B (browse all):** no specialty/location filter, 30-day window — this is what the SPA
  requests when the user leaves the filters at "Any".

### Before / after

| Query shape | p50 before | p50 after | p95 before | p95 after | max before | max after | Rows examined before | Rows examined after | Winning index |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| A · filtered specialty + location | 0.433 ms | 0.325 ms | 0.718 ms | 0.446 ms | 1.602 ms | 1.354 ms | 800 | 605 | `providers (specialty, clinic_location)` |
| B · browse all | 14.614 ms | 0.084 ms | 17.993 ms | 0.118 ms | 28.060 ms | 0.357 ms | 60,020 | 40 | `availability_slots (status, start_time, provider_id)` |

Derived changes:

| Query shape | p50 improvement | p95 improvement | Rows examined reduction |
| --- | ---: | ---: | ---: |
| A · filtered | 24.9% faster | 37.9% faster | 24.4% fewer (800 → 605) |
| B · browse all | 99.4% faster (174×) | 99.3% faster (152×) | 99.93% fewer (60,020 → 40) |

### Winning plan excerpts (verbatim from `docs/bench/after.txt`)

Shape B, after indexing — ordered range scan instead of a full table scan, and the filesort is gone:

```
-> Covering index range scan on s using idx_slots_status_start_provider over
   (status = 'OPEN' AND '2026-11-14 00:00:00.000000' <= start_time < '2026-12-14 00:00:00.000000')
   (cost=6096 rows=30016) (actual time=0.408..0.411 rows=20 loops=1)
```

Shape B, before indexing — the same query scanned all 60,000 rows and sorted them:

```
-> Sort: s.start_time, s.provider_id  (cost=4297 rows=60032) (actual ... rows=20 loops=1)
    -> Table scan on s  (cost=4297 rows=60032) (actual time=0.459..10.5 rows=60000 loops=1)
```

Shape A, before indexing — providers were filtered by a full scan:

```
-> Table scan on p  (cost=20.8 rows=200) (actual time=0.0416..0.0928 rows=200 loops=1)
-> Index lookup on s using uk_provider_start (provider_id=p.id), ...
```

Shape A, after indexing — the provider index returns only the 5 matching providers:

```
-> Covering index lookup on p using idx_providers_specialty_location
   (specialty='Cardiology', clinic_location='Downtown Clinic')
   (cost=0.841 rows=5) (actual time=0.0165..0.0187 rows=5 loops=1)
```

EXPLAIN JSON confirms the access-type change in `docs/bench/after.txt`:
shape B is `"access_type": "range"` with `"key": "idx_slots_status_start_provider"`; shape A is
`"access_type": "ref"` with `"key": "idx_providers_specialty_location"` (before: `"ALL"` / `"key":
null` for providers).

### Indexes applied (V3)

```sql
CREATE INDEX idx_slots_status_start_provider
    ON availability_slots (status, start_time, provider_id);
CREATE INDEX idx_providers_specialty_location
    ON providers (specialty, clinic_location);
```

The first version of the slots index did not change the filtered plan because MySQL correctly
prefers to filter the 5 matching providers first and probe `(provider_id, start_time)` for each.
The browse-all shape is where the composite index is the winning plan. Both plans are reported
above with their own honest numbers rather than forcing one index onto the other shape.

## 2. Double-booking concurrency harness

`scripts/concurrency_test.sh` logs in as staff, takes 10 distinct OPEN slots, and fires **50
simultaneous `POST /api/appointments` requests** per slot with a fixed thread pool and a
`CountDownLatch`, against the real running appointment-service, provider-service and MySQL.
Each request uses a unique patient and the same slot/time.

| Slot | Provider | Start | Success | 409 conflicts | Other errors | Appointment rows |
| ---: | ---: | --- | ---: | ---: | ---: | ---: |
| 2099 | 7 | 2026-11-14T11:00 | 1 | 49 | 0 | 1 |
| 2399 | 8 | 2026-11-14T11:00 | 1 | 49 | 0 | 1 |
| 2699 | 9 | 2026-11-14T11:00 | 1 | 49 | 0 | 1 |
| 2999 | 10 | 2026-11-14T11:00 | 1 | 49 | 0 | 1 |
| 3299 | 11 | 2026-11-14T11:00 | 1 | 49 | 0 | 1 |
| 3599 | 12 | 2026-11-14T11:00 | 1 | 49 | 0 | 1 |
| 3899 | 13 | 2026-11-14T11:00 | 1 | 49 | 0 | 1 |
| 4199 | 14 | 2026-11-14T11:00 | 1 | 49 | 0 | 1 |
| 4499 | 15 | 2026-11-14T11:00 | 1 | 49 | 0 | 1 |
| 4799 | 16 | 2026-11-14T11:00 | 1 | 49 | 0 | 1 |

Aggregate printed by the harness (verbatim from `docs/bench/concurrency.txt`):

```
successful bookings : 10 (expected 10)
409 conflicts       : 490 (expected 490)
other errors        : 0 (expected 0)
appointment rows    : 10 (expected 10)
total wall time     : 11963ms
invariant           : PASS - exactly one winner per slot
```

This is the end-to-end proof: provider-service's `@Version` optimistic lock admits one reserve,
and appointment-service's `uk_slot_active` unique key makes the database the final authority
(50 requests, 1 row).

## 3. Test counts

Measured from the reports in each service's `target/failsafe-reports/` and the Karma run:

| Suite | Tests | Result |
| --- | ---: | --- |
| provider-service Testcontainers `AvailabilitySearchIT` | 7 | 7 passed |
| appointment-service Testcontainers `BookingIT` | 8 | 8 passed |
| Angular/Karma/Jasmine specs | 19 | 19 passed |

These counts grow in Step 6 when unit, MockMvc and concurrency integration tests are added; the
final totals are recorded in the README.
