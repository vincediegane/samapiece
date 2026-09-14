import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import EnregistrementPiecePage from './EnregistrementPiecePage';
import type { PieceResponse } from './types';
import { mettreEnFile } from '../../shared/offline/fileSynchronisation';

vi.mock('../../shared/offline/fileSynchronisation', () => ({
  mettreEnFile: vi.fn().mockResolvedValue({}),
  listerFile: vi.fn().mockResolvedValue([]),
  reessayerItem: vi.fn(),
  reessayerTout: vi.fn(),
  demarrerDeclencheurs: vi.fn(() => () => {}),
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
  statut: 'EN_ATTENTE',
  remarques: null,
  creeLe: '2026-01-15T10:00:00Z',
};

async function remplirChampsRequis() {
  const utilisateur = userEvent.setup();
  await utilisateur.selectOptions(
    screen.getByLabelText('Type de document'),
    "Carte Nationale d'Identité (CNI)",
  );
  await utilisateur.type(screen.getByLabelText('Nom du titulaire'), 'Diop');
  await utilisateur.type(screen.getByLabelText('Prénom du titulaire'), 'Awa');
  await utilisateur.type(screen.getByLabelText('Numéro du document'), '1234567890');
  await utilisateur.type(screen.getByLabelText('Date de dépôt'), '2026-01-15');
  return utilisateur;
}

beforeEach(() => {
  window.localStorage.setItem('samapiece.accessToken', 'jeton-factice');
  vi.stubGlobal('fetch', vi.fn());
});

describe('EnregistrementPiecePage', () => {
  it('affiche les 8 champs du formulaire', () => {
    render(<EnregistrementPiecePage />);
    expect(screen.getByLabelText('Type de document')).toBeInTheDocument();
    expect(screen.getByLabelText('Nom du titulaire')).toBeInTheDocument();
    expect(screen.getByLabelText('Prénom du titulaire')).toBeInTheDocument();
    expect(screen.getByLabelText('Numéro du document')).toBeInTheDocument();
    expect(screen.getByLabelText('Date de naissance du titulaire')).toBeInTheDocument();
    expect(screen.getByLabelText('Date de dépôt')).toBeInTheDocument();
    expect(screen.getByLabelText('État du document')).toBeInTheDocument();
    expect(screen.getByLabelText('Remarques')).toBeInTheDocument();
  });

  it('bloque la soumission si typeDocument est manquant', async () => {
    const utilisateur = userEvent.setup();
    render(<EnregistrementPiecePage />);
    await utilisateur.type(screen.getByLabelText('Nom du titulaire'), 'Diop');
    await utilisateur.type(screen.getByLabelText('Prénom du titulaire'), 'Awa');
    await utilisateur.type(screen.getByLabelText('Numéro du document'), '1234567890');
    await utilisateur.type(screen.getByLabelText('Date de dépôt'), '2026-01-15');
    await utilisateur.click(screen.getByRole('button', { name: 'Enregistrer la pièce' }));

    expect(screen.getByText('Le type de document est requis.')).toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();
  });

  it('bloque la soumission si nomTitulaire est manquant', async () => {
    const utilisateur = userEvent.setup();
    render(<EnregistrementPiecePage />);
    await utilisateur.selectOptions(
      screen.getByLabelText('Type de document'),
      "Carte Nationale d'Identité (CNI)",
    );
    await utilisateur.type(screen.getByLabelText('Prénom du titulaire'), 'Awa');
    await utilisateur.type(screen.getByLabelText('Numéro du document'), '1234567890');
    await utilisateur.type(screen.getByLabelText('Date de dépôt'), '2026-01-15');
    await utilisateur.click(screen.getByRole('button', { name: 'Enregistrer la pièce' }));

    expect(screen.getByText('Le nom du titulaire est requis.')).toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();
  });

  it('bloque la soumission si prenomTitulaire est manquant', async () => {
    const utilisateur = userEvent.setup();
    render(<EnregistrementPiecePage />);
    await utilisateur.selectOptions(
      screen.getByLabelText('Type de document'),
      "Carte Nationale d'Identité (CNI)",
    );
    await utilisateur.type(screen.getByLabelText('Nom du titulaire'), 'Diop');
    await utilisateur.type(screen.getByLabelText('Numéro du document'), '1234567890');
    await utilisateur.type(screen.getByLabelText('Date de dépôt'), '2026-01-15');
    await utilisateur.click(screen.getByRole('button', { name: 'Enregistrer la pièce' }));

    expect(screen.getByText('Le prénom du titulaire est requis.')).toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();
  });

  it('bloque la soumission si numeroDocument est manquant', async () => {
    const utilisateur = userEvent.setup();
    render(<EnregistrementPiecePage />);
    await utilisateur.selectOptions(
      screen.getByLabelText('Type de document'),
      "Carte Nationale d'Identité (CNI)",
    );
    await utilisateur.type(screen.getByLabelText('Nom du titulaire'), 'Diop');
    await utilisateur.type(screen.getByLabelText('Prénom du titulaire'), 'Awa');
    await utilisateur.type(screen.getByLabelText('Date de dépôt'), '2026-01-15');
    await utilisateur.click(screen.getByRole('button', { name: 'Enregistrer la pièce' }));

    expect(screen.getByText('Le numéro du document est requis.')).toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();
  });

  it('bloque la soumission si dateDepot est manquant', async () => {
    const utilisateur = userEvent.setup();
    render(<EnregistrementPiecePage />);
    await utilisateur.selectOptions(
      screen.getByLabelText('Type de document'),
      "Carte Nationale d'Identité (CNI)",
    );
    await utilisateur.type(screen.getByLabelText('Nom du titulaire'), 'Diop');
    await utilisateur.type(screen.getByLabelText('Prénom du titulaire'), 'Awa');
    await utilisateur.type(screen.getByLabelText('Numéro du document'), '1234567890');
    await utilisateur.click(screen.getByRole('button', { name: 'Enregistrer la pièce' }));

    expect(screen.getByText('La date de dépôt est requise.')).toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();
  });

  it('affiche le reçu après soumission réussie', async () => {
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: true,
      status: 201,
      json: async () => PIECE_RESPONSE_MOCK,
    } as Response);

    render(<EnregistrementPiecePage />);
    const utilisateur = await remplirChampsRequis();
    await utilisateur.click(screen.getByRole('button', { name: 'Enregistrer la pièce' }));

    expect(await screen.findByText(/PC-ABCDEF01-2026-00001/)).toBeInTheDocument();
    expect(screen.getByLabelText('Nom du titulaire')).toHaveValue('');
  });

  it('affiche un message de session expirée sur 401', async () => {
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: false,
      status: 401,
      json: async () => {
        throw new Error('ne doit pas être appelé');
      },
    } as unknown as Response);

    render(<EnregistrementPiecePage />);
    const utilisateur = await remplirChampsRequis();
    await utilisateur.click(screen.getByRole('button', { name: 'Enregistrer la pièce' }));

    expect(await screen.findByText('Session expirée, reconnectez-vous.')).toBeInTheDocument();
  });

  it('affiche un message générique sur 400', async () => {
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: false,
      status: 400,
      json: async () => {
        throw new Error('ne doit pas être appelé');
      },
    } as unknown as Response);

    render(<EnregistrementPiecePage />);
    const utilisateur = await remplirChampsRequis();
    await utilisateur.click(screen.getByRole('button', { name: 'Enregistrer la pièce' }));

    expect(await screen.findByText('Vérifiez les informations saisies.')).toBeInTheDocument();
  });

  it('en cas d’échec réseau, met la fiche en file et réinitialise le formulaire', async () => {
    vi.mocked(fetch).mockRejectedValueOnce(new TypeError('Failed to fetch'));

    render(<EnregistrementPiecePage />);
    const utilisateur = await remplirChampsRequis();
    await utilisateur.click(screen.getByRole('button', { name: 'Enregistrer la pièce' }));

    await screen.findByText(
      'Pas de connexion : la fiche a été enregistrée localement, elle sera synchronisée automatiquement.',
    );
    expect(mettreEnFile).toHaveBeenCalledWith(
      expect.objectContaining({ nomTitulaire: 'Diop', prenomTitulaire: 'Awa' }),
    );
    expect(screen.getByLabelText('Nom du titulaire')).toHaveValue('');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
