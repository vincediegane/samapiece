import { describe, expect, it, vi, beforeEach } from 'vitest';

vi.mock('@sentry/react', () => ({ init: vi.fn() }));

describe('initialiserSentry', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_SENTRY_DSN', '');
  });

  it('ne doit rien faire si VITE_SENTRY_DSN est absent', async () => {
    const Sentry = await import('@sentry/react');
    const { initialiserSentry } = await import('./sentry');
    initialiserSentry();
    expect(Sentry.init).not.toHaveBeenCalled();
  });
});
