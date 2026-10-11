-- Alert channels and the delivery outbox (DESIGN.md §8). Portable types only, as in V1.

CREATE TABLE channels (
    id         VARCHAR(36)  NOT NULL PRIMARY KEY,
    project_id VARCHAR(36)  NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    type       VARCHAR(16)  NOT NULL CHECK (type IN ('EMAIL', 'TELEGRAM', 'SLACK', 'WEBHOOK')),
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
