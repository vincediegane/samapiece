import 'fake-indexeddb/auto';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CreerPieceRequest, PieceResponse } from '../../features/pieces/types';

vi.mock('../../features/pieces/piecesApi', () => {
  class PieceApiError extends Error {
    constructor(
      message: string,
      public readonly status: number,
    ) {
      super(message);
      this.name = 'PieceApiError';
    }
  }
  return { creerPiece: vi.fn(), PieceApiError };
});

const PAYLOAD_MOCK: CreerPieceRequest = {
  typeDocument: 'CNI',
  nomTitulaire: 'Diop',
  prenomTitulaire: 'Awa',
  numeroDocument: '1234567890',
  dateNaissanceTitulaire: null,
  dateDepot: '2026-01-15',
  etatDocument: null,
  remarques: null,
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
  statut: 'EN_ATTENTE',
  remarques: null,
  creeLe: '2026-01-15T10:00:00Z',
};

let dbModule: typeof import('./db');
let fileSynchronisation: typeof import('./fileSynchronisation');
let piecesApiMock: typeof import('../../features/pieces/piecesApi');

async function supprimerBase() {
  await new Promise<void>((resolve, reject) => {
    const requete = indexedDB.deleteDatabase('samapiece-offline');
    requete.onsuccess = () => resolve();
    requete.onerror = () => reject(requete.error);
    requete.onblocked = () => resolve();
  });
}

beforeEach(async () => {
  vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });
  if (dbModule) {
    const base = await dbModule.ouvrirBase();
    base.close();
  }
  await supprimerBase();
  vi.resetModules();
  dbModule = await import('./db');
  fileSynchronisation = await import('./fileSynchronisation');
  piecesApiMock = await import('../../features/pieces/piecesApi');
});

afterEach(() => {
  vi.useRealTimers();
});

describe('mettreEnFile / listerFile', () => {
  it('crée un enregistrement en_attente avec le payload conservé sans transformation', async () => {
    const item = await fileSynchronisation.mettreEnFile(PAYLOAD_MOCK);

    expect(item.statut).toBe('en_attente');
    expect(item.tentatives).toBe(0);
    expect(item.id).toEqual(expect.any(String));
    expect(item.payload).toEqual(PAYLOAD_MOCK);
    expect(item.derniereErreur).toBeNull();
    expect(item.numeroFicheServeur).toBeNull();
    expect(item.prochaineTentativeAuPlusTotLe).toBeNull();

    const liste = await fileSynchronisation.listerFile();
    expect(liste).toHaveLength(1);
    expect(liste[0]).toEqual(item);
  });

  it('trie les enregistrements par creeLeLocal croissant', async () => {
    vi.setSystemTime(new Date('2026-01-01T10:00:00.000Z'));
    const item1 = await fileSynchronisation.mettreEnFile(PAYLOAD_MOCK);
    vi.setSystemTime(new Date('2026-01-01T10:05:00.000Z'));
    const item2 = await fileSynchronisation.mettreEnFile(PAYLOAD_MOCK);

    const liste = await fileSynchronisation.listerFile();
    expect(liste.map((item) => item.id)).toEqual([item1.id, item2.id]);
  });
});

