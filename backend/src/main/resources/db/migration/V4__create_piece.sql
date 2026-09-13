CREATE TABLE piece (
    id                       UUID PRIMARY KEY,
    poste_id                 UUID NOT NULL REFERENCES poste(id),
    agent_createur_id        UUID NOT NULL REFERENCES agent(id),
    type_document            VARCHAR(30) NOT NULL CHECK (type_document IN
        ('cni', 'passeport', 'permis_conduire', 'carte_electeur',
         'extrait_naissance', 'carte_grise', 'carte_consulaire', 'autre')),
    nom_titulaire            VARCHAR(255) NOT NULL,
    prenom_titulaire         VARCHAR(255) NOT NULL,
    numero_document_hash     VARCHAR(64) NOT NULL,
    numero_document_sel      VARCHAR(64) NOT NULL,
    numero_document_masque   VARCHAR(64) NOT NULL,
    date_naissance_titulaire DATE,
    date_depot               DATE NOT NULL,
    etat_document            VARCHAR(255),
    statut                   VARCHAR(20) NOT NULL DEFAULT 'disponible' CHECK (statut IN
        ('disponible', 'reclamee', 'retiree', 'litige', 'archivee', 'detruite', 'signalee')),
    remarques                TEXT,
    cree_le                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    maj_le                   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_piece_poste_id ON piece(poste_id);
CREATE INDEX idx_piece_agent_createur_id ON piece(agent_createur_id);
CREATE INDEX idx_piece_statut ON piece(statut);
