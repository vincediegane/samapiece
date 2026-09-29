export const ROLES_GESTION_AGENTS = ['CHEF_POSTE', 'ADMIN_REGIONAL', 'ADMIN_NATIONAL'];

// Confort UX uniquement : le contrôle d'accès réel est le @PreAuthorize du backend.
export function peutGererAgents(role: string | null | undefined): boolean {
  return role != null && ROLES_GESTION_AGENTS.includes(role);
}
