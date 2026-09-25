import type { TypeDocument } from '../pieces/types';

export interface RecherchePubliqueRequest {
  typeDocument: TypeDocument | null;
  nomTitulaire: string;
  prenomTitulaire: string | null;
  numeroDocument: string | null;
  dateNaissanceTitulaire: string | null;
}

export interface PosteResume {
  nom: string;
  adresse: string;
  horaires: string;
  telephone: string;
}

export interface RecherchePubliqueResponse {
  trouve: boolean;
  typeDocument: TypeDocument | null;
  poste: PosteResume | null;
  referenceDossier: string | null;
}

export interface CaptchaDefi {
  captchaToken: string;
  question: string;
}

export interface CaptchaReponsePayload {
  captchaToken: string;
  captchaReponse: string;
}

export interface CreerAlerteRequest {
  typeDocument: TypeDocument | null;
  nomTitulaire: string;
  prenomTitulaire: string | null;
  numeroDocument: string | null;
  dateNaissanceTitulaire: string | null;
  contact: string;
}

export interface CreerAlerteResponse {
  message: string;
}
