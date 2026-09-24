import type {
  CaptchaDefi,
  CaptchaReponsePayload,
  RecherchePubliqueRequest,
  RecherchePubliqueResponse,
} from './types';

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

export class CaptchaRequisApiError extends RecherchePubliqueApiError {
  constructor() {
    super('Vérification supplémentaire requise avant de poursuivre la recherche.', 428);
    this.name = 'CaptchaRequisApiError';
  }
}

export async function obtenirDefiCaptcha(): Promise<CaptchaDefi> {
  const reponse = await fetch(`${BASE_URL}/captcha`);
  if (!reponse.ok) {
    throw new RecherchePubliqueApiError(`Erreur ${reponse.status}`, reponse.status);
  }
  return reponse.json();
}

export async function rechercher(
  payload: RecherchePubliqueRequest,
  captcha?: CaptchaReponsePayload,
): Promise<RecherchePubliqueResponse> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (captcha) {
    headers['X-Captcha-Token'] = captcha.captchaToken;
    headers['X-Captcha-Reponse'] = captcha.captchaReponse;
  }
  const reponse = await fetch(BASE_URL, {
    method: 'POST',
    headers,
    body: JSON.stringify(payload),
  });
  if (!reponse.ok) {
    if (reponse.status === 428) {
      throw new CaptchaRequisApiError();
    }
    if (reponse.status === 400) {
      throw new RecherchePubliqueApiError('Vérifiez les critères de recherche saisis.', 400);
    }
    throw new RecherchePubliqueApiError(`Erreur ${reponse.status}`, reponse.status);
  }
  return reponse.json();
}
