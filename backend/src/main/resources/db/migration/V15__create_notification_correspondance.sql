-- File d'attente (outbox) des notifications SMS de correspondance alerte <-> piece.
-- Remplace les files RabbitMQ (retry avec backoff + dead-letter). Aucune donnee personnelle :
-- uniquement des identifiants rechargees au moment de l'envoi. Pas de cle etrangere volontairement :
-- la file est autonome (un job orphelin finit simplement en echec apres les tentatives).
CREATE TABLE notification_correspondance (
    id                  UUID PRIMARY KEY,
    alerte_id           UUID NOT NULL,
    piece_id            UUID NOT NULL,
    statut              VARCHAR(10) NOT NULL DEFAULT 'EN_ATTENTE'
        CHECK (statut IN ('EN_ATTENTE', 'ENVOYEE', 'ANNULEE', 'ECHEC')),
    nombre_tentatives   INTEGER NOT NULL DEFAULT 0,
    prochaine_tentative TIMESTAMPTZ NOT NULL,
    cree_le             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_correspondance_a_traiter
    ON notification_correspondance(prochaine_tentative) WHERE statut = 'EN_ATTENTE';
