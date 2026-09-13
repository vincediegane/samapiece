CREATE TABLE agent (
    id                  UUID PRIMARY KEY,
    poste_id            UUID NOT NULL REFERENCES poste(id),
    matricule           VARCHAR(50) NOT NULL UNIQUE,
    nom                 VARCHAR(255) NOT NULL,
    role                VARCHAR(20) NOT NULL CHECK (role IN
                             ('agent', 'chef_poste', 'admin_regional', 'admin_national', 'auditeur')),
    hash_mot_de_passe   VARCHAR(255) NOT NULL,
    actif               BOOLEAN NOT NULL DEFAULT true,
    derniere_connexion  TIMESTAMPTZ,
    cree_le             TIMESTAMPTZ NOT NULL DEFAULT now(),
    maj_le              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_poste_id ON agent(poste_id);
