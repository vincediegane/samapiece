import { useEffect, useState } from 'react';
import {
  getStatistiquesNationale,
  getStatistiquesRegionale,
  recupererAgentCourant,
} from './dashboardApi';
import type { StatistiquesConsolidees } from './types';
import { IconAlertTriangle } from '../../shared/icons';

const MESSAGE_403 =
  'Accès refusé : cette vue est réservée aux administrateurs régionaux et nationaux.';
const MESSAGE_ERREUR = 'Impossible de charger la vue multi-poste.';

function valeur(nombre: number | null): string {
  return nombre === null ? '—' : String(nombre);
}

function VueMultiPostePage() {
  const [statistiques, setStatistiques] = useState<StatistiquesConsolidees | null>(null);
  const [erreur, setErreur] = useState<string | null>(null);

  useEffect(() => {
    recupererAgentCourant()
      .then((agent) => {
        if (agent.role === 'ADMIN_REGIONAL') return getStatistiquesRegionale();
        if (agent.role === 'ADMIN_NATIONAL') return getStatistiquesNationale();
        throw new Error('Erreur 403');
      })
      .then(setStatistiques)
      .catch((e: unknown) =>
        setErreur(e instanceof Error && e.message === 'Erreur 403' ? MESSAGE_403 : MESSAGE_ERREUR),
      );
  }, []);

  const national = statistiques?.portee === 'NATIONALE';
  const enDepassement = (statistiques?.totaux.nombrePiecesDepassantSeuil ?? 0) > 0;

  let titre = 'Vue multi-poste';
  if (statistiques) {
    titre = national ? 'Vue multi-poste — National' : `Vue multi-poste — ${statistiques.regionNom}`;
  }

  return (
    <main className="mx-auto w-full max-w-5xl px-4 py-10 sm:px-6">
      <h1 className="page-title">{titre}</h1>

      {erreur && (
        <p role="alert" className="alert-error">
          {erreur}
        </p>
      )}

      {!statistiques && !erreur && <p role="status">Chargement des statistiques…</p>}

      {statistiques && (
        <>
          {enDepassement && (
            <p role="alert" className="alert-error mb-5 flex items-center gap-2.5">
              <IconAlertTriangle width={18} height={18} className="flex-shrink-0" />
              {statistiques.totaux.nombrePiecesDepassantSeuil} pièce(s) dépassent le seuil de{' '}
              {statistiques.seuilAncienneteJours} jours dans{' '}
              {statistiques.totaux.nombrePostesEnDepassement} poste(s)
            </p>
          )}

          <div className="grid gap-4 sm:grid-cols-4">
            <div className="stat-tile">
              <span className="stat-tile-label">Postes</span>
              <p className="stat-tile-value">
                {statistiques.totaux.nombrePostes}
                <span className="sr-only"> — Postes : {statistiques.totaux.nombrePostes}</span>
              </p>
            </div>
            <div className="stat-tile">
              <span className="stat-tile-label">Pièces en attente</span>
              <p className="stat-tile-value">
                {statistiques.totaux.nombrePiecesEnAttente}
                <span className="sr-only">
                  {' '}
                  — Pièces en attente : {statistiques.totaux.nombrePiecesEnAttente}
                </span>
              </p>
            </div>
            <div className="stat-tile">
              <span className="stat-tile-label">Ancienneté moyenne</span>
              <p className="stat-tile-value">
                {valeur(statistiques.totaux.ancienneteMoyenneJours)}
                <span className="sr-only">
                  {' '}
                  — Ancienneté moyenne : {valeur(statistiques.totaux.ancienneteMoyenneJours)}
                </span>
              </p>
            </div>
            <div className="stat-tile">
              <span className="stat-tile-label">Ancienneté maximale</span>
              <p className="stat-tile-value">
                {valeur(statistiques.totaux.ancienneteMaxJours)}
                <span className="sr-only">
                  {' '}
                  — Ancienneté maximale : {valeur(statistiques.totaux.ancienneteMaxJours)}
                </span>
              </p>
            </div>
          </div>

          {statistiques.postes.length === 0 ? (
            <p className="mt-6">Aucun poste dans le périmètre.</p>
          ) : (
            <table className="mt-6 w-full text-left text-sm">
              <caption className="sr-only">Statistiques par poste</caption>
              <thead>
                <tr>
                  <th scope="col">Poste</th>
                  {national && <th scope="col">Région</th>}
                  <th scope="col">Pièces en attente</th>
                  <th scope="col">Ancienneté moyenne</th>
                  <th scope="col">Ancienneté max</th>
                  <th scope="col">Pièces au-delà du seuil</th>
                </tr>
              </thead>
              <tbody>
                {statistiques.postes.map((ligne) => (
                  <tr
                    key={ligne.posteId}
                    className={ligne.nombrePiecesDepassantSeuil > 0 ? 'bg-danger-50 text-danger-500' : ''}
                  >
                    <td>{ligne.posteNom}</td>
                    {national && <td>{ligne.regionNom}</td>}
                    <td>{ligne.nombrePiecesEnAttente}</td>
                    <td>{valeur(ligne.ancienneteMoyenneJours)}</td>
                    <td>{valeur(ligne.ancienneteMaxJours)}</td>
                    <td>{ligne.nombrePiecesDepassantSeuil}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </main>
  );
}

export default VueMultiPostePage;
