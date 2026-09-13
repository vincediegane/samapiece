ALTER TABLE piece ADD COLUMN numero_fiche VARCHAR(50) NOT NULL;
ALTER TABLE piece ADD CONSTRAINT uq_piece_numero_fiche UNIQUE (numero_fiche);

CREATE TABLE piece_sequence (
    poste_id       UUID NOT NULL REFERENCES poste(id),
    annee          INTEGER NOT NULL,
    dernier_numero INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (poste_id, annee)
);
