export interface Region {
  id: string;
  nom: string;
}

export interface CreerRegionPayload {
  nom: string;
}

export type TypePoste = 'POLICE' | 'GENDARMERIE';

export interface CreerPostePayload {
  regionId: string;
  nom: string;
  type: TypePoste;
  adresse: string;
  telephone: string;
  horaires: Record<string, unknown>;
  latitude?: number;
  longitude?: number;
}

export interface PosteCree {
  id: string;
  nom: string;
  type: string;
  adresse: string;
  telephone: string | null;
  horaires: string;
  latitude: number | null;
  longitude: number | null;
  region: Region;
}
