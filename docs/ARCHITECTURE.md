# ClinicScheduler Architecture

## Components

```
                    +-----------------------------+
                    |  Angular 20 SPA (web/)      |
                    |  standalone components      |
                    |  reactive forms, Router     |
                    |  JWT HttpInterceptor        |
                    +--------------+--------------+
                                   |
                 HTTPS/JSON (dev proxy, nginx in Docker)
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
      Flyway V1..Vn                                    Flyway V1..Vn
      (same MySQL 8 instance in compose, separate schemas)
```

## Why two services

The split follows the two distinct ownership boundaries in a specialty practice: the
provider domain (who a clinician is, what they treat, where they work, and when they are
free) changes on a slow cadence and is read-heavy, while the appointment domain (who booked
what, cancellations, patient history) is write-heavy, is where the double-booking invariant
must be enforced, and has a very different scaling and failure profile. Keeping availability
search in its own service lets us tune the `availability_slots` indexes and cache the hot
search path without redeploying booking logic; keeping booking in its own service lets the
reserve-then-insert workflow use its own database constraints (`uk_slot_active`,
`uk_patient_start`) as the final authority. The two databases are separate schemas, so the
services can be split onto separate MySQL instances or managed databases later without a
data migration, and each service can be built, tested, and deployed independently. The cost
is a network hop and the two-phase reserve/compensate flow described below, which is an
accepted trade-off documented in the README's limitations.
