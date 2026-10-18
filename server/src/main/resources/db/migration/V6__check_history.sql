-- Downtime handling and the status history of checks (DESIGN.md §4). Portable types only, as in V1.

-- When the detector last ran, on any instance. After a start, the time since tells how long the
-- server was away, so runs it could not hear do not turn into alerts. One row, NULL until the
-- first run.
CREATE TABLE detector_heartbeat (
    id      INTEGER NOT NULL PRIMARY KEY,
    beat_at BIGINT
);

INSERT INTO detector_heartbeat (id, beat_at) VALUES (1, NULL);

-- Status changes of checks, written in the transaction that changed the status. seq numbers the
-- events of a check in the order they were written (under the check's row lock), so two changes in
-- the same millisecond keep their order; UUIDv7 ids are not ordered within a millisecond.
CREATE TABLE status_events (
    id         VARCHAR(36) NOT NULL PRIMARY KEY,
    check_id   VARCHAR(36) NOT NULL REFERENCES checks (id) ON DELETE CASCADE,
    seq        BIGINT      NOT NULL,
    project_id VARCHAR(36) NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    kind       VARCHAR(8)  NOT NULL CHECK (kind IN ('UP', 'DOWN', 'PAUSED', 'RESUMED')),
    reason     VARCHAR(20) CHECK (reason IN ('MISSED', 'FAILED', 'RUNNING_TOO_LONG')),
    -- The run it is about: the missed due time (MISSED), the start of the run (RUNNING_TOO_LONG).
    run_at     BIGINT,
    at         BIGINT      NOT NULL,
    UNIQUE (check_id, seq)
);

CREATE INDEX status_events_check_at ON status_events (check_id, at, seq);
CREATE INDEX status_events_project_at ON status_events (project_id, at);
CREATE INDEX status_events_at ON status_events (at);

-- The current episode of checks that are DOWN or PAUSED, so their history and uptime do not
-- start as if they were fine. The time is the best one the check row knows.
INSERT INTO status_events (id, check_id, seq, project_id, kind, reason, run_at, at)
SELECT id, id, 1, project_id, status, down_reason,
       CASE down_reason WHEN 'MISSED' THEN next_due_at WHEN 'RUNNING_TOO_LONG' THEN run_started_at END,
       CASE down_reason
           WHEN 'MISSED' THEN next_due_at + grace_seconds * 1000
           WHEN 'RUNNING_TOO_LONG' THEN run_started_at + COALESCE(max_runtime_seconds, grace_seconds) * 1000
           ELSE updated_at
       END
  FROM checks
 WHERE status IN ('DOWN', 'PAUSED');

-- The status event that raised a delivery, so a check's history shows where its alerts went.
ALTER TABLE deliveries ADD COLUMN event_id VARCHAR(36);

CREATE INDEX deliveries_check_created ON deliveries (check_id, created_at);
