-- Accounts and project memberships (DESIGN.md §6). Portable types only, as in V1.

CREATE TABLE accounts (
    id            VARCHAR(36)  NOT NULL PRIMARY KEY,
    -- Lower-case.
    email         VARCHAR(254) NOT NULL UNIQUE,
    -- DelegatingPasswordEncoder format, e.g. {bcrypt}$2a$10$...
    password_hash VARCHAR(255) NOT NULL,
    -- 1 for the account made by the first-run setup, NULL for any other. The unique constraint
    -- lets only one of several concurrent setups succeed. NULLs never collide.
    setup_slot    INTEGER      UNIQUE,
    created_at    BIGINT       NOT NULL,
    updated_at    BIGINT       NOT NULL
);

CREATE TABLE project_members (
    project_id VARCHAR(36) NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    account_id VARCHAR(36) NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    role       VARCHAR(8)  NOT NULL CHECK (role IN ('OWNER', 'MEMBER')),
    created_at BIGINT      NOT NULL,
    PRIMARY KEY (project_id, account_id)
);

CREATE INDEX project_members_account ON project_members (account_id);
