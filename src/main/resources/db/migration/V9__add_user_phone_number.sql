-- Optional telephone number of the account holder, the only phone number MediCore keeps (never written to FHIR).
-- Stored normalized as text: an optional leading '+' and 6-15 digits (E.164 allows at most 15), without spaces or
-- separators. Not unique: several accounts may share a number. Existing accounts keep NULL.
ALTER TABLE users ADD COLUMN phone_number VARCHAR(16);
ALTER TABLE users ADD CONSTRAINT ck_users_phone_number_format
    CHECK (phone_number IS NULL OR phone_number ~ '^\+?[0-9]{6,15}$');
