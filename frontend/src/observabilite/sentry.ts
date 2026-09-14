import * as Sentry from '@sentry/react';

export function initialiserSentry(): void {
  const dsn = import.meta.env.VITE_SENTRY_DSN;
  if (!dsn) {
    return;
  }
  Sentry.init({ dsn, environment: import.meta.env.MODE });
}
