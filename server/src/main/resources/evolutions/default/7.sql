-- !Ups

-- When a RUNNING claim on a background job expires: past this instant the claim is considered abandoned
-- (e.g. the worker crashed) and the job can be claimed again. NULL until the job is claimed.
-- Not to be confused with execute_at, which is when the job may run.
ALTER TABLE background_jobs ADD COLUMN deadline TIMESTAMPTZ NULL;
