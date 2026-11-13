-- NOTE: intentionally no secondary indexes on status/start_time/specialty/location yet.
-- Step 5 measures the hot search query here, then V3 adds the indexes.
CREATE TABLE availability_slots (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    provider_id BIGINT      NOT NULL,
    start_time  DATETIME(6) NOT NULL,
    end_time    DATETIME(6) NOT NULL,
    status      ENUM ('OPEN', 'HELD', 'BOOKED') NOT NULL DEFAULT 'OPEN',
    version     BIGINT      NOT NULL DEFAULT 0,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_provider_start (provider_id, start_time),
    CONSTRAINT fk_slot_provider FOREIGN KEY (provider_id) REFERENCES providers (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
