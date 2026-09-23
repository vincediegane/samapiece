export interface LoginResult {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  role: string;
  nom: string;
}

export interface RefreshResult {
  accessToken: string;
  expiresIn: number;
}

export type AuthErrorCode = 'IDENTIFIANTS_INVALIDES' | 'COMPTE_VERROUILLE' | 'INCONNU';
