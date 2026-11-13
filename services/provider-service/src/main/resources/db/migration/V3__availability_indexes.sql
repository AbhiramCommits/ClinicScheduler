-- Step 5: the indexes chosen from the measured plans.
-- The hot availability search filters status + a start_time range, orders by start_time, and
-- joins each row to its provider, so this composite index serves the range, the ordering and
-- the join key without a filesort. Providers are filtered by specialty + clinic location.
CREATE INDEX idx_slots_status_start_provider
    ON availability_slots (status, start_time, provider_id);

CREATE INDEX idx_providers_specialty_location
    ON providers (specialty, clinic_location);
