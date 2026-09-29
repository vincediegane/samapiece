CREATE UNIQUE INDEX uq_region_nom_lower ON region (lower(nom));

CREATE UNIQUE INDEX uq_poste_region_nom_lower ON poste (region_id, lower(nom));
