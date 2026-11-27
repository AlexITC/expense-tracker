-- !Ups

-- A per-user counter embedded in the session cookie. Bumping it invalidates every session issued before the bump,
-- which is how a password reset, a password change or a "log out everywhere" request revokes existing sessions.
-- No column default, the application writes the initial value. Existing users start at 0.
ALTER TABLE users ADD COLUMN session_version INT NULL;
UPDATE users SET session_version = 0;
ALTER TABLE users ALTER COLUMN session_version SET NOT NULL;
