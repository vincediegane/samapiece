import { useEffect, useState } from 'react';
import { getStatistiquesPoste, recupererAgentCourant } from './dashboardApi';
import type { StatistiquesPoste } from './types';

function DashboardPage() {
  const [statistiques, setStatistiques] = useState<StatistiquesPoste | null>(null);
  const [erreur, setErreur] = useState<string | null>(null);

  useEffect(() => {
    recupererAgentCourant()
      .then((agent) => getStatistiquesPoste(agent.posteId))
      .then(setStatistiques)
      .catch(() => setErreur('Impossible de charger les statistiques du poste.'));
  }, []);

  return (
    <main>
      <h1>Tableau de bord{statistiques ? ` — ${statistiques.posteNom}` : ''}</h1>

      {erreur && <p role="alert">{erreur}</p>}

      {statistiques && (
        <section>
          <p>Pièces en attente : {statistiques.nombrePiecesEnAttente}</p>
          {statistiques.ancienneteMoyenneJours === null || statistiques.ancienneteMaxJours === null ? (
            <p>Aucune pièce en attente.</p>
          ) : (
            <>
              <p>Ancienneté moyenne : {statistiques.ancienneteMoyenneJours} jour(s)</p>
              <p>Ancienneté maximale : {statistiques.ancienneteMaxJours} jour(s)</p>
            </>
          )}

          {statistiques.nombrePiecesDepassantSeuil > 0 && (
            <p role="alert">
              {statistiques.nombrePiecesDepassantSeuil} pièce(s) dépassent le seuil de{' '}
              {statistiques.seuilAncienneteJours} jours
            </p>
          )}
        </section>
      )}
    </main>
  );
}

export default DashboardPage;
