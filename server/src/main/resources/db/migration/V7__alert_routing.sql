-- Discord and ntfy channels, and the channels each check alerts (DESIGN.md §8). Portable types
-- only, as in V1.

-- SQLite cannot change a CHECK constraint, so channels is rebuilt with the new types, and
-- deliveries with it: dropping channels while deliveries refers to it would delete them. The
-- rows wait in plain copies meanwhile, so every table, index and constraint keeps its name.
CREATE TABLE channels_copy AS SELECT * FROM channels;
CREATE TABLE deliveries_copy AS SELECT * FROM deliveries;
DROP TABLE deliveries;
DROP TABLE channels;

CREATE TABLE channels (
    id         VARCHAR(36)  NOT NULL PRIMARY KEY,
    project_id VARCHAR(36)  NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    type       VARCHAR(16)  NOT NULL
        CHECK (type IN ('EMAIL', 'TELEGRAM', 'SLACK', 'DISCORD', 'NTFY', 'WEBHOOK')),
    name       VARCHAR(200) NOT NULL,
    -- Type-specific settings as a JSON object. Holds secrets (tokens, webhook URLs): never log it.
    config     TEXT         NOT NULL,
    created_at BIGINT       NOT NULL,
    updated_at BIGINT       NOT NULL
);

CREATE INDEX channels_project ON channels (project_id);

-- One row per (alert, channel), written in the transaction that changed the check's status.
CREATE TABLE deliveries (
    id              VARCHAR(36) NOT NULL PRIMARY KEY,
    channel_id      VARCHAR(36) NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    check_id        VARCHAR(36) NOT NULL REFERENCES checks (id) ON DELETE CASCADE,
    event           VARCHAR(8)  NOT NULL CHECK (event IN ('DOWN', 'UP')),
    -- The alert as rendered when it was raised, JSON.
    payload         TEXT        NOT NULL,
    status          VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED')),
    attempts        INTEGER     NOT NULL DEFAULT 0,
    -- When the row is next due. While a sender holds the row, the end of its lease.
    next_attempt_at BIGINT      NOT NULL,
    last_attempt_at BIGINT,
    -- Short reason of the latest failed attempt: status code and message, never a URL or secret.
    last_error      VARCHAR(500),
    created_at      BIGINT      NOT NULL,
    delivered_at    BIGINT
);

CREATE INDEX deliveries_due ON deliveries (status, next_attempt_at);
CREATE INDEX deliveries_channel ON deliveries (channel_id, created_at);
CREATE INDEX deliveries_status_created ON deliveries (status, created_at);

INSERT INTO channels (id, project_id, type, name, config, created_at, updated_at)
SELECT id, project_id, type, name, config, created_at, updated_at FROM channels_copy;

INSERT INTO deliveries (id, channel_id, check_id, event, payload, status, attempts, next_attempt_at,
                        last_attempt_at, last_error, created_at, delivered_at)
SELECT id, channel_id, check_id, event, payload, status, attempts, next_attempt_at, last_attempt_at,
       last_error, created_at, delivered_at
  FROM deliveries_copy;

DROP TABLE deliveries_copy;
DROP TABLE channels_copy;

-- The channels a check picked; a check without rows alerts every channel of its project.
-- Owned by the checks module. Deleting a channel removes it from every selection.
CREATE TABLE check_channels (
    check_id   VARCHAR(36) NOT NULL REFERENCES checks (id) ON DELETE CASCADE,
    channel_id VARCHAR(36) NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    PRIMARY KEY (check_id, channel_id)
);

CREATE INDEX check_channels_channel ON check_channels (channel_id);
