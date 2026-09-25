import type {
  CaptchaDefi,
  CaptchaReponsePayload,
  CreerAlerteRequest,
  CreerAlerteResponse,
  RecherchePubliqueRequest,
  RecherchePubliqueResponse,
} from './types';

const BASE_URL = '/api/v1/recherche-publique';
const ALERTES_URL = '/api/v1/alertes';

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

export class AlerteApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code: string | null,
  ) {
    super(message);
    this.name = 'AlerteApiError';
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

export async function creerAlerte(payload: CreerAlerteRequest): Promise<CreerAlerteResponse> {
  const reponse = await fetch(ALERTES_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  if (!reponse.ok) {
    let code: string | null = null;
    let message = '';
    try {
      const corps = await reponse.json();
      code = corps.code ?? null;
      message = corps.message ?? '';
    } catch {
      // corps JSON absent ou invalide, message de repli utilisé ci-dessous
    }
    throw new AlerteApiError(
      message || "Erreur lors de la création de l'alerte.",
      reponse.status,
      code,
    );
  }
  return reponse.json();
}
