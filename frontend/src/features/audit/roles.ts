const ROLES_AUDIT = ['AUDITEUR', 'ADMIN_NATIONAL'];

// Confort UX uniquement : le contrôle d'accès réel est le @PreAuthorize du backend.
export function peutConsulterAudit(role: string | null | undefined): boolean {
  return role != null && ROLES_AUDIT.includes(role);
}
