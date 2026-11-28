-- !Ups

-- The free-text account event log was never read by the web app, so it is dropped along with its writes.
DROP TABLE user_logs;
