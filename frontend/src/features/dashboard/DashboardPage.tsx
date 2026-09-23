import { useEffect, useState } from 'react';
import { getStatistiquesPoste, recupererAgentCourant } from './dashboardApi';
import type { StatistiquesPoste } from './types';
import { IconAlertTriangle } from '../../shared/icons';

function DashboardPage() {
  const [statistiques, setStatistiques] = useState<StatistiquesPoste | null>(null);
  const [erreur, setErreur] = useState<string | null>(null);

  useEffect(() => {
    recupererAgentCourant()
      .then((agent) => getStatistiquesPoste(agent.posteId))
      .then(setStatistiques)
      .catch(() => setErreur('Impossible de charger les statistiques du poste.'));
  }, []);

  const enDepassement = (statistiques?.nombrePiecesDepassantSeuil ?? 0) > 0;

  return (
    <main className="mx-auto w-full max-w-5xl px-4 py-10 sm:px-6">
      <h1 className="page-title">
        Tableau de bord{statistiques ? ` — ${statistiques.posteNom}` : ''}
      </h1>

      {erreur && (
        <p role="alert" className="alert-error">
          {erreur}
        </p>
      )}

      {statistiques && (
        <>
          {enDepassement && (
            <p role="alert" className="alert-error mb-5 flex items-center gap-2.5">
              <IconAlertTriangle width={18} height={18} className="flex-shrink-0" />
              {statistiques.nombrePiecesDepassantSeuil} pièce(s) dépassent le seuil de{' '}
              {statistiques.seuilAncienneteJours} jours
            </p>
          )}

          <div className="grid gap-4 sm:grid-cols-3">
            <div className="stat-tile">
              <span className="stat-tile-label">Pièces en attente</span>
              <p className="stat-tile-value">
                {statistiques.nombrePiecesEnAttente}
                <span className="sr-only"> — Pièces en attente : {statistiques.nombrePiecesEnAttente}</span>
              </p>
            </div>

            {statistiques.ancienneteMoyenneJours === null ||
            statistiques.ancienneteMaxJours === null ? (
              <div className="stat-tile sm:col-span-2 flex items-center">
                <p className="text-slate-500">Aucune pièce en attente.</p>
              </div>
            ) : (
              <>
                <div className="stat-tile">
                  <span className="stat-tile-label">Ancienneté moyenne</span>
                  <p className="stat-tile-value">
                    {statistiques.ancienneteMoyenneJours}
                    <span className="text-base font-semibold text-slate-400"> j</span>
                    <span className="sr-only">
                      {' '}
                      — Ancienneté moyenne : {statistiques.ancienneteMoyenneJours} jour(s)
                    </span>
                  </p>
                </div>
                <div className={`stat-tile ${enDepassement ? 'border-danger-500/30 bg-danger-50' : ''}`}>
                  <span className={`stat-tile-label ${enDepassement ? 'text-danger-500' : ''}`}>
                    Ancienneté maximale
                  </span>
                  <p className={`stat-tile-value ${enDepassement ? 'text-danger-500' : ''}`}>
                    {statistiques.ancienneteMaxJours}
                    <span className="text-base font-semibold"> j</span>
                    <span className="sr-only">
                      {' '}
                      — Ancienneté maximale : {statistiques.ancienneteMaxJours} jour(s)
                    </span>
                  </p>
                </div>
              </>
            )}
          </div>
        </>
      )}
    </main>
  );
}

export default DashboardPage;
