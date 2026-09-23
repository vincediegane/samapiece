import { useEffect } from 'react';
import { refresh } from './authApi';
import {
  enregistrerAccessToken,
  lireRefreshToken,
  msAvantExpiration,
  viderSession,
} from './session';

interface UseRafraichissementSessionOptions {
  onSessionExpiree: () => void;
}

export const MARGE_RAFRAICHISSEMENT_MS = 30_000;

export function calculerDelaiRafraichissement(msAvantExpiration: number): number {
  return Math.max(msAvantExpiration - MARGE_RAFRAICHISSEMENT_MS, 0);
}

export function useRafraichissementSession(options: UseRafraichissementSessionOptions): void {
  useEffect(() => {
    let idCourant: number | undefined;

    function planifier() {
      const refreshToken = lireRefreshToken();
      if (!refreshToken) {
        viderSession();
        options.onSessionExpiree();
        return;
      }
      const delaiRestant = msAvantExpiration();
      if (delaiRestant === null) {
        viderSession();
        options.onSessionExpiree();
        return;
      }
      const delai = calculerDelaiRafraichissement(delaiRestant);
      idCourant = window.setTimeout(executerRafraichissement, delai);
    }

    async function executerRafraichissement() {
      const refreshToken = lireRefreshToken();
      if (!refreshToken) {
        viderSession();
        options.onSessionExpiree();
        return;
      }
      try {
        const resultat = await refresh(refreshToken);
        enregistrerAccessToken(resultat);
        planifier();
      } catch {
        viderSession();
        options.onSessionExpiree();
      }
    }

    planifier();
    return () => window.clearTimeout(idCourant);
  }, []);
}
