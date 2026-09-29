import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import EnregistrementPiecePage from './EnregistrementPiecePage';
import type { PieceResponse } from './types';
import { STATUT_PIECE_LABELS } from './types';
import { mettreEnFile } from '../../shared/offline/fileSynchronisation';
import { uploaderPhoto, PhotoApiError } from './photosApi';
import { recupererAgentCourant } from '../dashboard/dashboardApi';
import type { AgentCourant } from '../dashboard/types';

vi.mock('../../shared/offline/fileSynchronisation', () => ({
  mettreEnFile: vi.fn().mockResolvedValue({}),
  listerFile: vi.fn().mockResolvedValue([]),
  reessayerItem: vi.fn(),
  reessayerTout: vi.fn(),
  demarrerDeclencheurs: vi.fn(() => () => {}),
}));

vi.mock('./PhotosFiche', () => ({ default: () => null }));

vi.mock('./photosApi', async () => {
  const actual = await vi.importActual<typeof import('./photosApi')>('./photosApi');
  return { ...actual, uploaderPhoto: vi.fn() };
});

vi.mock('../dashboard/dashboardApi', () => ({
  recupererAgentCourant: vi.fn(),
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
  vi.mocked(recupererAgentCourant).mockResolvedValue(AGENT_COURANT_MOCK);
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

  it('affiche le badge de statut du reçu via STATUT_PIECE_LABELS', async () => {
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: true,
      status: 201,
      json: async () => PIECE_RESPONSE_MOCK,
    } as Response);

    render(<EnregistrementPiecePage />);
    const utilisateur = await remplirChampsRequis();
    await utilisateur.click(screen.getByRole('button', { name: 'Enregistrer la pièce' }));

    expect(await screen.findByText(STATUT_PIECE_LABELS.DISPONIBLE)).toBeInTheDocument();
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

describe('EnregistrementPiecePage — photo optionnelle', () => {
  const reponseCreationOk = {
    ok: true,
    status: 201,
    json: async () => PIECE_RESPONSE_MOCK,
  } as Response;

  function photoJpeg(): File {
    return new File(['x'], 'photo.jpg', { type: 'image/jpeg' });
  }

  beforeEach(() => {
    vi.mocked(uploaderPhoto).mockReset();
    vi.mocked(mettreEnFile).mockClear();
    URL.createObjectURL = vi.fn().mockReturnValue('blob:apercu');
    URL.revokeObjectURL = vi.fn();
  });

  it('affiche un aperçu à la sélection et le révoque au retrait', async () => {
    render(<EnregistrementPiecePage />);
    const utilisateur = userEvent.setup();

    await utilisateur.upload(screen.getByLabelText(/Photo du document/), photoJpeg());
    expect(await screen.findByAltText('Aperçu de la photo sélectionnée')).toBeInTheDocument();

    await utilisateur.click(screen.getByRole('button', { name: 'Retirer la photo' }));
    expect(screen.queryByAltText('Aperçu de la photo sélectionnée')).not.toBeInTheDocument();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:apercu');
  });

  it('rejette un GIF côté client avec un message', async () => {
    render(<EnregistrementPiecePage />);
    const utilisateur = userEvent.setup({ applyAccept: false });

    await utilisateur.upload(
      screen.getByLabelText(/Photo du document/),
      new File(['x'], 'a.gif', { type: 'image/gif' }),
    );

    expect(
      await screen.findByText('Format non accepté : utilisez une image JPEG ou PNG.'),
    ).toBeInTheDocument();
    expect(screen.queryByAltText('Aperçu de la photo sélectionnée')).not.toBeInTheDocument();
  });

  it('rejette un fichier de plus de 10 Mo côté client', async () => {
    render(<EnregistrementPiecePage />);
    const utilisateur = userEvent.setup();
    const gros = photoJpeg();
    Object.defineProperty(gros, 'size', { value: 10 * 1024 * 1024 + 1 });

    await utilisateur.upload(screen.getByLabelText(/Photo du document/), gros);

    expect(await screen.findByText('La photo dépasse 10 Mo.')).toBeInTheDocument();
  });

  it('sans photo, n’appelle jamais uploaderPhoto et affiche le reçu', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(reponseCreationOk);

    render(<EnregistrementPiecePage />);
    const utilisateur = await remplirChampsRequis();
    await utilisateur.click(screen.getByRole('button', { name: 'Enregistrer la pièce' }));

    expect(await screen.findByText(/PC-ABCDEF01-2026-00001/)).toBeInTheDocument();
    expect(uploaderPhoto).not.toHaveBeenCalled();
  });

  it('avec photo, appelle uploaderPhoto après creerPiece', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(reponseCreationOk);
    vi.mocked(uploaderPhoto).mockResolvedValueOnce({} as never);

    render(<EnregistrementPiecePage />);
    const utilisateur = await remplirChampsRequis();
    const fichier = photoJpeg();
    await utilisateur.upload(screen.getByLabelText(/Photo du document/), fichier);
    await utilisateur.click(screen.getByRole('button', { name: 'Enregistrer la pièce' }));

    await screen.findByText(/PC-ABCDEF01-2026-00001/);
    expect(uploaderPhoto).toHaveBeenCalledWith('id-1', 'RECTO', fichier);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('un échec photo (409) laisse le reçu et affiche une alerte sans relancer creerPiece', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(reponseCreationOk);
    vi.mocked(uploaderPhoto).mockRejectedValueOnce(
      new PhotoApiError(
        'Une photo de ce côté existe déjà pour cette fiche.',
        409,
        'PHOTO_DEJA_EXISTANTE',
      ),
    );

    render(<EnregistrementPiecePage />);
    const utilisateur = await remplirChampsRequis();
    await utilisateur.upload(screen.getByLabelText(/Photo du document/), photoJpeg());
    await utilisateur.click(screen.getByRole('button', { name: 'Enregistrer la pièce' }));

    expect(await screen.findByText(/PC-ABCDEF01-2026-00001/)).toBeInTheDocument();
    const alerte = await screen.findByRole('alert');
    expect(alerte).toHaveTextContent('Une photo de ce côté existe déjà pour cette fiche.');
    expect(alerte).toHaveTextContent('ajoutez la photo depuis la fiche');
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(mettreEnFile).not.toHaveBeenCalled();
  });

  it('hors connexion, met la fiche en file sans photo et l’indique', async () => {
    vi.mocked(fetch).mockRejectedValueOnce(new TypeError('Failed to fetch'));

    render(<EnregistrementPiecePage />);
    const utilisateur = await remplirChampsRequis();
    await utilisateur.upload(screen.getByLabelText(/Photo du document/), photoJpeg());
    await utilisateur.click(screen.getByRole('button', { name: 'Enregistrer la pièce' }));

    expect(await screen.findByText(/La photo n'a pas été conservée/)).toBeInTheDocument();
    expect(mettreEnFile).toHaveBeenCalledTimes(1);
    expect(vi.mocked(mettreEnFile).mock.calls[0][0]).not.toHaveProperty('photo');
    expect(uploaderPhoto).not.toHaveBeenCalled();
  });
});
