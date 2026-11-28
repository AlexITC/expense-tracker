-- !Ups

-- A per-user counter embedded in the session cookie. Bumping it invalidates every session issued before the bump,
-- which is how a password reset, a password change or a "log out everywhere" request revokes existing sessions.
-- Existing and new users start at 0.
ALTER TABLE users ADD COLUMN session_version INT NOT NULL DEFAULT 0;
