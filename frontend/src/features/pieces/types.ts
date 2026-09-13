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
