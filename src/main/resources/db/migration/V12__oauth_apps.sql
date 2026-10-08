-- Phase 12b: third-party apps (OAuth 2.0 authorization code + PKCE) and their installations.

-- PLATFORM table (no RLS): an app belongs to a developer (a platform user), not to a store.
CREATE TABLE oauth_apps (
    id                  uuid        PRIMARY KEY,
    owner_user_id       uuid        NOT NULL,
    name                text        NOT NULL,
    client_id           text        NOT NULL,
    -- {argon2}... via the application's PasswordEncoder; the secret itself is shown once.
    client_secret_hash  text        NOT NULL,
    redirect_uris       text[]      NOT NULL,
    -- The most an installation may be granted; merchants approve a subset or all of these.
    scopes              text[]      NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    CONSTRAINT uq_oauth_apps_client_id UNIQUE (client_id),
    CONSTRAINT ck_oauth_apps_redirects CHECK (cardinality(redirect_uris) BETWEEN 1 AND 10),
    CONSTRAINT ck_oauth_apps_scopes CHECK (cardinality(scopes) > 0),
    CONSTRAINT fk_oauth_apps_owner FOREIGN KEY (owner_user_id) REFERENCES users (id)
);
CREATE INDEX ix_oauth_apps_owner ON oauth_apps (owner_user_id);

-- An app installed on a store: tenant data. Its access token is an api_credentials row (kind APP_INSTALLATION).
CREATE TABLE app_installations (
    id              uuid        PRIMARY KEY,
    store_id        uuid        NOT NULL,
    app_id          uuid        NOT NULL,
    credential_id   uuid        NOT NULL,
    scopes          text[]      NOT NULL,
    installed_by    uuid        NOT NULL,
    installed_at    timestamptz NOT NULL,
    uninstalled_at  timestamptz,
    CONSTRAINT fk_app_installations_store FOREIGN KEY (store_id) REFERENCES stores (id) ON DELETE CASCADE,
    CONSTRAINT fk_app_installations_app FOREIGN KEY (app_id) REFERENCES oauth_apps (id),
    CONSTRAINT fk_app_installations_credential FOREIGN KEY (credential_id) REFERENCES api_credentials (id)
);
-- At most one ACTIVE installation of an app per store (reinstalling replaces it).
CREATE UNIQUE INDEX uq_app_installations_active ON app_installations (store_id, app_id) WHERE uninstalled_at IS NULL;
ALTER TABLE app_installations ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_installations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app_installations USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());

-- Spring Authorization Server's pending authorizations (between "authorize" and "token"), adapted
-- for PostgreSQL as its schema file instructs: blob -> text, timestamp -> timestamptz.
-- INFRASTRUCTURE (no RLS). Rows live only until the code is exchanged: StoreForge removes them
-- once a token is issued, so issued access tokens are never stored here (only their hash, elsewhere).
CREATE TABLE oauth2_authorization (
    id varchar(100) NOT NULL,
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorization_grant_type varchar(100) NOT NULL,
    authorized_scopes varchar(1000) DEFAULT NULL,
    attributes text DEFAULT NULL,
    state varchar(500) DEFAULT NULL,
    authorization_code_value text DEFAULT NULL,
    authorization_code_issued_at timestamptz DEFAULT NULL,
    authorization_code_expires_at timestamptz DEFAULT NULL,
    authorization_code_metadata text DEFAULT NULL,
    access_token_value text DEFAULT NULL,
    access_token_issued_at timestamptz DEFAULT NULL,
    access_token_expires_at timestamptz DEFAULT NULL,
    access_token_metadata text DEFAULT NULL,
    access_token_type varchar(100) DEFAULT NULL,
    access_token_scopes varchar(1000) DEFAULT NULL,
    oidc_id_token_value text DEFAULT NULL,
    oidc_id_token_issued_at timestamptz DEFAULT NULL,
    oidc_id_token_expires_at timestamptz DEFAULT NULL,
    oidc_id_token_metadata text DEFAULT NULL,
    refresh_token_value text DEFAULT NULL,
    refresh_token_issued_at timestamptz DEFAULT NULL,
    refresh_token_expires_at timestamptz DEFAULT NULL,
    refresh_token_metadata text DEFAULT NULL,
    user_code_value text DEFAULT NULL,
    user_code_issued_at timestamptz DEFAULT NULL,
    user_code_expires_at timestamptz DEFAULT NULL,
    user_code_metadata text DEFAULT NULL,
    device_code_value text DEFAULT NULL,
    device_code_issued_at timestamptz DEFAULT NULL,
    device_code_expires_at timestamptz DEFAULT NULL,
    device_code_metadata text DEFAULT NULL,
    PRIMARY KEY (id)
);
