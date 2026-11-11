-- !Ups

-- Fixed-window rate limiter shared by all (stateless) server instances.
-- key: TEXT enum, allowed values: Bedrock.
-- There is one row per key that resets itself when a new minute starts, so no cleanup is needed.
CREATE TABLE rate_limit_window(
  key TEXT NOT NULL,
  window_start TIMESTAMPTZ NOT NULL,
  count INT NOT NULL,
  CONSTRAINT rate_limit_window_pk PRIMARY KEY (key)
);
