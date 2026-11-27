-- Swedish personnummer of the account holder, normalized to 12 digits (YYYYMMDDNNNC). Required for every new
-- registration (all roles); existing accounts keep NULL and stay functional. One account per personnummer.
-- Used to match PATIENT accounts with Patients that MediCore registered in FHIR (journal access requests).
ALTER TABLE users ADD COLUMN personnummer VARCHAR(12);

ALTER TABLE users
    ADD CONSTRAINT uq_users_personnummer UNIQUE (personnummer),
    ADD CONSTRAINT ck_users_personnummer_format CHECK (personnummer IS NULL OR personnummer ~ '^[0-9]{12}$');
