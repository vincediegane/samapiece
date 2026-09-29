import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  consulterPiece,
  debloquerPiece,
  listerPieces,
  PieceApiError,
  retirerPiece,
  signalerPiece,
  telechargerRecu,
} from './piecesApi';
import type { PieceResponse } from './types';

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
  window.localStorage.setItem('samapiece.accessToken', 'jeton-factice');
  vi.stubGlobal('fetch', vi.fn());
});

describe('consulterPiece', () => {
  it('renvoie la pièce sur succès avec le header Authorization', async () => {
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => PIECE_RESPONSE_MOCK,
    } as Response);

    const resultat = await consulterPiece('id-1');

    expect(resultat).toEqual(PIECE_RESPONSE_MOCK);
    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/pieces/id-1',
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer jeton-factice' }),
      }),
    );
  });

  it.each([
    [401, 'Session expirée, reconnectez-vous.'],
    [403, "Cette pièce n'est pas dans votre périmètre."],
    [404, 'Pièce introuvable.'],
  ])('rejette avec le message attendu sur %i', async (status, message) => {
    vi.mocked(fetch).mockResolvedValueOnce({ ok: false, status } as Response);

    await expect(consulterPiece('id-1')).rejects.toMatchObject({ message, status });
  });
});

describe('retirerPiece', () => {
  it('envoie le payload en POST et renvoie la pièce mise à jour', async () => {
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ({ ...PIECE_RESPONSE_MOCK, statut: 'RETIREE' }),
    } as Response);

    const resultat = await retirerPiece('id-1', {
      nomReclamant: 'Fall',
      pieceJustificativePresentee: 'CNI',
    });

    expect(resultat.statut).toBe('RETIREE');
    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/pieces/id-1/retrait',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ nomReclamant: 'Fall', pieceJustificativePresentee: 'CNI' }),
        headers: expect.objectContaining({ Authorization: 'Bearer jeton-factice' }),
      }),
    );
  });

  it.each([
    [401, 'Session expirée, reconnectez-vous.'],
    [403, "Cette pièce n'est pas dans votre périmètre."],
    [404, 'Pièce introuvable.'],
    [409, 'Le statut de la pièce a changé entre-temps, rafraîchissez la fiche avant de réessayer.'],
  ])('rejette avec le message attendu sur %i', async (status, message) => {
    vi.mocked(fetch).mockResolvedValueOnce({ ok: false, status } as Response);

    await expect(
      retirerPiece('id-1', { nomReclamant: 'Fall', pieceJustificativePresentee: 'CNI' }),
    ).rejects.toMatchObject({ message, status });
  });
});

describe('signalerPiece', () => {
  it('envoie le payload en POST et renvoie la pièce mise à jour', async () => {
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ({ ...PIECE_RESPONSE_MOCK, statut: 'LITIGE' }),
    } as Response);

    const resultat = await signalerPiece('id-1', { statutCible: 'LITIGE', motif: 'Fraude' });

    expect(resultat.statut).toBe('LITIGE');
    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/pieces/id-1/signaler',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ statutCible: 'LITIGE', motif: 'Fraude' }),
      }),
    );
  });

  it.each([
    [401, 'Session expirée, reconnectez-vous.'],
    [403, "Cette pièce n'est pas dans votre périmètre."],
    [404, 'Pièce introuvable.'],
    [409, 'Le statut de la pièce a changé entre-temps, rafraîchissez la fiche avant de réessayer.'],
  ])('rejette avec le message attendu sur %i', async (status, message) => {
    vi.mocked(fetch).mockResolvedValueOnce({ ok: false, status } as Response);

    await expect(
      signalerPiece('id-1', { statutCible: 'LITIGE', motif: 'Fraude' }),
    ).rejects.toMatchObject({ message, status });
  });
});

