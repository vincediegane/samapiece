import { useEffect, useState } from 'react';
import { recupererAgentCourant } from '../dashboard/dashboardApi';
import { ErreurApiAudit, listerEvenementsAudit } from './auditApi';
import { peutConsulterAudit } from './roles';
import type { FiltresAudit, PageEvenementsAudit } from './types';

// Actions émises par le backend (@ActionAuditee) ; le filtre est une égalité stricte.
const ACTIONS = [
  'PIECE_CREEE',
  'PIECE_CONSULTEE',
  'PIECE_RETIREE',
  'PIECE_SIGNALEE',
  'PIECE_RECU_GENERE',
  'PIECE_DEBLOQUEE',
];
const ENTITES = ['PIECE'];

const MESSAGE_ACCES_RESERVE = 'Accès réservé aux auditeurs et administrateurs nationaux';

type Acces = 'attente' | 'autorise' | 'refuse';

function AuditPage() {
  const [acces, setAcces] = useState<Acces>('attente');
  const [filtres, setFiltres] = useState<FiltresAudit>({});
  const [page, setPage] = useState(0);
  const [resultat, setResultat] = useState<PageEvenementsAudit | null>(null);
  const [chargement, setChargement] = useState(false);
  const [erreur, setErreur] = useState<'generique' | 'acces' | null>(null);

  useEffect(() => {
    let annule = false;
    recupererAgentCourant()
      .then((agent) => {
        if (!annule) setAcces(peutConsulterAudit(agent.role) ? 'autorise' : 'refuse');
      })
      .catch(() => {
        if (!annule) setAcces('refuse');
      });
    return () => {
      annule = true;
    };
  }, []);

  useEffect(() => {
    if (acces !== 'autorise') return;
    let annule = false;
    setChargement(true);
    setErreur(null);
    listerEvenementsAudit(page, filtres)
      .then((donnees) => {
        if (!annule) setResultat(donnees);
      })
      .catch((e: unknown) => {
        if (annule) return;
        const statut = e instanceof ErreurApiAudit ? e.statut : null;
        setResultat(null);
        setErreur(statut === 401 || statut === 403 ? 'acces' : 'generique');
      })
      .finally(() => {
        if (!annule) setChargement(false);
      });
    return () => {
      annule = true;
    };
  }, [acces, page, filtres]);

  function changerFiltre(cle: keyof FiltresAudit, valeur: string) {
    setFiltres((precedents) => ({ ...precedents, [cle]: valeur === '' ? undefined : valeur }));
    setPage(0);
  }

  if (acces === 'attente') {
    return <div className="p-8 text-sm text-slate-500">Chargement…</div>;
  }

  if (acces === 'refuse' || erreur === 'acces') {
    return (
      <div className="p-8">
        <div role="alert" className="alert-error">
          {MESSAGE_ACCES_RESERVE}
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-5 p-8">
      <h1 className="text-xl font-bold text-slate-800">Journal d'audit</h1>

      <div className="flex gap-4">
        <label className="flex flex-col gap-1 text-sm text-slate-600">
          Action
          <select
            className="field-input"
            value={filtres.action ?? ''}
            onChange={(e) => changerFiltre('action', e.target.value)}
          >
            <option value="">Toutes</option>
            {ACTIONS.map((action) => (
              <option key={action} value={action}>
                {action}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm text-slate-600">
          Entité
          <select
            className="field-input"
            value={filtres.entiteCible ?? ''}
            onChange={(e) => changerFiltre('entiteCible', e.target.value)}
          >
            <option value="">Toutes</option>
            {ENTITES.map((entite) => (
              <option key={entite} value={entite}>
                {entite}
              </option>
            ))}
          </select>
        </label>
      </div>

      {erreur === 'generique' && (
        <div role="alert" className="alert-error">
          Impossible de charger le journal d'audit.
        </div>
      )}
      {chargement && <p className="text-sm text-slate-500">Chargement…</p>}

      {!chargement && resultat && resultat.content.length === 0 && (
        <p className="text-sm text-slate-500">Aucun événement d'audit</p>
      )}

      {resultat && resultat.content.length > 0 && (
        <table className="w-full text-left text-sm">
          <thead>
            <tr>
              <th>Horodatage</th>
              <th>Action</th>
              <th>Entité</th>
              <th>Acteur</th>
              <th>IP</th>
              <th>Détails</th>
            </tr>
          </thead>
          <tbody>
            {resultat.content.map((evenement) => (
              <tr key={evenement.id}>
                <td>{new Date(evenement.horodatage).toLocaleString('fr-FR')}</td>
                <td>{evenement.action}</td>
                <td>
                  {evenement.entiteCible}
                  {evenement.entiteCibleId ? ` ${evenement.entiteCibleId.slice(0, 8)}` : ''}
                </td>
                <td>
                  {evenement.typeActeur}
                  {evenement.acteurId ? ` ${evenement.acteurId}` : ''}
                </td>
                <td>{evenement.adresseIp}</td>
                <td>{evenement.details}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {resultat && (
        <div className="flex items-center gap-4 text-sm text-slate-600">
          <button
            type="button"
            className="btn-outline"
            disabled={resultat.number <= 0}
            onClick={() => setPage(resultat.number - 1)}
          >
            Précédent
          </button>
          <span>
            Page {resultat.number + 1} sur {Math.max(resultat.totalPages, 1)} ({resultat.totalElements}{' '}
            événements)
          </span>
          <button
            type="button"
            className="btn-outline"
            disabled={resultat.number + 1 >= resultat.totalPages}
            onClick={() => setPage(resultat.number + 1)}
          >
            Suivant
          </button>
        </div>
      )}
    </div>
  );
}

export default AuditPage;
