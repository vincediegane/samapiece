import { describe, expect, it } from 'vitest';
import { peutVoirVueMultiPoste } from './roles';

describe('peutVoirVueMultiPoste', () => {
  it.each(['ADMIN_REGIONAL', 'ADMIN_NATIONAL'])('autorise %s', (role) => {
    expect(peutVoirVueMultiPoste(role)).toBe(true);
  });

  it.each(['AGENT', 'CHEF_POSTE', 'AUDITEUR', ''])('refuse %s', (role) => {
    expect(peutVoirVueMultiPoste(role)).toBe(false);
  });

  it('refuse null et undefined', () => {
    expect(peutVoirVueMultiPoste(null)).toBe(false);
    expect(peutVoirVueMultiPoste(undefined)).toBe(false);
  });
});