describe('synchroniser', () => {
  it('succès : synchronise puis supprime immédiatement l’item d’IndexedDB', async () => {
    vi.mocked(piecesApiMock.creerPiece).mockResolvedValue(PIECE_RESPONSE_MOCK);
    await fileSynchronisation.mettreEnFile(PAYLOAD_MOCK);

    await fileSynchronisation.synchroniser();

    expect(piecesApiMock.creerPiece).toHaveBeenCalledWith(PAYLOAD_MOCK);
    const liste = await fileSynchronisation.listerFile();
    expect(liste).toHaveLength(0);
  });

  it('échec réseau puis retry avec backoff jusqu’au succès à la 4e tentative', async () => {
    vi.mocked(piecesApiMock.creerPiece)
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(PIECE_RESPONSE_MOCK);
    await fileSynchronisation.mettreEnFile(PAYLOAD_MOCK);

    await fileSynchronisation.synchroniser();
    let liste = await fileSynchronisation.listerFile();
    expect(liste[0].statut).toBe('echec_reseau');
    expect(liste[0].tentatives).toBe(1);
    expect(liste[0].prochaineTentativeAuPlusTotLe).toBe(new Date(Date.now() + 15_000).toISOString());

    await vi.advanceTimersByTimeAsync(15_000);
    await fileSynchronisation.synchroniser();
    liste = await fileSynchronisation.listerFile();
    expect(liste[0].statut).toBe('echec_reseau');
    expect(liste[0].tentatives).toBe(2);
    expect(liste[0].prochaineTentativeAuPlusTotLe).toBe(new Date(Date.now() + 60_000).toISOString());

    await vi.advanceTimersByTimeAsync(60_000);
    await fileSynchronisation.synchroniser();
    liste = await fileSynchronisation.listerFile();
    expect(liste[0].statut).toBe('echec_reseau');
    expect(liste[0].tentatives).toBe(3);
    expect(liste[0].prochaineTentativeAuPlusTotLe).toBe(new Date(Date.now() + 300_000).toISOString());

    await vi.advanceTimersByTimeAsync(300_000);
    await fileSynchronisation.synchroniser();
    liste = await fileSynchronisation.listerFile();
    expect(liste).toHaveLength(0);
  });

  it('passe en echec_definitif après épuisement des 4 tentatives réseau', async () => {
    vi.mocked(piecesApiMock.creerPiece).mockRejectedValue(new TypeError('Failed to fetch'));
    await fileSynchronisation.mettreEnFile(PAYLOAD_MOCK);

    await fileSynchronisation.synchroniser();
    await vi.advanceTimersByTimeAsync(15_000);
    await fileSynchronisation.synchroniser();
    await vi.advanceTimersByTimeAsync(60_000);
    await fileSynchronisation.synchroniser();
    await vi.advanceTimersByTimeAsync(300_000);
    await fileSynchronisation.synchroniser();

    const liste = await fileSynchronisation.listerFile();
    expect(liste[0].statut).toBe('echec_definitif');
    expect(liste[0].tentatives).toBe(4);
    expect(liste[0].prochaineTentativeAuPlusTotLe).toBeNull();

    vi.mocked(piecesApiMock.creerPiece).mockClear();
    await vi.advanceTimersByTimeAsync(600_000);
    await fileSynchronisation.synchroniser();
    expect(piecesApiMock.creerPiece).not.toHaveBeenCalled();
  });

  it('passe en conflit_doublon dès la première tentative sur 409, sans retry', async () => {
    vi.mocked(piecesApiMock.creerPiece).mockRejectedValue(
      new piecesApiMock.PieceApiError('Conflit', 409),
    );
    await fileSynchronisation.mettreEnFile(PAYLOAD_MOCK);

    await fileSynchronisation.synchroniser();
    const liste = await fileSynchronisation.listerFile();
    expect(liste[0].statut).toBe('conflit_doublon');
    expect(liste[0].derniereErreur).toBe(
      'Conflit détecté (doublon potentiel) — nécessite une vérification manuelle au poste.',
    );
    expect(liste[0].tentatives).toBe(1);

    vi.mocked(piecesApiMock.creerPiece).mockClear();
    await vi.advanceTimersByTimeAsync(600_000);
    await fileSynchronisation.synchroniser();
    expect(piecesApiMock.creerPiece).not.toHaveBeenCalled();
  });

  it('passe en echec_definitif immédiatement sur 401, sans retry', async () => {
    vi.mocked(piecesApiMock.creerPiece).mockRejectedValue(
      new piecesApiMock.PieceApiError('Session expirée', 401),
    );
    await fileSynchronisation.mettreEnFile(PAYLOAD_MOCK);

    await fileSynchronisation.synchroniser();
    const liste = await fileSynchronisation.listerFile();
    expect(liste[0].statut).toBe('echec_definitif');
    expect(liste[0].derniereErreur).toBe(
      'Session expirée : reconnectez-vous puis cliquez sur Réessayer.',
    );
    expect(liste[0].tentatives).toBe(1);

    vi.mocked(piecesApiMock.creerPiece).mockClear();
    await vi.advanceTimersByTimeAsync(600_000);
    await fileSynchronisation.synchroniser();
    expect(piecesApiMock.creerPiece).not.toHaveBeenCalled();
  });

  it('le verrou en mémoire empêche un double envoi lors d’appels concurrents', async () => {
    let debloquer: (() => void) | undefined;
    const appelControle = new Promise<void>((resolve) => {
      debloquer = resolve;
    });
    vi.mocked(piecesApiMock.creerPiece).mockImplementation(async () => {
      await appelControle;
      return PIECE_RESPONSE_MOCK;
    });
    await fileSynchronisation.mettreEnFile(PAYLOAD_MOCK);

    const synchro1 = fileSynchronisation.synchroniser();
    const synchro2 = fileSynchronisation.synchroniser();
    debloquer?.();
    await Promise.all([synchro1, synchro2]);

    expect(piecesApiMock.creerPiece).toHaveBeenCalledTimes(1);
  });
});

describe('reessayerItem', () => {
  it('réinitialise et relance immédiatement un item echec_definitif', async () => {
    vi.mocked(piecesApiMock.creerPiece).mockRejectedValueOnce(
      new piecesApiMock.PieceApiError('Session expirée', 401),
    );
    const item = await fileSynchronisation.mettreEnFile(PAYLOAD_MOCK);
    await fileSynchronisation.synchroniser();
    let liste = await fileSynchronisation.listerFile();
    expect(liste[0].statut).toBe('echec_definitif');

    vi.mocked(piecesApiMock.creerPiece).mockResolvedValueOnce(PIECE_RESPONSE_MOCK);
    await fileSynchronisation.reessayerItem(item.id);

    liste = await fileSynchronisation.listerFile();
    expect(liste).toHaveLength(0);
  });

  it('ne fait rien pour un item conflit_doublon', async () => {
    vi.mocked(piecesApiMock.creerPiece).mockRejectedValue(
      new piecesApiMock.PieceApiError('Conflit', 409),
    );
    const item = await fileSynchronisation.mettreEnFile(PAYLOAD_MOCK);
    await fileSynchronisation.synchroniser();

    vi.mocked(piecesApiMock.creerPiece).mockClear();
    await fileSynchronisation.reessayerItem(item.id);

    expect(piecesApiMock.creerPiece).not.toHaveBeenCalled();
    const liste = await fileSynchronisation.listerFile();
    expect(liste[0].statut).toBe('conflit_doublon');
  });
});
