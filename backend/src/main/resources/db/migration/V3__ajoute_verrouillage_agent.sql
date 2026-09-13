ALTER TABLE agent
    ADD COLUMN tentatives_echouees INT NOT NULL DEFAULT 0,
    ADD COLUMN verrouille_jusqu_a TIMESTAMPTZ;
