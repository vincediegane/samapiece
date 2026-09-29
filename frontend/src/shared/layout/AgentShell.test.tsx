import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AgentShell from './AgentShell';
import type { AgentCourant } from '../../features/dashboard/types';
import { recupererAgentCourant } from '../../features/dashboard/dashboardApi';

vi.mock('../../features/dashboard/dashboardApi', () => ({
  recupererAgentCourant: vi.fn(),
}));

vi.mock('../offline/fileSynchronisation', () => ({
  listerFile: vi.fn().mockResolvedValue([]),
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

beforeEach(() => {
  window.localStorage.setItem('samapiece.accessToken', 'access-1');
  window.localStorage.setItem('samapiece.refreshToken', 'refresh-1');
  window.localStorage.setItem('samapiece.accessTokenExpiresAt', String(Date.now() + 900_000));
  vi.mocked(recupererAgentCourant).mockResolvedValue(AGENT_COURANT_MOCK);
});

describe('AgentShell', () => {
  it('appelle onDeconnexion et vide la session au clic sur le bouton de déconnexion', async () => {
    const onDeconnexion = vi.fn();
    const utilisateur = userEvent.setup();
    render(
      <AgentShell
        actif="pieces"
        onNaviguer={vi.fn()}
        onRetourPublic={vi.fn()}
        onDeconnexion={onDeconnexion}
      >
        <div>contenu</div>
      </AgentShell>,
    );

    const boutonDeconnexion = await screen.findByTitle('Déconnexion');
    await utilisateur.click(boutonDeconnexion);

    expect(onDeconnexion).toHaveBeenCalledTimes(1);
    expect(window.localStorage.getItem('samapiece.accessToken')).toBeNull();
    expect(window.localStorage.getItem('samapiece.refreshToken')).toBeNull();
    expect(window.localStorage.getItem('samapiece.accessTokenExpiresAt')).toBeNull();
  });

  it.each(['AUDITEUR', 'ADMIN_NATIONAL'])(
    'affiche l’entrée "Journal d’audit" pour %s et navigue vers audit',
    async (role) => {
      vi.mocked(recupererAgentCourant).mockResolvedValue({ ...AGENT_COURANT_MOCK, role });
      const onNaviguer = vi.fn();
      const utilisateur = userEvent.setup();
      render(
        <AgentShell
          actif="pieces"
          onNaviguer={onNaviguer}
          onRetourPublic={vi.fn()}
          onDeconnexion={vi.fn()}
        >
          <div>contenu</div>
        </AgentShell>,
      );

      await utilisateur.click(await screen.findByRole('button', { name: "Journal d'audit" }));
      expect(onNaviguer).toHaveBeenCalledWith('audit');
    },
  );

  it.each(['AGENT', 'CHEF_POSTE'])('masque l’entrée "Journal d’audit" pour %s', async (role) => {
    vi.mocked(recupererAgentCourant).mockResolvedValue({ ...AGENT_COURANT_MOCK, role });
    render(
      <AgentShell
        actif="pieces"
        onNaviguer={vi.fn()}
        onRetourPublic={vi.fn()}
        onDeconnexion={vi.fn()}
      >
        <div>contenu</div>
      </AgentShell>,
    );

    await screen.findByText('Commissariat Central Dakar');
    expect(screen.queryByRole('button', { name: "Journal d'audit" })).not.toBeInTheDocument();
  });

  it('masque l’entrée "Journal d’audit" quand le rôle n’est pas chargé', async () => {
    vi.mocked(recupererAgentCourant).mockRejectedValue(new Error('Erreur 500'));
    render(
      <AgentShell
        actif="pieces"
        onNaviguer={vi.fn()}
        onRetourPublic={vi.fn()}
        onDeconnexion={vi.fn()}
      >
        <div>contenu</div>
      </AgentShell>,
    );

    await Promise.resolve();
    expect(screen.queryByRole('button', { name: "Journal d'audit" })).not.toBeInTheDocument();
  });

  it('affiche l’onglet "Référentiel" pour ADMIN_NATIONAL et navigue vers referentiel', async () => {
    vi.mocked(recupererAgentCourant).mockResolvedValue({
      ...AGENT_COURANT_MOCK,
      role: 'ADMIN_NATIONAL',
    });
    const onNaviguer = vi.fn();
    const utilisateur = userEvent.setup();
    render(
      <AgentShell
        actif="pieces"
        onNaviguer={onNaviguer}
        onRetourPublic={vi.fn()}
        onDeconnexion={vi.fn()}
      >
        <div>contenu</div>
      </AgentShell>,
    );

    await utilisateur.click(await screen.findByRole('button', { name: 'Référentiel' }));
    expect(onNaviguer).toHaveBeenCalledWith('referentiel');
  });

  it.each(['AGENT', 'CHEF_POSTE', 'ADMIN_REGIONAL', 'AUDITEUR'])(
    'masque l’onglet "Référentiel" pour %s',
    async (role) => {
      vi.mocked(recupererAgentCourant).mockResolvedValue({ ...AGENT_COURANT_MOCK, role });
      render(
        <AgentShell
          actif="pieces"
          onNaviguer={vi.fn()}
          onRetourPublic={vi.fn()}
          onDeconnexion={vi.fn()}
        >
          <div>contenu</div>
        </AgentShell>,
      );

      await screen.findByText('Commissariat Central Dakar');
      expect(screen.queryByRole('button', { name: 'Référentiel' })).not.toBeInTheDocument();
    },
  );

  it('masque l’onglet "Référentiel" quand le rôle n’est pas chargé', async () => {
    vi.mocked(recupererAgentCourant).mockRejectedValue(new Error('Erreur 500'));
    render(
      <AgentShell
        actif="pieces"
        onNaviguer={vi.fn()}
        onRetourPublic={vi.fn()}
        onDeconnexion={vi.fn()}
      >
        <div>contenu</div>
      </AgentShell>,
    );

    await Promise.resolve();
    expect(screen.queryByRole('button', { name: 'Référentiel' })).not.toBeInTheDocument();
  });

  it.each(['ADMIN_REGIONAL', 'ADMIN_NATIONAL'])(
    'affiche l’onglet "Vue multi-poste" pour %s et navigue vers vue-multi-poste',
    async (role) => {
      vi.mocked(recupererAgentCourant).mockResolvedValue({ ...AGENT_COURANT_MOCK, role });
      const onNaviguer = vi.fn();
      const utilisateur = userEvent.setup();
      render(
        <AgentShell
          actif="pieces"
          onNaviguer={onNaviguer}
          onRetourPublic={vi.fn()}
          onDeconnexion={vi.fn()}
        >
          <div>contenu</div>
        </AgentShell>,
      );

      await utilisateur.click(await screen.findByRole('button', { name: 'Vue multi-poste' }));
      expect(onNaviguer).toHaveBeenCalledWith('vue-multi-poste');
    },
  );

  it.each(['AGENT', 'CHEF_POSTE', 'AUDITEUR'])(
    'masque l’onglet "Vue multi-poste" pour %s',
    async (role) => {
      vi.mocked(recupererAgentCourant).mockResolvedValue({ ...AGENT_COURANT_MOCK, role });
      render(
        <AgentShell
          actif="pieces"
          onNaviguer={vi.fn()}
          onRetourPublic={vi.fn()}
          onDeconnexion={vi.fn()}
        >
          <div>contenu</div>
        </AgentShell>,
      );

      await screen.findByText('Commissariat Central Dakar');
      expect(screen.queryByRole('button', { name: 'Vue multi-poste' })).not.toBeInTheDocument();
    },
  );

  it('masque l’onglet "Vue multi-poste" quand le rôle n’est pas chargé', async () => {
    vi.mocked(recupererAgentCourant).mockRejectedValue(new Error('Erreur 500'));
    render(
      <AgentShell
        actif="pieces"
        onNaviguer={vi.fn()}
        onRetourPublic={vi.fn()}
        onDeconnexion={vi.fn()}
      >
        <div>contenu</div>
      </AgentShell>,
    );

    await Promise.resolve();
    expect(screen.queryByRole('button', { name: 'Vue multi-poste' })).not.toBeInTheDocument();
  });

  it('affiche le bouton "Ouvrir une fiche" et appelle onNaviguer(\'fiche\') au clic', async () => {
    const onNaviguer = vi.fn();
    const utilisateur = userEvent.setup();
    render(
      <AgentShell
        actif="pieces"
        onNaviguer={onNaviguer}
        onRetourPublic={vi.fn()}
        onDeconnexion={vi.fn()}
      >
        <div>contenu</div>
      </AgentShell>,
    );

    const bouton = screen.getByRole('button', { name: 'Ouvrir une fiche' });
    expect(bouton).toBeInTheDocument();
    await utilisateur.click(bouton);

    expect(onNaviguer).toHaveBeenCalledWith('fiche');
  });
});

describe('AgentShell - pièces en stock', () => {
  it('affiche le bouton "Pièces en stock" et appelle onNaviguer("stock") au clic', async () => {
    const onNaviguer = vi.fn();
    const utilisateur = userEvent.setup();
    render(
      <AgentShell
        actif="pieces"
        onNaviguer={onNaviguer}
        onRetourPublic={vi.fn()}
        onDeconnexion={vi.fn()}
      >
        <div>contenu</div>
      </AgentShell>,
    );

    await utilisateur.click(screen.getByRole('button', { name: 'Pièces en stock' }));

    expect(onNaviguer).toHaveBeenCalledWith('stock');
  });
});
