CREATE TABLE photo (
    id                 UUID PRIMARY KEY,
    piece_id           UUID NOT NULL REFERENCES piece(id),
    type               VARCHAR(10) NOT NULL CHECK (type IN ('recto', 'verso')),
    cle_objet_stockage VARCHAR(255) NOT NULL UNIQUE,
    type_mime          VARCHAR(50) NOT NULL,
    taille_octets      BIGINT NOT NULL,
    iv_chiffrement     VARCHAR(64) NOT NULL,
    cree_le            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_photo_piece_id ON photo(piece_id);
CREATE UNIQUE INDEX uq_photo_piece_type ON photo(piece_id, type);
