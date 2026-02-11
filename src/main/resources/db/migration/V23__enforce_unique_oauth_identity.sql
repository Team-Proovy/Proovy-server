-- provider/provider_user_id must uniquely identify an OAuth user.
-- Replace non-unique index with a unique index for non-null OAuth identities.
DROP INDEX IF EXISTS idx_user_provider;

CREATE UNIQUE INDEX IF NOT EXISTS idx_user_provider
    ON users(provider, provider_user_id)
    WHERE provider IS NOT NULL AND provider_user_id IS NOT NULL;
