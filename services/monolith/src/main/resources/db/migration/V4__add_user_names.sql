-- Display name of the account holder (first and last name), shown in MediPortal.
-- This is account data, not clinical data: it is not synchronized with FHIR Patient or Practitioner names.
-- Existing accounts keep NULL; no existing row is changed. New registrations always set both names.
ALTER TABLE users
    ADD COLUMN given_name VARCHAR(100),
    ADD COLUMN family_name VARCHAR(100);

ALTER TABLE users
    ADD CONSTRAINT ck_users_names_complete
        CHECK ((given_name IS NULL) = (family_name IS NULL)),
    ADD CONSTRAINT ck_users_given_name_not_blank
        CHECK (given_name IS NULL OR btrim(given_name) <> ''),
    ADD CONSTRAINT ck_users_family_name_not_blank
        CHECK (family_name IS NULL OR btrim(family_name) <> '');
