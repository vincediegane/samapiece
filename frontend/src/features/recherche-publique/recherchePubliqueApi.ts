import type { RecherchePubliqueRequest, RecherchePubliqueResponse } from './types';

const BASE_URL = '/api/v1/recherche-publique';

export class RecherchePubliqueApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
  ) {
    super(message);
    this.name = 'RecherchePubliqueApiError';
  }
}

export async function rechercher(
  payload: RecherchePubliqueRequest,
): Promise<RecherchePubliqueResponse> {
  const reponse = await fetch(BASE_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  if (!reponse.ok) {
    if (reponse.status === 400) {
      throw new RecherchePubliqueApiError('Vérifiez les critères de recherche saisis.', 400);
    }
    throw new RecherchePubliqueApiError(`Erreur ${reponse.status}`, reponse.status);
  }
  return reponse.json();
}
