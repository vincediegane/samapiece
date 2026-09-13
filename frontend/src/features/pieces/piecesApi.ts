import type { CreerPieceRequest, PieceResponse } from './types';

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
