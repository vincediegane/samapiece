import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { creerAgent, desactiverAgent, ErreurApiAgents, listerAgents } from './agentsApi';
import { recupererAgentCourant } from '../dashboard/dashboardApi';
import { peutGererAgents } from './roles';
import type { Agent, CreerAgentPayload, Poste, Role } from './types';
import { IconCopy, IconKey } from '../../shared/icons';

const ROLES: Role[] = ['AGENT', 'CHEF_POSTE', 'ADMIN_REGIONAL', 'ADMIN_NATIONAL', 'AUDITEUR'];

const LIBELLES_ROLE: Record<Role, string> = {
  AGENT: 'Agent',
  CHEF_POSTE: 'Chef de poste',
  ADMIN_REGIONAL: 'Administrateur régional',
  ADMIN_NATIONAL: 'Administrateur national',
  AUDITEUR: 'Auditeur',
};

const COULEUR_POINT_ROLE: Record<Role, string> = {
  AGENT: 'bg-slate-400',
  CHEF_POSTE: 'bg-primary-700',
  ADMIN_REGIONAL: 'bg-info-500',
  ADMIN_NATIONAL: 'bg-accent-500',
  AUDITEUR: 'bg-slate-600',
};

const FORMULAIRE_INITIAL: CreerAgentPayload = {
  posteId: '',
  matricule: '',
  nom: '',
  role: 'AGENT',
};

const MESSAGE_DROITS_INSUFFISANTS =
  'Droits insuffisants : la gestion des comptes agents est réservée aux chefs de poste et aux administrateurs.';
const MESSAGE_SESSION_INVALIDE = 'Session expirée ou invalide, reconnectez-vous.';

function messagePourErreur(e: unknown, parDefaut: string, utiliserMessage = false): string {
  if (e instanceof ErreurApiAgents && e.statut === 401) return MESSAGE_SESSION_INVALIDE;
  if (e instanceof ErreurApiAgents && e.statut === 403) return MESSAGE_DROITS_INSUFFISANTS;
  if (utiliserMessage && e instanceof Error) return e.message;
  return parDefaut;
}

