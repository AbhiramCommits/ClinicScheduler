-- ClinicScheduler availability-search benchmark.
-- Run against the fully seeded database with NO Step-5 secondary indexes, then again after
-- V3__availability_indexes.sql. Executed by scripts/bench_search.sh.
--
-- Two query shapes are measured because the API supports both:
--   A. filtered by specialty + clinic location (provider-first plan),
--   B. browse-all with no specialty/location filter (slots-first ordered plan, where the
--      composite (status, start_time, provider_id) index wins).
--
-- Each shape gets EXPLAIN FORMAT=JSON, EXPLAIN ANALYZE and 200 randomized timed executions
-- (after 20 unmeasured warmup queries). Latencies are engine-side milliseconds.

SELECT '=== shape A: filtered by specialty + location ===' AS section;

EXPLAIN FORMAT = JSON
SELECT s.id, s.provider_id, s.start_time
FROM availability_slots s
         JOIN providers p ON p.id = s.provider_id
WHERE p.specialty = 'Cardiology'
  AND p.clinic_location = 'Downtown Clinic'
  AND s.status = 'OPEN'
  AND s.start_time >= DATE_ADD(CURDATE(), INTERVAL 1 DAY)
  AND s.start_time < DATE_ADD(CURDATE(), INTERVAL 31 DAY)
ORDER BY s.start_time, s.provider_id
LIMIT 20;

EXPLAIN ANALYZE
SELECT s.id, s.provider_id, s.start_time
FROM availability_slots s
         JOIN providers p ON p.id = s.provider_id
WHERE p.specialty = 'Cardiology'
  AND p.clinic_location = 'Downtown Clinic'
  AND s.status = 'OPEN'
  AND s.start_time >= DATE_ADD(CURDATE(), INTERVAL 1 DAY)
  AND s.start_time < DATE_ADD(CURDATE(), INTERVAL 31 DAY)
ORDER BY s.start_time, s.provider_id
LIMIT 20;

SELECT '=== shape B: browse all open slots (no specialty/location filter) ===' AS section;

EXPLAIN FORMAT = JSON
SELECT s.id, s.provider_id, s.start_time
FROM availability_slots s
         JOIN providers p ON p.id = s.provider_id
WHERE s.status = 'OPEN'
  AND s.start_time >= DATE_ADD(CURDATE(), INTERVAL 1 DAY)
  AND s.start_time < DATE_ADD(CURDATE(), INTERVAL 31 DAY)
ORDER BY s.start_time, s.provider_id
LIMIT 20;

EXPLAIN ANALYZE
SELECT s.id, s.provider_id, s.start_time
FROM availability_slots s
         JOIN providers p ON p.id = s.provider_id
WHERE s.status = 'OPEN'
  AND s.start_time >= DATE_ADD(CURDATE(), INTERVAL 1 DAY)
  AND s.start_time < DATE_ADD(CURDATE(), INTERVAL 31 DAY)
ORDER BY s.start_time, s.provider_id
LIMIT 20;

DROP PROCEDURE IF EXISTS run_search_benchmark;

