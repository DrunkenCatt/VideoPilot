CREATE TABLE identity_user (
    user_id CHAR(36) NOT NULL,
    username VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_identity_user_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE user_session (
    session_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    session_token_hash CHAR(64) NOT NULL,
    csrf_token VARCHAR(128) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (session_id),
    UNIQUE KEY uk_user_session_token_hash (session_token_hash),
    KEY idx_user_session_user_expiry (user_id, expires_at),
    KEY idx_user_session_expiry (expires_at),
    CONSTRAINT fk_user_session_user FOREIGN KEY (user_id) REFERENCES identity_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
