import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import DashboardPage from './DashboardPage';
import type { AgentCourant, StatistiquesPoste } from './types';

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

const STATISTIQUES_MOCK: StatistiquesPoste = {
  posteId: 'poste-1',
  posteNom: 'Commissariat Central Dakar',
  nombrePiecesEnAttente: 2,
  ancienneteMoyenneJours: 115.0,
  ancienneteMaxJours: 200,
  seuilAncienneteJours: 180,
  nombrePiecesDepassantSeuil: 1,
};

const STATISTIQUES_SANS_PIECE_MOCK: StatistiquesPoste = {
  posteId: 'poste-1',
  posteNom: 'Commissariat Central Dakar',
  nombrePiecesEnAttente: 0,
  ancienneteMoyenneJours: null,
  ancienneteMaxJours: null,
  seuilAncienneteJours: 180,
  nombrePiecesDepassantSeuil: 0,
};

function mockerFetchAvecReponses(statistiques: StatistiquesPoste) {
  vi.mocked(fetch)
    .mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => AGENT_COURANT_MOCK,
    } as Response)
    .mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => statistiques,
    } as Response);
}

beforeEach(() => {
  window.localStorage.setItem('samapiece.accessToken', 'jeton-factice');
  vi.stubGlobal('fetch', vi.fn());
});

describe('DashboardPage', () => {
  it('affiche le poste, le nombre de pièces en attente et l\'ancienneté moyenne/max', async () => {
    mockerFetchAvecReponses(STATISTIQUES_MOCK);

    render(<DashboardPage />);

    expect(await screen.findByText(/Commissariat Central Dakar/)).toBeInTheDocument();
    expect(screen.getByText(/Pièces en attente : 2/)).toBeInTheDocument();
    expect(screen.getByText(/Ancienneté moyenne : 115/)).toBeInTheDocument();
    expect(screen.getByText(/Ancienneté maximale : 200/)).toBeInTheDocument();
  });

  it('affiche "Aucune pièce en attente" quand les anciennetés sont nulles', async () => {
    mockerFetchAvecReponses(STATISTIQUES_SANS_PIECE_MOCK);

    render(<DashboardPage />);

    expect(await screen.findByText('Aucune pièce en attente.')).toBeInTheDocument();
    expect(screen.queryByText(/NaN/)).not.toBeInTheDocument();
    expect(screen.queryByText(/null/)).not.toBeInTheDocument();
  });

  it('affiche un badge d\'alerte quand nombrePiecesDepassantSeuil > 0', async () => {
    mockerFetchAvecReponses(STATISTIQUES_MOCK);

    render(<DashboardPage />);

    const alerte = await screen.findByRole('alert');
    expect(alerte).toHaveTextContent('1 pièce(s) dépassent le seuil de 180 jours');
  });

  it('n\'affiche pas de badge d\'alerte quand nombrePiecesDepassantSeuil est 0', async () => {
    mockerFetchAvecReponses(STATISTIQUES_SANS_PIECE_MOCK);

    render(<DashboardPage />);

    await screen.findByText('Aucune pièce en attente.');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('affiche un message d\'erreur si le chargement échoue', async () => {
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: false,
      status: 401,
      json: async () => {
        throw new Error('ne doit pas être appelé');
      },
    } as unknown as Response);

    render(<DashboardPage />);

    expect(await screen.findByText('Impossible de charger les statistiques du poste.')).toBeInTheDocument();
  });
});
