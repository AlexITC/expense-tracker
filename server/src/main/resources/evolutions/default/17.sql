-- !Ups

-- A category can carry an optional free-text description of what belongs in it (NULL means none).
ALTER TABLE category ADD COLUMN description TEXT NULL;
