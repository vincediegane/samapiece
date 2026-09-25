import type {
  CreerPieceRequest,
  DeblocageRequest,
  PieceResponse,
  RetraitRequest,
  SignalerRequest,
} from './types';

const BASE_URL = '/api/v1/pieces';

export class PieceApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
  ) {
    super(message);
    this.name = 'PieceApiError';
  }
}

function enTeteAutorisation(): HeadersInit {
  const jeton = window.localStorage.getItem('samapiece.accessToken') ?? '';
  return { Authorization: `Bearer ${jeton}`, 'Content-Type': 'application/json' };
}

export async function creerPiece(payload: CreerPieceRequest): Promise<PieceResponse> {
  const reponse = await fetch(BASE_URL, {
    method: 'POST',
    headers: enTeteAutorisation(),
    body: JSON.stringify(payload),
  });
  if (!reponse.ok) {
    if (reponse.status === 401) {
      throw new PieceApiError('Session expirée, reconnectez-vous.', 401);
    }
    if (reponse.status === 400) {
      throw new PieceApiError('Vérifiez les informations saisies.', 400);
    }
    throw new PieceApiError(`Erreur ${reponse.status}`, reponse.status);
  }
  return reponse.json();
}

export async function consulterPiece(id: string): Promise<PieceResponse> {
  const reponse = await fetch(`${BASE_URL}/${id}`, { headers: enTeteAutorisation() });
  if (!reponse.ok) {
    if (reponse.status === 401) {
      throw new PieceApiError('Session expirée, reconnectez-vous.', 401);
    }
    if (reponse.status === 403) {
      throw new PieceApiError("Cette pièce n'est pas dans votre périmètre.", 403);
    }
    if (reponse.status === 404) {
      throw new PieceApiError('Pièce introuvable.', 404);
    }
    throw new PieceApiError(`Erreur ${reponse.status}`, reponse.status);
  }
  return reponse.json();
}

export async function retirerPiece(id: string, payload: RetraitRequest): Promise<PieceResponse> {
  const reponse = await fetch(`${BASE_URL}/${id}/retrait`, {
    method: 'POST',
    headers: enTeteAutorisation(),
    body: JSON.stringify(payload),
  });
  if (!reponse.ok) {
    if (reponse.status === 401) {
      throw new PieceApiError('Session expirée, reconnectez-vous.', 401);
    }
    if (reponse.status === 403) {
      throw new PieceApiError("Cette pièce n'est pas dans votre périmètre.", 403);
    }
    if (reponse.status === 404) {
      throw new PieceApiError('Pièce introuvable.', 404);
    }
    if (reponse.status === 409) {
      throw new PieceApiError(
        'Le statut de la pièce a changé entre-temps, rafraîchissez la fiche avant de réessayer.',
        409,
      );
    }
    if (reponse.status === 400) {
      throw new PieceApiError('Vérifiez les informations saisies.', 400);
    }
    throw new PieceApiError(`Erreur ${reponse.status}`, reponse.status);
  }
  return reponse.json();
}

export async function signalerPiece(
  id: string,
  payload: SignalerRequest,
): Promise<PieceResponse> {
  const reponse = await fetch(`${BASE_URL}/${id}/signaler`, {
    method: 'POST',
    headers: enTeteAutorisation(),
    body: JSON.stringify(payload),
  });
  if (!reponse.ok) {
    if (reponse.status === 401) {
      throw new PieceApiError('Session expirée, reconnectez-vous.', 401);
    }
    if (reponse.status === 403) {
      throw new PieceApiError("Cette pièce n'est pas dans votre périmètre.", 403);
    }
    if (reponse.status === 404) {
      throw new PieceApiError('Pièce introuvable.', 404);
    }
    if (reponse.status === 409) {
      throw new PieceApiError(
        'Le statut de la pièce a changé entre-temps, rafraîchissez la fiche avant de réessayer.',
        409,
      );
    }
    if (reponse.status === 400) {
      throw new PieceApiError('Vérifiez les informations saisies.', 400);
    }
    throw new PieceApiError(`Erreur ${reponse.status}`, reponse.status);
  }
  return reponse.json();
}

export async function debloquerPiece(
  id: string,
  payload: DeblocageRequest,
): Promise<PieceResponse> {
  const reponse = await fetch(`${BASE_URL}/${id}/debloquer`, {
    method: 'POST',
    headers: enTeteAutorisation(),
    body: JSON.stringify(payload),
  });
  if (!reponse.ok) {
    if (reponse.status === 401) {
      throw new PieceApiError('Session expirée, reconnectez-vous.', 401);
    }
    if (reponse.status === 403) {
      throw new PieceApiError(
        "Cette pièce n'est pas dans votre périmètre (poste/région).",
        403,
      );
    }
    if (reponse.status === 404) {
      throw new PieceApiError('Pièce introuvable.', 404);
    }
    if (reponse.status === 409) {
      throw new PieceApiError(
        'Le statut de la pièce a changé entre-temps, rafraîchissez la fiche avant de réessayer.',
        409,
      );
    }
    if (reponse.status === 400) {
      throw new PieceApiError('Vérifiez les informations saisies.', 400);
    }
    throw new PieceApiError(`Erreur ${reponse.status}`, reponse.status);
  }
  return reponse.json();
}

export async function telechargerRecu(id: string): Promise<{ blob: Blob; nomFichier: string }> {
  const jeton = window.localStorage.getItem('samapiece.accessToken') ?? '';
  const reponse = await fetch(`${BASE_URL}/${id}/recu`, {
    headers: { Authorization: `Bearer ${jeton}` },
  });
  if (!reponse.ok) {
    if (reponse.status === 401) {
      throw new PieceApiError('Session expirée, reconnectez-vous.', 401);
    }
    if (reponse.status === 403) {
      throw new PieceApiError("Cette pièce n'est pas dans votre périmètre.", 403);
    }
    if (reponse.status === 404) {
      throw new PieceApiError('Pièce introuvable.', 404);
    }
    throw new PieceApiError(`Erreur ${reponse.status}`, reponse.status);
  }
  const enTeteDisposition = reponse.headers.get('Content-Disposition');
  const correspondance = enTeteDisposition?.match(/filename="([^"]+)"/);
  const nomFichier = correspondance?.[1] ?? `recu-${id}.pdf`;
  return { blob: await reponse.blob(), nomFichier };
}
