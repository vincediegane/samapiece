ALTER TABLE piece
    ADD COLUMN cree_malgre_doublon BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX idx_piece_type_document_statut ON piece(type_document, statut);
