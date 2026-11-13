CREATE TABLE appointments (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    patient_id   VARCHAR(64)  NOT NULL,
    patient_name VARCHAR(120) NOT NULL,
    provider_id  BIGINT       NOT NULL,
    slot_id      BIGINT       NOT NULL,
    start_time   DATETIME(6)  NOT NULL,
    end_time     DATETIME(6)  NOT NULL,
    status       ENUM ('BOOKED', 'CANCELLED', 'COMPLETED') NOT NULL DEFAULT 'BOOKED',
    version      BIGINT       NOT NULL DEFAULT 0,
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_slot_active (slot_id),
    UNIQUE KEY uk_patient_start (patient_id, start_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
