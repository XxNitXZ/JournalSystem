-- One-off copy of the Lab 1 monolith's data into the per-service schemas (NIT-324).
--
-- Copies every row, keeping its id, from public.{users, journal_access_requests, registered_patients, messages}
-- into identity.users, identity.journal_access_requests, clinical.registered_patients and messaging.messages. The
-- Lab 1 tables in `public` are only read, never changed or dropped.
--
-- When to run it: once, at the EPIC-02 cut-over, after
--   1. init-schemas.sql has created the schemas and users, and
--   2. each new service has started once, so its Flyway created its tables (and its flyway_schema_history), and
--   3. the Lab 1 monolith has been stopped, so no new rows are written to `public` during or after the copy.
--
-- Run it as the database owner, who can read `public` and write every service schema:
--
--   docker exec -i medinexus-postgres psql -U medinexus -d medinexus_db -v ON_ERROR_STOP=1 \
--     < infrastructure/database/migrate-lab1-data.sql
--
-- Everything runs in one transaction. It stops without changing anything if a table is missing, if a target table
-- already has rows (so it can't run twice), or if the row counts differ after the copy.

\set ON_ERROR_STOP on

BEGIN;

DO $$
DECLARE
    target text;
    has_rows boolean;
BEGIN
    FOREACH target IN ARRAY ARRAY['public.users', 'public.journal_access_requests', 'public.registered_patients',
            'public.messages', 'identity.users', 'identity.journal_access_requests', 'clinical.registered_patients',
            'messaging.messages'] LOOP
        IF to_regclass(target) IS NULL THEN
            RAISE EXCEPTION 'Table % does not exist. Run init-schemas.sql and start each service once first.', target;
        END IF;
    END LOOP;

    FOREACH target IN ARRAY ARRAY['identity.users', 'identity.journal_access_requests',
            'clinical.registered_patients', 'messaging.messages'] LOOP
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM %s)', target) INTO has_rows;
        IF has_rows THEN
            RAISE EXCEPTION '% already has rows; the copy has already run. Nothing was changed.', target;
        END IF;
    END LOOP;
END
$$;

-- Identity: accounts first, because journal access requests reference them.
INSERT INTO identity.users (id, email, password_hash, role, fhir_patient_id, fhir_practitioner_id,
        given_name, family_name, personnummer, phone_number)
    SELECT id, email, password_hash, role, fhir_patient_id, fhir_practitioner_id,
           given_name, family_name, personnummer, phone_number
    FROM public.users;

INSERT INTO identity.journal_access_requests (id, user_id, fhir_patient_id, status, reviewed_by, created_at,
        reviewed_at)
    SELECT id, user_id, fhir_patient_id, status, reviewed_by, created_at, reviewed_at
    FROM public.journal_access_requests;

-- Clinical: the patient registry.
INSERT INTO clinical.registered_patients (fhir_patient_id, registered_by, registered_at)
    SELECT fhir_patient_id, registered_by, registered_at
    FROM public.registered_patients;

-- Linked patients become registered too, so Clinical's "known patient" check no longer needs Identity's users
-- table (docs/architecture.md, D5). registered_by stays NULL: the link was made by the operator command.
INSERT INTO clinical.registered_patients (fhir_patient_id, registered_by, registered_at)
    SELECT u.fhir_patient_id, NULL, now()
    FROM public.users u
    WHERE u.fhir_patient_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM public.registered_patients r WHERE r.fhir_patient_id = u.fhir_patient_id);

-- Messaging.
INSERT INTO messaging.messages (id, sender_id, recipient_id, content, created_at)
    SELECT id, sender_id, recipient_id, content, created_at
    FROM public.messages;

-- Ids were copied explicitly, so move each identity sequence past the highest copied id. Otherwise the next insert
-- would reuse id 1.
SELECT setval(pg_get_serial_sequence('identity.users', 'id'),
              COALESCE((SELECT max(id) FROM identity.users), 1), (SELECT max(id) IS NOT NULL FROM identity.users));
SELECT setval(pg_get_serial_sequence('identity.journal_access_requests', 'id'),
              COALESCE((SELECT max(id) FROM identity.journal_access_requests), 1),
              (SELECT max(id) IS NOT NULL FROM identity.journal_access_requests));
SELECT setval(pg_get_serial_sequence('messaging.messages', 'id'),
              COALESCE((SELECT max(id) FROM messaging.messages), 1),
              (SELECT max(id) IS NOT NULL FROM messaging.messages));

-- Verify before committing: every source row arrived.
DO $$
DECLARE
    expected_registered bigint;
BEGIN
    IF (SELECT count(*) FROM identity.users) <> (SELECT count(*) FROM public.users) THEN
        RAISE EXCEPTION 'users: row counts differ';
    END IF;
    IF (SELECT count(*) FROM identity.journal_access_requests) <> (SELECT count(*) FROM public.journal_access_requests) THEN
        RAISE EXCEPTION 'journal_access_requests: row counts differ';
    END IF;
    IF (SELECT count(*) FROM messaging.messages) <> (SELECT count(*) FROM public.messages) THEN
        RAISE EXCEPTION 'messages: row counts differ';
    END IF;

    SELECT count(*) INTO expected_registered FROM (
        SELECT fhir_patient_id FROM public.registered_patients
        UNION
        SELECT fhir_patient_id FROM public.users WHERE fhir_patient_id IS NOT NULL) AS known;
    IF (SELECT count(*) FROM clinical.registered_patients) <> expected_registered THEN
        RAISE EXCEPTION 'registered_patients: row counts differ';
    END IF;

    RAISE NOTICE 'Copied % users, % journal access requests, % registered patients, % messages.',
        (SELECT count(*) FROM identity.users), (SELECT count(*) FROM identity.journal_access_requests),
        (SELECT count(*) FROM clinical.registered_patients), (SELECT count(*) FROM messaging.messages);
END
$$;

COMMIT;
