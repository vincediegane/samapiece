const ROLES_VUE_MULTI_POSTE = ['ADMIN_REGIONAL', 'ADMIN_NATIONAL'];

// Confort UX uniquement : le contrôle d'accès réel est le @PreAuthorize du backend.
export function peutVoirVueMultiPoste(role: string | null | undefined): boolean {
  return role != null && ROLES_VUE_MULTI_POSTE.includes(role);
}
