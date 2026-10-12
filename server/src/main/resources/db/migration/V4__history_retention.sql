-- History retention (DESIGN.md §9, History). Portable types only, as in V1.

-- The pruner looks for old rows by time across all checks.
CREATE INDEX pings_received ON pings (received_at);
CREATE INDEX deliveries_status_created ON deliveries (status, created_at);

-- Leases for housekeeping jobs that one instance at a time should run. A job runs only after
-- moving locked_until from the past to the future with a conditional update.
CREATE TABLE job_locks (
    name         VARCHAR(64) NOT NULL PRIMARY KEY,
    locked_by    VARCHAR(36),
    locked_until BIGINT      NOT NULL
);

INSERT INTO job_locks (name, locked_until) VALUES ('history-pruning', 0);
