-- Runs unchanged on SQLite and PostgreSQL: keep it to portable types.
-- Ids are UUIDv7 strings made by the application. Times are epoch milliseconds (UTC).

CREATE TABLE projects (
    id         VARCHAR(36)  NOT NULL PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    created_at BIGINT       NOT NULL
);

CREATE TABLE api_keys (
    id           VARCHAR(36)  NOT NULL PRIMARY KEY,
    project_id   VARCHAR(36)  NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    name         VARCHAR(200) NOT NULL,
    -- SHA-256 of the key, hex. The key itself is never stored.
    key_hash     VARCHAR(64)  NOT NULL UNIQUE,
    -- First characters of the key, to tell keys apart in a list.
    key_prefix   VARCHAR(16)  NOT NULL,
    created_at   BIGINT       NOT NULL
);

CREATE INDEX api_keys_project ON api_keys (project_id);

CREATE TABLE checks (
    id                  VARCHAR(36)  NOT NULL PRIMARY KEY,
    ping_key            VARCHAR(36)  NOT NULL UNIQUE,
    project_id          VARCHAR(36)  NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    slug                VARCHAR(128) NOT NULL,
    name                VARCHAR(200) NOT NULL,
    schedule_kind       VARCHAR(8)   NOT NULL CHECK (schedule_kind IN ('CRON', 'PERIOD')),
    cron_expression     VARCHAR(200),
    time_zone           VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    period_seconds      BIGINT,
    grace_seconds       BIGINT       NOT NULL,
    max_runtime_seconds BIGINT,
    status              VARCHAR(8)   NOT NULL CHECK (status IN ('NEW', 'UP', 'DOWN', 'PAUSED')),
    down_reason         VARCHAR(20)  CHECK (down_reason IN ('MISSED', 'FAILED', 'RUNNING_TOO_LONG')),
    last_ping_at        BIGINT,
    last_start_at       BIGINT,
    last_success_at     BIGINT,
    next_due_at         BIGINT,
    -- Open run: start time and rid of the latest start not yet followed by success or fail.
    run_started_at      BIGINT,
    run_rid             VARCHAR(64),
    created_at          BIGINT       NOT NULL,
    updated_at          BIGINT       NOT NULL,
    CONSTRAINT checks_project_slug UNIQUE (project_id, slug),
    CONSTRAINT checks_schedule CHECK (
        (schedule_kind = 'CRON' AND cron_expression IS NOT NULL AND period_seconds IS NULL)
        OR (schedule_kind = 'PERIOD' AND period_seconds IS NOT NULL AND cron_expression IS NULL))
);

CREATE INDEX checks_status_due ON checks (status, next_due_at);

CREATE TABLE pings (
    id          VARCHAR(36) NOT NULL PRIMARY KEY,
    check_id    VARCHAR(36) NOT NULL REFERENCES checks (id) ON DELETE CASCADE,
    kind        VARCHAR(8)  NOT NULL CHECK (kind IN ('START', 'SUCCESS', 'FAIL', 'LOG')),
    exit_code   INTEGER,
    rid         VARCHAR(64),
    -- Request body, truncated to 10 KB.
    body        TEXT,
    received_at BIGINT      NOT NULL,
    remote_addr VARCHAR(64),
    user_agent  VARCHAR(256)
);

CREATE INDEX pings_check_received ON pings (check_id, received_at);
