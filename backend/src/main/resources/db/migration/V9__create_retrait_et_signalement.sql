CREATE TABLE retrait (
    id                             UUID PRIMARY KEY,
    piece_id                       UUID NOT NULL REFERENCES piece(id),
    agent_validateur_id            UUID NOT NULL REFERENCES agent(id),
    nom_reclamant                  VARCHAR(255) NOT NULL,
    piece_justificative_presentee  TEXT NOT NULL,
    cree_le                        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_retrait_piece_id ON retrait(piece_id);
CREATE INDEX idx_retrait_agent_validateur_id ON retrait(agent_validateur_id);

ALTER TABLE piece
    ADD COLUMN signale_par_id    UUID REFERENCES agent(id),
    ADD COLUMN signale_le        TIMESTAMPTZ,
    ADD COLUMN motif_signalement TEXT,
    ADD COLUMN debloque_par_id   UUID REFERENCES agent(id),
    ADD COLUMN debloque_le       TIMESTAMPTZ,
    ADD COLUMN motif_deblocage   TEXT;
