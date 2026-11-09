-- Registry of Patients that MediCore itself registered in the shared HAPI FHIR server (US-04.01).
-- Only the logical FHIR ID, the registering account and the time are stored: no clinical data, no names and
-- no personnummer. Patient search and profile reads are restricted to these Patients (and to Patients linked
-- to a MediCore account), because MediCore cannot otherwise tell its own Patients apart from those of other
-- users of the shared server. If the registering account is deleted, the registry entry stays.
CREATE TABLE registered_patients (
    fhir_patient_id VARCHAR(64) PRIMARY KEY,
    registered_by BIGINT REFERENCES users (id) ON DELETE SET NULL,
    registered_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ck_registered_patients_fhir_id_format CHECK (fhir_patient_id ~ '^[A-Za-z0-9.-]{1,64}$')
);
