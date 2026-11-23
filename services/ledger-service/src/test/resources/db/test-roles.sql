-- Runs once when the test Postgres container starts (as the container's superuser).
-- Tests use the same least-privileged login as production, so a missing GRANT in a migration
-- fails the tests instead of failing in Docker Compose.
CREATE ROLE ledger_app LOGIN PASSWORD 'ledger-app-test';
