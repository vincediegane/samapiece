import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ConsulterFichePage from './ConsulterFichePage';
import { consulterPiece, PieceApiError } from './piecesApi';
import { recupererAgentCourant } from '../dashboard/dashboardApi';
import type { PieceResponse } from './types';
import type { AgentCourant } from '../dashboard/types';

vi.mock('./piecesApi', async () => {
  const actual = await vi.importActual<typeof import('./piecesApi')>('./piecesApi');
  return { ...actual, consulterPiece: vi.fn() };
});

vi.mock('../dashboard/dashboardApi', () => ({
  recupererAgentCourant: vi.fn(),
}));

const PIECE_RESPONSE_MOCK: PieceResponse = {
  id: '11111111-1111-1111-1111-111111111111',
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
  vi.mocked(consulterPiece).mockReset();
  vi.mocked(recupererAgentCourant).mockReset();
  vi.mocked(recupererAgentCourant).mockResolvedValue(AGENT_COURANT_MOCK);
});

describe('ConsulterFichePage', () => {
  it('appelle consulterPiece et affiche FichePieceCard pour un UUID valide', async () => {
    vi.mocked(consulterPiece).mockResolvedValueOnce(PIECE_RESPONSE_MOCK);
    const utilisateur = userEvent.setup();
    render(<ConsulterFichePage />);

    await utilisateur.type(
      screen.getByLabelText('Identifiant de la fiche (UUID)'),
      PIECE_RESPONSE_MOCK.id,
    );
    await utilisateur.click(screen.getByRole('button', { name: 'Ouvrir la fiche' }));

    expect(consulterPiece).toHaveBeenCalledWith(PIECE_RESPONSE_MOCK.id);
    expect(await screen.findByText(/PC-ABCDEF01-2026-00001/)).toBeInTheDocument();
  });

  it('affiche un message client et n’appelle pas consulterPiece pour un UUID mal formé', async () => {
    const utilisateur = userEvent.setup();
    render(<ConsulterFichePage />);

    await utilisateur.type(screen.getByLabelText('Identifiant de la fiche (UUID)'), 'pas-un-uuid');
    await utilisateur.click(screen.getByRole('button', { name: 'Ouvrir la fiche' }));

    expect(
      screen.getByText("Format d'identifiant invalide (UUID attendu)."),
    ).toBeInTheDocument();
    expect(consulterPiece).not.toHaveBeenCalled();
  });

  it.each([
    [404, 'Pièce introuvable.'],
    [403, "Cette pièce n'est pas dans votre périmètre."],
    [401, 'Session expirée, reconnectez-vous.'],
  ])('affiche le message correspondant sur %i sans rendre FichePieceCard', async (status, message) => {
    vi.mocked(consulterPiece).mockRejectedValueOnce(new PieceApiError(message, status));
    const utilisateur = userEvent.setup();
    render(<ConsulterFichePage />);

    await utilisateur.type(
      screen.getByLabelText('Identifiant de la fiche (UUID)'),
      PIECE_RESPONSE_MOCK.id,
    );
    await utilisateur.click(screen.getByRole('button', { name: 'Ouvrir la fiche' }));

    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(screen.queryByText(/PC-ABCDEF01-2026-00001/)).not.toBeInTheDocument();
  });
});
