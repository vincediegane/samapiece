export type TypeDocument =
  | 'CNI'
  | 'PASSEPORT'
  | 'PERMIS_CONDUIRE'
  | 'CARTE_ELECTEUR'
  | 'EXTRAIT_NAISSANCE'
  | 'CARTE_GRISE'
  | 'CARTE_CONSULAIRE'
  | 'AUTRE';

export const TYPE_DOCUMENT_LABELS: Record<TypeDocument, string> = {
  CNI: "Carte Nationale d'Identité (CNI)",
  PASSEPORT: 'Passeport',
  PERMIS_CONDUIRE: 'Permis de conduire',
  CARTE_ELECTEUR: "Carte d'électeur",
  EXTRAIT_NAISSANCE: 'Extrait de naissance',
  CARTE_GRISE: 'Carte grise',
  CARTE_CONSULAIRE: 'Carte consulaire',
  AUTRE: 'Autre',
};

export type EtatDocumentOption = 'Bon état' | 'Endommagé' | 'Illisible partiellement';

export const ETAT_DOCUMENT_OPTIONS: EtatDocumentOption[] = [
  'Bon état',
  'Endommagé',
  'Illisible partiellement',
];

export interface CreerPieceRequest {
  typeDocument: TypeDocument;
  nomTitulaire: string;
  prenomTitulaire: string;
  numeroDocument: string;
  dateNaissanceTitulaire: string | null;
  dateDepot: string;
  etatDocument: string | null;
  remarques: string | null;
}

export interface PieceResponse {
  id: string;
  numeroFiche: string;
  posteId: string;
  agentCreateurId: string;
  typeDocument: string;
  nomTitulaire: string;
  prenomTitulaire: string;
  numeroDocumentMasque: string;
  dateNaissanceTitulaire: string | null;
  dateDepot: string;
  etatDocument: string | null;
  statut: string;
  remarques: string | null;
  creeLe: string;
}

export type StatutPiece =
  | 'DISPONIBLE'
  | 'RECLAMEE'
  | 'RETIREE'
  | 'LITIGE'
  | 'ARCHIVEE'
  | 'DETRUITE'
  | 'SIGNALEE';

export const STATUT_PIECE_LABELS: Record<StatutPiece, string> = {
  DISPONIBLE: 'Disponible',
  RECLAMEE: 'Réclamée',
  RETIREE: 'Retirée',
  LITIGE: 'En litige',
  ARCHIVEE: 'Archivée',
  DETRUITE: 'Détruite',
  SIGNALEE: 'Signalée',
};

export const STATUT_PIECE_COULEURS: Record<StatutPiece, string> = {
  DISPONIBLE: 'bg-primary-500',
  RECLAMEE: 'bg-info-500',
  RETIREE: 'bg-slate-400',
  LITIGE: 'bg-danger-500',
  SIGNALEE: 'bg-accent-500',
  ARCHIVEE: 'bg-slate-600',
  DETRUITE: 'bg-slate-700',
};

export interface RetraitRequest {
  nomReclamant: string;
  pieceJustificativePresentee: string;
}

export type StatutCibleSignalement = 'LITIGE' | 'SIGNALEE';

export interface SignalerRequest {
  statutCible: StatutCibleSignalement;
  motif: string;
}

export interface DeblocageRequest {
  motif: string;
}
