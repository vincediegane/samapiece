export interface StatistiquesPoste {
  posteId: string;
  posteNom: string;
  nombrePiecesEnAttente: number;
  ancienneteMoyenneJours: number | null;
  ancienneteMaxJours: number | null;
  seuilAncienneteJours: number;
  nombrePiecesDepassantSeuil: number;
}

export interface AgentCourant {
  id: string;
  matricule: string;
  nom: string;
  role: string;
  posteId: string;
  posteNom: string;
  actif: boolean;
  creeLe: string;
}