DELIMITER //
CREATE PROCEDURE run_search_benchmark(IN iterations INT, IN filtered INT)
BEGIN
    DECLARE i INT DEFAULT 0;
    DECLARE started DATETIME(6);
    DECLARE spec VARCHAR(80);
    DECLARE loc VARCHAR(120);
    DECLARE from_dt DATETIME(6);
    DECLARE to_dt DATETIME(6);

    DROP TEMPORARY TABLE IF EXISTS bench_times;
    CREATE TEMPORARY TABLE bench_times
    (
        iteration INT PRIMARY KEY,
        ms        DECIMAL(12, 3) NOT NULL
    );

    -- Warm the buffer pool and cached plans with 20 unmeasured executions.
    WHILE i < 20
        DO
            SET spec = ELT(1 + FLOOR(RAND() * 8),
                           'Cardiology', 'Dermatology', 'Endocrinology', 'Gastroenterology',
                           'Neurology', 'Orthopedics', 'Pediatrics', 'Psychiatry');
            SET loc = ELT(1 + FLOOR(RAND() * 5),
                          'Downtown Clinic', 'Northside Medical Center', 'Eastgate Health',
                          'Westpark Clinic', 'Lakeview Pavilion');
            SET from_dt = TIMESTAMPADD(DAY, 1 + FLOOR(RAND() * 60), CURDATE());
            SET to_dt = TIMESTAMPADD(DAY, 7, from_dt);

            IF filtered = 1 THEN
                SELECT COUNT(*)
                INTO @matched
                FROM (SELECT s.id
                      FROM availability_slots s
                               JOIN providers p ON p.id = s.provider_id
                      WHERE p.specialty = spec
                        AND p.clinic_location = loc
                        AND s.status = 'OPEN'
                        AND s.start_time >= from_dt
                        AND s.start_time < to_dt
                      ORDER BY s.start_time, s.provider_id
                      LIMIT 20) page;
            ELSE
                SELECT COUNT(*)
                INTO @matched
                FROM (SELECT s.id
                      FROM availability_slots s
                               JOIN providers p ON p.id = s.provider_id
                      WHERE s.status = 'OPEN'
                        AND s.start_time >= from_dt
                        AND s.start_time < to_dt
                      ORDER BY s.start_time, s.provider_id
                      LIMIT 20) page;
            END IF;

            SET i = i + 1;
        END WHILE;
    SET i = 0;

    WHILE i < iterations
        DO
            SET spec = ELT(1 + FLOOR(RAND() * 8),
                           'Cardiology', 'Dermatology', 'Endocrinology', 'Gastroenterology',
                           'Neurology', 'Orthopedics', 'Pediatrics', 'Psychiatry');
            SET loc = ELT(1 + FLOOR(RAND() * 5),
                          'Downtown Clinic', 'Northside Medical Center', 'Eastgate Health',
                          'Westpark Clinic', 'Lakeview Pavilion');
            SET from_dt = TIMESTAMPADD(DAY, 1 + FLOOR(RAND() * 60), CURDATE());
            SET to_dt = TIMESTAMPADD(DAY, 7, from_dt);

            SET started = SYSDATE(6);

            IF filtered = 1 THEN
                SELECT COUNT(*)
                INTO @matched
                FROM (SELECT s.id
                      FROM availability_slots s
                               JOIN providers p ON p.id = s.provider_id
                      WHERE p.specialty = spec
                        AND p.clinic_location = loc
                        AND s.status = 'OPEN'
                        AND s.start_time >= from_dt
                        AND s.start_time < to_dt
                      ORDER BY s.start_time, s.provider_id
                      LIMIT 20) page;
            ELSE
                SELECT COUNT(*)
                INTO @matched
                FROM (SELECT s.id
                      FROM availability_slots s
                               JOIN providers p ON p.id = s.provider_id
                      WHERE s.status = 'OPEN'
                        AND s.start_time >= from_dt
                        AND s.start_time < to_dt
                      ORDER BY s.start_time, s.provider_id
                      LIMIT 20) page;
            END IF;

            INSERT INTO bench_times (iteration, ms)
            VALUES (i, TIMESTAMPDIFF(MICROSECOND, started, SYSDATE(6)) / 1000.0);

            SET i = i + 1;
        END WHILE;

    SELECT CONCAT('randomized searches (',
                  IF(filtered = 1, 'filtered specialty+location', 'browse all'),
                  '): ', iterations, ' after 20 unmeasured warmup queries') AS benchmark;

    WITH ranked AS (SELECT ms,
                           ROW_NUMBER() OVER (ORDER BY ms) AS rn,
                           COUNT(*) OVER ()                AS n
                    FROM bench_times)
    SELECT ROUND(MIN(ms), 3)                                            AS min_ms,
           ROUND(AVG(ms), 3)                                            AS avg_ms,
           ROUND(MAX(CASE WHEN rn = CEIL(n * 0.5) THEN ms END), 3)      AS p50_ms,
           ROUND(MAX(CASE WHEN rn = CEIL(n * 0.95) THEN ms END), 3)     AS p95_ms,
           ROUND(MAX(ms), 3)                                            AS max_ms,
           MAX(n)                                                       AS iterations
    FROM ranked;

    DROP TEMPORARY TABLE IF EXISTS bench_times;
END//
DELIMITER ;

CALL run_search_benchmark(200, 1);
CALL run_search_benchmark(200, 0);
DROP PROCEDURE IF EXISTS run_search_benchmark;