function AgentsPage() {
  const [acces, setAcces] = useState<'attente' | 'autorise' | 'refuse'>('attente');
  const [agents, setAgents] = useState<Agent[]>([]);
  const [postes, setPostes] = useState<Poste[]>([]);
  const [formulaire, setFormulaire] = useState<CreerAgentPayload>(FORMULAIRE_INITIAL);
  const [motDePasseTemporaire, setMotDePasseTemporaire] = useState<string | null>(null);
  const [erreur, setErreur] = useState<string | null>(null);

  useEffect(() => {
    let annule = false;
    recupererAgentCourant()
      .then((agent) => {
        if (!annule) setAcces(peutGererAgents(agent.role) ? 'autorise' : 'refuse');
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
    listerAgents()
      .then(setAgents)
      .catch((e) => setErreur(messagePourErreur(e, 'Impossible de charger les agents.')));

    fetch('/api/v1/postes')
      .then((reponse) => {
        if (!reponse.ok) throw new Error(`Erreur ${reponse.status}`);
        return reponse.json();
      })
      .then(setPostes)
      .catch(() => setErreur('Impossible de charger les postes.'));
  }, [acces]);

  async function soumettreFormulaire(evenement: FormEvent) {
    evenement.preventDefault();
    setErreur(null);
    try {
      const resultat = await creerAgent(formulaire);
      setAgents((precedents) => [
        {
          id: resultat.id,
          matricule: resultat.matricule,
          nom: resultat.nom,
          role: resultat.role,
          posteId: resultat.posteId,
          posteNom: postes.find((poste) => poste.id === resultat.posteId)?.nom ?? '',
          actif: resultat.actif,
          creeLe: resultat.creeLe,
        },
        ...precedents,
      ]);
      setMotDePasseTemporaire(resultat.motDePasseTemporaire);
      setFormulaire(FORMULAIRE_INITIAL);
    } catch (e) {
      setErreur(messagePourErreur(e, 'Erreur inconnue lors de la création.', true));
    }
  }

  async function gererDesactivation(id: string) {
    setErreur(null);
    try {
      await desactiverAgent(id);
      setAgents((precedents) =>
        precedents.map((agent) => (agent.id === id ? { ...agent, actif: false } : agent)),
      );
    } catch (e) {
      setErreur(messagePourErreur(e, 'Erreur inconnue lors de la désactivation.', true));
    }
  }

  async function copierMotDePasse() {
    if (!motDePasseTemporaire) return;
    try {
      await navigator.clipboard.writeText(motDePasseTemporaire);
    } catch {
      // Copie manuelle si l'API Clipboard est indisponible.
    }
  }

  if (acces === 'attente') {
    return <div className="p-8 text-sm text-slate-500">Chargement…</div>;
  }

  if (acces === 'refuse') {
    return (
      <div className="p-8">
        <div role="alert" className="alert-error">
          {MESSAGE_DROITS_INSUFFISANTS}
        </div>
      </div>
    );
  }

  return (
    <main className="mx-auto w-full max-w-6xl px-4 py-10 sm:px-6">
      <h1 className="page-title">Gestion des comptes agents</h1>

      {erreur && (
        <p role="alert" className="alert-error">
          {erreur}
        </p>
      )}

      {motDePasseTemporaire && (
        <div
          role="status"
          className="mb-6 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-primary-500/25 bg-primary-50 px-5 py-3.5"
        >
          <div className="flex items-center gap-3">
            <IconKey width={20} height={20} className="flex-shrink-0 text-primary-700" />
            <p className="text-primary-700">
              Mot de passe temporaire : <strong>{motDePasseTemporaire}</strong>
              <span className="ml-1 font-normal text-primary-700/80">
                — à communiquer à l&apos;agent, ne sera plus affiché.
              </span>
            </p>
          </div>
          <button
            type="button"
            onClick={copierMotDePasse}
            className="btn-outline flex-shrink-0 gap-1.5 px-3.5 py-1.5 text-xs"
          >
            <IconCopy width={13} height={13} />
            Copier
          </button>
        </div>
      )}

      <div className="flex flex-col gap-6 lg:flex-row lg:items-start">
        <form
          onSubmit={soumettreFormulaire}
          className="flex w-full flex-col gap-5 rounded-2xl border border-slate-200 bg-white p-6 lg:w-80 lg:flex-shrink-0"
        >
          <h2 className="text-[15px] font-bold text-primary-700">Créer un compte agent</h2>
          <label className="field">
            <span className="field-label">Matricule</span>
            <input
              className="field-input"
              value={formulaire.matricule}
              onChange={(e) => setFormulaire({ ...formulaire, matricule: e.target.value })}
              required
            />
          </label>
          <label className="field">
            <span className="field-label">Nom</span>
            <input
              className="field-input"
              value={formulaire.nom}
              onChange={(e) => setFormulaire({ ...formulaire, nom: e.target.value })}
              required
            />
          </label>
          <label className="field">
            <span className="field-label">Rôle</span>
            <select
              className="field-input"
              value={formulaire.role}
              onChange={(e) => setFormulaire({ ...formulaire, role: e.target.value as Role })}
            >
              {ROLES.map((role) => (
                <option key={role} value={role}>
                  {LIBELLES_ROLE[role]}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span className="field-label">Poste</span>
            <select
              className="field-input"
              value={formulaire.posteId}
              onChange={(e) => setFormulaire({ ...formulaire, posteId: e.target.value })}
              required
            >
              <option value="" disabled>
                Sélectionner un poste
              </option>
              {postes.map((poste) => (
                <option key={poste.id} value={poste.id}>
                  {poste.nom}
                </option>
              ))}
            </select>
          </label>
          <button type="submit" className="btn-primary">
            Créer l&apos;agent
          </button>
        </form>

        <div className="table-wrap flex-1">
          <table className="table">
            <thead>
              <tr>
                <th>Matricule</th>
                <th>Nom</th>
                <th>Poste</th>
                <th>Rôle</th>
                <th>Statut</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {agents.map((agent) => (
                <tr key={agent.id}>
                  <td>{agent.matricule}</td>
                  <td className="font-semibold text-slate-900">{agent.nom}</td>
                  <td>{agent.posteNom}</td>
                  <td>
                    <span className="inline-flex items-center gap-1.5 font-semibold text-slate-700">
                      <span className={`role-dot ${COULEUR_POINT_ROLE[agent.role]}`} />
                      {LIBELLES_ROLE[agent.role] ?? agent.role}
                    </span>
                  </td>
                  <td>
                    <span
                      className={`inline-flex items-center gap-1.5 text-xs font-semibold ${
                        agent.actif ? 'text-primary-700' : 'text-slate-400'
                      }`}
                    >
                      <span
                        className={`badge-dot ${agent.actif ? 'bg-primary-500' : 'bg-slate-300'}`}
                      />
                      {agent.actif ? 'Actif' : 'Inactif'}
                    </span>
                  </td>
                  <td>
                    <button
                      type="button"
                      className="btn-danger-outline"
                      disabled={!agent.actif}
                      onClick={() => gererDesactivation(agent.id)}
                    >
                      Désactiver
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </main>
  );
}

export default AgentsPage;
