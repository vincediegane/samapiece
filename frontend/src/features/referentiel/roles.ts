// Confort UX uniquement : le contrôle d'accès réel est le @PreAuthorize du backend.
export function peutGererReferentiel(role: string | null | undefined): boolean {
  return role === 'ADMIN_NATIONAL';
}
