import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import FileAttenteSynchronisation from './FileAttenteSynchronisation';
import type { FicheEnAttente } from '../../shared/offline/types';
import { listerFile, reessayerItem, reessayerTout } from '../../shared/offline/fileSynchronisation';

vi.mock('../../shared/offline/fileSynchronisation', () => ({
  listerFile: vi.fn(),
  reessayerItem: vi.fn(),
  reessayerTout: vi.fn(),
}));

const PAYLOAD_MOCK = {
  typeDocument: 'CNI',
  nomTitulaire: 'Diop',
  prenomTitulaire: 'Awa',
  numeroDocument: '1234567890',
  dateNaissanceTitulaire: null,
  dateDepot: '2026-01-15',
  etatDocument: null,
  remarques: null,
} as const;

function ficheMock(overrides: Partial<FicheEnAttente>): FicheEnAttente {
  return {
    id: 'id-1',
    payload: PAYLOAD_MOCK,
    statut: 'en_attente',
    tentatives: 0,
    creeLeLocal: '2026-01-15T10:00:00.000Z',
    derniereErreur: null,
    numeroFicheServeur: null,
    prochaineTentativeAuPlusTotLe: null,
    ...overrides,
  };
}

describe('FileAttenteSynchronisation', () => {
  it("ne rend rien quand la file est vide", async () => {
    vi.mocked(listerFile).mockResolvedValue([]);
    const { container } = render(<FileAttenteSynchronisation />);
    await waitFor(() => expect(listerFile).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it('affiche le libellé et le bouton Réessayer pour un item en échec réseau', async () => {
    vi.mocked(listerFile).mockResolvedValue([
      ficheMock({ statut: 'echec_reseau', derniereErreur: 'Échec réseau, nouvelle tentative automatique programmée.' }),
    ]);
    render(<FileAttenteSynchronisation />);

    expect(
      await screen.findByText(/Échec réseau — nouvelle tentative automatique programmée/),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Réessayer maintenant' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Réessayer tout maintenant' })).toBeInTheDocument();
  });

  it("n'affiche pas de bouton Réessayer pour un item synchronisé ou en conflit doublon", async () => {
    vi.mocked(listerFile).mockResolvedValue([
      ficheMock({ id: 'id-synchronise', statut: 'synchronise', numeroFicheServeur: 'PC-1' }),
      ficheMock({
        id: 'id-conflit',
        statut: 'conflit_doublon',
        derniereErreur: 'Conflit détecté (doublon potentiel) — nécessite une vérification manuelle au poste.',
      }),
    ]);
    render(<FileAttenteSynchronisation />);

    await screen.findByText(/Synchronisé — numéro de fiche : PC-1/);
    expect(screen.getByText(/Conflit détecté \(doublon potentiel\)/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Réessayer maintenant' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Réessayer tout maintenant' })).not.toBeInTheDocument();
  });

  it('appelle reessayerItem au clic sur le bouton par item et reessayerTout au clic global', async () => {
    vi.mocked(listerFile).mockResolvedValue([ficheMock({ statut: 'echec_definitif', derniereErreur: 'Erreur du serveur (code 400) — vérifiez les données ou contactez le support.' })]);
    const { default: userEvent } = await import('@testing-library/user-event');
    const utilisateur = userEvent.setup();
    render(<FileAttenteSynchronisation />);

    await utilisateur.click(await screen.findByRole('button', { name: 'Réessayer maintenant' }));
    expect(reessayerItem).toHaveBeenCalledWith('id-1');

    await utilisateur.click(screen.getByRole('button', { name: 'Réessayer tout maintenant' }));
    expect(reessayerTout).toHaveBeenCalled();
  });
});
