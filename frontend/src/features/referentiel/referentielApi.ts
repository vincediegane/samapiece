import type { CreerPostePayload, CreerRegionPayload, PosteCree, Region } from './types';

function enTeteAutorisation(): HeadersInit {
  const jeton = window.localStorage.getItem('samapiece.accessToken') ?? '';
  return { Authorization: `Bearer ${jeton}`, 'Content-Type': 'application/json' };
}

async function lireErreur(reponse: Response): Promise<Error> {
  const erreur = await reponse.json().catch(() => null);
  return new Error(erreur?.message ?? `Erreur ${reponse.status}`);
}

export async function listerRegions(): Promise<Region[]> {
  const reponse = await fetch('/api/v1/regions', { headers: enTeteAutorisation() });
  if (!reponse.ok) throw await lireErreur(reponse);
  return reponse.json();
}

export async function creerRegion(payload: CreerRegionPayload): Promise<Region> {
  const reponse = await fetch('/api/v1/regions', {
    method: 'POST',
    headers: enTeteAutorisation(),
    body: JSON.stringify(payload),
  });
  if (!reponse.ok) throw await lireErreur(reponse);
  return reponse.json();
}

export async function creerPoste(payload: CreerPostePayload): Promise<PosteCree> {
  const reponse = await fetch('/api/v1/postes', {
    method: 'POST',
    headers: enTeteAutorisation(),
    body: JSON.stringify(payload),
  });
  if (!reponse.ok) throw await lireErreur(reponse);
  return reponse.json();
}
