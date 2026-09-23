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
});
