import type { CreerPieceRequest } from '../../features/pieces/types';

export type StatutFicheEnAttente =
  | 'en_attente'
  | 'en_cours'
  | 'echec_reseau'
  | 'conflit_doublon'
  | 'echec_definitif'
  | 'synchronise';

export interface FicheEnAttente {
  id: string;
  payload: CreerPieceRequest;
  statut: StatutFicheEnAttente;
  tentatives: number;
  creeLeLocal: string;
  derniereErreur: string | null;
  numeroFicheServeur: string | null;
  prochaineTentativeAuPlusTotLe: string | null;
}
