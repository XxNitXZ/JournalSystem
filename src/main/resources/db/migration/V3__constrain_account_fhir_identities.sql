-- One fixed KTH FHIR server per database. Existing references must never be
-- silently interpreted against another server when FHIR_BASE_URL changes.
-- Validate existing data; do not repair, clear or reassign any references.
ALTER TABLE users ADD COLUMN fhir_practitioner_id VARCHAR(255);

ALTER TABLE users
    ADD CONSTRAINT uq_users_fhir_patient_id UNIQUE (fhir_patient_id),
    ADD CONSTRAINT uq_users_fhir_practitioner_id UNIQUE (fhir_practitioner_id),
    ADD CONSTRAINT ck_users_fhir_patient_id_format
        CHECK (fhir_patient_id IS NULL OR fhir_patient_id ~ '^[A-Za-z0-9.-]{1,64}$'),
    ADD CONSTRAINT ck_users_fhir_practitioner_id_format
        CHECK (fhir_practitioner_id IS NULL OR fhir_practitioner_id ~ '^[A-Za-z0-9.-]{1,64}$'),
    ADD CONSTRAINT ck_users_fhir_patient_role
        CHECK (fhir_patient_id IS NULL OR role = 'PATIENT'),
    ADD CONSTRAINT ck_users_fhir_practitioner_role
        CHECK (fhir_practitioner_id IS NULL OR role IN ('DOCTOR', 'STAFF'));
