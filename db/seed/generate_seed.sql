-- Deterministic seed: 200 providers across 8 specialties / 5 locations and 60,000
-- OPEN/HELD/BOOKED availability slots spanning the next 90 days.
-- Run through scripts/seed.sh, which applies Flyway migrations first.
-- Safe to re-run: it truncates the two provider tables first.

SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE availability_slots;
TRUNCATE TABLE providers;
SET FOREIGN_KEY_CHECKS = 1;

SET @start_date = DATE_ADD(CURDATE(), INTERVAL 1 DAY);

INSERT INTO providers (full_name, specialty, npi_number, clinic_location, active)
WITH RECURSIVE nums AS (
    SELECT 0 AS n
    UNION ALL
    SELECT n + 1 FROM nums WHERE n < 199
)
SELECT CONCAT('Dr. ',
              ELT((n % 8) + 1, 'Avery', 'Blake', 'Cameron', 'Drew', 'Emerson', 'Finley', 'Gray', 'Harper'),
              ' ',
              ELT((n DIV 8) % 5 + 1, 'Nguyen', 'Patel', 'Okafor', 'Rossi', 'Silva'),
              ' #', n),
       ELT((n % 8) + 1,
           'Cardiology', 'Dermatology', 'Endocrinology', 'Gastroenterology',
           'Neurology', 'Orthopedics', 'Pediatrics', 'Psychiatry'),
       LPAD(1000000000 + n, 10, '0'),
       ELT((n DIV 8) % 5 + 1,
           'Downtown Clinic', 'Northside Medical Center', 'Eastgate Health',
           'Westpark Clinic', 'Lakeview Pavilion'),
       TRUE
FROM nums;

-- 300 slots per provider x 200 providers = 60,000 slots over 90 days.
-- Days 1-90 get 09:00/11:00/14:00; the first 30 days also get 15:30.
INSERT INTO availability_slots (provider_id, start_time, end_time, status)
WITH RECURSIVE nums AS (
    SELECT 0 AS i
    UNION ALL
    SELECT i + 1 FROM nums WHERE i < 299
)
SELECT t.provider_id,
       t.start_time,
       TIMESTAMPADD(MINUTE, 30, t.start_time),
       t.status
FROM (
    SELECT p.id AS provider_id,
           TIMESTAMPADD(MINUTE,
                        CASE WHEN n.i < 270 THEN (n.i MOD 3) * 120 + 540 ELSE 930 END,
                        TIMESTAMPADD(DAY,
                                     CASE WHEN n.i < 270 THEN n.i DIV 3 ELSE n.i - 270 END,
                                     @start_date)) AS start_time,
           CASE
               WHEN n.i % 10 = 0 THEN 'BOOKED'
               WHEN n.i % 17 = 0 THEN 'HELD'
               ELSE 'OPEN'
           END AS status
    FROM providers p
    CROSS JOIN nums n
) t;

SELECT 'providers' AS table_name, COUNT(*) AS row_count FROM providers
UNION ALL
SELECT 'availability_slots', COUNT(*) FROM availability_slots
UNION ALL
SELECT 'open_slots', COUNT(*) FROM availability_slots WHERE status = 'OPEN';
