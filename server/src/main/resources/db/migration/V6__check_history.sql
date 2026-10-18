-- Downtime handling and the status history of checks (DESIGN.md §4). Portable types only, as in V1.

-- When the detector last ran, on any instance. After a start, the time since tells how long the
-- server was away, so runs it could not hear do not turn into alerts. One row, NULL until the
-- first run.
CREATE TABLE detector_heartbeat (
    id      INTEGER NOT NULL PRIMARY KEY,
    beat_at BIGINT
);

INSERT INTO detector_heartbeat (id, beat_at) VALUES (1, NULL);
