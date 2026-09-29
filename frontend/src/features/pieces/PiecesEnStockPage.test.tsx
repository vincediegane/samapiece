import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import PiecesEnStockPage from './PiecesEnStockPage';
import { listerPieces, PieceApiError } from './piecesApi';
import type { PageResponse, PieceListeItem } from './types';

vi.mock('./piecesApi', async () => {
  const actual = await vi.importActual<typeof import('./piecesApi')>('./piecesApi');
  return { ...actual, listerPieces: vi.fn() };
});

const PIECE_A: PieceListeItem = {
  id: 'id-a',
  numeroFiche: 'PC-ABCDEF01-2026-00001',
  typeDocument: 'CNI',
  statut: 'DISPONIBLE',
  dateDepot: '2026-01-15',
  ancienneteJours: 257,
  depasseSeuil: true,
};

const PIECE_B: PieceListeItem = {
  id: 'id-b',
  numeroFiche: 'PC-ABCDEF01-2026-00002',
  typeDocument: 'PASSEPORT',
  statut: 'RECLAMEE',
  dateDepot: '2026-09-01',
  ancienneteJours: 28,
  depasseSeuil: false,
};

function page(content: PieceListeItem[], number = 0, totalPages = 1): PageResponse<PieceListeItem> {
  return { content, totalElements: content.length, totalPages, number, size: 20 };
}

beforeEach(() => {
  vi.mocked(listerPieces).mockReset();
});

describe('PiecesEnStockPage', () => {
  it('affiche les pièces avec ancienneté et badge de dépassement', async () => {
    vi.mocked(listerPieces).mockResolvedValue(page([PIECE_A, PIECE_B]));
    render(<PiecesEnStockPage onOuvrirFiche={vi.fn()} />);

    expect(screen.getByText('Chargement…')).toBeInTheDocument();
    expect(await screen.findByText('PC-ABCDEF01-2026-00001')).toBeInTheDocument();
    expect(screen.getByText('PC-ABCDEF01-2026-00002')).toBeInTheDocument();
    expect(screen.getByText(/257 j/)).toBeInTheDocument();
    expect(screen.getAllByText('Dépasse le seuil')).toHaveLength(1);
    expect(listerPieces).toHaveBeenCalledWith({ statut: undefined, page: 0 });
  });

  it('appelle onOuvrirFiche avec l’id de la pièce', async () => {
    vi.mocked(listerPieces).mockResolvedValue(page([PIECE_A]));
    const onOuvrirFiche = vi.fn();
    const utilisateur = userEvent.setup();
    render(<PiecesEnStockPage onOuvrirFiche={onOuvrirFiche} />);

    await utilisateur.click(await screen.findByRole('button', { name: 'Ouvrir la fiche' }));

    expect(onOuvrirFiche).toHaveBeenCalledWith('id-a');
  });

  it('rappelle listerPieces avec le statut choisi et la page 0', async () => {
    vi.mocked(listerPieces).mockResolvedValue(page([PIECE_A]));
    const utilisateur = userEvent.setup();
    render(<PiecesEnStockPage onOuvrirFiche={vi.fn()} />);
    await screen.findByText('PC-ABCDEF01-2026-00001');

    await utilisateur.selectOptions(screen.getByLabelText('Statut'), 'RETIREE');

    expect(listerPieces).toHaveBeenLastCalledWith({ statut: 'RETIREE', page: 0 });
  });

  it('pagine avec Suivant et Précédent', async () => {
    vi.mocked(listerPieces)
      .mockResolvedValueOnce(page([PIECE_A], 0, 2))
      .mockResolvedValueOnce(page([PIECE_B], 1, 2))
      .mockResolvedValueOnce(page([PIECE_A], 0, 2));
    const utilisateur = userEvent.setup();
    render(<PiecesEnStockPage onOuvrirFiche={vi.fn()} />);
    await screen.findByText('PC-ABCDEF01-2026-00001');

    expect(screen.getByRole('button', { name: 'Précédent' })).toBeDisabled();
    await utilisateur.click(screen.getByRole('button', { name: 'Suivant' }));
    await screen.findByText('PC-ABCDEF01-2026-00002');
    expect(listerPieces).toHaveBeenLastCalledWith({ statut: undefined, page: 1 });
    expect(screen.getByText('Page 2 / 2')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Suivant' })).toBeDisabled();

    await utilisateur.click(screen.getByRole('button', { name: 'Précédent' }));
    await screen.findByText('PC-ABCDEF01-2026-00001');
    expect(listerPieces).toHaveBeenLastCalledWith({ statut: undefined, page: 0 });
  });

  it('affiche l’état vide', async () => {
    vi.mocked(listerPieces).mockResolvedValue(page([]));
    render(<PiecesEnStockPage onOuvrirFiche={vi.fn()} />);

    expect(await screen.findByText('Aucune pièce en stock.')).toBeInTheDocument();
  });

  it('affiche le message d’erreur de l’API', async () => {
    vi.mocked(listerPieces).mockRejectedValue(
      new PieceApiError('Accès refusé à la liste des pièces.', 403),
    );
    render(<PiecesEnStockPage onOuvrirFiche={vi.fn()} />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Accès refusé à la liste des pièces.');
  });

  it('affiche le message hors connexion sur erreur réseau', async () => {
    vi.mocked(listerPieces).mockRejectedValue(new TypeError('Failed to fetch'));
    render(<PiecesEnStockPage onOuvrirFiche={vi.fn()} />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Action impossible hors connexion.');
  });
});
