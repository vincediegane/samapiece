import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  enregistrerAccessToken,
  enregistrerSession,
  estSessionValide,
  lireRefreshToken,
  msAvantExpiration,
  viderSession,
} from './session';
import type { LoginResult, RefreshResult } from './types';

const LOGIN_RESULT_MOCK: LoginResult = {
  accessToken: 'access-1',
  refreshToken: 'refresh-1',
  expiresIn: 900,
  role: 'AGENT',
  nom: 'Diop Awa',
};

beforeEach(() => {
  window.localStorage.clear();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('enregistrerSession', () => {
  it('écrit les 3 clés avec le bon calcul d’expiration', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-01-01T10:00:00.000Z'));

    enregistrerSession(LOGIN_RESULT_MOCK);

    expect(window.localStorage.getItem('samapiece.accessToken')).toBe('access-1');
    expect(window.localStorage.getItem('samapiece.refreshToken')).toBe('refresh-1');
    expect(window.localStorage.getItem('samapiece.accessTokenExpiresAt')).toBe(
      String(new Date('2026-01-01T10:00:00.000Z').getTime() + 900_000),
    );
  });
});

describe('enregistrerAccessToken', () => {
  it('ne modifie pas le refreshToken existant', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-01-01T10:00:00.000Z'));
    enregistrerSession(LOGIN_RESULT_MOCK);

    vi.setSystemTime(new Date('2026-01-01T10:10:00.000Z'));
    const resultat: RefreshResult = { accessToken: 'access-2', expiresIn: 900 };
    enregistrerAccessToken(resultat);

    expect(window.localStorage.getItem('samapiece.accessToken')).toBe('access-2');
    expect(window.localStorage.getItem('samapiece.refreshToken')).toBe('refresh-1');
    expect(window.localStorage.getItem('samapiece.accessTokenExpiresAt')).toBe(
      String(new Date('2026-01-01T10:10:00.000Z').getTime() + 900_000),
    );
  });
});

describe('estSessionValide', () => {
  it('retourne false quand aucune clé n’est présente', () => {
    expect(estSessionValide()).toBe(false);
  });

  it('retourne false quand accessToken est absent', () => {
    window.localStorage.setItem('samapiece.accessTokenExpiresAt', String(Date.now() + 10_000));
    expect(estSessionValide()).toBe(false);
  });

  it('retourne false quand accessTokenExpiresAt est absent', () => {
    window.localStorage.setItem('samapiece.accessToken', 'access-1');
    expect(estSessionValide()).toBe(false);
  });

  it('retourne false quand accessTokenExpiresAt n’est pas un nombre valide', () => {
    window.localStorage.setItem('samapiece.accessToken', 'access-1');
    window.localStorage.setItem('samapiece.accessTokenExpiresAt', 'pas-un-nombre');
    expect(estSessionValide()).toBe(false);
  });

  it('retourne false quand le token est expiré', () => {
    window.localStorage.setItem('samapiece.accessToken', 'access-1');
    window.localStorage.setItem('samapiece.accessTokenExpiresAt', String(Date.now() - 1_000));
    expect(estSessionValide()).toBe(false);
  });

  it('retourne true quand le token est présent et non expiré', () => {
    window.localStorage.setItem('samapiece.accessToken', 'access-1');
    window.localStorage.setItem('samapiece.accessTokenExpiresAt', String(Date.now() + 10_000));
    expect(estSessionValide()).toBe(true);
  });
});

describe('lireRefreshToken', () => {
  it('retourne null quand absent', () => {
    expect(lireRefreshToken()).toBeNull();
  });

  it('retourne la valeur stockée', () => {
    window.localStorage.setItem('samapiece.refreshToken', 'refresh-1');
    expect(lireRefreshToken()).toBe('refresh-1');
  });
});

describe('msAvantExpiration', () => {
  it('retourne null quand la clé est absente', () => {
    expect(msAvantExpiration()).toBeNull();
  });

  it('retourne null quand la clé n’est pas un nombre valide', () => {
    window.localStorage.setItem('samapiece.accessTokenExpiresAt', 'pas-un-nombre');
    expect(msAvantExpiration()).toBeNull();
  });

  it('retourne la différence, potentiellement négative si déjà expiré', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-01-01T10:00:00.000Z'));
    window.localStorage.setItem(
      'samapiece.accessTokenExpiresAt',
      String(new Date('2026-01-01T10:00:00.000Z').getTime() - 5_000),
    );
    expect(msAvantExpiration()).toBe(-5_000);
  });
});

describe('viderSession', () => {
  it('supprime les 3 clés, idempotent si déjà absentes', () => {
    enregistrerSession(LOGIN_RESULT_MOCK);

    viderSession();

    expect(window.localStorage.getItem('samapiece.accessToken')).toBeNull();
    expect(window.localStorage.getItem('samapiece.refreshToken')).toBeNull();
    expect(window.localStorage.getItem('samapiece.accessTokenExpiresAt')).toBeNull();

    expect(() => viderSession()).not.toThrow();
  });
});
