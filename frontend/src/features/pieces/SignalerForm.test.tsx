import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import SignalerForm from './SignalerForm';
import { signalerPiece, PieceApiError } from './piecesApi';
import type { PieceResponse } from './types';

vi.mock('./piecesApi', async () => {
  const actual = await vi.importActual<typeof import('./piecesApi')>('./piecesApi');
  return { ...actual, signalerPiece: vi.fn() };
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
  statut: 'LITIGE',
  remarques: null,
  creeLe: '2026-01-15T10:00:00Z',
};

beforeEach(() => {
  vi.mocked(signalerPiece).mockReset();
});

describe('SignalerForm', () => {
  it('affiche uniquement les options Litige et Signalée pour statutCible', () => {
    render(<SignalerForm pieceId="id-1" onSucces={vi.fn()} onAnnuler={vi.fn()} />);
    const select = screen.getByLabelText('Type de signalement') as HTMLSelectElement;
    const options = Array.from(select.options).map((o) => o.value);
    expect(options).toEqual(['LITIGE', 'SIGNALEE']);
  });

  it('bloque la soumission et affiche un message si le motif est vide', async () => {
    const utilisateur = userEvent.setup();
    render(<SignalerForm pieceId="id-1" onSucces={vi.fn()} onAnnuler={vi.fn()} />);

    await utilisateur.click(screen.getByRole('button', { name: 'Signaler la pièce' }));

    expect(screen.getByText('Le motif est requis.')).toBeInTheDocument();
    expect(signalerPiece).not.toHaveBeenCalled();
  });

  it('appelle signalerPiece avec le bon payload et onSucces sur succès', async () => {
    vi.mocked(signalerPiece).mockResolvedValueOnce(PIECE_RESPONSE_MOCK);
    const onSucces = vi.fn();
    const utilisateur = userEvent.setup();
    render(<SignalerForm pieceId="id-1" onSucces={onSucces} onAnnuler={vi.fn()} />);

    await utilisateur.selectOptions(screen.getByLabelText('Type de signalement'), 'SIGNALEE');
    await utilisateur.type(screen.getByLabelText('Motif'), 'Fraude suspectée');
    await utilisateur.click(screen.getByRole('button', { name: 'Signaler la pièce' }));

    expect(signalerPiece).toHaveBeenCalledWith('id-1', {
      statutCible: 'SIGNALEE',
      motif: 'Fraude suspectée',
    });
    await vi.waitFor(() => expect(onSucces).toHaveBeenCalledWith(PIECE_RESPONSE_MOCK));
  });

  it('affiche une erreur serveur si signalerPiece échoue', async () => {
    vi.mocked(signalerPiece).mockRejectedValueOnce(
      new PieceApiError('Pièce introuvable.', 404),
    );
    const utilisateur = userEvent.setup();
    render(<SignalerForm pieceId="id-1" onSucces={vi.fn()} onAnnuler={vi.fn()} />);

    await utilisateur.type(screen.getByLabelText('Motif'), 'Fraude suspectée');
    await utilisateur.click(screen.getByRole('button', { name: 'Signaler la pièce' }));

    expect(await screen.findByText('Pièce introuvable.')).toBeInTheDocument();
  });

  it('appelle onAnnuler au clic sur Annuler', async () => {
    const onAnnuler = vi.fn();
    const utilisateur = userEvent.setup();
    render(<SignalerForm pieceId="id-1" onSucces={vi.fn()} onAnnuler={onAnnuler} />);

    await utilisateur.click(screen.getByRole('button', { name: 'Annuler' }));

    expect(onAnnuler).toHaveBeenCalledTimes(1);
  });
});
