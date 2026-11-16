-- Journal access requests are created only when a PATIENT explicitly asks for access. A request can exist before
-- MediCore has registered the matching FHIR Patient: it then waits (AWAITING_RECORD, no FHIR Patient ID) until a
-- Patient with the account's personnummer is registered and is attached to it (PENDING). Existing rows, including
-- requests created automatically before this change, are kept unchanged as history.

ALTER TABLE journal_access_requests ALTER COLUMN fhir_patient_id DROP NOT NULL;

ALTER TABLE journal_access_requests DROP CONSTRAINT ck_journal_access_requests_status;
ALTER TABLE journal_access_requests ADD CONSTRAINT ck_journal_access_requests_status
    CHECK (status IN ('AWAITING_RECORD', 'PENDING', 'APPROVED', 'REJECTED'));

-- Open requests (awaiting a record or a doctor) are never reviewed; decided ones always have a review time.
ALTER TABLE journal_access_requests DROP CONSTRAINT ck_journal_access_requests_review;
ALTER TABLE journal_access_requests ADD CONSTRAINT ck_journal_access_requests_review
    CHECK ((status IN ('AWAITING_RECORD', 'PENDING') AND reviewed_at IS NULL AND reviewed_by IS NULL)
        OR (status IN ('APPROVED', 'REJECTED') AND reviewed_at IS NOT NULL));

-- Only a request awaiting its record has no FHIR Patient; PENDING, APPROVED and REJECTED always reference one.
ALTER TABLE journal_access_requests ADD CONSTRAINT ck_journal_access_requests_record
    CHECK ((status = 'AWAITING_RECORD' AND fhir_patient_id IS NULL)
        OR (status <> 'AWAITING_RECORD' AND fhir_patient_id IS NOT NULL));

-- At most one open request per account, whatever its FHIR Patient. (A unique index on (user_id, fhir_patient_id)
-- would not stop duplicates while fhir_patient_id is NULL, because NULLs are distinct in PostgreSQL.) This also
-- serializes concurrent requests of the same account. Fails loudly instead of changing history if old data has
-- several pending requests for one account.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM journal_access_requests WHERE status = 'PENDING'
               GROUP BY user_id HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'V8: an account has more than one PENDING journal access request; decide all but one first';
    END IF;
END
$$;

DROP INDEX uq_journal_access_requests_pending;
CREATE UNIQUE INDEX uq_journal_access_requests_open
    ON journal_access_requests (user_id) WHERE status IN ('AWAITING_RECORD', 'PENDING');
