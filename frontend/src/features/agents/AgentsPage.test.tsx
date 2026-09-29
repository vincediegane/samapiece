import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import AgentsPage from './AgentsPage';
import { creerAgent, ErreurApiAgents, listerAgents } from './agentsApi';
import { recupererAgentCourant } from '../dashboard/dashboardApi';
import type { AgentCourant } from '../dashboard/types';
import userEvent from '@testing-library/user-event';

vi.mock('../dashboard/dashboardApi', () => ({
  recupererAgentCourant: vi.fn(),
}));

vi.mock('./agentsApi', async (importOriginal) => {
  const original = await importOriginal<typeof import('./agentsApi')>();
  return {
    ...original,
    listerAgents: vi.fn(),
    creerAgent: vi.fn(),
    desactiverAgent: vi.fn(),
  };
});

const MESSAGE_DROITS =
  'Droits insuffisants : la gestion des comptes agents est réservée aux chefs de poste et aux administrateurs.';

const AGENT_COURANT_MOCK: AgentCourant = {
  id: 'agent-1',
  matricule: 'PN-2024-00001',
  nom: 'Diop Awa',
  role: 'CHEF_POSTE',
  posteId: 'poste-1',
  posteNom: 'Commissariat Central Dakar',
  actif: true,
  creeLe: '2026-01-15T10:00:00Z',
};

const fetchMock = vi.fn();

beforeEach(() => {
  vi.mocked(recupererAgentCourant).mockReset();
  vi.mocked(listerAgents).mockReset();
  vi.mocked(creerAgent).mockReset();
  fetchMock.mockReset();
  fetchMock.mockResolvedValue({ ok: true, json: async () => [] });
  vi.stubGlobal('fetch', fetchMock);
  vi.mocked(recupererAgentCourant).mockResolvedValue(AGENT_COURANT_MOCK);
  vi.mocked(listerAgents).mockResolvedValue([]);
});

describe('AgentsPage', () => {
  it.each(['AGENT', 'AUDITEUR'])(
    'affiche un message de droits insuffisants sans appel API pour %s',
    async (role) => {
      vi.mocked(recupererAgentCourant).mockResolvedValue({ ...AGENT_COURANT_MOCK, role });
      render(<AgentsPage />);

      const alerte = await screen.findByRole('alert');
      expect(alerte).toHaveTextContent(MESSAGE_DROITS);
      expect(listerAgents).not.toHaveBeenCalled();
      expect(fetchMock).not.toHaveBeenCalled();
      expect(screen.queryByText(/jeton|session/i)).not.toBeInTheDocument();
    },
  );

  it('affiche le message de droits si le rôle ne peut pas être déterminé', async () => {
    vi.mocked(recupererAgentCourant).mockRejectedValue(new Error('Erreur 500'));
    render(<AgentsPage />);

    expect(await screen.findByRole('alert')).toHaveTextContent(MESSAGE_DROITS);
    expect(listerAgents).not.toHaveBeenCalled();
  });

  it.each(['CHEF_POSTE', 'ADMIN_REGIONAL', 'ADMIN_NATIONAL'])(
    'charge et affiche les agents pour %s',
    async (role) => {
      vi.mocked(recupererAgentCourant).mockResolvedValue({ ...AGENT_COURANT_MOCK, role });
      vi.mocked(listerAgents).mockResolvedValue([
        {
          id: 'a1',
          matricule: 'PN-9999',
          nom: 'Fall Moussa',
          role: 'AGENT',
          posteId: 'poste-1',
          posteNom: 'Commissariat Central Dakar',
          actif: true,
          creeLe: '2026-01-15T10:00:00Z',
        },
      ]);
      render(<AgentsPage />);

      expect(await screen.findByText('PN-9999')).toBeInTheDocument();
      expect(listerAgents).toHaveBeenCalledTimes(1);
    },
  );

  it.each([
    [403, MESSAGE_DROITS],
    [401, 'Session expirée ou invalide, reconnectez-vous.'],
    [500, 'Impossible de charger les agents.'],
  ])('distingue l’erreur HTTP %i au chargement de la liste', async (statut, message) => {
    vi.mocked(listerAgents).mockRejectedValue(new ErreurApiAgents(statut));
    render(<AgentsPage />);

    const alerte = await screen.findByRole('alert');
    expect(alerte).toHaveTextContent(message);
    expect(screen.queryByText(/jeton absent ou expiré/)).not.toBeInTheDocument();
  });

  it('affiche le message backend quand la création échoue', async () => {
    fetchMock.mockResolvedValue({ ok: true, json: async () => [{ id: 'poste-1', nom: 'Dakar' }] });
    vi.mocked(creerAgent).mockRejectedValue(new ErreurApiAgents(409, 'Matricule déjà utilisé'));
    const utilisateur = userEvent.setup();
    render(<AgentsPage />);

    await utilisateur.type(await screen.findByLabelText('Matricule'), 'PN-1');
    await utilisateur.type(screen.getByLabelText('Nom'), 'Sow');
    await utilisateur.selectOptions(screen.getByLabelText('Poste'), 'poste-1');
    await utilisateur.click(screen.getByRole('button', { name: "Créer l'agent" }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Matricule déjà utilisé');
  });
});
