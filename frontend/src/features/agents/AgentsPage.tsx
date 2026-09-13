import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { creerAgent, desactiverAgent, listerAgents } from './agentsApi';
import type { Agent, CreerAgentPayload, Poste, Role } from './types';

const ROLES: Role[] = ['AGENT', 'CHEF_POSTE', 'ADMIN_REGIONAL', 'ADMIN_NATIONAL', 'AUDITEUR'];

const FORMULAIRE_INITIAL: CreerAgentPayload = {
  posteId: '',
  matricule: '',
  nom: '',
  role: 'AGENT',
};

function AgentsPage() {
  const [agents, setAgents] = useState<Agent[]>([]);
  const [postes, setPostes] = useState<Poste[]>([]);
  const [formulaire, setFormulaire] = useState<CreerAgentPayload>(FORMULAIRE_INITIAL);
  const [motDePasseTemporaire, setMotDePasseTemporaire] = useState<string | null>(null);
  const [erreur, setErreur] = useState<string | null>(null);

  useEffect(() => {
    listerAgents()
      .then(setAgents)
      .catch(() => setErreur('Impossible de charger les agents (jeton absent ou expiré).'));

    fetch('/api/v1/postes')
      .then((reponse) => {
        if (!reponse.ok) throw new Error(`Erreur ${reponse.status}`);
        return reponse.json();
      })
      .then(setPostes)
      .catch(() => setErreur('Impossible de charger les postes.'));
  }, []);

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
      setErreur(e instanceof Error ? e.message : 'Erreur inconnue lors de la création.');
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
      setErreur(e instanceof Error ? e.message : 'Erreur inconnue lors de la désactivation.');
    }
  }

  return (
    <main>
      <h1>Gestion des comptes agents</h1>

      {erreur && <p role="alert">{erreur}</p>}

      {motDePasseTemporaire && (
        <div role="status">
          <p>
            Mot de passe temporaire : <strong>{motDePasseTemporaire}</strong>
          </p>
          <p>À communiquer à l&apos;agent — ne sera plus affiché.</p>
        </div>
      )}

      <form onSubmit={soumettreFormulaire}>
        <label>
          Matricule
          <input
            value={formulaire.matricule}
            onChange={(e) => setFormulaire({ ...formulaire, matricule: e.target.value })}
            required
          />
        </label>
        <label>
          Nom
          <input
            value={formulaire.nom}
            onChange={(e) => setFormulaire({ ...formulaire, nom: e.target.value })}
            required
          />
        </label>
        <label>
          Rôle
          <select
            value={formulaire.role}
            onChange={(e) => setFormulaire({ ...formulaire, role: e.target.value as Role })}
          >
            {ROLES.map((role) => (
              <option key={role} value={role}>
                {role}
              </option>
            ))}
          </select>
        </label>
        <label>
          Poste
          <select
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
        <button type="submit">Créer l&apos;agent</button>
      </form>

      <table>
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
              <td>{agent.nom}</td>
              <td>{agent.posteNom}</td>
              <td>{agent.role}</td>
              <td>{agent.actif ? 'Actif' : 'Inactif'}</td>
              <td>
                <button
                  type="button"
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
    </main>
  );
}

export default AgentsPage;
