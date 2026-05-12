CREATE TABLE users (
    id INTEGER NOT NULL PRIMARY KEY,
    mc_uuid VARCHAR(36) NOT NULL UNIQUE,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(256),
    is_admin BOOLEAN NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agents (
    id INTEGER NOT NULL PRIMARY KEY,
    server_id VARCHAR(128) NOT NULL UNIQUE,
    agent_type VARCHAR(16) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    last_seen_at TIMESTAMP
);

CREATE TABLE registration_tokens (
    token VARCHAR(128) NOT NULL PRIMARY KEY,
    user_id INTEGER NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE audit_log (
    id INTEGER NOT NULL PRIMARY KEY,
    user_id INTEGER,
    action VARCHAR(64) NOT NULL,
    target VARCHAR(256),
    payload_json TEXT,
    at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id)
);
