import type { PhotoMeta, TypePhoto } from './types';

const BASE_URL = '/api/v1/pieces';

export const TYPES_PHOTO_ACCEPTES = ['image/jpeg', 'image/png'];
export const TAILLE_MAX_PHOTO = 10 * 1024 * 1024;

export class PhotoApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code?: string,
  ) {
    super(message);
    this.name = 'PhotoApiError';
  }
}

const MESSAGES_PAR_CODE: Record<string, string> = {
  TYPE_FICHIER_NON_AUTORISE: 'Format non accepté : utilisez une image JPEG ou PNG.',
  FICHIER_TROP_VOLUMINEUX: 'La photo dépasse 10 Mo.',
  PHOTO_DEJA_EXISTANTE: 'Une photo de ce côté existe déjà pour cette fiche.',
  STOCKAGE_INDISPONIBLE: 'Stockage des photos indisponible, réessayez plus tard.',
  PIECE_INTROUVABLE: 'Pièce introuvable.',
};

const MESSAGES_PAR_STATUT: Record<number, string> = {
  401: 'Session expirée, reconnectez-vous.',
  403: "Cette pièce n'est pas dans votre périmètre.",
  404: 'Pièce introuvable.',
};

function enTeteBearer(): HeadersInit {
  const jeton = window.localStorage.getItem('samapiece.accessToken') ?? '';
  return { Authorization: `Bearer ${jeton}` };
}

async function construireErreur(reponse: Response): Promise<PhotoApiError> {
  let code: string | undefined;
  try {
    const corps = await reponse.json();
    if (corps && typeof corps.code === 'string') code = corps.code;
  } catch {
    code = undefined;
  }
  const message =
    (code && MESSAGES_PAR_CODE[code]) ??
    MESSAGES_PAR_STATUT[reponse.status] ??
    `Erreur ${reponse.status}`;
  return new PhotoApiError(message, reponse.status, code);
}

export function validerPhoto(fichier: File): string | null {
  if (!TYPES_PHOTO_ACCEPTES.includes(fichier.type)) {
    return MESSAGES_PAR_CODE.TYPE_FICHIER_NON_AUTORISE;
  }
  if (fichier.size > TAILLE_MAX_PHOTO) {
    return MESSAGES_PAR_CODE.FICHIER_TROP_VOLUMINEUX;
  }
  return null;
}

export async function uploaderPhoto(
  pieceId: string,
  type: TypePhoto,
  fichier: File,
): Promise<PhotoMeta> {
  const donnees = new FormData();
  donnees.append('type', type);
  donnees.append('fichier', fichier);
  const reponse = await fetch(`${BASE_URL}/${pieceId}/photos`, {
    method: 'POST',
    headers: enTeteBearer(),
    body: donnees,
  });
  if (!reponse.ok) throw await construireErreur(reponse);
  return reponse.json();
}

export async function listerPhotos(pieceId: string): Promise<PhotoMeta[]> {
  const reponse = await fetch(`${BASE_URL}/${pieceId}/photos`, { headers: enTeteBearer() });
  if (!reponse.ok) throw await construireErreur(reponse);
  return reponse.json();
}

export async function telechargerPhoto(pieceId: string, photoId: string): Promise<Blob> {
  const reponse = await fetch(`${BASE_URL}/${pieceId}/photos/${photoId}`, {
    headers: enTeteBearer(),
  });
  if (!reponse.ok) throw await construireErreur(reponse);
  return reponse.blob();
}
