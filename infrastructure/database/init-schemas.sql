-- MediCore Lab 2: one schema and one database user per service (NIT-324).
--
-- Each microservice connects as its own user and owns exactly one schema. A user can create and change tables
-- in its own schema only. It has no rights on another service's schema or on the Lab 1 tables in `public`, so a
-- query like `SELECT * FROM identity.users` from the messaging user fails with "permission denied".
-- See docs/architecture.md (§3 and §7).
--
-- Run once per database as the database owner (`medinexus`), with one password per service user:
--
--   docker exec -i -e PGPASSWORD="$DB_PASSWORD" medinexus-postgres psql -U medinexus -d medinexus_db -v ON_ERROR_STOP=1 \
--     -v identity_password='…' -v clinical_password='…' -v messaging_password='…' \
--     < infrastructure/database/init-schemas.sql
--
-- Safe to run again: existing users only get their password updated, existing schemas are kept, and nothing is
-- dropped. The `images` schema for the Node.js service is added in EPIC-03.

\set ON_ERROR_STOP on

-- psql only substitutes :'var' outside dollar-quoted blocks, so the passwords are handed to the DO block below
-- through session settings.
SELECT set_config('medicore.identity_password', :'identity_password', false),
       set_config('medicore.clinical_password', :'clinical_password', false),
       set_config('medicore.messaging_password', :'messaging_password', false);

DO $$
DECLARE
    service text;
    password text;
BEGIN
    FOREACH service IN ARRAY ARRAY['identity', 'clinical', 'messaging'] LOOP
        password := current_setting('medicore.' || service || '_password');
        IF password IS NULL OR btrim(password) = '' THEN
            RAISE EXCEPTION 'Missing password for %_svc: pass -v %_password=…', service, service;
        END IF;

        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = service || '_svc') THEN
            EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L', service || '_svc', password);
        ELSE
            EXECUTE format('ALTER ROLE %I LOGIN PASSWORD %L', service || '_svc', password);
        END IF;

        -- The service user owns its schema, so its own Flyway can create tables there.
        EXECUTE format('CREATE SCHEMA IF NOT EXISTS %I AUTHORIZATION %I', service, service || '_svc');
        EXECUTE format('ALTER SCHEMA %I OWNER TO %I', service, service || '_svc');

        -- Unqualified table names resolve to the service's own schema.
        EXECUTE format('ALTER ROLE %I SET search_path = %I', service || '_svc', service);

        -- A service may connect to the database. It has no CREATE right on the database, so it can't add schemas.
        EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), service || '_svc');
    END LOOP;
END
$$;

-- Nobody but the owner may create objects in `public`, which holds the Lab 1 monolith's tables. PostgreSQL 15+
-- already defaults to this; it's stated explicitly here. The service users get no grants on `public` tables.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- Clear the passwords from the session.
SELECT set_config('medicore.identity_password', '', false),
       set_config('medicore.clinical_password', '', false),
       set_config('medicore.messaging_password', '', false);
