CREATE TABLE alerte (
    id                       UUID PRIMARY KEY,
    type_document            VARCHAR(30) NOT NULL CHECK (type_document IN
        ('cni', 'passeport', 'permis_conduire', 'carte_electeur',
         'extrait_naissance', 'carte_grise', 'carte_consulaire', 'autre')),
    nom_titulaire            VARCHAR(255) NOT NULL,
    prenom_titulaire         VARCHAR(255),
    numero_document_hash     VARCHAR(64),
    numero_document_sel      VARCHAR(64),
    numero_document_masque   VARCHAR(64),
    date_naissance_titulaire DATE,
    canal                    VARCHAR(10) NOT NULL DEFAULT 'sms' CHECK (canal IN ('sms')),
    contact_chiffre          BYTEA,
    contact_iv               VARCHAR(64),
    active                   BOOLEAN NOT NULL DEFAULT true,
    cree_le                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    maj_le                   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_alerte_active ON alerte(active);

CREATE TABLE alerte_desinscription_token (
    id           UUID PRIMARY KEY,
    alerte_id    UUID NOT NULL REFERENCES alerte(id),
    token_hash   VARCHAR(64) NOT NULL,
    expire_le    TIMESTAMPTZ NOT NULL,
    consomme_le  TIMESTAMPTZ,
    cree_le      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_alerte_desinscription_token_token_hash ON alerte_desinscription_token(token_hash);
CREATE INDEX idx_alerte_desinscription_token_alerte_id ON alerte_desinscription_token(alerte_id);
