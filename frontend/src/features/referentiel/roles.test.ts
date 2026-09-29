import { describe, expect, it } from 'vitest';
import { peutGererReferentiel } from './roles';

describe('peutGererReferentiel', () => {
  it('est vrai uniquement pour ADMIN_NATIONAL', () => {
    expect(peutGererReferentiel('ADMIN_NATIONAL')).toBe(true);
    for (const role of ['AGENT', 'CHEF_POSTE', 'ADMIN_REGIONAL', 'AUDITEUR']) {
      expect(peutGererReferentiel(role)).toBe(false);
    }
    expect(peutGererReferentiel(null)).toBe(false);
    expect(peutGererReferentiel(undefined)).toBe(false);
  });
});
