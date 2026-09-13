export type Role = 'AGENT' | 'CHEF_POSTE' | 'ADMIN_REGIONAL' | 'ADMIN_NATIONAL' | 'AUDITEUR';

export interface Agent {
  id: string;
  matricule: string;
  nom: string;
  role: Role;
  posteId: string;
  posteNom: string;
  actif: boolean;
  creeLe: string;
}

export interface CreerAgentPayload {
  posteId: string;
  matricule: string;
  nom: string;
  role: Role;
}

export interface CreerAgentResultat extends Agent {
  motDePasseTemporaire: string;
}

export interface ModifierAgentPayload {
  nom?: string;
  posteId?: string;
}

export interface Poste {
  id: string;
  nom: string;
}
