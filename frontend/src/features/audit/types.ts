export interface EvenementAudit {
  id: string;
  acteurId: string | null;
  typeActeur: string;
  action: string;
  entiteCible: string;
  entiteCibleId: string | null;
  details: string | null;
  adresseIp: string | null;
  horodatage: string;
}

export interface PageEvenementsAudit {
  content: EvenementAudit[];
  number: number;
  totalPages: number;
  totalElements: number;
  size: number;
}

export interface FiltresAudit {
  action?: string;
  entiteCible?: string;
}
