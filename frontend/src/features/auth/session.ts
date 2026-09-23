import type { LoginResult, RefreshResult } from './types';

const CLE_ACCESS_TOKEN = 'samapiece.accessToken';
const CLE_REFRESH_TOKEN = 'samapiece.refreshToken';
const CLE_ACCESS_TOKEN_EXPIRES_AT = 'samapiece.accessTokenExpiresAt';

export function enregistrerSession(resultat: LoginResult): void {
  window.localStorage.setItem(CLE_ACCESS_TOKEN, resultat.accessToken);
  window.localStorage.setItem(CLE_REFRESH_TOKEN, resultat.refreshToken);
  window.localStorage.setItem(
    CLE_ACCESS_TOKEN_EXPIRES_AT,
    String(Date.now() + resultat.expiresIn * 1000),
  );
}

export function enregistrerAccessToken(resultat: RefreshResult): void {
  window.localStorage.setItem(CLE_ACCESS_TOKEN, resultat.accessToken);
  window.localStorage.setItem(
    CLE_ACCESS_TOKEN_EXPIRES_AT,
    String(Date.now() + resultat.expiresIn * 1000),
  );
}

export function viderSession(): void {
  window.localStorage.removeItem(CLE_ACCESS_TOKEN);
  window.localStorage.removeItem(CLE_REFRESH_TOKEN);
  window.localStorage.removeItem(CLE_ACCESS_TOKEN_EXPIRES_AT);
}

export function estSessionValide(): boolean {
  const accessToken = window.localStorage.getItem(CLE_ACCESS_TOKEN);
  const expiresAtBrut = window.localStorage.getItem(CLE_ACCESS_TOKEN_EXPIRES_AT);
  if (!accessToken || !expiresAtBrut) return false;
  const expiresAt = Number(expiresAtBrut);
  if (!Number.isFinite(expiresAt)) return false;
  return Date.now() < expiresAt;
}

export function lireRefreshToken(): string | null {
  return window.localStorage.getItem(CLE_REFRESH_TOKEN) || null;
}

export function msAvantExpiration(): number | null {
  const brut = window.localStorage.getItem(CLE_ACCESS_TOKEN_EXPIRES_AT);
  if (!brut) return null;
  const expiresAt = Number(brut);
  if (!Number.isFinite(expiresAt)) return null;
  return expiresAt - Date.now();
}
