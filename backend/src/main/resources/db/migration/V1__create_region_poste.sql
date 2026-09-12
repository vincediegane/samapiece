CREATE TABLE region (
    id   UUID PRIMARY KEY,
    nom  VARCHAR(255) NOT NULL
);

CREATE TABLE poste (
    id          UUID PRIMARY KEY,
    region_id   UUID NOT NULL REFERENCES region(id),
    nom         VARCHAR(255) NOT NULL,
    type        VARCHAR(20) NOT NULL CHECK (type IN ('police', 'gendarmerie')),
    adresse     VARCHAR(500) NOT NULL,
    telephone   VARCHAR(30),
    horaires    JSONB NOT NULL DEFAULT '{}'::jsonb,
    latitude    DOUBLE PRECISION,
    longitude   DOUBLE PRECISION,
    cree_le     TIMESTAMPTZ NOT NULL DEFAULT now(),
    maj_le      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_poste_region_id ON poste(region_id);
