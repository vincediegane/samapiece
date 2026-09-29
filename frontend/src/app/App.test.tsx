import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from './App';
import type { AgentCourant } from '../features/dashboard/types';
import { recupererAgentCourant } from '../features/dashboard/dashboardApi';

vi.mock('../features/dashboard/dashboardApi', () => ({
  recupererAgentCourant: vi.fn(),
  getStatistiquesPoste: vi.fn(),
}));

vi.mock('../shared/offline/fileSynchronisation', () => ({
  listerFile: vi.fn().mockResolvedValue([]),
  mettreEnFile: vi.fn().mockResolvedValue({}),
  reessayerItem: vi.fn(),
  reessayerTout: vi.fn(),
  demarrerDeclencheurs: vi.fn(() => () => {}),
}));

const AGENT_COURANT_MOCK: AgentCourant = {
  id: 'agent-1',
  matricule: 'PN-2024-00001',
  nom: 'Diop Awa',
  role: 'AGENT',
  posteId: 'poste-1',
  posteNom: 'Commissariat Central Dakar',
  actif: true,
  creeLe: '2026-01-15T10:00:00Z',
};

function clicSurEspaceAgent() {
  const boutons = screen.getAllByRole('button', { name: 'Espace agent' });
  return userEvent.setup().click(boutons[0]);
}

beforeEach(() => {
  window.localStorage.clear();
  vi.stubGlobal('fetch', vi.fn());
  vi.mocked(recupererAgentCourant).mockResolvedValue(AGENT_COURANT_MOCK);
});

describe('App — espace agent et session', () => {
  it('affiche LoginPage quand aucune session valide n’est présente', async () => {
    render(<App />);
    await clicSurEspaceAgent();

    expect(screen.getByLabelText('Matricule')).toBeInTheDocument();
    expect(screen.getByLabelText('Mot de passe')).toBeInTheDocument();
  });

  it('affiche directement l’espace agent quand une session valide existe', async () => {
    window.localStorage.setItem('samapiece.accessToken', 'access-1');
    window.localStorage.setItem('samapiece.refreshToken', 'refresh-1');
    window.localStorage.setItem('samapiece.accessTokenExpiresAt', String(Date.now() + 900_000));

    render(<App />);
    await clicSurEspaceAgent();

    expect(await screen.findByLabelText('Type de document')).toBeInTheDocument();
    expect(screen.queryByLabelText('Matricule')).not.toBeInTheDocument();
  });

  it('affiche ConsulterFichePage lors de la navigation vers l’onglet "fiche"', async () => {
    window.localStorage.setItem('samapiece.accessToken', 'access-1');
    window.localStorage.setItem('samapiece.refreshToken', 'refresh-1');
    window.localStorage.setItem('samapiece.accessTokenExpiresAt', String(Date.now() + 900_000));

    render(<App />);
    await clicSurEspaceAgent();
    await screen.findByLabelText('Type de document');

    const utilisateur = userEvent.setup();
    await utilisateur.click(screen.getByRole('button', { name: 'Ouvrir une fiche' }));

    expect(await screen.findByLabelText('Identifiant de la fiche (UUID)')).toBeInTheDocument();
  });

  it('affiche AuditPage pour un auditeur et refuse un agent sans appel audit', async () => {
    window.localStorage.setItem('samapiece.accessToken', 'access-1');
    window.localStorage.setItem('samapiece.refreshToken', 'refresh-1');
    window.localStorage.setItem('samapiece.accessTokenExpiresAt', String(Date.now() + 900_000));
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ content: [], number: 0, totalPages: 0, totalElements: 0, size: 20 }),
    });
    vi.stubGlobal('fetch', fetchMock);
    vi.mocked(recupererAgentCourant).mockResolvedValue({ ...AGENT_COURANT_MOCK, role: 'AUDITEUR' });

    render(<App />);
    await clicSurEspaceAgent();
    await userEvent.setup().click(await screen.findByRole('button', { name: "Journal d'audit" }));

    expect(await screen.findByText("Aucun événement d'audit")).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/audit/evenements?page=0&size=20'),
      expect.anything(),
    );
  });

  it('ramène à LoginPage après déconnexion depuis la sidebar agent', async () => {
    window.localStorage.setItem('samapiece.accessToken', 'access-1');
    window.localStorage.setItem('samapiece.refreshToken', 'refresh-1');
    window.localStorage.setItem('samapiece.accessTokenExpiresAt', String(Date.now() + 900_000));

    render(<App />);
    await clicSurEspaceAgent();

    const boutonDeconnexion = await screen.findByTitle('Déconnexion');
    await userEvent.setup().click(boutonDeconnexion);

    expect(await screen.findByLabelText('Matricule')).toBeInTheDocument();
    expect(window.localStorage.getItem('samapiece.accessToken')).toBeNull();
  });
});
