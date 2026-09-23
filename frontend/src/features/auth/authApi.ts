import type { AuthErrorCode, LoginResult, RefreshResult } from './types';

const BASE_URL = '/api/v1/auth';

export class AuthApiError extends Error {
  constructor(
    message: string,
    public readonly code: AuthErrorCode,
    public readonly status: number,
  ) {
    super(message);
    this.name = 'AuthApiError';
  }
}

async function gererReponseEnErreur(reponse: Response): Promise<never> {
  if (reponse.status === 401 || reponse.status === 423) {
    const corps = await reponse.json();
    throw new AuthApiError(corps.message, corps.code as AuthErrorCode, reponse.status);
  }
  throw new AuthApiError('Une erreur est survenue, réessayez.', 'INCONNU', reponse.status);
}

export async function login(matricule: string, motDePasse: string): Promise<LoginResult> {
  const reponse = await fetch(`${BASE_URL}/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ matricule, motDePasse }),
  });
  if (!reponse.ok) await gererReponseEnErreur(reponse);
  return reponse.json();
}

export async function refresh(refreshToken: string): Promise<RefreshResult> {
  const reponse = await fetch(`${BASE_URL}/refresh`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken }),
  });
  if (!reponse.ok) await gererReponseEnErreur(reponse);
  return reponse.json();
}
