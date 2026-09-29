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

export interface LignePosteConsolidee {
  posteId: string;
  posteNom: string;
  regionNom: string;
  nombrePiecesEnAttente: number;
  ancienneteMoyenneJours: number | null;
  ancienneteMaxJours: number | null;
  nombrePiecesDepassantSeuil: number;
}

export interface TotauxConsolides {
  nombrePiecesEnAttente: number;
  ancienneteMoyenneJours: number | null;
  ancienneteMaxJours: number | null;
  nombrePiecesDepassantSeuil: number;
  nombrePostes: number;
  nombrePostesEnDepassement: number;
}

export interface StatistiquesConsolidees {
  portee: 'REGIONALE' | 'NATIONALE';
  regionId: string | null;
  regionNom: string | null;
  seuilAncienneteJours: number;
  totaux: TotauxConsolides;
  postes: LignePosteConsolidee[];
}
