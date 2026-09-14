CREATE INDEX idx_alerte_correspondance ON alerte(type_document, nom_titulaire) WHERE active = true;
