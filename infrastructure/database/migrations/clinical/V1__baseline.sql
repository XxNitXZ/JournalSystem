-- Clinical service baseline (NIT-324). Runs in schema `clinical` as user `clinical_svc`.
--
-- Same table as the Lab 1 monolith's V5. One difference: registered_by no longer references users, because
-- accounts now belong to the Identity service in another schema. It holds the registering account's id as a plain
-- value.
--
-- Registry of the Patients that belong to MediCore on the shared HAPI FHIR server: those MediCore registered or
-- imported, plus those linked to an account (Identity registers them here when it links them; docs/architecture.md,
-- D5 and D6). Only logical ids are stored: no clinical data, no names and no personnummer.
--
-- Staged here until NIT-788 moves it to services/clinical/src/main/resources/db/migration/.

CREATE TABLE registered_patients (
    fhir_patient_id VARCHAR(64) PRIMARY KEY,
    -- Account id (identity.users.id) of whoever registered or imported the Patient; NULL if unknown.
    registered_by BIGINT,
    registered_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ck_registered_patients_fhir_id_format CHECK (fhir_patient_id ~ '^[A-Za-z0-9.-]{1,64}$')
);
