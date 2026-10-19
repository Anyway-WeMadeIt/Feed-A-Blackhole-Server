-- 게임 계정. 진행 데이터는 이 계정에 붙는다.
CREATE TABLE account (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    created_at    DATETIME(6) NOT NULL,
    last_login_at DATETIME(6) NULL,
    PRIMARY KEY (id)
);

-- 계정으로 들어오는 방법. 한 계정에 여러 개가 붙을 수 있다(게스트 + 나중에 연동하는 Google/Apple).
-- GUEST: identifier = 서버가 발급한 guest id, secret_hash = 발급한 secret의 SHA-256(hex)
-- 소셜(나중): identifier = 제공자의 사용자 ID(sub, OIDC 표준상 최대 255자), secret_hash = NULL
CREATE TABLE auth_identity (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    account_id  BIGINT       NOT NULL,
    type        VARCHAR(20)  NOT NULL,
    identifier  VARCHAR(255) NOT NULL,
    secret_hash CHAR(64)     NULL,
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_auth_identity_type_identifier UNIQUE (type, identifier),
    CONSTRAINT fk_auth_identity_account FOREIGN KEY (account_id) REFERENCES account (id)
);

-- 로그인 유지용 토큰. 원본이 아니라 SHA-256(hex)만 저장한다. 갱신할 때마다 새 토큰으로 교체(회전)한다.
CREATE TABLE refresh_token (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    account_id BIGINT      NOT NULL,
    token_hash CHAR(64)    NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refresh_token_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_token_account FOREIGN KEY (account_id) REFERENCES account (id)
);
