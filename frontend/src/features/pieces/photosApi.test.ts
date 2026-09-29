import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  listerPhotos,
  PhotoApiError,
  telechargerPhoto,
  uploaderPhoto,
  validerPhoto,
} from './photosApi';

function reponseErreur(status: number, corps?: unknown): Response {
  return {
    ok: false,
    status,
    json: async () => {
      if (corps === undefined) throw new SyntaxError('corps non JSON');
      return corps;
    },
  } as unknown as Response;
}

function fichier(type: string, taille = 10): File {
  return new File([new Uint8Array(taille)], 'photo', { type });
}

beforeEach(() => {
  window.localStorage.setItem('samapiece.accessToken', 'jeton-factice');
  vi.stubGlobal('fetch', vi.fn());
});

describe('uploaderPhoto', () => {
  it('envoie un FormData avec type et fichier, sans Content-Type manuel', async () => {
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: true,
      status: 201,
      json: async () => ({ id: 'p1' }),
    } as Response);
    const f = fichier('image/jpeg');

    await uploaderPhoto('piece-1', 'VERSO', f);

    const [url, options] = vi.mocked(fetch).mock.calls[0];
    expect(url).toBe('/api/v1/pieces/piece-1/photos');
    expect(options?.method).toBe('POST');
    expect(options?.headers).toEqual({ Authorization: 'Bearer jeton-factice' });
    const corps = options?.body as FormData;
    expect(corps.get('type')).toBe('VERSO');
    expect(corps.get('fichier')).toBeInstanceOf(File);
  });

  it.each([
    [415, 'TYPE_FICHIER_NON_AUTORISE', 'Format non accepté : utilisez une image JPEG ou PNG.'],
    [413, 'FICHIER_TROP_VOLUMINEUX', 'La photo dépasse 10 Mo.'],
    [409, 'PHOTO_DEJA_EXISTANTE', 'Une photo de ce côté existe déjà pour cette fiche.'],
    [500, 'STOCKAGE_INDISPONIBLE', 'Stockage des photos indisponible, réessayez plus tard.'],
    [404, 'PIECE_INTROUVABLE', 'Pièce introuvable.'],
  ])('mappe %i / %s vers un message clair', async (status, code, message) => {
    vi.mocked(fetch).mockResolvedValueOnce(reponseErreur(status, { code, message: 'brut' }));

    const erreur = await uploaderPhoto('p', 'RECTO', fichier('image/jpeg')).catch((e) => e);

    expect(erreur).toBeInstanceOf(PhotoApiError);
    expect(erreur.message).toBe(message);
    expect(erreur.status).toBe(status);
    expect(erreur.code).toBe(code);
  });

  it.each([
    [401, 'Session expirée, reconnectez-vous.'],
    [403, "Cette pièce n'est pas dans votre périmètre."],
    [404, 'Pièce introuvable.'],
    [502, 'Erreur 502'],
  ])('replie sur le statut %i quand le corps n’est pas JSON', async (status, message) => {
    vi.mocked(fetch).mockResolvedValueOnce(reponseErreur(status));

    const erreur = await uploaderPhoto('p', 'RECTO', fichier('image/jpeg')).catch((e) => e);

    expect(erreur).toBeInstanceOf(PhotoApiError);
    expect(erreur.message).toBe(message);
    expect(erreur.code).toBeUndefined();
  });
});

describe('listerPhotos', () => {
  it('retourne la liste des métadonnées', async () => {
    const photos = [{ id: 'a', pieceId: 'p', type: 'RECTO' }];
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => photos,
    } as Response);

    await expect(listerPhotos('p')).resolves.toEqual(photos);
    expect(vi.mocked(fetch).mock.calls[0][0]).toBe('/api/v1/pieces/p/photos');
  });

  it('lève une PhotoApiError 403', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(reponseErreur(403));
    await expect(listerPhotos('p')).rejects.toMatchObject({ status: 403 });
  });
});

describe('telechargerPhoto', () => {
  it('retourne le blob', async () => {
    const blob = new Blob(['x']);
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: true,
      status: 200,
      blob: async () => blob,
    } as Response);

    await expect(telechargerPhoto('p', 'ph')).resolves.toBe(blob);
    expect(vi.mocked(fetch).mock.calls[0][0]).toBe('/api/v1/pieces/p/photos/ph');
  });
});

describe('validerPhoto', () => {
  it('accepte JPEG et PNG jusqu’à 10 Mo', () => {
    expect(validerPhoto(fichier('image/jpeg'))).toBeNull();
    expect(validerPhoto(fichier('image/png', 10 * 1024 * 1024))).toBeNull();
  });

  it('refuse un GIF', () => {
    expect(validerPhoto(fichier('image/gif'))).toBe(
      'Format non accepté : utilisez une image JPEG ou PNG.',
    );
  });

  it('refuse un fichier de plus de 10 Mo', () => {
    expect(validerPhoto(fichier('image/jpeg', 10 * 1024 * 1024 + 1))).toBe(
      'La photo dépasse 10 Mo.',
    );
  });
});
