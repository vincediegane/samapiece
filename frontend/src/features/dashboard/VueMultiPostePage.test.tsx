import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import VueMultiPostePage from './VueMultiPostePage';
import type { AgentCourant, StatistiquesConsolidees } from './types';

function agent(role: string): AgentCourant {
  return {
    id: 'agent-1',
    matricule: 'PN-2024-00001',
    nom: 'Diop Awa',
    role,
    posteId: 'poste-1',
    posteNom: 'Commissariat Central Dakar',
    actif: true,
    creeLe: '2026-01-15T10:00:00Z',
  };
}

const REGIONALE: StatistiquesConsolidees = {
  portee: 'REGIONALE',
  regionId: 'region-1',
  regionNom: 'Dakar',
  seuilAncienneteJours: 180,
  totaux: {
    nombrePiecesEnAttente: 5,
    ancienneteMoyenneJours: 96.4,
    ancienneteMaxJours: 200,
    nombrePiecesDepassantSeuil: 1,
    nombrePostes: 2,
    nombrePostesEnDepassement: 1,
  },
  postes: [
    {
      posteId: 'p1',
      posteNom: 'Commissariat Central Dakar',
      regionNom: 'Dakar',
      nombrePiecesEnAttente: 2,
      ancienneteMoyenneJours: 115,
      ancienneteMaxJours: 200,
      nombrePiecesDepassantSeuil: 1,
    },
    {
      posteId: 'p2',
      posteNom: 'Gendarmerie Rufisque',
      regionNom: 'Dakar',
      nombrePiecesEnAttente: 0,
      ancienneteMoyenneJours: null,
      ancienneteMaxJours: null,
      nombrePiecesDepassantSeuil: 0,
    },
  ],
};

const NATIONALE: StatistiquesConsolidees = {
  ...REGIONALE,
  portee: 'NATIONALE',
  regionId: null,
  regionNom: null,
};

const SANS_DEPASSEMENT: StatistiquesConsolidees = {
  ...REGIONALE,
  totaux: { ...REGIONALE.totaux, nombrePiecesDepassantSeuil: 0, nombrePostesEnDepassement: 0 },
};

function reponse(corps: unknown, status = 200): Response {
  return { ok: status < 400, status, json: async () => corps } as Response;
}

function mockerFetch(role: string, statistiques: StatistiquesConsolidees) {
  vi.mocked(fetch)
    .mockResolvedValueOnce(reponse(agent(role)))
    .mockResolvedValueOnce(reponse(statistiques));
}

beforeEach(() => {
  window.localStorage.setItem('samapiece.accessToken', 'jeton-factice');
  vi.stubGlobal('fetch', vi.fn());
});

describe('VueMultiPostePage', () => {
  it('appelle /regionale pour ADMIN_REGIONAL sans colonne Région', async () => {
    mockerFetch('ADMIN_REGIONAL', REGIONALE);

    render(<VueMultiPostePage />);

    expect(await screen.findByRole('heading', { name: 'Vue multi-poste — Dakar' })).toBeInTheDocument();
    expect(vi.mocked(fetch).mock.calls[1][0]).toBe('/api/v1/statistiques/regionale');
    expect(screen.queryByRole('columnheader', { name: 'Région' })).not.toBeInTheDocument();
    expect(screen.getByText('Gendarmerie Rufisque')).toBeInTheDocument();
  });

  it('appelle /nationale pour ADMIN_NATIONAL avec colonne Région', async () => {
    mockerFetch('ADMIN_NATIONAL', NATIONALE);

    render(<VueMultiPostePage />);

    expect(await screen.findByRole('heading', { name: 'Vue multi-poste — National' })).toBeInTheDocument();
    expect(vi.mocked(fetch).mock.calls[1][0]).toBe('/api/v1/statistiques/nationale');
    expect(screen.getByRole('columnheader', { name: 'Région' })).toBeInTheDocument();
  });

  it('affiche la bannière d’alerte quand des pièces dépassent le seuil', async () => {
    mockerFetch('ADMIN_REGIONAL', REGIONALE);

    render(<VueMultiPostePage />);

    const alerte = await screen.findByRole('alert');
    expect(alerte).toHaveTextContent('1 pièce(s) dépassent le seuil de 180 jours dans 1 poste(s)');
  });

  it('n’affiche pas de bannière sans dépassement', async () => {
    mockerFetch('ADMIN_REGIONAL', SANS_DEPASSEMENT);

    render(<VueMultiPostePage />);

    await screen.findByText('Gendarmerie Rufisque');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('rend les valeurs nulles par un tiret', async () => {
    mockerFetch('ADMIN_REGIONAL', REGIONALE);

    render(<VueMultiPostePage />);

    const ligne = (await screen.findByText('Gendarmerie Rufisque')).closest('tr');
    expect(ligne).toHaveTextContent('——');
    expect(screen.queryByText(/null/)).not.toBeInTheDocument();
  });

  it('affiche l’état de chargement', () => {
    vi.mocked(fetch).mockReturnValue(new Promise(() => {}));

    render(<VueMultiPostePage />);

    expect(screen.getByRole('status')).toHaveTextContent('Chargement des statistiques…');
  });

  it('affiche une erreur générique quand le fetch échoue', async () => {
    vi.mocked(fetch).mockRejectedValue(new Error('réseau'));

    render(<VueMultiPostePage />);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Impossible de charger la vue multi-poste.',
    );
  });

  it('affiche l’accès refusé sur réponse 403', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(reponse(agent('ADMIN_REGIONAL')))
      .mockResolvedValueOnce(reponse({}, 403));

    render(<VueMultiPostePage />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Accès refusé');
  });

  it('affiche l’accès refusé sans appel statistiques pour le rôle AGENT', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(reponse(agent('AGENT')));

    render(<VueMultiPostePage />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Accès refusé');
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('affiche un message quand le périmètre est vide', async () => {
    mockerFetch('ADMIN_REGIONAL', {
      ...REGIONALE,
      totaux: { ...REGIONALE.totaux, nombrePostes: 0, nombrePiecesDepassantSeuil: 0 },
      postes: [],
    });

    render(<VueMultiPostePage />);

    expect(await screen.findByText('Aucun poste dans le périmètre.')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });
});