describe('debloquerPiece', () => {
  it('envoie le payload en POST et renvoie la pièce mise à jour', async () => {
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ({ ...PIECE_RESPONSE_MOCK, statut: 'DISPONIBLE' }),
    } as Response);

    const resultat = await debloquerPiece('id-1', { motif: 'Résolu' });

    expect(resultat.statut).toBe('DISPONIBLE');
    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/pieces/id-1/debloquer',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ motif: 'Résolu' }),
      }),
    );
  });

  it('rejette avec le message spécifique de périmètre étendu sur 403', async () => {
    vi.mocked(fetch).mockResolvedValueOnce({ ok: false, status: 403 } as Response);

    await expect(debloquerPiece('id-1', { motif: 'Résolu' })).rejects.toMatchObject({
      message: "Cette pièce n'est pas dans votre périmètre (poste/région).",
      status: 403,
    });
  });

  it.each([
    [401, 'Session expirée, reconnectez-vous.'],
    [404, 'Pièce introuvable.'],
    [409, 'Le statut de la pièce a changé entre-temps, rafraîchissez la fiche avant de réessayer.'],
  ])('rejette avec le message attendu sur %i', async (status, message) => {
    vi.mocked(fetch).mockResolvedValueOnce({ ok: false, status } as Response);

    await expect(debloquerPiece('id-1', { motif: 'Résolu' })).rejects.toMatchObject({
      message,
      status,
    });
  });
});

describe('telechargerRecu', () => {
  it('renvoie le blob et le nom de fichier extrait du header Content-Disposition', async () => {
    const blobFactice = new Blob(['pdf']);
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: true,
      status: 200,
      headers: new Headers({ 'Content-Disposition': 'inline; filename="PC-0001.pdf"' }),
      blob: async () => blobFactice,
    } as unknown as Response);

    const resultat = await telechargerRecu('id-1');

    expect(resultat.nomFichier).toBe('PC-0001.pdf');
    expect(resultat.blob).toBe(blobFactice);
    expect(fetch).toHaveBeenCalledWith('/api/v1/pieces/id-1/recu', {
      headers: { Authorization: 'Bearer jeton-factice' },
    });
  });

  it('utilise un nom de repli si le header Content-Disposition est absent', async () => {
    const blobFactice = new Blob(['pdf']);
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: true,
      status: 200,
      headers: new Headers(),
      blob: async () => blobFactice,
    } as unknown as Response);

    const resultat = await telechargerRecu('id-1');

    expect(resultat.nomFichier).toBe('recu-id-1.pdf');
  });

  it.each([
    [401, 'Session expirée, reconnectez-vous.'],
    [403, "Cette pièce n'est pas dans votre périmètre."],
    [404, 'Pièce introuvable.'],
  ])('rejette avec le message attendu sur %i', async (status, message) => {
    vi.mocked(fetch).mockResolvedValueOnce({ ok: false, status } as Response);

    await expect(telechargerRecu('id-1')).rejects.toMatchObject({ message, status });
  });
});

describe('PieceApiError', () => {
  it('expose le status et le message', () => {
    const erreur = new PieceApiError('message', 404);
    expect(erreur.status).toBe(404);
    expect(erreur.message).toBe('message');
    expect(erreur.name).toBe('PieceApiError');
  });
});

describe('listerPieces', () => {
  const PAGE = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 };

  it('omet le statut par défaut et envoie le header Authorization', async () => {
    vi.mocked(fetch).mockResolvedValueOnce({ ok: true, status: 200, json: async () => PAGE } as Response);

    const resultat = await listerPieces({ page: 0 });

    expect(resultat).toEqual(PAGE);
    const [url, options] = vi.mocked(fetch).mock.calls[0];
    expect(url).toBe('/api/v1/pieces?page=0&size=20');
    expect((options?.headers as Record<string, string>).Authorization).toBe('Bearer jeton-factice');
  });

  it('inclut le statut quand il est fourni', async () => {
    vi.mocked(fetch).mockResolvedValueOnce({ ok: true, status: 200, json: async () => PAGE } as Response);

    await listerPieces({ statut: 'RETIREE', page: 2, size: 10 });

    expect(vi.mocked(fetch).mock.calls[0][0]).toBe('/api/v1/pieces?page=2&size=10&statut=RETIREE');
  });

  it.each([
    [401, 'Session expirée, reconnectez-vous.'],
    [403, 'Accès refusé à la liste des pièces.'],
    [500, 'Erreur 500'],
  ])('rejette avec le message attendu sur %i', async (status, message) => {
    vi.mocked(fetch).mockResolvedValueOnce({ ok: false, status } as Response);

    await expect(listerPieces({ page: 0 })).rejects.toMatchObject({ message, status });
  });
});
