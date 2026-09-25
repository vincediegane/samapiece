import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import DeblocageForm from './DeblocageForm';
import { debloquerPiece, PieceApiError } from './piecesApi';
import type { PieceResponse } from './types';

vi.mock('./piecesApi', async () => {
  const actual = await vi.importActual<typeof import('./piecesApi')>('./piecesApi');
  return { ...actual, debloquerPiece: vi.fn() };
});

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

beforeEach(() => {
  vi.mocked(debloquerPiece).mockReset();
});

describe('DeblocageForm', () => {
  it('affiche le champ motif', () => {
    render(<DeblocageForm pieceId="id-1" onSucces={vi.fn()} onAnnuler={vi.fn()} />);
    expect(screen.getByLabelText('Motif')).toBeInTheDocument();
  });

  it('bloque la soumission et affiche un message si le motif est vide', async () => {
    const utilisateur = userEvent.setup();
    render(<DeblocageForm pieceId="id-1" onSucces={vi.fn()} onAnnuler={vi.fn()} />);

    await utilisateur.click(screen.getByRole('button', { name: 'Débloquer la pièce' }));

    expect(screen.getByText('Le motif est requis.')).toBeInTheDocument();
    expect(debloquerPiece).not.toHaveBeenCalled();
  });

  it('appelle debloquerPiece avec le bon payload et onSucces sur succès', async () => {
    vi.mocked(debloquerPiece).mockResolvedValueOnce(PIECE_RESPONSE_MOCK);
    const onSucces = vi.fn();
    const utilisateur = userEvent.setup();
    render(<DeblocageForm pieceId="id-1" onSucces={onSucces} onAnnuler={vi.fn()} />);

    await utilisateur.type(screen.getByLabelText('Motif'), 'Litige résolu');
    await utilisateur.click(screen.getByRole('button', { name: 'Débloquer la pièce' }));

    expect(debloquerPiece).toHaveBeenCalledWith('id-1', { motif: 'Litige résolu' });
    await vi.waitFor(() => expect(onSucces).toHaveBeenCalledWith(PIECE_RESPONSE_MOCK));
  });

  it('affiche une erreur serveur si debloquerPiece échoue', async () => {
    vi.mocked(debloquerPiece).mockRejectedValueOnce(
      new PieceApiError('Pièce introuvable.', 404),
    );
    const utilisateur = userEvent.setup();
    render(<DeblocageForm pieceId="id-1" onSucces={vi.fn()} onAnnuler={vi.fn()} />);

    await utilisateur.type(screen.getByLabelText('Motif'), 'Litige résolu');
    await utilisateur.click(screen.getByRole('button', { name: 'Débloquer la pièce' }));

    expect(await screen.findByText('Pièce introuvable.')).toBeInTheDocument();
  });

  it('appelle onAnnuler au clic sur Annuler', async () => {
    const onAnnuler = vi.fn();
    const utilisateur = userEvent.setup();
    render(<DeblocageForm pieceId="id-1" onSucces={vi.fn()} onAnnuler={onAnnuler} />);

    await utilisateur.click(screen.getByRole('button', { name: 'Annuler' }));

    expect(onAnnuler).toHaveBeenCalledTimes(1);
  });
});
