import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import FichePieceCard from './FichePieceCard';
import { telechargerRecu } from './piecesApi';
import type { PieceResponse, StatutPiece } from './types';
import { STATUT_PIECE_COULEURS, STATUT_PIECE_LABELS } from './types';

vi.mock('./piecesApi', async () => {
  const actual = await vi.importActual<typeof import('./piecesApi')>('./piecesApi');
  return { ...actual, telechargerRecu: vi.fn() };
});

vi.mock('./RetraitForm', () => ({
  default: ({ onSucces }: { onSucces: (p: PieceResponse) => void }) => (
    <button
      type="button"
      onClick={() =>
        onSucces({
          id: 'id-1',
          numeroFiche: 'PC-ABCDEF01-2026-00001',
          posteId: 'poste-1',
          agentCreateurId: 'agent-1',
          typeDocument: 'CNI',
          nomTitulaire: 'Diop',
          prenomTitulaire: 'Awa',
          numeroDocumentMasque: '****1234',
          dateNaissanceTitulaire: null,
          dateDepot: '2026-01-15',
          etatDocument: null,
          statut: 'RETIREE',
          remarques: null,
          creeLe: '2026-01-15T10:00:00Z',
        })
      }
    >
      simuler-succes-retrait
    </button>
  ),
}));

const PIECE_RESPONSE_MOCK: PieceResponse = {
  id: 'id-1',
  numeroFiche: 'PC-ABCDEF01-2026-00001',
  posteId: 'poste-1',
  agentCreateurId: 'agent-1',
  typeDocument: 'CNI',
  nomTitulaire: 'Diop',
  prenomTitulaire: 'Awa',
  numeroDocumentMasque: '****1234',
  dateNaissanceTitulaire: null,
  dateDepot: '2026-01-15',
  etatDocument: null,
  statut: 'DISPONIBLE',
  remarques: null,
  creeLe: '2026-01-15T10:00:00Z',
};

function piece(statut: string): PieceResponse {
  return { ...PIECE_RESPONSE_MOCK, statut };
}

beforeEach(() => {
  vi.mocked(telechargerRecu).mockReset();
});

describe('FichePieceCard — badge de statut', () => {
  it.each(Object.keys(STATUT_PIECE_LABELS) as StatutPiece[])(
    'affiche le libellé et la couleur pour le statut %s',
    (statut) => {
      render(
        <FichePieceCard
          piece={piece(statut)}
          roleAgentCourant="AGENT"
          onMisAJour={vi.fn()}
        />,
      );
      expect(screen.getByText(STATUT_PIECE_LABELS[statut])).toBeInTheDocument();
      const badge = document.querySelector(`.badge-dot.${STATUT_PIECE_COULEURS[statut]}`);
      expect(badge).not.toBeNull();
    },
  );
});

describe('FichePieceCard — gating retrait/signalement', () => {
  it.each(['DISPONIBLE', 'RECLAMEE'])('affiche les boutons retrait/signalement pour %s', (statut) => {
    render(<FichePieceCard piece={piece(statut)} roleAgentCourant="AGENT" onMisAJour={vi.fn()} />);
    expect(screen.getByRole('button', { name: 'Enregistrer un retrait' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Signaler cette pièce' })).toBeInTheDocument();
  });

  it.each(['RETIREE', 'LITIGE', 'ARCHIVEE', 'DETRUITE', 'SIGNALEE'])(
    'masque les boutons retrait/signalement pour %s',
    (statut) => {
      render(
        <FichePieceCard piece={piece(statut)} roleAgentCourant="AGENT" onMisAJour={vi.fn()} />,
      );
      expect(
        screen.queryByRole('button', { name: 'Enregistrer un retrait' }),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByRole('button', { name: 'Signaler cette pièce' }),
      ).not.toBeInTheDocument();
    },
  );
});

describe('FichePieceCard — gating déblocage', () => {
  it.each(['RETIREE', 'ARCHIVEE', 'LITIGE', 'SIGNALEE'])(
    'affiche le bouton déblocage pour CHEF_POSTE et statut %s',
    (statut) => {
      render(
        <FichePieceCard
          piece={piece(statut)}
          roleAgentCourant="CHEF_POSTE"
          onMisAJour={vi.fn()}
        />,
      );
      expect(screen.getByRole('button', { name: 'Débloquer' })).toBeInTheDocument();
    },
  );

  it.each(['AGENT', 'ADMIN_REGIONAL', 'ADMIN_NATIONAL'])(
    'masque le bouton déblocage pour le rôle %s même à statut compatible',
    (role) => {
      render(
        <FichePieceCard piece={piece('RETIREE')} roleAgentCourant={role} onMisAJour={vi.fn()} />,
      );
      expect(screen.queryByRole('button', { name: 'Débloquer' })).not.toBeInTheDocument();
    },
  );

  it('masque le bouton déblocage pour CHEF_POSTE si le statut ne le permet pas', () => {
    render(
      <FichePieceCard
        piece={piece('DISPONIBLE')}
        roleAgentCourant="CHEF_POSTE"
        onMisAJour={vi.fn()}
      />,
    );
    expect(screen.queryByRole('button', { name: 'Débloquer' })).not.toBeInTheDocument();
  });
});

describe('FichePieceCard — téléchargement du reçu', () => {
  it('appelle telechargerRecu et déclenche la création du lien de téléchargement', async () => {
    const blobFactice = new Blob(['pdf']);
    vi.mocked(telechargerRecu).mockResolvedValueOnce({
      blob: blobFactice,
      nomFichier: 'PC-0001.pdf',
    });
    const urlCreee = 'blob:url-factice';
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn().mockReturnValue(urlCreee),
      revokeObjectURL: vi.fn(),
    });
    const clicSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

    const utilisateur = userEvent.setup();
    render(
      <FichePieceCard piece={PIECE_RESPONSE_MOCK} roleAgentCourant="AGENT" onMisAJour={vi.fn()} />,
    );
    await utilisateur.click(screen.getByRole('button', { name: /Télécharger le reçu/ }));

    expect(telechargerRecu).toHaveBeenCalledWith('id-1');
    expect(URL.createObjectURL).toHaveBeenCalledWith(blobFactice);
    expect(clicSpy).toHaveBeenCalled();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith(urlCreee);

    clicSpy.mockRestore();
  });
});

describe('FichePieceCard — mise à jour après succès formulaire', () => {
  it('appelle onMisAJour et met à jour le badge après succès du formulaire de retrait', async () => {
    const onMisAJour = vi.fn();
    const utilisateur = userEvent.setup();
    const { rerender } = render(
      <FichePieceCard piece={PIECE_RESPONSE_MOCK} roleAgentCourant="AGENT" onMisAJour={onMisAJour} />,
    );

    await utilisateur.click(screen.getByRole('button', { name: 'Enregistrer un retrait' }));
    await utilisateur.click(screen.getByRole('button', { name: 'simuler-succes-retrait' }));

    expect(onMisAJour).toHaveBeenCalledWith({ ...PIECE_RESPONSE_MOCK, statut: 'RETIREE' });

    rerender(
      <FichePieceCard
        piece={{ ...PIECE_RESPONSE_MOCK, statut: 'RETIREE' }}
        roleAgentCourant="AGENT"
        onMisAJour={onMisAJour}
      />,
    );
    expect(screen.getByText('Retirée')).toBeInTheDocument();
  });
});
