-- Instance admin, session versions and invites (DESIGN.md §6, Projects and invites).
-- Portable types only, as in V1.

-- 1 for the instance admin: the account made by the setup.
ALTER TABLE accounts ADD COLUMN instance_admin INTEGER NOT NULL DEFAULT 0;

-- Raised when the password changes; sessions made with an older version are signed out.
ALTER TABLE accounts ADD COLUMN session_version INTEGER NOT NULL DEFAULT 0;

UPDATE accounts SET instance_admin = 1 WHERE setup_slot = 1;

CREATE TABLE invites (
    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    -- SHA-256 of the token, hex. The token itself is never stored.
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    -- PROJECT: the invitee gets a new project of their own. MEMBER: joins project_id.
    kind        VARCHAR(8)   NOT NULL CHECK (kind IN ('PROJECT', 'MEMBER')),
    -- The project joined (MEMBER), or the project created on acceptance (PROJECT).
    project_id  VARCHAR(36)  REFERENCES projects (id) ON DELETE CASCADE,
    note        VARCHAR(200),
    created_by  VARCHAR(36)  NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    created_at  BIGINT       NOT NULL,
    expires_at  BIGINT       NOT NULL,
    accepted_by VARCHAR(36)  REFERENCES accounts (id) ON DELETE SET NULL,
    accepted_at BIGINT,
    CONSTRAINT invites_member_project CHECK (kind = 'PROJECT' OR project_id IS NOT NULL)
);

CREATE INDEX invites_project ON invites (project_id, created_at);
CREATE INDEX invites_kind ON invites (kind, created_at);
