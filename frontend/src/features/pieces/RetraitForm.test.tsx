import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import RetraitForm from './RetraitForm';
import { retirerPiece, PieceApiError } from './piecesApi';
import type { PieceResponse } from './types';

vi.mock('./piecesApi', async () => {
  const actual = await vi.importActual<typeof import('./piecesApi')>('./piecesApi');
  return { ...actual, retirerPiece: vi.fn() };
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
  statut: 'RETIREE',
  remarques: null,
  creeLe: '2026-01-15T10:00:00Z',
};

beforeEach(() => {
  vi.mocked(retirerPiece).mockReset();
});

describe('RetraitForm', () => {
  it('affiche les deux champs requis', () => {
    render(<RetraitForm pieceId="id-1" onSucces={vi.fn()} onAnnuler={vi.fn()} />);
    expect(screen.getByLabelText('Nom du réclamant')).toBeInTheDocument();
    expect(screen.getByLabelText('Pièce justificative présentée')).toBeInTheDocument();
  });

  it('bloque la soumission et affiche un message si les champs sont vides', async () => {
    const utilisateur = userEvent.setup();
    render(<RetraitForm pieceId="id-1" onSucces={vi.fn()} onAnnuler={vi.fn()} />);

    await utilisateur.click(screen.getByRole('button', { name: 'Confirmer le retrait' }));

    expect(
      screen.getByText('Le nom du réclamant et la pièce justificative sont requis.'),
    ).toBeInTheDocument();
    expect(retirerPiece).not.toHaveBeenCalled();
  });

  it('appelle retirerPiece avec le bon payload et onSucces sur succès', async () => {
    vi.mocked(retirerPiece).mockResolvedValueOnce(PIECE_RESPONSE_MOCK);
    const onSucces = vi.fn();
    const utilisateur = userEvent.setup();
    render(<RetraitForm pieceId="id-1" onSucces={onSucces} onAnnuler={vi.fn()} />);

    await utilisateur.type(screen.getByLabelText('Nom du réclamant'), 'Fall');
    await utilisateur.type(screen.getByLabelText('Pièce justificative présentée'), 'CNI');
    await utilisateur.click(screen.getByRole('button', { name: 'Confirmer le retrait' }));

    expect(retirerPiece).toHaveBeenCalledWith('id-1', {
      nomReclamant: 'Fall',
      pieceJustificativePresentee: 'CNI',
    });
    await vi.waitFor(() => expect(onSucces).toHaveBeenCalledWith(PIECE_RESPONSE_MOCK));
  });

  it('affiche une erreur serveur si retirerPiece échoue', async () => {
    vi.mocked(retirerPiece).mockRejectedValueOnce(new PieceApiError('Pièce introuvable.', 404));
    const utilisateur = userEvent.setup();
    render(<RetraitForm pieceId="id-1" onSucces={vi.fn()} onAnnuler={vi.fn()} />);

    await utilisateur.type(screen.getByLabelText('Nom du réclamant'), 'Fall');
    await utilisateur.type(screen.getByLabelText('Pièce justificative présentée'), 'CNI');
    await utilisateur.click(screen.getByRole('button', { name: 'Confirmer le retrait' }));

    expect(await screen.findByText('Pièce introuvable.')).toBeInTheDocument();
  });

  it('appelle onAnnuler au clic sur Annuler', async () => {
    const onAnnuler = vi.fn();
    const utilisateur = userEvent.setup();
    render(<RetraitForm pieceId="id-1" onSucces={vi.fn()} onAnnuler={onAnnuler} />);

    await utilisateur.click(screen.getByRole('button', { name: 'Annuler' }));

    expect(onAnnuler).toHaveBeenCalledTimes(1);
  });
});
