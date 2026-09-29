import type { FiltresAudit, PageEvenementsAudit } from './types';

export class ErreurApiAudit extends Error {
  readonly statut: number;

  constructor(statut: number) {
    super(`Erreur ${statut}`);
    this.statut = statut;
  }
}

export async function listerEvenementsAudit(
  page: number,
  filtres: FiltresAudit,
  taille = 20,
): Promise<PageEvenementsAudit> {
  const parametres = new URLSearchParams({ page: String(page), size: String(taille) });
  if (filtres.action) parametres.set('action', filtres.action);
  if (filtres.entiteCible) parametres.set('entiteCible', filtres.entiteCible);
  const jeton = window.localStorage.getItem('samapiece.accessToken') ?? '';
  const reponse = await fetch(`/api/v1/audit/evenements?${parametres.toString()}`, {
    headers: { Authorization: `Bearer ${jeton}` },
  });
  if (!reponse.ok) throw new ErreurApiAudit(reponse.status);
  return reponse.json();
}
