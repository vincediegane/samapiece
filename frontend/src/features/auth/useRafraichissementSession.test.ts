import { describe, expect, it } from 'vitest';
import { MARGE_RAFRAICHISSEMENT_MS, calculerDelaiRafraichissement } from './useRafraichissementSession';

describe('calculerDelaiRafraichissement', () => {
  it('retourne msAvantExpiration - MARGE_RAFRAICHISSEMENT_MS quand positif', () => {
    expect(calculerDelaiRafraichissement(100_000)).toBe(100_000 - MARGE_RAFRAICHISSEMENT_MS);
  });

  it('retourne 0 quand msAvantExpiration est inférieur ou égal à la marge', () => {
    expect(calculerDelaiRafraichissement(MARGE_RAFRAICHISSEMENT_MS)).toBe(0);
    expect(calculerDelaiRafraichissement(10_000)).toBe(0);
  });

  it('retourne 0 quand msAvantExpiration est négatif (session déjà expirée)', () => {
    expect(calculerDelaiRafraichissement(-5_000)).toBe(0);
  });
});
