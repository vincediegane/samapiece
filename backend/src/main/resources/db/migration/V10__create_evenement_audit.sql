CREATE TABLE evenement_audit (
    id               UUID PRIMARY KEY,
    acteur_id        UUID,
    type_acteur      VARCHAR(50) NOT NULL,
    action           VARCHAR(100) NOT NULL,
    entite_cible     VARCHAR(100) NOT NULL,
    entite_cible_id  UUID,
    details          JSONB NOT NULL,
    adresse_ip       VARCHAR(45),
    horodatage       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_evenement_audit_entite_cible ON evenement_audit(entite_cible, entite_cible_id);
CREATE INDEX idx_evenement_audit_acteur_id ON evenement_audit(acteur_id);
CREATE INDEX idx_evenement_audit_horodatage ON evenement_audit(horodatage DESC);
