import { describe, expect, it } from 'vitest';
import { peutGererAgents } from './roles';

describe('peutGererAgents', () => {
  it.each(['CHEF_POSTE', 'ADMIN_REGIONAL', 'ADMIN_NATIONAL'])('autorise %s', (role) => {
    expect(peutGererAgents(role)).toBe(true);
  });

  it.each(['AGENT', 'AUDITEUR', ''])('refuse %s', (role) => {
    expect(peutGererAgents(role)).toBe(false);
  });

  it('refuse null et undefined', () => {
    expect(peutGererAgents(null)).toBe(false);
    expect(peutGererAgents(undefined)).toBe(false);
  });
});
